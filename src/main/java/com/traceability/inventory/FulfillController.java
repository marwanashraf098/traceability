package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pick/pack fulfill flow (FR-8 + FR-9 core + FR-9.6 AWB scan).
 *
 * Access:
 *   OWNER / MANAGER — all operations including lock release of any order
 *   WORKER          — queue, lock own order, scan, unscan, complete, link AWB
 */
@RestController
@RequestMapping("/api/v1/fulfill")
public class FulfillController {

    private final FulfillService      svc;
    private final ShipmentLinkService linkSvc;

    public FulfillController(FulfillService svc, ShipmentLinkService linkSvc) {
        this.svc     = svc;
        this.linkSvc = linkSvc;
    }

    @GetMapping("/queue")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> getQueue() {
        return svc.getQueue();
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getOrder(@PathVariable UUID orderId) {
        return svc.getOrder(orderId);
    }

    @PostMapping("/{orderId}/lock")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock(@PathVariable UUID orderId,
                     @AuthenticationPrincipal CustomUserDetails principal) {
        svc.lockOrder(orderId, principal.userId());
    }

    @DeleteMapping("/{orderId}/lock")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID orderId,
                        @AuthenticationPrincipal CustomUserDetails principal) {
        boolean isManager = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_OWNER")
                        || a.getAuthority().equals("ROLE_MANAGER"));
        svc.releaseOrder(orderId, principal.userId(), isManager);
    }

    @PostMapping("/{orderId}/scan")
    @PreAuthorize("isAuthenticated()")
    public FulfillService.ScanResult scan(
            @PathVariable UUID orderId,
            @RequestBody ScanRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.scan(orderId, req.barcode(), principal.userId());
    }

    @DeleteMapping("/{orderId}/scan/{pieceId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unscan(@PathVariable UUID orderId,
                       @PathVariable String pieceId,
                       @AuthenticationPrincipal CustomUserDetails principal) {
        svc.unscan(orderId, pieceId, principal.userId());
    }

    @PostMapping("/{orderId}/complete")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> complete(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        int packed = svc.complete(orderId, principal.userId());
        return Map.of("packedPieces", packed);
    }

    /** FR-9.6 — AWB scan at pack: link a plugin-printed tracking number to the packed order. */
    @PostMapping("/{orderId}/link")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> linkAwb(
            @PathVariable UUID orderId,
            @RequestBody AwbLinkRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return linkSvc.linkByAwbScan(orderId, req.trackingNumber().trim(), principal.userId());
    }

    public record ScanRequest(String barcode) {}
    public record AwbLinkRequest(String trackingNumber) {}
}
