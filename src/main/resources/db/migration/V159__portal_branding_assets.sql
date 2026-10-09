-- ============================================================
-- V159 — Returns portal P1: uploaded logo + portal font, and a per-IP lookup throttle.
--
-- portal_assets: binary assets a tenant uploads, stored in Postgres (bytea) behind the
--   BinaryAssetStore interface — no object store, no new vendor or secret. Rows are
--   immutable: a replacement is a NEW row (the tenant row is pointed at it, then the old
--   one is deleted in the same transaction). kind 'logo' only for now (P3 adds photos).
--   bytes are always the re-encoded output of ImagePipeline (metadata stripped), never the
--   upload as sent. sha256 is the hex digest of bytes — the public logo endpoint's ETag,
--   readable without loading bytes.
--
-- tenants.portal_logo_asset_id: the current logo. Composite FK (asset id, tenant id) so a
--   tenant can only ever point at its OWN asset; deleting the asset nulls only this column
--   (ON DELETE SET NULL (col), PG15+), so a reset script that deletes a tenant's rows never
--   trips on it. Precedence on the portal: this asset > portal_logo_url (V103, unchanged)
--   > the store-name wordmark.
--
-- tenants.portal_font: the portal's font family, both languages. Self-hosted (@fontsource).
--
-- portal_lookup_attempts.ip_hash: HMAC of the client IP (no raw IP stored) — the per-IP
--   failure throttle counts across order keys, alongside the existing per-order one.
-- ============================================================

CREATE TABLE portal_assets (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    kind          text        NOT NULL CHECK (kind IN ('logo')),
    content_type  text        NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    bytes         bytea       NOT NULL,
    size_bytes    integer     NOT NULL CHECK (size_bytes > 0 AND size_bytes = octet_length(bytes)),
    width         integer     NOT NULL CHECK (width > 0),
    height        integer     NOT NULL CHECK (height > 0),
    sha256        text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT portal_assets_id_tenant_unique UNIQUE (id, tenant_id)
);

CREATE INDEX portal_assets_tenant_kind_idx ON portal_assets (tenant_id, kind);

ALTER TABLE portal_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_assets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portal_assets
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Immutable rows: app_user inserts, reads and deletes; never edits.
REVOKE UPDATE, TRUNCATE ON portal_assets FROM app_user;

ALTER TABLE tenants
    ADD COLUMN portal_logo_asset_id uuid,
    ADD COLUMN portal_font text NOT NULL DEFAULT 'cairo'
        CONSTRAINT tenants_portal_font_family
        CHECK (portal_font IN ('cairo', 'tajawal', 'ibm-plex-sans-arabic', 'almarai', 'readex-pro')),
    ADD CONSTRAINT tenants_portal_logo_asset_fk
        FOREIGN KEY (portal_logo_asset_id, id) REFERENCES portal_assets (id, tenant_id)
        ON DELETE SET NULL (portal_logo_asset_id);

ALTER TABLE portal_lookup_attempts ADD COLUMN ip_hash text;

CREATE INDEX portal_lookup_attempts_ip_idx
    ON portal_lookup_attempts (tenant_id, ip_hash, attempted_at)
    WHERE success = false AND ip_hash IS NOT NULL;
