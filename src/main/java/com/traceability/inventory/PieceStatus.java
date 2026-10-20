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
    DESTROYED("destroyed"),
    OUT_ON_TRANSFER("out_on_transfer"),
    SOLD("sold"),
    VOIDED("voided"),
    ON_HOLD("on_hold"),
    TRANSFERRED_OUT("transferred_out");

    /**
     * A piece in one of these has left Traced custody for its order: with the courier (or collected),
     * delivered, or on its way back / back but not yet inspected. Derived from the declaration order —
     * the courier / return lifecycle WITH_COURIER..RETURN_PENDING_INSPECTION stays contiguous
     * (OrderLineFreezeTest pins the set). Used by {@link OrderLineFreeze}.
     */
    public static final java.util.Set<PieceStatus> LEFT_CUSTODY =
        java.util.Collections.unmodifiableSet(java.util.EnumSet.range(WITH_COURIER, RETURN_PENDING_INSPECTION));

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
