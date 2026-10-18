-- ============================================================
-- V170 — Merged-PDF slice B: background waybill print jobs.
--
-- One row per "Print waybills" (kind 'print', up to 490 waybills) or batch reprint (kind
-- 'reprint') run in the background by PrintJobRunner: Bosta is asked in requests of ≤ 49,
-- progress is saved after each request, the merged PDF (pick-list order) is stored here and
-- downloaded as application/pdf. A print writes its pack_print_batches row only once the PDF
-- exists (one job = one batch); a reprint writes none, as before.
--
-- status: queued (waiting for one of the 2 server-wide print slots) → running → done |
-- partial (stopped early — Bosta busy / API key refused — with labels) | failed (nothing
-- printed, or swept: not updated for 10 minutes; any labels saved stay downloadable).
-- result: per-waybill outcomes ({items: [...]}) with slice 1 / A reason codes + the message.
-- pdf: the merged PDF; PrintJobSweeper sets pdf / pdf_bytes to NULL after expires_at (24 h
-- after the job ended). No row is ever deleted by the app.
--
-- One queued-or-running job per tenant (partial UNIQUE) — a second start answers 409 with
-- that job's id. RLS: tenant_isolation (NULLIF pattern) + FORCE; app_user SELECT / INSERT /
-- UPDATE (no DELETE). The sweeper reads tenant ids only on the owner pool (the
-- RefundDetailsPurgeJob pattern) and does every write per tenant under RLS.
-- ============================================================

CREATE TABLE pack_print_jobs (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    requested_by      uuid        REFERENCES users(id),
    kind              text        NOT NULL CHECK (kind IN ('print', 'reprint')),
    scope             text        CHECK (scope IN ('new', 'all')),
    sort              text        CHECK (sort IN ('oldest', 'newest')),
    paper             text        CHECK (paper IN ('A6', 'A4')),
    reprint_batch_id  uuid        REFERENCES pack_print_batches(id),
    status            text        NOT NULL DEFAULT 'queued'
                                  CHECK (status IN ('queued', 'running', 'done', 'partial', 'failed')),
    total             integer     NOT NULL DEFAULT 0 CHECK (total >= 0),
    done_count        integer     NOT NULL DEFAULT 0 CHECK (done_count >= 0),
    candidate_count   integer     NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    remaining_count   integer     NOT NULL DEFAULT 0 CHECK (remaining_count >= 0),
    result            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    pdf               bytea,
    pdf_bytes         integer,
    order_guaranteed  boolean,
    batch_id          uuid        REFERENCES pack_print_batches(id),
    error_code        text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    expires_at        timestamptz,
    CHECK ((kind = 'print') = (scope IS NOT NULL AND sort IS NOT NULL AND paper IS NOT NULL)),
    CHECK ((kind = 'reprint') = (reprint_batch_id IS NOT NULL)),
    CHECK ((pdf IS NULL) = (pdf_bytes IS NULL))
);

-- One queued-or-running job per tenant.
CREATE UNIQUE INDEX ux_pack_print_jobs_active_per_tenant
    ON pack_print_jobs (tenant_id) WHERE status IN ('queued', 'running');
-- The latest job (resume after navigation).
CREATE INDEX pack_print_jobs_tenant_created_idx
    ON pack_print_jobs (tenant_id, created_at DESC);
-- The sweeper: stuck jobs and expired PDFs.
CREATE INDEX pack_print_jobs_active_updated_idx
    ON pack_print_jobs (updated_at) WHERE status IN ('queued', 'running');
CREATE INDEX pack_print_jobs_pdf_expiry_idx
    ON pack_print_jobs (expires_at) WHERE pdf IS NOT NULL;

ALTER TABLE pack_print_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_print_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pack_print_jobs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE ALL ON pack_print_jobs FROM app_user;
GRANT SELECT, INSERT, UPDATE ON pack_print_jobs TO app_user;
