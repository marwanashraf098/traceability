package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Tier 2 — Discovery Poll: ingests NEW Bosta deliveries not yet known to Traced.
 *
 * Runs infrequently (default 20 minutes, config-wired via bosta.poll.discovery-cron —
 * see the constant default below; not yet changed by this class). Pages the Bosta List
 * API newest-first and routes each not-yet-linked delivery through BostaIngestionHelper
 * with source='bosta_poll_discovery'. Idempotency ensures already-processed deliveries
 * are a no-op; truly new ones get ingested and matched via ShipmentLinkService, then
 * Tier 1 keeps their status current.
 *
 * The full backfill (all pages) remains as the on-connect + manual Sync button trigger.
 * Tier 2 is the lightweight ongoing discovery pass covering the most recently created
 * deliveries that may have arrived since the last cycle.
 *
 * Paging (v2 search, 2026-10-03; supersedes V133's v0 paging). Bosta's v0 list capped pages at 10
 * items whatever pageSize asked for, offset by the requested size, so pages were walked blind. Discovery
 * now reads POST /api/v2/deliveries/search (BostaV2Client.searchDeliveriesPage) — the dashboard's own
 * search, proven in prod by BostaSearchProbe: sortBy "-createdAt", limit bosta.poll.discovery-page-limit
 * (50) honoured, page 2 continues exactly after page 1, count always 0. It walks pages 1, 2, 3 …
 * newest created first, de-duplicated by tracking number, until:
 *   - it reaches a delivery created before courier_accounts.discovery_mark_at minus
 *     bosta.poll.discovery-overlap-minutes (10) — the walk is complete; or
 *   - an empty page, or one shorter than the limit — the end of the list, complete; or
 *   - bosta.poll.discovery-max-pages (10), or the per-cycle item ceiling — incomplete.
 * Only a complete walk advances the mark (to the newest creation time it saw). An incomplete walk
 * keeps the old mark, stores the next page (discovery_walk_page) and the newest creation time at its
 * start (discovery_walk_newest_at), logs a WARN, and the next run first catches up on deliveries
 * created since then, then continues the walk shifted by their number. Repeat-page guard: a page with
 * the same first and last tracking number as the page before, or whose items were all already seen
 * this run, means Bosta isn't paging — the walk stops with a WARN and nothing is saved (mark kept).
 * V134 cleared the v0 walk state, so the first v2 run starts at page 1 and walks back to the mark.
 * Each list item is ingested from its own fields (BostaIngestionHelper.ingestListItem — no
 * per-delivery fetch here); an item missing what ingest needs falls back to one fetch.
 * Already-linked deliveries are skipped without a Bosta call; ingest stays idempotent.
 *
 * Per-item failures (V128 retry list, 2026-10-02): a delivery whose ingest fails (a fallback fetch's
 * 5xx / IO, 429, "Delivery not found", anything unexpected) is written to bosta_discovery_failures and
 * retried by tracking number at the start of every cycle — never just skipped while the mark
 * moves past it. A 429 also stops the cycle and holds the mark. After
 * bosta.poll.discovery-max-item-failures (10) counted failures the row escalates: the
 * bosta_discovery_failed exception is raised and the row is retried only every
 * bosta.poll.discovery-slow-retry-minutes (60), until bosta.poll.discovery-retry-cap-hours (48)
 * after its first failure. A success at any point deletes the row and clears the exception. The
 * mark is never held for a failed item.
 *
 * Per-tenant advisory lock: discoverTenant() runs many short-lived transactions
 * internally (one tx.execute() per DB touch inside BostaIngestionHelper), so a single
 * pg_advisory_xact_lock would release after the first of those commits — long before
 * the cycle finishes. tryDiscoverTenant() instead pins ONE Connection for the whole
 * cycle and uses the session-level pg_try_advisory_lock/pg_advisory_unlock pair on it.
 * Non-blocking (try, not block-and-wait): if a previous cycle for this tenant is still
 * running when the next scheduled fire happens, waiting would just queue up an
 * ever-growing backlog of blocked runs once cadence rises. Skipping is self-healing —
 * the skipped cycle's mark is untouched, so the next fire retries from the same place.
 *
 * TenantContext: same pattern as BostaBackfillJob and BostaStatusPollJob — all per-tenant
 * work runs inside TenantContext.runAs(tenantId).
 */
@Service
public class BostaDiscoveryPollJob {

    private static final Logger log = LoggerFactory.getLogger(BostaDiscoveryPollJob.class);

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted " +
        "FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    // Advisory-lock namespace: a fixed, arbitrary hash so this lock space never
    // collides with any other pg_advisory_lock use elsewhere in the codebase.
    private static final int LOCK_NAMESPACE = "bosta-discovery-lock".hashCode();

    /** Newest created first — proven by BostaSearchProbe (page 2 continues exactly after page 1). */
    static final String SORT_NEWEST_CREATED = "-createdAt";

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final BostaV2Client        bostaV2;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  maxPages;
    private final int                  pageLimit;
    private final int                  overlapMinutes;
    private final int                  recoveryDays;
    private final int                  maxNewItemsPerCycle;
    private final long                 interFetchDelayMs;
    private final boolean              discoveryEnabled;
    private final int                  maxItemFailures;
    private final int                  slowRetryMinutes;
    private final int                  retryCapHours;

    public BostaDiscoveryPollJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            BostaV2Client bostaV2,
            EncryptionService encryptionService,
            BostaIngestionHelper ingestionHelper,
            @Value("${bosta.poll.discovery-max-pages:10}") int maxPages,
            @Value("${bosta.poll.discovery-page-limit:50}") int pageLimit,
            @Value("${bosta.poll.discovery-max-items-per-cycle:150}") int maxNewItemsPerCycle,
            @Value("${bosta.poll.inter-fetch-delay-ms:100}") long interFetchDelayMs,
            @Value("${bosta.poll.discovery-enabled:true}") boolean discoveryEnabled,
            @Value("${bosta.poll.discovery-max-item-failures:10}") int maxItemFailures,
            @Value("${bosta.poll.discovery-slow-retry-minutes:60}") int slowRetryMinutes,
            @Value("${bosta.poll.discovery-retry-cap-hours:48}") int retryCapHours,
            @Value("${bosta.poll.discovery-overlap-minutes:10}") int overlapMinutes,
            @Value("${bosta.poll.discovery-recovery-days:7}") int recoveryDays) {
        this.ownerJdbc           = new JdbcTemplate(ownerDs);
        this.jdbc                = jdbc;
        this.tx                  = new TransactionTemplate(txm);
        this.bostaV2             = bostaV2;
        this.encryptionService   = encryptionService;
        this.ingestionHelper     = ingestionHelper;
        this.maxPages            = maxPages;
        this.pageLimit           = pageLimit;
        this.overlapMinutes      = overlapMinutes;
        this.recoveryDays        = recoveryDays;
        this.maxNewItemsPerCycle = maxNewItemsPerCycle;
        this.interFetchDelayMs   = interFetchDelayMs;
        this.discoveryEnabled    = discoveryEnabled;
        this.maxItemFailures     = maxItemFailures;
        this.slowRetryMinutes    = slowRetryMinutes;
        this.retryCapHours       = retryCapHours;
    }

    // Cron is config-wired (bosta.poll.discovery-cron) but defaults to the same */20 as
    // before — this change does NOT flip the effective cadence; that's a follow-up once
    // the high-water mark + lock above have proven themselves at the current interval.
    @Recurring(id = "bosta-discovery-poll", cron = "${bosta.poll.discovery-cron:*/20 * * * *}")
    @Job(name = "Bosta discovery poll")
    public void discoverAll() {
        // In-method guard, not @ConditionalOnProperty at class level (bed889d): a
        // class-level conditional means the bean doesn't exist when the flag is false,
        // but JobRunr's persistent recurring-job entry still fires on schedule, can't
        // resolve the bean, marks the run failed, and immediately reschedules — rapid
        // re-fire (~5s) that hammers the DB until someone notices. Keeping the bean
        // always present and only short-circuiting the method body avoids that entirely.
        if (!discoveryEnabled) {
            log.info("Discovery poll disabled via bosta.poll.discovery-enabled=false — skipping");
            return;
        }

        List<Map<String, Object>> accounts = ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS);
        if (accounts.isEmpty()) {
            log.debug("Discovery poll: no active Bosta accounts");
            return;
        }

        for (Map<String, Object> row : accounts) {
            UUID tenantId        = (UUID) row.get("tenant_id");
            String encryptedKey  = (String) row.get("api_key_encrypted");
            try {
                String apiKey = encryptionService.decrypt(encryptedKey);
                tryDiscoverTenant(tenantId, apiKey);
            } catch (Exception e) {
                log.warn("Discovery poll failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /**
     * Acquires the per-tenant advisory lock on a single pinned Connection, runs
     * discoverTenant() if acquired, and always releases on the same Connection before
     * returning it to the pool. See the class javadoc for why this is session-level
     * try-lock rather than pg_advisory_xact_lock, and why it skips rather than blocks.
     */
    private void tryDiscoverTenant(UUID tenantId, String apiKey) {
        int tenantKey = tenantId.hashCode();
        try (Connection lockConn = jdbc.getDataSource().getConnection()) {
            boolean acquired;
            try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                ps.setInt(1, LOCK_NAMESPACE);
                ps.setInt(2, tenantKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }

            if (!acquired) {
                log.info("Discovery poll tenant {}: previous cycle still running — skipping this run",
                    tenantId);
                return;
            }

            try {
                discoverTenant(tenantId, apiKey);
            } finally {
                try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                    ps.setInt(1, LOCK_NAMESPACE);
                    ps.setInt(2, tenantKey);
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Discovery advisory lock error for tenant " + tenantId, e);
        }
    }

    /** Per-tenant discovery state (V133). */
    private record WalkState(Instant markAt, Integer walkPage, Instant walkNewestAt, boolean seeded) {}

    private void discoverTenant(UUID tenantId, String apiKey) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            int total = 0, enqueued = 0, attempted = 0, pagesRead = 0;
            boolean ceilingHit      = false;
            boolean transientError  = false;
            boolean rateLimited     = false;

            // Retry pass (V128): every delivery whose fetch failed in an earlier cycle is
            // retried here by tracking number, BEFORE the list walk and independent of the
            // list window. Counts toward the ceiling.
            Set<String> handledThisCycle = new HashSet<>();
            stopExpiredRetries(tenantId);
            List<String> retries = tx.execute(s -> jdbc.query(
                "SELECT tracking_number FROM bosta_discovery_failures " +
                "WHERE tenant_id = ? AND retries_stopped_at IS NULL " +
                "  AND (escalated_at IS NULL OR next_retry_at IS NULL OR next_retry_at <= now()) " +
                "ORDER BY first_failed_at, id LIMIT ?",
                (rs, i) -> rs.getString(1), tenantId, maxNewItemsPerCycle));
            for (String tn : retries) {
                attempted++;
                handledThisCycle.add(tn);
                ItemOutcome outcome = ingestTracked(tenantId, apiKey, tn);
                if (outcome == ItemOutcome.ENQUEUED) enqueued++;
                if (outcome == ItemOutcome.RATE_LIMITED) { rateLimited = true; break; }
                if (!pause()) { transientError = true; break; }
                if (attempted >= maxNewItemsPerCycle) { ceilingHit = true; break; }
            }

            // List walk (v2 search). Newest created first, LIMIT per page, de-duplicated by tracking
            // number within the run, until it reaches deliveries created before the last complete run's
            // mark (minus an overlap), the end of the list (empty or short page), or the page cap. A
            // capped walk keeps the old mark and continues next run from where it stopped.
            WalkState state = loadState(tenantId);
            Instant stopBefore = state.markAt().minus(Duration.ofMinutes(overlapMinutes));
            boolean resuming = state.walkPage() != null && state.walkNewestAt() != null;
            // Head = deliveries created after the interrupted walk's newest item (exact — no overlap
            // here: a same-second batch must not keep the run in the head forever).
            Instant headNewest = resuming ? state.walkNewestAt() : null;
            boolean inHead = resuming;   // resuming: first catch up on deliveries created since the walk began
            int newSinceWalk = 0;
            Instant newestSeen = null;
            boolean reachedMark = false, endOfList = false, repeatedPage = false;
            int page = 1;
            Set<String> seen = new HashSet<>();
            String prevFirst = null, prevLast = null;

            outer:
            while (!rateLimited && !ceilingHit && !transientError) {
                if (pagesRead >= maxPages) break;
                List<JsonNode> items;
                try {
                    items = bostaV2.searchDeliveriesPage(apiKey, page, pageLimit, SORT_NEWEST_CREATED);
                } catch (BostaRateLimitException e) {
                    log.warn("Discovery poll tenant {}: rate limited on page {} — stopping", tenantId, page);
                    rateLimited = true;
                    break;
                } catch (BostaException e) {
                    log.warn("Discovery poll tenant {}: search page {} failed — stopping, mark kept: {}",
                        tenantId, page, e.getMessage());
                    transientError = true;
                    break;
                }
                pagesRead++;
                if (items.isEmpty()) { endOfList = true; break; }

                List<String> pageTracking = new ArrayList<>(items.size());
                for (JsonNode it : items) {
                    String tn = trackingNumber(it);
                    if (tn != null) pageTracking.add(tn);
                }
                // Repeat-page guard: Bosta answering the same page again (page ignored), or a page of
                // nothing new, means the walk can't trust its position — stop, don't move the mark.
                String first = pageTracking.isEmpty() ? null : pageTracking.get(0);
                String last  = pageTracking.isEmpty() ? null : pageTracking.get(pageTracking.size() - 1);
                boolean samePage = first != null && first.equals(prevFirst) && last.equals(prevLast);
                if (samePage || (!pageTracking.isEmpty() && seen.containsAll(pageTracking))) {
                    log.warn("Discovery poll tenant {}: Bosta search page {} repeats the walk ({}…{}) — " +
                        "stopping, mark {} kept", tenantId, page, first, last, state.markAt());
                    repeatedPage = true;
                    break;
                }
                prevFirst = first;
                prevLast  = last;

                Set<String> linked   = alreadyLinkedTrackingNumbers(tenantId, pageTracking);
                Set<String> recorded = failureRowTrackingNumbers(tenantId, pageTracking);
                boolean leftHead = false;

                for (JsonNode item : items) {
                    String tn = trackingNumber(item);
                    if (tn == null) continue;
                    Instant created = BostaHttpGateway.createdAt(item);
                    if (created != null && (newestSeen == null || created.isAfter(newestSeen))) newestSeen = created;
                    if (created != null && created.isBefore(stopBefore)) reachedMark = true;
                    if (inHead) {
                        if (created != null && !created.isAfter(headNewest)) leftHead = true;
                        else newSinceWalk++;
                    }
                    if (!seen.add(tn)) continue;   // dedup within the run
                    total++;

                    if (linked.contains(tn)) continue;   // Tier 1 owns it
                    // Handled by the retry pass this cycle, or on the retry list (the retry pass
                    // owns it, at its own pace). Never ingested twice in one cycle.
                    if (handledThisCycle.contains(tn) || recorded.contains(tn)) continue;

                    attempted++;
                    ItemOutcome outcome = ingestListed(tenantId, apiKey, tn, item);
                    if (outcome == ItemOutcome.ENQUEUED) enqueued++;
                    if (outcome == ItemOutcome.RATE_LIMITED) { rateLimited = true; break outer; }
                    if (Thread.currentThread().isInterrupted()) { transientError = true; break outer; }
                    if (attempted >= maxNewItemsPerCycle) { ceilingHit = true; break outer; }
                }

                if (reachedMark) break;
                if (items.size() < pageLimit) { endOfList = true; break; }
                if (inHead && leftHead) {
                    // Caught up on the head: jump back into the interrupted walk, shifted by the
                    // deliveries created since it began (one page earlier for overlap; dedup and
                    // idempotent ingest absorb the repeats).
                    inHead = false;
                    page = Math.max(page + 1, state.walkPage() + newSinceWalk / pageLimit - 1);
                    prevFirst = prevLast = null;   // a jump is not a repeat
                    continue;
                }
                page++;
            }

            boolean complete = (reachedMark || endOfList) && !rateLimited && !transientError && !ceilingHit
                && !repeatedPage;
            if (complete) {
                Instant newMark = latest(state.markAt(), latest(newestSeen, resuming ? state.walkNewestAt() : null));
                saveState(tenantId, newMark, null, null);
            } else if (!rateLimited && !transientError && !repeatedPage) {
                // Page cap or item ceiling: keep the old mark, continue from here next run.
                Instant walkNewest = resuming ? latest(state.walkNewestAt(), newestSeen) : newestSeen;
                // `page` is the next unread page after the page cap, or the page the item ceiling
                // interrupted (redone next run; its handled items are idempotent no-ops).
                saveState(tenantId, state.markAt(), page, walkNewest != null ? walkNewest : state.markAt());
                log.warn("Discovery poll tenant {}: walk not finished — {} page(s) read{}, mark {} kept, " +
                    "continuing from page {} next run", tenantId, pagesRead,
                    ceilingHit ? " (item ceiling)" : " (page cap)", state.markAt(), page);
            } else if (state.seeded()) {
                saveState(tenantId, state.markAt(), state.walkPage(), state.walkNewestAt());   // persist the seed
            }

            if (enqueued > 0) {
                log.info("Discovery poll tenant {}: {} page(s), {} seen, {} new deliveries enqueued",
                    tenantId, pagesRead, total, enqueued);
            } else {
                log.debug("Discovery poll tenant {}: {} page(s), {} seen, nothing new", tenantId, pagesRead, total);
            }
        });
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /**
     * The tenant's discovery state. First run after V133 (no discovery_mark_at): seed the mark so the
     * walk goes back over the window the old 50-per-page reads missed — the earlier of the old
     * tracking-number mark's creation time and now − recovery-days, never before the tenant's Shopify
     * connection cutoff (pre-connect deliveries are ignored anyway).
     */
    private WalkState loadState(UUID tenantId) {
        Map<String, Object> row = tx.execute(s -> jdbc.queryForMap(
            "SELECT discovery_mark_at, discovery_walk_page, discovery_walk_newest_at, discovery_high_water_tracking " +
            "FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'", tenantId));
        Instant mark = toInstant(row.get("discovery_mark_at"));
        Integer walkPage = row.get("discovery_walk_page") == null ? null : ((Number) row.get("discovery_walk_page")).intValue();
        Instant walkNewest = toInstant(row.get("discovery_walk_newest_at"));
        if (mark != null) return new WalkState(mark, walkPage, walkNewest, false);

        Instant seed = Instant.now().minus(Duration.ofDays(recoveryDays));
        String oldTracking = (String) row.get("discovery_high_water_tracking");
        if (oldTracking != null) {
            String raw = tx.execute(s -> jdbc.query(
                "SELECT raw::text FROM shipments WHERE tenant_id = ? AND tracking_number = ? AND raw IS NOT NULL " +
                "UNION ALL SELECT raw::text FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ? " +
                "  AND raw IS NOT NULL LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, oldTracking, tenantId, oldTracking));
            Instant oldTs = raw == null ? null : PreConnectDeliveryFilter.parseCreatedAt(readTree(raw));
            if (oldTs != null && oldTs.isBefore(seed)) seed = oldTs;
        }
        Object cutoff = tx.execute(s -> jdbc.queryForObject(
            "SELECT CASE WHEN bool_or(orders_ingest_from IS NULL) THEN NULL ELSE MIN(orders_ingest_from) END " +
            "FROM stores WHERE tenant_id = ?", Object.class, tenantId));
        Instant cut = toInstant(cutoff);
        if (cut != null && cut.isAfter(seed)) seed = cut;
        log.info("Discovery poll tenant {}: first paged run — walking back to {}", tenantId, seed);
        return new WalkState(seed, null, null, true);
    }

    private void saveState(UUID tenantId, Instant mark, Integer walkPage, Instant walkNewest) {
        tx.execute(s -> jdbc.update(
            "UPDATE courier_accounts SET discovery_mark_at = ?, discovery_walk_page = ?, discovery_walk_newest_at = ? " +
            "WHERE tenant_id = ? AND provider = 'bosta'",
            mark == null ? null : Timestamp.from(mark), walkPage,
            walkNewest == null ? null : Timestamp.from(walkNewest), tenantId));
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String raw) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw); }
        catch (Exception e) { return null; }
    }

    private enum ItemOutcome { ENQUEUED, SKIPPED, FAILED, RATE_LIMITED }

    /**
     * One delivery through BostaIngestionHelper. Success (enqueued or a legitimate skip)
     * clears any retry-list row; a 429 records the item without counting an attempt; any
     * other exception (5xx / IO, "Delivery not found", unexpected) counts one attempt.
     */
    private ItemOutcome ingestTracked(UUID tenantId, String apiKey, String trackingNumber) {
        try {
            boolean enq = ingestionHelper.ingestDelivery(
                tenantId, apiKey, trackingNumber, "bosta_poll_discovery");
            clearFailure(tenantId, trackingNumber);
            return enq ? ItemOutcome.ENQUEUED : ItemOutcome.SKIPPED;
        } catch (BostaRateLimitException e) {
            log.warn("Discovery poll tenant {}: rate limited on {} — stopping this cycle",
                tenantId, trackingNumber);
            recordFailure(tenantId, trackingNumber, true, "rate limited (retry after "
                + e.getRetryAfterSeconds() + "s)");
            return ItemOutcome.RATE_LIMITED;
        } catch (Exception e) {
            log.warn("Discovery poll tenant {}: fetch failed for {} — on the retry list: {}",
                tenantId, trackingNumber, e.toString());
            recordFailure(tenantId, trackingNumber, false, e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return ItemOutcome.FAILED;
        }
    }

    /** One search-list item through BostaIngestionHelper.ingestListItem — same outcome handling as ingestTracked. */
    private ItemOutcome ingestListed(UUID tenantId, String apiKey, String trackingNumber, JsonNode item) {
        try {
            boolean enq = ingestionHelper.ingestListItem(tenantId, apiKey, item, "bosta_poll_discovery");
            clearFailure(tenantId, trackingNumber);
            return enq ? ItemOutcome.ENQUEUED : ItemOutcome.SKIPPED;
        } catch (BostaRateLimitException e) {
            log.warn("Discovery poll tenant {}: rate limited on {} — stopping this cycle",
                tenantId, trackingNumber);
            recordFailure(tenantId, trackingNumber, true, "rate limited (retry after "
                + e.getRetryAfterSeconds() + "s)");
            return ItemOutcome.RATE_LIMITED;
        } catch (Exception e) {
            log.warn("Discovery poll tenant {}: ingest failed for {} — on the retry list: {}",
                tenantId, trackingNumber, e.toString());
            recordFailure(tenantId, trackingNumber, false, e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return ItemOutcome.FAILED;
        }
    }

    /** A search item's tracking number as text (Bosta sends it as a number or a string), or null. */
    private static String trackingNumber(JsonNode item) {
        JsonNode t = item.path("trackingNumber");
        if (t.isMissingNode() || t.isNull()) return null;
        String s = t.asText();
        return s.isBlank() ? null : s;
    }

    /** Sleeps the inter-fetch delay; false if interrupted. */
    private boolean pause() {
        try {
            if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recordFailure(UUID tenantId, String trackingNumber, boolean rateLimited, String error) {
        String err = error.length() > 500 ? error.substring(0, 500) : error;
        Boolean escalated = tx.execute(s -> {
            jdbc.update(
                "INSERT INTO bosta_discovery_failures " +
                "    (tenant_id, tracking_number, attempts, rate_limited_count, last_error) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, tracking_number) DO UPDATE SET " +
                "    attempts           = bosta_discovery_failures.attempts + EXCLUDED.attempts, " +
                "    rate_limited_count = bosta_discovery_failures.rate_limited_count + EXCLUDED.rate_limited_count, " +
                "    last_failed_at     = now(), " +
                "    last_error         = EXCLUDED.last_error",
                tenantId, trackingNumber, rateLimited ? 0 : 1, rateLimited ? 1 : 0, err);
            // An escalated row waits the slow interval before its next retry.
            jdbc.update(
                "UPDATE bosta_discovery_failures " +
                "SET next_retry_at = now() + (? * INTERVAL '1 minute') " +
                "WHERE tenant_id = ? AND tracking_number = ? AND escalated_at IS NOT NULL",
                slowRetryMinutes, tenantId, trackingNumber);
            // Escalate at N counted failures: raises bosta_discovery_failed; retries continue
            // at the slow interval until the cap.
            return jdbc.update(
                "UPDATE bosta_discovery_failures " +
                "SET escalated_at = now(), next_retry_at = now() + (? * INTERVAL '1 minute') " +
                "WHERE tenant_id = ? AND tracking_number = ? AND escalated_at IS NULL AND attempts >= ?",
                slowRetryMinutes, tenantId, trackingNumber, maxItemFailures) > 0;
        });
        if (Boolean.TRUE.equals(escalated)) {
            log.warn("Discovery poll tenant {}: {} failed {} times — raised as bosta_discovery_failed; " +
                "retrying every {} min until {} h after the first failure",
                tenantId, trackingNumber, maxItemFailures, slowRetryMinutes, retryCapHours);
        }
    }

    /**
     * Rows whose first failure is older than the cap stop being retried (escalated too if they
     * never reached N — e.g. only 429s — so the exception is raised either way).
     */
    private void stopExpiredRetries(UUID tenantId) {
        Integer stopped = tx.execute(s -> jdbc.update(
            "UPDATE bosta_discovery_failures " +
            "SET retries_stopped_at = now(), escalated_at = COALESCE(escalated_at, now()) " +
            "WHERE tenant_id = ? AND retries_stopped_at IS NULL " +
            "  AND first_failed_at <= now() - (? * INTERVAL '1 hour')",
            tenantId, retryCapHours));
        if (stopped != null && stopped > 0) {
            log.warn("Discovery poll tenant {}: stopped retrying {} delivery(ies) {} h after their first " +
                "failed fetch — bosta_discovery_failed stays open", tenantId, stopped, retryCapHours);
        }
    }

    private void clearFailure(UUID tenantId, String trackingNumber) {
        tx.execute(s -> jdbc.update(
            "DELETE FROM bosta_discovery_failures WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, trackingNumber));
    }

    /** Of these tracking numbers, which have a retry-list row (any state)? */
    private Set<String> failureRowTrackingNumbers(UUID tenantId, List<String> trackingNumbers) {
        if (trackingNumbers.isEmpty()) return Set.of();
        String placeholders = trackingNumbers.stream().map(t -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(trackingNumbers);
        List<String> found = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM bosta_discovery_failures " +
            "WHERE tenant_id = ? AND tracking_number IN (" + placeholders + ")",
            (rs, i) -> rs.getString(1),
            args.toArray()));
        return new HashSet<>(found);
    }

    /**
     * Batched per-page check: of these tracking numbers, which already have a
     * shipments row (i.e. are already linked)? One indexed query per page
     * (shipments.tracking_number is UNIQUE, V1) instead of one Bosta API call per item.
     */
    private Set<String> alreadyLinkedTrackingNumbers(UUID tenantId, List<String> trackingNumbers) {
        if (trackingNumbers.isEmpty()) return Set.of();

        String placeholders = trackingNumbers.stream().map(t -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(trackingNumbers);

        List<String> found = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM shipments " +
            "WHERE tenant_id = ? AND tracking_number IN (" + placeholders + ")",
            (rs, i) -> rs.getString(1),
            args.toArray()));
        return new HashSet<>(found);
    }

    /**
     * Exposed for tests only: lets a test acquire/release the exact same advisory
     * lock this job uses, without duplicating the key derivation.
     */
    public static int[] advisoryLockKeys(UUID tenantId) {
        return new int[]{LOCK_NAMESPACE, tenantId.hashCode()};
    }
}
