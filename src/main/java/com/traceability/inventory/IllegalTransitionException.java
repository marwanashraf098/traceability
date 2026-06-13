package com.traceability.inventory;

/**
 * Thrown before any DB access when the (expectedStatus → newStatus) pair
 * is not in the piece state machine's allowed-transition set.
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(PieceStatus from, PieceStatus to) {
        super(String.format(
                "Transition %s → %s is not permitted by the piece state machine", from, to));
    }
}
