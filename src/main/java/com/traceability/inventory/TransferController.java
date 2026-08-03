package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * FR-22 — Transfers & External Custody.
 *
 * FR-22.5 only: the reprint-outstanding-labels endpoint. createTransfer/scanOut/reconcile*
 * endpoints, i18n error bodies, and LookupService phraseKey wiring land in FR-22.6.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferSvc;

    public TransferController(TransferService transferSvc) {
        this.transferSvc = transferSvc;
    }

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
}
