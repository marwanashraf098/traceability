package com.traceability.setup;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Placeholder until the Shopify location address edit is wired in: makes no call and reports
 * {@link MainLocationAddressPusher.Outcome#NOT_LINKED} (nothing pushed, nothing failed). The wiring
 * step replaces this bean with the real implementation (delete this class, or mark the real one
 * {@code @Primary}).
 */
@Component
public class NoOpMainLocationAddressPusher implements MainLocationAddressPusher {

    @Override
    public Outcome push(UUID tenantId, UUID locationId, WarehouseAddress address) {
        return Outcome.NOT_LINKED;
    }
}
