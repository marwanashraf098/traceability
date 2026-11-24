package com.traceability.integrations.bosta;

import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ops trigger for the read-only Bosta visibility check, for running it without an owner
 * login: set {@code BOSTA_VISIBILITY_CHECK_ON_STARTUP} (bosta.visibility-check.on-startup)
 * to a comma-separated list of tenant ids, or {@code all} (every tenant with an active Bosta
 * account), and restart. One job per tenant is enqueued once at startup; it is never
 * scheduled. Leave the property empty (the default) otherwise — every restart with it set
 * runs the check again (harmless: read-only, but it spends Bosta calls).
 *
 * Same mechanism for the fulfillment-link catch-up (BostaFulfillmentCatchUpService):
 * {@code BOSTA_FULFILLMENT_LINK_CATCH_UP_ON_STARTUP=<tenant ids>|all}, dry run unless
 * {@code BOSTA_FULFILLMENT_LINK_CATCH_UP_APPLY=true}. Remove both after the run.
 *
 * And for the re-process of webhook events that failed on a Bosta rate limit
 * (BostaRateLimitedReprocessService): {@code BOSTA_REPROCESS_RATE_LIMITED_ON_STARTUP=<tenant ids>|all},
 * dry run unless {@code BOSTA_REPROCESS_RATE_LIMITED_APPLY=true}. Remove both after the run.
 *
 * And for the exchange / CRP reference catch-up (ExchangeReferenceCatchUpService, 2026-10-05):
 * {@code BOSTA_EXCHANGE_REFERENCE_CATCH_UP_ON_STARTUP=<tenant ids>|all}, dry run unless
 * {@code BOSTA_EXCHANGE_REFERENCE_CATCH_UP_APPLY=true}. Remove both after the run.
 */
@Component
public class BostaVisibilityCheckTrigger {

    private static final Logger log = LoggerFactory.getLogger(BostaVisibilityCheckTrigger.class);

    private final JdbcTemplate ownerJdbc;
    private final JobScheduler jobScheduler;
    private final BostaVisibilityCheckService service;
    private final String onStartup;
    private final BostaFulfillmentCatchUpService catchUp;
    private final String catchUpOnStartup;
    private final boolean catchUpApply;
    private final String catchUpOrders;
    private final BostaRateLimitedReprocessService reprocess;
    private final String reprocessOnStartup;
    private final boolean reprocessApply;
    private ExchangeReferenceCatchUpService referenceCatchUp;
    private String referenceCatchUpOnStartup = "";
    private boolean referenceCatchUpApply;

    private ExchangeMultiCatchUpService multiCatchUp;
    private String multiCatchUpOnStartup = "";
    private boolean multiCatchUpApply;

    /**
     * S3b (2026-11-19) — the multi-item exchange backfill (ExchangeMultiCatchUpService).
     * BOSTA_EXCHANGE_MULTI_CATCH_UP_ON_STARTUP=<tenant ids> ('all' is refused — tenant by tenant), dry run unless
     * BOSTA_EXCHANGE_MULTI_CATCH_UP_APPLY=true. One job per tenant, one-shot per startup; remove the lines after the run.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMultiCatchUp(ExchangeMultiCatchUpService multiCatchUp,
                                @Value("${bosta.exchange-multi.catch-up.on-startup:}") String onStartup,
                                @Value("${bosta.exchange-multi.catch-up.apply:false}") boolean apply) {
        this.multiCatchUp = multiCatchUp;
        this.multiCatchUpOnStartup = onStartup;
        this.multiCatchUpApply = apply;
    }

    private ExchangeLegRepairService legRepair;
    private String legRepairOnStartup = "";
    private boolean legRepairApply;
    private String legRepairAwbs = "";

    /**
     * 2026-11-24 — the exchange replacement leg repair (ExchangeLegRepairService). BOSTA_EXCHANGE_LEG_REPAIR_ON_STARTUP=
     * <tenant ids> ('all' is refused), optional BOSTA_EXCHANGE_LEG_REPAIR_AWBS, dry run unless _APPLY=true. One job per
     * tenant, one-shot per startup; remove the lines after the run.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLegRepair(ExchangeLegRepairService legRepair,
                             @Value("${bosta.exchange-leg-repair.on-startup:}") String onStartup,
                             @Value("${bosta.exchange-leg-repair.apply:false}") boolean apply,
                             @Value("${bosta.exchange-leg-repair.awbs:}") String awbs) {
        this.legRepair = legRepair;
        this.legRepairOnStartup = onStartup;
        this.legRepairApply = apply;
        this.legRepairAwbs = awbs;
    }

    private PreConnectReprocessService preConnectReprocess;
    private String preConnectOnStartup = "";
    private String preConnectTrackings = "";
    private boolean preConnectApply;

    /**
     * 2026-11-16 — re-process 25 / 30 deliveries dropped as ignored_pre_connect although created after the connect
     * (PreConnectReprocessService). BOSTA_REPROCESS_PRE_CONNECT_ON_STARTUP=<tenant ids> ('all' is refused — tenant by
     * tenant), BOSTA_REPROCESS_PRE_CONNECT_TRACKINGS=<tracking numbers> (empty = every open one of the tenant), dry run
     * unless BOSTA_REPROCESS_PRE_CONNECT_APPLY=true. One-shot per startup; remove the lines after the run.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreConnectReprocess(PreConnectReprocessService preConnectReprocess,
                                       @Value("${bosta.reprocess-pre-connect.on-startup:}") String onStartup,
                                       @Value("${bosta.reprocess-pre-connect.trackings:}") String trackings,
                                       @Value("${bosta.reprocess-pre-connect.apply:false}") boolean apply) {
        this.preConnectReprocess = preConnectReprocess;
        this.preConnectOnStartup = onStartup;
        this.preConnectTrackings = trackings;
        this.preConnectApply = apply;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setReferenceCatchUp(ExchangeReferenceCatchUpService referenceCatchUp,
                                    @Value("${bosta.exchange-reference.catch-up.on-startup:}") String onStartup,
                                    @Value("${bosta.exchange-reference.catch-up.apply:false}") boolean apply) {
        this.referenceCatchUp = referenceCatchUp;
        this.referenceCatchUpOnStartup = onStartup;
        this.referenceCatchUpApply = apply;
    }

    public BostaVisibilityCheckTrigger(@FlywayDataSource DataSource ownerDs,
                                       JobScheduler jobScheduler,
                                       BostaVisibilityCheckService service,
                                       @Value("${bosta.visibility-check.on-startup:}") String onStartup,
                                       BostaFulfillmentCatchUpService catchUp,
                                       @Value("${bosta.fulfillment-link.catch-up.on-startup:}") String catchUpOnStartup,
                                       @Value("${bosta.fulfillment-link.catch-up.apply:false}") boolean catchUpApply,
                                       @Value("${bosta.fulfillment-link.catch-up.orders:}") String catchUpOrders,
                                       BostaRateLimitedReprocessService reprocess,
                                       @Value("${bosta.reprocess-rate-limited.on-startup:}") String reprocessOnStartup,
                                       @Value("${bosta.reprocess-rate-limited.apply:false}") boolean reprocessApply) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jobScheduler = jobScheduler;
        this.service = service;
        this.onStartup = onStartup;
        this.catchUp = catchUp;
        this.catchUpOnStartup = catchUpOnStartup;
        this.catchUpApply = catchUpApply;
        this.catchUpOrders = catchUpOrders;
        this.reprocess = reprocess;
        this.reprocessOnStartup = reprocessOnStartup;
        this.reprocessApply = reprocessApply;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        for (UUID tenantId : tenantsToCheck()) {
            final UUID t = tenantId;
            jobScheduler.enqueue(() -> service.runAndLog(t));
            log.info("Bosta visibility check enqueued for tenant {} (bosta.visibility-check.on-startup)", t);
        }
        // Fulfillment-link catch-up (2026-10-03): BOSTA_FULFILLMENT_LINK_CATCH_UP_ON_STARTUP=<ids>|all,
        // dry run unless BOSTA_FULFILLMENT_LINK_CATCH_UP_APPLY=true. One-shot per startup.
        // S4 (2026-11-03): BOSTA_FULFILLMENT_LINK_CATCH_UP_ORDERS=<order numbers> limits it to those orders (one job
        // each) — the ops way to run the single-order catch-up without an owner token.
        final boolean apply = catchUpApply;
        List<String> orders = orderNumbers(catchUpOrders);
        for (UUID tenantId : tenants(catchUpOnStartup)) {
            final UUID t = tenantId;
            if (orders.isEmpty()) {
                jobScheduler.enqueue(() -> catchUp.runAndLog(t, apply));
                log.info("Bosta fulfillment-link catch-up enqueued for tenant {} (apply={})", t, apply);
                continue;
            }
            for (String order : orders) {
                final String o = order;
                jobScheduler.enqueue(() -> catchUp.runAndLog(t, apply, o));
                log.info("Bosta fulfillment-link catch-up enqueued for tenant {} order {} (apply={})", t, o, apply);
            }
        }
        // Rate-limited events re-process (2026-10-04): BOSTA_REPROCESS_RATE_LIMITED_ON_STARTUP=<ids>|all,
        // dry run unless BOSTA_REPROCESS_RATE_LIMITED_APPLY=true. One-shot per startup.
        final boolean reApply = reprocessApply;
        for (UUID tenantId : tenants(reprocessOnStartup)) {
            final UUID t = tenantId;
            jobScheduler.enqueue(() -> reprocess.runAndLog(t, reApply));
            log.info("Bosta rate-limited re-process enqueued for tenant {} (apply={})", t, reApply);
        }
        // Exchange / CRP reference catch-up (2026-10-05): BOSTA_EXCHANGE_REFERENCE_CATCH_UP_ON_STARTUP=<ids>|all,
        // dry run unless BOSTA_EXCHANGE_REFERENCE_CATCH_UP_APPLY=true. One-shot per startup.
        if (multiCatchUp != null && multiCatchUpOnStartup != null && !multiCatchUpOnStartup.isBlank()) {
            if (multiCatchUpOnStartup.trim().equalsIgnoreCase("all")) {
                log.warn("bosta.exchange-multi.catch-up.on-startup: 'all' is refused — list tenant ids");
            } else {
                final boolean mApply = multiCatchUpApply;
                final ExchangeMultiCatchUpService svc = multiCatchUp;
                for (UUID tenantId : tenants(multiCatchUpOnStartup)) {
                    final UUID t = tenantId;
                    jobScheduler.enqueue(() -> svc.runAndLog(t, mApply));
                    log.info("Multi-item exchange catch-up enqueued for tenant {} (apply={})", t, mApply);
                }
            }
        }
        if (legRepair != null && legRepairOnStartup != null && !legRepairOnStartup.isBlank()) {
            if (legRepairOnStartup.trim().equalsIgnoreCase("all")) {
                log.warn("bosta.exchange-leg-repair.on-startup: 'all' is refused — list tenant ids");
            } else {
                final boolean lApply = legRepairApply;
                final String lAwbs = legRepairAwbs == null ? "" : legRepairAwbs;
                final ExchangeLegRepairService svc = legRepair;
                for (UUID tenantId : tenants(legRepairOnStartup)) {
                    final UUID t = tenantId;
                    jobScheduler.enqueue(() -> svc.runAndLog(t, lApply, lAwbs));
                    log.info("Exchange leg repair enqueued for tenant {} (apply={})", t, lApply);
                }
            }
        }
        final boolean refApply = referenceCatchUpApply;
        if (referenceCatchUp != null) {
            for (UUID tenantId : tenants(referenceCatchUpOnStartup)) {
                final UUID t = tenantId;
                final ExchangeReferenceCatchUpService svc = referenceCatchUp;
                jobScheduler.enqueue(() -> svc.runAndLog(t, refApply));
                log.info("Exchange reference catch-up enqueued for tenant {} (apply={})", t, refApply);
            }
        }
        if (preConnectReprocess != null) {
            final boolean pcApply = preConnectApply;
            final String pcCsv = String.join(",", orderNumbers(preConnectTrackings));
            final List<String> pcTrackings = orderNumbers(preConnectTrackings);
            final PreConnectReprocessService svc = preConnectReprocess;
            for (UUID tenantId : preConnectTenants()) {
                final UUID t = tenantId;
                jobScheduler.enqueue(() -> svc.runAndLog(t, pcCsv, pcApply));
                log.info("Bosta pre-connect re-process enqueued for tenant {} ({} tracking number(s), apply={})",
                    t, pcTrackings.isEmpty() ? "open only" : pcTrackings.size(), pcApply);
            }
        }
    }

    /** The pre-connect re-process's tenants: explicit ids only ('all' is refused). */
    List<UUID> preConnectTenants() {
        String v = preConnectOnStartup == null ? "" : preConnectOnStartup.trim();
        if (v.equalsIgnoreCase("all")) {
            log.warn("bosta.reprocess-pre-connect.on-startup: 'all' is not accepted — name each tenant id; nothing enqueued");
            return List.of();
        }
        return tenants(v);
    }

    List<UUID> tenantsToCheck() {
        return tenants(onStartup);
    }

    /** Comma-separated order numbers, trimmed; empty = none (the whole tenant). */
    public static List<String> orderNumbers(String setting) {
        List<String> out = new ArrayList<>();
        if (setting == null) return out;
        for (String part : setting.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    List<UUID> tenants(String setting) {
        String v = setting == null ? "" : setting.trim();
        if (v.isEmpty()) return List.of();
        if (v.equalsIgnoreCase("all")) {
            return ownerJdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM courier_accounts WHERE provider = 'bosta' AND status = 'active'",
                UUID.class);
        }
        List<UUID> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                out.add(UUID.fromString(p));
            } catch (IllegalArgumentException e) {
                log.warn("bosta.visibility-check.on-startup: '{}' is not a tenant id — skipped", p);
            }
        }
        return out;
    }
}
