-- ============================================================
-- V107 — Returns Step 4d-1: return_request_status gains 'closed'
--
-- 'closed' = the request ended without a refund being recorded in Traced: the merchant
-- closed it (no_refund / other), or nothing ever arrived and the rest was marked
-- not coming. Distinct from 'rejected' (a decision on a requested return) and
-- 'cancelled' (withdrawn before anything happened).
--
-- Its own migration: a value added by ALTER TYPE ... ADD VALUE cannot be used in the
-- same transaction that adds it, and Flyway runs each migration in one transaction.
-- ============================================================

ALTER TYPE return_request_status ADD VALUE IF NOT EXISTS 'closed';
