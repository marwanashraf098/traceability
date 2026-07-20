-- FR-18: Connection-anchored order ingest cutoff.
-- NULL  = no cutoff (all existing stores like Jumi — zero behavioral change).
-- Non-null = hard floor: set once at first connect, never overwritten on reconnect.
-- Every ingest path checks this value before writing an order row.
ALTER TABLE stores ADD COLUMN orders_ingest_from TIMESTAMPTZ DEFAULT NULL;
