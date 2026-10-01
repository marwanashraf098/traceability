-- ============================================================
-- V125 — Pick & Pack S2: batch waybill printing.
--
-- pack_print_batches      — one row per "Print waybills" action that produced a PDF.
-- pack_print_batch_items  — the shipments actually printed in that batch, in PDF order.
--
-- A shipment is "printed" when it appears in any batch item. Shipments Bosta excluded
-- or answered with its email-instead-of-PDF message are never recorded.
--
-- Both tables: tenant_id + tenant_isolation RLS (NULLIF pattern) + FORCE, app_user
-- SELECT/INSERT only (a batch is history — no UPDATE/DELETE path in the app).
--
-- batch_no is per tenant, 1..n. PackPrintBatchService allocates it under a per-tenant
-- pg_advisory_xact_lock (MAX+1 inside the lock); UNIQUE (tenant_id, batch_no) is the
-- backstop.
--
-- order_id / shipment_id are ON DELETE CASCADE: production never deletes orders or
-- shipments; the only deleters are DemoSeeder.reseed() (demo tenant fixture reload) and
-- test cleanups, which then drop the matching batch items with them. Batch header rows
-- reference only tenants/users, which reseed preserves.
-- ============================================================

CREATE TABLE pack_print_batches (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants(id),
    batch_no       integer     NOT NULL CHECK (batch_no > 0),
    printed_by     uuid        REFERENCES users(id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    paper          text        NOT NULL CHECK (paper IN ('A6', 'A4')),
    sort           text        NOT NULL CHECK (sort IN ('oldest', 'newest')),
    scope          text        NOT NULL CHECK (scope IN ('new', 'all')),
    waybill_count  integer     NOT NULL CHECK (waybill_count > 0),
    order_guaranteed boolean   NOT NULL,
    UNIQUE (tenant_id, batch_no)
);

CREATE INDEX pack_print_batches_tenant_created_idx
    ON pack_print_batches (tenant_id, created_at DESC);

ALTER TABLE pack_print_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_print_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pack_print_batches
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE TABLE pack_print_batch_items (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id         uuid        NOT NULL REFERENCES pack_print_batches(id),
    tenant_id        uuid        NOT NULL REFERENCES tenants(id),
    order_id         uuid        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    shipment_id      uuid        NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    tracking_number  text        NOT NULL,
    position         integer     NOT NULL CHECK (position > 0),
    UNIQUE (batch_id, shipment_id)
);

-- "Has this shipment ever been printed?" (queue's awb_printed, getOrder's awbPrinted,
-- the 'new' scope) — looked up by shipment.
CREATE INDEX pack_print_batch_items_tenant_shipment_idx
    ON pack_print_batch_items (tenant_id, shipment_id);
CREATE INDEX pack_print_batch_items_batch_idx
    ON pack_print_batch_items (batch_id, position);

ALTER TABLE pack_print_batch_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_print_batch_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pack_print_batch_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE ALL ON pack_print_batches, pack_print_batch_items FROM app_user;
GRANT SELECT, INSERT ON pack_print_batches, pack_print_batch_items TO app_user;
