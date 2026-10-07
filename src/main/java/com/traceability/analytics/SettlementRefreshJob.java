package com.traceability.analytics;

import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.integrations.bosta.DeliveryNotFoundException;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analytics slice 3 — re-reads finished Bosta legs until Bosta has paid them, so the settlement
 * columns (V147) catch up with deposits and payouts that happen days after delivery (a status
 * webhook no longer fires by then).
 *
 * Reads only (Mode B): the existing per-shipment v0 GET (BostaGateway.fetchDelivery), which runs
 * at BACKGROUND priority on the shared Bosta limiter; a rate limit stops the tenant's run and backs
 * the tenant off until the next run after retry-after (rate-limit-as-reschedule, like the status
 * poll). The settlement columns are the only thing written — never raw, never a state.
 *
 * Eligible: the tenant's Bosta legs (any type) in a terminal state, on a post-floor order, not paid,
 * not 'unresolved', Bosta still knows the tracking number. Cadence:
 *   none      — every 12 h;
 *   deposited — once a day; when the tenant's payout weekday is known (the weekday most recent
 *               cashout dates share), only on the day after it, with an 8-day safety net;
 *   45 days after the leg finished with no payout → 'unresolved', no more refreshes.
 * At most {@code analytics.settlement.refresh-max-per-tenant} legs per tenant per run, the
 * least recently refreshed first.
 *
 * Tenants come from the same owner-pool courier_accounts listing BostaStatusPollJob uses; each
 * tenant's work runs as app_user under TenantContext.runAs (RLS). No new hatch.
 */
@Service
public class SettlementRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementRefreshJob.class);

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    public record RefreshResult(int markedUnresolved, int selected, int refreshed, int notFound,
                                int failed, boolean rateLimited, Integer payoutWeekday) {}

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EncryptionService encryption;
    private final BostaGateway bosta;
    private final AnalyticsFloorOverrides overrides;
    private final Clock clock;
    private final int maxPerTenant;
    private final boolean enabled;
    private final Map<UUID, Long> retryUntilByTenant = new ConcurrentHashMap<>();

    public SettlementRefreshJob(@FlywayDataSource DataSource ownerDs,
                                JdbcTemplate jdbc,
                                PlatformTransactionManager txm,
                                EncryptionService encryption,
                                BostaGateway bosta,
                                AnalyticsFloorOverrides overrides,
                                Clock clock,
                                @Value("${analytics.settlement.refresh-max-per-tenant:100}") int maxPerTenant,
                                @Value("${analytics.settlement.refresh-enabled:true}") boolean enabled) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.encryption = encryption;
        this.bosta = bosta;
        this.overrides = overrides;
        this.clock = clock;
        this.maxPerTenant = maxPerTenant;
        this.enabled = enabled;
    }

    @Recurring(id = "bosta-settlement-refresh", cron = "23 * * * *", zoneId = "Africa/Cairo")
    @Job(name = "Bosta settlement refresh")
    public void refreshAll() {
        if (!enabled) return;
        for (Map<String, Object> row : ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS)) {
            UUID tenantId = (UUID) row.get("tenant_id");
            Long until = retryUntilByTenant.get(tenantId);
            if (until != null && System.currentTimeMillis() < until) {
                log.info("Settlement refresh tenant {}: rate-limited — skipped this run", tenantId);
                continue;
            }
            try {
                RefreshResult r = refreshTenant(tenantId, encryption.decrypt((String) row.get("api_key_encrypted")));
                if (r.selected() > 0 || r.markedUnresolved() > 0) {
                    log.info("Settlement refresh tenant {}: {}", tenantId, r);
                }
            } catch (Exception e) {
                log.warn("Settlement refresh failed for tenant {}: {}", tenantId, e.toString());
            }
        }
    }

    /** One tenant's run (public for tests). */
    public RefreshResult refreshTenant(UUID tenantId, String apiKey) {
        return TenantContext.runAs(tenantId, () -> {
            Integer unresolved = tx.execute(s -> jdbc.update(
                "UPDATE shipments s SET settlement_status = 'unresolved' " +
                "WHERE s.tenant_id = ? AND s.provider = 'bosta' " +
                "  AND s.settlement_status IN ('none', 'deposited') " +
                "  AND s.internal_state IN " + SettlementSql.TERMINAL_STATES +
                "  AND " + SettlementSql.terminalAt("s") + " < now() - interval '45 days'",
                tenantId));

            Integer weekday = tx.execute(s -> jdbc.query(SettlementSql.PAYOUT_WEEKDAY,
                rs -> rs.next() ? rs.getInt(1) : null, tenantId));
            int today = LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO)).getDayOfWeek().getValue();
            boolean dayAfterPayout = weekday == null || today == (weekday % 7) + 1;

            List<Map<String, Object>> queue = tx.execute(s -> jdbc.query(
                "SELECT s.id, s.tracking_number FROM shipments s" + SettlementSql.floorJoin("s") +
                "WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.tracking_number IS NOT NULL " +
                "  AND s.provider_not_found_at IS NULL " +
                "  AND s.internal_state IN " + SettlementSql.TERMINAL_STATES +
                "  AND fo.status <> 'cancelled'::order_status AND " + SettlementSql.POST_FLOOR +
                "  AND ( (s.settlement_status = 'none' " +
                "         AND (s.settlement_refreshed_at IS NULL OR s.settlement_refreshed_at < now() - interval '12 hours')) " +
                "     OR (s.settlement_status = 'deposited' " +
                "         AND (s.settlement_refreshed_at IS NULL " +
                "              OR (? AND s.settlement_refreshed_at < now() - interval '20 hours') " +
                "              OR s.settlement_refreshed_at < now() - interval '8 days')) ) " +
                "ORDER BY s.settlement_refreshed_at ASC NULLS FIRST, s.created_at ASC " +
                "LIMIT ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
                    ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
                    ps.setObject(3, tenantId);
                    ps.setBoolean(4, dayAfterPayout);
                    ps.setInt(5, maxPerTenant);
                },
                (rs, i) -> Map.<String, Object>of("id", rs.getObject("id"), "tn", rs.getString("tracking_number"))));
            if (queue == null) queue = List.of();

            int refreshed = 0, notFound = 0, failed = 0;
            boolean rateLimited = false;
            for (Map<String, Object> leg : queue) {
                UUID id = (UUID) leg.get("id");
                String tn = (String) leg.get("tn");
                BostaDelivery d;
                try {
                    d = bosta.fetchDelivery(apiKey, tn);   // BACKGROUND priority on the shared limiter
                } catch (BostaRateLimitException e) {
                    retryUntilByTenant.put(tenantId,
                        System.currentTimeMillis() + (e.getRetryAfterSeconds() + 10) * 1000L);
                    rateLimited = true;
                    break;
                } catch (DeliveryNotFoundException e) {
                    notFound++;
                    tx.execute(s -> jdbc.update(
                        "UPDATE shipments SET settlement_refreshed_at = now() WHERE id = ?", id));
                    continue;
                } catch (Exception e) {
                    failed++;
                    log.warn("Settlement refresh tenant {}: fetch of {} failed: {}", tenantId, tn, e.toString());
                    continue;
                }
                final BostaDelivery fd = d;
                tx.execute(s -> ShipmentSettlement.applyRefreshed(jdbc, id, fd == null ? null : fd.raw()));
                refreshed++;
            }
            return new RefreshResult(unresolved == null ? 0 : unresolved, queue.size(), refreshed,
                notFound, failed, rateLimited, weekday);
        });
    }

    /** Test hook: whether the tenant is backed off right now. */
    boolean backedOff(UUID tenantId) {
        Long until = retryUntilByTenant.get(tenantId);
        return until != null && System.currentTimeMillis() < until;
    }
}
