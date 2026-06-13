package com.traceability.integrations.shopify;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shopify")
public class ShopifyController {

    private final ShopifySyncService syncService;

    public ShopifyController(ShopifySyncService syncService) {
        this.syncService = syncService;
    }

    public record ConnectRequest(String shopDomain, String adminToken) {}

    public record ConnectResponse(
            String storeId,
            String shopName,
            int productsImported,
            int variantsImported,
            int ordersImported,
            int flaggedOrders) {}

    @PostMapping("/connect")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public ConnectResponse connect(
            @RequestBody ConnectRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        ShopifySyncService.ConnectResult result =
                syncService.connectAndImport(principal.tenantId(), req.shopDomain(), req.adminToken());

        return new ConnectResponse(
                result.storeId().toString(),
                result.shopName(),
                result.importResult().products(),
                result.importResult().variants(),
                result.importResult().orders(),
                result.importResult().flaggedOrders());
    }
}
