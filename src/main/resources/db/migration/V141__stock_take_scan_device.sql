-- ============================================================
-- V141 — Q1b: which device a stock-take count came from.
--
-- The phone scanner (Q1) now feeds stock take: a count scanned on a paired phone is
-- recorded as 'phone', a keyboard / HID scan as 'hardware'. Decided server-side only — the
-- scan endpoint marks a scan 'phone' when the request names a relay event that belongs to
-- the caller's own live pairing, in this tenant, for the same code (PhoneScanSource); a
-- client-sent "source" is never trusted. The review screen shows "N of M scans came from a
-- phone" before Finalize (informational only).
--
-- stock_take_scans.source already exists with another meaning ('scan' vs 'manager_found'),
-- hence the separate column name. Existing rows are hardware scans by definition.
-- Transfers need no column: their scans record {"via":"phone"} in piece_events.metadata.
-- ============================================================

ALTER TABLE stock_take_scans ADD COLUMN scan_device text NOT NULL DEFAULT 'hardware'
    CHECK (scan_device IN ('hardware', 'phone'));
