-- V43 — FR-12.6 CRP return shipment leg + type-normalization fixes
--
-- Root cause: ux_active_shipment_per_order (V19) treats 'delivered' as active,
-- blocking a CRP (type.code=25) return shipment INSERT when the forward shipment
-- is in state 'delivered'. Fix: add shipment_leg discriminator; rebuild the index
-- as UNIQUE (order_id, shipment_leg) so forward and return coexist.
--
-- Deferred-list note: state 41 has no :ALL fallback — future unknown order types
-- (neither SEND/RTO/FXF_SEND/EXCHANGE/CUSTOMER RETURN PICKUP) will unknownCode there.
--
-- Pre-flight note: tracking 8012985727 (CRP, order #385327609470) is already linked
-- because its order had no forward shipment — empty active slot, no V19 collision.
-- Step 2 backfills it to shipment_leg='return'. Step 3 then validates no collisions
-- before the new index is built.
--
-- State-mapper fix bundled here: V37 seeded applies_to_order_type='CRP' for state 41,
-- but BostaHttpGateway normalizes type.value.toUpperCase() → 'CUSTOMER RETURN PICKUP'.
-- The mapper key '41:CRP' is never found; CRP at state 41 falls through to unknownCode.
-- States 22/23/30/46 use :ALL and are unaffected. Step 5 fixes the single broken row
-- without changing the 27-row count (MigrationSmokeTest expects 27 rows).

-- 1. Add leg discriminator; NOT NULL DEFAULT 'forward' correctly labels all existing rows
--    (all prior rows are forward deliveries — CRP inserts have never succeeded).
ALTER TABLE shipments
    ADD COLUMN shipment_leg TEXT NOT NULL DEFAULT 'forward'
        CHECK (shipment_leg IN ('forward', 'return'));

-- 2. Backfill: mark any existing CRP-linked shipments as return legs.
--    Targets raw.type.code=25 from the stored Bosta payload. Runs BEFORE the new index
--    so a second active-return row cannot violate the not-yet-existing constraint.
UPDATE shipments
SET    shipment_leg = 'return'
WHERE  (raw -> 'type' ->> 'code')::int = 25;

-- 3. Safety guard: refuse to build the index if any (order_id, leg) pair has
--    more than one active row. Raised exception rolls back the whole migration.
DO $$
BEGIN
    IF EXISTS (
        SELECT order_id, shipment_leg
        FROM   shipments
        WHERE  internal_state NOT IN ('terminated', 'cancelled')
        GROUP  BY order_id, shipment_leg
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V43 safety check: duplicate active (order_id, shipment_leg) found — '
            'inspect shipments and resolve before applying this migration';
    END IF;
END $$;

-- 4. Rebuild the active-shipment uniqueness index per leg.
--    Old index (ux_active_shipment_per_order) blocked the CRP INSERT when forward
--    is 'delivered'. New index allows forward + return to coexist.
DROP   INDEX ux_active_shipment_per_order;

CREATE UNIQUE INDEX ux_active_shipment_per_order_leg
    ON shipments (order_id, shipment_leg)
    WHERE internal_state NOT IN ('terminated', 'cancelled');

-- 5. Fix V37 CRP state mapping: 'CRP' → 'CUSTOMER RETURN PICKUP'
--    (actual type.value.toUpperCase() from Bosta v0 API for type.code=25).
--    State 41 has no :ALL fallback; without this fix, CRP at state 41 → unknownCode.
--    One row updated; 27-row count unchanged.
UPDATE bosta_state_mappings
SET    applies_to_order_type = 'CUSTOMER RETURN PICKUP'
WHERE  state_code            = 41
  AND  applies_to_order_type = 'CRP';
