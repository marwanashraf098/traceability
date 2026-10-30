package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Traced location in Shopify (2026-10-30) — Settings › Connections.
 *
 * POST /api/v1/shopify/location/check        — owner / manager: check the location now (after reactivating it in
 *                                               Shopify); a reactivation releases the held updates once
 * POST /api/v1/shopify/location/resend-failed — OWNER only: "Re-send failed updates" — the claims that used up their
 *                                               attempts while the location was inactive / deleted, through the
 *                                               normal path (ShopifyInventoryService.resendLocationFailures); 409
 *                                               while updates are still held
 */
@RestController
@RequestMapping("/api/v1/shopify/location")
public class ShopifyLocationController {

    private final ShopifyLocationCheckService checks;
    private final ShopifyInventoryService inventory;
    private final AuditService audit;

    public ShopifyLocationController(ShopifyLocationCheckService checks, ShopifyInventoryService inventory,
                                     AuditService audit) {
        this.checks = checks;
        this.inventory = inventory;
        this.audit = audit;
    }

    @PostMapping("/check")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> check(@AuthenticationPrincipal CustomUserDetails principal) {
        return TenantContext.runAs(principal.tenantId(), () -> {
            String state = checks.check(principal.tenantId()).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Couldn't reach Shopify to check the Traced location — try again shortly"));
            return Map.<String, Object>of("locationState", state);
        });
    }

    @PostMapping("/resend-failed")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> resendFailed(@AuthenticationPrincipal CustomUserDetails principal) {
        return TenantContext.runAs(principal.tenantId(), () -> {
            ShopifyInventoryService.ResendResult r = inventory.resendLocationFailures();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("increments", r.increments());
            out.put("pieceUpdates", r.pieces());
            out.put("applied", r.applied());
            audit.record(principal.userId(), "shopify_location_resend_failed", null, null, out);
            return out;
        });
    }
}
