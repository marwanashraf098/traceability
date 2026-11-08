-- ============================================================
-- V225 — Shopify-native returns S5a: a return made on Traced's portal is also created as a Return in Shopify
-- (refund requests only in S5a; exchanges are S5b, fees S5c). Approved 2026-11-07.
--
-- The ONE writer of every column / row below is portal.ShopifyReturnSyncService (+ the owner switch in
-- ShopifyReturnSyncSettingsService). Shopify never restocks these items (process = NOT_RESTOCKED), Traced never
-- refunds through Shopify (no financialTransfer / refundShipping) and sends no exchange line.
--
-- tenants.shopify_returns_sync_enabled / _since — the owner's "Create returns in Shopify" switch (default OFF for
--   every tenant, existing and new); only requests DECIDED at/after _since are synced (no backfill). The switch is
--   visible only to tenants listed in TRACED_SHOPIFY_RETURNS_SYNC_TENANTS.
--
-- return_requests.shopify_sync_* — the create claim (claim-before-call, like booking_status):
--   NULL             not attempted
--   skipped          not synced, reason in shopify_sync_reason (permission, pre-connect, not fulfilled in Shopify, …)
--   pending          claimed; the one returnCreate call is in flight (> 15 min → failed_ambiguous)
--   created          shopify_return_gid / _name set
--   failed           definite failure (nothing was created) — retried up to 5 attempts
--   failed_ambiguous no confirmed answer — NEVER re-sent automatically; resolved by reading the order's returns for
--                    the request's marker ("Traced RR-…" in the line note), or by a person
--   needs_review     created, but a later read differs or Shopify ended it — a person checks
--   resolved         a person checked it in Shopify and dismissed it
-- shopify_sync_lines = what returnCreate was sent ([{lineItemGid, fulfillmentLineItemGid, quantity}], no PII) — the
--   read-back compares Shopify's return with exactly this.
--
-- shopify_return_actions — one claim row per later Shopify write on the created return: process (one unit, a
--   request item), remove (a return line's units that never came back), cancel / close (the return). Same states.
-- ============================================================

ALTER TABLE tenants
    ADD COLUMN shopify_returns_sync_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN shopify_returns_sync_since   timestamptz;

ALTER TABLE return_requests
    ADD COLUMN shopify_sync_status       text
        CONSTRAINT return_requests_shopify_sync_status_check
        CHECK (shopify_sync_status IN ('skipped', 'pending', 'created', 'failed', 'failed_ambiguous',
                                       'needs_review', 'resolved')),
    ADD COLUMN shopify_sync_reason       text,
    ADD COLUMN shopify_sync_error        text,
    ADD COLUMN shopify_sync_attempts     integer     NOT NULL DEFAULT 0,
    ADD COLUMN shopify_sync_attempted_at timestamptz,
    ADD COLUMN shopify_sync_verified_at  timestamptz,
    ADD COLUMN shopify_return_gid        text,
    ADD COLUMN shopify_return_name       text,
    ADD COLUMN shopify_sync_lines        jsonb,
    ADD CONSTRAINT return_requests_shopify_gid_needs_created
        CHECK (shopify_return_gid IS NULL OR shopify_sync_status IN ('created', 'needs_review', 'resolved'));

CREATE UNIQUE INDEX return_requests_shopify_return_gid_key
    ON return_requests (tenant_id, shopify_return_gid) WHERE shopify_return_gid IS NOT NULL;

CREATE TABLE shopify_return_actions (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenants(id),
    request_id      uuid        NOT NULL REFERENCES return_requests(id),
    action          text        NOT NULL CHECK (action IN ('process', 'remove', 'cancel', 'close')),
    unit_key        text        NOT NULL,
    request_item_id uuid        REFERENCES return_request_items(id),
    status          text        NOT NULL CHECK (status IN ('pending', 'applied', 'skipped', 'failed',
                                                           'failed_ambiguous', 'needs_review', 'resolved')),
    reason          text,
    error           text,
    attempts        integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    attempted_at    timestamptz,
    done_at         timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, request_id, action, unit_key)
);
CREATE INDEX shopify_return_actions_request_idx ON shopify_return_actions (tenant_id, request_id);

ALTER TABLE shopify_return_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_return_actions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_return_actions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON shopify_return_actions TO app_user;
REVOKE DELETE, TRUNCATE ON shopify_return_actions FROM app_user;
