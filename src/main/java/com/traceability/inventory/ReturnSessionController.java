package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** FR-24: session-based returns rebuild — see ReturnSessionService for the model. */
@RestController
@RequestMapping("/api/v1/returns")
public class ReturnSessionController {

    private final ReturnSessionService sessionService;
    private final LabelService         labelService;

    public ReturnSessionController(ReturnSessionService sessionService, LabelService labelService) {
        this.sessionService = sessionService;
        this.labelService   = labelService;
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @PostMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestBody CreateSessionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        try {
            UUID id = sessionService.createSession(req.note(), principal.userId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sessionId", id.toString()));
        } catch (DuplicateKeyException e) {
            Map<String, Object> existing = sessionService.getOpenSessionSummary();
            Map<String, Object> details = existing == null ? Map.of() : Map.of(
                "sessionId",  existing.get("id").toString(),
                "openedAt",   existing.get("opened_at").toString(),
                "pieceCount", existing.get("piece_count"));
            throw new ReturnSessionException(ReturnSessionException.Code.SESSION_ALREADY_OPEN,
                "An open return session already exists",
                "توجد بالفعل جلسة إرجاع مفتوحة",
                HttpStatus.CONFLICT, details);
        }
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandon(@PathVariable UUID sessionId, @AuthenticationPrincipal CustomUserDetails principal) {
        sessionService.abandon(sessionId, principal.userId());
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return sessionService.listSessions(page, size);
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getSession(@PathVariable UUID sessionId) {
        return sessionService.getSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/close")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> close(@PathVariable UUID sessionId,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.close(sessionId, principal.userId());
    }

    // ── Scan / disposition ───────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/scan")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> scan(
            @PathVariable UUID sessionId,
            @RequestBody ScanRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.scan(sessionId, req.scan(), req.locationId(), principal.userId());
    }

    @PostMapping("/sessions/{sessionId}/items/{pieceId}/disposition")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> disposition(
            @PathVariable UUID sessionId,
            @PathVariable String pieceId,
            @RequestBody DispositionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return sessionService.disposition(
            sessionId, pieceId, req.disposition(), req.reason(), req.locationId(), principal.userId());
    }

    // ── Reprint (any status — separate surface from the piece-level /pieces/{id}/label below) ──

    @PostMapping("/sessions/{sessionId}/pieces/{pieceId}/reprint-label")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> reprintLabel(
            @PathVariable UUID sessionId,
            @PathVariable String pieceId,
            @RequestParam(required = false) Float widthMm,
            @RequestParam(required = false) Float heightMm,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        sessionService.recordReprint(sessionId, pieceId, principal.userId());
        byte[] pdf = labelService.generatePieceLabel(pieceId, widthMm, heightMm);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pieceId + ".pdf\"")
            .body(pdf);
    }

    // ── Pre-existing, untouched (FR-12 change 3) — old single-piece gated reprint ──

    /**
     * Requires piece to be in return_pending_inspection or damaged. Kept exactly as it
     * was — not widened, not merged with the new reprint-label surface above (see
     * ReturnSessionService.validateAndRecordReprint()'s javadoc for why).
     */
    @GetMapping("/pieces/{pieceId}/label")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> reprintPieceLabelGated(
            @PathVariable String pieceId,
            @RequestParam(required = false) Float widthMm,
            @RequestParam(required = false) Float heightMm,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        sessionService.validateAndRecordReprint(pieceId, principal.userId());
        byte[] pdf = labelService.generatePieceLabel(pieceId, widthMm, heightMm);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pieceId + ".pdf\"")
            .body(pdf);
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> analytics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return sessionService.analytics(
            from == null ? null : Instant.parse(from),
            to   == null ? null : Instant.parse(to));
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record CreateSessionRequest(String note) {}
    public record ScanRequest(String scan, UUID locationId) {}
    public record DispositionRequest(String disposition, String reason, UUID locationId) {}
}
