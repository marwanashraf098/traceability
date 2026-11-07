package com.traceability.setup;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Onboarding gate — the main warehouse address (step 2 of the gate, and the grandfathered
 * tenants' banner). Owner only: a manager waits for the owner, a worker never sees it.
 *
 *   GET /api/v1/setup/address — {address | null, provinces: [{code, nameEn, nameAr, areas}]}
 *   PUT /api/v1/setup/address — {provinceCode, city, street, building?, floor?} → {setupStep}
 *   GET /api/v1/setup/address/shopify-locations — O2b: the store's own Shopify locations to pre-fill from (read only;
 *       empty when the address is already complete or the read fails)
 */
@RestController
@RequestMapping("/api/v1/setup/address")
public class SetupAddressController {

    private final SetupAddressService svc;
    private final ShopifyAddressPrefillService prefill;

    public SetupAddressController(SetupAddressService svc, ShopifyAddressPrefillService prefill) {
        this.svc = svc;
        this.prefill = prefill;
    }

    @GetMapping("/shopify-locations")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> shopifyLocations() {
        return prefill.locations();
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> get() {
        return svc.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> save(@RequestBody SetupAddressService.SaveRequest req,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.save(principal.userId(), req);
    }
}
