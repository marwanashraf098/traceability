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
 */
@RestController
@RequestMapping("/api/v1/setup/address")
public class SetupAddressController {

    private final SetupAddressService svc;

    public SetupAddressController(SetupAddressService svc) {
        this.svc = svc;
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
