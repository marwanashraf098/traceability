package com.traceability.integrations.bosta;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * One-off repair (2026-11-24, BROEK multi catch-up): exchange replacement legs that advance-at-commit never reached
 * (it ran in the committed transaction with no tenant — fixed in BostaWebhookJob.advanceAtCommit). Never scheduled:
 * BOSTA_EXCHANGE_LEG_REPAIR_ON_STARTUP=<tenant ids> (never 'all'), optionally BOSTA_EXCHANGE_LEG_REPAIR_AWBS=<AWBs>
 * (only those; else every internal:exchange: replacement whose newest forward Bosta leg is 'created' and not on hold),
 * dry run unless BOSTA_EXCHANGE_LEG_REPAIR_APPLY=true. Per AWB: {@link BostaWebhookJob#repairExchangeLeg} — the same
 * type-30 furthest-step reading and applyMappedState writer as advance-at-commit; a leg it can't place puts its order
 * on hold; pieces reserved → left for a person. No Bosta call, no ledger write, no Shopify write.
 */
@Service
public class ExchangeLegRepairService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeLegRepairService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BostaWebhookJob webhookJob;

    public ExchangeLegRepairService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaWebhookJob webhookJob) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.webhookJob = webhookJob;
    }

    public record Row(String trackingNumber, String verdict, String state, Integer bostaCode, String detail) {}

    /** JobRunr entry point: {@code awbsCsv} blank = every candidate of the tenant. */
    public void runAndLog(UUID tenantId, boolean apply, String awbsCsv) {
        List<Row> rows = run(tenantId, apply, awbs(awbsCsv));
        Map<String, Integer> counts = new TreeMap<>();
        for (Row r : rows) {
            counts.merge(r.verdict(), 1, Integer::sum);
            log.info("EXCHANGE_LEG_REPAIR tenant {} {} {} state={} bosta={}{}", tenantId, r.trackingNumber(), r.verdict(),
                r.state(), r.bostaCode(), r.detail() == null ? "" : " — " + r.detail());
        }
        log.info("EXCHANGE_LEG_REPAIR_SUMMARY tenant {} apply={} rows={} {}", tenantId, apply, rows.size(), counts);
    }

    public List<Row> run(UUID tenantId, boolean apply, List<String> awbs) {
        List<String> targets = awbs.isEmpty() ? candidates(tenantId) : awbs;
        List<Row> out = new ArrayList<>();
        for (String tn : targets) {
            try {
                BostaWebhookJob.LegRepair r = TenantContext.runAs(tenantId, () -> webhookJob.repairExchangeLeg(tenantId, tn, apply));
                out.add(new Row(tn, r.verdict(), r.state(), r.bostaCode(), r.detail()));
            } catch (RuntimeException e) {
                out.add(new Row(tn, "FAILED", null, null, e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    /** Replacement orders whose newest forward leg is a Bosta leg still 'created', order not on hold. */
    public List<String> candidates(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT substring(o.external_id from length('internal:exchange:') + 1) FROM merchant_orders o " +
            "JOIN LATERAL (SELECT provider, internal_state FROM shipments WHERE order_id = o.id AND tenant_id = o.tenant_id " +
            "  AND shipment_leg = 'forward' ORDER BY created_at DESC, id DESC LIMIT 1) ls ON true " +
            "WHERE o.tenant_id = ? AND o.external_id LIKE 'internal:exchange:%' AND o.external_id NOT LIKE 'internal:exchange:rr:%' " +
            "  AND o.on_hold = false AND ls.provider = 'bosta' AND ls.internal_state = 'created' " +
            "ORDER BY o.created_at", String.class, tenantId)));
    }

    static List<String> awbs(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split("[,\\s]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
}
