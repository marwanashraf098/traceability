-- ============================================================
-- V112 — Step 5c: return_request_status gains 'exchanged'
--
-- An exchange request finishes as 'exchanged' once the old item's piece got its final
-- disposition (restocked or damaged) — never refund_pending / refunded.
--
-- Its own migration: a value added by ALTER TYPE ... ADD VALUE cannot be used in the
-- same transaction that adds it (see V107), and Flyway runs each migration in one
-- transaction.
-- ============================================================

ALTER TYPE return_request_status ADD VALUE IF NOT EXISTS 'exchanged';
