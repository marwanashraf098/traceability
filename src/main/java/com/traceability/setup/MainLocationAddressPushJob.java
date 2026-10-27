package com.traceability.setup;

import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import com.traceability.inventory.MainLocationAddressService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Onboarding gate — puts the saved main warehouse address on the Traced Main Warehouse Shopify location.
 * Enqueued by {@link SetupAddressService#save} only after the address commits. ONE job, A's fill rule
 * called synchronously ({@link MainLocationAddressService#apply}, write mode, no DB transaction open around
 * it); the outcome lives only in V184's locations.shopify_address_* columns.
 *
 * Reads the address at run time ({@link MerchantWarehouseAddressSource}), so a retry or an older job always
 * sends the latest save. Outcomes (approved 2026-10-25):
 *   filled / has_address → done (has_address = the merchant's own Shopify address stays);
 *   local_pickup_on      → never for a real address (the fill rule only blocks the generic one) — done;
 *   failed / not_linked / missing_scope → the LOW alert warehouse_address_push_failed, and the job throws
 *                          so JobRunr retries. Never blocks the merchant: the gate reads only the address.
 */
@Component
public class MainLocationAddressPushJob {

    private static final Logger log = LoggerFactory.getLogger(MainLocationAddressPushJob.class);

    private final MerchantWarehouseAddressSource source;
    private final MainLocationAddressService     fillRule;

    public MainLocationAddressPushJob(MerchantWarehouseAddressSource source, MainLocationAddressService fillRule) {
        this.source   = source;
        this.fillRule = fillRule;
    }

    @Job(name = "Push main warehouse address to Shopify — tenant %0", retries = 8)
    public void push(UUID tenantId) {
        Optional<LocationAddress> address = TenantContext.runAs(tenantId, () -> source.merchantAddress(tenantId));
        if (address.isEmpty()) return;
        MainLocationAddressService.Outcome outcome = TenantContext.runAs(tenantId,
            () -> fillRule.apply(tenantId, address.get(), false)).outcome();
        switch (outcome) {
            case filled, has_address, local_pickup_on -> { }
            case failed, not_linked, missing_scope -> {
                log.warn("main warehouse address not on Shopify yet tenant={} outcome={}", tenantId, outcome);
                throw new IllegalStateException("Main warehouse address not on Shopify for tenant " + tenantId
                    + ": " + outcome + " — JobRunr will retry");
            }
        }
    }
}
