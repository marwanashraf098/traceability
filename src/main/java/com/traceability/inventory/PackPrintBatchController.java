package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
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

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public PackPrintBatchService.PrintBatchResult print(
            @RequestBody PrintRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.print(req.scope(), req.paper(), req.sort(), principal.userId());
    }

    public record PrintRequest(String scope, String paper, String sort) {}
}
