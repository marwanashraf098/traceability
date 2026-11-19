-- V238 — Bazar POS slice B6b (approved 2026-11-19): seller invites (WhatsApp link + PIN). No Shopify write.
--   - bazar_event_sellers: the WORKERS an owner / manager added to one event ("Add seller"). One row per event + user;
--     "Remove seller" stamps removed_at / removed_by (never deleted — app_user has no DELETE); adding the same worker
--     again clears it. A removed seller can't sell at that event (BazarSellers.removedSql, read by every sell-side
--     permission check) and their sessions are revoked when removed ("log out of all devices"). A worker who was never
--     added keeps the pre-B6b rule (their role in the event's seller_roles).
--   - bazar_seller_invites: append-only (INSERT / SELECT): one row per sign-in link issued to a seller — who invited
--     whom, when, until when. token_hash = the SHA-256 the link's token is stored under in magic_link_tokens (consumed
--     only through hatch #6 consume_magic_link — no new hatch); after the consume it tells which event the seller lands
--     on. Also the per-tenant issuance rate limit (BazarSellerService.INVITES_PER_HOUR).
-- RLS NULLIF + FORCE on both.

CREATE TABLE bazar_event_sellers (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    event_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES users(id),
    added_by    uuid        NOT NULL REFERENCES users(id),
    added_at    timestamptz NOT NULL DEFAULT now(),
    removed_at  timestamptz,
    removed_by  uuid        REFERENCES users(id),
    -- true on the row whose "Add seller" CREATED this user (a new worker from a name); false when an existing worker
    -- was picked. A user created this way is turned off (UserService.deactivate) when removed from their last event
    -- that hasn't ended; a picked team member only leaves the event.
    created_user boolean    NOT NULL DEFAULT false,
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    UNIQUE (event_id, user_id),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);
CREATE INDEX bazar_event_sellers_user_idx ON bazar_event_sellers (user_id);

ALTER TABLE bazar_event_sellers ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_event_sellers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_event_sellers
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_event_sellers FROM app_user;

CREATE TABLE bazar_seller_invites (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    event_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES users(id),
    invited_by  uuid        NOT NULL REFERENCES users(id),
    token_hash  text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    CHECK (expires_at > created_at),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);
CREATE INDEX bazar_seller_invites_tenant_time_idx ON bazar_seller_invites (tenant_id, created_at);
CREATE INDEX bazar_seller_invites_event_user_idx ON bazar_seller_invites (event_id, user_id, created_at);

ALTER TABLE bazar_seller_invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_seller_invites FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_seller_invites
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON bazar_seller_invites FROM app_user;
