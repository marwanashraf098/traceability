package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ShopifyInventoryReconcileService reconcileService;

    public ShopifyInventorySyncController(ShopifyCatalogActivationService activationService,
                                           ShopifyInventoryReconcileService reconcileService) {
        this.activationService = activationService;
        this.reconcileService   = reconcileService;
    }

    /** Part B: bulk inventoryActivate at the Traced GID for every catalog variant. */
    @PostMapping("/activate")
    public ShopifyCatalogActivationService.ActivationOutcome activate() {
        return activationService.activateAll();
    }

    /** Part C step 1-2: read-only reconcile report. Writes nothing. */
    @GetMapping("/reconcile")
    public ShopifyInventoryReconcileService.ReconcileReport reconcile() {
        return reconcileService.reconcile();
    }

    /** Part C step 3: guarded positive-delta seed write, after Marawan reviews the report. */
    @PostMapping("/reconcile/apply")
    public ShopifyInventoryReconcileService.ApplyResult applyReconcile(
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconcileService.apply(principal.userId());
    }
}
