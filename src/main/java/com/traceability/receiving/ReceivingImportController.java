package com.traceability.receiving;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Receiving import (slice 2b). Owner + manager, like the rest of receiving.
 *
 *   POST /api/v1/receiving/sessions/{id}/import/preview   multipart "file" (.csv / .xlsx) → the preview (writes nothing)
 *   POST /api/v1/receiving/sessions/{id}/import/apply     {lines: [{variantId, quantity}]} → adds to the session's lines
 *   GET  /api/v1/receiving/import/template?format=xlsx|csv  every active variant in Shopify's export layout
 */
@RestController
@RequestMapping("/api/v1/receiving")
public class ReceivingImportController {

    public record ApplyRequest(List<ReceivingImportService.ApplyLine> lines) {}

    private final ReceivingImportService service;

    public ReceivingImportController(ReceivingImportService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{sessionId}/import/preview")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ReceivingImportService.Preview preview(@PathVariable UUID sessionId,
                                                  @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) throw ImportException.emptyFile();
        if (file.getSize() > ShopifyInventoryFile.MAX_BYTES) throw ImportException.tooLarge();
        return service.preview(sessionId, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/sessions/{sessionId}/import/apply")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ReceivingImportService.Applied apply(@PathVariable UUID sessionId, @RequestBody ApplyRequest req) {
        return service.apply(sessionId, req == null ? null : req.lines());
    }

    // No produces= on the mapping (like the label endpoints): a JSON error body must stay writable.
    @GetMapping("/import/template")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) {
        ReceivingImportService.Template t = service.template(format);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(t.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + t.filename() + "\"")
            .body(t.bytes());
    }
}
