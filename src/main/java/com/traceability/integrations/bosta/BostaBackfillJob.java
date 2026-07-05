package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Backfills historical Bosta deliveries that existed before the webhook was configured
 * or that were missed while the webhook was down.
 *
 * Design: backfilled deliveries route through the existing webhook pipeline via
 * BostaIngestionHelper (shared with Tier 1/2 poll jobs). ONE ingest code path —
 * matching, state mapping, piece transitions, and unlinked recording are identical
 * to live webhook and poll processing.
 *
 * Idempotency: BostaIngestionHelper synthesizes a payload with updatedAt from the
 * fetched delivery, so sha256(trackingNumber:stateCode:updatedAt) matches the idem
 * key a real webhook or poll event would produce — dedup in BostaWebhookJob prevents
 * double-processing across all sources.
 *
 * TenantContext: the ENTIRE job runs inside TenantContext.runAs(tenantId). Never call
 * runAs inside the loop — the context is inherited.
 */
@Component
public class BostaBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(BostaBackfillJob.class);

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final BostaGateway         bostaGateway;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  defaultMaxPages;
    private final int                  pageSize;
    private final long                 interFetchDelayMs;

    public BostaBackfillJob(JdbcTemplate jdbc,
                             PlatformTransactionManager txm,
                             BostaGateway bostaGateway,
                             EncryptionService encryptionService,
                             BostaIngestionHelper ingestionHelper,
                             @Value("${bosta.backfill.max-pages:20}") int defaultMaxPages,
                             @Value("${bosta.backfill.page-size:50}") int pageSize,
                             @Value("${bosta.backfill.inter-fetch-delay-ms:100}") long interFetchDelayMs) {
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.bostaGateway      = bostaGateway;
        this.encryptionService = encryptionService;
        this.ingestionHelper   = ingestionHelper;
        this.defaultMaxPages   = defaultMaxPages;
        this.pageSize          = pageSize;
        this.interFetchDelayMs = interFetchDelayMs;
    }

    @Job(name = "Bosta delivery backfill — tenant %0")
    public void run(UUID tenantId, int maxPages) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            // 1. Load API key.
            String[] accountInfo = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? new String[]{rs.getString(1)} : null,
                tenantId));

            if (accountInfo == null) {
                log.warn("Backfill: no active Bosta account for tenant {} — skipping", tenantId);
                return;
            }

            String rawApiKey = encryptionService.decrypt(accountInfo[0]);
            int total = 0, enqueued = 0;

            // 2. Page loop — cap prevents unbounded pulls.
            outer:
            for (int page = 1; page <= maxPages; page++) {
                List<BostaGateway.SlimDelivery> items;
                try {
                    items = bostaGateway.listDeliveriesPage(rawApiKey, page, pageSize);
                } catch (BostaTransientException e) {
                    log.warn("Backfill tenant {}: transient error on page {} — stopping early: {}",
                        tenantId, page, e.getMessage());
                    break;
                }

                if (items.isEmpty()) break;

                for (BostaGateway.SlimDelivery slim : items) {
                    total++;
                    try {
                        if (ingestionHelper.ingestDelivery(
                                tenantId, rawApiKey, slim.trackingNumber(), "bosta_backfill")) {
                            enqueued++;
                        }
                    } catch (BostaTransientException e) {
                        log.warn("Backfill tenant {}: transient error on {} — skipping: {}",
                            tenantId, slim.trackingNumber(), e.getMessage());
                    } catch (Exception e) {
                        log.error("Backfill tenant {}: unexpected error on {} — skipping",
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

            // 3. Update backfill counters (under tenant context, GUC active).
            final int fTotal = total, fEnqueued = enqueued;
            tx.execute(s -> {
                jdbc.update(
                    "UPDATE courier_accounts " +
                    "SET last_backfill_at = now(), last_backfill_total = ?, last_backfill_enqueued = ? " +
                    "WHERE tenant_id = ? AND provider = 'bosta'",
                    fTotal, fEnqueued, tenantId);
                return null;
            });

            log.info("Backfill complete for tenant {}: {} seen, {} enqueued", tenantId, total, enqueued);
        });
    }
}
