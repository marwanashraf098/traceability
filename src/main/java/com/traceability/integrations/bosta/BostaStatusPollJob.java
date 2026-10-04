package com.traceability.integrations.bosta;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 1 — Status Poll: keeps known in-flight Bosta shipments current.
 *
 * Runs on a short interval (default 3 minutes). For each tenant with an active Bosta
 * courier_account, fetches every non-terminal shipment individually from Bosta's
 * fetchDelivery endpoint and routes the result through the shared ingest pipeline
 * (BostaIngestionHelper → BostaWebhookJob). Idempotency ensures unchanged shipments
 * are a cheap no-op; a changed state produces a new idem key and is processed.
 *
 * Terminal states (delivered, returned, lost, terminated, cancelled) are excluded —
 * they can no longer change and drop naturally out of the poll set as they arrive.
 *
 * Rotation: shipments are ordered by last_polled_at ASC NULLS FIRST so that if
 * in-flight count > max-per-cycle, every shipment is visited in round-robin order
 * and none is starved.
 *
 * TenantContext: each tenant's work runs inside TenantContext.runAs(tenantId) so
 * TenantAwareConnection fires SET LOCAL app.current_tenant for every transaction.
 * The cross-tenant ownerJdbc query to list active tenants bypasses RLS (ownerDs is
 * the Flyway/JobRunr postgres-role datasource).
 *
 * Coexistence: Tier 1 + Tier 2 discovery + manual backfill + live webhook all feed
 * the same idempotent pipeline — no double-processing regardless of source.
 */
/*
 * 2026-10-04 (V138): the poll is a WALK of Bosta's v2 delivery search sorted "-updatedAt" — only deliveries
 * that changed — instead of one v0 fetch per in-flight shipment (~730 requests/hour for a big tenant).
 * Per tenant, each cycle:
 *   1. walk pages 1, 2, 3 … (bosta.poll.status-page-limit, 50) until items updated before
 *      poll_mark_at minus bosta.poll.status-overlap-minutes (10), an empty or short page, or
 *      bosta.poll.status-max-pages (10). Only a complete walk advances the mark (to the newest updatedAt
 *      seen); a capped walk resumes next cycle (head first, then shifted by the newly updated — the same
 *      mechanics as discovery); a page repeating the walk (same first / last, or nothing new) stops it with
 *      a WARN and saves nothing. First run: walk back bosta.poll.status-safety-net-hours.
 *      An item whose tracking number is one of this tenant's in-flight shipments goes through
 *      BostaIngestionHelper.ingestListItem (the idem key drops what didn't change); the shipment counts as
 *      checked (last_polled_at). Everything else on the list is discovery's.
 *   2. safety net: in-flight shipments not checked for bosta.poll.status-safety-net-hours (4) are fetched
 *      one by one as before (oldest first, at most status-max-per-cycle) — a shipment the walk never
 *      shows (unchanged, or missed) is still verified every few hours.
 * All Bosta calls are BACKGROUND in the shared limiter; nothing sleeps in the worker — a rate limit stops
 * the tenant's cycle and backs it off.
 */
@Service
public class BostaStatusPollJob {

    private static final Logger log = LoggerFactory.getLogger(BostaStatusPollJob.class);

    // Non-terminal states that the status poll must watch. Keep in sync with
    // the shipment_internal_state enum and the V35 partial index predicate.
    private static final String TERMINAL_IN_CLAUSE =
        "'delivered','returned','lost','terminated','cancelled'";

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted " +
        "FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  maxPerCycle;
    private final boolean              statusEnabled;
    private BostaV2Client              bostaV2;
    private int pageLimit = 50, maxPages = 10, overlapMinutes = 10, safetyNetHours = 4;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWalk(BostaV2Client bostaV2,
                        @Value("${bosta.poll.status-page-limit:50}") int pageLimit,
                        @Value("${bosta.poll.status-max-pages:10}") int maxPages,
                        @Value("${bosta.poll.status-overlap-minutes:10}") int overlapMinutes,
                        @Value("${bosta.poll.status-safety-net-hours:4}") int safetyNetHours) {
        this.bostaV2 = bostaV2;
        this.pageLimit = pageLimit;
        this.maxPages = maxPages;
        this.overlapMinutes = overlapMinutes;
        this.safetyNetHours = safetyNetHours;
    }

    // Per-tenant rate-limit backoff: epoch-ms after which the tenant's poll resumes.
    // Rate limits are per API key, so each tenant has its own backoff timer.
    // Reset to empty on app restart (acceptable — the 429 window is short).
    private final Map<UUID, Long> rateLimitRetryUntilByTenant = new ConcurrentHashMap<>();

    public BostaStatusPollJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            EncryptionService encryptionService,
            BostaIngestionHelper ingestionHelper,
            @Value("${bosta.poll.status-max-per-cycle:200}") int maxPerCycle,
            // No longer used (2026-10-04): nothing sleeps in the worker; the shared limiter paces calls.
            // Kept so the constructor (and hand-built instances) stay unchanged.
            @Value("${bosta.poll.inter-fetch-delay-ms:100}") long interFetchDelayMs,
            @Value("${bosta.poll.status-enabled:true}") boolean statusEnabled) {
        this.ownerJdbc      = new JdbcTemplate(ownerDs);
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.encryptionService = encryptionService;
        this.ingestionHelper = ingestionHelper;
        this.maxPerCycle    = maxPerCycle;
        this.statusEnabled  = statusEnabled;
    }

    // Cron: every 3 minutes (5-field standard cron: minute hour dom month dow).
    // Setting bosta.poll.status-enabled=false makes pollAll() a no-op WITHOUT removing the
    // bean — keeping the bean present ensures JobRunr's recurring-job registry stays up-to-date
    // and doesn't re-fire stale entries from its own database with a different cron.
    @Recurring(id = "bosta-status-poll", cron = "*/3 * * * *")
    @Job(name = "Bosta status poll")
    public void pollAll() {
        if (!statusEnabled) {
            log.info("Status poll disabled via bosta.poll.status-enabled=false — skipping");
            return;
        }
        List<Map<String, Object>> accounts = ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS);
        if (accounts.isEmpty()) {
            log.debug("Status poll: no active Bosta accounts");
            return;
        }

        for (Map<String, Object> row : accounts) {
            UUID tenantId       = (UUID) row.get("tenant_id");
            String encryptedKey = (String) row.get("api_key_encrypted");

            // Per-tenant rate-limit backoff: skip this tenant until the window clears.
            Long retryUntil = rateLimitRetryUntilByTenant.get(tenantId);
            if (retryUntil != null && System.currentTimeMillis() < retryUntil) {
                long waitSecs = (retryUntil - System.currentTimeMillis()) / 1000;
                log.info("Status poll tenant {}: rate-limited — backing off {}s more", tenantId, waitSecs);
                continue;
            }

            try {
                String apiKey = encryptionService.decrypt(encryptedKey);
                pollTenant(tenantId, apiKey);
            } catch (Exception e) {
                log.warn("Status poll failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void pollTenant(UUID tenantId, String apiKey) {
        java.sql.Timestamp cycleStart = java.sql.Timestamp.from(java.time.Instant.now());
        WalkResult walk = bostaV2 != null ? walkTenant(tenantId, apiKey) : new WalkResult(true, 0, 0);
        // Observability (2026-10-04): {safety-net fetches, changes found, of which missed by the walk}.
        int[] net = {0, 0, 0};
        if (walk.ok()) TenantContext.runAs(tenantId, (Runnable) () -> {

            // Safety net (V138): in-flight shipments not checked for safety-net-hours, oldest first.
            // provider_state is the last known numeric Bosta code — used to skip re-enqueuing
            // if the fetched state hasn't changed (prevents unnecessary webhook_events rows).
            // Runs inside tx.execute() so TenantAwareConnection fires the GUC → RLS applies.
            List<Map<String, Object>> shipments = tx.execute(s -> jdbc.queryForList(
                "SELECT id, tracking_number, provider_state " +
                "FROM shipments " +
                "WHERE tenant_id = ? " +
                "  AND provider = 'bosta' " +
                "  AND tracking_number IS NOT NULL " +
                "  AND internal_state NOT IN (" + TERMINAL_IN_CLAUSE + ") " +
                "  AND provider_not_found_at IS NULL " +   // FR-14: exclude permanently-unknown deliveries
                "  AND (last_polled_at IS NULL OR last_polled_at < LEAST(now() - (? * INTERVAL '1 hour'), ?)) " +
                "ORDER BY last_polled_at ASC NULLS FIRST " +
                "LIMIT ?",
                tenantId, safetyNetHours, cycleStart, maxPerCycle));   // never what this cycle's walk just checked

            if (shipments == null || shipments.isEmpty()) return;

            int seen = 0, enqueued = 0;
            for (Map<String, Object> row : shipments) {
                UUID    shipmentId           = (UUID)    row.get("id");
                String  trackingNumber       = (String)  row.get("tracking_number");
                // provider_state is nullable (NULL = never polled successfully before)
                Integer currentProviderState = (Integer) row.get("provider_state");
                seen++;
                net[0]++;

                try {
                    if (ingestionHelper.ingestDelivery(
                            tenantId, apiKey, trackingNumber, "bosta_poll",
                            currentProviderState)) {
                        enqueued++;
                        net[1]++;
                        // The walk should have caught this: a change on a shipment it hasn't shown for
                        // safety-net-hours. (No known state yet → just the first one, not a miss.)
                        if (currentProviderState != null) {
                            net[2]++;
                            Map<String, Object> ev = tx.execute(s -> jdbc.queryForMap(
                                "SELECT payload->>'state' AS state, payload->>'updatedAt' AS updated_at FROM webhook_events " +
                                "WHERE tenant_id = ? AND source = 'bosta_poll'::webhook_source " +
                                "  AND payload->>'trackingNumber' = ? ORDER BY id DESC LIMIT 1",
                                tenantId, trackingNumber));
                            log.warn("Status poll tenant {}: status walk missed change — tracking {} state {}→{} " +
                                "(Bosta updatedAt {})", tenantId, trackingNumber, currentProviderState,
                                ev.get("state"), ev.get("updated_at"));
                        }
                    }
                } catch (DeliveryNotFoundException e) {
                    // FR-14: Bosta says this tracking number does not exist (HTTP 400 "Delivery not found").
                    // Stamp provider_not_found_at so the shipment drops out of the poll set permanently.
                    // The shipment row is preserved for audit; internal_state is unchanged.
                    log.warn("Status poll tenant {}: tracking {} not found in Bosta — stamping provider_not_found_at (drops from poll set)",
                        tenantId, trackingNumber);
                    final UUID fShipId = shipmentId;
                    tx.execute(s -> {
                        jdbc.update(
                            "UPDATE shipments SET provider_not_found_at = now() WHERE id = ? AND tenant_id = ?",
                            fShipId, tenantId);
                        return null;
                    });
                } catch (BostaRateLimitException e) {
                    // Rate-limited: stop fetching ALL remaining shipments for this tenant
                    // (more calls would extend the blackout window). Record the backoff
                    // and return immediately — do NOT update last_polled_at for this
                    // shipment (it will be retried first-in-rotation on the next cycle).
                    long resumeAt = System.currentTimeMillis() + (e.getRetryAfterSeconds() + 10) * 1000L;
                    rateLimitRetryUntilByTenant.put(tenantId, resumeAt);
                    log.warn("Status poll tenant {}: rate-limited by Bosta (retryAfter={}s) — " +
                             "aborting cycle, next attempt after {}s",
                        tenantId, e.getRetryAfterSeconds(), e.getRetryAfterSeconds() + 10);
                    if (seen > 1) {
                        log.info("Status poll tenant {}: {} of {} shipments checked before rate limit",
                            tenantId, seen - 1, shipments.size());
                    }
                    return; // exit the TenantContext.runAs Runnable — breaks out of shipment loop
                } catch (BostaTransientException e) {
                    log.warn("Status poll tenant {}: transient error on {} — skipping: {}",
                        tenantId, trackingNumber, e.getMessage());
                } catch (Exception e) {
                    log.error("Status poll tenant {}: unexpected error on {} — skipping",
                        tenantId, trackingNumber, e);
                }

                // Update last_polled_at regardless of outcome (for rotation).
                final UUID fShipmentId = shipmentId;
                tx.execute(s -> {
                    jdbc.update("UPDATE shipments SET last_polled_at = now() WHERE id = ?",
                        fShipmentId);
                    return null;
                });

                if (Thread.currentThread().isInterrupted()) break;   // no sleep: the shared limiter paces the calls
            }

        });
        log.info("Status poll tenant {}: walk {} page(s), {} change(s) ingested{}; safety net {} fetch(es), " +
            "{} change(s) found ({} missed by the walk)", tenantId, walk.pages(), walk.ingested(),
            walk.ok() ? "" : " (rate limited)", net[0], net[1], net[2]);
    }

    // ---- the -updatedAt walk (V138) ------------------------------------------------------------

    private record WalkState(java.time.Instant markAt, Integer walkPage, java.time.Instant walkNewestAt, boolean seeded) {}

    /** Walks the tenant's changed deliveries. False only when rate limited (the tenant then backs off). */
    /** One cycle's walk: false {@code ok} = rate limited (the tenant then backs off and skips the safety net). */
    record WalkResult(boolean ok, int pages, int ingested) {}

    WalkResult walkTenant(UUID tenantId, String apiKey) {
        WalkResult result = TenantContext.runAs(tenantId, () -> {
            WalkState state = loadState(tenantId);
            java.time.Instant stopBefore = state.markAt().minus(java.time.Duration.ofMinutes(overlapMinutes));
            boolean resuming = state.walkPage() != null && state.walkNewestAt() != null;
            java.time.Instant headNewest = resuming ? state.walkNewestAt() : null;
            boolean inHead = resuming;
            int newSinceWalk = 0, pagesRead = 0, page = 1, ingested = 0;
            java.time.Instant newestSeen = null;
            boolean reachedMark = false, endOfList = false, repeated = false, stopped = false, rateLimited = false;
            java.util.Set<String> seen = new java.util.HashSet<>();
            String prevFirst = null, prevLast = null;

            while (pagesRead < maxPages) {
                List<com.fasterxml.jackson.databind.JsonNode> items;
                try {
                    items = bostaV2.searchDeliveriesPage(apiKey, page, pageLimit, "-updatedAt");
                } catch (BostaRateLimitException e) {
                    backOff(tenantId, e.getRetryAfterSeconds());
                    rateLimited = true;
                    break;
                } catch (BostaException e) {
                    log.warn("Status poll tenant {}: search page {} failed — walk stops, mark kept: {}", tenantId, page, e.getMessage());
                    stopped = true;
                    break;
                }
                pagesRead++;
                if (items.isEmpty()) { endOfList = true; break; }

                List<String> pageTracking = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode it : items) {
                    String tn = it.path("trackingNumber").asText("");
                    if (!tn.isBlank()) pageTracking.add(tn);
                }
                String first = pageTracking.isEmpty() ? null : pageTracking.get(0);
                String last = pageTracking.isEmpty() ? null : pageTracking.get(pageTracking.size() - 1);
                if ((first != null && first.equals(prevFirst) && last.equals(prevLast))
                        || (!pageTracking.isEmpty() && seen.containsAll(pageTracking))) {
                    log.warn("Status poll tenant {}: Bosta search page {} repeats the walk ({}…{}) — stopping, mark {} kept",
                        tenantId, page, first, last, state.markAt());
                    repeated = true;
                    break;
                }
                prevFirst = first;
                prevLast = last;

                java.util.Set<String> inFlight = inFlight(tenantId, pageTracking);
                boolean leftHead = false;
                for (com.fasterxml.jackson.databind.JsonNode item : items) {
                    String tn = item.path("trackingNumber").asText("");
                    if (tn.isBlank()) continue;
                    java.time.Instant updated = BostaHttpGateway.updatedAt(item);
                    if (updated != null && (newestSeen == null || updated.isAfter(newestSeen))) newestSeen = updated;
                    if (updated != null && updated.isBefore(stopBefore)) reachedMark = true;
                    if (inHead) {
                        if (updated != null && !updated.isAfter(headNewest)) leftHead = true;
                        else newSinceWalk++;
                    }
                    if (!seen.add(tn) || !inFlight.contains(tn)) continue;
                    try {
                        if (ingestionHelper.ingestListItem(tenantId, apiKey, item, "bosta_poll")) ingested++;
                        tx.execute(s -> jdbc.update("UPDATE shipments SET last_polled_at = now() " +
                            "WHERE tenant_id = ? AND tracking_number = ?", tenantId, tn));
                    } catch (BostaRateLimitException e) {
                        backOff(tenantId, e.getRetryAfterSeconds());
                        rateLimited = true;
                        break;
                    } catch (Exception e) {
                        log.warn("Status poll tenant {}: ingest of {} failed — the safety net will fetch it: {}",
                            tenantId, tn, e.toString());
                    }
                }
                if (rateLimited || reachedMark) break;
                if (items.size() < pageLimit) { endOfList = true; break; }
                if (inHead && leftHead) {
                    inHead = false;
                    page = Math.max(page + 1, state.walkPage() + newSinceWalk / pageLimit - 1);
                    prevFirst = prevLast = null;
                    continue;
                }
                page++;
            }

            boolean complete = (reachedMark || endOfList) && !rateLimited && !stopped && !repeated;
            if (complete) {
                java.time.Instant newMark = latest(state.markAt(), latest(newestSeen, resuming ? state.walkNewestAt() : null));
                saveState(tenantId, newMark, null, null);
            } else if (!rateLimited && !stopped && !repeated) {
                java.time.Instant walkNewest = resuming ? latest(state.walkNewestAt(), newestSeen) : newestSeen;
                saveState(tenantId, state.markAt(), page, walkNewest != null ? walkNewest : state.markAt());
                log.warn("Status poll tenant {}: walk not finished — {} page(s) read (page cap), mark {} kept, " +
                    "continuing from page {} next cycle", tenantId, pagesRead, state.markAt(), page);
            } else if (state.seeded()) {
                saveState(tenantId, state.markAt(), state.walkPage(), state.walkNewestAt());
            }
            return new WalkResult(!rateLimited, pagesRead, ingested);
        });
        return result == null ? new WalkResult(true, 0, 0) : result;
    }

    private void backOff(UUID tenantId, long retryAfterSeconds) {
        rateLimitRetryUntilByTenant.put(tenantId, System.currentTimeMillis() + (retryAfterSeconds + 10) * 1000L);
        log.warn("Status poll tenant {}: rate limited (retryAfter={}s) — cycle stops, next after {}s",
            tenantId, retryAfterSeconds, retryAfterSeconds + 10);
    }

    /** Of these tracking numbers, the tenant's in-flight shipments (the ones the poll keeps current). */
    private java.util.Set<String> inFlight(UUID tenantId, List<String> trackingNumbers) {
        if (trackingNumbers.isEmpty()) return java.util.Set.of();
        String in = String.join(",", java.util.Collections.nCopies(trackingNumbers.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(trackingNumbers);
        List<String> found = tx.execute(s -> jdbc.queryForList(
            "SELECT tracking_number FROM shipments WHERE tenant_id = ? AND provider = 'bosta' " +
            "  AND internal_state NOT IN (" + TERMINAL_IN_CLAUSE + ") AND provider_not_found_at IS NULL " +
            "  AND tracking_number IN (" + in + ")", String.class, args.toArray()));
        return new java.util.HashSet<>(found);
    }

    private WalkState loadState(UUID tenantId) {
        Map<String, Object> row = tx.execute(s -> jdbc.queryForMap(
            "SELECT poll_mark_at, poll_walk_page, poll_walk_newest_at FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta'", tenantId));
        java.time.Instant mark = toInstant(row.get("poll_mark_at"));
        Integer walkPage = row.get("poll_walk_page") == null ? null : ((Number) row.get("poll_walk_page")).intValue();
        java.time.Instant walkNewest = toInstant(row.get("poll_walk_newest_at"));
        if (mark != null) return new WalkState(mark, walkPage, walkNewest, false);
        return new WalkState(java.time.Instant.now().minus(java.time.Duration.ofHours(Math.max(1, safetyNetHours))),
            null, null, true);
    }

    private void saveState(UUID tenantId, java.time.Instant mark, Integer walkPage, java.time.Instant walkNewest) {
        tx.execute(s -> jdbc.update(
            "UPDATE courier_accounts SET poll_mark_at = ?, poll_walk_page = ?, poll_walk_newest_at = ? " +
            "WHERE tenant_id = ? AND provider = 'bosta'",
            mark == null ? null : java.sql.Timestamp.from(mark), walkPage,
            walkNewest == null ? null : java.sql.Timestamp.from(walkNewest), tenantId));
    }

    private static java.time.Instant latest(java.time.Instant a, java.time.Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static java.time.Instant toInstant(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
