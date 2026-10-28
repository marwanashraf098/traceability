-- V191 — Warehouse places S1: validate the NOT VALID foreign keys V189 added. Its own migration, so each runs
-- in a separate transaction from the ADD: VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on pieces /
-- piece_events (reads and writes carry on) and only scans; every existing row has NULL bin columns.

ALTER TABLE pieces       VALIDATE CONSTRAINT pieces_bin_same_location;
ALTER TABLE piece_events VALIDATE CONSTRAINT piece_events_from_bin_fk;
ALTER TABLE piece_events VALIDATE CONSTRAINT piece_events_to_bin_fk;
