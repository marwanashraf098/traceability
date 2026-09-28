-- ============================================================
-- V116 — Meta ad attribution captured at signup.
--
-- One row per tenant, written only by AuthRepository.createTenantWithOwner() (standalone
-- /signup), and only when the signup carried a Meta identifier (_fbp, _fbc, fbclid) or a
-- utm_* value. @tracedtech.com signups are never written. Values are validated and capped
-- in SignupAttribution before insert; no DB CHECKs here on purpose, so attribution data can
-- never fail a signup.
--
-- Read later by a server-side "ShopifyConnected" Conversions API job, which stamps
-- connected_event_sent_at. client_ip / client_user_agent exist only for that match.
-- Retention (design only, not built): clear client_ip and client_user_agent once
-- connected_event_sent_at is set, or 90 days after captured_at, whichever comes first.
--
-- ON DELETE CASCADE from tenants: ad data goes with the tenant (erasure, test cleanup).
-- ============================================================

CREATE TABLE tenant_ad_attribution (
    tenant_id               uuid        PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    fbp                     text,
    fbc                     text,
    fbclid                  text,
    utm_source              text,
    utm_medium              text,
    utm_campaign            text,
    utm_term                text,
    utm_content             text,
    client_ip               text,
    client_user_agent       text,
    captured_at             timestamptz NOT NULL DEFAULT now(),
    connected_event_sent_at timestamptz
);

ALTER TABLE tenant_ad_attribution ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_ad_attribution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_ad_attribution
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- V1's default privileges gave app_user full DML; the app only inserts, reads, and (later)
-- stamps connected_event_sent_at / clears the retention columns.
REVOKE DELETE, TRUNCATE ON tenant_ad_attribution FROM app_user;
