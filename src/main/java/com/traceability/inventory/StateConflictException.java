package com.traceability.inventory;

/**
 * Thrown when the conditional UPDATE in transition() returns 0 rows:
 * either the piece status was not what the caller expected (concurrent change),
 * or the piece is invisible under the current RLS context.
 */
public class StateConflictException extends RuntimeException {

    private final String pieceId;
    private final PieceStatus expected;
    private final PieceStatus actual;  // null = piece not found / no RLS access

    public StateConflictException(String pieceId, PieceStatus expected, PieceStatus actual) {
        super(String.format("Piece %s: expected status %s but was %s",
                pieceId, expected,
                actual == null ? "not found / no access" : actual));
        this.pieceId  = pieceId;
        this.expected = expected;
        this.actual   = actual;
    }

    public String getPieceId()      { return pieceId; }
    public PieceStatus getExpected() { return expected; }
    public PieceStatus getActual()   { return actual; }
}
