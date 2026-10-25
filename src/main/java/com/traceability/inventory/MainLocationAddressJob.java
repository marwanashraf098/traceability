package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Feature A — the job behind {@link MainLocationAddressService#setMainLocationAddressAfterCommit}.
 * Plain string arguments (JobRunr serialises them). Runs under TenantContext.runAs (app_user, RLS).
 * A {@code failed} outcome throws, so JobRunr retries with its backoff; every other outcome is final.
 */
@Component
public class MainLocationAddressJob {

    private final MainLocationAddressService service;

    public MainLocationAddressJob(MainLocationAddressService service) {
        this.service = service;
    }

    @Job(name = "Set the Traced main location's Shopify address", retries = 5)
    public void apply(UUID tenantId, String address1, String address2, String city,
                      String provinceCode, String zip, String countryCode) {
        LocationAddress address = new LocationAddress(address1, address2, city, provinceCode, zip, countryCode);
        MainLocationAddressService.Result r = TenantContext.runAs(tenantId,
            () -> service.apply(tenantId, address, false));
        if (r.outcome() == MainLocationAddressService.Outcome.failed) {
            throw new IllegalStateException("Main location address not set for tenant " + tenantId + ": "
                + r.detail() + " — JobRunr will retry");
        }
    }
}
