package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Pick &amp; Pack S2 — batch waybill printing.
 *
 * Access: {@code isAuthenticated()}, the same expression as every other Pick &amp; Pack
 * endpoint (FulfillController) — owner, manager and worker can all print; packers do it in
 * practice. Tenant scoping is RLS via the JWT tenant.
 */
@RestController
@RequestMapping("/api/v1/fulfill/print-batches")
public class PackPrintBatchController {

    private static final Logger log = LoggerFactory.getLogger(PackPrintBatchController.class);

    private final PackPrintBatchService svc;

    public PackPrintBatchController(PackPrintBatchService svc) {
        this.svc = svc;
    }

    /** Print dialog defaults (paper preselected from the store's Bosta label setting). */
    @GetMapping("/options")
    @PreAuthorize("isAuthenticated()")
    public PackPrintBatchService.PrintOptions options() {
        return svc.options();
    }

    /**
     * The synchronous print (≤ 49). The UI prints through POST /api/v1/fulfill/print-jobs since merged-PDF
     * slice B; kept unchanged so nothing that still calls it breaks.
     * @deprecated remove after merged-PDF jobs are stable in prod — the WARN below shows whether anything
     *             still calls it.
     */
    @Deprecated
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public PackPrintBatchService.PrintBatchResult print(
            @RequestBody PrintRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        log.warn("Deprecated POST /api/v1/fulfill/print-batches called: tenant={} user={} — the UI uses print-jobs",
            principal.tenantId(), principal.userId());
        return svc.print(req.scope(), req.paper(), req.sort(), principal.userId());
    }

    public record PrintRequest(String scope, String paper, String sort) {}
}
