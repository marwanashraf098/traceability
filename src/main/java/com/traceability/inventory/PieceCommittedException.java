package com.traceability.inventory;

import java.util.UUID;

/**
 * Thrown when an adjust or release-for-adjust is attempted on a piece that is currently
 * reserved or packed against an active order. Callers map this to HTTP 409 PIECE_COMMITTED
 * with the blocking order details so the UI can explain which order must be released first.
 */
public class PieceCommittedException extends RuntimeException {

    private final UUID   orderId;
    private final String orderNumber;

    public PieceCommittedException(UUID orderId, String orderNumber) {
        super("Piece is committed to order " + orderNumber);
        this.orderId      = orderId;
        this.orderNumber  = orderNumber;
    }

    public UUID   getOrderId()      { return orderId; }
    public String getOrderNumber()  { return orderNumber; }
}
