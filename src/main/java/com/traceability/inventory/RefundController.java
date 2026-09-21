package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * FR-EXCHANGE Step 4a-2 — the "Exchanges & Refunds" tab's CRP (refund) feed. Landed here
 * rather than on ReturnController or ReturnSessionController: both of those are scoped to
 * piece-level return sessions/inspection (ReturnController's own javadoc: "trimmed to the
 * one endpoint that survives the session-based rebuild"); this is a shipment-leg list for
 * merchant display, a different concept, backed by ShipmentLinkService (the existing home
 * of CRP-leg logic and of the analogous listUnlinked() display list). New controller keeps
 * both existing controllers' documented narrow scope intact and gives this feed the same
 * REST-resource symmetry as its sibling, /api/v1/exchanges.
 *
 * Ship-tab-first: read-only. No confirm/attach/bare/dismiss surface for CRP this pass —
 * unmatched CRPs stay in the existing exceptions surface, not here.
 */
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private final ShipmentLinkService shipmentLinkService;

    public RefundController(ShipmentLinkService shipmentLinkService) {
        this.shipmentLinkService = shipmentLinkService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return shipmentLinkService.listCrpReturns(page, Math.min(size, 100));
    }
}
