-- Non-Bosta couriers G1 (V168 / V169) — READ-ONLY before/after snapshot for the deploy.
--
-- Prints, for every tenant: per open order its derived status, shipping badge / carrier and
-- pickable yes/no; per variant its committed quantity; the number of open stuck alerts; the
-- generic switch and generic-leg count (both must stay false / 0 — no tenant is switched on); and
-- (G2) the analytics outcome counts of the last 30 days (section 4); and (G3, section 5) per tenant: pickups
-- (open-session scans by courier, handovers), delivered and returned forward legs of the 30 days before as_of
-- by courier and source, generic parcels scanned back, and the returns-portal eligible order count.
-- Every value is computed by SQL that mirrors the code paths (one comment per block names the Java
-- it mirrors). It sees what the DATABASE says: the migration's effects (enum value, the swapped
-- uniqueness indexes, shipping_carrier_of with the named-carrier list, the V132 trigger) and any row
-- the new code writes. It does not execute the Java, so the in-app check after deploy is still: open
-- Orders / Pick & Pack / Exceptions for one Bosta tenant and compare with before.
--
-- RUN (prod, read-only; a few seconds — open orders only, no jsonb detoast of old orders):
--   AS_OF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"    # pick ONE timestamp and use it for both runs
--   psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > before.txt
--   … deploy …
--   psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > after.txt
--   diff before.txt after.txt
-- Expected: empty. as_of pins every "now" (badge overdue days, stuck window) so the clock moving
-- between runs can't show up. Real activity between the runs (a new order, a Bosta webhook) can — each
-- order line ends with its last-activity marker, so such a line is recognisable: its marker changed too.
-- Connection: the owner / postgres role (sees every tenant). Use the session pooler :5432, never :6543.

\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';

-- 0. Per tenant: the switch and generic legs (must be false / 0 before AND after).
SELECT 'tenant' AS section, t.name, t.id,
       -- to_jsonb: the column doesn't exist before V169 (prints false then too, so the diff stays empty)
       'generic_courier_enabled=' || coalesce(
           (SELECT (to_jsonb(t2)->>'generic_courier_enabled')::boolean FROM tenants t2 WHERE t2.id = t.id), false)
       || ' generic_legs=' || (SELECT count(*) FROM shipments s WHERE s.tenant_id = t.id
                                 AND s.provider::text = 'generic')
FROM tenants t
ORDER BY t.name, t.id;

-- 1. Open orders: derived status (OrderStatusDeriver.derive, list path), badge (OrderShippingBadge.derive),
--    pickable (FulfillService.PICKABLE_SHIPMENT_GATE without the queue's lookback filter).
WITH cfg AS (SELECT (:'as_of')::timestamptz AS now_ts, 3 AS overdue_days, 60 AS grace_minutes),
open_orders AS (
    SELECT o.*
    FROM orders o
    WHERE o.external_id NOT LIKE 'internal:portal:%'          -- merchant_orders
      AND o.status NOT IN ('delivered', 'returned', 'lost', 'cancelled')
),
fwd AS (
    SELECT o.id AS order_id, s.*
    FROM open_orders o
    LEFT JOIN LATERAL (
        SELECT sh.id AS shipment_id, sh.provider::text AS provider,
               to_jsonb(sh) ->> 'carrier_name' AS carrier_name,   -- column exists only from V169
               sh.internal_state::text AS internal_state, sh.last_synced_at, sh.created_at AS s_created,
               sh.is_delayed, sh.sla_breached, sh.exception_code,
               (SELECT count(*) FROM shipment_status_history h2
                  LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                 WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                   AND h2.exception_code IS NOT NULL
                   AND (n.category = 'forward' OR n.category IS NULL)) AS failed_attempts,
               coalesce((SELECT max(CASE h.internal_state WHEN 'created' THEN 1 WHEN 'with_courier' THEN 2
                                                          WHEN 'returning' THEN 3 WHEN 'exception' THEN 0 END)
                           FROM shipment_status_history h WHERE h.shipment_id = sh.id),
                        CASE sh.internal_state WHEN 'created' THEN 1 WHEN 'with_courier' THEN 2
                                               WHEN 'returning' THEN 3 WHEN 'exception' THEN 0 END) AS max_rank
        FROM shipments sh
        WHERE sh.order_id = o.id AND sh.tenant_id = o.tenant_id AND sh.shipment_leg = 'forward'
        ORDER BY sh.created_at DESC, sh.id DESC
        LIMIT 1
    ) s ON true
),
derived AS (
    SELECT o.tenant_id, o.id, o.number, o.status::text AS order_status, o.is_self_pickup, o.on_hold,
           o.shipping_carrier_class, o.shipping_carrier_name, o.placed_at,
           f.provider, f.carrier_name, f.internal_state, f.max_rank, f.failed_attempts,
           greatest(f.last_synced_at, f.s_created,
                    (SELECT max(ft.last_seen_at) FROM order_fulfillment_tracking ft WHERE ft.order_id = o.id)) AS activity,
           -- OrderStatusDeriver.derive (non-cancelled orders only here)
           CASE
             WHEN f.internal_state IS NULL THEN 'pipeline:' || o.status::text
             -- G3: a generic leg's delivered reads "Delivered · {carrier}"
             WHEN f.internal_state = 'delivered' AND f.provider = 'generic' THEN 'status.delivered_with_carrier'
             WHEN f.internal_state IN ('delivered','returned','lost','terminated','cancelled') THEN 'status.' || f.internal_state
             WHEN f.failed_attempts >= 1 THEN 'status.delivery_failed'
             WHEN f.internal_state = 'exception' THEN 'status.needs_attention'
             WHEN coalesce(f.max_rank, 1) = 1 THEN 'status.label_created'
             WHEN coalesce(f.max_rank, 1) = 2 THEN CASE WHEN f.provider = 'generic' THEN 'status.with_carrier'
                                                        ELSE 'status.in_transit' END
             WHEN coalesce(f.max_rank, 1) = 3 THEN 'status.returning'
             ELSE 'status.awaiting_courier'
           END AS derived_status,
           EXISTS (SELECT 1 FROM courier_accounts c WHERE c.tenant_id = o.tenant_id
                     AND c.provider = 'bosta' AND c.status = 'active') AS ships_with_bosta,
           EXISTS (SELECT 1 FROM order_fulfillment_tracking ft, cfg
                    WHERE ft.order_id = o.id AND ft.tenant_id = o.tenant_id AND ft.carrier_class = 'bosta'
                      AND lower(coalesce(ft.fulfillment_status, '')) <> 'cancelled'
                      AND ft.link_status IS DISTINCT FROM 'linked'
                      AND NOT EXISTS (SELECT 1 FROM shipments s2 WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id
                                        AND s2.tracking_number = ft.tracking_number)
                      AND (ft.link_status IN ('conflict', 'gave_up')
                           OR ft.first_seen_at < cfg.now_ts - make_interval(mins => cfg.grace_minutes))) AS link_problem,
           (o.on_hold = false
            AND o.status IN ('new', 'ready_to_pick', 'self_pickup_pending')
            AND (o.is_self_pickup OR f.internal_state = 'created')) AS pickable
    FROM open_orders o
    JOIN fwd f ON f.order_id = o.id
)
SELECT 'order' AS section, t.name, d.number,
       d.derived_status
       || ' | badge=' || CASE
            -- OrderShippingBadge.derive (order not cancelled: open orders only)
            WHEN d.internal_state IS NOT NULL AND d.internal_state NOT IN ('cancelled', 'terminated')
                 THEN CASE WHEN d.provider = 'generic' THEN 'generic_carrier/' || coalesce(d.carrier_name, '')
                           ELSE 'linked/Bosta' END
            WHEN d.is_self_pickup OR NOT d.ships_with_bosta THEN 'none'
            WHEN d.link_problem THEN 'bosta_tracking_not_linked/Bosta'
            WHEN d.shipping_carrier_class = 'other_known' THEN 'shipped_elsewhere/' || coalesce(d.shipping_carrier_name, '')
            WHEN d.placed_at IS NOT NULL
                 AND (SELECT cfg.now_ts FROM cfg) - d.placed_at >= make_interval(days => (SELECT overdue_days FROM cfg))
                 THEN 'not_booked_overdue/' || floor(extract(epoch FROM (SELECT cfg.now_ts FROM cfg) - d.placed_at) / 86400)::int
            ELSE 'awaiting_booking'
          END
       || ' | carrier=' || coalesce(d.shipping_carrier_class, '-') || '/' || coalesce(d.shipping_carrier_name, '-')
       || ' | pickable=' || CASE WHEN d.pickable THEN 'yes' ELSE 'no' END
       || ' | activity=' || coalesce(to_char(d.activity AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS'), '-')
FROM derived d JOIN tenants t ON t.id = d.tenant_id
ORDER BY t.name, d.number, d.id;

-- 2. Committed quantity per variant (VariantStockService.COMMITTED_SQL — same gate as Pick & Pack).
SELECT 'committed' AS section, t.name, coalesce(v.sku, v.id::text),
       sum(greatest(oi.quantity - coalesce(alloc.active_count, 0), 0))::text
FROM orders o
JOIN tenants t ON t.id = o.tenant_id
JOIN order_items oi ON oi.order_id = o.id
JOIN variants v ON v.id = oi.variant_id
LEFT JOIN (SELECT a.order_item_id, count(*) AS active_count FROM allocations a
            WHERE a.status = 'active' GROUP BY a.order_item_id) alloc ON alloc.order_item_id = oi.id
LEFT JOIN LATERAL (
    SELECT internal_state FROM shipments
    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward'
    ORDER BY created_at DESC, id DESC LIMIT 1
) latest_shipment ON true
WHERE o.external_id NOT LIKE 'internal:portal:%'
  AND o.status IN ('new', 'ready_to_pick', 'self_pickup_pending')
  AND o.on_hold = false
  AND (o.is_self_pickup = true OR latest_shipment.internal_state = 'created')
GROUP BY t.name, coalesce(v.sku, v.id::text)
ORDER BY 2, 3;

-- 3. Open stuck alerts per tenant (ExceptionService.detectStuck; simulated tenants are muted there).
SELECT 'stuck' AS section, t.name, t.id::text, count(s.id)::text
FROM tenants t
LEFT JOIN shipments s ON s.tenant_id = t.id
     AND s.shipment_leg = 'forward'
     AND s.provider::text <> 'generic'
     AND s.internal_state NOT IN ('delivered', 'returned', 'lost', 'terminated', 'cancelled')
     AND coalesce(s.last_synced_at, s.created_at) < (:'as_of')::timestamptz - (interval '1 day' * t.stuck_shipment_days)
     AND EXISTS (SELECT 1 FROM orders o WHERE o.id = s.order_id AND o.external_id NOT LIKE 'internal:portal:%')
     AND NOT EXISTS (SELECT 1 FROM exception_resolutions er
                      WHERE er.tenant_id = s.tenant_id AND er.exception_type = 'stuck_shipment'
                        AND er.subject_key = 'stuck:shipment:' || s.id
                        AND er.resolved_at > coalesce(s.last_synced_at, s.created_at))
WHERE NOT EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = t.id)
GROUP BY t.name, t.id
ORDER BY t.name, t.id;

-- 4. Analytics outcomes per tenant (G2): orders placed in the 30 days before as_of, per outcome — the
--    SalesAnalyticsService.ORDER_OUTCOMES rule as of G2: the deciding leg is the order's BOSTA forward leg
--    (never a type 25 / 30 leg; a live one first, else the newest ended one); an order with no deciding
--    Bosta leg, or one ended before pickup, is 'wijha' when Shopify names another known carrier OR it has a
--    live generic (non-Bosta courier) forward leg, else 'not_shipped'. Cohort ≈ soldLines: merchant
--    orders, not cancelled, not exchange replacements, at/after the store's ingest floor, with a line
--    still sold (floor overrides and refund add-backs are left out — same script before and after, so the
--    diff is what matters). Bosta-only tenants: identical before and after the deploy.
WITH cfg AS (SELECT (:'as_of')::timestamptz AS now_ts),
period_orders AS (
    SELECT o.tenant_id, o.id AS order_id, o.shipping_carrier_class AS carrier_class
    FROM orders o
    JOIN stores st ON st.id = o.store_id
    CROSS JOIN cfg
    WHERE o.external_id NOT LIKE 'internal:portal:%'          -- merchant_orders
      AND o.external_id NOT LIKE 'internal:exchange:%'
      AND o.status <> 'cancelled' AND NOT coalesce(o.raw_cancelled, false)
      AND o.placed_at >= cfg.now_ts - interval '30 days' AND o.placed_at < cfg.now_ts
      AND (st.orders_ingest_from IS NULL OR o.placed_at >= st.orders_ingest_from)
      AND EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND coalesce(oi.current_qty, oi.quantity) > 0)
),
outcomes AS (
    SELECT po.tenant_id,
           CASE
             WHEN NOT d.bosta_decides THEN
                  CASE WHEN po.carrier_class = 'other_known'
                         OR EXISTS (SELECT 1 FROM shipments g WHERE g.order_id = po.order_id
                                      AND g.provider::text = 'generic' AND g.shipment_leg = 'forward'
                                      AND g.internal_state NOT IN ('terminated', 'cancelled'))
                       THEN 'wijha' ELSE 'not_shipped' END
             WHEN leg.internal_state IN ('terminated', 'cancelled')
                  AND leg.collected_from_business_at IS NULL AND NOT coalesce(h.picked_up, false) THEN 'not_shipped'
             WHEN leg.internal_state = 'delivered' THEN 'delivered'
             WHEN leg.type_code = '20' OR leg.internal_state IN ('returning', 'returned')
                  OR (h.first_return IS NOT NULL AND (h.first_delivered IS NULL OR h.first_return < h.first_delivered))
                  THEN 'refused'
             WHEN h.first_delivered IS NOT NULL THEN 'delivered'
             WHEN leg.internal_state IN ('lost', 'terminated', 'cancelled') THEN 'other_terminal'
             ELSE 'in_transit'
           END AS outcome
    FROM period_orders po
    LEFT JOIN LATERAL (
        SELECT s.id AS shipment_id, s.internal_state::text AS internal_state, s.collected_from_business_at, s.type_code
        FROM shipments s
        WHERE s.order_id = po.order_id AND s.tenant_id = po.tenant_id AND s.provider::text = 'bosta'
          AND s.shipment_leg = 'forward' AND coalesce(s.type_code, '10') NOT IN ('25', '30')
        ORDER BY (s.internal_state IN ('terminated', 'cancelled')), s.created_at DESC, s.id DESC
        LIMIT 1
    ) leg ON true
    LEFT JOIN LATERAL (
        SELECT min(hh.occurred_at) FILTER (WHERE hh.internal_state = 'delivered')                AS first_delivered,
               min(hh.occurred_at) FILTER (WHERE hh.internal_state IN ('returning', 'returned')) AS first_return,
               coalesce(bool_or(hh.internal_state IN ('with_courier', 'returning', 'returned', 'delivered', 'lost')), false)
                                                                                                  AS picked_up
        FROM shipment_status_history hh
        WHERE hh.shipment_id = leg.shipment_id AND hh.tenant_id = po.tenant_id
    ) h ON true
    CROSS JOIN LATERAL (
        SELECT leg.shipment_id IS NOT NULL
               AND NOT (leg.internal_state IN ('terminated', 'cancelled')
                        AND (po.carrier_class = 'other_known'
                             OR (leg.collected_from_business_at IS NULL AND NOT coalesce(h.picked_up, false))))
               AS bosta_decides
    ) d
)
SELECT 'outcomes' AS section, t.name, t.id::text, oc.outcome, count(*)::text
FROM outcomes oc JOIN tenants t ON t.id = oc.tenant_id
GROUP BY t.name, t.id, oc.outcome
ORDER BY t.name, t.id, oc.outcome;

-- 5. G3 (V173) per tenant — every line is "metric/courier[/source]" → count. Bosta rows have no source (V173
--    leaves it NULL for them) and print '-'; a tenant with no generic shipment prints only bosta / '-' metrics,
--    identical before and after. Read through to_jsonb where a column is new in V173, so the script also runs
--    on the schema before it. Windows end at as_of (the 30 days before it; the portal: the tenant's own
--    return window), so the clock moving between runs can't show up — real activity between runs can.
--    Mirrors: PickupSessionService (open scans; handover = a with_courier history row with no webhook event),
--    GenericLegTransitions (delivered / returned + source), ReturnSessionService.scanGenericAwb (parcels
--    scanned back), PortalService.deliveredWithinWindow + GenericShipments.newestForwardIsGenericSql (eligible).
WITH cfg AS (SELECT (:'as_of')::timestamptz AS now_ts),
metrics AS (
    SELECT p.tenant_id, 'pickup_open_scans/' || s.provider::text AS metric, count(*) AS n
    FROM pickups p
    JOIN pickup_shipments ps ON ps.pickup_id = p.id
    JOIN shipments s ON s.id = ps.shipment_id
    WHERE p.session_status = 'open'
    GROUP BY 1, 2
    UNION ALL
    SELECT h.tenant_id, 'handed_over/' || s.provider::text || '/' || coalesce(to_jsonb(h) ->> 'source', '-'), count(*)
    FROM shipment_status_history h
    JOIN shipments s ON s.id = h.shipment_id
    CROSS JOIN cfg
    WHERE h.internal_state = 'with_courier' AND h.webhook_event_id IS NULL AND s.shipment_leg = 'forward'
      AND h.occurred_at >= cfg.now_ts - interval '30 days' AND h.occurred_at < cfg.now_ts
    GROUP BY 1, 2
    UNION ALL
    SELECT s.tenant_id, 'delivered/' || s.provider::text || '/' || coalesce(
               (SELECT to_jsonb(h) ->> 'source' FROM shipment_status_history h
                 WHERE h.shipment_id = s.id AND h.internal_state = 'delivered'
                 ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1), '-'), count(*)
    FROM shipments s CROSS JOIN cfg
    WHERE s.shipment_leg = 'forward' AND s.delivered_at >= cfg.now_ts - interval '30 days' AND s.delivered_at < cfg.now_ts
    GROUP BY 1, 2
    UNION ALL
    SELECT s.tenant_id, 'returned/' || s.provider::text, count(*)
    FROM shipments s CROSS JOIN cfg
    WHERE s.shipment_leg = 'forward' AND s.internal_state = 'returned'
      AND s.returned_at >= cfg.now_ts - interval '30 days' AND s.returned_at < cfg.now_ts
    GROUP BY 1, 2
    UNION ALL
    SELECT rss.tenant_id, 'return_scans/' || CASE WHEN to_jsonb(rss) ->> 'shipment_id' IS NULL THEN 'bosta' ELSE 'generic' END,
           count(*)
    FROM return_session_shipments rss CROSS JOIN cfg
    WHERE rss.linked_at >= cfg.now_ts - interval '30 days' AND rss.linked_at < cfg.now_ts
    GROUP BY 1, 2
    UNION ALL
    -- starts from forward legs delivered in a bounded range (shipments_tenant_delivered_at_idx), never all orders
    SELECT s.tenant_id, 'portal_eligible', count(DISTINCT s.order_id)
    FROM shipments s
    JOIN tenants tt ON tt.id = s.tenant_id
    JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
    CROSS JOIN cfg
    WHERE s.shipment_leg = 'forward'
      AND s.delivered_at >= cfg.now_ts - interval '120 days' AND s.delivered_at <= cfg.now_ts
      AND cfg.now_ts - s.delivered_at <= interval '1 day' * tt.customer_return_window_days
      AND o.external_id NOT LIKE 'internal:portal:%' AND o.pii_redacted_at IS NULL
      -- the order's newest forward leg that has a delivered_at is this one
      AND NOT EXISTS (SELECT 1 FROM shipments s2 WHERE s2.order_id = s.order_id AND s2.tenant_id = s.tenant_id
                        AND s2.shipment_leg = 'forward' AND s2.delivered_at IS NOT NULL
                        AND (s2.created_at, s2.id) > (s.created_at, s.id))
      -- G3: the order's newest forward leg (any state) isn't another courier's
      AND NOT coalesce((SELECT s_g.provider::text = 'generic' FROM shipments s_g
                         WHERE s_g.order_id = s.order_id AND s_g.shipment_leg = 'forward'
                         ORDER BY s_g.created_at DESC, s_g.id DESC LIMIT 1), false)
    GROUP BY 1, 2
)
SELECT 'g3' AS section, t.name, t.id::text, m.metric, m.n::text
FROM metrics m JOIN tenants t ON t.id = m.tenant_id
ORDER BY t.name, t.id, m.metric;

ROLLBACK;
