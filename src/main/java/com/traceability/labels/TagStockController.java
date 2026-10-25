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
import java.util.Map;
import java.util.UUID;

/**
 * Flexible labeling slice 3 — Tag stock (owner + manager in v1; main warehouse only). 404 unless the tenant has
 * flexible labeling. Label PDFs come from GET /api/v1/label-codes/batches/{id}/pdf (named piece labels).
 *
 *   GET  /api/v1/tag-stock?q=                              variants with untagged stock + warehouse totals
 *   POST /api/v1/tag-stock/sessions                        start (or resume the caller's open session)
 *   GET  /api/v1/tag-stock/sessions/{id}                   per-variant printed / tagged / unused
 *   POST /api/v1/tag-stock/sessions/{id}/print             {variantId, count, idempotencyKey} → batch
 *   POST /api/v1/tag-stock/sessions/{id}/reprint           {variantId} → PDF of this session's unused tag labels (none issued)
 *   POST /api/v1/tag-stock/sessions/{id}/replacement       {variantId, idempotencyKey} → batch of 1 (an unused
 *                                                          replacement code of the variant is reused first)
 *   POST /api/v1/tag-stock/leftovers/{variantId}/pdf       labels left unused by ended sessions, reprinted
 *                                                          (void them with POST /api/v1/label-codes/void)
 *   POST /api/v1/tag-stock/sessions/{id}/scan              {code, relayEventId?} → TAGGED | ALREADY_TAGGED | REJECTED:…
 *   POST /api/v1/tag-stock/sessions/{id}/end
 *   POST /api/v1/tag-stock/sessions/{id}/void-unused       {variantId?} → {voided}
 */
@RestController
@RequestMapping("/api/v1/tag-stock")
public class TagStockController {

    public record PrintRequest(UUID variantId, Integer count, String idempotencyKey) {}
    public record ReplacementRequest(UUID variantId, String idempotencyKey) {}
    public record ScanRequest(String code, UUID relayEventId) {}
    public record VoidRequest(UUID variantId) {}
    public record ReprintRequest(UUID variantId) {}

    private final TagStockService service;
    private final PhoneScanSource phoneScanSource;

    public TagStockController(TagStockService service, PhoneScanSource phoneScanSource) {
        this.service = service;
        this.phoneScanSource = phoneScanSource;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.Overview overview(@RequestParam(required = false) String q,
                                             @AuthenticationPrincipal CustomUserDetails principal) {
        return service.overview(q, principal.userId());
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.Session start(@AuthenticationPrincipal CustomUserDetails principal) {
        return service.start(principal.userId());
    }

    @GetMapping("/sessions/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.Session get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/sessions/{id}/print")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LabelCodeService.Batch print(@PathVariable UUID id, @RequestBody PrintRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.variantId() == null || req.count() == null || req.count() < 1) throw LabelCodeException.invalidCount();
        return service.print(id, req.variantId(), req.count(), req.idempotencyKey(), principal.userId());
    }

    // No produces= (like the label endpoints): a JSON error body must stay writable.
    @PostMapping("/sessions/{id}/reprint")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> reprint(@PathVariable UUID id, @RequestBody ReprintRequest req) throws IOException {
        if (req == null || req.variantId() == null) throw LabelCodeException.unknownVariant();
        return pdf(service.reprintUnused(id, req.variantId()), "tag-labels-" + id);
    }

    @PostMapping("/leftovers/{variantId}/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> leftoverPdf(@PathVariable UUID variantId) throws IOException {
        return pdf(service.leftoverPdf(variantId), "unused-labels-" + variantId);
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + ".pdf\"")
            .body(bytes);
    }

    @PostMapping("/sessions/{id}/replacement")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LabelCodeService.Batch replacement(@PathVariable UUID id, @RequestBody ReplacementRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.variantId() == null) throw LabelCodeException.unknownVariant();
        return service.replacement(id, req.variantId(), req.idempotencyKey(), principal.userId());
    }

    @PostMapping("/sessions/{id}/scan")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.ScanOutcome scan(@PathVariable UUID id, @RequestBody ScanRequest req,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
        String code = req == null ? null : req.code();
        // 'via phone' only when the relay event verifies as the caller's own (PhoneScanSource), never client-sent.
        boolean viaPhone = req != null && phoneScanSource.isPhone(req.relayEventId(), code, principal.userId());
        return service.scan(id, code, principal.userId(), viaPhone);
    }

    @PostMapping("/sessions/{id}/end")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.Session end(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.end(id, principal.userId());
    }

    @PostMapping("/sessions/{id}/void-unused")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> voidUnused(@PathVariable UUID id, @RequestBody(required = false) VoidRequest req) {
        return Map.of("voided", service.voidUnused(id, req == null ? null : req.variantId()));
    }
}
