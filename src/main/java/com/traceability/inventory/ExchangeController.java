package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 2 — manual mapping step. Same access tier as
 * UnlinkedDeliveryController (OWNER/MANAGER) — mapping is an ambiguous-record
 * resolution action, not routine worker pick/pack.
 */
@RestController
@RequestMapping("/api/v1/exchanges")
public class ExchangeController {

    private final ExchangeService svc;

    public ExchangeController(ExchangeService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return svc.list(status);
    }

    @PostMapping("/{id}/map")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> map(@PathVariable UUID id, @RequestBody MapRequest req) {
        return svc.map(id, req.outboundVariantId(), req.inboundVariantId());
    }

    public record MapRequest(UUID outboundVariantId, UUID inboundVariantId) {}
}
