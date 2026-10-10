package com.traceability.analytics;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;

/**
 * Analytics slice 4 — stock health, from the tenant's pieces (Traced's own per-unit inventory).
 * Tenants with no pieces get {@code hasPieces = false} and empty figures, so the screen shows its
 * empty state. Point-in-time unless a period is named.
 *
 * Definitions (one place each):
 *   in warehouse  — pieces with status 'available' (any location); voided pieces never count;
 *   received at   — pieces.created_at (a piece is created by receiving: InventoryLedger.batchReceive);
 *   age           — whole days since received;
 *   value         — at selling price (variants.price) always; at cost (variants.unit_cost) over costed
 *                   variants only, null when none is costed (with costed / total variant counts);
 *   coming back   — pieces in return_in_transit or return_pending_inspection (on their way back
 *                   to sellable stock);
 *   trip          — a piece event moving it from packed / awaiting_pickup / reserved into with_courier
 *                   or delivered ({@link #TRIP}); its order, forward leg (the event's shipment, else
 *                   the order's forward leg booked before the event) and that leg's outcome (the s2
 *                   rule, SalesAnalyticsService.LEG_OUTCOME_WHENS) and fee (SettlementSql.fee);
 *   velocity      — delivered units per day over the last 30 Cairo days (s2 outcomes on the s1 sold
 *                   lines placed in those days ÷ 30);
 *   days of cover — on hand ÷ velocity (null without sales);
 *   sell-through  — sold ÷ (sold + on hand), last 30 days;
 *   running low   — velocity > 0 and days of cover ≤ 7 (sold out included);
 *   dead stock    — on hand > 0 and no sale in 60 days (the s1 last sale, all time), cash tied up at
 *                   cost when the variant is costed, else at selling price (flagged);
 *   returns rate  — (returned + exchanged) ÷ delivered units over the last 90 days (the s2 returns,
 *                   the s5 exchanges: ProductExtrasAnalyticsService.variantRows), with the most
 *                   frequent return-request reason in those days.
 *
 * STOCK TRUST ({@link #trust}): piece counts are only true when the tenant packs through Traced.
 *   packedThroughTracedPct — of the orders Bosta delivered in the last 30 days (a forward Bosta leg
 *                   delivered then; internal exchange orders left out), the share with any piece
 *                   allocation or piece event;
 *   Shopify figure — variants.shopify_inventory_quantity (V153: Shopify's REST inventory_quantity,
 *                   available summed over all locations, as of the variant's last products/*
 *                   webhook — shopify_variant_updated_at; GraphQL-imported variants have none);
 *   mismatch      — over variants with pieces AND a Shopify figure: |traced available − max(Shopify,
 *                   0)| summed ÷ Shopify units;
 *   level         — none (no pieces) | high (≥ 80 % packed through Traced AND mismatch ≤ 10 %; no
 *                   Shopify figure at all counts as no mismatch) | low.
 * At level low the stock-based figures — days of cover, running low, dead stock, sell-through,
 * restock, sells-out-soon — use the Shopify figure (stockSource "shopify"; a variant with no Shopify
 * figure falls back to its pieces and says so); piece-only figures (age, trips, damaged, on hold)
 * stay on pieces and the summary flags them lowTrust.
 */
@Service
public class StockAnalyticsService {

    static final int VELOCITY_DAYS = 30;
    static final int RETURNS_DAYS = 90;
    static final int DEAD_STOCK_DAYS = 60;
    static final BigDecimal RUNNING_LOW_DAYS = BigDecimal.valueOf(7);
    public static final int DEFAULT_LEAD_DAYS = 21;
    public static final int DEFAULT_COVER_DAYS = 35;

    /** A trip: out of the warehouse to the customer (SQL predicate over a piece_events alias). */
    static String trip(String e) {
        return " (" + e + ".from_status IN ('packed', 'awaiting_pickup', 'reserved') AND "
            + e + ".to_status IN ('with_courier', 'delivered')) ";
    }

    // ── records ─────────────────────────────────────────────────────────────

    public record LocationCount(UUID locationId, String name, long pieces) {}

    public record AgeBucket(String key, long pieces, BigDecimal valueAtPrice) {}

    public record Valued(long pieces, BigDecimal valueAtCost, long costedPieces) {}

    public record Trust(String level, BigDecimal packedThroughTracedPct, long bostaDeliveredOrders30,
                        long packedThroughTraced30, long tracedAvailableTotal, long shopifyAvailableTotal,
                        long variantsCompared, long variantsWithMismatch, long mismatchUnits, BigDecimal mismatchShare,
                        long variantsWithoutShopifyFigure, Instant shopifyFigureOldest, Instant shopifyFigureNewest,
                        String shopifySource) {

        boolean low() {
            return "low".equals(level);
        }
    }

    static final String SHOPIFY_FRESH_SOURCE =
        "variants.stock_available_shopify_traced (Shopify available at the Traced location; slice-10 read pass + inventory_levels/update), "
        + "else variants.shopify_inventory_quantity for variants not synced yet";

    static final String SHOPIFY_SOURCE =
        "variants.shopify_inventory_quantity (Shopify REST inventory_quantity, all locations, as of the variant's last products webhook)";

    public record Summary(boolean hasPieces, AnalyticsPeriod.Range range, Instant asOf, long inWarehouse,
                          List<LocationCount> byLocation, BigDecimal valueAtPrice, BigDecimal valueAtCost,
                          long costedVariants, long variantsInStock, BigDecimal avgDaysInStock,
                          List<AgeBucket> ageBuckets, long onHold, Valued damaged, Valued lostThisPeriod,
                          long piecesMovedFourPlus, Trust trust, boolean lowTrust) {}

    public record VariantStock(UUID variantId, String productTitle, String variantTitle, String sku,
                               long onHand, long comingBack, BigDecimal velocityPerDay, BigDecimal daysOfCover,
                               BigDecimal sellThrough, BigDecimal avgPieceAgeDays, Instant lastSoldAt,
                               long soldUnits30, long deliveredUnits30, BigDecimal returnsRate,
                               long returnedUnits90, long exchangedUnits90, String topReturnReason,
                               boolean runningLow, boolean deadStock, BigDecimal stockValue, boolean valueAtCost,
                               Integer shopifyAvailable, long stockUsed, String stockSource) {}

    public record Variants(boolean hasPieces, Instant asOf, String trustLevel, String stockSource, String sort,
                           String filter, long total, List<VariantStock> variants) {}

    public record RestockItem(UUID variantId, String productTitle, String variantTitle, String sku,
                              BigDecimal velocityPerDay, long onHand, long comingBack, BigDecimal daysOfCover,
                              long suggestedUnits, BigDecimal costAtUnitCost) {}

    public record Restock(boolean hasPieces, Instant asOf, String trustLevel, String stockSource, int supplierLeadDays,
                          int coverDays, int velocityDays, List<RestockItem> items) {}

    public record Settings(int supplierLeadDays, int coverDays, boolean defaults) {}

    public record Trip(Instant at, UUID orderId, String orderNumber, String trackingNumber, String cityId,
                       String cityName, String outcome, BigDecimal fee, boolean feeEstimated) {}

    public record PieceHistory(String pieceId, String barcode, String shortCode, String status, UUID variantId,
                               String sku, String productTitle, String variantTitle, Instant receivedAt,
                               String location, List<Trip> trips) {}

    public record PieceRow(String pieceId, String barcode, String shortCode, String status, Instant receivedAt,
                           String location, long trips, Instant lastTripAt) {}

    public record VariantPieces(UUID variantId, int minTrips, long total, List<PieceRow> pieces) {}

    /** A piece of any variant with its trip count (GET /analytics/pieces — "Pieces moved 4+ times"). */
    public record TripPiece(String pieceId, String barcode, String shortCode, String status, UUID variantId, String sku,
                            String productTitle, String variantTitle, String location, long trips, Instant lastTripAt) {}

    public record TripPieces(int minTrips, long total, List<TripPiece> pieces) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;
    private final ProductExtrasAnalyticsService extras;
    private final AuditService audit;

    public StockAnalyticsService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides,
                                 ProductExtrasAnalyticsService extras, AuditService audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
        this.extras = extras;
        this.audit = audit;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    boolean hasPieces(UUID tid) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pieces WHERE tenant_id = ? AND status <> 'voided'::piece_status)", Boolean.class, tid));
    }

    // ── trust ───────────────────────────────────────────────────────────────

    static final BigDecimal HIGH_PACKED = new BigDecimal("0.80");
    static final BigDecimal HIGH_MISMATCH = new BigDecimal("0.10");

    /* Parameters: tenant id (allocations), tenant id (piece events), tenant id (orders), now. */
    private static final String PACKED_SQL = """
        WITH traced_orders AS (
            SELECT oi.order_id FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id WHERE a.tenant_id = ?
            UNION
            SELECT e.order_id FROM piece_events e WHERE e.tenant_id = ? AND e.order_id IS NOT NULL
        )
        SELECT COUNT(*) AS delivered, COUNT(t.order_id) AS traced
        FROM merchant_orders o
        LEFT JOIN traced_orders t ON t.order_id = o.id
        WHERE o.tenant_id = ? AND o.external_id NOT LIKE 'internal:exchange:%'
          AND EXISTS (SELECT 1 FROM shipments s
                      WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.provider = 'bosta'
                        AND s.shipment_leg = 'forward' AND COALESCE(s.type_code, '10') NOT IN ('25', '30')
                        AND s.internal_state = 'delivered' AND s.delivered_at >= ?::timestamptz - interval '30 days')
        """;

    /** The tenant's stock trust; level "none" without pieces. */
    Trust trust(UUID tid, Instant now) {
        if (!hasPieces(tid)) return new Trust("none", null, 0, 0, 0, 0, 0, 0, 0, null, 0, null, null, SHOPIFY_SOURCE);
        long[] packed = jdbc.query(PACKED_SQL, rs -> { rs.next(); return new long[] {rs.getLong("delivered"), rs.getLong("traced")}; },
            tid, tid, tid, Timestamp.from(now));
        long traced = 0, shopify = 0, compared = 0, mismatched = 0, units = 0, noFigure = 0;
        Instant oldest = null, newest = null;
        // Slice 10: a variant the Shopify read pass (or inventory_levels/update) has synced compares the
        // pieces available AT the Traced location with Shopify available at that same location
        // (stock_available_shopify_traced); one not synced yet keeps the V153 figure (all locations).
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT pc.available, pc.available_at_traced, v.stock_synced_at IS NOT NULL AS fresh, " +
            "       CASE WHEN v.stock_synced_at IS NOT NULL THEN v.stock_available_shopify_traced " +
            "            ELSE v.shopify_inventory_quantity END AS shopify, " +
            "       COALESCE(v.stock_synced_at, v.shopify_variant_updated_at) AS updated " +
            "FROM (SELECT variant_id, COUNT(*) FILTER (WHERE status = 'available') AS available, " +
            "             COUNT(*) FILTER (WHERE status = 'available' AND current_location_id = " +
            "                 (SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1)) AS available_at_traced " +
            "      FROM pieces WHERE tenant_id = ? AND status <> 'voided'::piece_status GROUP BY variant_id) pc " +
            "JOIN variants v ON v.id = pc.variant_id WHERE v.tenant_id = ?", tid, tid, tid);
        boolean anyFresh = false;
        for (Map<String, Object> r : rows) {
            boolean fresh = Boolean.TRUE.equals(r.get("fresh"));
            anyFresh |= fresh;
            long a = ((Number) r.get(fresh ? "available_at_traced" : "available")).longValue();
            if (r.get("shopify") == null) {
                noFigure++;
                continue;
            }
            long sh = Math.max(0, ((Number) r.get("shopify")).longValue());
            compared++;
            traced += a;
            shopify += sh;
            if (a != sh) mismatched++;
            units += Math.abs(a - sh);
            Instant u = r.get("updated") == null ? null : ((Timestamp) r.get("updated")).toInstant();
            if (u != null && (oldest == null || u.isBefore(oldest))) oldest = u;
            if (u != null && (newest == null || u.isAfter(newest))) newest = u;
        }
        BigDecimal packedPct = AnalyticsSql.rate(packed[1], packed[0]);
        BigDecimal share = compared == 0 ? null
            : shopify == 0 ? (units == 0 ? BigDecimal.ZERO.setScale(4) : BigDecimal.ONE.setScale(4))
            : BigDecimal.valueOf(units).divide(BigDecimal.valueOf(shopify), 4, RoundingMode.HALF_UP);
        String level = packedPct != null && packedPct.compareTo(HIGH_PACKED) >= 0
            && (share == null || share.compareTo(HIGH_MISMATCH) <= 0) ? "high" : "low";
        return new Trust(level, packedPct, packed[0], packed[1], traced, shopify, compared, mismatched, units, share,
            noFigure, oldest, newest, anyFresh ? SHOPIFY_FRESH_SOURCE : SHOPIFY_SOURCE);
    }

    // ── /stock/summary ──────────────────────────────────────────────────────

    /* Parameters: now (×6: age), tenant id. */
    private static final String SUMMARY_SQL = """
        SELECT COUNT(*) FILTER (WHERE p.status = 'available')                                         AS in_wh,
               COALESCE(SUM(v.price) FILTER (WHERE p.status = 'available'), 0)                         AS value_price,
               SUM(v.unit_cost) FILTER (WHERE p.status = 'available' AND v.unit_cost IS NOT NULL)      AS value_cost,
               COUNT(DISTINCT p.variant_id) FILTER (WHERE p.status = 'available')                      AS variants_in_stock,
               COUNT(DISTINCT p.variant_id) FILTER (WHERE p.status = 'available' AND v.unit_cost IS NOT NULL)
                                                                                                        AS variants_costed,
               AVG(a.age) FILTER (WHERE p.status = 'available')                                        AS avg_age,
               COUNT(*) FILTER (WHERE p.status = 'available' AND a.age <= 30)                         AS b1,
               COUNT(*) FILTER (WHERE p.status = 'available' AND a.age BETWEEN 31 AND 60)             AS b2,
               COUNT(*) FILTER (WHERE p.status = 'available' AND a.age BETWEEN 61 AND 90)             AS b3,
               COUNT(*) FILTER (WHERE p.status = 'available' AND a.age > 90)                          AS b4,
               COALESCE(SUM(v.price) FILTER (WHERE p.status = 'available' AND a.age <= 30), 0)        AS v1,
               COALESCE(SUM(v.price) FILTER (WHERE p.status = 'available' AND a.age BETWEEN 31 AND 60), 0) AS v2,
               COALESCE(SUM(v.price) FILTER (WHERE p.status = 'available' AND a.age BETWEEN 61 AND 90), 0) AS v3,
               COALESCE(SUM(v.price) FILTER (WHERE p.status = 'available' AND a.age > 90), 0)         AS v4,
               COUNT(*) FILTER (WHERE p.status = 'on_hold')                                            AS on_hold,
               COUNT(*) FILTER (WHERE p.status = 'damaged')                                            AS damaged,
               SUM(v.unit_cost) FILTER (WHERE p.status = 'damaged' AND v.unit_cost IS NOT NULL)        AS damaged_cost,
               COUNT(*) FILTER (WHERE p.status = 'damaged' AND v.unit_cost IS NOT NULL)                AS damaged_costed
        FROM pieces p
        JOIN variants v ON v.id = p.variant_id
        CROSS JOIN LATERAL (SELECT floor(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 86400)::int AS age) a
        WHERE p.tenant_id = ?
        """;

    @Transactional(readOnly = true)
    public Summary summary(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        if (!hasPieces(tid)) {
            return new Summary(false, period.range(), now, 0, List.of(), money(null), null, 0, 0, null, buckets(null),
                0, new Valued(0, null, 0), new Valued(0, null, 0), 0, trust(tid, now), false);
        }
        Timestamp t = Timestamp.from(now);
        Map<String, Object> r = jdbc.queryForMap(SUMMARY_SQL, t, tid);
        List<LocationCount> locs = jdbc.query(
            "SELECT p.current_location_id AS id, COALESCE(l.name, 'No location') AS name, COUNT(*) AS n " +
            "FROM pieces p LEFT JOIN locations l ON l.id = p.current_location_id " +
            "WHERE p.tenant_id = ? AND p.status = 'available' GROUP BY 1, 2 ORDER BY n DESC, name",
            (rs, i) -> new LocationCount(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("n")), tid);
        Valued lost = jdbc.query(
            "SELECT COUNT(*) AS n, SUM(v.unit_cost) FILTER (WHERE v.unit_cost IS NOT NULL) AS cost, " +
            "       COUNT(*) FILTER (WHERE v.unit_cost IS NOT NULL) AS costed " +
            "FROM piece_events e JOIN pieces p ON p.id = e.piece_id JOIN variants v ON v.id = p.variant_id " +
            "WHERE e.tenant_id = ? AND e.to_status IN ('lost', 'destroyed') " +
            "  AND e.from_status IS DISTINCT FROM e.to_status " +
            "  AND e.occurred_at >= ? AND e.occurred_at < ?",
            rs -> { rs.next(); return new Valued(rs.getLong("n"), moneyOrNull(rs.getBigDecimal("cost")), rs.getLong("costed")); },
            tid, Timestamp.from(period.startInclusive()), Timestamp.from(period.endExclusive()));
        // The same rule as tripPieces(4, …) — the list the card opens: voided pieces don't count.
        Long moved = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT e.piece_id FROM piece_events e WHERE e.tenant_id = ? AND " + trip("e") +
            "GROUP BY e.piece_id HAVING COUNT(*) >= 4) x " +
            "JOIN pieces p ON p.id = x.piece_id AND p.tenant_id = ? AND p.status <> 'voided'::piece_status", Long.class, tid, tid);
        Object avg = r.get("avg_age");
        Trust trust = trust(tid, now);
        return new Summary(true, period.range(), now, num(r, "in_wh"), locs, money(dec(r, "value_price")),
            moneyOrNull(dec(r, "value_cost")), num(r, "variants_costed"), num(r, "variants_in_stock"),
            avg == null ? null : new BigDecimal(avg.toString()).setScale(1, RoundingMode.HALF_UP),
            buckets(r), num(r, "on_hold"),
            new Valued(num(r, "damaged"), moneyOrNull(dec(r, "damaged_cost")), num(r, "damaged_costed")),
            lost, moved == null ? 0 : moved, trust, trust.low());
    }

    private static List<AgeBucket> buckets(Map<String, Object> r) {
        String[] keys = {"0-30", "31-60", "61-90", "90+"};
        List<AgeBucket> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(new AgeBucket(keys[i], r == null ? 0 : num(r, "b" + (i + 1)),
                money(r == null ? null : dec(r, "v" + (i + 1)))));
        }
        return out;
    }

    // ── /stock/variants ─────────────────────────────────────────────────────

    /*
     * Per variant: the last 30 days' sold / delivered units (soldLines + ORDER_OUTCOMES +
     * LINE_RETURNS over that window), its last sale (SalesAnalyticsService.LAST_SOLD_CTE) and its
     * pieces. Parameters: the shared 13, last_sold's 2 tenant ids, now, tenant id (pieces), tenant id
     * (variants), Shopify mode (a variant with only a Shopify figure is listed too).
     */
    private static final String VARIANTS_SQL = SalesAnalyticsService.soldLines(false)
        + SalesAnalyticsService.ORDER_OUTCOMES + SalesAnalyticsService.LINE_RETURNS + ", "
        + SalesAnalyticsService.LAST_SOLD_CTE + """
        , sales30 AS (
            SELECT variant_id, SUM(qty) AS sold, COALESCE(SUM(qty) FILTER (WHERE outcome = 'delivered'), 0) AS delivered
            FROM line_facts
            GROUP BY variant_id
        ),
        stock AS (
            SELECT p.variant_id,
                   COUNT(*) FILTER (WHERE p.status = 'available')                                            AS on_hand,
                   COUNT(*) FILTER (WHERE p.status IN ('return_in_transit', 'return_pending_inspection'))    AS coming_back,
                   AVG(floor(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 86400))
                       FILTER (WHERE p.status = 'available')                                                 AS avg_age
            FROM pieces p
            WHERE p.tenant_id = ?
            GROUP BY p.variant_id
        )
        SELECT v.id AS variant_id, pr.title AS product_title, v.title AS variant_title, v.sku, v.price, v.unit_cost,
               CASE WHEN v.stock_synced_at IS NOT NULL THEN v.stock_available_shopify
                    ELSE v.shopify_inventory_quantity END AS shopify,
               COALESCE(st.on_hand, 0) AS on_hand, COALESCE(st.coming_back, 0) AS coming_back, st.avg_age,
               COALESCE(s.sold, 0) AS sold, COALESCE(s.delivered, 0) AS delivered, ls.last_sold_at
        FROM variants v
        JOIN products pr ON pr.id = v.product_id
        LEFT JOIN stock st  ON st.variant_id = v.id
        LEFT JOIN sales30 s ON s.variant_id = v.id
        LEFT JOIN last_sold ls ON ls.variant_id = v.id
        WHERE v.tenant_id = ?
          AND (COALESCE(st.on_hand, 0) > 0 OR COALESCE(st.coming_back, 0) > 0 OR COALESCE(s.sold, 0) > 0
               OR (?::boolean AND COALESCE(CASE WHEN v.stock_synced_at IS NOT NULL THEN v.stock_available_shopify
                                                ELSE v.shopify_inventory_quantity END, 0) > 0))
        """;

    /** Every stocked or selling variant with its stock figures (no sort / filter). */
    List<VariantStock> loadVariants(UUID tid, Instant now, boolean useShopify) {
        LocalDate today = today();
        AnalyticsPeriod last30 = new AnalyticsPeriod(today.minusDays(VELOCITY_DAYS - 1L), today);
        List<Object[]> rows = new ArrayList<>();
        jdbc.query(VARIANTS_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, last30, overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);        // outcomes 2, returns 4
            ps.setObject(i++, tid);                                    // last_sold
            ps.setObject(i++, tid);
            ps.setTimestamp(i++, Timestamp.from(now));                 // stock: age
            ps.setObject(i++, tid);
            ps.setObject(i++, tid);                                    // variants
            ps.setBoolean(i, useShopify);
        }, rs -> {
            rows.add(new Object[] {rs.getObject("variant_id", UUID.class), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku"), rs.getBigDecimal("price"), rs.getBigDecimal("unit_cost"),
                rs.getLong("on_hand"), rs.getLong("coming_back"), rs.getBigDecimal("avg_age"), rs.getLong("sold"),
                rs.getLong("delivered"), OrderFacts.instant(rs.getTimestamp("last_sold_at")),
                rs.getObject("shopify") == null ? null : rs.getInt("shopify")});
        });

        AnalyticsPeriod last90 = new AnalyticsPeriod(today.minusDays(RETURNS_DAYS - 1L), today);
        Map<UUID, ProductExtrasAnalyticsService.VariantRow> returns = new HashMap<>();
        for (ProductExtrasAnalyticsService.VariantRow r : extras.variantRows(last90)) returns.put(r.variantId(), r);
        Map<UUID, String> reasons = new HashMap<>();
        jdbc.query(
            "SELECT DISTINCT ON (rri.variant_id) rri.variant_id, rri.reason_code " +
            "FROM return_request_items rri JOIN return_requests rr ON rr.id = rri.request_id " +
            "WHERE rri.tenant_id = ? AND rr.status <> 'rejected' AND rr.created_at >= ? AND rri.variant_id IS NOT NULL " +
            "GROUP BY rri.variant_id, rri.reason_code " +
            "ORDER BY rri.variant_id, COUNT(*) DESC, rri.reason_code",
            rs -> { reasons.put(rs.getObject("variant_id", UUID.class), rs.getString("reason_code")); },
            tid, Timestamp.from(last90.startInclusive()));

        Instant deadBefore = now.minus(java.time.Duration.ofDays(DEAD_STOCK_DAYS));
        List<VariantStock> out = new ArrayList<>();
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            BigDecimal price = (BigDecimal) r[4], cost = (BigDecimal) r[5];
            long onHand = (Long) r[6], comingBack = (Long) r[7], sold = (Long) r[9], delivered = (Long) r[10];
            Instant lastSold = (Instant) r[11];
            Integer shopify = (Integer) r[12];
            boolean fromShopify = useShopify && shopify != null;
            long stock = fromShopify ? Math.max(0, shopify) : onHand;
            BigDecimal velocity = velocity(delivered);
            BigDecimal cover = cover(stock, delivered);
            BigDecimal sellThrough = AnalyticsSql.rate(sold, sold + stock);
            BigDecimal age = r[8] == null ? null : ((BigDecimal) r[8]).setScale(1, RoundingMode.HALF_UP);
            ProductExtrasAnalyticsService.VariantRow ret = returns.get(id);
            long returned = ret == null ? 0 : ret.returned(), exchanged = ret == null ? 0 : ret.exchanged();
            long delivered90 = ret == null ? 0 : ret.deliveredUnits();
            boolean runningLow = delivered > 0 && cover.compareTo(RUNNING_LOW_DAYS) <= 0;
            boolean dead = stock > 0 && (lastSold == null || lastSold.isBefore(deadBefore));
            boolean atCost = cost != null;
            BigDecimal value = money(BigDecimal.valueOf(stock).multiply(atCost ? cost : price == null ? BigDecimal.ZERO : price));
            out.add(new VariantStock(id, (String) r[1], (String) r[2], (String) r[3], onHand, comingBack, velocity,
                cover, sellThrough, age, lastSold, sold, delivered, AnalyticsSql.rate(returned + exchanged, delivered90),
                returned, exchanged, reasons.get(id), runningLow, dead, value, atCost, shopify, stock,
                fromShopify ? "shopify" : "pieces"));
        }
        return out;
    }

    /** Delivered units per day over the velocity window, 2 decimals. */
    static BigDecimal velocity(long delivered) {
        return BigDecimal.valueOf(delivered).divide(BigDecimal.valueOf(VELOCITY_DAYS), 2, RoundingMode.HALF_UP);
    }

    /** On hand ÷ velocity in days (1 decimal); null without deliveries. */
    static BigDecimal cover(long onHand, long delivered) {
        return delivered == 0 ? null
            : BigDecimal.valueOf(onHand * (long) VELOCITY_DAYS).divide(BigDecimal.valueOf(delivered), 1, RoundingMode.HALF_UP);
    }

    public static final List<String> SORTS = List.of("velocity", "daysOfCover", "onHand", "age", "sellThrough", "cash", "lastSold");
    public static final List<String> FILTERS = List.of("all", "running_low", "dead_stock");

    @Transactional(readOnly = true)
    public Variants variants(String sort, String filter, int limit) {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        Trust trust = trust(tid, now);
        if ("none".equals(trust.level())) return new Variants(false, now, "none", "pieces", sort, filter, 0, List.of());
        List<VariantStock> all = new ArrayList<>(loadVariants(tid, now, trust.low()));
        if ("running_low".equals(filter)) all.removeIf(v -> !v.runningLow());
        if ("dead_stock".equals(filter)) all.removeIf(v -> !v.deadStock());
        Comparator<VariantStock> byName = Comparator.comparing(VariantStock::productTitle, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(VariantStock::variantTitle, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(v -> v.variantId().toString());
        Comparator<VariantStock> c = switch (sort) {
            case "daysOfCover" -> Comparator.comparing(VariantStock::daysOfCover, Comparator.nullsLast(Comparator.naturalOrder()));
            case "onHand" -> Comparator.comparingLong(VariantStock::stockUsed).reversed();
            case "age" -> Comparator.comparing(VariantStock::avgPieceAgeDays, Comparator.nullsLast(Comparator.reverseOrder()));
            case "sellThrough" -> Comparator.comparing(VariantStock::sellThrough, Comparator.nullsLast(Comparator.reverseOrder()));
            case "cash" -> Comparator.comparing(VariantStock::stockValue).reversed();
            case "lastSold" -> Comparator.comparing(VariantStock::lastSoldAt, Comparator.nullsFirst(Comparator.naturalOrder()));
            default -> Comparator.comparing(VariantStock::velocityPerDay).reversed();
        };
        all.sort(c.thenComparing(byName));
        return new Variants(true, now, trust.level(), trust.low() ? "shopify" : "pieces", sort, filter, all.size(),
            List.copyOf(all.subList(0, Math.min(limit, all.size()))));
    }

    // ── /stock/restock ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Restock restock() {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        Settings s = settingsOf(tid);
        Trust trust = trust(tid, now);
        if ("none".equals(trust.level())) {
            return new Restock(false, now, "none", "pieces", s.supplierLeadDays(), s.coverDays(), VELOCITY_DAYS, List.of());
        }
        Map<UUID, BigDecimal> costs = new HashMap<>();
        jdbc.query("SELECT id, unit_cost FROM variants WHERE tenant_id = ? AND unit_cost IS NOT NULL",
            rs -> { costs.put(rs.getObject("id", UUID.class), rs.getBigDecimal("unit_cost")); }, tid);
        List<RestockItem> items = new ArrayList<>();
        for (VariantStock v : loadVariants(tid, now, trust.low())) {
            long units = suggestedUnits(v.deliveredUnits30(), s.supplierLeadDays() + s.coverDays(), v.stockUsed(), v.comingBack());
            if (units <= 0) continue;
            BigDecimal cost = costs.get(v.variantId());
            items.add(new RestockItem(v.variantId(), v.productTitle(), v.variantTitle(), v.sku(), v.velocityPerDay(),
                v.stockUsed(), v.comingBack(), v.daysOfCover(), units,
                cost == null ? null : money(cost.multiply(BigDecimal.valueOf(units)))));
        }
        items.sort(Comparator.comparingLong(RestockItem::suggestedUnits).reversed()
            .thenComparing(RestockItem::velocityPerDay, Comparator.reverseOrder())
            .thenComparing(i -> i.variantId().toString()));
        return new Restock(true, now, trust.level(), trust.low() ? "shopify" : "pieces", s.supplierLeadDays(), s.coverDays(),
            VELOCITY_DAYS, items);
    }

    /**
     * THE restock rule: velocity × (lead time + cover days) − on hand − coming back, rounded up to
     * whole units, never below 0. Velocity is unrounded here (delivered units ÷ 30).
     */
    static long suggestedUnits(long delivered30, int days, long onHand, long comingBack) {
        BigDecimal need = BigDecimal.valueOf(delivered30 * (long) days)
            .divide(BigDecimal.valueOf(VELOCITY_DAYS), 4, RoundingMode.HALF_UP)
            .subtract(BigDecimal.valueOf(onHand + comingBack));
        return Math.max(0, need.setScale(0, RoundingMode.CEILING).longValueExact());
    }

    // ── /settings ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Settings settings() {
        return settingsOf(TenantContext.require());
    }

    private Settings settingsOf(UUID tid) {
        List<Settings> s = jdbc.query("SELECT supplier_lead_days, cover_days FROM analytics_settings WHERE tenant_id = ?",
            (rs, i) -> new Settings(rs.getInt(1), rs.getInt(2), false), tid);
        return s.isEmpty() ? new Settings(DEFAULT_LEAD_DAYS, DEFAULT_COVER_DAYS, true) : s.get(0);
    }

    /** Upserts the tenant's settings (validated by the caller) and records the change in audit_log. */
    @Transactional
    public Settings saveSettings(int leadDays, int coverDays, UUID actor) {
        UUID tid = TenantContext.require();
        Settings before = settingsOf(tid);
        jdbc.update("INSERT INTO analytics_settings (tenant_id, supplier_lead_days, cover_days, updated_by, updated_at) " +
                    "VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id) DO UPDATE SET " +
                    "supplier_lead_days = EXCLUDED.supplier_lead_days, cover_days = EXCLUDED.cover_days, " +
                    "updated_by = EXCLUDED.updated_by, updated_at = now()", tid, leadDays, coverDays, actor);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("supplierLeadDays", Map.of("from", before.supplierLeadDays(), "to", leadDays));
        meta.put("coverDays", Map.of("from", before.coverDays(), "to", coverDays));
        audit.record(actor, "analytics_settings_update", null, null, meta);
        return new Settings(leadDays, coverDays, false);
    }

    // ── /pieces/{id}/history, /variants/{id}/pieces ─────────────────────────

    /*
     * A piece's trips. The trip's leg: the event's shipment, else the order's forward leg booked
     * before the event (the newest). Its outcome: the s2 rule; with no leg, a delivered move is
     * 'delivered' (a self-pickup handover), anything else 'unknown'. Parameters: piece id, tenant id.
     */
    private static final String TRIPS_SQL = """
        SELECT e.occurred_at, e.order_id, o.number, leg.tracking_number, leg.city_id, leg.city,
               CASE WHEN leg.shipment_id IS NULL THEN CASE WHEN e.to_status = 'delivered' THEN 'delivered' ELSE 'unknown' END
        """ + SalesAnalyticsService.LEG_OUTCOME_WHENS + """
               END AS outcome,
               leg.fee, leg.estimated
        FROM piece_events e
        LEFT JOIN merchant_orders o ON o.id = e.order_id
        LEFT JOIN LATERAL (
            SELECT s.id AS shipment_id, s.tracking_number, s.internal_state, s.collected_from_business_at, s.type_code,
                   s.city_id, s.city_name AS city,
                   """ + SettlementSql.fee("s") + """
                    AS fee,
                   NOT """ + SettlementSql.settled("s") + """
                    AS estimated
            FROM shipments s
            WHERE s.tenant_id = e.tenant_id
              AND (s.id = e.shipment_id
                   OR (e.shipment_id IS NULL AND s.order_id = e.order_id AND s.shipment_leg = 'forward'
                       AND s.created_at <= e.occurred_at))
            ORDER BY (s.id = e.shipment_id) DESC NULLS LAST, s.created_at DESC, s.id DESC
            LIMIT 1
        ) leg ON true
        LEFT JOIN LATERAL (
            SELECT MIN(hh.occurred_at) FILTER (WHERE hh.internal_state = 'delivered')                AS first_delivered,
                   MIN(hh.occurred_at) FILTER (WHERE hh.internal_state IN ('returning', 'returned')) AS first_return,
                   COALESCE(bool_or(hh.internal_state IN ('with_courier', 'returning', 'returned', 'delivered', 'lost')), false)
                                                                                                      AS picked_up
            FROM shipment_status_history hh
            WHERE hh.shipment_id = leg.shipment_id
        ) h ON true
        WHERE e.piece_id = ? AND e.tenant_id = ? AND """ + trip("e") + """
        ORDER BY e.occurred_at, e.id
        """;

    /** Null when the piece isn't the tenant's (or doesn't exist). */
    @Transactional(readOnly = true)
    public PieceHistory pieceHistory(String pieceId) {
        UUID tid = TenantContext.require();
        List<PieceHistory> p = jdbc.query(
            "SELECT p.id, p.barcode, p.short_code, p.status::text AS status, p.variant_id, v.sku, pr.title AS product_title, " +
            "       v.title AS variant_title, p.created_at, l.name AS location " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN locations l ON l.id = p.current_location_id WHERE p.id = ? AND p.tenant_id = ?",
            (rs, i) -> new PieceHistory(rs.getString("id"), rs.getString("barcode"), rs.getString("short_code"),
                rs.getString("status"), rs.getObject("variant_id", UUID.class), rs.getString("sku"),
                rs.getString("product_title"), rs.getString("variant_title"),
                OrderFacts.instant(rs.getTimestamp("created_at")), rs.getString("location"), null),
            pieceId, tid);
        if (p.isEmpty()) return null;
        List<Trip> trips = jdbc.query(TRIPS_SQL, (rs, i) -> new Trip(OrderFacts.instant(rs.getTimestamp("occurred_at")),
            rs.getObject("order_id", UUID.class), rs.getString("number"), rs.getString("tracking_number"),
            rs.getString("city_id"), rs.getString("city"), rs.getString("outcome"), moneyOrNull(rs.getBigDecimal("fee")),
            rs.getBigDecimal("fee") != null && rs.getBoolean("estimated")), pieceId, tid);
        PieceHistory h = p.get(0);
        return new PieceHistory(h.pieceId(), h.barcode(), h.shortCode(), h.status(), h.variantId(), h.sku(),
            h.productTitle(), h.variantTitle(), h.receivedAt(), h.location(), trips);
    }

    public static final int MAX_PIECES = 500;

    @Transactional(readOnly = true)
    public VariantPieces variantPieces(UUID variantId, int minTrips, int limit) {
        UUID tid = TenantContext.require();
        String base = "FROM pieces p LEFT JOIN locations l ON l.id = p.current_location_id " +
            "LEFT JOIN LATERAL (SELECT COUNT(*) AS trips, MAX(e.occurred_at) AS last_trip FROM piece_events e " +
            "                   WHERE e.piece_id = p.id AND e.tenant_id = p.tenant_id AND " + trip("e") + ") t ON true " +
            "WHERE p.tenant_id = ? AND p.variant_id = ? AND p.status <> 'voided'::piece_status AND t.trips >= ? ";
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + base, Long.class, tid, variantId, minTrips);
        List<PieceRow> rows = jdbc.query(
            "SELECT p.id, p.barcode, p.short_code, p.status::text AS status, p.created_at, l.name AS location, " +
            "       t.trips, t.last_trip " + base + "ORDER BY t.trips DESC, p.created_at, p.id LIMIT ?",
            (rs, i) -> new PieceRow(rs.getString("id"), rs.getString("barcode"), rs.getString("short_code"),
                rs.getString("status"), OrderFacts.instant(rs.getTimestamp("created_at")), rs.getString("location"),
                rs.getLong("trips"), OrderFacts.instant(rs.getTimestamp("last_trip"))),
            tid, variantId, minTrips, limit);
        return new VariantPieces(variantId, minTrips, total == null ? 0 : total, rows);
    }

    /**
     * Every piece of the tenant with at least {@code minTrips} trips (the same trip rule and the same
     * voided exclusion as {@link #variantPieces}), most trips first, then oldest. The tenant-wide list
     * behind the Stock health "Pieces moved 4+ times" card; {@code total} counts them all.
     */
    @Transactional(readOnly = true)
    public TripPieces tripPieces(int minTrips, int limit) {
        UUID tid = TenantContext.require();
        String base = "FROM (SELECT e.piece_id, COUNT(*) AS trips, MAX(e.occurred_at) AS last_trip FROM piece_events e " +
            "      WHERE e.tenant_id = ? AND " + trip("e") + " GROUP BY e.piece_id HAVING COUNT(*) >= ?) t " +
            "JOIN pieces p ON p.id = t.piece_id AND p.tenant_id = ? AND p.status <> 'voided'::piece_status " +
            "JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN locations l ON l.id = p.current_location_id ";
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + base, Long.class, tid, minTrips, tid);
        List<TripPiece> rows = jdbc.query(
            "SELECT p.id, p.barcode, p.short_code, p.status::text AS status, p.variant_id, v.sku, pr.title AS product_title, " +
            "       v.title AS variant_title, l.name AS location, t.trips, t.last_trip " + base +
            "ORDER BY t.trips DESC, p.created_at, p.id LIMIT ?",
            (rs, i) -> new TripPiece(rs.getString("id"), rs.getString("barcode"), rs.getString("short_code"), rs.getString("status"),
                rs.getObject("variant_id", UUID.class), rs.getString("sku"), rs.getString("product_title"), rs.getString("variant_title"),
                rs.getString("location"), rs.getLong("trips"), OrderFacts.instant(rs.getTimestamp("last_trip"))),
            tid, minTrips, tid, limit);
        return new TripPieces(minTrips, total == null ? 0 : total, rows);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static long num(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? 0 : ((Number) v).longValue();
    }

    static BigDecimal dec(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static BigDecimal moneyOrNull(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
