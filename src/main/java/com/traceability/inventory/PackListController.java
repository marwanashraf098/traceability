package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Pick &amp; Pack S4 — waybill-mode page lists, batch reprint, session summary.
 * Access: {@code isAuthenticated()} like every Pick &amp; Pack endpoint (workers print and pack);
 * tenant scoping is RLS. The summary is the caller's own session only (403 / 404 otherwise).
 * Resolving the two manager exceptions stays on the existing owner/manager-only
 * POST /api/v1/exceptions/resolve.
 */
@RestController
@RequestMapping("/api/v1")
public class PackListController {

    private static final Logger log = LoggerFactory.getLogger(PackListController.class);

    private final PackListService svc;

    public PackListController(PackListService svc) {
        this.svc = svc;
    }

    /** Print batches created today (Africa/Cairo), newest first, with progress counts. */
    @GetMapping("/fulfill/print-batches/today")
    @PreAuthorize("isAuthenticated()")
    public List<PackListService.BatchToday> batchesToday() {
        return svc.batchesToday();
    }

    /** Regenerate one batch's PDF in its stored order; writes no batch row. */
    /**
     * The synchronous reprint. The UI reprints through POST /api/v1/fulfill/print-jobs (kind 'reprint')
     * since merged-PDF slice B; kept unchanged so nothing that still calls it breaks.
     * @deprecated remove after merged-PDF jobs are stable in prod — the WARN below shows whether anything
     *             still calls it.
     */
    @Deprecated
    @PostMapping("/fulfill/print-batches/{batchId}/reprint")
    @PreAuthorize("isAuthenticated()")
    public PackPrintBatchService.PrintBatchResult reprint(@PathVariable UUID batchId) {
        log.warn("Deprecated POST /api/v1/fulfill/print-batches/{}/reprint called: tenant={} — the UI uses print-jobs",
            batchId, TenantContext.get());
        return svc.reprint(batchId);
    }

    /** Printed waybills whose order isn't packed yet (+ unresolved cancelled-after-print). */
    @GetMapping("/fulfill/printed-not-packed")
    @PreAuthorize("isAuthenticated()")
    public List<PackListService.NotPackedRow> printedNotPacked() {
        return svc.printedNotPacked();
    }

    @GetMapping("/pack-sessions/{sessionId}/summary")
    @PreAuthorize("isAuthenticated()")
    public PackListService.SessionSummary summary(@PathVariable UUID sessionId,
                                                  @AuthenticationPrincipal CustomUserDetails p) {
        return svc.summary(sessionId, p.userId());
    }
}
