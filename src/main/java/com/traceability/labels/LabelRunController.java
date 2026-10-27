package com.traceability.labels;

import com.traceability.identity.CustomUserDetails;
import com.traceability.receiving.ImportException;
import com.traceability.receiving.ReceivingImportService;
import com.traceability.receiving.ShopifyInventoryFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Flexible labeling slice 6a — factory runs (Receiving → Factory runs). Owner + manager; 404 unless the tenant has
 * flexible labeling.
 *
 *   GET  /api/v1/label-runs                    runs, newest first (expected / printed / received per run)
 *   POST /api/v1/label-runs                    {factoryName, reference?, lines: [{variantId, expectedQty}]} → run
 *   GET  /api/v1/label-runs/{id}               the run: lines, totals, label parts
 *   POST /api/v1/label-runs/{id}/print         issues the codes each line still lacks (none on a second call) → run
 *   GET  /api/v1/label-runs/{id}/pdf?part=n    part n (≤ 2,000 labels) of the run's unused labels — also the reprint
 *   POST /api/v1/label-runs/import/preview     multipart file → matched rows, "On hand (new)" as expected quantity
 */
@RestController
@RequestMapping("/api/v1/label-runs")
public class LabelRunController {

    public record CreateRequest(String factoryName, String reference, List<LabelRunService.CreateLine> lines) {}

    private final LabelRunService service;

    public LabelRunController(LabelRunService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<LabelRunService.RunSummary> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<LabelRunService.RunDetail> create(@RequestBody(required = false) CreateRequest req,
                                                            @AuthenticationPrincipal CustomUserDetails principal) {
        LabelRunService.RunDetail run = service.create(principal.userId(),
            req == null ? null : req.factoryName(), req == null ? null : req.reference(), req == null ? null : req.lines());
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LabelRunService.RunDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/print")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LabelRunService.PrintResult print(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.print(id, principal.userId());
    }

    // No produces= on the mapping (like the other label endpoints): a JSON error body must stay writable.
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(defaultValue = "1") int part) throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factory-run-labels-" + id + "-part-" + part + ".pdf\"")
            .body(service.pdf(id, part));
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ReceivingImportService.Preview importPreview(@RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) throw ImportException.emptyFile();
        if (file.getSize() > ShopifyInventoryFile.MAX_BYTES) throw ImportException.tooLarge();
        return service.importPreview(file.getOriginalFilename(), file.getBytes());
    }
}
