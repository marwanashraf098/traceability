package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * S3d — "Release reservation" for a Shopify-native exchange shadow order (#…-X1), owner / manager only, audited
 * ({@code shopify_exchange_release}). The safety net for a reservation Traced can't explain
 * (shopify_exchange_still_waiting). Ends it with reason 'manual_release' (ShopifyShadowOrders.releaseManually).
 */
@RestController
@RequestMapping("/api/v1/shopify-exchanges")
public class ShopifyExchangeController {

    private final ShopifyShadowOrders shadows;
    private final AuditService audit;

    public ShopifyExchangeController(ShopifyShadowOrders shadows, AuditService audit) {
        this.shadows = shadows;
        this.audit = audit;
    }

    @PostMapping("/{orderId}/release-reservation")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> release(@PathVariable UUID orderId, @AuthenticationPrincipal CustomUserDetails principal) {
        String number;
        try {
            number = shadows.releaseManually(orderId);
        } catch (ShopifyShadowOrders.ReleaseRefused e) {
            throw new ResponseStatusException("NOT_FOUND".equals(e.code) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT, e.getMessage());
        }
        audit.record(principal.userId(), "shopify_exchange_release", "order", orderId.toString(),
            Map.of("number", number, "reason", ShopifyShadowOrders.RELEASE_REASON));
        return Map.of("orderId", orderId.toString(), "number", number, "state", "ended",
            "endReason", ShopifyShadowOrders.RELEASE_REASON);
    }
}
