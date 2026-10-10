package com.traceability.analytics;

import com.traceability.analytics.AnalyticsSql.Compared;
import com.traceability.privacy.CustomerSubject;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;
import static com.traceability.analytics.AnalyticsSql.rate;

/**
 * Analytics slice 6 — customers, since the store connected to Traced. Owner-only (controller).
 *
 * Identity = orders.customer_key (V149: 'c:' + Shopify customer id, else 'p:' + canonical phone).
 * The key is NEVER returned (it can hold a phone number): responses carry {@code customerRef} — an
 * HMAC-SHA256 of tenant + key under a server-side secret (analytics.ref-secret, env
 * ANALYTICS_REF_SECRET, ≥ 32 bytes; the app refuses to start without it), never a plain hash,
 * because phone numbers are guessable — and the display name (first name + last initial). No phone ever
 * leaves SQL — the blocklist match ({@code blocked}) is computed in the statement with the
 * blocklist's own canonical form (CustomerSubject.canonicalPhoneSql) and read-only.
 *
 * Orders = the s5 OrderFacts cohort (post-floor, not cancelled, not an internal exchange order, a
 * sold line) of all time since connect. Connect = the store's analytics floor (orders_ingest_from,
 * else the floor override); a store with neither (Jumi) — its first ingested order.
 *
 * Every order of a known customer has ONE class:
 *   existing  — the customer was created in Shopify before connect (orders.customer_created_at, V155);
 *   new       — the customer's first order since connect, customer created on / after connect;
 *   unknown   — the customer's first order since connect, no creation date (e.g. phone-only orders);
 *   returning — a 2nd or later order since connect of a customer who isn't existing.
 * In a period a customer counts in the class of their first order in that period, so new +
 * existing + returning + unknown = customers who ordered. Repeat purchase rate = of those, the
 * share with 2+ orders since connect up to the period end. Success rate = delivered ÷ (delivered +
 * failed), the one definition.
 */
@Service
public class CustomerAnalyticsService {

    public static final List<String> CLASSES = List.of("new", "existing", "returning", "unknown");
    static final int TOP = 50;
    static final int GOVERNORATE_MIN_CUSTOMERS = 10;
    static final int WATCH_MIN_REFUSED = 2;
    static final int WATCH_LIMIT = 100;

    public record ClassFigures(String key, long customers, long orders, BigDecimal booked, BigDecimal realized,
                               long delivered, long failed, BigDecimal successRate) {}

    public record Summary(long customersWhoOrdered, long newCustomers, long existingCustomers, long returningCustomers,
                          long unknownCustomers, BigDecimal repeatPurchaseRate, BigDecimal medianDaysBetweenOrders,
                          long ordersWithoutCustomer, List<ClassFigures> byClass) {}

    public record TopCustomer(String customerRef, String displayName, String governorate, String governorateAr,
                              String customerType, long orders, long delivered, long failed, BigDecimal successRate,
                              BigDecimal realized, Instant firstOrderAt, Instant lastOrderAt) {}

    public record Top(Instant asOf, long totalCustomers, List<TopCustomer> customers) {}

    public record GovernorateRepeat(String key, String label, String labelAr, long customers, long repeatCustomers,
                                    BigDecimal repeatRate) {}

    public record ByGovernorate(Instant asOf, int minCustomers, List<GovernorateRepeat> governorates) {}

    public record Cohort(String month, long customers, long existingCustomers, List<BigDecimal> orderedAgainPct,
                         List<Boolean> monthComplete) {}

    public record Cohorts(Instant asOf, List<Cohort> cohorts) {}

    public record Watched(String customerRef, String displayName, String governorate, String governorateAr,
                          long orders, long refusedCodOrders, long deliveredOrders, BigDecimal refusedValue,
                          boolean blocked, String suggestion, String blocklistLink) {}

    public record Watch(Instant asOf, int minRefused, List<Watched> customers) {}

    /** One loaded order. */
    record CO(UUID orderId, Instant placedAt, BigDecimal booked, String outcome, String cityId, String city,
              BigDecimal returned, String key, Instant createdAt, String name, String province, Instant connectAt,
              BigDecimal legCod, boolean blocked) {

        BigDecimal realized() {
            return "delivered".equals(outcome) ? booked.subtract(returned) : BigDecimal.ZERO;
        }

        boolean failed() {
            return "refused".equals(outcome) || "other_terminal".equals(outcome);
        }
    }

    /** A customer's orders since connect, oldest first. */
    record Cust(String key, List<CO> orders) {

        Instant createdAt() {
            Instant c = null;
            for (CO o : orders) if (o.createdAt() != null && (c == null || o.createdAt().isBefore(c))) c = o.createdAt();
            return c;
        }

        boolean existing() {
            Instant c = createdAt();
            Instant connect = orders.get(0).connectAt();
            return c != null && connect != null && c.isBefore(connect);
        }

        /** THE order-class rule (see the class comment). */
        String classOf(int index) {
            if (existing()) return "existing";
            if (index > 0) return "returning";
            return createdAt() == null ? "unknown" : "new";
        }

        String type() {
            return existing() ? "existing" : createdAt() == null ? "unknown" : "new";
        }

        CO latestNamed() {
            for (int i = orders.size() - 1; i >= 0; i--) if (orders.get(i).name() != null) return orders.get(i);
            return orders.get(orders.size() - 1);
        }
    }

    /*
     * OrderFacts' CTEs, then per order: the customer fields, the store's connect instant, the deciding
     * leg's COD and the blocklist flag. Parameters: the shared 13, then tenant id (first orders).
     */
    static final String CUSTOMERS_SQL = OrderFacts.SQL.substring(0, OrderFacts.SQL.indexOf("SELECT om.order_id, om.placed_at,"))
        + """
        SELECT om.order_id, om.placed_at, om.booked, om.outcome, om.city_id, om.city, om.returned_rev,
               o.customer_key, o.customer_created_at, o.customer_name, o.ship_province AS province_code,
               COALESCE(f.floor_at, fs.first_at) AS connect_at,
               COALESCE(lg.cod_amount, lg.raw_cod) AS leg_cod,
               (o.customer_phone IS NOT NULL AND EXISTS (
                   SELECT 1 FROM blocklist b
                   WHERE b.tenant_id = o.tenant_id AND b.active
                     AND b.phone_canonical = """ + CustomerSubject.canonicalPhoneSql("o.customer_phone") + """
               )) AS blocked
        FROM order_money om
        JOIN merchant_orders o  ON o.id = om.order_id
        JOIN floors f  ON f.store_id = o.store_id
        LEFT JOIN (SELECT store_id, MIN(placed_at) AS first_at FROM merchant_orders WHERE tenant_id = ? GROUP BY store_id) fs
               ON fs.store_id = o.store_id
        LEFT JOIN shipments lg ON lg.id = om.shipment_id
        """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;
    private final byte[] refSecret;

    public CustomerAnalyticsService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides,
                                    @org.springframework.beans.factory.annotation.Value("${analytics.ref-secret:}") String refSecret) {
        if (refSecret == null || refSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "analytics.ref-secret (ANALYTICS_REF_SECRET) must be set to at least 32 bytes — it keys the customer references");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
        this.refSecret = refSecret.getBytes(StandardCharsets.UTF_8);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    /** Every order since connect, oldest first (placed_at, id). */
    List<CO> load(UUID tid) {
        AnalyticsPeriod all = new AnalyticsPeriod(LocalDate.of(2000, 1, 1), today());
        List<CO> rows = jdbc.query(CUSTOMERS_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, all, overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);
            ps.setObject(i, tid);
        }, (rs, n) -> row(rs));
        rows.sort(Comparator.comparing(CO::placedAt).thenComparing(o -> o.orderId().toString()));
        return rows;
    }

    private static CO row(ResultSet rs) throws SQLException {
        return new CO(rs.getObject("order_id", UUID.class), OrderFacts.instant(rs.getTimestamp("placed_at")),
            OrderFacts.nz(rs.getBigDecimal("booked")), rs.getString("outcome"), rs.getString("city_id"), rs.getString("city"),
            OrderFacts.nz(rs.getBigDecimal("returned_rev")), rs.getString("customer_key"),
            OrderFacts.instant(rs.getTimestamp("customer_created_at")), rs.getString("customer_name"),
            rs.getString("province_code"), OrderFacts.instant(rs.getTimestamp("connect_at")), rs.getBigDecimal("leg_cod"),
            rs.getBoolean("blocked"));
    }

    static Map<String, Cust> customers(List<CO> orders) {
        Map<String, List<CO>> byKey = new LinkedHashMap<>();
        for (CO o : orders) if (o.key() != null) byKey.computeIfAbsent(o.key(), k -> new ArrayList<>()).add(o);
        Map<String, Cust> out = new LinkedHashMap<>();
        byKey.forEach((k, v) -> out.put(k, new Cust(k, v)));
        return out;
    }

    // ── /customers/summary ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Compared<Summary> summary(AnalyticsPeriod period, boolean compare) {
        UUID tid = TenantContext.require();
        AnalyticsPeriod prev = AnalyticsSql.previousOrNull(period, compare);
        List<CO> orders = load(tid);
        Map<String, Cust> custs = customers(orders);
        return new Compared<>(period.range(), AnalyticsSql.rangeOf(prev), summarise(orders, custs, period),
            prev == null ? null : summarise(orders, custs, prev));
    }

    static Summary summarise(List<CO> all, Map<String, Cust> custs, AnalyticsPeriod p) {
        Instant from = p.startInclusive(), to = p.endExclusive();
        Map<String, long[]> classCounts = new LinkedHashMap<>();                 // customers, orders, delivered, failed
        Map<String, BigDecimal[]> classMoney = new LinkedHashMap<>();            // booked, realized
        for (String c : CLASSES) {
            classCounts.put(c, new long[4]);
            classMoney.put(c, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        long noCustomer = 0;
        for (CO o : all) if (o.key() == null && in(o, from, to)) noCustomer++;

        long who = 0, repeaters = 0;
        List<Double> gaps = new ArrayList<>();
        for (Cust c : custs.values()) {
            List<CO> os = c.orders();
            boolean counted = false;
            int upToEnd = 0;
            for (int i = 0; i < os.size(); i++) {
                CO o = os.get(i);
                if (o.placedAt().isBefore(to)) upToEnd++;
                if (!in(o, from, to)) continue;
                String cls = c.classOf(i);
                long[] k = classCounts.get(cls);
                if (!counted) {
                    k[0]++;
                    counted = true;
                    who++;
                }
                k[1]++;
                if ("delivered".equals(o.outcome())) k[2]++;
                if (o.failed()) k[3]++;
                BigDecimal[] mny = classMoney.get(cls);
                mny[0] = mny[0].add(o.booked());
                mny[1] = mny[1].add(o.realized());
                if (i > 0) gaps.add((o.placedAt().toEpochMilli() - os.get(i - 1).placedAt().toEpochMilli()) / 86_400_000d);
            }
            if (counted && upToEnd >= 2) repeaters++;
        }
        List<ClassFigures> byClass = new ArrayList<>();
        for (String c : CLASSES) {
            long[] k = classCounts.get(c);
            BigDecimal[] mny = classMoney.get(c);
            byClass.add(new ClassFigures(c, k[0], k[1], money(mny[0]), money(mny[1]), k[2], k[3], rate(k[2], k[2] + k[3])));
        }
        return new Summary(who, classCounts.get("new")[0], classCounts.get("existing")[0], classCounts.get("returning")[0],
            classCounts.get("unknown")[0], rate(repeaters, who), median(gaps), noCustomer, byClass);
    }

    static boolean in(CO o, Instant from, Instant to) {
        return !o.placedAt().isBefore(from) && o.placedAt().isBefore(to);
    }

    static BigDecimal median(List<Double> v) {
        if (v.isEmpty()) return null;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
        return BigDecimal.valueOf(m).setScale(1, RoundingMode.HALF_UP);
    }

    // ── /customers/top ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Top top(int limit) {
        UUID tid = TenantContext.require();
        OrderFacts.Cities cities = OrderFacts.cities(jdbc);
        Map<String, Cust> custs = customers(load(tid));
        List<TopCustomer> out = new ArrayList<>();
        for (Cust c : custs.values()) {
            long delivered = 0, failed = 0;
            BigDecimal realized = BigDecimal.ZERO;
            for (CO o : c.orders()) {
                if ("delivered".equals(o.outcome())) delivered++;
                if (o.failed()) failed++;
                realized = realized.add(o.realized());
            }
            String[] g = governorate(c, cities);
            out.add(new TopCustomer(ref(tid, c.key()), OrderFinanceService.displayName(c.latestNamed().name()), g[1], g[2],
                c.type(), c.orders().size(), delivered, failed, rate(delivered, delivered + failed), money(realized),
                c.orders().get(0).placedAt(), c.orders().get(c.orders().size() - 1).placedAt()));
        }
        out.sort(Comparator.comparing(TopCustomer::realized).reversed()
            .thenComparing(Comparator.comparingLong(TopCustomer::orders).reversed())
            .thenComparing(TopCustomer::customerRef));
        return new Top(clock.instant(), custs.size(), List.copyOf(out.subList(0, Math.min(limit, out.size()))));
    }

    // ── /customers/by-governorate ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public ByGovernorate byGovernorate() {
        UUID tid = TenantContext.require();
        OrderFacts.Cities cities = OrderFacts.cities(jdbc);
        Map<String, String[]> names = new HashMap<>();
        Map<String, long[]> counts = new LinkedHashMap<>();                       // customers, repeat customers
        for (Cust c : customers(load(tid)).values()) {
            String[] g = governorate(c, cities);
            names.putIfAbsent(g[0], g);
            long[] k = counts.computeIfAbsent(g[0], x -> new long[2]);
            k[0]++;
            if (c.orders().size() >= 2) k[1]++;
        }
        List<GovernorateRepeat> out = new ArrayList<>();
        long[] other = new long[2];
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long[] k = e.getValue();
            if (k[0] >= GOVERNORATE_MIN_CUSTOMERS) {
                String[] g = names.get(e.getKey());
                out.add(new GovernorateRepeat(g[0], g[1], g[2], k[0], k[1], rate(k[1], k[0])));
            } else {
                other[0] += k[0];
                other[1] += k[1];
            }
        }
        out.sort(Comparator.comparingLong(GovernorateRepeat::customers).reversed().thenComparing(GovernorateRepeat::label));
        if (other[0] > 0) out.add(new GovernorateRepeat("other", "Other", "أخرى", other[0], other[1], rate(other[1], other[0])));
        return new ByGovernorate(clock.instant(), GOVERNORATE_MIN_CUSTOMERS, out);
    }

    /** The customer's governorate: their latest order's (the s5 rule — Bosta city, else Shopify province). */
    static String[] governorate(Cust c, OrderFacts.Cities cities) {
        CO o = c.orders().get(c.orders().size() - 1);
        return RevenueAnalyticsService.governorate(o.cityId(), o.city(), o.province(), cities);
    }

    // ── /customers/cohorts ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Cohorts cohorts() {
        UUID tid = TenantContext.require();
        YearMonth current = YearMonth.from(today());
        Map<YearMonth, List<Cust>> byMonth = new TreeMap<>();
        for (Cust c : customers(load(tid)).values()) {
            byMonth.computeIfAbsent(month(c.orders().get(0).placedAt()), m -> new ArrayList<>()).add(c);
        }
        List<Cohort> out = new ArrayList<>();
        for (Map.Entry<YearMonth, List<Cust>> e : byMonth.entrySet()) {
            YearMonth m = e.getKey();
            List<Cust> cs = e.getValue();
            List<BigDecimal> pct = new ArrayList<>();
            List<Boolean> complete = new ArrayList<>();
            for (int k = 1; k <= 3; k++) {
                YearMonth target = m.plusMonths(k);
                if (target.isAfter(current)) {
                    pct.add(null);
                    complete.add(false);
                    continue;
                }
                long again = 0;
                for (Cust c : cs) {
                    for (CO o : c.orders()) {
                        if (month(o.placedAt()).equals(target)) {
                            again++;
                            break;
                        }
                    }
                }
                pct.add(rate(again, cs.size()));
                complete.add(target.isBefore(current));
            }
            long existing = cs.stream().filter(Cust::existing).count();
            out.add(new Cohort(m.toString(), cs.size(), existing, pct, complete));
        }
        return new Cohorts(clock.instant(), out);
    }

    static YearMonth month(Instant t) {
        return YearMonth.from(t.atZone(AnalyticsPeriod.CAIRO));
    }

    // ── /customers/watch ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Watch watch() {
        UUID tid = TenantContext.require();
        OrderFacts.Cities cities = OrderFacts.cities(jdbc);
        List<Watched> out = new ArrayList<>();
        for (Cust c : customers(load(tid)).values()) {
            long refused = 0, delivered = 0;
            boolean blocked = false;
            BigDecimal refusedValue = BigDecimal.ZERO;
            for (CO o : c.orders()) {
                if ("refused".equals(o.outcome()) && o.legCod() != null && o.legCod().signum() > 0) {
                    refused++;
                    refusedValue = refusedValue.add(o.booked());
                }
                if ("delivered".equals(o.outcome())) delivered++;
                blocked |= o.blocked();
            }
            if (refused < WATCH_MIN_REFUSED) continue;
            String[] g = governorate(c, cities);
            out.add(new Watched(ref(tid, c.key()), OrderFinanceService.displayName(c.latestNamed().name()), g[1], g[2],
                c.orders().size(), refused, delivered, money(refusedValue), blocked, "Ask for prepayment", "/blocklist"));
        }
        out.sort(Comparator.comparingLong(Watched::refusedCodOrders).reversed()
            .thenComparing(Comparator.comparingLong(Watched::orders).reversed())
            .thenComparing(Watched::customerRef));
        return new Watch(clock.instant(), WATCH_MIN_REFUSED, List.copyOf(out.subList(0, Math.min(WATCH_LIMIT, out.size()))));
    }

    /**
     * An opaque, stable reference for a customer: HMAC-SHA256(secret, tenant + ":" + key), first 8
     * bytes as hex. Never the key itself (it can hold a phone) and never a plain hash of it (a phone
     * number's hash is reversible by trying every number).
     */
    String ref(UUID tid, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(refSecret, "HmacSHA256"));
            byte[] h = mac.doFinal((tid + ":" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
