-- FR-17 v2 concurrency fix: one fulfillment location per tenant is a real invariant,
-- not just an assumption baked into application code. Without this, two concurrent
-- writers (e.g. an operator flagging a second location via LocationController at the same
-- moment ShopifyImportJob's Part A provisioning runs) could both end up with
-- is_fulfillment=true rows for the same tenant, which breaks the "the Traced GID" premise
-- that ShopifyInventoryService's location-target guard (and Part A's create-serialization
-- claim) both depend on.
--
-- Partial unique index: only rows where is_fulfillment=true participate, so a tenant can
-- have any number of is_fulfillment=false locations.
CREATE UNIQUE INDEX locations_one_fulfillment_per_tenant
    ON locations (tenant_id)
    WHERE is_fulfillment = true;
