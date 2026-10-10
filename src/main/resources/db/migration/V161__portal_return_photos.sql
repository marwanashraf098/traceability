-- ============================================================
-- V161 — Returns portal P3: customer photos of the items they return.
--
-- tenants.portal_require_photos: when true, every line of a portal request needs 1–3 photos.
--   DEFAULT false, so every existing store keeps today's flow; a NEW store gets true at signup
--   (AuthRepository.createTenantWithOwner — the one tenant-creation path, web and embedded).
--
-- portal_assets: kind 'photo' joins 'logo' (P1). Photo bytes live there, written only through
--   BinaryAssetStore after ImagePipeline (JPEG, ≤ 1600 px, no metadata) — the one pipeline / one
--   store rule (CLAUDE.md).
--
-- return_request_photos: one row per uploaded photo. Created by the PUBLIC upload (lookup token →
--   that token's order), claimed by the submission (request_id + the line's first item row).
--   Photo "bytes" = the asset row; removing them deletes the asset (asset_id → NULL via the FK) and
--   stamps redacted_at + redaction_reason, the row stays (retention 90 days after the request
--   ended, or a privacy request). Unclaimed uploads are deleted after 1 hour — app_user may DELETE
--   only unclaimed rows (restrictive policy), and may UPDATE only the claim / redaction columns.
-- ============================================================

ALTER TABLE tenants ADD COLUMN portal_require_photos boolean NOT NULL DEFAULT false;

ALTER TABLE portal_assets DROP CONSTRAINT portal_assets_kind_check;
ALTER TABLE portal_assets ADD CONSTRAINT portal_assets_kind_check CHECK (kind IN ('logo', 'photo'));

CREATE TABLE return_request_photos (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    order_id          uuid        NOT NULL REFERENCES orders(id),
    request_id        uuid        REFERENCES return_requests(id),
    item_id           uuid        REFERENCES return_request_items(id),
    asset_id          uuid,
    content_type      text        NOT NULL CHECK (content_type IN ('image/jpeg')),
    size_bytes        integer     NOT NULL CHECK (size_bytes > 0),
    width             integer     NOT NULL CHECK (width > 0),
    height            integer     NOT NULL CHECK (height > 0),
    sha256            text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at        timestamptz NOT NULL DEFAULT now(),
    claimed_at        timestamptz,
    redacted_at       timestamptz,
    redaction_reason  text        CHECK (redaction_reason IN ('retention', 'privacy')),
    CONSTRAINT return_request_photos_asset_fk
        FOREIGN KEY (asset_id, tenant_id) REFERENCES portal_assets (id, tenant_id)
        ON DELETE SET NULL (asset_id),
    CONSTRAINT return_request_photos_claim_shape
        CHECK ((request_id IS NULL) = (claimed_at IS NULL) AND (item_id IS NULL OR request_id IS NOT NULL)),
    CONSTRAINT return_request_photos_redaction_shape
        CHECK ((redacted_at IS NULL) = (redaction_reason IS NULL))
);

CREATE INDEX return_request_photos_request_idx ON return_request_photos (tenant_id, request_id);
CREATE INDEX return_request_photos_order_recent_idx ON return_request_photos (tenant_id, order_id, created_at);
CREATE INDEX return_request_photos_unclaimed_idx ON return_request_photos (tenant_id, created_at) WHERE request_id IS NULL;

ALTER TABLE return_request_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_request_photos FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_request_photos
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- A claimed photo is a record of the request: only an unclaimed upload can ever be deleted.
CREATE POLICY delete_only_unclaimed ON return_request_photos AS RESTRICTIVE FOR DELETE
    USING (request_id IS NULL);

REVOKE UPDATE, TRUNCATE ON return_request_photos FROM app_user;
GRANT UPDATE (request_id, item_id, claimed_at, asset_id, redacted_at, redaction_reason)
    ON return_request_photos TO app_user;
