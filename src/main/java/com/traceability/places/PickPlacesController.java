package com.traceability.places;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places S3 — Pick &amp; Pack places, display only (PickPlacesService). Same access as the fulfill flow
 * (any signed-in role); 404 unless the tenant has places.
 *
 *   GET  /api/v1/fulfill/{orderId}/places        the order's lines → places (pack screen, queue + waybill mode)
 *   POST /api/v1/fulfill/places/variants         {variantIds} → places per variant (gather list, one query)
 *   POST /api/v1/fulfill/places/orders           {orderIds} → each order's lines (printed-but-not-packed list)
 */
@RestController
@RequestMapping("/api/v1/fulfill")
@PreAuthorize("isAuthenticated()")
public class PickPlacesController {

    public record VariantsRequest(List<UUID> variantIds) {}
    public record OrdersRequest(List<UUID> orderIds) {}

    private final PickPlacesService service;

    public PickPlacesController(PickPlacesService service) {
        this.service = service;
    }

    @GetMapping("/{orderId}/places")
    public PickPlacesService.OrderPlaces order(@PathVariable UUID orderId) {
        return service.order(orderId);
    }

    @PostMapping("/places/variants")
    public PickPlacesService.VariantsResponse variants(@RequestBody(required = false) VariantsRequest req) {
        return service.variants(req == null ? null : req.variantIds());
    }

    @PostMapping("/places/orders")
    public PickPlacesService.OrdersResponse orders(@RequestBody(required = false) OrdersRequest req) {
        return service.orders(req == null ? null : req.orderIds());
    }
}
