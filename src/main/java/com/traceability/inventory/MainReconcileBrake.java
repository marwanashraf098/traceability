package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;

/**
 * Main reconcile — the big-change brake (V226, 2026-11-08). Protects a store whose Shopify Main stock is far from
 * Traced's (a new signup like Juno / mashwar) from being "fixed" in one go: when its confirmed differences would move
 * more than max(units, percent of Shopify's on_hand at Main) in total (sum of |delta|), the run writes NO reconcile
 * write. Part 1 subtracts are never braked (one per order line, already safe). Small differences are written as before.
 *
 * What is weighed: the run's own confirmed differences, plus — on a per-variant run — the store's OTHER standing
 * confirmed differences (variant state from the latest write sweep onward, delta confirmed, no event since, nothing
 * written since its check). Without that, a held store would trickle its big change out through small per-variant
 * runs, each one under the limit.
 *
 * Denominator: a sweep's own total on_hand at Main (every level it read); a per-variant run uses the latest write
 * sweep's total, else the levels it read itself (smaller → the units floor decides).
 *
 * Keep-bazar exemption (U1a, approved 2026-11-22): a busy Keep-mode bazar ('leave' — Shopify keeps showing the pieces
 * while they're away) easily confirms 20+ units in one window, all of them really gone. The part of a variant's
 * difference that its Keep-bazar piece changes explain is not weighed: per variant, the net of its pieces' moves
 * out_on_transfer → sold / damaged / lost (−1) and back (+1), made while the piece was away on a Keep bazar's transfer,
 * since the variant's last APPLIED reconcile write (that write already put Shopify in line with everything before it),
 * up to the run's read time — counted only in the delta's direction and never more than |delta| ({@link #explained}).
 * Remove-mode bazars (their send already left Shopify) and every non-bazar difference are weighed exactly as before.
 *
 * Held run: status 'skipped', skip_reason 'large_change_held', counts and diffs kept as computed. Alert: once per
 * store — again only after a run evaluated under the limit, or realert-hours after the last alert. Evaluated under the
 * seed's per-tenant advisory lock, so two concurrent runs of one store can't both alert. Not a bean: built by
 * MainReconcileService on its own JdbcTemplate (app_user + RLS, inside TenantContext.runAs). No Shopify call.
 */
final class MainReconcileBrake {

    static final String LARGE_CHANGE_HELD = "large_change_held";
    static final int TOP_VARIANTS = 10;

    /** One confirmed difference the brake weighed (shopifyOnHand / target null when only the stored delta is known). */
    record Item(UUID variantId, int delta, Integer shopifyOnHand, Integer target) {}

    /** A variant line for the alert. */
    record Line(String sku, String title, int delta, Integer shopifyOnHand, Integer target) {}

    record Decision(boolean held, boolean alert, int unitsUp, int unitsDown, int onHand, int limit, List<Line> top) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int units;
    private final int percent;
    private final int realertHours;
    private final int confirmAfterMinutes;

    MainReconcileBrake(JdbcTemplate jdbc, TransactionTemplate tx, int units, int percent, int realertHours,
                       int confirmAfterMinutes) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.units = units;
        this.percent = percent;
        this.realertHours = realertHours;
        this.confirmAfterMinutes = confirmAfterMinutes;
    }

    /**
     * U1a: per variant, the net Keep-bazar change of the Main target since the variant's last applied reconcile write
     * (negative = pieces sold / damaged / lost at a Keep bazar). A move counts only while the piece was on a Keep bazar's
     * transfer (sent → closed) — the only moves that change the target with no Shopify write behind them.
     */
    static final String KEEP_BAZAR_NET_SQL =
        "WITH v AS (SELECT unnest(CAST(? AS uuid[])) AS variant_id), " +
        "s AS (SELECT v.variant_id, (SELECT max(w.created_at) FROM main_reconcile_writes w " +
        "        WHERE w.tenant_id = ? AND w.variant_id = v.variant_id AND w.status = 'applied') AS since FROM v) " +
        "SELECT p.variant_id, SUM(CASE " +
        "    WHEN e.from_status::text = 'out_on_transfer' AND e.to_status::text IN ('sold', 'damaged', 'lost') THEN -1 " +
        "    WHEN e.from_status::text IN ('sold', 'damaged', 'lost') AND e.to_status::text = 'out_on_transfer' THEN 1 " +
        "    ELSE 0 END) AS net " +
        "FROM s JOIN pieces p ON p.tenant_id = ? AND p.variant_id = s.variant_id " +
        "JOIN piece_events e ON e.piece_id = p.id AND e.tenant_id = p.tenant_id " +
        "WHERE (s.since IS NULL OR e.occurred_at > s.since) AND e.occurred_at <= ?::timestamptz " +
        "  AND ((e.from_status::text = 'out_on_transfer' AND e.to_status::text IN ('sold', 'damaged', 'lost')) " +
        "    OR (e.from_status::text IN ('sold', 'damaged', 'lost') AND e.to_status::text = 'out_on_transfer')) " +
        "  AND EXISTS (SELECT 1 FROM transfer_pieces tp " +
        "      JOIN transfers t ON t.id = tp.transfer_id AND t.tenant_id = tp.tenant_id " +
        "      JOIN bazar_events be ON be.transfer_id = t.id AND be.tenant_id = t.tenant_id " +
        "      WHERE tp.piece_id = p.id AND tp.tenant_id = p.tenant_id AND t.shopify_sync_mode = 'leave' " +
        "        AND e.occurred_at >= COALESCE(t.sent_at, t.created_at) " +
        "        AND (t.closed_at IS NULL OR e.occurred_at <= t.closed_at)) " +
        "GROUP BY p.variant_id";

    /** The part of {@code delta} that {@code keepNet} explains: same direction only, never more than |delta|. */
    static int explained(int delta, int keepNet) {
        if (delta == 0 || keepNet == 0 || Integer.signum(delta) != Integer.signum(keepNet)) return 0;
        return Math.min(Math.abs(delta), Math.abs(keepNet));
    }

    /** The limit: max(units, floor(percent% of on_hand)). Over it = held. */
    static int limit(int units, int percent, int onHand) {
        return Math.max(units, (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, onHand) * percent / 100));
    }

    /** Held iff the total is over BOTH the units floor and the percentage (exact integer comparison). */
    static boolean over(int units, int percent, int onHand, long total) {
        return total > units && total * 100 > (long) percent * Math.max(0, onHand);
    }

    /**
     * Evaluates the brake for one write run (after its compute transaction committed) and records it on the run row.
     * @param ownOnHand sum of on_hand at Main over the levels this run read
     */
    Decision evaluate(UUID tenantId, UUID runId, boolean sweep, List<MainReconcileWriter.Candidate> candidates,
                      int ownOnHand, Timestamp readAt) {
        return tx.execute(st -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", rs -> null, tenantId.toString());

            List<Item> items = new ArrayList<>();
            for (MainReconcileWriter.Candidate c : candidates) {
                items.add(new Item(c.variantId(), c.delta(), c.shopifyOnHand(), c.target()));
            }
            int onHand;
            if (sweep) {
                onHand = ownOnHand;
                jdbc.update("UPDATE main_reconcile_runs SET shopify_on_hand_total = ? WHERE id = ? AND tenant_id = ?",
                    ownOnHand, runId, tenantId);
            } else {
                Map<String, Object> lastSweep = jdbc.query(
                    "SELECT started_at, shopify_on_hand_total FROM main_reconcile_runs " +
                    "WHERE tenant_id = ? AND kind = 'sweep' AND mode = 'write' AND shopify_on_hand_total IS NOT NULL " +
                    "ORDER BY started_at DESC, id DESC LIMIT 1",
                    rs -> rs.next() ? Map.of("started_at", rs.getTimestamp(1), "total", rs.getInt(2)) : null, tenantId);
                onHand = lastSweep == null ? ownOnHand : (Integer) lastSweep.get("total");
                if (lastSweep != null) {
                    // The store's other standing confirmed differences (not checked by this run).
                    jdbc.query(
                        "SELECT s.variant_id, s.last_delta FROM main_reconcile_variant_state s " +
                        "WHERE s.tenant_id = ? AND s.last_run_id IS DISTINCT FROM ? " +
                        "  AND s.last_delta IS NOT NULL AND s.last_delta <> 0 " +
                        "  AND s.last_checked_at >= ? " +
                        "  AND s.delta_since IS NOT NULL AND s.delta_since <= ?::timestamptz - make_interval(mins => ?) " +
                        "  AND (s.last_event_at IS NULL OR s.last_event_at < s.delta_since) " +
                        "  AND NOT EXISTS (SELECT 1 FROM main_reconcile_writes w WHERE w.tenant_id = s.tenant_id " +
                        "                  AND w.variant_id = s.variant_id AND w.created_at >= s.last_checked_at)",
                        rs -> { items.add(new Item(rs.getObject(1, UUID.class), rs.getInt(2), null, null)); },
                        tenantId, runId, lastSweep.get("started_at"), readAt, confirmAfterMinutes);
                }
            }

            // U1a: what Keep-bazar piece changes explain is not weighed.
            Map<UUID, Integer> keepNet = new HashMap<>();
            if (!items.isEmpty()) {
                jdbc.query(KEEP_BAZAR_NET_SQL,
                    rs -> { keepNet.put(rs.getObject(1, UUID.class), rs.getInt(2)); },
                    MainReconcileService.uuidArray(items.stream().map(Item::variantId).distinct().toList()),
                    tenantId, tenantId, readAt);
            }
            int up = 0, down = 0, exempt = 0;
            for (Item i : items) {
                int ex = explained(i.delta(), keepNet.getOrDefault(i.variantId(), 0));
                exempt += ex;
                int weighed = Math.abs(i.delta()) - ex;
                if (i.delta() > 0) up += weighed; else down += weighed;
            }
            if (exempt > 0) {
                org.slf4j.LoggerFactory.getLogger(MainReconcileBrake.class).info(
                    "Main reconcile brake run {} tenant={}: {} unit(s) explained by Keep-mode bazar changes, not weighed",
                    runId, tenantId, exempt);
            }
            long total = (long) up + down;
            int lim = limit(units, percent, onHand);
            boolean held = over(units, percent, onHand, total);

            boolean alert = false;
            if (held) {
                Map<String, Object> last = jdbc.query(
                    "SELECT brake_checked_at, brake_checked_at < now() - make_interval(hours => ?) AS stale " +
                    "FROM main_reconcile_runs WHERE tenant_id = ? AND brake_alerted " +
                    "ORDER BY brake_checked_at DESC, id DESC LIMIT 1",
                    rs -> rs.next() ? Map.of("at", rs.getTimestamp(1), "stale", rs.getBoolean(2)) : null,
                    realertHours, tenantId);
                if (last == null || Boolean.TRUE.equals(last.get("stale"))) {
                    alert = true;
                } else {
                    alert = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM main_reconcile_runs WHERE tenant_id = ? AND brake_checked_at > ? " +
                        "  AND skip_reason IS DISTINCT FROM ?)",   // a run since the alert that was under the limit
                        Boolean.class, tenantId, last.get("at"), LARGE_CHANGE_HELD));
                }
            }

            jdbc.update("UPDATE main_reconcile_runs SET brake_units_up = ?, brake_units_down = ?, brake_on_hand = ?, " +
                "brake_limit = ?, brake_checked_at = clock_timestamp(), brake_alerted = ? " +
                (held ? ", status = 'skipped', skip_reason = '" + LARGE_CHANGE_HELD + "' " : "") +
                "WHERE id = ? AND tenant_id = ?", up, down, onHand, lim, alert, runId, tenantId);

            return new Decision(held, alert, up, down, onHand, lim, alert ? top(tenantId, items) : List.of());
        });
    }

    /** The biggest TOP_VARIANTS differences by |delta|, with SKU and title (catalog data only — no customer data). */
    private List<Line> top(UUID tenantId, List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt((Item i) -> -Math.abs(i.delta())).thenComparing(i -> i.variantId().toString()));
        List<Item> head = sorted.subList(0, Math.min(TOP_VARIANTS, sorted.size()));
        if (head.isEmpty()) return List.of();
        Map<UUID, String[]> names = new HashMap<>();
        jdbc.query("SELECT v.id, v.sku, p.title, v.title FROM variants v LEFT JOIN products p ON p.id = v.product_id " +
                   "WHERE v.tenant_id = ? AND v.id = ANY(CAST(? AS uuid[]))",
            rs -> { names.put(rs.getObject(1, UUID.class), new String[]{rs.getString(2), rs.getString(3), rs.getString(4)}); },
            tenantId, MainReconcileService.uuidArray(head.stream().map(Item::variantId).toList()));
        List<Line> out = new ArrayList<>();
        for (Item i : head) {
            String[] n = names.getOrDefault(i.variantId(), new String[]{null, null, null});
            String title = n[1] == null ? "" : n[1];
            if (n[2] != null && !n[2].isBlank() && !"Default Title".equals(n[2])) title = title.isEmpty() ? n[2] : title + " — " + n[2];
            out.add(new Line(n[0], title.isEmpty() ? i.variantId().toString() : title, i.delta(), i.shopifyOnHand(), i.target()));
        }
        return out;
    }
}
