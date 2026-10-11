package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.integrations.bosta.BostaRateLimiter;
import com.traceability.integrations.bosta.CourierSimulation;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns portal P4c — the on-demand lookup of an order placed BEFORE the store connected to Traced
 * (stores.orders_ingest_from). Runs only when the normal DB lookup found nothing and
 * {@code portal.preconnect.lookup-enabled} is on (PortalService decides; this class never answers
 * SUCCESS itself — it stores the order and PortalService's normal lookup then finds it).
 *
 * Order of checks — every one before the first external call it protects:
 *   1. SKIP (no call): a portal row for this number already exists (its DB miss was the phone or the
 *      window), or the number is at / above the lowest order number Traced ingested (same shape) — an
 *      order placed after the connect would be in Traced already.
 *   2. INELIGIBLE (no call): not exactly one connected Shopify store, a NULL connect cutoff, no active
 *      Bosta key, or a simulated-courier tenant — today's generic not-found, unpadded.
 *   3. BUSY (no call): the per-tenant / per-IP hourly caps (portal_lookup_attempts.ondemand_outcome), or
 *      (P4d) no Bosta headroom — a non-consuming peek at the portal budget and the shared limiter, made
 *      before anything about the order or the phone is known.
 *   4. NOT_FOUND (no call): the negative cache (30 min) for this order.
 *   5. Shopify (the tenant's token): orders by name, first 2 — exactly one; else the "too old" boundary:
 *      the oldest order Shopify still returns (cached per tenant, 1 h) — same number shape and lower →
 *      TOO_OLD with its date, decided before any phone check; incomparable → NOT_FOUND. Created at /
 *      after the cutoff → NOT_FOUND. The token can read no customer field → UNAVAILABLE.
 *   6. Phone (shopify_order_pii_phone, the import's precedence) — a mismatch is NOT_FOUND and Bosta is
 *      never called.
 *   7. Bosta: the tracking number from the order's fulfillments, fetchDelivery (v0) through the portal's
 *      own budget (PortalBostaBudget, one per 5 s) and at most 3 s on the global limiter; losing the slot
 *      to a concurrent lookup here (after the phone check) is the padded generic not-found, not cached.
 *      Delivered (state 45), delivered within customer_return_window_days, businessReference = the
 *      order name → else NOT_FOUND.
 *   8. Insert, one transaction: the orders row (origin portal_pre_connect, V163) + its order_items
 *      mapped to catalog variants. A concurrent insert of the same Shopify order → the winner's row.
 *
 * No DB transaction is open during a Shopify or Bosta call. Nothing about the customer, the order,
 * tokens or the Shopify / Bosta bodies is logged — only the tenant and the outcome.
 */
@Service
public class PortalOnDemandLookup {

    private static final Logger log = LoggerFactory.getLogger(PortalOnDemandLookup.class);
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    /** A typed number the lookup may send to Shopify: letters, digits, '-' and '_' only. */
    private static final Pattern SAFE_NUMBER = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    /** prefix · digits · suffix — the "shape" two order numbers must share to be compared. */
    private static final Pattern SHAPE = Pattern.compile("^(\\D*?)(\\d{1,18})(\\D*)$");
    private static final int DELIVERED = 45;


    /**
     * INELIGIBLE: this store can never look old orders up (NULL connect cutoff, no active Bosta key,
     * simulated courier, not exactly one connected store) — the caller answers today's generic not-found
     * exactly as with the flag off: unpadded, recorded as a plain miss. SKIP: an eligible store's miss
     * that needs no external call — padded like every other on-demand outcome.
     */
    public enum Kind { FETCHED, SKIP, INELIGIBLE, NOT_FOUND, TOO_OLD, UNAVAILABLE, BUSY }

    /** {@code tooOldBefore}: orders placed before this date can't be looked up online (TOO_OLD only). */
    public record Result(Kind kind, UUID orderId, LocalDate tooOldBefore) {
        static Result of(Kind k) { return new Result(k, null, null); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokens;
    private final BostaGateway bosta;
    private final EncryptionService encryption;
    private final PortalBostaBudget budget;
    private final BostaRateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();

    private final boolean enabled;
    private final int tenantPerHour;
    private final int ipPerHour;
    private final long bostaMaxWaitMs;
    private final long bostaReadReserveMs;
    private final Duration negativeTtl;
    private final Duration oldestTtl;

    private record Expiring<T>(T value, Instant until) {
        boolean live() { return until.isAfter(Instant.now()); }
    }

    /** (tenant, order key) → the order isn't eligible; no external call until it expires. */
    private final Map<String, Expiring<Boolean>> negative = new ConcurrentHashMap<>();
    /** (tenant, order key) → the one Shopify order found — a retried phone doesn't call Shopify again. */
    private final Map<String, Expiring<ShopifyGateway.Order>> found = new ConcurrentHashMap<>();
    /** tenant → the oldest order Shopify still returns (name + createdAt), or empty. */
    private final Map<UUID, Expiring<Optional<ShopifyGateway.Order>>> oldest = new ConcurrentHashMap<>();

    public PortalOnDemandLookup(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                                ShopifyTokenProvider tokens, BostaGateway bosta, EncryptionService encryption,
                                PortalBostaBudget budget, BostaRateLimiter rateLimiter,
                                @Value("${portal.preconnect.lookup-enabled:false}") boolean enabled,
                                @Value("${portal.preconnect.tenant-per-hour:20}") int tenantPerHour,
                                @Value("${portal.preconnect.ip-per-hour:5}") int ipPerHour,
                                @Value("${portal.preconnect.bosta-max-wait-ms:3000}") long bostaMaxWaitMs,
                                @Value("${portal.preconnect.bosta-read-reserve-ms:2000}") long bostaReadReserveMs,
                                @Value("${portal.preconnect.negative-cache-minutes:30}") long negativeMinutes,
                                @Value("${portal.preconnect.oldest-order-cache-minutes:60}") long oldestMinutes) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.shopify = shopify;
        this.tokens = tokens;
        this.bosta = bosta;
        this.encryption = encryption;
        this.budget = budget;
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.tenantPerHour = tenantPerHour;
        this.ipPerHour = ipPerHour;
        this.bostaMaxWaitMs = bostaMaxWaitMs;
        this.bostaReadReserveMs = bostaReadReserveMs;
        this.negativeTtl = Duration.ofMinutes(negativeMinutes);
        this.oldestTtl = Duration.ofMinutes(oldestMinutes);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Test seam: forget every cached answer. */
    public void clearCaches() {
        negative.clear();
        found.clear();
        oldest.clear();
    }

    private record Tenant(UUID storeId, String shopDomain, Instant cutoff, String bostaKey, int windowDays) {}

    /**
     * {@code deadlineNanos}: when the caller's timing pad ends (System.nanoTime). The Bosta budget wait
     * only uses what is left of it (minus {@code bosta-read-reserve-ms} for the read) and never more than
     * {@code bosta-max-wait-ms} — a found order must not take longer than a miss: past the pad the response
     * time would say the number and phone matched (Bosta is only called after that). Too little time left →
     * BUSY, which is answered at once and says nothing about the order.
     */
    public Result fetch(UUID tenantId, String raw, String stripped, String orderKey, String phoneRaw, String ipHash,
                        long deadlineNanos) {
        Result r = decide(tenantId, raw, stripped, orderKey, phoneRaw, ipHash, deadlineNanos);
        log.info("Portal on-demand lookup: tenant {} → {}", tenantId, r.kind());
        return r;
    }

    private Result decide(UUID tenantId, String raw, String stripped, String orderKey, String phoneRaw, String ipHash,
                          long deadlineNanos) {
        if (!SAFE_NUMBER.matcher(stripped).matches()) return Result.of(Kind.SKIP);
        String phone = ShipmentLinkService.normalizePhone(phoneRaw);
        if (phone == null) return Result.of(Kind.SKIP);

        // UNAVAILABLE is answered ONLY for a transient failure of an eligible store BEFORE the phone
        // check — (a) the Shopify token can't be had (getValidToken throws: missing / revoked / refresh
        // failed), (b) the Shopify search fails (401 / 403 / 5xx / GraphQL error / timeout), (c) the token
        // can read no customer field (ACCESS_DENIED on every tier). After the phone matched, any failure is
        // the padded generic not-found: an answer there must not say the phone was right.

        // 1–3: no external call.
        Object pre = inTenant(tenantId, () -> {
            // The store first: one that can never do this answers every miss exactly as today (unpadded).
            Tenant t = tenant(tenantId);
            if (t == null) return Kind.INELIGIBLE;
            if (portalRowExists(tenantId, raw, stripped)) return Kind.SKIP;
            if (atOrAboveLowestIngested(tenantId, stripped)) return Kind.SKIP;
            if (overCaps(tenantId, ipHash)) return Kind.BUSY;
            return t;
        });
        if (pre instanceof Kind k) return Result.of(k);
        Tenant t = (Tenant) pre;

        // 4 (P4d): BUSY is decided HERE, before anything about the order or the phone is known — a peek
        // (takes nothing) at the portal's Bosta budget and the shared limiter's headroom for this key.
        // After the phone matches the slot is acquired; losing it to a concurrent lookup then is the padded
        // generic not-found, never BUSY — so a 503 never says the phone was right.
        long peekWait = bostaWaitWithin(deadlineNanos);
        if (!budget.hasSlotWithin(peekWait) || !rateLimiter.hasHeadroom(t.bostaKey(), peekWait)) {
            return Result.of(Kind.BUSY);
        }

        String cacheKey = tenantId + "|" + orderKey;
        Expiring<Boolean> miss = negative.get(cacheKey);
        if (miss != null && miss.live()) return Result.of(Kind.NOT_FOUND);

        // 5: Shopify.
        ShopifyGateway.Order order;
        Expiring<ShopifyGateway.Order> cached = found.get(cacheKey);
        if (cached != null && cached.live()) {
            order = cached.value();
        } else {
            String token;
            ShopifyGateway.PortalOrderSearch search;
            try {
                token = tokens.getValidToken(t.storeId());
                search = shopify.findOrdersByName(t.shopDomain(), token, stripped);
            } catch (RuntimeException e) {
                return Result.of(Kind.UNAVAILABLE);
            }
            if (!search.customerPhoneReadable()) return Result.of(Kind.UNAVAILABLE);
            List<ShopifyGateway.Order> exact = search.orders().stream()
                .filter(o -> sameNumber(o.name(), stripped)).toList();
            if (exact.isEmpty()) {
                LocalDate before = tooOldBefore(tenantId, t, token, stripped);
                if (before != null) return new Result(Kind.TOO_OLD, null, before);
                return miss(cacheKey);
            }
            if (exact.size() > 1) return miss(cacheKey);
            order = exact.get(0);
            if (order.createdAt() == null || !order.createdAt().isBefore(t.cutoff())) return miss(cacheKey);
            found.put(cacheKey, new Expiring<>(order, Instant.now().plus(negativeTtl)));
        }

        // 6: the phone, before Bosta is ever called.
        String orderPhone = ShipmentLinkService.normalizePhone(jdbc.queryForObject(
            "SELECT shopify_order_pii_phone(?::jsonb)", String.class, order.raw().toString()));
        if (orderPhone == null || !orderPhone.equals(phone)) return Result.of(Kind.NOT_FOUND);

        // 7: Bosta.
        String tracking = bostaTracking(order.raw());
        if (tracking == null) return miss(cacheKey);
        // The phone matched: from here every failure is the padded generic not-found, not cached.
        if (!budget.tryAcquire(bostaWaitWithin(deadlineNanos))) return Result.of(Kind.NOT_FOUND);   // lost the race
        long sharedWait = bostaWaitWithin(deadlineNanos);
        BostaDelivery delivery;
        try {
            delivery = BostaRateLimiter.userFacing(() -> BostaRateLimiter.withMaxWait(sharedWait,
                () -> bosta.fetchDelivery(t.bostaKey(), tracking)));
        } catch (BostaRateLimitException e) {
            return Result.of(Kind.NOT_FOUND);   // the limiter's headroom went to someone else
        } catch (RuntimeException e) {
            // After the phone check: never UNAVAILABLE (it would say the phone matched). Not cached —
            // a Bosta outage is not a fact about the order.
            return Result.of(Kind.NOT_FOUND);
        }
        if (delivery == null || delivery.stateCode() != DELIVERED || delivery.raw() == null) return miss(cacheKey);
        Instant deliveredAt = deliveredAt(delivery.raw());
        if (deliveredAt == null || deliveredAt.isBefore(Instant.now().minus(Duration.ofDays(t.windowDays())))) {
            return miss(cacheKey);
        }
        if (!referenceMatches(delivery.businessReference(), order.name())) return miss(cacheKey);

        // 8: store it.
        ShopifyGateway.Order o = order;
        UUID orderId = inTenant(tenantId, () -> insert(tenantId, t, o, delivery, deliveredAt));
        if (orderId == null) return miss(cacheKey);
        return new Result(Kind.FETCHED, orderId, null);
    }

    /** What is left of the timing pad for a Bosta wait: pad − elapsed − read reserve, at most bosta-max-wait-ms. */
    private long bostaWaitWithin(long deadlineNanos) {
        long left = (deadlineNanos - System.nanoTime()) / 1_000_000L - bostaReadReserveMs;
        return Math.max(0, Math.min(bostaMaxWaitMs, left));
    }

    private Result miss(String cacheKey) {
        negative.put(cacheKey, new Expiring<>(true, Instant.now().plus(negativeTtl)));
        return Result.of(Kind.NOT_FOUND);
    }

    private <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> body.get()));
    }

    // ── checks without an external call ─────────────────────────────────────────

    private boolean portalRowExists(UUID tenantId, String raw, String stripped) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect' " +
            "  AND (number = ? OR number = ? OR number = ?))",
            Boolean.class, tenantId, raw, stripped, "#" + stripped));
    }

    /** True when the typed number has the shape of the store's ingested numbers and isn't below the lowest. */
    private boolean atOrAboveLowestIngested(UUID tenantId, String stripped) {
        Matcher m = SHAPE.matcher(stripped);
        if (!m.matches()) return false;
        String prefix = m.group(1), suffix = m.group(3);
        BigDecimal lowest = jdbc.queryForObject(
            "SELECT MIN(mid::numeric) FROM ( " +
            "  SELECT substr(n, length(?) + 1, length(n) - length(?) - length(?)) AS mid FROM ( " +
            "    SELECT ltrim(number, '#') AS n FROM merchant_orders " +
            "    WHERE tenant_id = ? AND external_id NOT LIKE 'internal:%' " +
            "  ) o " +
            "  WHERE length(n) > length(?) + length(?) " +
            "    AND lower(left(n, length(?))) = lower(?) AND lower(right(n, length(?))) = lower(?) " +
            ") x WHERE mid ~ '^[0-9]+$'",
            BigDecimal.class, prefix, prefix, suffix, tenantId, prefix, suffix, prefix, prefix, suffix, suffix);
        return lowest != null && new BigDecimal(m.group(2)).compareTo(lowest) >= 0;
    }

    /** The tenant's on-demand prerequisites, or null (→ UNAVAILABLE). */
    private Tenant tenant(UUID tenantId) {
        if (CourierSimulation.isSimulated(jdbc, tenantId)) return null;
        List<Map<String, Object>> stores = jdbc.queryForList(
            "SELECT id, shop_domain, orders_ingest_from FROM stores WHERE tenant_id = ? AND status = 'connected'", tenantId);
        if (stores.size() != 1 || stores.get(0).get("orders_ingest_from") == null) return null;
        Boolean anyNullCutoff = jdbc.queryForObject(
            "SELECT bool_or(orders_ingest_from IS NULL) FROM stores WHERE tenant_id = ?", Boolean.class, tenantId);
        if (Boolean.TRUE.equals(anyNullCutoff)) return null;
        String key = jdbc.queryForList(
            "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            String.class, tenantId).stream().findFirst().orElse(null);
        if (key == null) return null;
        Integer window = jdbc.queryForObject("SELECT customer_return_window_days FROM tenants WHERE id = ?", Integer.class, tenantId);
        Map<String, Object> s = stores.get(0);
        return new Tenant((UUID) s.get("id"), (String) s.get("shop_domain"),
            ((Timestamp) s.get("orders_ingest_from")).toInstant(), encryption.decrypt(key), window == null ? 0 : window);
    }

    /** The hourly caps: on-demand attempts that used the budget (busy / unavailable never count). */
    private boolean overCaps(UUID tenantId, String ipHash) {
        Integer tenantCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM portal_lookup_attempts WHERE tenant_id = ? " +
            "  AND ondemand_outcome IN ('fetched', 'not_found', 'too_old') AND attempted_at > now() - interval '1 hour'",
            Integer.class, tenantId);
        if (tenantCount != null && tenantCount >= tenantPerHour) return true;
        if (ipHash == null) return false;
        Integer ipCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM portal_lookup_attempts WHERE tenant_id = ? AND ip_hash = ? " +
            "  AND ondemand_outcome IN ('fetched', 'not_found', 'too_old') AND attempted_at > now() - interval '1 hour'",
            Integer.class, tenantId, ipHash);
        return ipCount != null && ipCount >= ipPerHour;
    }

    // ── Shopify ──────────────────────────────────────────────────────────────────

    private static boolean sameNumber(String shopifyName, String stripped) {
        if (shopifyName == null) return false;
        String n = shopifyName.startsWith("#") ? shopifyName.substring(1) : shopifyName;
        return n.equalsIgnoreCase(stripped);
    }

    /**
     * Step 0b §7: Shopify (read_orders) silently omits orders older than ~60 days, so a miss can't tell
     * "too old" from "doesn't exist". Only when the typed number has the same shape as the oldest order
     * Shopify still returns and is LOWER is the answer TOO_OLD — with that order's date; anything else
     * is the generic not-found. Never confirms the typed order exists.
     */
    private LocalDate tooOldBefore(UUID tenantId, Tenant t, String token, String stripped) {
        Expiring<Optional<ShopifyGateway.Order>> c = oldest.get(tenantId);
        Optional<ShopifyGateway.Order> o;
        if (c != null && c.live()) {
            o = c.value();
        } else {
            try {
                o = shopify.oldestOrder(t.shopDomain(), token);
            } catch (RuntimeException e) {
                return null;
            }
            oldest.put(tenantId, new Expiring<>(o, Instant.now().plus(oldestTtl)));
        }
        if (o.isEmpty() || o.get().name() == null || o.get().createdAt() == null) return null;
        String oldestName = o.get().name().startsWith("#") ? o.get().name().substring(1) : o.get().name();
        Matcher typed = SHAPE.matcher(stripped), old = SHAPE.matcher(oldestName);
        if (!typed.matches() || !old.matches()) return null;
        if (!typed.group(1).equalsIgnoreCase(old.group(1)) || !typed.group(3).equalsIgnoreCase(old.group(3))) return null;
        if (new BigDecimal(typed.group(2)).compareTo(new BigDecimal(old.group(2))) >= 0) return null;
        return LocalDate.ofInstant(o.get().createdAt(), CAIRO);
    }

    /** The Bosta tracking number on the order's fulfillments — a Bosta-labelled one first, digits only. */
    static String bostaTracking(JsonNode raw) {
        String bostaLabelled = null, any = null;
        for (JsonNode f : raw.path("fulfillments")) {
            if ("CANCELLED".equalsIgnoreCase(f.path("status").asText(""))) continue;
            for (JsonNode ti : f.path("trackingInfo")) {
                String n = ti.path("number").asText("").replaceAll("\\s+", "");
                if (!n.matches("^[0-9]{6,15}$")) continue;
                if (bostaLabelled == null && ti.path("company").asText("").toLowerCase(Locale.ROOT).contains("bosta")) {
                    bostaLabelled = n;
                }
                if (any == null) any = n;
            }
        }
        return bostaLabelled != null ? bostaLabelled : any;
    }

    // ── Bosta ────────────────────────────────────────────────────────────────────

    /**
     * When Bosta delivered it: {@code state.deliveryTime}, else the latest completed timeline entry with
     * code 45 (or value "delivered") — confirmed against prod shipment raws: state.code 45 + deliveryTime
     * equals the timeline's code-45 date. Null when neither is there — the order is then not eligible.
     */
    static Instant deliveredAt(JsonNode raw) {
        Instant t = parse(raw.path("state").path("deliveryTime").asText(null));
        if (t != null) return t;
        Instant best = null;
        for (JsonNode e : raw.path("timeline")) {
            if (!e.path("done").asBoolean(false)) continue;
            boolean delivered = e.path("code").asInt(-1) == DELIVERED
                || "delivered".equalsIgnoreCase(e.path("value").asText("").trim());
            if (!delivered) continue;
            Instant d = parse(e.path("date").asText(null));
            if (d != null && (best == null || d.isAfter(best))) best = d;
        }
        return best;
    }

    private static Instant parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /** businessReference names this order (as sent, '#'-stripped, either side of a ':'). */
    static boolean referenceMatches(String reference, String orderName) {
        if (reference == null || orderName == null) return false;
        String name = orderName.startsWith("#") ? orderName.substring(1) : orderName;
        for (String part : reference.split(":")) {
            String p = part.trim();
            if (p.startsWith("#")) p = p.substring(1);
            if (p.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ── insert ───────────────────────────────────────────────────────────────────

    /** The portal row + its lines, or the concurrent winner's row; null when nothing usable was stored. */
    private UUID insert(UUID tenantId, Tenant t, ShopifyGateway.Order order, BostaDelivery delivery, Instant deliveredAt) {
        // Lines → catalog variants. A line whose variant Traced doesn't know is left out (it couldn't be
        // returned or exchanged through the portal anyway); with no known line at all nothing is stored.
        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, Integer> current = new HashMap<>();
        for (JsonNode e : order.raw().path("lineItems").path("edges")) {
            JsonNode n = e.path("node");
            String gid = n.path("variant").path("id").asText(null);
            int qty = n.path("quantity").asInt(0);
            if (gid == null || qty <= 0) continue;
            quantities.merge(gid, qty, Integer::sum);
            if (n.hasNonNull("currentQuantity")) current.merge(gid, n.path("currentQuantity").asInt(0), Integer::sum);
        }
        Map<String, UUID> variants = new HashMap<>();
        if (!quantities.isEmpty()) {
            jdbc.query("SELECT external_id, id FROM variants WHERE tenant_id = ? AND external_id = ANY(?::text[])",
                rs -> { variants.put(rs.getString(1), rs.getObject(2, UUID.class)); },
                tenantId, quantities.keySet().toArray(new String[0]));
        }
        if (variants.isEmpty()) return null;
        int unmapped = quantities.size() - variants.size();
        if (unmapped > 0) log.info("Portal on-demand lookup: tenant {} — {} order line(s) not in the catalog, left out", tenantId, unmapped);

        String payment = ShopifySyncService.inferPaymentMethod(order.displayFinancialStatus(),
            order.paymentGateways() == null ? List.of() : order.paymentGateways());
        BigDecimal cod = "cod".equals(payment) ? order.totalPrice() : null;

        ObjectNode portalDelivery = mapper.createObjectNode();
        portalDelivery.put("trackingNumber", delivery.trackingNumber());
        portalDelivery.put("businessReference", delivery.businessReference());
        portalDelivery.put("deliveredAt", deliveredAt.toString());
        portalDelivery.set("dropOffAddress", delivery.raw().path("dropOffAddress").deepCopy());
        portalDelivery.set("receiver", delivery.raw().path("receiver").deepCopy());

        List<UUID> inserted = jdbc.queryForList(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, customer_name, customer_phone, shopify_address, " +
            "    pii_source, payment_method, cod_amount, placed_at, raw, status, origin, shopify_order_gid, " +
            "    portal_fetched_at, portal_delivered_at, portal_delivery) " +
            "SELECT ?, ?, 'internal:portal:' || gen_random_uuid(), ?, shopify_order_pii_name(r.raw), " +
            "       shopify_order_pii_phone(r.raw), shopify_order_pii_address(r.raw), 'shopify', ?::order_payment_method, ?, ?, " +
            "       r.raw, 'delivered', 'portal_pre_connect', ?, now(), ?, ?::jsonb " +
            "FROM (SELECT ?::jsonb AS raw) r " +
            "WHERE NOT EXISTS (SELECT 1 FROM orders x WHERE x.tenant_id = ? AND x.external_id = ?) " +
            "ON CONFLICT (tenant_id, shopify_order_gid) WHERE shopify_order_gid IS NOT NULL DO NOTHING " +
            "RETURNING id",
            UUID.class, tenantId, t.storeId(), order.name(), payment, cod, Timestamp.from(order.createdAt()),
            order.gid(), Timestamp.from(deliveredAt), portalDelivery.toString(), order.raw().toString(),
            tenantId, order.gid());
        if (inserted.isEmpty()) {
            // A concurrent lookup stored it first (or Shopify's own ingest has it now): use that row.
            return jdbc.queryForList(
                "SELECT id FROM orders WHERE tenant_id = ? AND shopify_order_gid = ? AND origin = 'portal_pre_connect'",
                UUID.class, tenantId, order.gid()).stream().findFirst().orElse(null);
        }
        UUID orderId = inserted.get(0);
        for (Map.Entry<String, Integer> line : quantities.entrySet()) {
            UUID variant = variants.get(line.getKey());
            if (variant == null) continue;
            ObjectNode raw = mapper.createObjectNode();
            if (current.containsKey(line.getKey())) raw.put("current_quantity", current.get(line.getKey()));
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) VALUES (?, ?, ?, ?, ?::jsonb)",
                tenantId, orderId, variant, line.getValue(), raw.toString());
        }
        return orderId;
    }
}
