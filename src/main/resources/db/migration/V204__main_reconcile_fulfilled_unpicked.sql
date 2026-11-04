-- V204 — Main reconcile: the fulfilled_unpicked term (prod finding 2026-11-04, Dolt in write mode, 0 writes landed).
--
-- The Bosta app fulfills an order in Shopify FROM MAIN as soon as it creates the shipment — often before Traced picks
-- it. Shopify Main's on_hand has already dropped; in Traced the pieces are still plain 'available' with no allocation,
-- so fulfilled_from_main (which only sees orders with pieces allocated in custody) never subtracted them and the
-- reconciler read Shopify as N too LOW (would have written +N → oversell).
--
--   fulfilled_unpicked  per variant: units of order lines Shopify fulfilled from Main (successful REST fulfillments,
--                       order not cancelled) that Traced has not covered yet — neither allocated in custody nor
--                       already left — for orders still waiting to be picked in Traced (MainReconcileRules).
--                       Recorded on every diff and on every reconcile write.
ALTER TABLE main_reconcile_diffs  ADD COLUMN fulfilled_unpicked integer;
ALTER TABLE main_reconcile_writes ADD COLUMN fulfilled_unpicked integer NOT NULL DEFAULT 0;
