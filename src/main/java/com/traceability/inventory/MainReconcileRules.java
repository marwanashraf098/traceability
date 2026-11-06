package com.traceability.inventory;

import java.util.List;

/**
 * Shopify Main-location reconciler ("check-up", V201 — R0 + R1, report-only; approved 2026-11-02). THE definitions,
 * as SQL, for what Shopify's Traced Main Warehouse should hold for a variant, why a variant is not checked this run,
 * and Part 1 (fulfillments shipped from another Shopify location). Never re-derive these anywhere else.
 *
 * <h3>Target (per variant, at the tenant's main warehouse — compared with Shopify ON-HAND, never available)</h3>
 * <pre>
 *   target = MAX(0, custody_at_main + away_counted − fulfilled_from_main − fulfilled_unpicked + part1_owed
 *                   + unfulfilled_shipped)
 * </pre>
 * <ul>
 *   <li><b>custody_at_main</b>: pieces at the main warehouse that are available / reserved / packed / awaiting_pickup.
 *       Physical presence decides — Traced is the source of truth at Main, so a piece Shopify never counted (an arrival
 *       that never reached Shopify) still belongs in the target. on_hold (already left Shopify at hold-enter), damaged
 *       (compared separately, report-only), return_pending_inspection (the +1 comes at restock) and everything that
 *       left custody are not in it. Internal exchange / Lookup replacement orders never exist in Shopify: their reserved
 *       piece stays in the target (Shopify still holds it) until it leaves, when the exchange-dispatch −1 applies.</li>
 *   <li><b>away_counted</b>: a piece away from the main warehouse (out_on_transfer, or in custody at another location)
 *       still counts at Main while the departure that took it from Main did not leave Shopify's count —
 *       NOT {@link TransferShopifySync#RETURN_COUNTED_SQL} (the one rule for "did this departure leave Shopify"): 'leave'
 *       mode, a skipped / exhausted / pre-V157 send, or a piece the send left out. Same verdicts as
 *       {@link PieceShopifyRules#countedAtMain}. A piece out on a transfer still being prepared at Main counts (it has
 *       not left). No departure from Main at all (received elsewhere) → not counted. A departure whose send is still in
 *       flight → the variant is skipped ({@link #TRANSFER_SYNC_IN_FLIGHT}).</li>
 *   <li><b>fulfilled_from_main</b>: units Shopify already removed from Main for an order whose pieces Traced still holds
 *       (about 83% of Bosta orders are fulfilled in Shopify before Traced packs). Per (order, variant):
 *       {@code GREATEST(0, LEAST(held, F_main − R_main − left))} — held = custody pieces at Main allocated to the order;
 *       F_main = quantity of SUCCESSFUL REST fulfillment lines from the Main location (cancelled / errored / pending
 *       never count; a cancelled / errored one skips the variant ({@link #FULFILLMENT_CANCELLED}) only while it changed
 *       less than cancelled-fulfillment-window-minutes ago — Shopify may re-fulfill — or its time is unreadable); R_main = Shopify restocks at Main ({@code shopify_restocked_units}, V212: GREATEST of refund restocks and RESTOCKED return dispositions — so a cancel or a return +
 *       restock at Main while Traced still holds the pieces reconciles as in sync); left = the order's pieces of the
 *       variant that already left custody (with_courier / delivered) — those are already out of the target.</li>
 *   <li><b>fulfilled_unpicked</b> (V204, prod 2026-11-04): units Shopify fulfilled FROM MAIN on a live order Traced
 *       hasn't picked yet ({@link #AWAITING_PICK_SQL}: not packed AND (self-pickup OR no forward waybill yet — the
 *       Bosta link hasn't attached it — OR the newest forward waybill still pre-pickup)) — the Bosta app fulfills at shipment creation. Per (order,
 *       variant) {@code GREATEST(0, F_main − R_main − left − allocated in custody)}; fulfilled_from_main covers the
 *       allocated part, so a partly allocated line is never subtracted twice. The target is floored at 0 (diff note
 *       'target_floored').</li>
 *   <li><b>part1_owed</b>: Part 1 claims not yet applied — units Traced shipped for a line Shopify fulfilled from ANOTHER
 *       location, so Shopify Main still holds them until Part 1 subtracts. Adding them back keeps the runner from
 *       subtracting the same units a second time; once applied they drop out (and Shopify Main dropped with them).
 *       'ignored_pre_cutoff' / 'superseded_by_reconcile' claims are never owed.</li>
 *   <li><b>unfulfilled_shipped</b> (R2 fix 2): pieces that left Traced custody (now with_courier / delivered — their
 *       latest move out names the order) for a line of a non-cancelled order that Shopify still shows UNFULFILLED:
 *       Shopify still holds them on_hand as committed and subtracts on fulfillment. Per (order, variant)
 *       {@code LEAST(out, quantity − fulfilled)} ({@link #unfulfilledUnitsSql}); per variant capped at Shopify's
 *       committed at Main (Traced can't see which location an unfulfilled order is committed at) — a capped variant's
 *       diff carries note 'unfulfilled_capped'. An order whose fulfillments Traced can't read skips the variant
 *       ({@link #FULFILLMENT_UNREADABLE}) unless Shopify says it is FULFILLED.</li>
 * </ul>
 *
 * <h3>Write mode (R2, V203) — settling other paths' ambiguous claims</h3>
 * In a write store ({@code :settle}), a failed_ambiguous claim at Main older than {@code :holdMinutes} (another path's
 * shopify_inventory_adjustments row, a Part 1 claim, or an earlier reconcile write) no longer skips its variant: the
 * target is Traced's own count, so the reconcile write makes Shopify right whatever the ambiguous one did; the claim
 * is settled 'superseded_by_reconcile' in the transaction that records the applied write (so nothing re-sends it).
 * A pending reconcile write, or an ambiguous one still inside the hold, skips the variant
 * ({@link #RECONCILE_WRITE_IN_FLIGHT}).
 *
 * <h3>Part 1 (per successful REST fulfillment line at a location that is not Main)</h3>
 * Units Traced shipped for (order, variant) first cover what Shopify fulfilled from Main; the rest, minus earlier
 * claims, is available to this line (lines in fulfillment order). Enough for the line → claim its quantity (exactly
 * once per fulfillment id + line item id); not enough but pieces still in custody on the order → wait for them to leave
 * ({@link #P1_WAITING}); nothing more will leave → claim what did leave (never more than fulfilled), or
 * {@link #P1_CANCELLED_BEFORE_SHIPPING} / {@link #P1_NOT_SHIPPED_BY_TRACED}. A claim is never reversed: a piece that
 * comes back goes through the existing return flow (+1 at Main). R1 writes claims as 'report_only' only.
 */
public final class MainReconcileRules {

    private MainReconcileRules() {}

    // ── Store-level skips (a run row with status 'skipped') ──────────────────────────────────────────────────────
    public static final String STORE_NO_STORE          = "no_store";
    public static final String STORE_MAIN_NOT_LINKED   = "main_not_linked";
    public static final String STORE_NEEDS_REAUTH      = "needs_reauth";
    public static final String STORE_LOCATION_INACTIVE = "location_inactive";
    public static final String STORE_LOCATION_DELETED  = "location_deleted";

    // ── Variant skips, in the order they are decided (the first that applies is recorded) ───────────────────────────
    public static final String NOT_LINKED                = "not_linked";
    public static final String SHOPIFY_UNREAD            = "shopify_unread";
    public static final String PART1_IN_FLIGHT           = "part1_in_flight";
    public static final String ARRIVAL_REVISIT_PENDING   = "arrival_revisit_pending";
    public static final String CLAIM_IN_FLIGHT           = "claim_in_flight";
    public static final String CLAIM_AMBIGUOUS           = "claim_ambiguous";
    public static final String STOCK_TAKE_SYNC_IN_FLIGHT = "stock_take_sync_in_flight";
    public static final String TRANSFER_SYNC_IN_FLIGHT   = "transfer_sync_in_flight";
    public static final String FULFILLMENT_UNREADABLE    = "fulfillment_unreadable";
    public static final String FULFILLMENT_PENDING       = "fulfillment_pending";
    public static final String FULFILLMENT_CANCELLED     = "fulfillment_cancelled";
    public static final String NOT_ACTIVATED_AT_MAIN     = "not_activated_at_main";
    public static final String RECONCILE_WRITE_IN_FLIGHT = "reconcile_write_in_flight";
    /**
     * S3a interim guard (approved 2026-11-06, until S3c adds shadow pieces to the target): a WRITE store with an open
     * Shopify-native exchange shadow order, or a Shopify-native exchange's internal:exchange: replacement still in
     * custody, writes nothing — every variant with a difference is recorded skipped with this reason, Part 1 claims
     * stay queued (sent once the hold clears) and no ambiguous claim is settled. See {@link #EXCHANGE_HOLD_SQL}.
     */
    public static final String SHOPIFY_EXCHANGE_IN_CUSTODY = "shopify_exchange_in_custody";

    /** Diff note: unfulfilled_shipped was capped at Shopify's committed at Main. */
    public static final String NOTE_UNFULFILLED_CAPPED = "unfulfilled_capped";
    /** Diff note: the parts summed below 0 and the target was floored at 0. */
    public static final String NOTE_TARGET_FLOORED = "target_floored";

    public static final List<String> SKIP_ORDER = List.of(NOT_LINKED, SHOPIFY_UNREAD, RECONCILE_WRITE_IN_FLIGHT, PART1_IN_FLIGHT,
        ARRIVAL_REVISIT_PENDING, CLAIM_IN_FLIGHT, CLAIM_AMBIGUOUS, STOCK_TAKE_SYNC_IN_FLIGHT, TRANSFER_SYNC_IN_FLIGHT,
        FULFILLMENT_UNREADABLE, FULFILLMENT_PENDING, FULFILLMENT_CANCELLED, NOT_ACTIVATED_AT_MAIN, SHOPIFY_EXCHANGE_IN_CUSTODY);

    /**
     * S3a interim guard — true when the tenant has (a) an open Shopify-native exchange shadow order
     * ('internal:shopify-exchange:', any status but delivered / cancelled / returned / lost), or (b) a Shopify-native
     * exchange ({@link ShopifyNativeExchange} rule) whose internal:exchange: replacement has a piece in Traced custody
     * (active / packed allocation, piece reserved / packed / awaiting_pickup). Why: the target counts such a piece in
     * custody but never counts it once it leaves (its order is internal:% — liveShopifyOrderSql), while Shopify counts
     * the unit on the ORIGINAL order's exchange line until it fulfills that line — so a reconcile write in that window
     * is either a spurious +1 / −1 pair (Shopify fulfilled first) or a real double −1 (Traced shipped first).
     * Parameters: tenant, tenant.
     */
    public static final String EXCHANGE_HOLD_SQL =
        "SELECT EXISTS (SELECT 1 FROM warehouse_orders so WHERE so.tenant_id = ? " +
        "               AND so.external_id LIKE 'internal:shopify-exchange:%' " +
        "               AND so.status::text NOT IN ('delivered', 'cancelled', 'returned', 'lost')) " +
        "    OR EXISTS (SELECT 1 FROM exchanges e " +
        "               JOIN merchant_orders xo ON xo.id = e.outbound_order_id AND xo.tenant_id = e.tenant_id " +
        "               JOIN order_items xi ON xi.order_id = xo.id AND xi.tenant_id = xo.tenant_id " +
        "               JOIN allocations xa ON xa.order_item_id = xi.id AND xa.tenant_id = xi.tenant_id " +
        "                    AND xa.status IN ('active', 'packed') " +
        "               JOIN pieces xp ON xp.id = xa.piece_id AND xp.tenant_id = xa.tenant_id " +
        "                    AND xp.status::text IN ('reserved', 'packed', 'awaiting_pickup') " +
        "               WHERE e.tenant_id = ? AND xo.external_id LIKE 'internal:exchange:%' " +
        "                 AND " + ShopifyNativeExchange.nativeExchangeSql("xp.variant_id") + ")";

    // ── Part 1 outcomes ───────────────────────────────────────────────────────────────────────────────────────────
    public static final String P1_WOULD_SUBTRACT            = "would_subtract";
    public static final String P1_ALREADY_CLAIMED           = "already_claimed";
    public static final String P1_WAITING                   = "waiting_for_traced";
    public static final String P1_NOT_SHIPPED_BY_TRACED     = "not_shipped_by_traced";
    public static final String P1_CANCELLED_BEFORE_SHIPPING = "cancelled_before_shipping";
    public static final String P1_FULFILLMENT_CANCELLED     = "fulfillment_cancelled";
    public static final String P1_FULFILLMENT_PENDING       = "fulfillment_pending";
    public static final String P1_UNLINKED_VARIANT          = "unlinked_variant";
    public static final String P1_FULFILLMENT_UNREADABLE    = "fulfillment_unreadable";
    /** R2 fix 1: a fulfillment created before TRACED_MAIN_RECONCILE_PART1_SINCE (or with no readable time) — settled. */
    public static final String P1_PRE_CUTOFF                = "pre_cutoff";

    /** Part 1 claim statuses that still owe their units to Shopify Main (target adds them back). */
    public static final String P1_OWED_STATUSES_SQL = "('report_only', 'queued', 'pending', 'failed', 'failed_ambiguous')";
    /** Part 1 claim statuses that are being sent (R2) — the runner leaves the variant alone. */
    public static final String P1_IN_FLIGHT_STATUSES_SQL = "('queued', 'pending', 'failed', 'failed_ambiguous')";

    static final String CUSTODY_SQL = "('available', 'reserved', 'packed', 'awaiting_pickup')";

    /** The order's stored fulfillments as an array (anything else → empty). */
    static String fulfillmentsSql(String orderAlias) {
        return "(CASE WHEN jsonb_typeof(" + orderAlias + ".raw->'fulfillments') = 'array' THEN " + orderAlias +
               ".raw->'fulfillments' ELSE '[]'::jsonb END)";
    }

    /** A fulfillment Traced can read line by line and location by location: the REST (webhook) shape. */
    static String restShapeSql(String f) {
        return "(" + f + "->>'location_id' IS NOT NULL AND jsonb_typeof(" + f + "->'line_items') = 'array')";
    }

    /** The fulfillment's line items as an array (anything else → empty — never an error). */
    static String lineItemsSql(String f) {
        return "(CASE WHEN jsonb_typeof(" + f + "->'line_items') = 'array' THEN " + f + "->'line_items' ELSE '[]'::jsonb END)";
    }

    /**
     * When the fulfillment last changed (REST updated_at — the cancel time for a cancelled one — else created_at);
     * NULL when neither is a readable timestamp (never a cast error).
     */
    static String fulfillmentChangedAtSql(String f) {
        String ts = "'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}'";
        return "(CASE WHEN " + f + "->>'updated_at' ~ " + ts + " THEN (" + f + "->>'updated_at')::timestamptz " +
               "WHEN " + f + "->>'created_at' ~ " + ts + " THEN (" + f + "->>'created_at')::timestamptz END)";
    }

    /** Shopify's fulfillment created_at; NULL when unreadable (never a cast error). */
    static String fulfillmentCreatedAtSql(String f) {
        return "(CASE WHEN " + f + "->>'created_at' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}' " +
               "THEN (" + f + "->>'created_at')::timestamptz END)";
    }

    /**
     * R2 fix 2 — THE "units of this variant Shopify has not fulfilled on this order": Traced's order lines' quantity
     * (Shopify's current quantity) minus SUCCESSFUL REST fulfillment lines of the variant from ANY location; never < 0.
     * Shared by the target and the delivered_not_fulfilled_in_shopify alert. o = the order, vv = the variant.
     */
    public static String unfulfilledUnitsSql(String o, String vv) {
        return "GREATEST(0, " +
               "  COALESCE((SELECT SUM(oi.quantity) FROM order_items oi WHERE oi.tenant_id = " + o + ".tenant_id " +
               "            AND oi.order_id = " + o + ".id AND oi.variant_id = " + vv + ".id), 0) " +
               "  - COALESCE((SELECT SUM((li->>'quantity')::int) " +
               "              FROM jsonb_array_elements(" + fulfillmentsSql(o) + ") f, jsonb_array_elements(" + lineItemsSql("f") + ") li " +
               "              WHERE " + restShapeSql("f") + " AND lower(f->>'status') = 'success' " +
               "                AND " + lineMatchesVariantSql("li", vv) + " AND li->>'quantity' ~ '^[0-9]+$'), 0))";
    }

    /** The order has a fulfillment Traced can't read and Shopify doesn't say the order is fulfilled. */
    static String unfulfilledUnreadableSql(String o) {
        return "(EXISTS (SELECT 1 FROM jsonb_array_elements(" + fulfillmentsSql(o) + ") f WHERE NOT " + restShapeSql("f") + ") " +
               " AND COALESCE(upper(" + o + ".raw->>'displayFulfillmentStatus'), '') <> 'FULFILLED')";
    }

    /** Shopify says the whole order is fulfilled (REST fulfillment_status / GraphQL displayFulfillmentStatus). */
    static String orderFulfilledSql(String o) {
        return "(lower(COALESCE(" + o + ".raw->>'fulfillment_status', '')) = 'fulfilled' " +
               " OR upper(COALESCE(" + o + ".raw->>'displayFulfillmentStatus', '')) = 'FULFILLED')";
    }

    /**
     * The order is still waiting to be picked in Traced — the Bosta app fulfills in Shopify when it creates the shipment,
     * before the pick: not packed yet (PackListRules.NOT_YET_PACKED_SQL) and either self-pickup or its newest forward
     * waybill still waits for the courier (OrderLineFreeze.prePickupWaybillSql). Once that waybill moves without a Traced
     * pick (shipped outside Traced) the order no longer counts: Traced can't tell then whether its stock was ever in
     * Traced, and subtracting units Traced never held would read Shopify too high. Alias o.
     */
    static final String AWAITING_PICK_SQL =
        "(" + PackListRules.NOT_YET_PACKED_SQL + " AND (o.is_self_pickup " +
        " OR NOT EXISTS (SELECT 1 FROM shipments s_nw WHERE s_nw.order_id = o.id AND s_nw.tenant_id = o.tenant_id " +
        "                AND s_nw.shipment_leg = 'forward') " +
        " OR EXISTS (SELECT 1 FROM shipments s_ap WHERE s_ap.order_id = o.id AND s_ap.tenant_id = o.tenant_id " +
        "            AND " + OrderLineFreeze.prePickupWaybillSql("s_ap") + ")))";

    /** A non-cancelled, non-internal merchant order. */
    static String liveShopifyOrderSql(String o) {
        return "(" + o + ".external_id NOT LIKE 'internal:%' AND NOT COALESCE(" + o + ".is_cancelled, false) " +
               " AND " + o + ".status <> 'cancelled')";
    }

    /**
     * Each piece now out of Traced custody (with_courier / delivered) with the order its LATEST move out named
     * (a piece sold twice counts for the order it left on last). Columns piece_id, variant_id, order_id, left_at.
     * pieceFilter is extra SQL on p (e.g. a variant restriction).
     */
    static String piecesOutSql(String pieceFilter) {
        return "SELECT DISTINCT ON (p.id) p.id AS piece_id, p.variant_id, e.order_id, e.occurred_at AS left_at " +
               "FROM pieces p JOIN piece_events e ON e.piece_id = p.id AND e.tenant_id = p.tenant_id " +
               "WHERE p.tenant_id = :t AND p.status::text IN ('with_courier', 'delivered') " +
               "  AND e.to_status::text IN ('with_courier', 'delivered') AND e.order_id IS NOT NULL " + pieceFilter + " " +
               "ORDER BY p.id, e.occurred_at DESC, e.id DESC";
    }

    static String lineMatchesVariantSql(String li, String variantAlias) {
        return "('gid://shopify/ProductVariant/' || (" + li + "->>'variant_id') = " + variantAlias + ".external_id)";
    }

    /**
     * One row per variant id in :ids — the target's parts and every skip flag. Named parameters: :t (tenant), :main
     * (main warehouse id), :mainGid (its Shopify location gid), :mainNum (its numeric id), :ids (uuid[] as text),
     * :cancelWindowMinutes (a cancelled / errored fulfillment younger than this skips the variant), :settle (write store —
     * old ambiguous claims are settleable), :holdMinutes (how old an ambiguous claim must be to be settled).
     * Runs on the caller's connection, under RLS.
     */
    public static final String EVALUATE_SQL =
        "WITH v AS ( " +
        "  SELECT v.id AS variant_id, v.external_id, v.shopify_inventory_item_id AS item " +
        "  FROM variants v WHERE v.tenant_id = :t AND v.id = ANY(CAST(:ids AS uuid[])) " +
        "), custody AS ( " +
        "  SELECT p.variant_id, " +
        "         COUNT(*) FILTER (WHERE p.status::text IN " + CUSTODY_SQL + ") AS custody, " +
        "         COUNT(*) FILTER (WHERE p.status = 'damaged') AS damaged " +
        "  FROM pieces p JOIN v ON v.variant_id = p.variant_id " +
        "  WHERE p.tenant_id = :t AND p.current_location_id = :main " +
        "    AND p.status::text IN ('available', 'reserved', 'packed', 'awaiting_pickup', 'damaged') " +
        "  GROUP BY p.variant_id " +
        "), away_p AS ( " +
        "  SELECT p.variant_id, " +
        "         CASE WHEN outb.transfer_id IS NULL " +
        "              THEN (p.status = 'out_on_transfer' AND p.current_location_id = :main) " +
        "              ELSE NOT COALESCE(outb.return_counted, false) END AS counted, " +   // NULL (no seed) = not counted out

        "         COALESCE(outb.claim_status IN ('queued', 'pending', 'failed_ambiguous') " +
        "                  OR (outb.claim_status = 'failed' AND outb.claim_attempts < " + TransferShopifySync.MAX_ATTEMPTS + "), " +
        "                  false) AS in_flight " +
        "  FROM pieces p JOIN v ON v.variant_id = p.variant_id " +
        "  LEFT JOIN LATERAL ( " +
        "      SELECT tr.id FROM transfer_pieces tp JOIN transfers tr ON tr.id = tp.transfer_id AND tr.tenant_id = tp.tenant_id " +
        "      WHERE tp.piece_id = p.id AND tp.tenant_id = p.tenant_id " +
        "      ORDER BY tr.created_at DESC, tr.id DESC LIMIT 1) cur ON true " +
        "  LEFT JOIN LATERAL ( " +
        "      SELECT t_out.id AS transfer_id, y.status AS claim_status, COALESCE(y.attempt_count, 0) AS claim_attempts, " +
        "             " + TransferShopifySync.RETURN_COUNTED_SQL + " AS return_counted " +
        "      FROM transfer_pieces tp_out " +
        "      JOIN transfers t_out ON t_out.id = tp_out.transfer_id AND t_out.tenant_id = tp_out.tenant_id " +
        "      LEFT JOIN transfer_shopify_syncs y ON y.transfer_id = t_out.id AND y.tenant_id = t_out.tenant_id " +
        "      WHERE tp_out.piece_id = p.id AND tp_out.tenant_id = p.tenant_id " +
        "        AND COALESCE(tp_out.from_location_id, t_out.source_location_id) = :main " +
        "        AND t_out.status NOT IN ('preparing', 'cancelled') " +
        "        AND ((t_out.transfer_mode = 'round_trip' AND p.status = 'out_on_transfer' AND t_out.id = cur.id) " +
        "          OR (t_out.transfer_mode = 'relocate_out' AND (t_out.id = cur.id OR tp_out.outcome = 'relocated'))) " +
        "      ORDER BY t_out.created_at DESC, t_out.id DESC LIMIT 1) outb ON true " +
        "  WHERE p.tenant_id = :t " +
        "    AND (p.status = 'out_on_transfer' " +
        "         OR (p.status::text IN " + CUSTODY_SQL + " AND p.current_location_id IS DISTINCT FROM :main)) " +
        "), away AS ( " +
        "  SELECT variant_id, COUNT(*) FILTER (WHERE counted AND NOT in_flight) AS away_counted, bool_or(in_flight) AS in_flight " +
        "  FROM away_p GROUP BY variant_id " +
        "), held AS ( " +
        "  SELECT oi.order_id, p.variant_id, COUNT(*) AS held " +
        "  FROM allocations a " +
        "  JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = a.tenant_id " +
        "  JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = a.tenant_id " +
        "  JOIN v ON v.variant_id = p.variant_id " +
        "  WHERE a.tenant_id = :t AND a.status IN ('active', 'packed') " +
        "    AND p.status::text IN ('reserved', 'packed', 'awaiting_pickup') AND p.current_location_id = :main " +
        "  GROUP BY oi.order_id, p.variant_id " +
        "), hf AS ( " +
        "  SELECT h.variant_id, h.held, " +
        "    COALESCE((SELECT SUM((li->>'quantity')::int) " +
        "              FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f, jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "              WHERE " + restShapeSql("f") + " AND lower(f->>'status') = 'success' AND f->>'location_id' = :mainNum " +
        "                AND " + lineMatchesVariantSql("li", "vv") + " AND li->>'quantity' ~ '^[0-9]+$'), 0) AS f_main, " +
        "    shopify_restocked_units(o.raw, o.tenant_id, o.id, vv.external_id, :mainGid) AS r_main, " +
        "    (SELECT COUNT(DISTINCT e.piece_id) FROM piece_events e " +
        "       JOIN pieces pp ON pp.id = e.piece_id AND pp.tenant_id = e.tenant_id " +
        "       WHERE e.tenant_id = :t AND e.order_id = h.order_id AND e.to_status::text IN ('with_courier', 'delivered') " +
        "         AND pp.variant_id = h.variant_id) AS left_n, " +
        "    EXISTS (SELECT 1 FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f " +
        "            WHERE NOT " + restShapeSql("f") + " " +
        "               OR EXISTS (SELECT 1 FROM jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "                          WHERE jsonb_typeof(f->'line_items') = 'array' AND " + lineMatchesVariantSql("li", "vv") +
        "                            AND NOT COALESCE(li->>'quantity' ~ '^[0-9]+$', false))) AS unreadable, " +
        "    EXISTS (SELECT 1 FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f, jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "            WHERE " + restShapeSql("f") + " AND lower(f->>'status') IN ('pending', 'open') " +
        "              AND " + lineMatchesVariantSql("li", "vv") + ") AS pending, " +
        "    EXISTS (SELECT 1 FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f, jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "            WHERE " + restShapeSql("f") + " AND lower(f->>'status') IN ('cancelled', 'error', 'failure') " +
        "              AND " + lineMatchesVariantSql("li", "vv") + " " +
        "              AND COALESCE(" + fulfillmentChangedAtSql("f") + " > now() - make_interval(mins => :cancelWindowMinutes), " +
        "                           true)) AS cancelled " +
        "  FROM held h " +
        "  JOIN merchant_orders o ON o.id = h.order_id AND o.tenant_id = :t " +
        "  JOIN variants vv ON vv.id = h.variant_id AND vv.tenant_id = :t " +
        "), fsum AS ( " +
        "  SELECT variant_id, SUM(GREATEST(0, LEAST(held, f_main - r_main - left_n))) AS fulfilled_from_main, " +
        "         bool_or(unreadable) AS unreadable, bool_or(pending) AS pending, bool_or(cancelled) AS cancelled " +
        "  FROM hf GROUP BY variant_id " +
        // fulfilled_unpicked: lines Shopify fulfilled FROM MAIN (successful REST only — a cancelled / errored
        // fulfillment never counts) on a live, non-cancelled order still waiting to be picked in Traced, minus what
        // Traced already covers on the line (pieces allocated in custody anywhere + pieces that left) and refund
        // restocks at Main. With fulfilled_from_main the two never subtract more than F − R − left per line.
        // Performance (2026-11-06): the tenant's orders are read ONCE per statement — fu_main expands the Main
        // fulfillment lines of live, not-yet-packed orders (cheap column filters before any jsonb), keyed by the
        // Shopify variant gid, MATERIALIZED so the planner can't re-run it per variant; the chunk's variants then
        // hash-join on that gid, and AWAITING_PICK (subplans) runs only for orders that have such a line. Same rows
        // as joining variants inside the expansion (v = this tenant's :ids, matched by external_id). Before, a
        // 1,000-variant chunk re-scanned and re-expanded every order per variant and hit the 120 s statement_timeout
        // (MainReconcileSweepPerformanceTest).
        "), fu_main AS MATERIALIZED ( " +
        "  SELECT o.id AS order_id, 'gid://shopify/ProductVariant/' || (li->>'variant_id') AS external_id, " +
        "         SUM((li->>'quantity')::int) AS f_main " +
        "  FROM merchant_orders o " +
        "  CROSS JOIN LATERAL jsonb_array_elements(" + fulfillmentsSql("o") + ") f " +
        "  CROSS JOIN LATERAL jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "  WHERE o.tenant_id = :t AND " + liveShopifyOrderSql("o") + " AND " + PackListRules.NOT_YET_PACKED_SQL +
        "    AND " + restShapeSql("f") + " AND lower(f->>'status') = 'success' " +
        "    AND f->>'location_id' = :mainNum AND li->>'quantity' ~ '^[0-9]+$' AND li->>'variant_id' IS NOT NULL " +
        "  GROUP BY 1, 2 " +
        "), fu_lines AS ( " +
        "  SELECT x.order_id, v.variant_id, x.f_main " +
        "  FROM fu_main x JOIN v ON v.external_id = x.external_id " +
        "  WHERE EXISTS (SELECT 1 FROM merchant_orders o WHERE o.id = x.order_id AND o.tenant_id = :t AND " + AWAITING_PICK_SQL + ") " +
        "), fu AS ( " +
        "  SELECT x.variant_id, SUM(GREATEST(0, x.f_main " +
        "           - shopify_restocked_units(o.raw, o.tenant_id, o.id, vv.external_id, :mainGid) " +
        "           - (SELECT COUNT(DISTINCT e.piece_id) FROM piece_events e " +
        "                JOIN pieces pp ON pp.id = e.piece_id AND pp.tenant_id = e.tenant_id " +
        "               WHERE e.tenant_id = :t AND e.order_id = x.order_id " +
        "                 AND e.to_status::text IN ('with_courier', 'delivered') AND pp.variant_id = x.variant_id) " +
        "           - (SELECT COUNT(*) FROM allocations a " +
        "                JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = a.tenant_id " +
        "                JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = a.tenant_id " +
        "               WHERE a.tenant_id = :t AND oi.order_id = x.order_id AND p.variant_id = x.variant_id " +
        "                 AND a.status IN ('active', 'packed') AND p.status::text IN " + CUSTODY_SQL + "))) AS fulfilled_unpicked " +
        "  FROM fu_lines x " +
        "  JOIN merchant_orders o ON o.id = x.order_id AND o.tenant_id = :t " +
        "  JOIN variants vv ON vv.id = x.variant_id AND vv.tenant_id = :t " +
        "  GROUP BY x.variant_id " +
        "), p1x AS ( " +
        "  SELECT c.*, (:settle AND c.status = 'failed_ambiguous' " +
        "               AND COALESCE(c.send_started_at, c.updated_at) < now() - make_interval(mins => :holdMinutes)) AS settleable " +
        "  FROM main_reconcile_part1_claims c JOIN v ON v.variant_id = c.variant_id WHERE c.tenant_id = :t " +
        "), p1 AS ( " +
        "  SELECT variant_id, " +
        "         COALESCE(SUM(quantity) FILTER (WHERE status IN " + P1_OWED_STATUSES_SQL + " AND NOT settleable), 0) AS owed, " +
        "         bool_or(status IN " + P1_IN_FLIGHT_STATUSES_SQL + " AND NOT settleable) AS in_flight, " +
        "         array_agg(id) FILTER (WHERE settleable) AS settle_part1 " +
        "  FROM p1x GROUP BY variant_id " +
        "), cl AS ( " +
        "  SELECT sia.variant_id, " +
        "         bool_or(sia.status IN ('queued', 'pending') " +
        "                 OR (sia.status = 'failed' AND NOT sia.legacy AND sia.attempt_count < " + IncrementRecoveryRules.MAX_ATTEMPTS +
        "                     AND NOT COALESCE(" + IncrementRecoveryRules.AMBIGUOUS_EXPIRED_SQL + ", false))) AS in_flight, " +
        "         bool_or(sia.status = 'failed_ambiguous' AND NOT (:settle " +
        "                 AND COALESCE(sia.send_started_at, sia.created_at) < now() - make_interval(mins => :holdMinutes))) AS ambiguous, " +
        "         array_agg(sia.id) FILTER (WHERE sia.status = 'failed_ambiguous' AND :settle " +
        "                 AND COALESCE(sia.send_started_at, sia.created_at) < now() - make_interval(mins => :holdMinutes)) AS settle_claims " +
        "  FROM shopify_inventory_adjustments sia JOIN v ON v.variant_id = sia.variant_id " +
        "  WHERE sia.tenant_id = :t AND sia.location_id = :main " +
        "    AND sia.status IN ('queued', 'pending', 'failed', 'failed_ambiguous') " +
        "  GROUP BY sia.variant_id " +
        "), rw AS ( " +
        "  SELECT w.variant_id, " +
        "         bool_or(w.status = 'pending' OR (w.status = 'failed_ambiguous' " +
        "                 AND w.created_at >= now() - make_interval(mins => :holdMinutes))) AS in_flight, " +
        "         array_agg(w.id) FILTER (WHERE w.status = 'failed_ambiguous' " +
        "                 AND w.created_at < now() - make_interval(mins => :holdMinutes)) AS settle_writes " +
        "  FROM main_reconcile_writes w JOIN v ON v.variant_id = w.variant_id " +
        "  WHERE w.tenant_id = :t AND w.status IN ('pending', 'failed_ambiguous') GROUP BY w.variant_id " +
        "), lo AS ( " +
        "  SELECT x.variant_id, x.order_id, COUNT(*) AS out_n " +
        "  FROM (" + piecesOutSql("AND p.variant_id = ANY(CAST(:ids AS uuid[]))") + ") x GROUP BY x.variant_id, x.order_id " +
        "), uf AS ( " +
        "  SELECT lo.variant_id, " +
        "         SUM(CASE WHEN " + orderFulfilledSql("o") + " OR " + unfulfilledUnreadableSql("o") + " THEN 0 " +
        "                  ELSE LEAST(lo.out_n, " + unfulfilledUnitsSql("o", "vv") + ") END) AS unfulfilled_shipped, " +
        "         bool_or(NOT " + orderFulfilledSql("o") + " AND " + unfulfilledUnreadableSql("o") + ") AS unreadable " +
        "  FROM lo JOIN merchant_orders o ON o.id = lo.order_id AND o.tenant_id = :t " +
        "  JOIN variants vv ON vv.id = lo.variant_id AND vv.tenant_id = :t " +
        "  WHERE " + liveShopifyOrderSql("o") + " " +
        "  GROUP BY lo.variant_id " +
        "), ar AS ( " +
        "  SELECT DISTINCT p.variant_id " +
        "  FROM shopify_inventory_adjustments sia " +
        "  JOIN pieces p ON p.id = split_part(sia.trigger_id, ':', 1) AND p.tenant_id = sia.tenant_id " +
        "  JOIN v ON v.variant_id = p.variant_id " +
        "  JOIN shopify_inventory_adjustments r ON r.tenant_id = sia.tenant_id AND r.trigger_type = 'receiving_session' " +
        "       AND r.trigger_id = p.receipt_id::text AND r.variant_id = p.variant_id AND r.location_id = :main " +
        "  WHERE sia.tenant_id = :t AND sia.status = 'skipped' AND sia.skip_reason IN " + ArrivalRevisit.REVISIT_REASONS_SQL +
        "    AND sia.trigger_type IN " + ArrivalRevisit.TRIGGERS_SQL + " AND " + PieceShopifyRules.LIVE_SQL +
        "    AND (r.status IN ('queued', 'pending') " +
        "         OR (r.status = 'failed' AND NOT r.legacy AND (r.attempt_count < " + IncrementRecoveryRules.MAX_ATTEMPTS +
        "             OR r.failure_class = 'ambiguous'))) " +
        ") " +
        "SELECT v.variant_id, v.item, " +
        "       COALESCE(custody.custody, 0) AS custody_at_main, COALESCE(custody.damaged, 0) AS traced_damaged, " +
        "       COALESCE(away.away_counted, 0) AS away_counted, COALESCE(fsum.fulfilled_from_main, 0) AS fulfilled_from_main, " +
        "       COALESCE(fu.fulfilled_unpicked, 0) AS fulfilled_unpicked, " +
        "       COALESCE(p1.owed, 0) AS part1_owed, " +
        "       COALESCE(uf.unfulfilled_shipped, 0) AS unfulfilled_shipped_raw, " +
        "       COALESCE(p1.in_flight, false) AS part1_in_flight, " +
        "       COALESCE(rw.in_flight, false) AS reconcile_write_in_flight, " +
        "       p1.settle_part1, cl.settle_claims, rw.settle_writes, " +
        "       (ar.variant_id IS NOT NULL) AS arrival_revisit_pending, " +
        "       COALESCE(cl.in_flight, false) AS claim_in_flight, COALESCE(cl.ambiguous, false) AS claim_ambiguous, " +
        "       EXISTS (SELECT 1 FROM stock_take_shopify_syncs s WHERE s.tenant_id = :t " +
        "               AND s.status IN ('pending', 'failed', 'failed_ambiguous') " +
        "               AND jsonb_typeof(s.payload->'deltas') = 'object' " +
        "               AND jsonb_exists(s.payload->'deltas', v.variant_id::text)) AS stock_take_sync_in_flight, " +
        "       (COALESCE(away.in_flight, false) OR EXISTS (SELECT 1 FROM transfer_shopify_syncs y WHERE y.tenant_id = :t " +
        "               AND (y.status IN ('queued', 'pending', 'failed_ambiguous') " +
        "                    OR (y.status = 'failed' AND y.attempt_count < " + TransferShopifySync.MAX_ATTEMPTS + ")) " +
        "               AND jsonb_typeof(y.deltas) = 'object' AND jsonb_exists(y.deltas, v.variant_id::text))) " +
        "           AS transfer_sync_in_flight, " +
        "       (COALESCE(fsum.unreadable, false) OR COALESCE(uf.unreadable, false)) AS fulfillment_unreadable, " +
        "       COALESCE(fsum.pending, false) AS fulfillment_pending, " +
        "       COALESCE(fsum.cancelled, false) AS fulfillment_cancelled " +
        "FROM v " +
        "LEFT JOIN custody ON custody.variant_id = v.variant_id " +
        "LEFT JOIN away ON away.variant_id = v.variant_id " +
        "LEFT JOIN fsum ON fsum.variant_id = v.variant_id " +
        "LEFT JOIN fu ON fu.variant_id = v.variant_id " +
        "LEFT JOIN p1 ON p1.variant_id = v.variant_id " +
        "LEFT JOIN cl ON cl.variant_id = v.variant_id " +
        "LEFT JOIN rw ON rw.variant_id = v.variant_id " +
        "LEFT JOIN uf ON uf.variant_id = v.variant_id " +
        "LEFT JOIN ar ON ar.variant_id = v.variant_id";

    /**
     * The sweep's Traced side: every variant Traced holds anything of at (or counted at) Main, or owes a Part 1
     * claim for — so a variant Shopify shows nothing for is still checked. :t, :main.
     */
    public static final String TRACED_VARIANTS_SQL =
        "SELECT DISTINCT p.variant_id FROM pieces p WHERE p.tenant_id = :t " +
        "  AND (p.status = 'out_on_transfer' " +
        "       OR (p.status::text IN ('available', 'reserved', 'packed', 'awaiting_pickup', 'damaged') " +
        "           AND p.current_location_id = :main) " +
        "       OR (p.status::text IN " + CUSTODY_SQL + " AND p.current_location_id IS DISTINCT FROM :main)) " +
        "UNION " +
        "SELECT c.variant_id FROM main_reconcile_part1_claims c " +
        "WHERE c.tenant_id = :t AND c.status IN " + P1_OWED_STATUSES_SQL;

    /**
     * Part 1 candidates: every successful-or-not REST fulfillment line at a location that is not Main, on a non-internal
     * order created in the window. vv is NULL for a line whose variant Traced doesn't know. :t, :mainNum, :days, :all
     * (boolean — every variant) or :ids.
     */
    public static final String PART1_LINES_SQL =
        "SELECT o.id AS order_id, (COALESCE(o.is_cancelled, false) OR o.status = 'cancelled') AS order_cancelled, " +
        "       vv.id AS variant_id, f->>'id' AS fulfillment_id, li->>'id' AS line_item_id, " +
        "       lower(f->>'status') AS fulfillment_status, f->>'location_id' AS fulfilled_location_id, " +
        "       (li->>'quantity')::int AS qty, " + fulfillmentCreatedAtSql("f") + " AS fulfillment_created_at " +
        "FROM merchant_orders o " +
        "CROSS JOIN LATERAL jsonb_array_elements(" + fulfillmentsSql("o") + ") f " +
        "CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(f->'line_items') = 'array' " +
        "                                             THEN f->'line_items' ELSE '[]'::jsonb END) li " +
        "LEFT JOIN variants vv ON vv.tenant_id = o.tenant_id AND " + lineMatchesVariantSql("li", "vv") + " " +
        "WHERE o.tenant_id = :t AND o.created_at > now() - make_interval(days => :days) " +
        "  AND o.external_id NOT LIKE 'internal:%' " +
        "  AND " + restShapeSql("f") + " AND f->>'location_id' <> :mainNum " +
        "  AND f->>'id' IS NOT NULL AND li->>'id' IS NOT NULL " +
        "  AND li->>'quantity' ~ '^[0-9]+$' AND (li->>'quantity')::int > 0 " +
        "  AND (:all OR vv.id = ANY(CAST(:ids AS uuid[]))) " +
        "ORDER BY o.id, vv.id, f->>'created_at', f->>'id', li->>'id'";

    /**
     * R2 fix 2 alert (delivered_not_fulfilled_in_shopify, MEDIUM): non-cancelled Shopify orders whose pieces Traced
     * delivered more than :days days ago (and within :lookbackDays) while Shopify still shows units of those variants
     * unfulfilled. One row per order. :t, :days, :lookbackDays.
     */
    public static final String DELIVERED_NOT_FULFILLED_SQL =
        "SELECT o.id AS order_id, o.number AS order_number, MIN(x.left_at) AS delivered_at, COUNT(*) AS pieces " +
        "FROM (" + piecesOutSql("AND p.status = 'delivered'") + ") x " +
        "JOIN merchant_orders o ON o.id = x.order_id AND o.tenant_id = :t " +
        "JOIN variants vv ON vv.id = x.variant_id AND vv.tenant_id = :t " +
        "WHERE " + liveShopifyOrderSql("o") + " AND NOT " + orderFulfilledSql("o") +
        "  AND NOT " + unfulfilledUnreadableSql("o") +
        "  AND x.left_at < now() - make_interval(days => :days) " +
        "  AND x.left_at > now() - make_interval(days => :lookbackDays) " +
        "  AND " + unfulfilledUnitsSql("o", "vv") + " > 0 " +
        "GROUP BY o.id, o.number";

    /** Orders in the window with a fulfillment Traced can't read (GraphQL import shape) — counted for Part 1. */
    public static final String PART1_UNREADABLE_SQL =
        "SELECT COUNT(*) FROM merchant_orders o " +
        "WHERE o.tenant_id = :t AND o.created_at > now() - make_interval(days => :days) " +
        "  AND o.external_id NOT LIKE 'internal:%' " +
        "  AND EXISTS (SELECT 1 FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f WHERE NOT " + restShapeSql("f") + ")";

    /**
     * Part 1, per (order, variant): F_main (successful REST lines from Main), left (pieces of the order + variant that
     * left custody), in_custody (pieces still allocated in custody, any location), claimed (existing claims' units,
     * dropped excluded). :t, :mainNum, :orderIds, :variantIds (parallel uuid[] as text).
     */
    public static final String PART1_GROUPS_SQL =
        "SELECT g.order_id, g.variant_id, " +
        "  COALESCE((SELECT SUM((li->>'quantity')::int) " +
        "            FROM jsonb_array_elements(" + fulfillmentsSql("o") + ") f, jsonb_array_elements(" + lineItemsSql("f") + ") li " +
        "            WHERE " + restShapeSql("f") + " AND lower(f->>'status') = 'success' AND f->>'location_id' = :mainNum " +
        "              AND " + lineMatchesVariantSql("li", "vv") + " AND li->>'quantity' ~ '^[0-9]+$'), 0) AS f_main, " +
        "  (SELECT COUNT(DISTINCT e.piece_id) FROM piece_events e " +
        "     JOIN pieces pp ON pp.id = e.piece_id AND pp.tenant_id = e.tenant_id " +
        "     WHERE e.tenant_id = :t AND e.order_id = g.order_id AND e.to_status::text IN ('with_courier', 'delivered') " +
        "       AND pp.variant_id = g.variant_id) AS left_n, " +
        "  (SELECT COUNT(*) FROM allocations a " +
        "     JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = a.tenant_id " +
        "     JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = a.tenant_id " +
        "     WHERE a.tenant_id = :t AND oi.order_id = g.order_id AND p.variant_id = g.variant_id " +
        "       AND a.status IN ('active', 'packed') AND p.status::text IN " + CUSTODY_SQL + ") AS in_custody, " +
        "  (SELECT COALESCE(SUM(c.quantity), 0) FROM main_reconcile_part1_claims c " +
        "     WHERE c.tenant_id = :t AND c.order_id = g.order_id AND c.variant_id = g.variant_id " +
        "       AND c.status <> 'dropped') AS claimed " +
        "FROM unnest(CAST(:orderIds AS uuid[]), CAST(:variantIds AS uuid[])) AS g(order_id, variant_id) " +
        "JOIN merchant_orders o ON o.id = g.order_id AND o.tenant_id = :t " +
        "JOIN variants vv ON vv.id = g.variant_id AND vv.tenant_id = :t";
}
