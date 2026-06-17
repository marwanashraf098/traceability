package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    /** Worker scans an arriving RTO piece barcode. */
    @PostMapping("/intake")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> intake(
            @RequestBody IntakeRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return returnService.intakeScan(
                req.barcode().trim(),
                req.locationId(),
                principal.userId());
    }

    /** Manager view: all pieces currently at return_pending_inspection. */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return returnService.listPending(page, size);
    }

    /** Manager: restock a piece back to available. */
    @PostMapping("/pieces/{pieceId}/restock")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void restock(
            @PathVariable String pieceId,
            @RequestBody RestockRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        returnService.restock(pieceId, req.locationId(), principal.userId());
    }

    /** Manager: mark a piece as damaged (terminal). Reason required. */
    @PostMapping("/pieces/{pieceId}/damage")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void damage(
            @PathVariable String pieceId,
            @RequestBody DamageRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        returnService.markDamaged(pieceId, req.reason(), principal.userId());
    }

    /** Never-received report: pieces Bosta confirmed returned but not yet scanned in. */
    @GetMapping("/never-received")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> neverReceived(
            @RequestParam(defaultValue = "3") int windowDays) {
        return returnService.neverReceived(windowDays);
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record IntakeRequest(String barcode, UUID locationId) {}
    public record RestockRequest(UUID locationId) {}
    public record DamageRequest(String reason) {}
}
