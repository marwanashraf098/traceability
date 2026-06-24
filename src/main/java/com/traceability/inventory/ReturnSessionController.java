package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/returns")
public class ReturnSessionController {

    private final ReturnSessionService sessionService;
    private final LabelService         labelService;

    public ReturnSessionController(ReturnSessionService sessionService,
                                   LabelService labelService) {
        this.sessionService = sessionService;
        this.labelService   = labelService;
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    /** Open a returns session by waybill number. */
    @PostMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> createSession(
            @RequestBody CreateSessionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.createSession(
                req.waybillNumber().trim(),
                req.locationId(),
                req.note(),
                principal.userId());
    }

    /** List all returns sessions for this tenant. */
    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> listSessions() {
        return sessionService.listSessions();
    }

    /** Pieces eligible for return in this session (return_in_transit OR delivered). */
    @GetMapping("/sessions/{sessionId}/pieces")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> sessionPieces(@PathVariable UUID sessionId) {
        return sessionService.getSessionPieces(sessionId);
    }

    /**
     * Record a per-piece verdict (restock or damaged) within this session.
     *
     * For delivered pieces: enforces the customer_return_window_days guard.
     * A 422 response means the piece is out-of-window — use POST /returns/intake instead.
     */
    @PostMapping("/sessions/{sessionId}/pieces/{pieceId}/verdict")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> recordVerdict(
            @PathVariable UUID   sessionId,
            @PathVariable String pieceId,
            @RequestBody  VerdictRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.recordVerdict(
                sessionId, pieceId,
                req.verdict(), req.reason(),
                req.locationId(),
                principal.userId());
    }

    /** Finalize the session. Un-scanned return_in_transit pieces are reported but do not block. */
    @PostMapping("/sessions/{sessionId}/finalize")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> finalizeSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.finalizeSession(sessionId, principal.userId());
    }

    // ── Label reprint (Change 3: scoped to pieces in a return flow) ───────────

    /**
     * Reprint a label for a piece actively in a return flow.
     *
     * Requires piece to be in return_pending_inspection or damaged.
     * Writes a label_reprinted piece_events entry via InventoryLedger.recordLabelReprinted().
     * Returns the same barcode PDF that was originally printed — no new barcode is minted.
     */
    @GetMapping("/pieces/{pieceId}/label")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> reprintPieceLabel(
            @PathVariable String pieceId,
            @RequestParam(required = false) Float widthMm,
            @RequestParam(required = false) Float heightMm,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {

        // Validates piece is in return flow + writes label_reprinted event.
        sessionService.validateAndRecordReprint(pieceId, principal.userId());

        byte[] pdf = labelService.generatePieceLabel(pieceId, widthMm, heightMm);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + pieceId + ".pdf\"")
                .body(pdf);
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record CreateSessionRequest(String waybillNumber, UUID locationId, String note) {}
    public record VerdictRequest(String verdict, String reason, UUID locationId) {}
}
