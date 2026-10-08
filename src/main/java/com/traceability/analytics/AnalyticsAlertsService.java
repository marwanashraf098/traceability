package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;

/**
 * Analytics slice 7 — the Summary's "Needs attention" list and the cash forecast. Owner-only
 * (controller). Every figure is built on an existing definition: the s3 stuck / not-paid lists, the
 * s5 governorate grouping and success rate, the s3 pipeline's in-transit stage and city rates.
 */
@Service
public class AnalyticsAlertsService {

    static final BigDecimal LOW_SUCCESS = new BigDecimal("0.65");
    static final int LOW_SUCCESS_MIN_ORDERS = 10;
    static final int SELLS_OUT_DAYS = 4;
    static final int BEST_SELLERS = 20;

    public record AlertDetail(String key, String label, String labelAr, long orders, BigDecimal successRate,
                              BigDecimal failedValue) {}

    /** One "Needs attention" line: how many, how much (null when money doesn't apply), where to go. */
    /** A best seller about to sell out (the sells-out-soon line). */
    public record AlertSku(UUID variantId, String productTitle, String variantTitle, String sku, long onHand,
                           BigDecimal daysOfCover, BigDecimal velocityPerDay) {}

    /** One "Needs attention" line: how many, how much (null when money doesn't apply), where to go. */
    public record Alert(String key, String label, long count, BigDecimal amount, String link,
                        List<AlertDetail> details, List<AlertSku> skus) {
        Alert(String key, String label, long count, BigDecimal amount, String link, List<AlertDetail> details) {
            this(key, label, count, amount, link, details, null);
        }
    }

    public record Alerts(AnalyticsPeriod.Range range, Instant asOf, List<Alert> alerts) {}

    public record Part(long count, BigDecimal amount) {}

    public record InTransitPart(long count, BigDecimal cod, BigDecimal expected, LocalDate expectedPayoutDate) {}

    public record Method(int payoutWeekday, BigDecimal medianLagDays, long lagSample, int lagDaysUsed,
                         LocalDate nextPayoutDate, Part awaitingDeposited, Part awaitingNotSettled,
                         InTransitPart inTransit) {}

    public record Bucket(String key, LocalDate from, LocalDate to, BigDecimal amount, BigDecimal awaitingPayout,
                         BigDecimal inTransit) {}

    public record Forecast(List<Bucket> buckets, BigDecimal later, Method method) {}

    /** forecast null with a reason (payout_cadence_unknown, payout_lag_unknown) when it can't be placed. */
    public record CashForecast(Instant asOf, LocalDate today, String reason, Forecast forecast) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;
    private final MoneyAnalyticsService money;
    private final StockAnalyticsService stock;

    public AnalyticsAlertsService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides,
                                  MoneyAnalyticsService money, StockAnalyticsService stock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
        this.money = money;
        this.stock = stock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    // ── /alerts ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Alerts alerts(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        List<Alert> out = new ArrayList<>();

        MoneyAnalyticsService.Stuck stuck = money.stuck();
        out.add(new Alert("stuck_with_bosta", "Stuck with Bosta for more than 7 days", stuck.stuckWithBosta().size(),
            money(stuck.stuckWithBosta().stream().map(MoneyAnalyticsService.StuckShipment::cod).reduce(BigDecimal.ZERO, BigDecimal::add)),
            "/analytics/money?view=stuck&kind=stuck_with_bosta", null));
        out.add(new Alert("never_picked_up", "Booked, never picked up for more than 7 days", stuck.neverPickedUp().size(),
            money(stuck.neverPickedUp().stream().map(MoneyAnalyticsService.StuckShipment::cod).reduce(BigDecimal.ZERO, BigDecimal::add)),
            "/analytics/money?view=stuck&kind=never_picked_up", null));
        out.add(new Alert("delivered_not_paid", "Delivered, not paid", stuck.deliveredNotPaid().size(),
            money(stuck.deliveredNotPaid().stream().map(MoneyAnalyticsService.NotPaidShipment::deposited)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)),
            "/analytics/money?view=stuck&kind=delivered_not_paid", null));

        out.add(lowSuccessGovernorates(tid, period));

        if (stock.hasPieces(tid)) out.add(sellsOutSoon());
        return new Alerts(period.range(), now, out);
    }

    /** s5 governorates (OrderFacts + RevenueAnalyticsService.governorate) with success < 65 % and ≥ 10 orders. */
    private Alert lowSuccessGovernorates(UUID tid, AnalyticsPeriod period) {
        OrderFacts.Cities cities = OrderFacts.cities(jdbc);
        Map<String, long[]> counts = new LinkedHashMap<>();        // orders, delivered, failed
        Map<String, BigDecimal> failedValue = new HashMap<>();
        Map<String, String[]> names = new HashMap<>();
        for (OrderFacts.Order o : OrderFacts.load(jdbc, tid, period, overrides)) {
            String[] g = RevenueAnalyticsService.governorate(o, cities);
            if ("unknown".equals(g[0])) continue;
            names.putIfAbsent(g[0], g);
            long[] c = counts.computeIfAbsent(g[0], k -> new long[3]);
            c[0]++;
            if (o.delivered()) c[1]++;
            if (o.failed()) {
                c[2]++;
                failedValue.merge(g[0], o.booked(), BigDecimal::add);
            }
        }
        List<AlertDetail> details = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            BigDecimal rate = AnalyticsSql.rate(c[1], c[1] + c[2]);
            if (c[0] >= LOW_SUCCESS_MIN_ORDERS && rate != null && rate.compareTo(LOW_SUCCESS) < 0) {
                String[] g = names.get(e.getKey());
                details.add(new AlertDetail(g[0], g[1], g[2], c[0], rate,
                    money(failedValue.getOrDefault(e.getKey(), BigDecimal.ZERO))));
            }
        }
        details.sort(Comparator.comparing(AlertDetail::successRate).thenComparing(AlertDetail::label));
        BigDecimal amount = details.stream().map(AlertDetail::failedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Alert("low_success_governorates", "Governorates with delivery success under 65%", details.size(),
            money(amount), "/analytics/delivery?by=governorate", details);
    }

    /**
     * Sells out soon (slice 4): among the best sellers — the top {@value #BEST_SELLERS} variants by
     * velocity (StockAnalyticsService: delivered units per day, last 30 days) — those with stock on
     * hand and at most {@value #SELLS_OUT_DAYS} days of cover. Listed in {@code skus}, lowest cover first.
     */
    private Alert sellsOutSoon() {
        List<StockAnalyticsService.VariantStock> best = new ArrayList<>(
            stock.variants("velocity", "all", Integer.MAX_VALUE).variants());
        best.removeIf(v -> v.deliveredUnits30() == 0);
        if (best.size() > BEST_SELLERS) best = new ArrayList<>(best.subList(0, BEST_SELLERS));
        List<AlertSku> skus = new ArrayList<>();
        for (StockAnalyticsService.VariantStock v : best) {
            if (v.onHand() > 0 && v.daysOfCover() != null && v.daysOfCover().compareTo(BigDecimal.valueOf(SELLS_OUT_DAYS)) <= 0) {
                skus.add(new AlertSku(v.variantId(), v.productTitle(), v.variantTitle(), v.sku(), v.onHand(),
                    v.daysOfCover(), v.velocityPerDay()));
            }
        }
        skus.sort(Comparator.comparing(AlertSku::daysOfCover).thenComparing(s -> s.variantId().toString()));
        return new Alert("sells_out_soon", "Best sellers that sell out within 4 days", skus.size(), null,
            "/analytics/stock?filter=running_low", null, skus);
    }

    // ── /cash-forecast ──────────────────────────────────────────────────────

    /*
     * Median delivered → paid lag in days over the tenant's paid forward legs whose payout is in the
     * 90 days before now. Parameters: override domains, override days, tenant id, now.
     */
    private static final String LAG_SQL = """
        SELECT """ + SettlementSql.medianPayoutLag("s") + """
                   AS median_lag,
               COUNT(*) AS n
        FROM shipments s""" + SettlementSql.floorJoin("s") + """
        WHERE s.tenant_id = ? AND s.provider = 'bosta' AND """ + SettlementSql.payoutLagLeg("s") + """
          AND s.cashout_date > (?::timestamptz AT TIME ZONE 'Africa/Cairo')::date - 90
          AND """ + SettlementSql.POST_FLOOR;

    /*
     * Awaiting payout (the pipeline's two parts): deposited legs (one row), and delivered legs not
     * settled yet per Cairo delivery day with their cod − fee estimate. Parameters: override domains,
     * override days, tenant id.
     */
    private static final String AWAITING_SQL = """
        SELECT CASE WHEN """ + MoneyAnalyticsService.AWAITING_DEPOSITED + """
                    THEN 'deposited' ELSE 'not_settled' END AS kind,
               CASE WHEN NOT """ + MoneyAnalyticsService.AWAITING_DEPOSITED + """
                    THEN (COALESCE(s.delivered_at, s.last_synced_at, s.created_at) AT TIME ZONE 'Africa/Cairo')::date END AS day,
               COUNT(*) AS n,
               COALESCE(SUM(CASE WHEN """ + MoneyAnalyticsService.AWAITING_DEPOSITED + """
                                 THEN s.deposited_amt
                                 ELSE """ + SettlementSql.cod("s") + " - COALESCE(" + SettlementSql.fee("s") + """
                                 , 0) END), 0) AS amount
        FROM shipments s""" + SettlementSql.floorJoin("s") + """
        WHERE s.tenant_id = ? AND s.provider = 'bosta'
          AND (""" + MoneyAnalyticsService.AWAITING_DEPOSITED + " OR " + MoneyAnalyticsService.AWAITING_UNSETTLED + """
          ) AND """ + SettlementSql.POST_FLOOR + """
        GROUP BY 1, 2
        """;

    static final int[][] BUCKETS = {{0, 7}, {8, 14}, {15, 30}};
    static final String[] BUCKET_KEYS = {"next7", "days8to14", "days15to30"};

    @Transactional(readOnly = true)
    public CashForecast cashForecast() {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        LocalDate today = today();
        Integer weekday = jdbc.query(SettlementSql.PAYOUT_WEEKDAY, rs -> rs.next() ? rs.getInt(1) : null, tid, Timestamp.from(now));
        if (weekday == null) return new CashForecast(now, today, "payout_cadence_unknown", null);

        Object[] lag = jdbc.query(LAG_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(3, tid);
            ps.setTimestamp(4, Timestamp.from(now));
        }, rs -> {
            rs.next();
            return new Object[] {rs.getBigDecimal("median_lag"), rs.getLong("n")};
        });
        if (lag[0] == null) return new CashForecast(now, today, "payout_lag_unknown", null);
        BigDecimal median = ((BigDecimal) lag[0]).setScale(1, RoundingMode.HALF_UP);
        int lagDays = median.setScale(0, RoundingMode.CEILING).intValue();
        long lagSample = (Long) lag[1];

        DayOfWeek dow = DayOfWeek.of(weekday);
        LocalDate nextPayout = today.with(TemporalAdjusters.nextOrSame(dow));
        BigDecimal[] awaiting = zeros(), transit = zeros();
        BigDecimal[] later = {BigDecimal.ZERO};

        long[] depN = {0}, notN = {0};
        BigDecimal[] depAmt = {BigDecimal.ZERO}, notAmt = {BigDecimal.ZERO};
        jdbc.query(AWAITING_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(3, tid);
        }, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            LocalDate payout;
            if ("deposited".equals(rs.getString("kind"))) {
                depN[0] += rs.getLong("n");
                depAmt[0] = depAmt[0].add(amount);
                payout = nextPayout;
            } else {
                notN[0] += rs.getLong("n");
                notAmt[0] = notAmt[0].add(amount);
                LocalDate expected = rs.getDate("day").toLocalDate().plusDays(lagDays);
                payout = (expected.isBefore(today) ? today : expected).with(TemporalAdjusters.nextOrSame(dow));
            }
            place(awaiting, later, today, payout, amount);
        });

        MoneyAnalyticsService.Stage inTransit = money.inTransit(tid, now);
        LocalDate transitPayout = today.plusDays(lagDays).with(TemporalAdjusters.nextOrSame(dow));
        if (inTransit.expected() != null) place(transit, later, today, transitPayout, inTransit.expected());

        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < BUCKETS.length; i++) {
            buckets.add(new Bucket(BUCKET_KEYS[i], today.plusDays(BUCKETS[i][0]), today.plusDays(BUCKETS[i][1]),
                money(awaiting[i].add(transit[i])), money(awaiting[i]), money(transit[i])));
        }
        Method method = new Method(weekday, median, lagSample, lagDays, nextPayout,
            new Part(depN[0], money(depAmt[0])), new Part(notN[0], money(notAmt[0])),
            new InTransitPart(inTransit.count(), inTransit.value(), inTransit.expected(), transitPayout));
        return new CashForecast(now, today, null, new Forecast(buckets, money(later[0]), method));
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] z = new BigDecimal[BUCKETS.length];
        Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }

    private static void place(BigDecimal[] into, BigDecimal[] later, LocalDate today, LocalDate day, BigDecimal amount) {
        long d = ChronoUnit.DAYS.between(today, day);
        for (int i = 0; i < BUCKETS.length; i++) {
            if (d >= BUCKETS[i][0] && d <= BUCKETS[i][1]) {
                into[i] = into[i].add(amount);
                return;
            }
        }
        later[0] = later[0].add(amount);
    }
}
