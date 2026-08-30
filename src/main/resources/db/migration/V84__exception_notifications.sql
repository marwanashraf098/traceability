-- V84 — exception_notifications ledger (hybrid exception notifications: immediate
-- CRITICAL/HIGH sweep + daily digest).
--
-- Dedup ledger for outbound exception emails, keyed by (tenant_id, exception_type,
-- subject_key, channel) — mirrors exception_resolutions (V11) exactly, one row per
-- notification actually sent for a given exception instance on a given channel.
-- 'immediate' rows are at-most-once-ever (v1): once an exception has an immediate
-- row, it never re-alerts even if it recurs. 'digest' rows mark "already itemized
-- in a daily summary" so the next digest's "new since last summary" section only
-- lists exceptions with no prior digest row for the same key.
CREATE TABLE exception_notifications (
    id             bigserial    PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants(id),
    exception_type text         NOT NULL,
    subject_key    text         NOT NULL,
    channel        text         NOT NULL,          -- 'immediate' | 'digest'
    notified_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, exception_type, subject_key, channel)
);

ALTER TABLE exception_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON exception_notifications
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
