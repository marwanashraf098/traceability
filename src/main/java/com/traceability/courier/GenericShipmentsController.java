package com.traceability.courier;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Non-Bosta couriers G3 — the Orders list's "With &lt;carrier&gt;" filter options and "Mark delivered". Owner + manager. */
@RestController
@RequestMapping("/api/v1/generic-shipments")
public class GenericShipmentsController {

    public record MarkRequest(List<UUID> orderIds) {}

    private final GenericDeliveryService delivery;

    public GenericShipmentsController(GenericDeliveryService delivery) {
        this.delivery = delivery;
    }

    @GetMapping("/carriers")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<GenericDeliveryService.CarrierCount> carriers() {
        return delivery.carriersWithCourier();
    }

    @PostMapping("/mark-delivered")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<GenericDeliveryService.MarkResult> markDelivered(@RequestBody MarkRequest req,
                                                                 @AuthenticationPrincipal CustomUserDetails principal) {
        return delivery.markDelivered(req == null ? null : req.orderIds(), principal.userId());
    }
}
