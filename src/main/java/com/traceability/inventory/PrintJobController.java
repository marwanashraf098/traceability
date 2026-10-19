package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Merged-PDF slice B — background waybill print jobs (print ≤ 490, or reprint a batch).
 * Access: {@code isAuthenticated()} like every Pick &amp; Pack endpoint — every user of the tenant can start,
 * follow and download; tenant scoping is RLS (another tenant's job is 404).
 */
@RestController
@RequestMapping("/api/v1/fulfill/print-jobs")
public class PrintJobController {

    private final PrintJobService svc;

    public PrintJobController(PrintJobService svc) {
        this.svc = svc;
    }

    /**
     * kind 'print' (scope / paper / sort, as the print dialog) or 'reprint' (batchId). 202 {jobId};
     * 409 {code PRINT_JOB_RUNNING, message_en, message_ar, jobId} when the tenant already has one running.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest req,
                                                     @AuthenticationPrincipal CustomUserDetails principal) {
        try {
            UUID jobId = "reprint".equals(req.kind())
                ? svc.startReprint(req.batchId(), principal.userId())
                : svc.startPrint(req.scope(), req.paper(), req.sort(), principal.userId());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
        } catch (PrintJobService.JobRunningException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "PRINT_JOB_RUNNING");
            body.put("message_en", "A print is already running for this store.");
            body.put("message_ar", "هناك طباعة جارية بالفعل لهذا المتجر.");
            body.put("jobId", e.jobId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    /** The tenant's most recent job of the last 24 h, or 204. */
    @GetMapping("/latest")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PrintJobService.JobView> latest() {
        return svc.latest().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public PrintJobService.JobView progress(@PathVariable UUID jobId) {
        return svc.view(jobId);
    }

    /** The merged PDF, binary. download=true → attachment, else inline (opened in a tab). */
    @GetMapping(value = "/{jobId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID jobId,
                                      @RequestParam(name = "download", defaultValue = "false") boolean download) {
        PrintJobService.Pdf pdf = svc.pdf(jobId);
        ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(pdf.filename()).build();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(pdf.bytes());
    }

    public record StartRequest(String kind, String scope, String paper, String sort, UUID batchId) {}
}
