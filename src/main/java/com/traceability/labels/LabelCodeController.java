package com.traceability.labels;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
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
 * Flexible labeling — named label codes (slice 1, named since slice 3). Owner + manager for now (slices 3 / 4 decide
 * worker access per screen). 404 unless the tenant has flexible labeling (LabelCodeService).
 *
 *   POST /api/v1/label-codes/batches          {kind, lines: [{variantId, count, orderId?, orderItemId?}], idempotencyKey}
 *                                             → the batch + its codes (each tied to its variant)
 *   GET  /api/v1/label-codes/batches/{id}/pdf the batch's still-issued codes as named piece labels
 *   POST /api/v1/label-codes/void             {codeIds, reason} → {voided}
 */
@RestController
@RequestMapping("/api/v1/label-codes")
public class LabelCodeController {

    public record IssueRequest(String kind, List<LabelCodeService.Line> lines, String idempotencyKey) {}
    public record VoidRequest(List<UUID> codeIds, String reason) {}

    private final LabelCodeService service;

    public LabelCodeController(LabelCodeService service) {
        this.service = service;
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LabelCodeService.Batch issue(@RequestBody IssueRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return service.issue(principal.userId(), req.kind(), req.lines(), req.idempotencyKey());
    }

    @GetMapping("/batches/{id}/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"blank-labels-" + id + ".pdf\"")
            .body(service.batchPdf(id));
    }

    @PostMapping("/void")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> voidCodes(@RequestBody VoidRequest req) {
        return Map.of("voided", service.voidCodes(req.codeIds(), req.reason()));
    }
}
