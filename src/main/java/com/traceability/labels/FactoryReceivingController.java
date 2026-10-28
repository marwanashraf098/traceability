package com.traceability.labels;

import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.PhoneScanSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * Flexible labeling slice 6b — the scan side of a FACTORY receiving session (created, read, finalized and deleted
 * through /api/v1/receiving/sessions like every session). Owner + manager; 404 unless the tenant has flexible labeling.
 *
 *   POST /api/v1/receiving/sessions/{id}/factory/scan                {code, relayEventId?} → STAGED | REJECTED:…
 *   POST /api/v1/receiving/sessions/{id}/factory/undo                undoes the session's newest scan
 *   POST /api/v1/receiving/sessions/{id}/factory/labels              {variantId, idempotencyKey} → 1 code ("Piece without a label")
 *   GET  /api/v1/receiving/sessions/{id}/factory/labels/{codeId}/pdf that code's label
 */
@RestController
@RequestMapping("/api/v1/receiving/sessions/{id}/factory")
public class FactoryReceivingController {

    public record ScanRequest(String code, UUID relayEventId) {}
    public record ExtraLabelRequest(UUID variantId, String idempotencyKey) {}

    private final FactoryReceivingService service;
    private final PhoneScanSource phoneScanSource;

    public FactoryReceivingController(FactoryReceivingService service, PhoneScanSource phoneScanSource) {
        this.service = service;
        this.phoneScanSource = phoneScanSource;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FactoryReceivingService.ScanOutcome scan(@PathVariable UUID id, @RequestBody(required = false) ScanRequest req,
                                                    @AuthenticationPrincipal CustomUserDetails principal) {
        String code = req == null ? null : req.code();
        // 'via phone' only when the relay event verifies as the caller's own (PhoneScanSource), never client-sent.
        boolean viaPhone = req != null && phoneScanSource.isPhone(req.relayEventId(), code, principal.userId());
        return service.scan(id, code, principal.userId(), viaPhone);
    }

    @PostMapping("/undo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FactoryReceivingService.UndoResult undo(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.undoLast(id, principal.userId());
    }

    @PostMapping("/labels")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FactoryReceivingService.ExtraLabel extraLabel(@PathVariable UUID id, @RequestBody(required = false) ExtraLabelRequest req,
                                                         @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.variantId() == null) throw LabelCodeException.unknownVariant();
        return service.extraLabel(id, req.variantId(), req.idempotencyKey(), principal.userId());
    }

    // No produces= (like the other label endpoints): a JSON error body must stay writable.
    @GetMapping("/labels/{codeId}/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> extraLabelPdf(@PathVariable UUID id, @PathVariable UUID codeId) throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factory-label-" + codeId + ".pdf\"")
            .body(service.extraLabelPdf(id, codeId));
    }
}
