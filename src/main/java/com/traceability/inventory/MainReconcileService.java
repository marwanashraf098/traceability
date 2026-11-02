package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;

/**
 * Shopify Main-location reconciler ("check-up") — R1, REPORT-ONLY (V200, approved 2026-11-02).
 *
 * For one tenant, per variant: Traced's target for Shopify's Traced Main Warehouse ({@link MainReconcileRules}) against
 * Shopify's ON-HAND there, recorded on main_reconcile_runs / main_reconcile_diffs, plus Part 1's would-be subtracts
 * (main_reconcile_part1_claims, status 'report_only'). <b>It never writes to Shopify</b>: the only gateway methods it
 * calls are the read-only {@code fetchMainLevels} / {@code fetchMainLevelsPage} (and the item-id read). Write mode is
 * R2; while {@code traced.main-reconcile.report-only} is on (the default) there is no write path to reach, and turning
 * it off refuses to start ({@link #setReportOnly}) until R2 exists.
 *
 * Order of work: store-level checks (allowlist, store, main linked, IncrementRecoveryRules.holdReason — inactive /
 * deleted location → skipped run, the existing shopify_location_inactive alert is the merchant's warning) → Shopify
 * read, with NO DB transaction open → ONE transaction under the seed's per-tenant pg_advisory_xact_lock: Part 1, then
 * the target and every skip, the two-run rule, diffs, the variant state and the run's counts.
 *
 * Two-run rule: a difference is confirmed only when the same delta was first seen ≥ confirm-after-minutes earlier
 * (delta_since, the first Shopify read that saw it) and no orders/updated / orders/cancelled / inventory_levels/update
 * for the variant arrived since then (variant_state.last_event_at). An unconfirmed difference is re-queued to be read
 * again once that time has passed.
 *
 * Runs on the caller's JdbcTemplate (app_user + RLS) inside TenantContext.runAs (MainReconcileJob).
 */
@Service
public class MainReconcileService {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileService.class);
    private static final int EVAL_CHUNK = 1000;

    public record Level(boolean stocked, int onHand, int available, int committed, int damaged) {
        static final Level UNSTOCKED = new Level(false, 0, 0, 0, 0);
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final StoreRepository storeRepository;
    private final InventoryItemIdService itemIds;
    private final ObjectMapper mapper = new ObjectMapper();

    private boolean enabled = false;
    private Set<String> shops = Set.of();
    private int confirmAfterMinutes = 10;
    private int part1WindowDays = 30;
    private int debounceSeconds = 60;
    private int queueBatch = 500;
    private int cancelledFulfillmentWindowMinutes = 60;
    private int storeSkipRowMinutes = 60;
    private int retentionDays = 14;

    public MainReconcileService(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                                ShopifyTokenProvider tokenProvider, StoreRepository storeRepository) {
        this.jdbc            = jdbc;
        this.named           = new NamedParameterJdbcTemplate(jdbc);
        this.tx              = new TransactionTemplate(txm);
        this.shopify         = shopify;
        this.tokenProvider   = tokenProvider;
        this.storeRepository = storeRepository;
        this.itemIds         = new InventoryItemIdService(jdbc, txm, shopify);
    }

    // ── configuration ───────────────────────────────────────────────────────────────────────────────────────────

    /** Write mode does not exist in R1: report-only must stay on. */
    @Autowired
    public void setReportOnly(@Value("${traced.main-reconcile.report-only:true}") boolean reportOnly) {
        if (!reportOnly) {
            throw new IllegalStateException("traced.main-reconcile.report-only=false: write mode is not built (R2) — " +
                "the reconciler only reports");
        }
    }

    @Autowired
    public void setEnabled(@Value("${traced.main-reconcile.enabled:false}") boolean enabled) { this.enabled = enabled; }

    /** Staged rollout: comma-separated shop domains; empty = every store (SHOPIFY_OAUTH_UPGRADE_SHOPS semantics). */
    @Autowired
    public void setShops(@Value("${traced.main-reconcile.shops:}") String csv) {
        Set<String> s = new HashSet<>();
        if (csv != null) for (String x : csv.split(",")) {
            String t = x.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) s.add(t);
        }
        this.shops = Set.copyOf(s);
    }

    @Autowired
    public void setConfirmAfterMinutes(@Value("${traced.main-reconcile.confirm-after-minutes:10}") int m) { this.confirmAfterMinutes = m; }
    @Autowired
    public void setPart1WindowDays(@Value("${traced.main-reconcile.part1-window-days:30}") int d) { this.part1WindowDays = d; }
    @Autowired
    public void setDebounceSeconds(@Value("${traced.main-reconcile.debounce-seconds:60}") int s) { this.debounceSeconds = s; }
    @Autowired
    public void setQueueBatch(@Value("${traced.main-reconcile.queue-batch:500}") int n) { this.queueBatch = n; }

    /** A cancelled / errored fulfillment younger than this skips its variant (Shopify may re-fulfill); older → ignored. */
    @Autowired
    public void setCancelledFulfillmentWindowMinutes(
            @Value("${traced.main-reconcile.cancelled-fulfillment-window-minutes:60}") int m) { this.cancelledFulfillmentWindowMinutes = m; }
    /** At most one store-level skipped run row per store per this many minutes. */
    @Autowired
    public void setStoreSkipRowMinutes(@Value("${traced.main-reconcile.store-skip-row-minutes:60}") int m) { this.storeSkipRowMinutes = m; }
    /** Runs and their diffs older than this are deleted by the sweep job (Part 1 claims are kept). */
    @Autowired
    public void setRetentionDays(@Value("${traced.main-reconcile.retention-days:14}") int d) { this.retentionDays = d; }

    public boolean enabled() { return enabled; }
    public int retentionDays() { return retentionDays; }
    public int debounceSeconds() { return debounceSeconds; }

    boolean allowlisted(String shopDomain) {
        return shops.isEmpty() || (shopDomain != null && shops.contains(shopDomain.trim().toLowerCase(Locale.ROOT)));
    }

    // ── entry points ────────────────────────────────────────────────────────────────────────────────────────────

    /** The debounced per-variant run: variants queued and untouched for debounce-seconds. Returns the run id or null. */
    public UUID runQueued(UUID tenantId) {
        requireTenant(tenantId);
        List<UUID> due = tx.execute(s -> jdbc.queryForList(
            "SELECT variant_id FROM main_reconcile_variant_state WHERE tenant_id = ? AND pending_since IS NOT NULL " +
            "  AND pending_touched_at < now() - ? * interval '1 second' ORDER BY pending_since LIMIT ?",
            UUID.class, tenantId, debounceSeconds, queueBatch));
        if (due == null || due.isEmpty()) return null;
        return run(tenantId, "variant", due);
    }

    public UUID runVariants(UUID tenantId, Collection<UUID> variantIds) {
        requireTenant(tenantId);
        return run(tenantId, "variant", new ArrayList<>(variantIds));
    }

    public UUID runSweep(UUID tenantId) {
        requireTenant(tenantId);
        return run(tenantId, "sweep", null);
    }

    private static void requireTenant(UUID tenantId) {
        if (!tenantId.equals(TenantContext.require())) throw new IllegalStateException("tenant context mismatch");
    }

    // ── the run ─────────────────────────────────────────────────────────────────────────────────────────────────

    private record Main(UUID id, String gid, String syncStatus) {
        String numeric() { return gid == null ? null : gid.replaceAll("^.*/", ""); }
    }

    private UUID run(UUID tenantId, String kind, List<UUID> variantIds) {
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
        if (store != null && !allowlisted(store.shopDomain())) {
            // Not in the staged rollout: no run row; drop what was queued so the queue job doesn't re-pick it every minute.
            tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE main_reconcile_variant_state SET pending_since = NULL, pending_touched_at = NULL, pending_source = NULL " +
                "WHERE tenant_id = ? AND pending_since IS NOT NULL", tenantId));
            return null;
        }
        Main main = tx.execute(s -> jdbc.query(
            "SELECT id, shopify_location_id, shopify_sync_status FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            rs -> rs.next() ? new Main(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)) : null, tenantId));

        String storeSkip = store == null ? MainReconcileRules.STORE_NO_STORE
            : (main == null || main.gid() == null || !"linked".equals(main.syncStatus())) ? MainReconcileRules.STORE_MAIN_NOT_LINKED
            : holdSkip(tenantId);
        if (storeSkip != null && recentStoreSkip(tenantId)) {
            // At most one store-level skipped run row per store per store-skip-row-minutes: take what was queued off
            // the queue, write nothing else (the shopify_location_inactive alert is the merchant's warning).
            tx.executeWithoutResult(s -> clearQueue(tenantId, variantIds,
                jdbc.queryForObject("SELECT now()", Timestamp.class)));
            return null;
        }

        UUID runId = tx.execute(s -> jdbc.queryForObject(
            "INSERT INTO main_reconcile_runs (tenant_id, kind, location_id, shopify_location_id) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, kind, main == null ? null : main.id(), main == null ? null : main.gid()));
        Timestamp startedAt = tx.execute(s -> jdbc.queryForObject(
            "SELECT started_at FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", Timestamp.class, runId, tenantId));

        if (storeSkip != null) {
            finishSkipped(tenantId, runId, storeSkip, variantIds, startedAt);
            return runId;
        }

        try {
            String token = tokenProvider.getValidToken(store.id());
            Map<UUID, String> items = new LinkedHashMap<>();     // variant → inventory item gid (null = unlinked)
            Map<String, Level> levels = new HashMap<>();
            Set<String> readItems = new HashSet<>();

            if ("sweep".equals(kind)) {
                String cursor = null;
                do {
                    ShopifyGateway.MainLevelsPage page = shopify.fetchMainLevelsPage(store.shopDomain(), token, main.gid(), cursor);
                    for (ShopifyGateway.MainLevel l : page.levels()) levels.put(l.inventoryItemGid(), level(l));
                    cursor = page.nextCursor();
                } while (cursor != null);
                Set<UUID> ids = new LinkedHashSet<>(variantsForItems(tenantId, levels.keySet()));
                ids.addAll(tracedVariants(tenantId, main));
                items.putAll(resolveItems(tenantId, ids, store, token));
                readItems.addAll(items.values().stream().filter(Objects::nonNull).toList());   // a sweep reads them all
            } else {
                items.putAll(resolveItems(tenantId, variantIds, store, token));
                List<String> gids = items.values().stream().filter(Objects::nonNull).distinct().toList();
                if (!gids.isEmpty()) {
                    for (ShopifyGateway.MainLevel l : shopify.fetchMainLevels(store.shopDomain(), token, main.gid(), gids)) {
                        levels.put(l.inventoryItemGid(), level(l));
                        readItems.add(l.inventoryItemGid());
                    }
                }
            }
            Timestamp readAt = tx.execute(s -> jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class));
            compute(tenantId, runId, kind, main, items, levels, readItems, readAt, startedAt);
        } catch (RuntimeException e) {
            log.warn("Main reconcile run {} tenant={} failed: {}", runId, tenantId, e.toString());
            String msg = String.valueOf(e.getMessage());
            tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE main_reconcile_runs SET status = 'failed', finished_at = now(), error = ? " +
                "WHERE id = ? AND tenant_id = ? AND status = 'running'",
                e.getClass().getSimpleName() + ": " + (msg.length() > 500 ? msg.substring(0, 500) : msg), runId, tenantId));
        }
        return runId;
    }

    private boolean recentStoreSkip(UUID tenantId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM main_reconcile_runs WHERE tenant_id = ? AND status = 'skipped' " +
            "  AND started_at > now() - make_interval(mins => ?))", Boolean.class, tenantId, storeSkipRowMinutes)));
    }

    // ── retention ───────────────────────────────────────────────────────────────────────────────────────────────

    private static final int PURGE_RUNS_PER_TX = 20;

    /**
     * Deletes this tenant's runs older than retention-days and their diffs, a few runs per transaction (short
     * transactions — a sweep's diffs can be thousands of rows). Part 1 claims and the variant state are never deleted
     * (neither has an FK to a run). Returns the number of runs deleted.
     */
    public int purgeOld(UUID tenantId) {
        requireTenant(tenantId);
        int total = 0;
        while (true) {
            Integer n = tx.execute(s -> {
                List<UUID> ids = jdbc.queryForList(
                    "SELECT id FROM main_reconcile_runs WHERE tenant_id = ? AND started_at < now() - make_interval(days => ?) " +
                    "ORDER BY started_at LIMIT ?", UUID.class, tenantId, retentionDays, PURGE_RUNS_PER_TX);
                if (ids.isEmpty()) return 0;
                String arr = uuidArray(ids);
                jdbc.update("DELETE FROM main_reconcile_diffs WHERE tenant_id = ? AND run_id = ANY(CAST(? AS uuid[]))", tenantId, arr);
                return jdbc.update("DELETE FROM main_reconcile_runs WHERE tenant_id = ? AND id = ANY(CAST(? AS uuid[]))", tenantId, arr);
            });
            if (n == null || n == 0) return total;
            total += n;
        }
    }

    private String holdSkip(UUID tenantId) {
        IncrementRecoveryRules.SetupProblem hold = tx.execute(s -> IncrementRecoveryRules.holdReason(jdbc, tenantId));
        if (hold == null) return null;
        return switch (hold) {
            case NEEDS_REAUTH      -> MainReconcileRules.STORE_NEEDS_REAUTH;
            case LOCATION_DELETED  -> MainReconcileRules.STORE_LOCATION_DELETED;
            default                -> MainReconcileRules.STORE_LOCATION_INACTIVE;
        };
    }

    private void finishSkipped(UUID tenantId, UUID runId, String reason, List<UUID> variantIds, Timestamp startedAt) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE main_reconcile_runs SET status = 'skipped', skip_reason = ?, finished_at = now() " +
                        "WHERE id = ? AND tenant_id = ?", reason, runId, tenantId);
            clearQueue(tenantId, variantIds, startedAt);
        });
    }

    /** Variants queued before this run started are taken off the queue (anything touched since stays queued). */
    private void clearQueue(UUID tenantId, List<UUID> variantIds, Timestamp startedAt) {
        if (variantIds == null) {
            jdbc.update("UPDATE main_reconcile_variant_state SET pending_since = NULL, pending_touched_at = NULL, pending_source = NULL " +
                        "WHERE tenant_id = ? AND pending_since IS NOT NULL AND pending_touched_at <= ?", tenantId, startedAt);
        } else if (!variantIds.isEmpty()) {
            jdbc.update("UPDATE main_reconcile_variant_state SET pending_since = NULL, pending_touched_at = NULL, pending_source = NULL " +
                        "WHERE tenant_id = ? AND variant_id = ANY(CAST(? AS uuid[])) AND pending_touched_at <= ?",
                        tenantId, uuidArray(variantIds), startedAt);
        }
    }

    private static Level level(ShopifyGateway.MainLevel l) {
        return new Level(l.stocked(), l.onHand(), l.available(), l.committed(), l.damaged());
    }

    /** Fixture variants of a simulated (review) tenant are left out, as the seed does. */
    private static final String REAL_VARIANT_SQL =
        " AND NOT (EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = v.tenant_id)" +
        "          AND v.external_id NOT LIKE 'gid://shopify/%')";

    private List<UUID> variantsForItems(UUID tenantId, Collection<String> itemGids) {
        if (itemGids.isEmpty()) return List.of();
        return tx.execute(s -> jdbc.queryForList(
            "SELECT v.id FROM variants v WHERE v.tenant_id = ? AND v.shopify_inventory_item_id = ANY(CAST(? AS text[]))" +
            REAL_VARIANT_SQL, UUID.class, tenantId, textArray(itemGids)));
    }

    private List<UUID> tracedVariants(UUID tenantId, Main main) {
        return tx.execute(s -> named.queryForList(MainReconcileRules.TRACED_VARIANTS_SQL,
            new MapSqlParameterSource("t", tenantId).addValue("main", main.id()), UUID.class));
    }

    /** variant → item gid: stored, else read through nodes(ids:) and written back (InventoryItemIdService). */
    private Map<UUID, String> resolveItems(UUID tenantId, Collection<UUID> variantIds, StoreRepository.Store store, String token) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (variantIds.isEmpty()) return out;
        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT v.id, v.external_id, v.shopify_inventory_item_id FROM variants v " +
            "WHERE v.tenant_id = ? AND v.id = ANY(CAST(? AS uuid[]))" + REAL_VARIANT_SQL,
            tenantId, uuidArray(variantIds)));
        List<InventoryItemIdService.VariantRef> refs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            refs.add(new InventoryItemIdService.VariantRef((UUID) r.get("id"), (String) r.get("external_id"),
                (String) r.get("shopify_inventory_item_id")));
            out.put((UUID) r.get("id"), (String) r.get("shopify_inventory_item_id"));
        }
        boolean missing = refs.stream().anyMatch(r -> r.inventoryItemGid() == null || r.inventoryItemGid().isBlank());
        if (missing) {
            Map<UUID, String> resolved = itemIds.resolveAll(tenantId, refs, store.shopDomain(), token);
            resolved.forEach(out::put);
        }
        return out;
    }

    // ── the compute transaction ─────────────────────────────────────────────────────────────────────────────────

    private record State(Integer lastDelta, Timestamp deltaSince, Timestamp lastEventAt) {}

    private void compute(UUID tenantId, UUID runId, String kind, Main main, Map<UUID, String> items,
                         Map<String, Level> levels, Set<String> readItems, Timestamp readAt, Timestamp startedAt) {
        tx.executeWithoutResult(st -> {
            // The seed's lock (ShopifyInventoryReconcileService.acquireTenantLock): one Main writer/reader at a time.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", rs -> null, tenantId.toString());

            List<UUID> ids = new ArrayList<>(items.keySet());
            ObjectNode part1 = part1(tenantId, runId, main, "sweep".equals(kind) ? null : ids);

            Map<UUID, State> states = new HashMap<>();
            for (List<UUID> chunk : chunks(ids)) {
                jdbc.query("SELECT variant_id, last_delta, delta_since, last_event_at FROM main_reconcile_variant_state " +
                           "WHERE tenant_id = ? AND variant_id = ANY(CAST(? AS uuid[]))",
                    rs -> { states.put(rs.getObject(1, UUID.class), new State((Integer) rs.getObject(2),
                                rs.getTimestamp(3), rs.getTimestamp(4))); },
                    tenantId, uuidArray(chunk));
            }
            clearQueue(tenantId, "sweep".equals(kind) ? null : ids, startedAt);

            int checked = 0, inSync = 0, skipped = 0, differences = 0, confirmed = 0, up = 0, down = 0, damagedMismatch = 0;
            Map<String, Integer> byReason = new TreeMap<>();
            long confirmMillis = confirmAfterMinutes * 60_000L;

            for (List<UUID> chunk : chunks(ids)) {
                List<Map<String, Object>> rows = named.queryForList(MainReconcileRules.EVALUATE_SQL,
                    new MapSqlParameterSource("t", tenantId).addValue("main", main.id()).addValue("mainGid", main.gid())
                        .addValue("mainNum", main.numeric()).addValue("ids", uuidArray(chunk))
                        .addValue("cancelWindowMinutes", cancelledFulfillmentWindowMinutes));
                for (Map<String, Object> r : rows) {
                    checked++;
                    UUID variantId = (UUID) r.get("variant_id");
                    String item = items.get(variantId);
                    int custody   = num(r, "custody_at_main");
                    int away      = num(r, "away_counted");
                    int fulfilled = num(r, "fulfilled_from_main");
                    int owed      = num(r, "part1_owed");
                    int tDamaged  = num(r, "traced_damaged");
                    int target    = custody + away - fulfilled + owed;
                    Level lv = item == null ? null : levels.getOrDefault(item, Level.UNSTOCKED);

                    String skip = item == null ? MainReconcileRules.NOT_LINKED
                        : !readItems.contains(item) ? MainReconcileRules.SHOPIFY_UNREAD
                        : flag(r, "part1_in_flight") ? MainReconcileRules.PART1_IN_FLIGHT
                        : flag(r, "arrival_revisit_pending") ? MainReconcileRules.ARRIVAL_REVISIT_PENDING
                        : flag(r, "claim_in_flight") ? MainReconcileRules.CLAIM_IN_FLIGHT
                        : flag(r, "claim_ambiguous") ? MainReconcileRules.CLAIM_AMBIGUOUS
                        : flag(r, "stock_take_sync_in_flight") ? MainReconcileRules.STOCK_TAKE_SYNC_IN_FLIGHT
                        : flag(r, "transfer_sync_in_flight") ? MainReconcileRules.TRANSFER_SYNC_IN_FLIGHT
                        : flag(r, "fulfillment_unreadable") ? MainReconcileRules.FULFILLMENT_UNREADABLE
                        : flag(r, "fulfillment_pending") ? MainReconcileRules.FULFILLMENT_PENDING
                        : flag(r, "fulfillment_cancelled") ? MainReconcileRules.FULFILLMENT_CANCELLED
                        : (!lv.stocked() && target > 0) ? MainReconcileRules.NOT_ACTIVATED_AT_MAIN
                        : null;

                    Integer onHand = lv == null || MainReconcileRules.SHOPIFY_UNREAD.equals(skip) ? null : lv.onHand();
                    Integer delta = onHand == null ? null : target - onHand;
                    State prev = states.get(variantId);
                    String status;
                    Timestamp deltaSince = null;
                    Timestamp recheckAt = null;

                    if (skip != null) {
                        skipped++;
                        byReason.merge(skip, 1, Integer::sum);
                        status = "skipped";   // delta still recorded for the report; never confirmed
                    } else if (delta == 0) {
                        inSync++;
                        status = (lv.damaged() != tDamaged) ? "damaged_only" : null;
                        if (status != null) damagedMismatch++;
                    } else {
                        differences++;
                        if (lv.damaged() != tDamaged) damagedMismatch++;
                        boolean sameChain = prev != null && prev.lastDelta() != null && prev.lastDelta().equals(delta)
                            && prev.deltaSince() != null
                            && (prev.lastEventAt() == null || prev.lastEventAt().before(prev.deltaSince()));
                        deltaSince = sameChain ? prev.deltaSince() : readAt;
                        if (sameChain && readAt.getTime() - prev.deltaSince().getTime() >= confirmMillis) {
                            status = "confirmed";
                            confirmed++;
                            if (delta > 0) up += delta; else down += -delta;
                        } else {
                            status = "unconfirmed";
                            recheckAt = new Timestamp(deltaSince.getTime() + confirmMillis - debounceSeconds * 1000L + 1000L);
                        }
                    }

                    if (status != null) {
                        jdbc.update("INSERT INTO main_reconcile_diffs (tenant_id, run_id, variant_id, status, skip_reason, " +
                            "custody_at_main, away_counted, fulfilled_from_main, part1_owed, target, shopify_on_hand, " +
                            "shopify_available, shopify_committed, shopify_damaged, traced_damaged, delta, delta_since) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            tenantId, runId, variantId, status, skip, custody, away, fulfilled, owed, target, onHand,
                            lv == null || onHand == null ? null : lv.available(), lv == null || onHand == null ? null : lv.committed(),
                            lv == null || onHand == null ? null : lv.damaged(), tDamaged, delta, deltaSince);
                    }
                    Integer keepDelta = skip != null ? null : delta;
                    jdbc.update("INSERT INTO main_reconcile_variant_state AS s (tenant_id, variant_id, last_checked_at, " +
                        "  last_run_id, last_delta, delta_since, pending_since, pending_touched_at, pending_source) " +
                        "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE now() END, ?, " +
                        "        CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'recheck' END) " +
                        "ON CONFLICT (tenant_id, variant_id) DO UPDATE SET last_checked_at = EXCLUDED.last_checked_at, " +
                        "  last_run_id = EXCLUDED.last_run_id, last_delta = EXCLUDED.last_delta, delta_since = EXCLUDED.delta_since, " +
                        "  pending_since = CASE WHEN EXCLUDED.pending_touched_at IS NULL THEN s.pending_since " +
                        "                       ELSE COALESCE(s.pending_since, now()) END, " +
                        "  pending_touched_at = CASE WHEN EXCLUDED.pending_touched_at IS NULL THEN s.pending_touched_at " +
                        "                       ELSE LEAST(COALESCE(s.pending_touched_at, EXCLUDED.pending_touched_at), EXCLUDED.pending_touched_at) END, " +
                        "  pending_source = CASE WHEN EXCLUDED.pending_touched_at IS NULL THEN s.pending_source ELSE 'recheck' END",
                        tenantId, variantId, readAt, runId, keepDelta, deltaSince, recheckAt, recheckAt, recheckAt);
                }
            }

            ObjectNode reasons = mapper.createObjectNode();
            byReason.forEach(reasons::put);
            jdbc.update("UPDATE main_reconcile_runs SET status = 'completed', shopify_read_at = ?, finished_at = now(), " +
                "variants_checked = ?, variants_in_sync = ?, variants_skipped = ?, differences = ?, confirmed = ?, " +
                "confirmed_units_up = ?, confirmed_units_down = ?, damaged_mismatches = ?, skipped_by_reason = ?::jsonb, " +
                "part1_summary = ?::jsonb WHERE id = ? AND tenant_id = ?",
                readAt, checked, inSync, skipped, differences, confirmed, up, down, damagedMismatch,
                reasons.toString(), part1.toString(), runId, tenantId);
        });
    }

    // ── Part 1 (report-only claims) ─────────────────────────────────────────────────────────────────────────────

    private record P1Line(UUID orderId, boolean orderCancelled, UUID variantId, String fulfillmentId, String lineItemId,
                          String status, String location, int qty) {}

    private ObjectNode part1(UUID tenantId, UUID runId, Main main, List<UUID> variantIds) {
        Map<String, Integer> outcomes = new TreeMap<>();
        int units = 0;
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId).addValue("mainNum", main.numeric())
            .addValue("days", part1WindowDays).addValue("all", variantIds == null)
            .addValue("ids", uuidArray(variantIds == null ? List.of() : variantIds));
        List<P1Line> lines = named.query(MainReconcileRules.PART1_LINES_SQL, p, (rs, i) -> new P1Line(
            rs.getObject("order_id", UUID.class), rs.getBoolean("order_cancelled"), rs.getObject("variant_id", UUID.class),
            rs.getString("fulfillment_id"), rs.getString("line_item_id"), rs.getString("fulfillment_status"),
            rs.getString("fulfilled_location_id"), rs.getInt("qty")));

        Map<List<UUID>, List<P1Line>> groups = new LinkedHashMap<>();
        for (P1Line l : lines) {
            if (l.variantId() == null) { outcomes.merge(MainReconcileRules.P1_UNLINKED_VARIANT, 1, Integer::sum); continue; }
            groups.computeIfAbsent(List.of(l.orderId(), l.variantId()), k -> new ArrayList<>()).add(l);
        }
        if (!groups.isEmpty()) {
            List<UUID> orderIds = new ArrayList<>(), vIds = new ArrayList<>();
            groups.keySet().forEach(k -> { orderIds.add(k.get(0)); vIds.add(k.get(1)); });
            Map<List<UUID>, Map<String, Object>> agg = new HashMap<>();
            for (Map<String, Object> g : named.queryForList(MainReconcileRules.PART1_GROUPS_SQL,
                    new MapSqlParameterSource("t", tenantId).addValue("mainNum", main.numeric())
                        .addValue("orderIds", uuidArray(orderIds)).addValue("variantIds", uuidArray(vIds)))) {
                agg.put(List.of((UUID) g.get("order_id"), (UUID) g.get("variant_id")), g);
            }
            Set<String> claimedKeys = new HashSet<>(jdbc.queryForList(
                "SELECT fulfillment_id || '|' || line_item_id FROM main_reconcile_part1_claims " +
                "WHERE tenant_id = ? AND order_id = ANY(CAST(? AS uuid[]))", String.class, tenantId, uuidArray(orderIds)));

            for (Map.Entry<List<UUID>, List<P1Line>> e : groups.entrySet()) {
                Map<String, Object> g = agg.get(e.getKey());
                if (g == null) continue;
                int fMain = num(g, "f_main"), left = num(g, "left_n"), inCustody = num(g, "in_custody"), claimed = num(g, "claimed");
                int availOther = left - Math.min(left, fMain) - claimed;
                for (P1Line l : e.getValue()) {
                    String outcome;
                    if (claimedKeys.contains(l.fulfillmentId() + "|" + l.lineItemId())) {
                        outcome = MainReconcileRules.P1_ALREADY_CLAIMED;
                    } else if (Set.of("cancelled", "error", "failure").contains(l.status())) {
                        outcome = MainReconcileRules.P1_FULFILLMENT_CANCELLED;
                    } else if (!"success".equals(l.status())) {
                        outcome = MainReconcileRules.P1_FULFILLMENT_PENDING;
                    } else if (availOther >= l.qty()) {
                        if (claim(tenantId, runId, main, l, l.qty())) units += l.qty();
                        availOther -= l.qty();
                        outcome = MainReconcileRules.P1_WOULD_SUBTRACT;
                    } else if (inCustody > 0) {
                        outcome = MainReconcileRules.P1_WAITING;
                    } else if (availOther > 0) {
                        if (claim(tenantId, runId, main, l, availOther)) units += availOther;
                        availOther = 0;
                        outcome = MainReconcileRules.P1_WOULD_SUBTRACT;
                    } else if (l.orderCancelled()) {
                        outcome = MainReconcileRules.P1_CANCELLED_BEFORE_SHIPPING;
                    } else {
                        outcome = MainReconcileRules.P1_NOT_SHIPPED_BY_TRACED;
                    }
                    outcomes.merge(outcome, 1, Integer::sum);
                }
            }
        }
        Integer unreadable = named.queryForObject(MainReconcileRules.PART1_UNREADABLE_SQL,
            new MapSqlParameterSource("t", tenantId).addValue("days", part1WindowDays), Integer.class);
        ObjectNode out = mapper.createObjectNode();
        outcomes.forEach(out::put);
        out.put("new_claim_units", units);
        out.put("unreadable_orders", unreadable == null ? 0 : unreadable);
        return out;
    }

    /** The claim (exactly once per fulfillment + line item — the UNIQUE key). R1: 'report_only', never sent. */
    private boolean claim(UUID tenantId, UUID runId, Main main, P1Line l, int qty) {
        return jdbc.update(
            "INSERT INTO main_reconcile_part1_claims (tenant_id, order_id, variant_id, location_id, fulfillment_id, " +
            "  line_item_id, fulfilled_location_id, fulfilled_quantity, quantity, status, reason, run_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'report_only', 'shipped_by_traced_fulfilled_elsewhere', ?) " +
            "ON CONFLICT (tenant_id, fulfillment_id, line_item_id) DO NOTHING",
            tenantId, l.orderId(), l.variantId(), main.id(), l.fulfillmentId(), l.lineItemId(), l.location(),
            l.qty(), qty, runId) > 0;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static int num(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static boolean flag(Map<String, Object> r, String k) { return Boolean.TRUE.equals(r.get(k)); }

    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += EVAL_CHUNK) out.add(ids.subList(i, Math.min(ids.size(), i + EVAL_CHUNK)));
        return out;
    }

    static String uuidArray(Collection<UUID> ids) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        for (UUID id : ids) j.add(id.toString());
        return j.toString();
    }

    static String textArray(Collection<String> values) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        for (String v : values) j.add("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        return j.toString();
    }
}
