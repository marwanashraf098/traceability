-- ============================================================
-- V220 — Onboarding O1: the progress model's stored part (2026-11-07).
--
-- Everything that can be derived from existing rows is derived at read time
-- (OnboardingProgressService). Only what can't be derived is stored here.
--
-- 1. tenants.onboarding_prefs — per-tenant onboarding choices. Keys (the ONE writer,
--    OnboardingProgressService.updatePrefs, accepts exactly these):
--      card              'full' | 'slim' | 'hidden'   — the Overview "Get started" card
--      skipped           array of 'courier' | 'test_label' — setup steps skipped for now
--      pickPackChosen    boolean — the once-only Pick & Pack mode chooser was answered
--      testLabelPrinted  boolean — a first label print (not logged anywhere else)
--      labelingPace      integer — reserved for O7 (labeling plan)
--    app_user already has UPDATE on tenants (RLS: own row) — no grant change.
--
--    Backfill:
--      card              'hidden' when onboarding_dismissed_at is set OR the tenant has a
--                        finalized, non-reversed receipt; 'full' for every other tenant
--      pickPackChosen    true when the tenant has packed in Pick & Pack at least once (a 'pack' piece
--                        event that isn't the Lookup exchange dispatch's — same predicate as
--                        OnboardingProgressService.REAL_PACK_SQL) OR pick_pack_mode = 'waybill_scan'
--      testLabelPrinted  true when the tenant has a label_reprints row OR a finalized
--                        print_stick receipt
--
-- 2. user_ui_state — per-user UI state: first-time tours done (or skipped) and sidebar pages
--    opened. One row per user (PK tenant_id, user_id). Tenant RLS (NULLIF pattern) + FORCE;
--    the user scoping is the writer's WHERE (the caller's own user id from the JWT).
--    app_user: SELECT / INSERT, UPDATE of the state columns only — no DELETE, no TRUNCATE.
--    user_id references users through a composite (id, tenant_id) key, so a row can never
--    name another tenant's user; users gets that UNIQUE key here (id is already unique, so
--    it can't fail on existing data; users is a small table).
-- ============================================================

ALTER TABLE tenants ADD COLUMN onboarding_prefs jsonb NOT NULL DEFAULT '{}'::jsonb;

UPDATE tenants t SET onboarding_prefs = jsonb_build_object(
    'card', CASE
        WHEN t.onboarding_dismissed_at IS NOT NULL
          OR EXISTS (SELECT 1 FROM receipts r
                      WHERE r.tenant_id = t.id AND r.finalized_at IS NOT NULL AND r.status <> 'reversed')
        THEN 'hidden' ELSE 'full' END,
    'pickPackChosen',
        t.pick_pack_mode = 'waybill_scan'
        OR EXISTS (SELECT 1 FROM piece_events e WHERE e.tenant_id = t.id AND e.event_type = 'pack'
                  AND COALESCE(e.metadata->>'event', '') <> 'lookup_exchange_dispatch'),
    'testLabelPrinted',
        EXISTS (SELECT 1 FROM label_reprints lr WHERE lr.tenant_id = t.id)
        OR EXISTS (SELECT 1 FROM receipts r
                    WHERE r.tenant_id = t.id AND r.finalized_at IS NOT NULL AND r.mode = 'print_stick'));

COMMENT ON COLUMN tenants.onboarding_prefs IS
    'Onboarding O1 (V220): card / skipped / pickPackChosen / testLabelPrinted / labelingPace. '
    'Written only by OnboardingProgressService.updatePrefs (whitelisted keys).';

ALTER TABLE users ADD CONSTRAINT users_id_tenant_key UNIQUE (id, tenant_id);

CREATE TABLE user_ui_state (
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    user_id     uuid        NOT NULL,
    tours_done  text[]      NOT NULL DEFAULT '{}',
    pages_seen  text[]      NOT NULL DEFAULT '{}',
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES users (id, tenant_id)
);

ALTER TABLE user_ui_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_ui_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_ui_state
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE ALL ON user_ui_state FROM app_user;
GRANT SELECT, INSERT ON user_ui_state TO app_user;
-- UPDATE only on the state columns: a row's tenant / user never change.
GRANT UPDATE (tours_done, pages_seen, updated_at) ON user_ui_state TO app_user;

COMMENT ON TABLE user_ui_state IS
    'Onboarding O1 (V220): per-user first-time tours done / sidebar pages opened. Written only by '
    'UserUiStateService (whitelisted ids, idempotent append). No DELETE for app_user.';
