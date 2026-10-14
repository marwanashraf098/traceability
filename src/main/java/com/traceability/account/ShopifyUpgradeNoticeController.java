package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/v1/shopify/custom-app-deletion/confirm — the owner's "I've deleted it" on the app-wide notice
 * shown after a custom-app → official app upgrade. 204 when confirmed (or already confirmed — no rewrite);
 * 409 NOT_AWAITING_DELETION when the tenant's store isn't an upgraded official-app store awaiting it.
 */
@RestController
public class ShopifyUpgradeNoticeController {

    private final ShopifyUpgradeNoticeService svc;

    public ShopifyUpgradeNoticeController(ShopifyUpgradeNoticeService svc) {
        this.svc = svc;
    }

    @PostMapping("/api/v1/shopify/custom-app-deletion/confirm")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> confirm(@AuthenticationPrincipal CustomUserDetails principal) {
        return switch (svc.confirmDeletion(principal.userId())) {
            case CONFIRMED, ALREADY_CONFIRMED -> ResponseEntity.noContent().build();
            case NOT_AWAITING_DELETION -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "NOT_AWAITING_DELETION",
                "message", "This store isn't waiting for the old custom app to be deleted."));
        };
    }
}
