package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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

    // Reason codes written to unlinked_bosta_deliveries.match_reason (V19).
    public static final String REASON_NO_MATCH        = "NO_MATCH";
    public static final String REASON_AMBIGUOUS_MULTI = "AMBIGUOUS_MULTI";
    public static final String REASON_COD_ONLY        = "COD_ONLY_AMBIGUOUS";

    private final JdbcTemplate      jdbc;
    private final InventoryLedger   ledger;
    private final BostaStateMapper  stateMapper;
    private final BostaGateway      bostaGateway;
    private final EncryptionService encryptionService;
    private final BlocklistService  blocklist;
    private final ObjectMapper      mapper;

    public ShipmentLinkService(JdbcTemplate jdbc,
                                InventoryLedger ledger,
                                BostaStateMapper stateMapper,
                                BostaGateway bostaGateway,
                                EncryptionService encryptionService,
                                BlocklistService blocklist,
                                ObjectMapper mapper) {
        this.jdbc              = jdbc;
        this.ledger            = ledger;
        this.stateMapper       = stateMapper;
        this.bostaGateway      = bostaGateway;
        this.encryptionService = encryptionService;
        this.blocklist         = blocklist;
        this.mapper            = mapper;
    }

    /**
     * Result returned by tryMatchDelivery().
     * orderId != null → successfully linked.
     * orderId == null → unlinkedReason carries the match_reason for unlinked_bosta_deliveries.
     */
    public record LinkResult(UUID orderId, String unlinkedReason) {}

    // ---- private record for strong-key matching result ----------------------

    /**
     * Carries the result of the strong-key (businessReference / shopifyOrderId) lookup.
     * Three states:
     *   found(id)    — exactly one order matched; id is non-null.
     *   ambiguous()  — >1 order matched the same key (shouldn't happen; flag for manual resolution).
     *   notFound()   — no match; fall through to phone+COD.
     */
    private record StrongMatch(UUID orderId, boolean isAmbiguous) {
        static StrongMatch found(UUID id) { return new StrongMatch(id, false); }
        static StrongMatch ambiguous()    { return new StrongMatch(null, true); }
        static StrongMatch notFound()     { return new StrongMatch(null, false); }
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
        boolean isNewShipment = false;
        if (shipmentId == null) {
            try {
                shipmentId = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO shipments " +
                    "(id, tenant_id, order_id, provider, tracking_number, internal_state) " +
                    "VALUES (?, ?, ?, 'bosta', ?, 'created')",
                    shipmentId, tenantId, orderId, trackingNumber);
                isNewShipment = true;
            } catch (DuplicateKeyException e) {
                // ux_active_shipment_per_order: order already has an active shipment.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order already has an active shipment — resolve it before linking a new AWB");
            }
        }

        // FR-4.6 prerequisite: fetch Bosta _id for future cancel capability.
        // Non-blocking: if the fetch fails, the link still succeeds; flag is set for exception detector.
        // Only fetch for newly created shipments — re-links that already have provider_delivery_id skip.
        if (isNewShipment) {
            fetchAndStoreProviderDeliveryId(shipmentId, trackingNumber, tenantId);
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
     * Returns a LinkResult: orderId != null means linked; null means flagged for unlinked
     * with the reason in unlinkedReason.
     *
     * Not annotated @Transactional — BostaWebhookJob runs its own TransactionTemplate
     * blocks; individual writes inside this method go through ledger.transition()
     * (which is @Transactional) and the direct jdbc.update() calls which each
     * auto-commit in the absence of an outer transaction.
     */
    public LinkResult tryMatchDelivery(UUID tenantId, String trackingNumber,
                                        BostaDelivery delivery, BostaStateMapper.MappedState mapped) {
        // Step 1 — strong-key matching: businessReference (Shopify order number embedded
        // by the plugin) and shopifyInfo.orderId (numeric Shopify ID → gid: URI). These are
        // authoritative, unique identifiers. A single match here is definitive — phone+COD
        // is NEVER consulted. Ambiguity in phone+COD cannot veto a confident strong-key match.
        //
        // Guard: if >1 order somehow matches the same strong key (shouldn't happen for Shopify
        // order numbers), flag immediately for manual resolution rather than picking arbitrarily.
        StrongMatch strong = matchByBusinessReference(
            tenantId, delivery.businessReference(), delivery.shopifyOrderId());

        if (strong.isAmbiguous()) {
            log.warn("tryMatchDelivery: AMBIGUOUS strong-key match tracking={} ref='{}' — manual resolution required",
                trackingNumber, delivery.businessReference());
            return new LinkResult(null, REASON_AMBIGUOUS_MULTI);
        }

        UUID orderId = strong.orderId();

        // Step 2 — phone+COD fallback: ONLY if both strong-key paths produced no match.
        // Ambiguity within this strategy (AMBIGUOUS_MULTI) is independent of step 1 —
        // we are here because step 1 found nothing, so the guard applies only to this strategy.
        String unlinkedReason = null;
        if (orderId == null) {
            log.debug("tryMatchDelivery: tracking={} no strong-key match, trying phone+COD fallback", trackingNumber);
            MatchResult m = matchByPhoneAndCod(tenantId, delivery);
            orderId       = m.orderId();
            unlinkedReason = m.reason();
        }

        if (orderId == null) {
            log.info("tryMatchDelivery: tracking={} → unlinked ({}) ref='{}' shopifyId='{}'",
                trackingNumber, unlinkedReason, delivery.businessReference(), delivery.shopifyOrderId());
            return new LinkResult(null, unlinkedReason);
        }

        // Step 3 — create/find shipment and link
        UUID shipmentId;
        try {
            shipmentId = createOrFindShipment(
                tenantId, orderId, trackingNumber, mapped.shipmentInternalState(), delivery);
        } catch (DuplicateKeyException e) {
            // ux_active_shipment_per_order fired: another delivery already holds the active
            // shipment slot for this order (concurrent double-link race). Route to unlinked.
            log.warn("Active-shipment constraint prevented double-link for order {} tracking {}",
                     orderId, trackingNumber);
            return new LinkResult(null, REASON_NO_MATCH);
        }

        transitionPackedPieces(orderId, shipmentId, tenantId, null);

        // Advance order if it is currently packed
        jdbc.update(
            "UPDATE orders SET status = 'awaiting_pickup' " +
            "WHERE id = ? AND tenant_id = ? AND status = 'packed'",
            orderId, tenantId);

        // Populate consignee PII from Bosta receiver data (fill-only-if-null).
        // GDPR guard: pii_redacted_at IS NULL check prevents re-populating redacted orders.
        populateConsigneePii(orderId, tenantId, delivery);

        // FR-7.8a deferred gate: re-check blocklist using Bosta receiver phone.
        // Pre-PCD orders have customer_phone=null at import — this is the first reliable phone.
        // Bosta v0 API places the recipient in "receiver", not "consignee".
        String bostaRawPhone = delivery.raw() != null
            ? delivery.raw().path("receiver").path("phone").asText(null) : null;
        blocklist.checkAndHoldIfBlocked(orderId, bostaRawPhone, tenantId);

        log.info("Auto-matched delivery {} to order {}", trackingNumber, orderId);
        return new LinkResult(orderId, null);
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

        UUID shipmentId;
        try {
            shipmentId = createOrFindShipment(
                tenantId, orderId, trackingNumber, mapped.shipmentInternalState(), null);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order already has an active shipment — resolve it before manually linking");
        }

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
            "       bosta_order_type, match_reason, first_seen_at, last_seen_at " +
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
        if (existing != null) {
            // Idempotent backfill: if a prior link stored raw but not provider_delivery_id, fill it now.
            if (delivery != null && delivery.raw() != null) {
                String bostaId = delivery.raw().path("_id").asText(null);
                if (bostaId != null && !bostaId.isBlank()) {
                    jdbc.update(
                        "UPDATE shipments SET provider_delivery_id = ? " +
                        "WHERE id = ? AND provider_delivery_id IS NULL",
                        bostaId, existing);
                }
            }
            return existing;
        }

        UUID id = UUID.randomUUID();
        String rawJson = (delivery != null && delivery.raw() != null)
            ? delivery.raw().toString() : null;
        String bostaId = (delivery != null && delivery.raw() != null)
            ? delivery.raw().path("_id").asText(null) : null;
        if (bostaId != null && bostaId.isBlank()) bostaId = null;
        jdbc.update(
            "INSERT INTO shipments " +
            "(id, tenant_id, order_id, provider, tracking_number, internal_state, raw, provider_delivery_id) " +
            "VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, ?::jsonb, ?)",
            id, tenantId, orderId, trackingNumber, internalState, rawJson, bostaId);
        return id;
    }

    private void fetchAndStoreProviderDeliveryId(UUID shipmentId, String trackingNumber, UUID tenantId) {
        try {
            String[] accountInfo = jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? new String[]{rs.getString(1)} : null,
                tenantId);
            if (accountInfo == null) {
                log.warn("No active Bosta account for tenant {} — cannot fetch provider_delivery_id for {}",
                    tenantId, trackingNumber);
                jdbc.update("UPDATE shipments SET provider_id_fetch_failed = true WHERE id = ?", shipmentId);
                return;
            }
            String rawApiKey = encryptionService.decrypt(accountInfo[0]);
            BostaDelivery delivery = bostaGateway.fetchDelivery(rawApiKey, trackingNumber);
            if (delivery != null && delivery.raw() != null) {
                String bostaId = delivery.raw().path("_id").asText(null);
                if (bostaId != null && !bostaId.isBlank()) {
                    jdbc.update("UPDATE shipments SET provider_delivery_id = ? WHERE id = ?",
                        bostaId, shipmentId);
                    return;
                }
            }
            log.warn("fetchDelivery for {} returned no _id — setting fetch-failed flag", trackingNumber);
            jdbc.update("UPDATE shipments SET provider_id_fetch_failed = true WHERE id = ?", shipmentId);
        } catch (Exception e) {
            log.warn("fetchDelivery for {} failed — provider_delivery_id will be NULL: {}",
                trackingNumber, e.getMessage());
            try {
                jdbc.update("UPDATE shipments SET provider_id_fetch_failed = true WHERE id = ?", shipmentId);
            } catch (Exception ex) {
                log.error("Failed to set provider_id_fetch_failed on shipment {}: {}", shipmentId, ex.getMessage());
            }
        }
    }

    private void resolveUnlinked(UUID tenantId, String trackingNumber) {
        jdbc.update(
            "UPDATE unlinked_bosta_deliveries SET resolved = true, last_seen_at = now() " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false",
            tenantId, trackingNumber);
    }

    // ---- PII helpers --------------------------------------------------------

    /**
     * Populates orders.customer_name / customer_phone / address from Bosta receiver data.
     *
     * Fill-only-if-null: COALESCE keeps any existing Shopify-sourced value untouched.
     * GDPR guard: skips the UPDATE entirely if pii_redacted_at IS NOT NULL.
     *
     * Verified Bosta v0 field paths (delivery-receiver.json + BostaShapeRegressionTest):
     *   name  ← receiver.fullName (fallback: firstName + " " + lastName)
     *   phone ← receiver.phone (E.164, normalized to canonical 01XXXXXXXXX)
     *   addr  ← dropOffAddress.{firstLine, city.name, zone.name, district.name}
     */
    private void populateConsigneePii(UUID orderId, UUID tenantId, BostaDelivery delivery) {
        if (delivery == null || delivery.raw() == null) return;
        JsonNode raw = delivery.raw();

        // Name: prefer fullName, fall back to firstName + lastName
        String fullName = raw.path("receiver").path("fullName").asText(null);
        if (fullName == null || fullName.isBlank()) {
            String first = raw.path("receiver").path("firstName").asText(null);
            String last  = raw.path("receiver").path("lastName").asText(null);
            if (first != null || last != null) {
                fullName = ((first != null ? first : "") + " " + (last != null ? last : "")).strip();
                if (fullName.isBlank()) fullName = null;
            }
        }

        // Phone: normalize to canonical 01XXXXXXXXX
        String phone = normalizePhone(raw.path("receiver").path("phone").asText(null));

        // Address: build JSON only when at least one field is present
        JsonNode drop     = raw.path("dropOffAddress");
        String firstLine  = drop.path("firstLine").asText(null);
        String city       = drop.path("city").path("name").asText(null);
        String zone       = drop.path("zone").path("name").asText(null);
        String district   = drop.path("district").path("name").asText(null);

        String addressJson = null;
        if (firstLine != null || city != null || zone != null || district != null) {
            ObjectNode addr = mapper.createObjectNode();
            if (firstLine != null) addr.put("firstLine", firstLine);
            if (city     != null) addr.put("city",      city);
            if (zone     != null) addr.put("zone",      zone);
            if (district != null) addr.put("district",  district);
            addressJson = addr.toString();
        }

        if (fullName == null && phone == null && addressJson == null) return;

        jdbc.update(
            "UPDATE orders " +
            "SET customer_name  = COALESCE(customer_name,  ?), " +
            "    customer_phone = COALESCE(customer_phone, ?), " +
            "    address        = COALESCE(address,        ?::jsonb), " +
            "    pii_source     = COALESCE(pii_source, 'bosta') " +
            "WHERE id = ? AND tenant_id = ? " +
            "  AND pii_redacted_at IS NULL",
            fullName, phone, addressJson, orderId, tenantId);

        log.debug("Populated consignee PII for order {} from Bosta receiver", orderId);
    }

    // ---- matching helpers ---------------------------------------------------

    /**
     * Matches a Bosta delivery to an order by businessReference (order number) or
     * shopifyOrderId (Shopify numeric order ID from plugin-created deliveries).
     *
     * LIMIT 2 is intentional: if the DB returns 2 rows the strong key is ambiguous
     * (same order number stored twice — shouldn't happen, but guard anyway).
     * Ambiguous → StrongMatch.ambiguous() so tryMatchDelivery routes to unlinked
     * immediately without falling through to phone+COD.
     */
    private StrongMatch matchByBusinessReference(UUID tenantId, String businessRef, String shopifyOrderId) {
        // businessReference path: covers #-prefixed, stripped, and hashed variants plus external_id.
        if (businessRef != null && !businessRef.isBlank()) {
            String stripped = businessRef.startsWith("#") ? businessRef.substring(1) : businessRef;
            String hashed   = "#" + stripped;
            log.debug("matchByBusinessReference: tenant={} ref='{}' stripped='{}' hashed='{}'",
                tenantId, businessRef, stripped, hashed);
            List<UUID> ids = jdbc.query(
                "SELECT id FROM orders " +
                "WHERE tenant_id = ? " +
                "  AND (number = ? OR number = ? OR number = ? OR external_id = ?) " +
                "LIMIT 2",
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId, businessRef, stripped, hashed, businessRef);
            log.debug("matchByBusinessReference: ref='{}' → {} row(s)", businessRef, ids.size());
            if (ids.size() == 1) return StrongMatch.found(ids.get(0));
            if (ids.size() > 1) {
                log.warn("matchByBusinessReference: AMBIGUOUS — ref='{}' matched {} orders for tenant={}",
                    businessRef, ids.size(), tenantId);
                return StrongMatch.ambiguous();
            }
            // no match on businessRef — fall through to shopifyOrderId
        }
        // shopifyOrderId path: plugin-created deliveries carry Shopify numeric order ID
        // in shopifyInfo.orderId; match against orders.external_id in GID format.
        if (shopifyOrderId != null && !shopifyOrderId.isBlank()) {
            String gid = "gid://shopify/Order/" + shopifyOrderId;
            log.debug("matchByBusinessReference: shopifyOrderId='{}' gid='{}'", shopifyOrderId, gid);
            List<UUID> ids = jdbc.query(
                "SELECT id FROM orders WHERE tenant_id = ? AND external_id = ? LIMIT 2",
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId, gid);
            log.debug("matchByBusinessReference: gid='{}' → {} row(s)", gid, ids.size());
            if (ids.size() == 1) return StrongMatch.found(ids.get(0));
            if (ids.size() > 1) {
                log.warn("matchByBusinessReference: AMBIGUOUS — shopifyOrderId='{}' matched {} orders for tenant={}",
                    shopifyOrderId, ids.size(), tenantId);
                return StrongMatch.ambiguous();
            }
        }
        return StrongMatch.notFound();
    }

    /**
     * Phone+COD fallback matcher.
     *
     * Phone normalization contract: both sides are canonicalized to 11-digit 01XXXXXXXXX form.
     *   Bosta side: normalized in Java by normalizePhone() before the query.
     *   Order side: normalized at query time via the SQL expression
     *     '0' || RIGHT(REGEXP_REPLACE(customer_phone, '[^0-9]', '', 'g'), 10)
     *   This means the storage format of orders.customer_phone cannot cause silent mismatches;
     *   '+20XXXXXXXXXX', '00201XXXXXXXXX', '01XXXXXXXXX', and forms with spaces all canonicalize
     *   identically. The functional index on this expression (V19) keeps the lookup fast once
     *   PCD approval lands and customer_phone is populated.
     *
     * Pre-PCD state: all orders.customer_phone are NULL. The phone+COD query returns 0 rows
     * for every delivery (NULL equality is always false), so all fallback deliveries route to
     * unlinked_bosta_deliveries with reason NO_MATCH. This is safe and expected; the operator
     * resolves manually. Once PCD is approved, the same code auto-links without change.
     *
     * COD = 0 (prepaid): treated as a valid match value, not "missing". Passing cod=0 to
     * the query is correct. Note that prepaid orders currently store cod_amount=NULL
     * (not 0) so a Bosta COD=0 will produce NO_MATCH in practice — acceptable because
     * phone+COD together disambiguate once phones are populated, and a 0-amount match
     * without phone confirmation would be too ambiguous anyway.
     */
    private MatchResult matchByPhoneAndCod(UUID tenantId, BostaDelivery delivery) {
        if (delivery.raw() == null) return MatchResult.flagged(REASON_NO_MATCH);
        JsonNode raw = delivery.raw();

        // COD: flat scalar at raw.cod (NOT nested raw.cod.amount).
        // A present value of 0 is valid (prepaid order) — only absent or JSON-null is "missing".
        JsonNode codNode = raw.path("cod");
        if (codNode.isMissingNode() || codNode.isNull()) {
            return MatchResult.flagged(REASON_NO_MATCH);
        }
        BigDecimal cod;
        try { cod = new BigDecimal(codNode.asText()); }
        catch (NumberFormatException e) { return MatchResult.flagged(REASON_NO_MATCH); }

        // Normalize Bosta phone. If absent or invalid → COD-only matching, which is too
        // ambiguous to auto-link. Flag immediately without running a candidate query.
        // Bosta v0 API places the recipient in "receiver", not "consignee".
        String rawPhone   = raw.path("receiver").path("phone").asText(null);
        String bostaPhone = normalizePhone(rawPhone);
        if (bostaPhone == null) {
            return MatchResult.flagged(REASON_COD_ONLY);
        }

        // Candidate query: phone+COD+non-terminal+no-active-shipment.
        // Terminal statuses: delivered, returned, lost, cancelled.
        // NOT EXISTS sub-select excludes orders already linked to an active shipment,
        // providing a secondary concurrency guard alongside the ux_active_shipment_per_order
        // partial unique index (V19).
        List<UUID> candidates = jdbc.query(
            "SELECT id FROM orders " +
            "WHERE tenant_id = ? " +
            "  AND '0' || RIGHT(REGEXP_REPLACE(customer_phone, '[^0-9]', '', 'g'), 10) = ? " +
            "  AND cod_amount = ? " +
            "  AND status NOT IN ('delivered','returned','lost','cancelled') " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM shipments s " +
            "      WHERE s.order_id  = orders.id " +
            "        AND s.tenant_id = ? " +
            "        AND s.internal_state NOT IN ('terminated','cancelled') " +
            "  )",
            (rs, i) -> rs.getObject("id", UUID.class),
            tenantId, bostaPhone, cod, tenantId);

        return switch (candidates.size()) {
            case 0  -> MatchResult.flagged(REASON_NO_MATCH);
            case 1  -> MatchResult.linked(candidates.get(0));
            default -> MatchResult.flagged(REASON_AMBIGUOUS_MULTI);
        };
    }

    /**
     * Normalizes an Egyptian phone number to canonical 11-digit 01XXXXXXXXX form.
     * Returns null for absent, malformed, or non-Egyptian numbers.
     *
     * Handles:
     *   +201001234567   (E.164, 12 digits after stripping '+')
     *   00201001234567  (IDD prefix, 14 digits)
     *   201001234567    (country code without '+', 12 digits)
     *   01001234567     (local, already canonical, 11 digits)
     *   1001234567      (local without leading zero, 10 digits)
     *   +20 100 123 4567 (E.164 with spaces)
     */
    public static String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0020") && digits.length() == 14) {
            digits = "0" + digits.substring(4);   // 0020XXXXXXXXXX → 0XXXXXXXXXX
        } else if (digits.startsWith("20") && digits.length() == 12) {
            digits = "0" + digits.substring(2);   // 20XXXXXXXXXX → 0XXXXXXXXXX
        } else if (digits.length() == 10) {
            digits = "0" + digits;                // XXXXXXXXXX → 0XXXXXXXXXX (missing leading zero)
        }
        if (digits.startsWith("01") && digits.length() == 11) return digits;
        return null;
    }

    // ---- private record for matchByPhoneAndCod result ----------------------

    private record MatchResult(UUID orderId, String reason) {
        static MatchResult linked(UUID id)        { return new MatchResult(id, null); }
        static MatchResult flagged(String reason) { return new MatchResult(null, reason); }
    }
}
