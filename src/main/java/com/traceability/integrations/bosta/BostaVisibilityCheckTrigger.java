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
    private final BostaRateLimitedReprocessService reprocess;
    private final String reprocessOnStartup;
    private final boolean reprocessApply;
    private ExchangeReferenceCatchUpService referenceCatchUp;
    private String referenceCatchUpOnStartup = "";
    private boolean referenceCatchUpApply;

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
        final boolean apply = catchUpApply;
        for (UUID tenantId : tenants(catchUpOnStartup)) {
            final UUID t = tenantId;
            jobScheduler.enqueue(() -> catchUp.runAndLog(t, apply));
            log.info("Bosta fulfillment-link catch-up enqueued for tenant {} (apply={})", t, apply);
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
        final boolean refApply = referenceCatchUpApply;
        if (referenceCatchUp != null) {
            for (UUID tenantId : tenants(referenceCatchUpOnStartup)) {
                final UUID t = tenantId;
                final ExchangeReferenceCatchUpService svc = referenceCatchUp;
                jobScheduler.enqueue(() -> svc.runAndLog(t, refApply));
                log.info("Exchange reference catch-up enqueued for tenant {} (apply={})", t, refApply);
            }
        }
    }

    List<UUID> tenantsToCheck() {
        return tenants(onStartup);
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
