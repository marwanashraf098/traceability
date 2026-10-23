package com.traceability.labels;

import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.PhoneScanSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
 *   POST /api/v1/tag-stock/sessions/{id}/replacement       {variantId, idempotencyKey} → batch of 1
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

    private final TagStockService service;
    private final PhoneScanSource phoneScanSource;

    public TagStockController(TagStockService service, PhoneScanSource phoneScanSource) {
        this.service = service;
        this.phoneScanSource = phoneScanSource;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TagStockService.Overview overview(@RequestParam(required = false) String q) {
        return service.overview(q);
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
