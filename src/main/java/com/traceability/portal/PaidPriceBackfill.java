package com.traceability.portal;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Returns portal fees — ONE-OFF backfill of line paid prices (prod fix 2026-11-06). Never scheduled.
 *
 * <p>For the portal-eligible orders (a forward leg delivered inside the tenant's return window) of tenants with fees ON ({@link PortalFeesRollout}) that still have a line with no
 * stored paid price ({@code order_line_paid_unit_price} NULL — V218's order-raw tier already counts as stored), it
 * runs each order through {@link OrderLinePaidPrices#resolve} — the ONE live read, whose write-back stores
 * Shopify's price on the line's {@code paid_unit_price} key. Nothing else is written; no Bosta call, no Shopify write.
 *
 * <p>Started at boot by {@code TRACED_PAID_PRICE_BACKFILL_ON_STARTUP} = {@code fees} (every fees-on tenant) or tenant
 * ids (each must be fees-on, else skipped). DRY RUN unless {@code TRACED_PAID_PRICE_BACKFILL_APPLY=true}: the dry
 * run makes no Shopify call and logs, per tenant, the eligible orders and unpriced lines. Apply: one order at a time
 * on its own thread (never a JobRunr worker), {@code delay-ms} between Shopify reads (default 1500), at most
 * {@code max-orders} per run (default 2000). Resumable: a priced line drops out of the selection, so re-running
 * picks up exactly what is left. Logs carry the tenant and counts only (grep PAID_PRICE_BACKFILL) — never a price,
 * an order number, a customer field or a token.
 */
@Component
public class PaidPriceBackfill {

    private static final Logger log = LoggerFactory.getLogger(PaidPriceBackfill.class);

    /**
     * One tenant's eligible orders after a keyset cursor. ORDER BY id is a resumable cursor, NOT a recency proxy.
     * On merchant_orders: no portal pre-connect row (they store Shopify's paid price when fetched — prod 2026-11-06:
     * none unpriced on a fees-on store) and no Shopify-native exchange shadow; internal orders fail the GID test.
     */
    static final String ELIGIBLE_SQL =
        "SELECT o.id FROM merchant_orders o JOIN tenants t ON t.id = o.tenant_id " +
        "WHERE o.tenant_id = ? AND o.id > ? " +
        "  AND o.external_id LIKE 'gid://shopify/Order/%' " +
        "  AND EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND oi.tenant_id = o.tenant_id " +
        "              AND order_line_paid_unit_price(oi) IS NULL) " +
        // Any forward leg delivered inside the window (a superset of the portal's newest-leg rule: one extra
        // Shopify read at worst, never a missed order).
        "  AND EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
        "              AND s.shipment_leg = 'forward' AND s.delivered_at IS NOT NULL " +
        "              AND now() - s.delivered_at <= interval '1 day' * t.customer_return_window_days) " +
        "ORDER BY o.id LIMIT ?";

    static final String UNPRICED_LINES_SQL =
        "SELECT count(*) FROM order_items oi WHERE oi.order_id = ? AND oi.tenant_id = ? " +
        "  AND order_line_paid_unit_price(oi) IS NULL";

    private static final UUID ZERO = new UUID(0, 0);
    private static final int PAGE = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderLinePaidPrices prices;
    private final PortalFeesRollout rollout;
    private final String onStartup;
    private final boolean apply;
    private final long delayMs;
    private final int maxOrders;
    private LongConsumer sleeper = PaidPriceBackfill::sleep;

    public PaidPriceBackfill(JdbcTemplate jdbc, PlatformTransactionManager txm, OrderLinePaidPrices prices,
                             PortalFeesRollout rollout,
                             @Value("${traced.paid-price-backfill.on-startup:}") String onStartup,
                             @Value("${traced.paid-price-backfill.apply:false}") boolean apply,
                             @Value("${traced.paid-price-backfill.delay-ms:1500}") long delayMs,
                             @Value("${traced.paid-price-backfill.max-orders:2000}") int maxOrders) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.prices = prices;
        this.rollout = rollout;
        this.onStartup = onStartup == null ? "" : onStartup.strip();
        this.apply = apply;
        this.delayMs = Math.max(0, delayMs);
        this.maxOrders = Math.max(0, maxOrders);
    }

    /** Tests only: no real-clock waits. */
    public void setSleeperForTest(LongConsumer sleeper) { this.sleeper = sleeper; }

    public record TenantReport(UUID tenantId, int orders, long unpricedLines, int ordersRead, long linesPriced) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (onStartup.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                run(onStartup, apply);
            } catch (RuntimeException e) {
                log.warn("PAID_PRICE_BACKFILL stopped ({})", e.getClass().getSimpleName());
            }
        }, "paid-price-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** Runs the backfill for {@code target} ("fees" or tenant ids); returns one report per tenant processed. */
    public List<TenantReport> run(String target, boolean apply) {
        List<TenantReport> out = new ArrayList<>();
        int budget = maxOrders;
        log.info("PAID_PRICE_BACKFILL start mode={} target={}", apply ? "APPLY" : "DRY_RUN", target);
        for (UUID tenantId : tenants(target)) {
            if (!rollout.on(tenantId)) {
                log.warn("PAID_PRICE_BACKFILL tenant {} skipped — customer fees are not on for it", tenantId);
                continue;
            }
            TenantReport r = runTenant(tenantId, apply, budget);
            budget -= r.ordersRead();
            out.add(r);
            log.info("PAID_PRICE_BACKFILL tenant {} {} eligible_orders={} unpriced_lines={} orders_read={} lines_priced={}",
                     tenantId, apply ? "APPLY" : "DRY_RUN", r.orders(), r.unpricedLines(), r.ordersRead(), r.linesPriced());
            if (apply && budget <= 0) {
                log.info("PAID_PRICE_BACKFILL max-orders reached — re-run to continue");
                break;
            }
        }
        log.info("PAID_PRICE_BACKFILL done mode={}", apply ? "APPLY" : "DRY_RUN");
        return out;
    }

    private TenantReport runTenant(UUID tenantId, boolean apply, int budget) {
        int orders = 0, read = 0;
        long unpriced = 0, priced = 0;
        UUID cursor = ZERO;
        while (true) {
            UUID after = cursor;
            List<UUID> page = TenantContext.runAs(tenantId, () -> tx.execute(s ->
                jdbc.queryForList(ELIGIBLE_SQL, UUID.class, tenantId, after, PAGE)));
            if (page == null || page.isEmpty()) break;
            for (UUID orderId : page) {
                cursor = orderId;
                long before = unpriced(tenantId, orderId);
                orders++;
                unpriced += before;
                if (!apply || read >= budget) continue;
                if (read > 0) sleeper.accept(delayMs);
                prices.resolve(tenantId, orderId);   // the live read + write-back; never throws for Shopify failures
                read++;
                priced += before - unpriced(tenantId, orderId);
            }
        }
        return new TenantReport(tenantId, orders, unpriced, read, priced);
    }

    private long unpriced(UUID tenantId, UUID orderId) {
        Long n = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForObject(UNPRICED_LINES_SQL, Long.class, orderId, tenantId)));
        return n == null ? 0 : n;
    }

    private Set<UUID> tenants(String target) {
        Set<UUID> out = new LinkedHashSet<>();
        if ("fees".equalsIgnoreCase(target)) {
            if (rollout.everyone()) {
                log.warn("PAID_PRICE_BACKFILL target 'fees' with fees on for EVERY tenant — name tenant ids instead");
                return out;
            }
            out.addAll(rollout.listed());
            return out;
        }
        int i = 0;
        for (String p : target.split(",")) {
            String s = p.strip();
            i++;
            if (s.isEmpty()) continue;
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                log.warn("PAID_PRICE_BACKFILL entry {} isn't a tenant id — skipped", i);
            }
        }
        return out;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
