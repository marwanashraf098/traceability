package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 2 (outbound mapping) + Step 3 Part D (inbound match resolution).
 * Same access tier as UnlinkedDeliveryController (OWNER/MANAGER) — both mapping and
 * unmatched-exchange resolution are ambiguous-record resolution actions, not routine
 * worker pick/pack.
 */
@RestController
@RequestMapping("/api/v1/exchanges")
public class ExchangeController {

    private final ExchangeService svc;
    private final ExchangeMatchService matchSvc;

    public ExchangeController(ExchangeService svc, ExchangeMatchService matchSvc) {
        this.svc = svc;
        this.matchSvc = matchSvc;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return svc.list(status, page, Math.min(size, 100));
    }

    /** Step 4a-1 — single-exchange detail, same row shape as list(). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return svc.detail(id);
    }

    /** Step 4a-1 — wires the already-built ExchangeMatchService.listCandidates(); derived at read. */
    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<ExchangeMatchService.Candidate> candidates(@PathVariable UUID id) {
        return matchSvc.listCandidates(id);
    }

    @PostMapping("/{id}/map")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> map(@PathVariable UUID id, @RequestBody MapRequest req) {
        return svc.map(id, req.outboundVariantId(), req.inboundVariantId());
    }

    /** Part D — merchant supplies the order the old item is actually returning from. */
    @PostMapping("/{id}/attach")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> attach(@PathVariable UUID id, @RequestBody AttachRequest req) {
        return matchSvc.searchAttach(id, req.orderId());
    }

    /** Part D — accept the physical item back with no order link (custody starts at intake). */
    @PostMapping("/{id}/bare-return")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void bareReturn(@PathVariable UUID id) {
        matchSvc.acceptAsBareReturn(id);
    }

    /** Part D — dismiss an unmatched/needs_confirmation exchange. */
    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void dismiss(@PathVariable UUID id) {
        matchSvc.dismiss(id);
    }

    public record MapRequest(UUID outboundVariantId, UUID inboundVariantId) {}
    public record AttachRequest(UUID orderId) {}
}
