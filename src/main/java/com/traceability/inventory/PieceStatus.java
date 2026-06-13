package com.traceability.inventory;

/** Mirrors the piece_status SQL enum. Use .db to get the lowercase value for JDBC casts. */
public enum PieceStatus {

    AVAILABLE("available"),
    RESERVED("reserved"),
    PACKED("packed"),
    AWAITING_PICKUP("awaiting_pickup"),
    WITH_COURIER("with_courier"),
    DELIVERED("delivered"),
    RETURN_IN_TRANSIT("return_in_transit"),
    RETURN_PENDING_INSPECTION("return_pending_inspection"),
    DAMAGED("damaged"),
    LOST("lost"),
    DESTROYED("destroyed");

    /** Lowercase value matching the PostgreSQL enum literal. */
    public final String db;

    PieceStatus(String db) {
        this.db = db;
    }

    public static PieceStatus fromDb(String s) {
        for (PieceStatus p : values()) {
            if (p.db.equals(s)) return p;
        }
        throw new IllegalArgumentException("Unknown piece status: " + s);
    }
}
