package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Lookup "Reprint label" — a fresh copy of a piece's EXISTING label (same short code, never a
 * new one) for a torn, smudged or missing sticker.
 *
 * Not a status change: the audit is InventoryLedger.recordLabelReprinted() (piece_events
 * 'label_reprinted', from_status = to_status = current status — the established non-status
 * write path Returns and Transfers already use). Never transition(), never a Shopify call.
 *
 * Separate from the Returns reprint endpoints on purpose — those gates are not widened
 * (see ReturnSessionService.validateAndRecordReprint()).
 */
@Service
public class PieceLabelReprintService {

    /**
     * The ONE allow-list: pieces physically on our shelf. Read by reprint() and by
     * LookupService (the lookup response's canReprint) — the frontend never repeats it.
     */
    public static final Set<PieceStatus> REPRINTABLE = EnumSet.of(
        PieceStatus.AVAILABLE,
        PieceStatus.ON_HOLD,
        PieceStatus.DAMAGED,
        PieceStatus.RETURN_PENDING_INSPECTION,
        PieceStatus.RESERVED);

    public static boolean canReprint(String statusDb) {
        return statusDb != null && REPRINTABLE.contains(PieceStatus.fromDb(statusDb));
    }

    private final JdbcTemplate     jdbc;
    private final InventoryLedger  ledger;
    private final LabelService     labelService;

    public PieceLabelReprintService(JdbcTemplate jdbc, InventoryLedger ledger, LabelService labelService) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.labelService = labelService;
    }

    /**
     * Checks the status, records the reprint, renders the PDF — one transaction, so a render
     * failure rolls the event back. The piece row is locked (FOR UPDATE) so the status checked
     * here is the status the event records; a concurrent transition waits for this to commit.
     *
     * @throws ResponseStatusException 404 when the piece isn't this tenant's (or doesn't exist)
     * @throws ReprintNotAllowedException 422 when the status isn't in {@link #REPRINTABLE}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = IOException.class)
    public byte[] reprint(String pieceId, UUID actorUserId) throws IOException {
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ? FOR UPDATE",
            rs -> rs.next() ? rs.getString(1) : null,
            pieceId, tenantId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        if (!canReprint(status)) {
            throw new ReprintNotAllowedException();
        }
        UntaggedPieces.refuse(jdbc, tenantId, pieceId);   // no code to reprint — before any event

        // Same context ReturnSessionService.recordReprint() records: the piece's location, its
        // order, and that order's forward shipment (newest first — an order can have a
        // terminated forward leg next to the live one).
        Context ctx = jdbc.query(
            "SELECT p.current_location_id, p.current_order_id, " +
            "       (SELECT s.id FROM shipments s " +
            "         WHERE s.order_id = p.current_order_id AND s.tenant_id = p.tenant_id " +
            "           AND s.shipment_leg = 'forward' " +
            "         ORDER BY s.created_at DESC, s.id DESC LIMIT 1) AS shipment_id " +
            "FROM pieces p WHERE p.id = ? AND p.tenant_id = ?",
            rs -> {
                rs.next();
                return new Context(
                    rs.getObject("current_location_id", UUID.class),
                    rs.getObject("current_order_id",    UUID.class),
                    rs.getObject("shipment_id",         UUID.class));
            },
            pieceId, tenantId);

        ledger.recordLabelReprinted(pieceId, actorUserId, ctx.locationId(), ctx.orderId(), ctx.shipmentId());

        return labelService.generatePieceLabel(pieceId, null, null);
    }

    private record Context(UUID locationId, UUID orderId, UUID shipmentId) {}
}
