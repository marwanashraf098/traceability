package com.traceability.inventory;

/**
 * Thrown when the diagnostic SELECT after a zero-row UPDATE returns no row:
 * the piece does not exist in this tenant context, or RLS has made it invisible.
 *
 * Callers map this to rejection code PIECE_NOT_FOUND, distinct from
 * StateConflictException (WRONG_STATUS / ALREADY_RESERVED).
 */
public class PieceNotFoundException extends RuntimeException {

    private final String pieceId;

    public PieceNotFoundException(String pieceId) {
        super("Piece not found or not accessible in current tenant context: " + pieceId);
        this.pieceId = pieceId;
    }

    public String getPieceId() { return pieceId; }
}
