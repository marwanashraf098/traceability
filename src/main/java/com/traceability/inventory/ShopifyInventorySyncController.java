package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-17 v2 go-live — operator-triggered Parts B/C actions (activate catalog, reconcile,
 * guarded seed write). Marawan runs these against real stores himself; nothing here runs
 * automatically. Part D's three ongoing triggers live in ShopifyInventoryService, wired
 * from the existing receiving/return/damage call sites — not exposed here.
 */
@RestController
@RequestMapping("/api/v1/shopify/inventory")
@PreAuthorize("hasRole('OWNER')")
public class ShopifyInventorySyncController {

    private final ShopifyCatalogActivationService activationService;

    public ShopifyInventorySyncController(ShopifyCatalogActivationService activationService) {
        this.activationService = activationService;
    }

    /** Part B: bulk inventoryActivate at the Traced GID for every catalog variant. */
    @PostMapping("/activate")
    public ShopifyCatalogActivationService.ActivationOutcome activate() {
        return activationService.activateAll();
    }
}
