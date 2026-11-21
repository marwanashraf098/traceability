-- V240 — incident 2026-11-21: piece_events had no index on shipment_id. ExceptionService.detectShippedWithoutScan
-- asks "did Traced scan any piece onto this waybill" (EXISTS piece_events WHERE shipment_id = …) for the old and the
-- replacing leg of every superseded waybill; without an index each probe read the whole table (≈ 72k rows in prod).
-- Partial: most piece events carry no shipment.
--
-- Non-transactional (V240__piece_events_shipment_index.sql.conf): CREATE INDEX CONCURRENTLY never blocks piece_events
-- writes (V179 / V190 / V217 pattern; Flyway's lock is session-level, spring.flyway.postgresql.transactional-lock:
-- false). Re-runnable after a failure (`flyway repair`): a leftover (possibly INVALID) build is dropped first.

DROP INDEX CONCURRENTLY IF EXISTS piece_events_shipment_idx;

CREATE INDEX CONCURRENTLY piece_events_shipment_idx
    ON piece_events (shipment_id)
    WHERE shipment_id IS NOT NULL;
