package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-22 — Transfers & External Custody.
 *
 * scan-out mirrors FulfillController's /scan endpoint exactly (Invariant 7): returns the
 * TransferService.ScanOutResult/ScanBackResult record directly as the response body, 200
 * whether the scan succeeds or is cleanly rejected — the frontend reads .success()/.code()
 * client-side, the same contract as FulfillService.scan(). "Command" endpoints
 * (create/begin-reconcile/classify/close) instead throw TransferException on failure, mapped
 * by ApiExceptionHandler's single TransferException handler (added in FR-22.3, reused here —
 * not duplicated) to {code, message_en, message_ar}.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferSvc;

    public TransferController(TransferService transferSvc) {
        this.transferSvc = transferSvc;
    }

    // ── Create ───────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> createTransfer(
            @RequestBody CreateTransferRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        UUID id = transferSvc.createTransfer(
            req.transferType(), UUID.fromString(req.destinationLocationId()),
            req.expectedReturnAt(), req.note(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id.toString()));
    }

    // ── Send-out scan ────────────────────────────────────────────────────────

    /** Mirrors pick permissions — any authenticated role may send a piece out on a transfer. */
    @PostMapping("/{transferId}/scan-out")
    @PreAuthorize("isAuthenticated()")
    public TransferService.ScanOutResult scanOut(
            @PathVariable UUID transferId,
            @RequestBody ScanRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return transferSvc.scanOut(transferId, req.barcode(), principal.userId());
    }

    // ── List / get (consignment view) ───────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listOpen() {
        return transferSvc.listOpen();
    }

    @GetMapping("/{transferId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getTransfer(@PathVariable UUID transferId) {
        return transferSvc.getTransfer(transferId);
    }

    // ── Reconcile ────────────────────────────────────────────────────────────

    @PostMapping("/{transferId}/begin-reconcile")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void beginReconcile(@PathVariable UUID transferId,
                              @AuthenticationPrincipal CustomUserDetails principal) {
        transferSvc.beginReconcile(transferId, principal.userId());
    }

    @PostMapping("/{transferId}/scan-back")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TransferService.ScanBackResult reconcileScanBack(
            @PathVariable UUID transferId,
            @RequestBody ScanBackRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return transferSvc.reconcileScanBack(transferId, req.barcode(), req.condition(), principal.userId());
    }

    @PostMapping("/{transferId}/classify")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void classifyShortfall(
            @PathVariable UUID transferId,
            @RequestBody ClassifyRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        transferSvc.classifyShortfall(transferId, UUID.fromString(req.lineId()),
            new TransferService.ShortfallCounts(req.sold(), req.lost(), req.condemnedNotReturned()),
            principal.userId());
    }

    @PostMapping("/{transferId}/close")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeTransfer(@PathVariable UUID transferId,
                             @AuthenticationPrincipal CustomUserDetails principal) {
        transferSvc.closeTransfer(transferId, principal.userId());
    }

    // ── Reprint outstanding labels (FR-22.5) ────────────────────────────────

    /**
     * Reprints the barcodes of every piece still outstanding on this transfer, merged into
     * one PDF. MANAGER/OWNER only — the physical relabel step is a reconcile-adjacent
     * inventory-management action, not a warehouse-floor scan action.
     */
    @PostMapping(value = "/{transferId}/reprint-outstanding", produces = "application/pdf")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> reprintOutstandingLabels(
            @PathVariable UUID transferId,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        byte[] pdf = transferSvc.reprintOutstandingLabels(transferId, principal.userId());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"reprint-outstanding-" + transferId + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    // ── Request records ──────────────────────────────────────────────────────

    public record CreateTransferRequest(
        String transferType, String destinationLocationId, Instant expectedReturnAt, String note) {}

    public record ScanRequest(String barcode) {}

    public record ScanBackRequest(String barcode, String condition) {}

    public record ClassifyRequest(String lineId, int sold, int lost, int condemnedNotReturned) {}
}
