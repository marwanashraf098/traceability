package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * FR-13: Manual piece adjustments.
 *
 * POST /api/v1/pieces/{id}/adjust          — adjust status with reason (13.1, 13.3)
 * POST /api/v1/pieces/{id}/release-for-adjust — free a committed piece before adjusting (13.2)
 * POST /api/v1/pieces/{id}/void            — receiving-overcount / duplicate-entry correction (FR-13.x)
 * POST /api/v1/pieces/{id}/hold            — enter on_hold (FR-13.x)
 * POST /api/v1/pieces/{id}/unhold          — exit on_hold back to available (FR-13.x)
 */
@RestController
@RequestMapping("/api/v1/pieces")
public class PieceAdjustController {

    record AdjustBody(String toStatus, String reason, String note) {}
    record VoidBody(String reason, String note) {}
    record HoldBody(String reason, String note) {}
    record HoldResponse(String holdEventId) {}

    private final PieceAdjustService svc;

    public PieceAdjustController(PieceAdjustService svc) {
        this.svc = svc;
    }

    @PostMapping("/{id}/adjust")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void adjust(
            @PathVariable String id,
            @RequestBody AdjustBody body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.adjustPiece(id, body.toStatus(), body.reason(), body.note(), principal.userId());
    }

    @PostMapping("/{id}/release-for-adjust")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void releaseForAdjust(
            @PathVariable String id,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.releaseForAdjust(id, principal.userId());
    }

    @PostMapping("/{id}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void voidPiece(
            @PathVariable String id,
            @RequestBody VoidBody body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.voidPiece(id, body.reason(), body.note(), principal.userId());
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public HoldResponse hold(
            @PathVariable String id,
            @RequestBody HoldBody body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return new HoldResponse(svc.hold(id, body.reason(), body.note(), principal.userId()).toString());
    }

    @PostMapping("/{id}/unhold")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void unhold(
            @PathVariable String id,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.unhold(id, principal.userId());
    }
}
