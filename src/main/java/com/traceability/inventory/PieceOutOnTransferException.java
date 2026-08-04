package com.traceability.inventory;

import java.util.UUID;

/**
 * Thrown when an adjust (PieceAdjustService.adjustPiece) is attempted on a piece that is
 * currently out_on_transfer. Distinct from PieceCommittedException — there is no order to
 * point at, only the transfer that owns the piece. The three reconcile-only ALLOWED edges
 * (out_on_transfer:available/damaged/lost) are legal ONLY through TransferService, which
 * resolves the matching transfer_pieces row (outcome, line counters) in the same
 * transaction as the ledger transition. Any other caller reaching those edges leaves that
 * row orphaned (outcome IS NULL forever) and permanently blocks closeTransfer() for that
 * transfer — this exception is the guard that stops that from happening.
 */
public class PieceOutOnTransferException extends RuntimeException {

    private final UUID transferId;

    public PieceOutOnTransferException(UUID transferId) {
        super("Piece is out on transfer " + transferId);
        this.transferId = transferId;
    }

    public UUID getTransferId() { return transferId; }
}
