package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-21: Stock Taking.
 *
 * POST /api/v1/stock-takes/sessions — open a session + snapshot the full piece
 * population in scope (Step 2).
 */
@RestController
@RequestMapping("/api/v1/stock-takes")
public class StockTakeController {

    private final StockTakeService stockTake;
    private final StockTakeReconciliationService reconciliation;

    public StockTakeController(StockTakeService stockTake,
                               StockTakeReconciliationService reconciliation) {
        this.stockTake = stockTake;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> openSession(
            @RequestBody OpenSessionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        List<UUID> variantIds = req.variantIds() == null ? null :
            req.variantIds().stream().map(UUID::fromString).toList();
        UUID locationId = req.locationId() != null ? UUID.fromString(req.locationId()) : null;

        Map<String, Object> result = stockTake.openSession(
            req.scopeType(), variantIds, locationId, req.note(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> listSessions() {
        return stockTake.listSessions();
    }

    /** Landing analytics band — 4-tile calm row, derive-on-read, no client-side scraping. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public StockTakeService.StockTakeSummaryCounts summary() {
        return stockTake.summary();
    }

    /** WORKER+ — the scan screen loads session context before any counting starts. */
    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getSession(@PathVariable UUID sessionId) {
        return stockTake.getSessionDetail(sessionId);
    }

    /** Blind scan: the response never includes expected quantities, only the classification. */
    @PostMapping("/sessions/{sessionId}/scan")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> scan(
            @PathVariable UUID sessionId,
            @RequestBody ScanRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return stockTake.scan(sessionId, req.barcode(), req.condition(), principal.userId());
    }

    /** Undo a scan — WORKER+, same role as scan(). Idempotent: no-op on an unscanned piece. */
    @DeleteMapping("/sessions/{sessionId}/scan/{pieceId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unscan(@PathVariable UUID sessionId, @PathVariable String pieceId) {
        stockTake.unscan(sessionId, pieceId);
    }

    // ── Reconciliation + resolutions (Step 4) ────────────────────────────────

    @GetMapping("/sessions/{sessionId}/reconciliation")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> reconciliation(@PathVariable UUID sessionId) {
        return reconciliation.reconciliation(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/resolve")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> resolve(
            @PathVariable UUID sessionId,
            @RequestBody List<StockTakeReconciliationService.ResolveItem> items,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconciliation.resolve(sessionId, items, principal.userId());
    }

    @PostMapping("/sessions/{sessionId}/attest-complete")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> attestComplete(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconciliation.attestComplete(sessionId, principal.userId());
    }

    @PostMapping("/sessions/{sessionId}/finalize")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> finalizeSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconciliation.finalizeSession(sessionId, principal.userId());
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        reconciliation.cancel(sessionId, principal.userId());
    }

    // ── Step 6.1: failed_ambiguous ops tail ──────────────────────────────────

    @PostMapping("/sessions/{sessionId}/sync/mark-resolved")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> markSyncResolved(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconciliation.markSyncResolved(sessionId, principal.userId());
    }

    @PostMapping("/sessions/{sessionId}/sync/repush")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> repushSync(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return reconciliation.repushSync(sessionId, principal.userId());
    }

    public record OpenSessionRequest(
        String scopeType, List<String> variantIds, String locationId, String note) {}

    public record ScanRequest(String barcode, String condition) {}
}
