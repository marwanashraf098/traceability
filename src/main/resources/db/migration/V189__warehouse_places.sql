-- V189 — Warehouse places S1 (approved 2026-10-26): zones / racks / shelves / white boxes inside a location.
--
-- A place ("bin") belongs to ONE location for ever (bins.location_id never changes). Zone → rack → shelf; a
-- white box sits directly in a zone or on a shelf, and may move zone ↔ shelf inside the same location. A piece
-- can be placed on a SHELF or in a BOX only, and only while it is available / on_hold / damaged. Placing a piece
-- is not a status change and never touches Shopify. Nothing in S1 sets pieces.bin_id (the put-away writer is S2).
--   - tenants.places_enabled: per-tenant capability (default off, set by ops — no merchant switch).
--   - bins: the tree. Allowed parents are declarative: parent_kind + CHECK of the allowed pairs + a composite FK
--     (parent_id, parent_kind, location_id) → bins (id, kind, location_id), so a parent is always of an allowed
--     kind AND in the same location. code = what people read (renameable, unique per location, case-insensitive);
--     scan_code = what the sticker's barcode holds ('Q' + 6+ digits, from bin_counters, never changes).
--   - bin_events: created / renamed / moved / archived / purpose_changed (app_user INSERT / SELECT only).
--   - bin_counters: the per-tenant scan_code sequence (PlaceCodes.claimScanCodes).
--   - pieces.bin_id + FK (bin_id, current_location_id) → bins (id, location_id): a piece's place is in its
--     current location. NOT VALID here (no scan / long lock on pieces), VALIDATEd in V191.
--   - piece_events.from_bin_id / to_bin_id: for the S2 place events (NOT VALID FKs, VALIDATEd in V191).
--   - pieces_bin_guard (BEFORE UPDATE, SECURITY INVOKER — not a hatch): clears bin_id when the location changes
--     or the new status isn't placeable (every departure, whichever code path moves the piece); refuses a place
--     that isn't a shelf / box or is archived. It only ever touches bin_id; InventoryLedger stays the only writer
--     of piece status.
-- The partial index on pieces.bin_id is V190 (CONCURRENTLY).

-- 1. The capability.
ALTER TABLE tenants ADD COLUMN places_enabled boolean NOT NULL DEFAULT false;

-- 2. A composite key on locations so a bin can name (location, tenant) — a bin can never point at another
--    tenant's location (FK checks don't go through RLS).
CREATE UNIQUE INDEX locations_id_tenant_uniq ON locations (id, tenant_id);

-- 3. Places.
CREATE TABLE bins (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    location_id  uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('zone', 'rack', 'shelf', 'box')),
    parent_id    uuid,
    parent_kind  text,
    code         text        NOT NULL,
    scan_code    text        NOT NULL CHECK (scan_code ~ '^Q[0-9]{6,}$'),
    name         text        CHECK (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 60),
    purpose      text        CHECK (purpose IN ('damaged')),
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid        REFERENCES users(id),
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id),
    UNIQUE (id, tenant_id),
    UNIQUE (id, location_id),
    UNIQUE (id, kind, location_id),
    FOREIGN KEY (parent_id, parent_kind, location_id) REFERENCES bins (id, kind, location_id),
    -- Latin letters, digits, '.', '_' and '-' (scanner- and search-friendly; a place's own words go in name).
    -- Never shaped like a piece code or a sticker barcode, so a typed code can't be mistaken for either.
    CONSTRAINT bins_code_shape CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$' AND code !~* '^[PQ][0-9]{6,}$'),
    CONSTRAINT bins_parent_allowed CHECK (
        (kind = 'zone'  AND parent_id IS NULL AND parent_kind IS NULL)
     OR (kind = 'rack'  AND parent_id IS NOT NULL AND parent_kind = 'zone')
     OR (kind = 'shelf' AND parent_id IS NOT NULL AND parent_kind = 'rack')
     OR (kind = 'box'   AND parent_id IS NOT NULL AND parent_kind IN ('zone', 'shelf'))),
    CONSTRAINT bins_purpose_kind CHECK (purpose IS NULL OR kind IN ('zone', 'box'))
);

CREATE UNIQUE INDEX bins_code_uniq      ON bins (tenant_id, location_id, upper(code));
CREATE UNIQUE INDEX bins_scan_code_uniq ON bins (tenant_id, scan_code);
CREATE INDEX bins_tree_idx              ON bins (tenant_id, location_id, parent_id);

ALTER TABLE bins ENABLE ROW LEVEL SECURITY;
ALTER TABLE bins FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bins
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A place is archived, never deleted; its location, kind and scan code never change.
REVOKE UPDATE, DELETE, TRUNCATE ON bins FROM app_user;
GRANT UPDATE (code, name, parent_id, parent_kind, purpose, archived_at) ON bins TO app_user;

-- 4. What happened to each place.
CREATE TABLE bin_events (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenants(id),
    bin_id          uuid        NOT NULL,
    event_type      text        NOT NULL CHECK (event_type IN ('created', 'renamed', 'moved', 'archived', 'purpose_changed')),
    actor_user_id   uuid        REFERENCES users(id),
    from_parent_id  uuid        REFERENCES bins(id),
    to_parent_id    uuid        REFERENCES bins(id),
    metadata        jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (bin_id, tenant_id) REFERENCES bins (id, tenant_id)
);
CREATE INDEX bin_events_bin_idx ON bin_events (tenant_id, bin_id, occurred_at DESC);

ALTER TABLE bin_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE bin_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bin_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON bin_events FROM app_user;

-- 5. The per-tenant sticker barcode sequence (same claim shape as piece_counters).
CREATE TABLE bin_counters (
    tenant_id   uuid   NOT NULL PRIMARY KEY REFERENCES tenants(id),
    last_value  bigint NOT NULL DEFAULT 0
);
ALTER TABLE bin_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE bin_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bin_counters
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bin_counters FROM app_user;

-- 6. A piece's place (metadata-only column add; FK NOT VALID — checked for every new write, existing rows are
--    all NULL and are validated in V191 without a long lock).
ALTER TABLE pieces ADD COLUMN bin_id uuid;
ALTER TABLE pieces ADD CONSTRAINT pieces_bin_same_location
    FOREIGN KEY (bin_id, current_location_id) REFERENCES bins (id, location_id) NOT VALID;

ALTER TABLE piece_events ADD COLUMN from_bin_id uuid, ADD COLUMN to_bin_id uuid;
ALTER TABLE piece_events ADD CONSTRAINT piece_events_from_bin_fk FOREIGN KEY (from_bin_id) REFERENCES bins (id) NOT VALID;
ALTER TABLE piece_events ADD CONSTRAINT piece_events_to_bin_fk   FOREIGN KEY (to_bin_id)   REFERENCES bins (id) NOT VALID;

-- 7. The departure rule, wherever the piece is moved from (InventoryLedger.transition for status; the raw
--    current_location_id UPDATEs in TransferService / ReturnService / ReturnSessionService /
--    StockTakeReconciliationService for location). BEFORE: the FK above is checked after this has cleared bin_id.
CREATE FUNCTION pieces_bin_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE
    b_kind     text;
    b_archived timestamptz;
BEGIN
    IF NEW.current_location_id IS DISTINCT FROM OLD.current_location_id
       OR NEW.status::text NOT IN ('available', 'on_hold', 'damaged') THEN
        NEW.bin_id := NULL;
    -- Coming back from a status that holds no place (restocked return, found, back from a transfer): the piece
    -- arrives with no place — unless this same UPDATE is the one putting it somewhere.
    ELSIF NEW.status IS DISTINCT FROM OLD.status
          AND OLD.status::text NOT IN ('available', 'on_hold', 'damaged')
          AND NEW.bin_id IS NOT DISTINCT FROM OLD.bin_id THEN
        NEW.bin_id := NULL;
    END IF;
    IF NEW.bin_id IS NOT NULL AND NEW.bin_id IS DISTINCT FROM OLD.bin_id THEN
        -- The FK (bin_id, current_location_id) is MATCH SIMPLE: a NULL location would skip it.
        IF NEW.current_location_id IS NULL THEN
            RAISE EXCEPTION 'a piece with no location has no place'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'pieces_bin_needs_location';
        END IF;
        -- FOR SHARE: an archive of this place (FOR UPDATE on the same row) waits for this transaction, then sees
        -- the piece and refuses; an archive that committed first is seen here.
        SELECT kind, archived_at INTO b_kind, b_archived FROM bins WHERE id = NEW.bin_id FOR SHARE;
        IF b_kind IS NULL THEN
            RAISE EXCEPTION 'place % not found', NEW.bin_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF b_kind NOT IN ('shelf', 'box') THEN
            RAISE EXCEPTION 'pieces go on a shelf or in a box, not a %', b_kind
                USING ERRCODE = 'check_violation', CONSTRAINT = 'pieces_bin_holds_pieces';
        END IF;
        IF b_archived IS NOT NULL THEN
            RAISE EXCEPTION 'place % is archived', NEW.bin_id
                USING ERRCODE = 'check_violation', CONSTRAINT = 'pieces_bin_not_archived';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pieces_bin_guard
    BEFORE UPDATE OF status, current_location_id, bin_id ON pieces
    FOR EACH ROW EXECUTE FUNCTION pieces_bin_guard();
