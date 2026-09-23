package com.traceability.portal;

import com.traceability.inventory.ShipmentLinkService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;

/**
 * Public returns portal (Step 4a): config + order lookup. Unauthenticated.
 *
 * Tenancy: every call first resolves the slug via hatch #14
 * ({@code resolve_tenant_by_portal_slug} — tenant id only, nothing else crosses the
 * boundary), then does ALL tenant work inside {@code TenantContext.runAs(tenantId, tx…)}
 * on the app_user datasource, so RLS scopes every query — the same shape as the Bosta
 * webhook (hatch #4 → runAs). Queries also filter tenant_id explicitly (defence in depth).
 *
 * Lookup never reveals which check failed: every failure is the same {@link Outcome#NOT_FOUND}.
 * The response carries no customer name, phone, email or address.
 *
 * Programmatic transactions (not @Transactional) so tests can construct this service on a
 * real app_user connection — see PortalLookupRlsTest.
 */
@Service
public class PortalService {

    public static final List<String> REASON_CODES =
        List.of("wrong_size", "damaged", "not_as_pictured", "wrong_item", "changed_mind", "other");

    static final int THROTTLE_MAX_FAILURES = 5;
    static final int THROTTLE_WINDOW_MINUTES = 60;

    public enum Outcome { SUCCESS, NOT_FOUND, THROTTLED }

    public record LookupResult(Outcome outcome, Map<String, Object> body) {
        static LookupResult notFound()  { return new LookupResult(Outcome.NOT_FOUND, null); }
        static LookupResult throttled() { return new LookupResult(Outcome.THROTTLED, null); }
    }

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final PortalTokenService  tokens;

    public PortalService(JdbcTemplate jdbc, PlatformTransactionManager txm, PortalTokenService tokens) {
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
        this.tokens = tokens;
    }

    /** Hatch #14. Null for an unknown or disabled slug. */
    public UUID resolveTenant(String slug) {
        if (slug == null || slug.isBlank()) return null;
        return jdbc.queryForObject("SELECT resolve_tenant_by_portal_slug(?)", UUID.class, slug);
    }

    /** Empty for an unknown/disabled slug. */
    public Optional<Map<String, Object>> config(String slug) {
        UUID tenantId = resolveTenant(slug);
        if (tenantId == null) return Optional.empty();
        return Optional.ofNullable(TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            Map<String, Object> t = jdbc.queryForMap(
                "SELECT name, customer_return_window_days FROM tenants WHERE id = ?", tenantId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("storeName", t.get("name"));
            body.put("returnWindowDays", t.get("customer_return_window_days"));
            body.put("reasonCodes", REASON_CODES);
            return body;
        })));
    }

    /** Empty for an unknown/disabled slug; otherwise SUCCESS / NOT_FOUND / THROTTLED. */
    public Optional<LookupResult> lookup(String slug, String orderNumber, String phone) {
        UUID tenantId = resolveTenant(slug);
        if (tenantId == null) return Optional.empty();
        return Optional.of(TenantContext.runAs(tenantId,
            () -> tx.execute(s -> lookupInTenant(tenantId, orderNumber, phone))));
    }

    private LookupResult lookupInTenant(UUID tenantId, String orderNumberRaw, String phoneRaw) {
        String raw      = orderNumberRaw == null ? "" : orderNumberRaw.replaceAll("\\s+", "");
        String stripped = raw.startsWith("#") ? raw.substring(1) : raw;
        String orderKey = stripped.toLowerCase(Locale.ROOT);

        // 1. Throttle first — counts failures only, per tenant + order key, last 60 min.
        //    A throttled call is NOT recorded: only real attempts count, so the lockout lifts
        //    60 minutes after the 5th real failure no matter how often the caller retries.
        Integer recentFailures = jdbc.queryForObject(
            "SELECT COUNT(*) FROM portal_lookup_attempts " +
            "WHERE tenant_id = ? AND order_key = ? AND success = false " +
            "  AND attempted_at > now() - (interval '1 minute' * ?)",
            Integer.class, tenantId, orderKey, THROTTLE_WINDOW_MINUTES);
        if (recentFailures != null && recentFailures >= THROTTLE_MAX_FAILURES) {
            return LookupResult.throttled();
        }

        Map<String, Object> order = findEligibleOrder(tenantId, raw, stripped, phoneRaw);
        if (order == null) {
            recordAttempt(tenantId, orderKey, false);
            return LookupResult.notFound();
        }

        UUID orderId = (UUID) order.get("id");
        List<Map<String, Object>> lines = returnableLines(tenantId, orderId);
        recordAttempt(tenantId, orderKey, true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",       tokens.issue(tenantId, orderId));
        body.put("orderNumber", order.get("number"));
        body.put("deliveredAt", ((Timestamp) order.get("delivered_at")).toInstant().toString());
        body.put("lines",       lines);
        return new LookupResult(Outcome.SUCCESS, body);
    }

    /**
     * The one order that matches number (raw / #-stripped / #-prefixed — same forms as the
     * Bosta businessReference matcher) AND phone (both sides canonicalised to 01XXXXXXXXX),
     * is not PII-redacted, and has a forward leg delivered within the return window.
     * Null on ANY failure, including ambiguity.
     */
    private Map<String, Object> findEligibleOrder(UUID tenantId, String raw, String stripped, String phoneRaw) {
        if (stripped.isEmpty()) return null;
        String phone = ShipmentLinkService.normalizePhone(phoneRaw);
        if (phone == null) return null;

        List<Map<String, Object>> matches = jdbc.queryForList(
            "SELECT o.id, o.number, o.pii_redacted_at FROM orders o " +
            "WHERE o.tenant_id = ? " +
            "  AND (o.number = ? OR o.number = ? OR o.number = ?) " +
            "  AND '0' || RIGHT(REGEXP_REPLACE(o.customer_phone, '[^0-9]', '', 'g'), 10) = ?",
            tenantId, raw, stripped, "#" + stripped, phone);
        if (matches.size() != 1) return null;
        Map<String, Object> order = matches.get(0);
        if (order.get("pii_redacted_at") != null) return null;

        List<Map<String, Object>> delivered = jdbc.queryForList(
            "SELECT s.delivered_at, " +
            "       (now() - s.delivered_at <= interval '1 day' * t.customer_return_window_days) AS in_window " +
            "FROM shipments s JOIN tenants t ON t.id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND s.shipment_leg = 'forward' " +
            "  AND s.delivered_at IS NOT NULL " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            tenantId, order.get("id"));
        if (delivered.isEmpty() || !Boolean.TRUE.equals(delivered.get(0).get("in_window"))) return null;

        Map<String, Object> result = new LinkedHashMap<>(order);
        result.put("delivered_at", delivered.get(0).get("delivered_at"));
        return result;
    }

    /**
     * Delivered pieces of this order grouped by variant. returnableQuantity = delivered pieces
     * not already in an active return_request_items row; 0 when the variant is non_returnable.
     */
    private List<Map<String, Object>> returnableLines(UUID tenantId, UUID orderId) {
        return jdbc.query(
            "SELECT v.id AS variant_id, pr.title AS product_title, v.title AS variant_title, " +
            "       pr.image_url, v.non_returnable, " +
            "       COUNT(p.id) AS delivered_qty, " +
            "       COUNT(p.id) FILTER (WHERE NOT EXISTS ( " +
            "           SELECT 1 FROM return_request_items rri " +
            "           WHERE rri.piece_id = p.id AND rri.tenant_id = p.tenant_id AND rri.active)) AS free_qty " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND p.current_order_id = ? AND p.status = 'delivered'::piece_status " +
            "GROUP BY v.id, pr.title, v.title, pr.image_url, v.non_returnable " +
            "ORDER BY pr.title, v.title, v.id",
            (rs, i) -> {
                boolean nonReturnable = rs.getBoolean("non_returnable");
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("variantId",          rs.getObject("variant_id", UUID.class).toString());
                line.put("productTitle",       rs.getString("product_title"));
                line.put("variantTitle",       rs.getString("variant_title"));
                line.put("imageUrl",           rs.getString("image_url"));
                line.put("deliveredQuantity",  rs.getInt("delivered_qty"));
                line.put("returnableQuantity", nonReturnable ? 0 : rs.getInt("free_qty"));
                line.put("nonReturnable",      nonReturnable);
                return line;
            },
            tenantId, orderId);
    }

    private void recordAttempt(UUID tenantId, String orderKey, boolean success) {
        jdbc.update(
            "INSERT INTO portal_lookup_attempts (tenant_id, order_key, success) VALUES (?, ?, ?)",
            tenantId, orderKey, success);
    }
}
