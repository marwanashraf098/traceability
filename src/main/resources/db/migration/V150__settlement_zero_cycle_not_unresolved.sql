-- V150 — Analytics B1: a ZERO cash cycle is never 'unresolved'.
--
-- Bosta settles some legs for exactly 0 (a fee-free Return to Origin, a COD equal to the fees) and
-- never sends a cashout for them — nothing is owed. SettlementRefreshJob marked two such legs
-- 'unresolved' (Snouts, 2026-10-08: RTO, deposited_amt 0.00, bosta_fees 0.00, cash cycle present,
-- no cashout). From B1 the job never does (SettlementSql.zeroCycle); this puts the legs it already
-- marked back to 'deposited', the status their settlement shows. Idempotent; touches only those rows.
UPDATE shipments
SET settlement_status = 'deposited'
WHERE settlement_status = 'unresolved'
  AND cash_cycle_id IS NOT NULL
  AND deposited_amt = 0
  AND cashout_txn_id IS NULL
  AND deposited_at IS NOT NULL;
