package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Unpack-order S2 — "Packed, not picked up" on Pick &amp; Pack (both packing modes). Every signed-in role reads
 * it (workers look for a packed parcel too); unpacking is owner / manager only (FulfillController).
 */
@RestController
@RequestMapping("/api/v1/fulfill")
public class PackedOrderController {

    private final PackedOrderService svc;

    public PackedOrderController(PackedOrderService svc) {
        this.svc = svc;
    }

    @GetMapping("/packed")
    @PreAuthorize("isAuthenticated()")
    public List<PackedOrderService.PackedOrderRow> packed() {
        return svc.list();
    }
}
