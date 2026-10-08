package com.traceability.analytics;

import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
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
 * columns (V148) catch up with deposits and payouts that happen days after delivery (a status
 * webhook no longer fires by then).
 *
 * Reads only (Mode B): the existing per-shipment v0 GET (BostaGateway.fetchDelivery), which runs
 * at BACKGROUND priority on the shared Bosta limiter; a rate limit stops the tenant's run and backs
 * the tenant off until the next run after retry-after (rate-limit-as-reschedule, like the status
 * poll). A payload whose state or type differs from the stored one goes through the status poll's
 * pipeline (BostaIngestionHelper.ingestFetched → BostaWebhookJob: history, piece / order effects,
 * monotonic rules); an unchanged one writes only the fresh raw + settlement columns.
 *
 * Eligible: the tenant's Bosta legs (any type) in a terminal state, on a post-floor order, not paid,
 * not 'unresolved', Bosta still knows the tracking number. Cadence:
 *   none      — every 12 h;
 *   deposited — once a day (never a zero cash cycle — SettlementSql.zeroCycle: nothing is owed); when the tenant's payout weekday is known (the weekday most recent
 *               cashout dates share), only on the day after it, with an 8-day safety net;
 *   'unresolved' (no more refreshes) only when a successful read made 45+ days after the leg
 *               finished still shows no payout; such old legs are read first, oldest first.
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

    /** A leg is 'unresolved' after a successful read this long after it finished, still unpaid. */
    static final String UNRESOLVED_AFTER = "45 days";

    public record RefreshResult(int markedUnresolved, int selected, int refreshed, int routed, int notFound,
                                int failed, int newlyDeposited, int newlyPaid, boolean rateLimited,
                                Integer payoutWeekday) {}

    private record Leg(UUID id, String tn, Integer providerState, String typeValue, String status) {}

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EncryptionService encryption;
    private final BostaGateway bosta;
    private final BostaIngestionHelper ingestion;
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
                                BostaIngestionHelper ingestion,
                                AnalyticsFloorOverrides overrides,
                                Clock clock,
                                @Value("${analytics.settlement.refresh-max-per-tenant:100}") int maxPerTenant,
                                @Value("${analytics.settlement.refresh-enabled:true}") boolean enabled) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.encryption = encryption;
        this.bosta = bosta;
        this.ingestion = ingestion;
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
                refreshTenant(tenantId, encryption.decrypt((String) row.get("api_key_encrypted")));
            } catch (Exception e) {
                log.warn("Settlement refresh failed for tenant {}: {}", tenantId, e.toString());
            }
        }
    }

    /** One tenant's run (public for tests). Logs one INFO line per run. */
    public RefreshResult refreshTenant(UUID tenantId, String apiKey) {
        return TenantContext.runAs(tenantId, () -> {
            Integer weekday = tx.execute(s -> jdbc.query(SettlementSql.PAYOUT_WEEKDAY,
                rs -> rs.next() ? rs.getInt(1) : null, tenantId, java.sql.Timestamp.from(clock.instant())));
            int today = LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO)).getDayOfWeek().getValue();
            boolean dayAfterPayout = weekday == null || today == (weekday % 7) + 1;

            // Legs finished 45+ days ago go first (oldest first): they need one successful read
            // before they may become 'unresolved'. Then the least recently refreshed.
            List<Leg> queue = tx.execute(s -> jdbc.query(
                "SELECT s.id, s.tracking_number, s.provider_state, s.raw->'type'->>'value' AS type_value, " +
                "       s.settlement_status, t.terminal_at " +
                "FROM shipments s" + SettlementSql.floorJoin("s") +
                "CROSS JOIN LATERAL (SELECT " + SettlementSql.terminalAt("s") + " AS terminal_at) t " +
                "WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.tracking_number IS NOT NULL " +
                "  AND s.provider_not_found_at IS NULL " +
                "  AND s.internal_state IN " + SettlementSql.TERMINAL_STATES +
                "  AND fo.status <> 'cancelled'::order_status AND " + SettlementSql.POST_FLOOR +
                "  AND ( (s.settlement_status = 'none' " +
                "         AND (s.settlement_refreshed_at IS NULL OR s.settlement_refreshed_at < now() - interval '12 hours')) " +
                "     OR (s.settlement_status = 'deposited' AND NOT " + SettlementSql.zeroCycle("s") +
                "         AND (s.settlement_refreshed_at IS NULL " +
                "              OR (? AND s.settlement_refreshed_at < now() - interval '20 hours') " +
                "              OR s.settlement_refreshed_at < now() - interval '8 days')) ) " +
                "ORDER BY (t.terminal_at < now() - interval '" + UNRESOLVED_AFTER + "') DESC, " +
                "         CASE WHEN t.terminal_at < now() - interval '" + UNRESOLVED_AFTER + "' THEN t.terminal_at END ASC, " +
                "         s.settlement_refreshed_at ASC NULLS FIRST, s.created_at ASC " +
                "LIMIT ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
                    ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
                    ps.setObject(3, tenantId);
                    ps.setBoolean(4, dayAfterPayout);
                    ps.setInt(5, maxPerTenant);
                },
                (rs, i) -> new Leg(rs.getObject("id", UUID.class), rs.getString("tracking_number"),
                    (Integer) rs.getObject("provider_state"), rs.getString("type_value"),
                    rs.getString("settlement_status"))));
            if (queue == null) queue = List.of();

            int refreshed = 0, routed = 0, notFound = 0, failed = 0, newlyDeposited = 0, newlyPaid = 0, limited = 0;
            boolean rateLimited = false;
            for (Leg leg : queue) {
                BostaDelivery d;
                try {
                    d = bosta.fetchDelivery(apiKey, leg.tn());   // BACKGROUND priority on the shared limiter
                } catch (BostaRateLimitException e) {
                    retryUntilByTenant.put(tenantId,
                        System.currentTimeMillis() + (e.getRetryAfterSeconds() + 10) * 1000L);
                    rateLimited = true;
                    limited++;
                    break;
                } catch (DeliveryNotFoundException e) {
                    d = null;
                } catch (Exception e) {
                    failed++;
                    log.warn("Settlement refresh tenant {}: fetch of {} failed: {}", tenantId, leg.tn(), e.toString());
                    continue;
                }
                if (d == null || d.raw() == null) {
                    // Not found: paced like any attempt, never a successful read (never 'unresolved' evidence).
                    notFound++;
                    tx.execute(s -> jdbc.update(
                        "UPDATE shipments SET settlement_refreshed_at = now() WHERE id = ?", leg.id()));
                    continue;
                }
                final BostaDelivery fd = d;
                boolean changed = leg.providerState() == null || leg.providerState() != fd.stateCode()
                    || (leg.typeValue() != null && fd.type() != null && !leg.typeValue().equalsIgnoreCase(fd.type()));
                if (changed) {
                    // A status change: the status poll's own pipeline (webhook_events → BostaWebhookJob:
                    // history, piece / order effects, monotonic rules, raw + settlement). Only the
                    // settlement columns are written here (monotonic), never raw or a state.
                    ingestion.ingestFetched(tenantId, fd, "bosta_poll", leg.providerState());
                    routed++;
                    tx.execute(s -> ShipmentSettlement.applyRefreshed(jdbc, leg.id(), fd.raw()));
                } else {
                    // Same state and type: the fresh payload and its settlement only.
                    tx.execute(s -> {
                        jdbc.update("UPDATE shipments SET raw = ?::jsonb WHERE id = ?", fd.raw().toString(), leg.id());
                        return ShipmentSettlement.applyRefreshed(jdbc, leg.id(), fd.raw());
                    });
                }
                refreshed++;
                String after = tx.execute(s -> jdbc.queryForObject(
                    "SELECT settlement_status FROM shipments WHERE id = ?", String.class, leg.id()));
                if ("paid".equals(after) && !"paid".equals(leg.status())) newlyPaid++;
                else if ("deposited".equals(after) && !"deposited".equals(leg.status())) newlyDeposited++;
            }

            // 'unresolved' only on evidence: a successful read made 45+ days after the leg finished
            // that still shows no payout. Stale raw, a not-found or a failed read never count.
            Integer unresolved = tx.execute(s -> jdbc.update(
                "UPDATE shipments s SET settlement_status = 'unresolved' " +
                "WHERE s.tenant_id = ? AND s.provider = 'bosta' " +
                "  AND s.settlement_status IN ('none', 'deposited') " +
                "  AND NOT " + SettlementSql.zeroCycle("s") +
                "  AND s.internal_state IN " + SettlementSql.TERMINAL_STATES +
                "  AND s.settlement_verified_at IS NOT NULL " +
                "  AND s.settlement_verified_at >= " + SettlementSql.terminalAt("s") + " + interval '" + UNRESOLVED_AFTER + "'",
                tenantId));

            RefreshResult r = new RefreshResult(unresolved == null ? 0 : unresolved, queue.size(), refreshed,
                routed, notFound, failed, newlyDeposited, newlyPaid, rateLimited, weekday);
            log.info("Settlement refresh tenant {}: {} leg(s) refreshed of {} selected ({} status change(s) " +
                "sent through the poll pipeline), {} newly deposited, {} newly paid, {} newly unresolved, " +
                "{} not found, {} failed, {} rate limit(s) (429)", tenantId, refreshed, queue.size(), routed,
                newlyDeposited, newlyPaid, r.markedUnresolved(), notFound, failed, limited);
            return r;
        });
    }

    /** Test hook: whether the tenant is backed off right now. */
    boolean backedOff(UUID tenantId) {
        Long until = retryUntilByTenant.get(tenantId);
        return until != null && System.currentTimeMillis() < until;
    }
}
