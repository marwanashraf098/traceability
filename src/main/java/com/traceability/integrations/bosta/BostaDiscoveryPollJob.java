package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tier 2 — Discovery Poll: ingests NEW Bosta deliveries not yet known to Traced.
 *
 * Runs infrequently (default 20 minutes). Pages the Bosta List API for the first
 * K pages (newest-created first) and routes each delivery through BostaIngestionHelper
 * with source='bosta_poll_discovery'. Idempotency ensures already-processed deliveries
 * are a no-op; truly new ones get ingested and matched via ShipmentLinkService, then
 * Tier 1 keeps their status current.
 *
 * The full backfill (all pages) remains as the on-connect + manual Sync button trigger.
 * Tier 2 is the lightweight ongoing discovery pass covering the most recently created
 * deliveries that may have arrived since the last backfill.
 *
 * TenantContext: same pattern as BostaBackfillJob and BostaStatusPollJob — all per-tenant
 * work runs inside TenantContext.runAs(tenantId).
 */
@Service
@ConditionalOnProperty(name = "bosta.poll.discovery-enabled", havingValue = "true", matchIfMissing = true)
public class BostaDiscoveryPollJob {

    private static final Logger log = LoggerFactory.getLogger(BostaDiscoveryPollJob.class);

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted " +
        "FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final BostaGateway         bostaGateway;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  discoveryPages;
    private final int                  pageSize;
    private final long                 interFetchDelayMs;

    public BostaDiscoveryPollJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            BostaGateway bostaGateway,
            EncryptionService encryptionService,
            BostaIngestionHelper ingestionHelper,
            @Value("${bosta.poll.discovery-pages:3}") int discoveryPages,
            @Value("${bosta.backfill.page-size:50}") int pageSize,
            @Value("${bosta.poll.inter-fetch-delay-ms:100}") long interFetchDelayMs) {
        this.ownerJdbc         = new JdbcTemplate(ownerDs);
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.bostaGateway      = bostaGateway;
        this.encryptionService = encryptionService;
        this.ingestionHelper   = ingestionHelper;
        this.discoveryPages    = discoveryPages;
        this.pageSize          = pageSize;
        this.interFetchDelayMs = interFetchDelayMs;
    }

    @Recurring(id = "bosta-discovery-poll", cron = "*/20 * * * *")
    @Job(name = "Bosta discovery poll")
    public void discoverAll() {
        List<Map<String, Object>> accounts = ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS);
        if (accounts.isEmpty()) {
            log.debug("Discovery poll: no active Bosta accounts");
            return;
        }

        for (Map<String, Object> row : accounts) {
            UUID tenantId       = (UUID) row.get("tenant_id");
            String encryptedKey = (String) row.get("api_key_encrypted");
            try {
                String apiKey = encryptionService.decrypt(encryptedKey);
                discoverTenant(tenantId, apiKey);
            } catch (Exception e) {
                log.warn("Discovery poll failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void discoverTenant(UUID tenantId, String apiKey) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            int total = 0, enqueued = 0;

            outer:
            for (int page = 1; page <= discoveryPages; page++) {
                List<BostaGateway.SlimDelivery> items;
                try {
                    items = bostaGateway.listDeliveriesPage(apiKey, page, pageSize);
                } catch (BostaTransientException e) {
                    log.warn("Discovery poll tenant {}: transient error on page {} — stopping: {}",
                        tenantId, page, e.getMessage());
                    break;
                }

                if (items.isEmpty()) break;

                for (BostaGateway.SlimDelivery slim : items) {
                    total++;
                    try {
                        if (ingestionHelper.ingestDelivery(
                                tenantId, apiKey, slim.trackingNumber(), "bosta_poll_discovery")) {
                            enqueued++;
                        }
                    } catch (BostaTransientException e) {
                        log.warn("Discovery poll tenant {}: transient error on {} — skipping: {}",
                            tenantId, slim.trackingNumber(), e.getMessage());
                    } catch (Exception e) {
                        log.error("Discovery poll tenant {}: unexpected error on {} — skipping",
                            tenantId, slim.trackingNumber(), e);
                    }

                    try {
                        if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break outer;
                    }
                }
            }

            if (enqueued > 0) {
                log.info("Discovery poll tenant {}: {} seen, {} new deliveries enqueued",
                    tenantId, total, enqueued);
            } else {
                log.debug("Discovery poll tenant {}: {} seen, nothing new", tenantId, total);
            }
        });
    }
}
