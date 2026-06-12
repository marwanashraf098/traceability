-- ============================================================
-- V1 — Full schema: all tables, indexes, RLS, role grants
-- ============================================================

-- ---- enums --------------------------------------------------

CREATE TYPE plan_type AS ENUM ('trial','starter','growth','scale');
CREATE TYPE tenant_status AS ENUM ('trial','active','suspended');
CREATE TYPE user_role AS ENUM ('owner','manager','worker');
CREATE TYPE store_platform AS ENUM ('shopify');
CREATE TYPE store_status AS ENUM ('connected','disconnected','error');
CREATE TYPE courier_provider AS ENUM ('bosta');
CREATE TYPE courier_account_status AS ENUM ('active','disconnected','error');
CREATE TYPE location_type AS ENUM
    ('warehouse','branch','showroom','retail','event','vendor');
CREATE TYPE piece_status AS ENUM (
    'available','reserved','packed','awaiting_pickup',
    'with_courier','delivered','return_in_transit',
    'return_pending_inspection','damaged','lost','destroyed');
CREATE TYPE order_payment_method AS ENUM ('cod','prepaid');
CREATE TYPE order_status AS ENUM (
    'new','confirmed','ready_to_pick','picking','packed',
    'awaiting_pickup','with_courier','delivered',
    'returning','returned','lost','cancelled');
CREATE TYPE allocation_status AS ENUM ('active','packed','released');
CREATE TYPE shipment_internal_state AS ENUM (
    'created','with_courier','delivered','returning','returned',
    'lost','exception','terminated','cancelled');
CREATE TYPE webhook_source AS ENUM ('shopify','bosta');
CREATE TYPE webhook_status AS ENUM ('pending','processed','failed');

-- ---- application role ----------------------------------------
-- App connects as this role; it has no BYPASSRLS, so RLS is
-- always enforced. Tenant GUC set per-request before any query.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        CREATE ROLE app_user LOGIN;
    END IF;
END $$;

-- ---- tables (ordered to satisfy FK dependencies) ------------

CREATE TABLE tenants (
    id         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text          NOT NULL,
    plan       plan_type     NOT NULL DEFAULT 'trial',
    status     tenant_status NOT NULL DEFAULT 'trial',
    created_at timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            uuid      PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid      NOT NULL REFERENCES tenants(id),
    name          text      NOT NULL,
    email         text,
    phone         text,
    password_hash text,
    role          user_role NOT NULL,
    pin_code      text,
    active        boolean   NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    id                     uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid           NOT NULL REFERENCES tenants(id),
    platform               store_platform NOT NULL DEFAULT 'shopify',
    shop_domain            text           NOT NULL,
    access_token_encrypted text,
    webhook_secret         text,
    status                 store_status   NOT NULL DEFAULT 'disconnected',
    last_sync_at           timestamptz,
    UNIQUE (shop_domain)
);

CREATE TABLE courier_accounts (
    id                     uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid                   NOT NULL REFERENCES tenants(id),
    provider               courier_provider       NOT NULL DEFAULT 'bosta',
    api_key_encrypted      text,
    default_pickup_address jsonb,
    business_ref           text,
    status                 courier_account_status NOT NULL DEFAULT 'disconnected'
);

CREATE TABLE locations (
    id         uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid          NOT NULL REFERENCES tenants(id),
    name       text          NOT NULL,
    type       location_type NOT NULL DEFAULT 'warehouse',
    is_default boolean       NOT NULL DEFAULT false
);

CREATE TABLE products (
    id          uuid  PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid  NOT NULL REFERENCES tenants(id),
    store_id    uuid  NOT NULL REFERENCES stores(id),
    external_id text  NOT NULL,
    title       text  NOT NULL,
    status      text  NOT NULL DEFAULT 'active',
    raw         jsonb,
    UNIQUE (store_id, external_id)
);

CREATE TABLE variants (
    id          uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid    NOT NULL REFERENCES tenants(id),
    product_id  uuid    NOT NULL REFERENCES products(id),
    external_id text    NOT NULL,
    sku         text,
    title       text    NOT NULL,
    upc_barcode text,
    price       numeric(10,2),
    raw         jsonb,
    UNIQUE (product_id, external_id)
);

CREATE TABLE receipts (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    reference     text,
    supplier_name text,
    received_by   uuid        REFERENCES users(id),
    location_id   uuid        REFERENCES locations(id),
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- orders before pieces so pieces.current_order_id FK resolves cleanly
CREATE TABLE orders (
    id             uuid                 PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid                 NOT NULL REFERENCES tenants(id),
    store_id       uuid                 NOT NULL REFERENCES stores(id),
    external_id    text                 NOT NULL,
    number         text,
    customer_name  text,
    customer_phone text,
    address        jsonb,
    payment_method order_payment_method,
    cod_amount     numeric(10,2),
    status         order_status         NOT NULL DEFAULT 'new',
    placed_at      timestamptz,
    raw            jsonb,
    created_at     timestamptz          NOT NULL DEFAULT now(),
    UNIQUE (store_id, external_id)
);

CREATE TABLE pieces (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid         NOT NULL REFERENCES tenants(id),
    variant_id          uuid         NOT NULL REFERENCES variants(id),
    receipt_id          uuid         REFERENCES receipts(id),
    barcode             text         NOT NULL UNIQUE,
    status              piece_status NOT NULL DEFAULT 'available',
    current_location_id uuid         REFERENCES locations(id),
    current_order_id    uuid         REFERENCES orders(id),
    last_event_at       timestamptz,
    last_user_id        uuid         REFERENCES users(id),
    created_at          timestamptz  NOT NULL DEFAULT now()
);

-- piece_events: shipment_id FK added after shipments table
CREATE TABLE piece_events (
    id            bigserial    PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants(id),
    piece_id      uuid         NOT NULL REFERENCES pieces(id),
    event_type    text         NOT NULL,
    actor_user_id uuid         REFERENCES users(id),
    order_id      uuid         REFERENCES orders(id),
    shipment_id   uuid,
    location_id   uuid         REFERENCES locations(id),
    from_status   piece_status,
    to_status     piece_status,
    metadata      jsonb,
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id         uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid    NOT NULL REFERENCES tenants(id),
    order_id   uuid    NOT NULL REFERENCES orders(id),
    variant_id uuid    NOT NULL REFERENCES variants(id),
    quantity   integer NOT NULL CHECK (quantity > 0),
    raw        jsonb
);

CREATE TABLE allocations (
    id            uuid              PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid              NOT NULL REFERENCES tenants(id),
    order_item_id uuid              NOT NULL REFERENCES order_items(id),
    piece_id      uuid              NOT NULL REFERENCES pieces(id),
    status        allocation_status NOT NULL DEFAULT 'active',
    allocated_by  uuid              REFERENCES users(id),
    allocated_at  timestamptz       NOT NULL DEFAULT now()
);

-- A piece may only have one live allocation at a time (active or packed).
CREATE UNIQUE INDEX allocations_piece_active_unique
    ON allocations (piece_id)
    WHERE status IN ('active','packed');

CREATE TABLE shipments (
    id                    uuid                    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid                    NOT NULL REFERENCES tenants(id),
    order_id              uuid                    NOT NULL REFERENCES orders(id),
    courier_account_id    uuid                    REFERENCES courier_accounts(id),
    provider              courier_provider        NOT NULL DEFAULT 'bosta',
    tracking_number       text                    UNIQUE,
    provider_delivery_id  text,
    provider_state        integer,
    internal_state        shipment_internal_state NOT NULL DEFAULT 'created',
    cod_amount            numeric(10,2),
    awb_url               text,
    number_of_attempts    integer                 NOT NULL DEFAULT 0,
    is_confirmed_delivery boolean,
    last_synced_at        timestamptz,
    raw                   jsonb,
    created_at            timestamptz             NOT NULL DEFAULT now()
);

ALTER TABLE piece_events
    ADD CONSTRAINT piece_events_shipment_id_fkey
    FOREIGN KEY (shipment_id) REFERENCES shipments(id);

CREATE TABLE pickups (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    courier_account_id uuid        REFERENCES courier_accounts(id),
    provider_pickup_id text,
    scheduled_date     date,
    status             text        NOT NULL DEFAULT 'pending',
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pickup_shipments (
    pickup_id   uuid NOT NULL REFERENCES pickups(id),
    shipment_id uuid NOT NULL REFERENCES shipments(id),
    tenant_id   uuid NOT NULL REFERENCES tenants(id),
    PRIMARY KEY (pickup_id, shipment_id)
);

-- UNIQUE NULLS NOT DISTINCT: two rows with the same source and
-- external_event_id=NULL would otherwise pass the unique check.
CREATE TABLE webhook_events (
    id                bigserial      PRIMARY KEY,
    source            webhook_source NOT NULL,
    tenant_id         uuid           REFERENCES tenants(id),
    topic             text           NOT NULL,
    external_event_id text,
    payload           jsonb          NOT NULL,
    status            webhook_status NOT NULL DEFAULT 'pending',
    error             text,
    received_at       timestamptz    NOT NULL DEFAULT now(),
    processed_at      timestamptz,
    UNIQUE NULLS NOT DISTINCT (source, external_event_id)
);

-- ---- global lookup tables (no tenant isolation needed) ------

CREATE TABLE bosta_state_mappings (
    state_code              integer                 NOT NULL,
    applies_to_order_type   text                    NOT NULL DEFAULT 'ALL',
    bosta_state             text                    NOT NULL,
    internal_shipment_state shipment_internal_state NOT NULL,
    piece_status_after      piece_status,
    notes                   text,
    PRIMARY KEY (state_code, applies_to_order_type)
);

CREATE TABLE ndr_codes (
    code        integer PRIMARY KEY,
    category    text    NOT NULL,
    description text    NOT NULL,
    severity    text    NOT NULL
);

-- ---- indexes ------------------------------------------------
-- Blueprint-mandated piece indexes:
CREATE INDEX pieces_tenant_variant_status  ON pieces (tenant_id, variant_id, status);
CREATE INDEX pieces_tenant_status          ON pieces (tenant_id, status);
CREATE INDEX pieces_barcode                ON pieces (barcode);
-- Blueprint-mandated piece_events indexes:
CREATE INDEX piece_events_piece_time       ON piece_events (piece_id, occurred_at);
CREATE INDEX piece_events_tenant_type_time ON piece_events (tenant_id, event_type, occurred_at);
-- Supporting FK indexes:
CREATE INDEX users_tenant_idx              ON users (tenant_id);
CREATE INDEX stores_tenant_idx             ON stores (tenant_id);
CREATE INDEX courier_accounts_tenant_idx   ON courier_accounts (tenant_id);
CREATE INDEX locations_tenant_idx          ON locations (tenant_id);
CREATE INDEX products_tenant_idx           ON products (tenant_id);
CREATE INDEX variants_tenant_idx           ON variants (tenant_id);
CREATE INDEX variants_product_idx          ON variants (product_id);
CREATE INDEX receipts_tenant_idx           ON receipts (tenant_id);
CREATE INDEX orders_tenant_idx             ON orders (tenant_id);
CREATE INDEX orders_tenant_status          ON orders (tenant_id, status);
CREATE INDEX order_items_order_idx         ON order_items (order_id);
CREATE INDEX allocations_piece_idx         ON allocations (piece_id);
CREATE INDEX allocations_order_item_idx    ON allocations (order_item_id);
CREATE INDEX shipments_tenant_idx          ON shipments (tenant_id);
CREATE INDEX shipments_order_idx           ON shipments (order_id);
CREATE INDEX pickups_tenant_idx            ON pickups (tenant_id);
CREATE INDEX webhook_events_status_time    ON webhook_events (status, received_at);
CREATE INDEX webhook_events_tenant_idx     ON webhook_events (tenant_id)
    WHERE tenant_id IS NOT NULL;

-- ---- Row-Level Security -------------------------------------
-- FORCE ROW LEVEL SECURITY: even the table owner is bound by
-- policies. Superusers (including Flyway's postgres user) always
-- bypass RLS regardless of FORCE.

ALTER TABLE tenants          ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants          FORCE ROW LEVEL SECURITY;
ALTER TABLE users            ENABLE ROW LEVEL SECURITY;
ALTER TABLE users            FORCE ROW LEVEL SECURITY;
ALTER TABLE stores           ENABLE ROW LEVEL SECURITY;
ALTER TABLE stores           FORCE ROW LEVEL SECURITY;
ALTER TABLE courier_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE courier_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE locations        ENABLE ROW LEVEL SECURITY;
ALTER TABLE locations        FORCE ROW LEVEL SECURITY;
ALTER TABLE products         ENABLE ROW LEVEL SECURITY;
ALTER TABLE products         FORCE ROW LEVEL SECURITY;
ALTER TABLE variants         ENABLE ROW LEVEL SECURITY;
ALTER TABLE variants         FORCE ROW LEVEL SECURITY;
ALTER TABLE receipts         ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipts         FORCE ROW LEVEL SECURITY;
ALTER TABLE pieces           ENABLE ROW LEVEL SECURITY;
ALTER TABLE pieces           FORCE ROW LEVEL SECURITY;
ALTER TABLE piece_events     ENABLE ROW LEVEL SECURITY;
ALTER TABLE piece_events     FORCE ROW LEVEL SECURITY;
ALTER TABLE orders           ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders           FORCE ROW LEVEL SECURITY;
ALTER TABLE order_items      ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items      FORCE ROW LEVEL SECURITY;
ALTER TABLE allocations      ENABLE ROW LEVEL SECURITY;
ALTER TABLE allocations      FORCE ROW LEVEL SECURITY;
ALTER TABLE shipments        ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipments        FORCE ROW LEVEL SECURITY;
ALTER TABLE pickups          ENABLE ROW LEVEL SECURITY;
ALTER TABLE pickups          FORCE ROW LEVEL SECURITY;
ALTER TABLE pickup_shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE pickup_shipments FORCE ROW LEVEL SECURITY;
ALTER TABLE webhook_events   ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_events   FORCE ROW LEVEL SECURITY;

-- Tenants table has no tenant_id column; it IS the tenant.
-- Admin flows set app.current_tenant to the target UUID before any DML.
CREATE POLICY tenant_isolation ON tenants
    USING (id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON stores
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON courier_accounts
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON locations
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON products
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON variants
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON receipts
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON pieces
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON piece_events
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON orders
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON order_items
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON allocations
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON shipments
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON pickups
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation ON pickup_shipments
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Webhooks arrive before tenant resolution; NULL tenant_id rows
-- are visible to any connection regardless of GUC state.
CREATE POLICY tenant_isolation ON webhook_events
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant', true)::uuid
    )
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant', true)::uuid
    );

-- ---- Grants to app_user -------------------------------------

GRANT USAGE ON SCHEMA public TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_user;

-- Custody ledger is append-only at the privilege level (Blueprint §5, §16).
REVOKE UPDATE, DELETE ON piece_events FROM app_user;
