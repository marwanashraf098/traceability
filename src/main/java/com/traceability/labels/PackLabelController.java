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
import java.util.UUID;

/**
 * Flexible labeling slice 4 — packing labels. 404 unless the tenant has flexible labeling. Same roles as
 * "Print waybills" ({@code isAuthenticated()}, like every Pick &amp; Pack endpoint); tenant scoping is RLS.
 *
 *   POST /api/v1/pack-labels/preview      {scope new|all|last, sort oldest|newest} | {orderId} → summary, nothing issued
 *   POST /api/v1/pack-labels/print        same body → issues what's missing (reusing a line's unbound codes) → summary + parts
 *   POST /api/v1/pack-labels/pdf          {orderIds} (one part, in sequence) → the label PDF (labels + separators)
 *   POST /api/v1/pack-labels/replacement  {orderId, orderItemId, idempotencyKey} → a 1-label PDF (kind replacement)
 *
 * The PDF endpoints declare no {@code produces}: a refusal must still answer its JSON body (ApiExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1/pack-labels")
public class PackLabelController {

    public record PdfRequest(List<UUID> orderIds) {}
    public record ReplacementRequest(UUID orderId, UUID orderItemId, String idempotencyKey) {}

    private final PackLabelService service;

    public PackLabelController(PackLabelService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @PreAuthorize("isAuthenticated()")
    public PackLabelService.Summary preview(@RequestBody PackLabelService.Request req) {
        return service.preview(req);
    }

    /** "Print for packing": waybill + label counts for one scope + sort (switch on; same roles as preview). */
    @PostMapping("/plan")
    @PreAuthorize("isAuthenticated()")
    public PackLabelService.PrintPlan plan(@RequestBody PackLabelService.Request req) {
        return service.printPlan(req);
    }

    @PostMapping("/print")
    @PreAuthorize("isAuthenticated()")
    public PackLabelService.Summary print(@RequestBody PackLabelService.Request req,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        return service.print(req, principal.userId());
    }

    @PostMapping("/pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> pdf(@RequestBody PdfRequest req) throws IOException {
        return pdfResponse(service.pdf(req.orderIds()), "pack-labels.pdf");
    }

    @PostMapping("/replacement")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> replacement(@RequestBody ReplacementRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        return pdfResponse(service.replacement(req.orderId(), req.orderItemId(), req.idempotencyKey(), principal.userId()),
            "replacement-label.pdf");
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(bytes);
    }
}
