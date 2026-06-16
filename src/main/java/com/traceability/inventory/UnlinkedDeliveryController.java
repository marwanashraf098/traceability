package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
public class UnlinkedDeliveryController {

    private final ShipmentLinkService svc;

    public UnlinkedDeliveryController(ShipmentLinkService svc) {
        this.svc = svc;
    }

    @GetMapping("/unlinked")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> listUnlinked(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return svc.listUnlinked(page, Math.min(size, 100));
    }

    @PostMapping("/unlinked/{id}/link")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void manualLink(
            @PathVariable long id,
            @RequestBody ManualLinkRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.manualLink(id, req.orderId(), principal.userId());
    }

    public record ManualLinkRequest(UUID orderId) {}
}
