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
    private final PickupAreaService   pickupAreas;
    private final PickupBookingScheduler bookingScheduler;

    /** Without a scheduler (tests on an app_user connection): auto-approval never enqueues a booking. */
    public PortalService(JdbcTemplate jdbc, PlatformTransactionManager txm, PortalTokenService tokens) {
        this(jdbc, txm, tokens, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PortalService(JdbcTemplate jdbc, PlatformTransactionManager txm, PortalTokenService tokens,
                         PickupBookingScheduler bookingScheduler) {
        this.bookingScheduler = bookingScheduler;
        this.jdbc        = jdbc;
        this.tx          = new TransactionTemplate(txm);
        this.tokens      = tokens;
        // Built on the same JdbcTemplate (not injected) so a test that constructs this service
        // on an app_user connection gets pickup-area reads on that connection too.
        this.pickupAreas = new PickupAreaService(jdbc);
        this.requests    = new ReturnRequestLifecycle(jdbc);
    }

    /** Step 4d-1: request history, on this service's own JdbcTemplate. */
    private final ReturnRequestLifecycle requests;

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
                "SELECT name, customer_return_window_days, portal_auto_approve, " +
                "       portal_logo_url, portal_brand_color, portal_policy_text, portal_pickup_booking " +
                "FROM tenants WHERE id = ?", tenantId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("storeName", t.get("name"));
            body.put("returnWindowDays", t.get("customer_return_window_days"));
            body.put("reasonCodes", REASON_CODES);
            body.put("logoUrl", t.get("portal_logo_url"));
            body.put("brandColor", t.get("portal_brand_color"));
            body.put("policyText", t.get("portal_policy_text"));
            body.put("autoApprove", t.get("portal_auto_approve"));
            body.put("pickupBooking", t.get("portal_pickup_booking"));
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
        // Step 4c-2: the pickup area choice — only when this tenant books Bosta pickups and
        // the delivery city has pickup-available districts. City and district names only.
        body.put("pickup", pickupOffer(tenantId, orderId).map(a -> a.toJson(true)).orElse(null));
        return new LookupResult(Outcome.SUCCESS, body);
    }

    /**
     * The pickup-area choice for this order, or empty: booking off, delivery city unknown, or
     * no pickup-available district in it. Lookup and submission both use this, so submission
     * requires a district exactly when lookup offered the choice.
     */
    private Optional<PickupAreaService.CityAreas> pickupOffer(UUID tenantId, UUID orderId) {
        boolean booking = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, tenantId));
        return booking ? pickupAreas.forOrder(tenantId, orderId) : Optional.empty();
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

        Timestamp deliveredAt = deliveredWithinWindow(tenantId, (UUID) order.get("id"));
        if (deliveredAt == null) return null;

        Map<String, Object> result = new LinkedHashMap<>(order);
        result.put("delivered_at", deliveredAt);
        return result;
    }

    /**
     * The order's newest forward leg's delivered_at when it is within the tenant's return
     * window; null otherwise. Shared by lookup and submission (which re-checks from scratch).
     */
    private Timestamp deliveredWithinWindow(UUID tenantId, UUID orderId) {
        List<Map<String, Object>> delivered = jdbc.queryForList(
            "SELECT s.delivered_at, " +
            "       (now() - s.delivered_at <= interval '1 day' * t.customer_return_window_days) AS in_window " +
            "FROM shipments s JOIN tenants t ON t.id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND s.shipment_leg = 'forward' " +
            "  AND s.delivered_at IS NOT NULL " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            tenantId, orderId);
        if (delivered.isEmpty() || !Boolean.TRUE.equals(delivered.get(0).get("in_window"))) return null;
        return (Timestamp) delivered.get(0).get("delivered_at");
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

    // ── Step 4b: customer submission ─────────────────────────────────────────────

    public enum SubmitOutcome { CREATED, UNAUTHORIZED, INVALID, CONFLICT }

    public record SubmitLine(UUID variantId, Integer quantity, String reasonCode) {}

    /** districtId: the chosen pickup area — required when lookup offered one, ignored otherwise. */
    public record SubmitRequest(List<SubmitLine> lines, String email, String note, String districtId) {
        public SubmitRequest(List<SubmitLine> lines, String email, String note) {
            this(lines, email, note, null);
        }
    }

    public record SubmitResult(SubmitOutcome outcome, Map<String, Object> body) {}

    static final int NOTE_MAX = 300;
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O/1/I
    private static final int REFERENCE_LENGTH = 6;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final java.util.regex.Pattern EMAIL =
        java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String ACTIVE_PIECE_INDEX = "return_request_items_one_active_per_piece";

    /** Thrown inside the submission transaction to roll it back and answer 400. */
    private static final class InvalidSubmission extends RuntimeException {
        InvalidSubmission() { super(null, null, false, false); }
    }

    /**
     * POST /api/v1/portal/{slug}/requests. Empty for an unknown/disabled slug.
     *
     * The lookup token must verify (signature, expiry) AND carry this slug's tenant; the order
     * it names is re-checked from scratch (not redacted, delivered within the window) — nothing
     * from the lookup response is trusted. Each line binds specific pieces: delivered pieces of
     * that variant on that order, not in an active request item, oldest first (created_at, id).
     * Request + items are one transaction; a concurrent submission that grabbed the same piece
     * trips the one-active-item-per-piece index → CONFLICT. Auto-approve tenants get
     * status 'approved' (decided_by NULL = system) in the same transaction.
     * Email, note and phone are never logged.
     */
    public Optional<SubmitResult> submit(String slug, String bearerToken, SubmitRequest req) {
        UUID tenantId = resolveTenant(slug);
        if (tenantId == null) return Optional.empty();
        Optional<PortalTokenService.Claims> claims = tokens.verify(bearerToken, tenantId);
        if (claims.isEmpty()) return Optional.of(new SubmitResult(SubmitOutcome.UNAUTHORIZED, null));
        UUID orderId = claims.get().orderId();

        try {
            Map<String, Object> body = TenantContext.runAs(tenantId,
                () -> tx.execute(s -> submitInTenant(tenantId, orderId, req)));
            // Step 4c-3: the transaction above has committed. An auto-approved request of a tenant
            // that books Bosta pickups gets its booking job now (the client never sees the id).
            UUID bookRequestId = (UUID) body.remove("_bookRequestId");
            if (bookRequestId != null && bookingScheduler != null) bookingScheduler.enqueue(bookRequestId, tenantId);
            return Optional.of(new SubmitResult(SubmitOutcome.CREATED, body));
        } catch (InvalidSubmission e) {
            return Optional.of(new SubmitResult(SubmitOutcome.INVALID, null));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // A concurrent submission took one of these pieces first (one active item per piece).
            if (String.valueOf(e.getMessage()).contains(ACTIVE_PIECE_INDEX)) {
                return Optional.of(new SubmitResult(SubmitOutcome.CONFLICT, null));
            }
            throw e;
        }
    }

    private Map<String, Object> submitInTenant(UUID tenantId, UUID orderId, SubmitRequest req) {
        if (req == null || req.lines() == null || req.lines().isEmpty()) throw new InvalidSubmission();
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        String note  = req.note()  == null || req.note().isBlank()  ? null : req.note().trim();
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) throw new InvalidSubmission();
        if (note != null && note.length() > NOTE_MAX) throw new InvalidSubmission();

        // Re-check eligibility from scratch.
        List<Map<String, Object>> order = jdbc.queryForList(
            "SELECT id FROM orders WHERE id = ? AND tenant_id = ? AND pii_redacted_at IS NULL",
            orderId, tenantId);
        if (order.isEmpty() || deliveredWithinWindow(tenantId, orderId) == null) throw new InvalidSubmission();

        // Bind pieces line by line (the same variant may appear on two lines with different reasons).
        Set<String> bound = new HashSet<>();
        List<Object[]> items = new ArrayList<>();
        for (SubmitLine line : req.lines()) {
            if (line == null || line.variantId() == null || line.quantity() == null || line.quantity() < 1
                    || !REASON_CODES.contains(line.reasonCode())) {
                throw new InvalidSubmission();
            }
            List<String> free = jdbc.queryForList(
                "SELECT p.id FROM pieces p JOIN variants v ON v.id = p.variant_id " +
                "WHERE p.tenant_id = ? AND p.current_order_id = ? AND p.variant_id = ? " +
                "  AND p.status = 'delivered'::piece_status AND v.non_returnable = false " +
                "  AND NOT EXISTS (SELECT 1 FROM return_request_items rri " +
                "                  WHERE rri.piece_id = p.id AND rri.tenant_id = p.tenant_id AND rri.active) " +
                "ORDER BY p.created_at, p.id",
                String.class, tenantId, orderId, line.variantId());
            free.removeAll(bound);
            if (free.size() < line.quantity()) throw new InvalidSubmission();
            for (String pieceId : free.subList(0, line.quantity())) {
                bound.add(pieceId);
                items.add(new Object[]{pieceId, line.variantId(), line.reasonCode()});
            }
        }

        // Step 4c-2: the pickup area, re-derived from scratch (never trusted from the lookup).
        Optional<PickupAreaService.CityAreas> offer = pickupOffer(tenantId, orderId);
        PickupAreaService.District district = null;
        if (offer.isPresent()) {
            district = offer.get().find(req.districtId()).orElseThrow(InvalidSubmission::new);
        }

        boolean autoApprove = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT portal_auto_approve FROM tenants WHERE id = ?", Boolean.class, tenantId));
        String reference = newReference(tenantId);
        UUID requestId = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, customer_email, customer_note, " +
            "    decided_at, pickup_city_id, pickup_city_name, pickup_district_id, pickup_district_name, " +
            "    pickup_district_name_ar) " +
            "VALUES (?, ?, 'refund', ?::return_request_status, ?, ?, ?, CASE WHEN ? THEN now() END, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, orderId, autoApprove ? "approved" : "requested", reference, email, note, autoApprove,
            district == null ? null : offer.get().cityId(),
            district == null ? null : offer.get().cityName(),
            district == null ? null : district.id(),
            district == null ? null : district.name(),
            district == null ? null : district.nameAr());
        for (Object[] it : items) {
            jdbc.update(
                "INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                "VALUES (?, ?, ?, ?, ?)",
                tenantId, requestId, it[0], it[1], it[2]);
        }
        requests.event(tenantId, requestId, "requested", null,
            ReturnRequestLifecycle.meta("items", items.size()));
        if (autoApprove) {
            requests.event(tenantId, requestId, "approved", null, ReturnRequestLifecycle.meta("auto", true));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("status", autoApprove ? "approved" : "requested");
        if (autoApprove && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, tenantId))) {
            body.put("_bookRequestId", requestId);   // internal — removed before the response
        }
        return body;
    }

    /** RR- + 6 characters from an unambiguous alphabet, unused within this tenant. */
    private String newReference(UUID tenantId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("RR-");
            for (int i = 0; i < REFERENCE_LENGTH; i++) {
                sb.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String ref = sb.toString();
            Boolean taken = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND reference = ?)",
                Boolean.class, tenantId, ref);
            if (!Boolean.TRUE.equals(taken)) return ref;
        }
        throw new IllegalStateException("Could not generate a unique return reference");
    }

    private void recordAttempt(UUID tenantId, String orderKey, boolean success) {
        jdbc.update(
            "INSERT INTO portal_lookup_attempts (tenant_id, order_key, success) VALUES (?, ?, ?)",
            tenantId, orderKey, success);
    }
}
