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
 */
@RestController
@RequestMapping("/api/v1/pieces")
public class PieceAdjustController {

    record AdjustBody(String toStatus, String reason, String note) {}

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
}
