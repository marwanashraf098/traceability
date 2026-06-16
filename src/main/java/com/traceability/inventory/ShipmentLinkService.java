package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/**
 * Mode-B fulfillment linking: AWB-scan at pack, delivery auto-matching, manual link.
 *
 * Three entry points:
 *   linkByAwbScan()    — packer scans the plugin-printed AWB at pack time
 *   tryMatchDelivery() — called by BostaWebhookJob before recordUnlinked(); tries
 *                        businessReference then phone+COD fallback
 *   manualLink()       — operator manually links an unlinked_bosta_deliveries row to an order
 *
 * Swapped-AWB rejection: if a tracking_number already belongs to a DIFFERENT order,
 * linkByAwbScan() throws 409 with the conflicting order number. This catches label-mix
 * at the pack station before handoff.
 *
 * Idempotency: all write paths check for existing rows and skip gracefully.
 * transitionPackedPieces() catches StateConflictException(actual==AWAITING_PICKUP)
 * and skips — safe to call twice.
 */
@Service
public class ShipmentLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentLinkService.class);

    private final JdbcTemplate     jdbc;
    private final InventoryLedger  ledger;
    private final BostaStateMapper stateMapper;

    public ShipmentLinkService(JdbcTemplate jdbc,
                                InventoryLedger ledger,
                                BostaStateMapper stateMapper) {
        this.jdbc        = jdbc;
        this.ledger      = ledger;
        this.stateMapper = stateMapper;
    }

    // ── AWB scan at pack (FR-9.6) ─────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> linkByAwbScan(UUID orderId, String trackingNumber, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // 1. Verify order exists and is in a linkable state
        String[] orderInfo = jdbc.query(
            "SELECT status, number FROM orders WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new String[]{rs.getString("status"), rs.getString("number")} : null,
            orderId, tenantId);
        if (orderInfo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String orderStatus = orderInfo[0];
        String orderNumber = orderInfo[1];
        if (!"packed".equals(orderStatus) && !"awaiting_pickup".equals(orderStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order must be in 'packed' state to link an AWB (current: " + orderStatus + ")");
        }

        // 2. Swapped-AWB check: does this tracking_number already belong to a DIFFERENT order?
        UUID shipmentId = handleSwappedAwbCheck(trackingNumber, tenantId, orderId, orderNumber);

        // 3. Create shipment if not already present
        if (shipmentId == null) {
            shipmentId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO shipments " +
                "(id, tenant_id, order_id, provider, tracking_number, internal_state) " +
                "VALUES (?, ?, ?, 'bosta', ?, 'created')",
                shipmentId, tenantId, orderId, trackingNumber);
        }

        // 4. Transition packed pieces → awaiting_pickup (writes tracking_linked events)
        int linked = transitionPackedPieces(orderId, shipmentId, tenantId, actorUserId);

        // 5. Advance order to awaiting_pickup (idempotent WHERE clause)
        jdbc.update(
            "UPDATE orders SET status = 'awaiting_pickup' " +
            "WHERE id = ? AND tenant_id = ? AND status = 'packed'",
            orderId, tenantId);

        // 6. Resolve any previously-unlinked entry for this tracking number
        resolveUnlinked(tenantId, trackingNumber);

        log.info("Order {} AWB-linked to {} ({} pieces)", orderNumber, trackingNumber, linked);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId",     shipmentId.toString());
        result.put("trackingNumber", trackingNumber);
        result.put("linkedPieces",   linked);
        result.put("orderStatus",    "awaiting_pickup");
        return result;
    }

    // ── Auto-match from webhook ───────────────────────────────────────────────

    /**
     * Tries to match a Bosta delivery to an order.
     * Called by BostaWebhookJob before recordUnlinked().
     * Returns the matched orderId, or null if unresolvable.
     *
     * Not annotated @Transactional — BostaWebhookJob runs its own TransactionTemplate
     * blocks; individual writes inside this method go through ledger.transition()
     * (which is @Transactional) and the direct jdbc.update() calls which each
     * auto-commit in the absence of an outer transaction.
     */
    public UUID tryMatchDelivery(UUID tenantId, String trackingNumber,
                                  BostaDelivery delivery, BostaStateMapper.MappedState mapped) {
        // Step 1 — businessReference → order number or external_id
        UUID orderId = matchByBusinessReference(tenantId, delivery.businessReference());

        // Step 2 — phone + COD fallback
        if (orderId == null) {
            orderId = matchByPhoneAndCod(tenantId, delivery);
        }

        if (orderId == null) return null;

        // Step 3 — create/find shipment and link
        UUID shipmentId = createOrFindShipment(
            tenantId, orderId, trackingNumber, mapped.shipmentInternalState(), delivery);

        transitionPackedPieces(orderId, shipmentId, tenantId, null);

        // Advance order if it is currently packed
        jdbc.update(
            "UPDATE orders SET status = 'awaiting_pickup' " +
            "WHERE id = ? AND tenant_id = ? AND status = 'packed'",
            orderId, tenantId);

        log.info("Auto-matched delivery {} to order {}", trackingNumber, orderId);
        return orderId;
    }

    // ── Manual link ───────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void manualLink(long unlinkedId, UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        Object[] row = jdbc.query(
            "SELECT tracking_number, bosta_state_code, bosta_order_type " +
            "FROM unlinked_bosta_deliveries " +
            "WHERE id = ? AND tenant_id = ? AND resolved = false",
            rs -> rs.next() ? new Object[]{
                rs.getString("tracking_number"),
                rs.getInt("bosta_state_code"),
                rs.getString("bosta_order_type")} : null,
            unlinkedId, tenantId);

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Unlinked delivery not found or already resolved");
        }

        String trackingNumber = (String) row[0];
        BostaStateMapper.MappedState mapped = stateMapper.map((Integer) row[1], (String) row[2]);

        // Swapped-AWB check applies to manual link too
        handleSwappedAwbCheck(trackingNumber, tenantId, orderId, null);

        UUID shipmentId = createOrFindShipment(
            tenantId, orderId, trackingNumber, mapped.shipmentInternalState(), null);

        transitionPackedPieces(orderId, shipmentId, tenantId, actorUserId);

        jdbc.update(
            "UPDATE orders SET status = 'awaiting_pickup' " +
            "WHERE id = ? AND tenant_id = ? AND status = 'packed'",
            orderId, tenantId);

        jdbc.update(
            "UPDATE unlinked_bosta_deliveries SET resolved = true WHERE id = ?",
            unlinkedId);
    }

    // ── List unlinked ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> listUnlinked(int page, int size) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT id, tracking_number, business_reference, bosta_state_code, " +
            "       bosta_order_type, first_seen_at, last_seen_at " +
            "FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND resolved = false " +
            "ORDER BY first_seen_at DESC LIMIT ? OFFSET ?",
            tenantId, size, page * size);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Checks whether this tracking_number already belongs to a DIFFERENT order.
     * Returns the existing shipment UUID if it belongs to THIS order (idempotent path),
     * or null if no shipment exists yet.
     * Throws 409 if it belongs to a different order (swapped label).
     */
    private UUID handleSwappedAwbCheck(String trackingNumber, UUID tenantId,
                                        UUID orderId, String orderNumber) {
        return jdbc.query(
            "SELECT s.id, s.order_id, o.number AS other_number " +
            "FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tracking_number = ? AND s.tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                UUID existingOrderId = rs.getObject("order_id", UUID.class);
                UUID existingShipId  = rs.getObject("id", UUID.class);
                if (existingOrderId.equals(orderId)) {
                    return existingShipId; // idempotent
                }
                String other = rs.getString("other_number");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AWB " + trackingNumber + " is already linked to order " +
                    (other != null ? other : existingOrderId) + " — possible swapped label");
            },
            trackingNumber, tenantId);
    }

    /**
     * Transitions all 'packed' pieces for an order to 'awaiting_pickup'
     * with event_type='tracking_linked' and the shipmentId in context.
     * Idempotent: pieces already at awaiting_pickup are skipped.
     */
    private int transitionPackedPieces(UUID orderId, UUID shipmentId,
                                        UUID tenantId, UUID actorUserId) {
        List<String> pieceIds = jdbc.queryForList(
            "SELECT a.piece_id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'packed'",
            String.class, orderId, tenantId);

        int count = 0;
        for (String pieceId : pieceIds) {
            try {
                ledger.transition(pieceId,
                    PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP,
                    "tracking_linked", actorUserId,
                    new TransitionContext(orderId, shipmentId, null, orderId, null));
                count++;
            } catch (StateConflictException e) {
                if (e.getActual() == PieceStatus.AWAITING_PICKUP) {
                    log.debug("Piece {} already at awaiting_pickup — idempotent skip", pieceId);
                } else {
                    log.warn("Piece {} unexpected state {} during AWB link — skipping",
                        pieceId, e.getActual());
                }
            } catch (IllegalTransitionException e) {
                log.warn("Piece {} cannot transition to awaiting_pickup — skipping: {}",
                    pieceId, e.getMessage());
            }
        }
        return count;
    }

    private UUID createOrFindShipment(UUID tenantId, UUID orderId, String trackingNumber,
                                       String internalState, BostaDelivery delivery) {
        UUID existing = jdbc.query(
            "SELECT id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            trackingNumber, tenantId);
        if (existing != null) return existing;

        UUID id = UUID.randomUUID();
        String rawJson = (delivery != null && delivery.raw() != null)
            ? delivery.raw().toString() : null;
        jdbc.update(
            "INSERT INTO shipments " +
            "(id, tenant_id, order_id, provider, tracking_number, internal_state, raw) " +
            "VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, ?::jsonb)",
            id, tenantId, orderId, trackingNumber, internalState, rawJson);
        return id;
    }

    private void resolveUnlinked(UUID tenantId, String trackingNumber) {
        jdbc.update(
            "UPDATE unlinked_bosta_deliveries SET resolved = true, last_seen_at = now() " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false",
            tenantId, trackingNumber);
    }

    // ---- matching helpers ---------------------------------------------------

    private UUID matchByBusinessReference(UUID tenantId, String businessRef) {
        if (businessRef == null || businessRef.isBlank()) return null;

        String stripped = businessRef.startsWith("#") ? businessRef.substring(1) : businessRef;
        String hashed   = "#" + stripped;

        return jdbc.query(
            "SELECT id FROM orders " +
            "WHERE tenant_id = ? " +
            "  AND (number = ? OR number = ? OR number = ? OR external_id = ?) " +
            "LIMIT 1",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId, businessRef, stripped, hashed, businessRef);
    }

    private UUID matchByPhoneAndCod(UUID tenantId, BostaDelivery delivery) {
        if (delivery.raw() == null) return null;
        JsonNode raw = delivery.raw();

        String rawPhone = raw.path("consignee").path("phone").asText(null);
        String normalized = normalizePhone(rawPhone);
        if (normalized == null) return null;

        JsonNode codNode = raw.path("cod").path("amount");
        if (codNode.isMissingNode() || codNode.isNull()) return null;
        BigDecimal cod;
        try { cod = new BigDecimal(codNode.asText()); }
        catch (NumberFormatException e) { return null; }

        return jdbc.query(
            "SELECT id FROM orders " +
            "WHERE tenant_id = ? " +
            "  AND customer_phone ILIKE ? " +
            "  AND cod_amount = ? " +
            "  AND status NOT IN ('cancelled','returned','lost') " +
            "ORDER BY placed_at DESC LIMIT 1",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId, "%" + normalized + "%", cod);
    }

    static String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        // Strip Egyptian country code (20) → convert to local 0-prefixed format
        if (digits.startsWith("20") && digits.length() == 12) digits = "0" + digits.substring(2);
        if (digits.startsWith("01") && digits.length() == 11) return digits;
        return null;
    }
}
