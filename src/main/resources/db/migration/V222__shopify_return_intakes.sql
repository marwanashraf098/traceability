-- ============================================================
-- V222 — Shopify-native returns S4: returned items come back through normal Scan returns intake (R4).
--
-- shopify_return_intakes: one row per piece a Scan returns scan accepted as a unit of a Shopify return line — which
-- Shopify return line each arrived piece satisfies (by order + variant, oldest open line first). The ONE writer is
-- inventory.ShopifyReturnIntake (called from ReturnSessionService.scanPiece); "expected back" for a Shopify return
-- (ShipmentLinkService.isExpectedBack / shopifyReturnExpects) and the S2 card's "In Traced" read it.
--
--   order_id        the ORIGINAL order (the Shopify return's order) — a replacement shipped on a shadow order
--                   (#…-X1) resolves to its original through shopify_exchange_shadows
--   piece_order_id  the order the piece came back for (the shadow for a returned replacement, else = order_id) —
--                   the piece's grade is read for that order (OrderReturnState.pieceStateLateral)
--   request_item_id the Traced return request item that took the scan first (duplicate: request + Shopify return on
--                   the same piece) — the Shopify line is then COVERED by that request's scan
--
-- Nothing here moves a piece or writes to Shopify. RLS NULLIF; app_user INSERT / SELECT only (no UPDATE, DELETE,
-- TRUNCATE): a row records a scan that happened.
-- ============================================================

ALTER TABLE shopify_return_lines ADD CONSTRAINT shopify_return_lines_id_tenant_key UNIQUE (id, tenant_id);

CREATE TABLE shopify_return_intakes (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid        NOT NULL REFERENCES tenants(id),
    return_line_id         uuid        NOT NULL,
    return_id              uuid        NOT NULL,
    order_id               uuid        NOT NULL,
    piece_order_id         uuid        NOT NULL,
    piece_id               text        NOT NULL REFERENCES pieces(id),
    variant_id             uuid        NOT NULL REFERENCES variants(id),
    session_id             uuid        REFERENCES return_sessions(id),
    return_session_item_id uuid,
    request_item_id        uuid        REFERENCES return_request_items(id),
    received_by            uuid        REFERENCES users(id),
    received_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, piece_id, return_line_id),
    FOREIGN KEY (return_line_id, tenant_id) REFERENCES shopify_return_lines (id, tenant_id),
    FOREIGN KEY (return_id, tenant_id)      REFERENCES shopify_returns (id, tenant_id),
    FOREIGN KEY (order_id, tenant_id)       REFERENCES orders (id, tenant_id),
    FOREIGN KEY (piece_order_id, tenant_id) REFERENCES orders (id, tenant_id)
);
CREATE INDEX shopify_return_intakes_line_idx  ON shopify_return_intakes (tenant_id, return_line_id);
CREATE INDEX shopify_return_intakes_order_idx ON shopify_return_intakes (tenant_id, order_id);
CREATE INDEX shopify_return_intakes_piece_idx ON shopify_return_intakes (piece_id);

ALTER TABLE shopify_return_intakes ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_return_intakes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_return_intakes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT ON shopify_return_intakes TO app_user;
REVOKE UPDATE, DELETE, TRUNCATE ON shopify_return_intakes FROM app_user;
