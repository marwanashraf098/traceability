-- FR-2.6: Privileged-action audit log.
-- Append-only by design: app_user receives INSERT + SELECT only.
-- Audit entries are never mutated or deleted after creation.

CREATE TABLE audit_log (
    id            bigserial    PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants(id),
    actor_user_id uuid         REFERENCES users(id),   -- null for system-generated entries
    action        text         NOT NULL,               -- e.g. 'user_create', 'convert_to_self_pickup'
    target_type   text,                                -- e.g. 'user', 'order'
    target_id     text,                                -- UUID or other identifier as string
    metadata      jsonb,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit_log
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Append-only: V1 ALTER DEFAULT PRIVILEGES grants UPDATE+DELETE to app_user on every new table.
-- Revoke those here so audit_log is truly INSERT+SELECT only.
GRANT SELECT, INSERT ON audit_log TO app_user;
REVOKE UPDATE, DELETE ON audit_log FROM app_user;
GRANT USAGE, SELECT ON SEQUENCE audit_log_id_seq TO app_user;

CREATE INDEX audit_log_tenant_created ON audit_log (tenant_id, created_at DESC);
CREATE INDEX audit_log_actor          ON audit_log (actor_user_id) WHERE actor_user_id IS NOT NULL;
CREATE INDEX audit_log_action         ON audit_log (tenant_id, action);
