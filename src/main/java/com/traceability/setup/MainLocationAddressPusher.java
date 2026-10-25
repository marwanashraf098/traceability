package com.traceability.setup;

import java.util.UUID;

/**
 * Onboarding gate — pushes the main warehouse address the merchant saved in Traced to the tenant's
 * "Traced Main Warehouse" location in Shopify (Shopify needs a full address there to show products
 * as in stock).
 *
 * Contract:
 *   - called by {@link MainLocationAddressPushJob} AFTER the address commit, with TenantContext =
 *     {@code tenantId} and NO database transaction open (it may make an HTTP call);
 *   - it only ever edits the Traced Main Warehouse location's address — never inventory, never any
 *     other location;
 *   - throwing means "failed, try again" (the job records it, raises the LOW alert
 *     warehouse_address_push_failed and JobRunr retries); it must be safe to call again with the
 *     same address (an address edit is idempotent);
 *   - {@link Outcome#NOT_LINKED}: the tenant has no Shopify location to edit yet — nothing pushed,
 *     nothing failed (the location is created later with the address).
 *
 * The implementation shipped with the gate is {@link NoOpMainLocationAddressPusher}; wiring it to the
 * Shopify location address edit is a separate step.
 */
public interface MainLocationAddressPusher {

    enum Outcome { PUSHED, NOT_LINKED }

    Outcome push(UUID tenantId, UUID locationId, WarehouseAddress address) throws Exception;
}
