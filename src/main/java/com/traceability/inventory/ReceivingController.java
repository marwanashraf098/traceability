package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receiving session management and label PDF endpoints.
 *
 * Access:
 *   OWNER / MANAGER — always allowed (receiving is an inventory-management operation).
 *   WORKER          — only if tenant.worker_receiving_enabled = true (FR-2.5a stub).
 *                     Full guard deferred; worker endpoints return 403 until the
 *                     toggle is implemented in tenant settings (Day 9+).
 */
@RestController
@RequestMapping("/api/v1/receiving")
public class ReceivingController {

    private final ReceivingService receiving;
    private final LabelService     labels;

    public ReceivingController(ReceivingService receiving, LabelService labels) {
        this.receiving = receiving;
        this.labels    = labels;
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestBody CreateSessionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        UUID sessionId = receiving.createSession(
            principal.userId(),
            req.locationId() != null ? UUID.fromString(req.locationId()) : null,
            req.reference(), req.supplierName(), req.note());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("sessionId", sessionId.toString()));
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> listSessions() {
        return receiving.listSessions();
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> getSession(@PathVariable UUID sessionId) {
        return receiving.getSession(sessionId);
    }

    // ── Lines ─────────────────────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/lines")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> addLine(
            @PathVariable UUID sessionId,
            @RequestBody AddLineRequest req) {
        UUID lineId = receiving.addLine(sessionId, UUID.fromString(req.variantId()), req.quantity());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("lineId", lineId.toString()));
    }

    @PutMapping("/sessions/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLine(@PathVariable UUID sessionId,
                           @PathVariable UUID lineId,
                           @RequestBody UpdateLineRequest req) {
        receiving.updateLine(sessionId, lineId, req.quantity());
    }

    @DeleteMapping("/sessions/{sessionId}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLine(@PathVariable UUID sessionId, @PathVariable UUID lineId) {
        receiving.deleteLine(sessionId, lineId);
    }

    // ── Finalize ──────────────────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/finalize")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> finalize(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        int count = receiving.finalize(sessionId, principal.userId());
        return Map.of("piecesCreated", count);
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @GetMapping(value = "/sessions/{sessionId}/labels", produces = "application/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> getLabels(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) Float widthMm,
            @RequestParam(required = false) Float heightMm) throws IOException {
        byte[] pdf = labels.generateSessionLabels(sessionId, widthMm, heightMm);
        return pdfResponse(pdf, "labels-" + sessionId + ".pdf");
    }

    @PostMapping(value = "/sessions/{sessionId}/reprint", produces = "application/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> reprint(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) ReprintRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        String note = req != null ? req.note() : null;
        Float wMm   = req != null ? req.widthMm()  : null;
        Float hMm   = req != null ? req.heightMm() : null;
        byte[] pdf  = labels.reprint(sessionId, principal.userId(), note, wMm, hMm);
        return pdfResponse(pdf, "reprint-" + sessionId + ".pdf");
    }

    // ── Pieces (barcodes) ─────────────────────────────────────────────────────

    @GetMapping("/sessions/{sessionId}/pieces")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> getSessionPieces(@PathVariable UUID sessionId) {
        return receiving.getSessionPieces(sessionId);
    }

    // ── Variant search ────────────────────────────────────────────────────────

    @GetMapping("/variants/search")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> searchVariants(@RequestParam String q) {
        return receiving.searchVariants(q);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record CreateSessionRequest(
        String locationId, String reference, String supplierName, String note) {}

    public record AddLineRequest(String variantId, int quantity) {}

    public record UpdateLineRequest(int quantity) {}

    public record ReprintRequest(String note, Float widthMm, Float heightMm) {}
}
