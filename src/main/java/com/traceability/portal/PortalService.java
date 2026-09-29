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

    private boolean exchangesEnabled(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT portal_exchanges_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId));
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
                "SELECT name, customer_return_window_days, portal_auto_approve, " +
                "       portal_logo_url, portal_brand_color, portal_policy_text, portal_pickup_booking, " +
                "       portal_exchanges_enabled " +
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
            // Step 5b: present only when the store offers exchanges (absent, not false, otherwise —
            // stores without exchanges get exactly the pre-5b config).
            if (Boolean.TRUE.equals(t.get("portal_exchanges_enabled"))) body.put("exchangesEnabled", true);
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
        // Step 6a: the order's UNTRACKED lines (no allocation of any status) — keyed by order line.
        lines.addAll(untrackedLines(tenantId, orderId));
        recordAttempt(tenantId, orderKey, true);
        // Step 5b: when the store offers exchanges, each line also carries what it could be
        // exchanged for (siblings of the same product, in stock or not) — absent otherwise.
        if (exchangesEnabled(tenantId)) {
            Map<UUID, com.traceability.inventory.VariantStockService.VariantStock> stock =
                new com.traceability.inventory.VariantStockService(jdbc).computeAll();
            ExchangeOptions options = new ExchangeOptions(jdbc);
            for (Map<String, Object> line : lines) {
                line.putAll(options.forVariant(tenantId, UUID.fromString((String) line.get("variantId")), stock));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",       tokens.issue(tenantId, orderId));
        body.put("orderNumber", order.get("number"));
        body.put("deliveredAt", ((Timestamp) order.get("delivered_at")).toInstant().toString());
        body.put("lines",       lines);
        // Step 4c-2: the pickup area choice — only when this tenant books Bosta pickups and
        // the delivery city has pickup-available districts. City and district names only.
        Optional<PickupAreaService.CityAreas> offer = pickupOffer(tenantId, orderId);
        Map<String, Object> pickup = offer.map(a -> a.toJson(true)).orElse(null);
        // Step 5c: in exchange mode only districts Bosta can deliver to AND collect from — the
        // key exists only when the tenant allows exchanges (exact-key tests rely on that).
        if (pickup != null && exchangesEnabled(tenantId)) {
            pickup.put("exchangeDistrictIds", pickupAreas.exchangeDistrictIds(offer.get().cityId()));
        }
        // V117: the cities a customer can choose for a different pickup address — every Bosta city
        // with a pickup-available district (names and ids only; the districts come per city from
        // GET /portal/{slug}/districts). Never a street address.
        if (pickup != null) {
            pickup.put("cities", pickupAreas.pickupCities().stream().map(PickupAreaService.City::toJson).toList());
        }
        body.put("pickup", pickup);
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

    // ── V117: districts for a different pickup address ─────────────────────────────

    public enum DistrictsOutcome { OK, UNAUTHORIZED }

    public record DistrictsResult(DistrictsOutcome outcome, Map<String, Object> body) {}

    /**
     * GET /api/v1/portal/{slug}/districts?cityId=…[&mode=exchange] — a city's pickup-available
     * districts (and, for an exchange, only those Bosta can also deliver to), grouped by zone in
     * order. Auth = the lookup token for this slug's tenant. Reference data only (bosta_districts);
     * an unknown city, or a store that doesn't book pickups, gets an empty list. Empty Optional for
     * an unknown/disabled slug.
     */
    public Optional<DistrictsResult> districts(String slug, String bearerToken, String cityId, boolean exchange) {
        UUID tenantId = resolveTenant(slug);
        if (tenantId == null) return Optional.empty();
        if (tokens.verify(bearerToken, tenantId).isEmpty()) {
            return Optional.of(new DistrictsResult(DistrictsOutcome.UNAUTHORIZED, null));
        }
        return Optional.of(new DistrictsResult(DistrictsOutcome.OK, TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            boolean booking = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, tenantId));
            Optional<PickupAreaService.CityAreas> areas = booking
                ? pickupAreas.forCity(cityId, null, exchange) : Optional.empty();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cityId", cityId);
            body.put("districts", areas.map(a -> a.districts().stream().map(PickupAreaService.District::toJson).toList())
                .orElse(List.of()));
            return body;
        }))));
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

    /**
     * Step 6a — THE per-line form of ShipmentLinkService.orderUntrackedSql (alias {@code oi}):
     * the order line has NO allocation row of any status. Released allocations count as tracked,
     * and so does a partially allocated line (piece-level only).
     */
    static final String LINE_UNTRACKED_SQL =
        "NOT EXISTS (SELECT 1 FROM allocations a_ln WHERE a_ln.order_item_id = oi.id) ";

    /**
     * Step 6a — how many units of an untracked order line a request can still take: the line's
     * quantity, capped by the Shopify REST {@code current_quantity} when the stored line has one
     * (Shopify-side removals / refunds), minus units already in a request that isn't released
     * (awaiting, arrived or done — a unit that came back is not returnable again).
     */
    private static final String UNTRACKED_CAP_SQL =
        "(CASE WHEN (oi.raw->>'current_quantity') ~ '^[0-9]+$' " +
        "      THEN LEAST(oi.quantity, (oi.raw->>'current_quantity')::int) ELSE oi.quantity END)";

    private static final String UNTRACKED_TAKEN_SQL =
        "(SELECT COUNT(*) FROM return_request_items rri WHERE rri.order_item_id = oi.id " +
        "   AND rri.tenant_id = oi.tenant_id AND rri.item_status <> 'not_coming')";

    /**
     * Step 6a — the order's untracked lines, one per order line (the same variant can also be
     * a tracked line on a mixed order, so the key is orderItemId). Same title / image sources as
     * tracked lines. Only these lines carry orderItemId / tracked:false.
     */
    private List<Map<String, Object>> untrackedLines(UUID tenantId, UUID orderId) {
        return jdbc.query(
            "SELECT oi.id AS order_item_id, v.id AS variant_id, pr.title AS product_title, v.title AS variant_title, " +
            "       pr.image_url, v.non_returnable, " + UNTRACKED_CAP_SQL + " AS cap, " + UNTRACKED_TAKEN_SQL + " AS taken " +
            "FROM order_items oi " +
            "JOIN variants v  ON v.id  = oi.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE oi.tenant_id = ? AND oi.order_id = ? AND " + LINE_UNTRACKED_SQL +
            "ORDER BY pr.title, v.title, oi.id",
            (rs, i) -> {
                boolean nonReturnable = rs.getBoolean("non_returnable");
                int cap = Math.max(0, rs.getInt("cap"));
                int free = Math.max(0, cap - rs.getInt("taken"));
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("orderItemId",        rs.getObject("order_item_id", UUID.class).toString());
                line.put("tracked",            false);
                line.put("variantId",          rs.getObject("variant_id", UUID.class).toString());
                line.put("productTitle",       rs.getString("product_title"));
                line.put("variantTitle",       rs.getString("variant_title"));
                line.put("imageUrl",           rs.getString("image_url"));
                line.put("deliveredQuantity",  cap);
                line.put("returnableQuantity", nonReturnable ? 0 : free);
                line.put("nonReturnable",      nonReturnable);
                return line;
            },
            tenantId, orderId);
    }

    // ── Step 4b: customer submission ─────────────────────────────────────────────

    public enum SubmitOutcome { CREATED, UNAUTHORIZED, INVALID, CONFLICT }

    /**
     * A tracked line (variantId — today's shape, orderItemId null) or, Step 6a, an untracked
     * order line (orderItemId; variantId optional and, when sent, must be that line's variant).
     */
    public record SubmitLine(UUID variantId, Integer quantity, String reasonCode, UUID orderItemId) {
        public SubmitLine(UUID variantId, Integer quantity, String reasonCode) {
            this(variantId, quantity, reasonCode, null);
        }
    }

    /**
     * districtId: the chosen pickup area — required when lookup offered one, ignored otherwise.
     * Step 5b: mode 'exchange' (default 'refund') with replacementVariantId and refundFallbackOk.
     */
    public record SubmitRequest(List<SubmitLine> lines, String email, String note, String districtId,
                                String mode, UUID replacementVariantId, Boolean refundFallbackOk,
                                String addressSource, CustomAddress customAddress) {
        public SubmitRequest(List<SubmitLine> lines, String email, String note, String districtId,
                             String mode, UUID replacementVariantId, Boolean refundFallbackOk) {
            this(lines, email, note, districtId, mode, replacementVariantId, refundFallbackOk, null, null);
        }
        public SubmitRequest(List<SubmitLine> lines, String email, String note, String districtId) {
            this(lines, email, note, districtId, null, null, null);
        }
        public SubmitRequest(List<SubmitLine> lines, String email, String note) {
            this(lines, email, note, null);
        }
        boolean exchange() { return "exchange".equals(mode); }
        boolean customAddressChosen() { return "custom".equals(addressSource); }
    }

    /**
     * V117 — a different pickup address typed by the customer: the city and area (validated
     * against bosta_districts like the order's area) plus the street (required, more than 5
     * characters) and optional landmark (secondLine), building, floor and apartment. PII —
     * never logged, never returned by a public endpoint.
     */
    public record CustomAddress(String cityId, String districtId, String firstLine, String secondLine,
                                String buildingNumber, String floor, String apartment) {}

    static final int CUSTOM_LINE_MAX = 250;
    static final int CUSTOM_SHORT_MAX = 20;

    public record SubmitResult(SubmitOutcome outcome, Map<String, Object> body) {}

    static final int NOTE_MAX = 300;
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O/1/I
    private static final int REFERENCE_LENGTH = 6;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final java.util.regex.Pattern EMAIL =
        java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String ACTIVE_PIECE_INDEX = "return_request_items_one_active_per_piece";
    /** Step 6a: the per-unit twin of ACTIVE_PIECE_INDEX (untracked order lines). */
    private static final String ACTIVE_UNIT_INDEX = "return_request_items_one_active_per_unit";

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
            if (String.valueOf(e.getMessage()).contains(ACTIVE_PIECE_INDEX)
                    || String.valueOf(e.getMessage()).contains(ACTIVE_UNIT_INDEX)) {
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
        if (req.mode() != null && !"refund".equals(req.mode()) && !req.exchange()) throw new InvalidSubmission();
        UUID replacement = req.exchange() ? validExchange(tenantId, orderId, req) : null;

        // Bind pieces line by line (the same variant may appear on two lines with different reasons).
        // Step 6a: an untracked order line binds unit numbers instead (one item row per unit).
        Set<String> bound = new HashSet<>();
        Map<UUID, Set<Integer>> boundUnits = new HashMap<>();
        List<Object[]> items = new ArrayList<>();
        for (SubmitLine line : req.lines()) {
            if (line == null || line.quantity() == null || line.quantity() < 1
                    || !REASON_CODES.contains(line.reasonCode())) {
                throw new InvalidSubmission();
            }
            if (line.orderItemId() != null) {
                bindUntrackedUnits(tenantId, orderId, line, boundUnits, items);
                continue;
            }
            if (line.variantId() == null) throw new InvalidSubmission();
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
                items.add(new Object[]{pieceId, line.variantId(), line.reasonCode(), null, null});
            }
        }

        // Step 4c-2: the pickup area, re-derived from scratch (never trusted from the lookup).
        // V117: or the city + area of a different address the customer typed.
        if (req.addressSource() != null && !"order".equals(req.addressSource()) && !req.customAddressChosen()) {
            throw new InvalidSubmission();
        }
        Optional<PickupAreaService.CityAreas> offer = req.customAddressChosen()
            ? customAddressArea(tenantId, req, replacement != null) : pickupOffer(tenantId, orderId);
        CustomAddress custom = req.customAddressChosen() ? cleanCustomAddress(req.customAddress()) : null;
        PickupAreaService.District district = null;
        if (custom != null) {
            // Already filtered to pickup (and, for an exchange, drop-off) available districts.
            district = offer.get().find(custom.districtId()).orElseThrow(InvalidSubmission::new);
        } else if (offer.isPresent()) {
            district = offer.get().find(req.districtId()).orElseThrow(InvalidSubmission::new);
            // Step 5c: an exchange courier delivers and collects — the district must allow both.
            if (replacement != null && !pickupAreas.exchangeDistrictIds(offer.get().cityId()).contains(district.id())) {
                throw new InvalidSubmission();
            }
        }

        // Step 5b: exchanges always wait for the merchant — auto-approve applies to refunds only.
        boolean autoApprove = replacement == null && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT portal_auto_approve FROM tenants WHERE id = ?", Boolean.class, tenantId));
        String reference = newReference(tenantId);
        UUID requestId = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, customer_email, customer_note, " +
            "    decided_at, pickup_city_id, pickup_city_name, pickup_district_id, pickup_district_name, " +
            "    pickup_district_name_ar, refund_fallback_ok, pickup_address_source, custom_first_line, " +
            "    custom_second_line, custom_building_number, custom_floor, custom_apartment) " +
            "VALUES (?, ?, ?, ?::return_request_status, ?, ?, ?, CASE WHEN ? THEN now() END, ?, ?, ?, ?, ?, ?, " +
            "        ?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, orderId, replacement == null ? "refund" : "exchange",
            autoApprove ? "approved" : "requested", reference, email, note, autoApprove,
            district == null ? null : offer.get().cityId(),
            district == null ? null : offer.get().cityName(),
            district == null ? null : district.id(),
            district == null ? null : district.name(),
            district == null ? null : district.nameAr(),
            replacement != null && Boolean.TRUE.equals(req.refundFallbackOk()),
            custom == null ? "order" : "custom",
            custom == null ? null : custom.firstLine(),
            custom == null ? null : custom.secondLine(),
            custom == null ? null : custom.buildingNumber(),
            custom == null ? null : custom.floor(),
            custom == null ? null : custom.apartment());
        for (Object[] it : items) {
            jdbc.update(
                "INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, " +
                "    replacement_variant_id, order_item_id, unit_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, requestId, it[0], it[1], it[2], replacement, it[3], it[4]);
        }
        requests.event(tenantId, requestId, "requested", null, replacement == null
            ? ReturnRequestLifecycle.meta("items", items.size())
            : ReturnRequestLifecycle.meta("items", items.size(), "type", "exchange",
                "replacement_variant_id", replacement.toString()));
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

    /**
     * V117 — the chosen city's areas for a different address: only when the store books Bosta
     * pickups; pickup-available districts, and for an exchange also drop-off-available (the same
     * rule as the order's area). An unknown city, or a city with none, is invalid.
     */
    private Optional<PickupAreaService.CityAreas> customAddressArea(UUID tenantId, SubmitRequest req, boolean exchange) {
        CustomAddress a = req.customAddress();
        if (a == null || a.cityId() == null || a.districtId() == null) throw new InvalidSubmission();
        boolean booking = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, tenantId));
        if (!booking) throw new InvalidSubmission();
        Optional<PickupAreaService.CityAreas> areas = pickupAreas.forCity(a.cityId().trim(), null, exchange);
        if (areas.isEmpty()) throw new InvalidSubmission();
        return areas;
    }

    /** V117 — trimmed, blanks to null; street required and longer than 5 characters; length caps. */
    private static CustomAddress cleanCustomAddress(CustomAddress a) {
        String first = blankToNull(a.firstLine());
        if (first == null || first.length() <= 5 || first.length() > CUSTOM_LINE_MAX) throw new InvalidSubmission();
        String second = blankToNull(a.secondLine());
        String building = blankToNull(a.buildingNumber());
        String floor = blankToNull(a.floor());
        String apartment = blankToNull(a.apartment());
        if (second != null && second.length() > CUSTOM_LINE_MAX) throw new InvalidSubmission();
        for (String shortField : new String[]{building, floor, apartment}) {
            if (shortField != null && shortField.length() > CUSTOM_SHORT_MAX) throw new InvalidSubmission();
        }
        return new CustomAddress(a.cityId().trim(), a.districtId().trim(), first, second, building, floor, apartment);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Step 5b — an exchange submission: the store offers exchanges; exactly one line of
     * quantity 1; a replacement variant that is a DIFFERENT variant of the SAME, ACTIVE product and is in
     * stock now (VariantStockService, recomputed here — never trusted from lookup). Returns the
     * replacement variant id; anything else is the generic invalid-submission 400.
     */
    private UUID validExchange(UUID tenantId, UUID orderId, SubmitRequest req) {
        if (!exchangesEnabled(tenantId)) throw new InvalidSubmission();
        if (req.lines().size() != 1) throw new InvalidSubmission();
        SubmitLine line = req.lines().get(0);
        UUID replacement = req.replacementVariantId();
        // Step 6a: the exchanged unit may be an untracked order line — its variant is the line's.
        UUID lineVariant = line == null ? null
            : line.orderItemId() != null ? untrackedLineVariant(tenantId, orderId, line.orderItemId()) : line.variantId();
        if (line == null || lineVariant == null || line.quantity() == null || line.quantity() != 1
                || replacement == null || replacement.equals(lineVariant)) {
            throw new InvalidSubmission();
        }
        Boolean sameProduct = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM variants r JOIN variants o ON o.product_id = r.product_id " +
            "               JOIN products p ON p.id = r.product_id AND p.tenant_id = r.tenant_id " +
            "               WHERE r.id = ? AND o.id = ? AND r.tenant_id = ? AND o.tenant_id = ? " +
            "                 AND p.status = 'active')",
            Boolean.class, replacement, lineVariant, tenantId, tenantId);
        if (!Boolean.TRUE.equals(sameProduct)) throw new InvalidSubmission();
        com.traceability.inventory.VariantStockService stock = new com.traceability.inventory.VariantStockService(jdbc);
        if (stock.forVariant(stock.computeAll(), replacement).available() <= 0) throw new InvalidSubmission();
        return replacement;
    }

    /** Step 6a — the variant of an untracked, returnable line of this order; null otherwise. */
    private UUID untrackedLineVariant(UUID tenantId, UUID orderId, UUID orderItemId) {
        return jdbc.queryForList(
            "SELECT oi.variant_id FROM order_items oi JOIN variants v ON v.id = oi.variant_id " +
            "WHERE oi.id = ? AND oi.tenant_id = ? AND oi.order_id = ? AND v.non_returnable = false AND " + LINE_UNTRACKED_SQL,
            UUID.class, orderItemId, tenantId, orderId).stream().findFirst().orElse(null);
    }

    /**
     * Step 6a — binds {@code line.quantity()} units of an untracked order line: the first free
     * unit numbers in 1..cap (cap as in lookup). A unit is taken when an item of it isn't
     * released (not_coming), or when this submission already bound it. A concurrent submission
     * choosing the same unit collides on return_request_items_one_active_per_unit → 409.
     */
    private void bindUntrackedUnits(UUID tenantId, UUID orderId, SubmitLine line,
                                    Map<UUID, Set<Integer>> boundUnits, List<Object[]> items) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT oi.variant_id, " + UNTRACKED_CAP_SQL + " AS cap " +
            "FROM order_items oi JOIN variants v ON v.id = oi.variant_id " +
            "WHERE oi.id = ? AND oi.tenant_id = ? AND oi.order_id = ? AND v.non_returnable = false AND " + LINE_UNTRACKED_SQL,
            line.orderItemId(), tenantId, orderId);
        if (rows.isEmpty()) throw new InvalidSubmission();
        UUID variantId = (UUID) rows.get(0).get("variant_id");
        if (line.variantId() != null && !line.variantId().equals(variantId)) throw new InvalidSubmission();
        int cap = ((Number) rows.get(0).get("cap")).intValue();
        Set<Integer> taken = new HashSet<>(jdbc.queryForList(
            "SELECT unit_no::int FROM return_request_items WHERE order_item_id = ? AND tenant_id = ? " +
            "  AND item_status <> 'not_coming'", Integer.class, line.orderItemId(), tenantId));
        Set<Integer> mine = boundUnits.computeIfAbsent(line.orderItemId(), k -> new HashSet<>());
        List<Integer> free = new ArrayList<>();
        for (int unit = 1; unit <= cap && free.size() < line.quantity(); unit++) {
            if (!taken.contains(unit) && !mine.contains(unit)) free.add(unit);
        }
        if (free.size() < line.quantity()) throw new InvalidSubmission();
        for (Integer unit : free) {
            mine.add(unit);
            items.add(new Object[]{null, variantId, line.reasonCode(), line.orderItemId(), unit});
        }
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
