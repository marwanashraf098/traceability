package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 2 — the manual mapping step. Operator maps an exchange's two
 * free-text legs to catalog variants; confirming creates the Model-A internal outbound
 * order (§3.3 of the spec) and records the inbound mapping, flipping status
 * needs_mapping → mapped.
 *
 * list()/detail() return raw column-labelled maps (same convention as ExceptionService's
 * detectors / ShipmentLinkService.listUnlinked() — snake_case JSON, matches the
 * existing Exceptions.tsx precedent for this shape of endpoint).
 *
 * FR-EXCHANGE Step 4a: list()/detail() now surface status/matched_order_id/match_method/
 * matched_at (V95 columns — added by Step 3 Part A, never exposed by a read endpoint
 * until now). LABELLING GUARD: matched_order_id is the ONLY field either method ever
 * calls "the mapped order" for an exchange — outbound_order_id (the synthetic Model-A
 * replacement order created by map(), below) is a structurally different concept and is
 * deliberately NOT included in either projection, so no caller can mistake one for the
 * other. list() also deliberately carries no return-leg lifecycle state: no shipments
 * row exists for an exchange's return leg (V74 — the forward and return legs share one
 * tracking_number, and shipments.tracking_number has been globally UNIQUE since V1, so a
 * second shipments row for the same number is a hard DB conflict, not just unbuilt), and
 * ExchangeStateInterpreter.interpretReturnLeg() is an intentionally-unimplemented hook
 * (always empty, pending confirmed Bosta vocabulary) — so exchanges.status itself is the
 * only real lifecycle signal available today, and it is what's exposed here.
 *
 * map() creates AND links the forward shipment immediately (ShipmentLinkService.
 * linkAtMapTime(), reusing linkByAwbScan()'s creation/link body — see that class for the
 * full reasoning). The order enters the Fulfill queue via PICKABLE_ORDERS_FILTER's
 * existing `latest_shipment.internal_state = 'created'` disjunct — the same mechanism
 * a normal order's webhook-auto-matched-before-pack AWB already uses; no exchange-
 * specific queue disjunct is needed. map() still does NOT touch orders.is_self_pickup.
 */
@Service
public class ExchangeService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShipmentLinkService shipmentLinkService;
    private final ExchangeMatchService matchService;

    public ExchangeService(JdbcTemplate jdbc, ObjectMapper mapper,
                            ShipmentLinkService shipmentLinkService,
                            ExchangeMatchService matchService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.shipmentLinkService = shipmentLinkService;
        this.matchService = matchService;
    }

    // Shared projection for list() and detail() — kept identical so a row looks the same
    // shape whichever endpoint returned it. matched_order_id is the ONLY "mapped order"
    // field here — see class javadoc's labelling guard; outbound_order_id is never
    // included. auto_matched (V97) — true only when the outbound leg was auto-committed
    // by resolveOutboundVariant() classifying EXACT, never for a human map() call.
    private static final String ROW_SELECT =
        "SELECT e.id, e.tracking_number, e.status, e.matched_order_id, e.match_method, e.matched_at, " +
        "       e.outbound_description, e.inbound_description, " +
        "       e.inbound_description_ar, e.cod, e.goods_value, e.auto_matched, " +
        "       NULLIF(e.raw #>> '{specs,packageDetails,itemsCount}', '')::int AS outbound_items_count, " +
        "       NULLIF(e.raw #>> '{returnSpecs,packageDetails,itemsCount}', '')::int AS inbound_items_count, " +
        "       COALESCE(" +
        "           NULLIF(e.raw #>> '{receiver,fullName}', ''), " +
        "           NULLIF(trim(concat(e.raw #>> '{receiver,firstName}', ' ', e.raw #>> '{receiver,lastName}')), '')" +
        "       ) AS customer_name, " +
        "       e.raw #>> '{receiver,phone}' AS customer_phone " +
        "FROM exchanges e ";

    /**
     * Only caller confirmed (Step 4a-1 diagnosis): ExchangeController's GET / route —
     * no frontend consumer exists yet either, so flipping the ordering below is safe.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, int page, int size) {
        UUID tenantId = TenantContext.require();

        StringBuilder sql = new StringBuilder(ROW_SELECT).append("WHERE e.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID exchangeId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            ROW_SELECT + "WHERE e.id = ? AND e.tenant_id = ?", exchangeId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange not found");
        }
        return rows.get(0);
    }

    /**
     * Claim (conditional UPDATE, first write) → validate variants → create the Model-A
     * order + order_item → populate PII (reused from ShipmentLinkService, not
     * reimplemented) → record the mapping on the exchange row.
     *
     * One @Transactional method, no self-invocation: the claim UPDATE and every write
     * after it commit or roll back together. A concurrent second call sees the row
     * already 'mapped' and the claim affects 0 rows — it 409s before touching
     * orders/order_items at all. orders' UNIQUE(store_id, external_id) is an
     * independent backstop (external_id is deterministic from the tracking number, so
     * any accidental double-insert is still caught at the DB level).
     */
    @Transactional
    public Map<String, Object> map(UUID exchangeId, UUID outboundVariantId, UUID inboundVariantId) {
        if (outboundVariantId == null || inboundVariantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "outboundVariantId and inboundVariantId are both required");
        }

        UUID tenantId = TenantContext.require();

        int claimed = claim(exchangeId, tenantId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exchange is not awaiting mapping");
        }

        return commit(exchangeId, tenantId, outboundVariantId, inboundVariantId, false);
    }

    /**
     * Part B — automatic outbound resolution. Same call shape as
     * {@link ExchangeMatchService#attemptMatch(String)} (tenant-scoped lookup by
     * tracking number, cheap no-op once the exchange has moved past 'needs_mapping')
     * and called right before it from BostaWebhookJob's ROUTED case — so an exchange
     * that auto-commits here is immediately eligible for inbound phone-matching in the
     * SAME webhook invocation, not a later one.
     *
     * Only ever auto-commits on {@link ExchangeMatchService.OutboundMatchClassification#EXACT}
     * — RECS and NONE always leave the exchange at 'needs_mapping' for the existing
     * human map() flow (RECS additionally feeds the mapping screen's pre-population via
     * {@link #outboundResolution}). Claims AFTER resolving (not before): resolving is
     * read-only and cheap, so there's no reason to hold the claim across it, and
     * claiming only once EXACT is known means a concurrent human map() call racing this
     * one is decided by whichever's claim UPDATE lands first, exactly like two
     * concurrent map() calls today — no new race shape introduced.
     */
    @Transactional
    public void tryAutoMap(String trackingNumber) {
        UUID tenantId = TenantContext.require();

        // Step 5c: an exchange Traced booked from a return request (return_request_id set) is
        // never guessed — its outbound variant is the request's replacement (attachForRequest).
        ExchangeStub ex = jdbc.query(
            "SELECT id, status, outbound_description FROM exchanges " +
            "WHERE tenant_id = ? AND tracking_number = ? AND return_request_id IS NULL",
            rs -> rs.next() ? new ExchangeStub(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("outbound_description")) : null,
            tenantId, trackingNumber);
        if (ex == null || !"needs_mapping".equals(ex.status())) return;

        ExchangeMatchService.OutboundResolution resolution =
            matchService.resolveOutboundVariant(ex.outboundDescription());
        if (resolution.classification() != ExchangeMatchService.OutboundMatchClassification.EXACT) return;

        int claimed = claim(ex.id(), tenantId);
        if (claimed == 0) return; // lost the race — a human map() (or a concurrent webhook) won first

        commit(ex.id(), tenantId, resolution.committedVariantId(), null, true);
    }

    /**
     * Part A read path for the mapping screen: what would the resolver currently say
     * about this exchange's outbound leg, without committing anything. Used to
     * pre-select (never auto-confirm) the outbound picker on RECS.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> outboundResolution(UUID exchangeId) {
        UUID tenantId = TenantContext.require();
        List<String> rows = jdbc.query(
            "SELECT outbound_description FROM exchanges WHERE id = ? AND tenant_id = ?",
            (rs, i) -> rs.getString(1), exchangeId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange not found");
        }

        ExchangeMatchService.OutboundResolution resolution = matchService.resolveOutboundVariant(rows.get(0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classification", resolution.classification().name());
        result.put("committedVariantId",
            resolution.committedVariantId() != null ? resolution.committedVariantId().toString() : null);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ExchangeMatchService.OutboundVariantCandidate c : resolution.rankedCandidates()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("variantId", c.variantId().toString());
            cm.put("variantTitle", c.variantTitle());
            cm.put("productTitle", c.productTitle());
            cm.put("axesMatched", c.axesMatched());
            candidates.add(cm);
        }
        result.put("candidates", candidates);
        return result;
    }

    /**
     * Lets an operator correct an auto-committed (or any still-unpicked) exchange's
     * outbound variant before pack — the "must still be openable and overridable"
     * requirement. Gated to status='mapped' (the only state where an outbound_order_id
     * + its single order_item are known to exist — see commit()) AND zero live
     * allocations on that order_item (the same "has capacity been taken" signal
     * FulfillService.scan() itself uses) — once a piece has been scanned for it,
     * changing the variant here would orphan that allocation, so this 409s instead and
     * the operator must use the normal exception/adjustment path.
     *
     * The new variant must belong to the SAME store as the order already does — the
     * order's store_id was fixed at commit() time from the original variant's product;
     * silently moving it to a different store's product here would strand a
     * store-scoped order under the wrong store for anything that later keys off it.
     */
    @Transactional
    public Map<String, Object> overrideOutboundVariant(UUID exchangeId, UUID newVariantId) {
        if (newVariantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "variantId is required");
        }
        UUID tenantId = TenantContext.require();

        Map<String, Object> row = jdbc.query(
            "SELECT status, outbound_order_id FROM exchanges WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? Map.of("status", (Object) rs.getString(1),
                "outbound_order_id", (Object) rs.getObject(2, UUID.class)) : null,
            exchangeId, tenantId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange not found");
        }
        if (!"mapped".equals(row.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exchange is not in a mapped state");
        }
        UUID orderId = (UUID) row.get("outbound_order_id");

        UUID newStoreId = jdbc.query(
            "SELECT p.store_id FROM variants v JOIN products p ON p.id = v.product_id " +
            "WHERE v.id = ? AND v.tenant_id = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            newVariantId, tenantId);
        if (newStoreId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variant not found");
        }
        UUID currentStoreId = jdbc.queryForObject(
            "SELECT store_id FROM orders WHERE id = ? AND tenant_id = ?", UUID.class, orderId, tenantId);
        if (!newStoreId.equals(currentStoreId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Variant must belong to the same store as the existing replacement order");
        }

        UUID orderItemId = jdbc.queryForObject(
            "SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ?", UUID.class, orderId, tenantId);

        Integer allocated = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE order_item_id = ? AND status IN ('active','packed')",
            Integer.class, orderItemId);
        if (allocated != null && allocated > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A piece has already been scanned for this order — variant can no longer be changed here");
        }

        jdbc.update("UPDATE order_items SET variant_id = ? WHERE id = ? AND tenant_id = ?",
            newVariantId, orderItemId, tenantId);
        jdbc.update(
            "UPDATE exchanges SET outbound_variant_id = ?, auto_matched = false WHERE id = ? AND tenant_id = ?",
            newVariantId, exchangeId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchangeId", exchangeId.toString());
        result.put("orderId", orderId.toString());
        result.put("variantId", newVariantId.toString());
        return result;
    }

    // ── Step 5c: exchanges Traced booked from an approved exchange request ─────

    /** Outcome of {@link #attachForRequest}. */
    public enum AttachOutcome {
        /** The row is linked, mapped from the request and matched to the request's order (now or already). */
        ATTACHED,
        /** The request isn't booked with this tracking number (yet) — nothing done. */
        NOT_READY,
        /** The tracking number's row belongs to another request, or can't be mapped any more. */
        CONFLICT
    }

    /**
     * Step 5c — a type 30 delivery arriving with no forward shipment yet is "ours" when:
     *   (a) an exchanges row for this tracking number is already linked to a request, or an
     *       exchange request was booked with this tracking number; or
     *   (b) its businessReference is the order number of an exchange request whose Traced
     *       booking is in flight ('pending', tracking not saved yet) — dashboard-made exchanges
     *       carry no businessReference at all. Its uniqueBusinessReference picks the request
     *       when present; otherwise exactly one candidate → that request; two or more → ours,
     *       but unlinked (the booking attaches it when its result is saved).
     * Returns null when the delivery isn't ours (the dashboard lane, unchanged).
     */
    public record OwnExchange(UUID requestId) {}

    @Transactional(readOnly = true)
    public OwnExchange findOwnExchange(UUID tenantId, String trackingNumber, JsonNode raw) {
        List<UUID> linked = jdbc.queryForList(
            "SELECT return_request_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ? " +
            "  AND return_request_id IS NOT NULL", UUID.class, tenantId, trackingNumber);
        if (!linked.isEmpty()) return new OwnExchange(linked.get(0));
        List<UUID> booked = jdbc.queryForList(
            "SELECT id FROM return_requests WHERE tenant_id = ? AND type = 'exchange' AND bosta_tracking_number = ?",
            UUID.class, tenantId, trackingNumber);
        if (!booked.isEmpty()) return new OwnExchange(booked.get(0));

        String reference = raw == null ? null : raw.path("businessReference").asText(null);
        if (reference == null || reference.isBlank()) return null;
        String bare = reference.trim().startsWith("#") ? reference.trim().substring(1) : reference.trim();
        List<UUID> pending = jdbc.queryForList(
            "SELECT rr.id FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.tenant_id = ? AND rr.type = 'exchange' AND rr.booking_status = 'pending' " +
            "  AND (o.number = ? OR ltrim(o.number, '#') = ?) " +
            "  AND NOT EXISTS (SELECT 1 FROM exchanges e WHERE e.return_request_id = rr.id) " +
            "ORDER BY rr.created_at, rr.id",
            UUID.class, tenantId, reference.trim(), bare);
        if (pending.isEmpty()) return null;
        String unique = raw.path("uniqueBusinessReference").asText(null);
        if (unique != null) {
            for (UUID id : pending) if (id.toString().equals(unique.trim())) return new OwnExchange(id);
        }
        return new OwnExchange(pending.size() == 1 ? pending.get(0) : null);
    }

    /**
     * Step 5c — the exchange for a return request Traced booked with {@code trackingNumber}.
     * Creates the exchanges row (or attaches the one a webhook already created), maps it from
     * the request through the existing {@link #claim} + {@link #commit} (internal order
     * internal:exchange:&lt;AWB&gt; + forward leg via linkAtMapTime — the replacement becomes
     * pickable and committed), and matches it to the request's order exactly
     * (match_method 'reference', status matched). No guessing: tryAutoMap / attemptMatch skip
     * rows with return_request_id.
     *
     * Idempotent: the row is unique per tracking number and per request, it is locked before
     * anything is decided, and commit() only runs while it has no outbound order — a repeated
     * call (booking result, read-back, webhook, sweeper) finds it done. The row starts with an
     * empty raw ({}); the first webhook for the delivery fills it. The internal order's
     * customer details come from the original order's delivered forward leg (the same customer
     * and address the booking sent), fill-only-if-null as always.
     */
    @Transactional
    public AttachOutcome attachForRequest(UUID tenantId, UUID requestId, String trackingNumber) {
        Map<String, Object> rq = jdbc.queryForList(
            "SELECT rr.order_id, rr.reference, i.variant_id, i.replacement_variant_id, " +
            "       pr.title AS product, v.title AS variant, rv.title AS replacement " +
            "FROM return_requests rr " +
            "JOIN return_request_items i ON i.request_id = rr.id AND i.tenant_id = rr.tenant_id " +
            "JOIN variants v ON v.id = i.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN variants rv ON rv.id = i.replacement_variant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ? AND rr.type = 'exchange' AND rr.bosta_tracking_number = ? " +
            "  AND rr.booking_status IN ('booked', 'needs_review') " +
            "ORDER BY i.created_at, i.id LIMIT 1",
            requestId, tenantId, trackingNumber).stream().findFirst().orElse(null);
        if (rq == null || rq.get("replacement_variant_id") == null) return AttachOutcome.NOT_READY;

        UUID originalOrderId = (UUID) rq.get("order_id");
        UUID inboundVariantId = (UUID) rq.get("variant_id");
        UUID outboundVariantId = (UUID) rq.get("replacement_variant_id");
        String outboundDesc = com.traceability.portal.ReturnPickupBookingService.exchangeOutboundDescription(
            (String) rq.get("reference"), (String) rq.get("product"), (String) rq.get("replacement"));
        String inboundDesc = com.traceability.portal.ReturnPickupBookingService.exchangeReturnDescription(
            (String) rq.get("product"), (String) rq.get("variant"));

        try {
            jdbc.update(
                "INSERT INTO exchanges (tenant_id, tracking_number, status, return_request_id, " +
                "    outbound_description, inbound_description, raw) " +
                "VALUES (?, ?, 'needs_mapping', ?, ?, ?, '{}'::jsonb) " +
                "ON CONFLICT (tenant_id, tracking_number) DO UPDATE SET " +
                "    return_request_id    = COALESCE(exchanges.return_request_id, EXCLUDED.return_request_id), " +
                "    outbound_description = COALESCE(exchanges.outbound_description, EXCLUDED.outbound_description), " +
                "    inbound_description  = COALESCE(exchanges.inbound_description, EXCLUDED.inbound_description), " +
                "    updated_at = now()",
                tenantId, trackingNumber, requestId, outboundDesc, inboundDesc);
        } catch (DuplicateKeyException e) {
            return AttachOutcome.CONFLICT;   // this request already holds a different exchange row
        }

        Map<String, Object> ex = jdbc.queryForList(
            "SELECT id, status, return_request_id, outbound_order_id FROM exchanges " +
            "WHERE tenant_id = ? AND tracking_number = ? FOR UPDATE",
            tenantId, trackingNumber).get(0);
        if (!requestId.equals(ex.get("return_request_id"))) return AttachOutcome.CONFLICT;
        UUID exchangeId = (UUID) ex.get("id");

        if (ex.get("outbound_order_id") == null) {
            if (claim(exchangeId, tenantId) == 0) return AttachOutcome.CONFLICT;
            Map<String, Object> made = commit(exchangeId, tenantId, outboundVariantId, inboundVariantId, false);
            UUID orderId = UUID.fromString((String) made.get("orderId"));
            String forwardRaw = jdbc.queryForList(
                "SELECT raw::text FROM shipments WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'forward' " +
                "  AND delivered_at IS NOT NULL AND raw IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, tenantId, originalOrderId).stream().findFirst().orElse(null);
            if (forwardRaw != null) {
                try {
                    shipmentLinkService.populateConsigneePiiFromRaw(orderId, tenantId, mapper.readTree(forwardRaw));
                } catch (Exception ignored) { /* no customer details — the order still works */ }
            }
        }

        jdbc.update(
            "UPDATE exchanges SET matched_order_id = ?, match_method = 'reference', " +
            "    matched_at = COALESCE(matched_at, now()), inbound_variant_id = COALESCE(inbound_variant_id, ?), " +
            "    status = CASE WHEN status IN ('needs_mapping', 'mapped', 'unmatched', 'needs_confirmation') " +
            "                  THEN 'matched' ELSE status END, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?",
            originalOrderId, inboundVariantId, exchangeId, tenantId);
        return AttachOutcome.ATTACHED;
    }

    private int claim(UUID exchangeId, UUID tenantId) {
        return jdbc.update(
            "UPDATE exchanges SET status = 'mapped', updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'needs_mapping'",
            exchangeId, tenantId);
    }

    /**
     * Shared commit body for both the human map() path and Part B's tryAutoMap() path
     * — identical order/order_item/shipment/PII writes either way, and either way
     * order_items.variant_id is a real catalog FK (map()'s human pick, or the
     * resolver's EXACT-only pick) — never a placeholder, never free text.
     *
     * inboundVariantId is nullable ONLY because the auto path never touches the
     * inbound leg at all (Part A is outbound-only; inbound stays ExchangeMatchService's
     * existing phone/description matcher, independent of this column). map() itself
     * still validates both non-null BEFORE calling this — see map() above — so this
     * method being permissive does not weaken that public contract.
     */
    private Map<String, Object> commit(UUID exchangeId, UUID tenantId, UUID outboundVariantId,
                                        UUID inboundVariantId, boolean autoMatched) {
        // Load the exchange's tracking_number + raw for order construction.
        Map<String, Object> exchange = jdbc.query(
            "SELECT tracking_number, raw::text AS raw FROM exchanges WHERE id = ? AND tenant_id = ?",
            rs -> rs.next()
                ? Map.of("tracking_number", (Object) rs.getString(1), "raw", (Object) rs.getString(2))
                : null,
            exchangeId, tenantId);
        if (exchange == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange not found");
        }
        String trackingNumber = (String) exchange.get("tracking_number");
        String rawJson = (String) exchange.get("raw");
        JsonNode raw;
        try {
            raw = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new RuntimeException("Exchange " + exchangeId + " has malformed raw jsonb", e);
        }

        // store_id derived from the OUTBOUND variant's product — not a separate
        // tenant-store lookup. Disambiguates correctly if a tenant ever connects
        // more than one Shopify store (variant ownership already decides it).
        UUID storeId = jdbc.query(
            "SELECT p.store_id FROM variants v JOIN products p ON p.id = v.product_id " +
            "WHERE v.id = ? AND v.tenant_id = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            outboundVariantId, tenantId);
        if (storeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outbound variant not found");
        }
        if (inboundVariantId != null) {
            Integer inboundVariantExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM variants WHERE id = ? AND tenant_id = ?",
                Integer.class, inboundVariantId, tenantId);
            if (inboundVariantExists == null || inboundVariantExists == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inbound variant not found");
            }
        }

        // Guaranteed 1 by construction (ExchangeIngestService only ever creates an
        // exchanges row when both legs' itemsCount == 1) — read from raw defensively
        // rather than hardcoding the literal.
        int quantity = raw.path("specs").path("packageDetails").path("itemsCount").asInt(1);

        // Model-A internal order (§3.3). external_id/number are deterministic from
        // the tracking number — 'internal:exchange:' can never collide with a real
        // Shopify GID, so this order is never mistaken for one by any Shopify-facing
        // code path (confirmed in Phase 0 §0.3 — nothing sweeps orders by store_id
        // to push to Shopify anyway).
        // NOT inserting into shipments and NOT touching is_self_pickup here is
        // deliberate — see class javadoc (Fulfill-queue inertness guarantee).
        UUID orderId;
        try {
            orderId = jdbc.query(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, now(), ?::jsonb) RETURNING id",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, storeId, "internal:exchange:" + trackingNumber, "EXC-" + trackingNumber, rawJson);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An order already exists for this exchange");
        }

        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
            tenantId, orderId, outboundVariantId, quantity);

        // Forward shipment, created and linked NOW (not deferred to pack). An
        // exchange has exactly one possible AWB — Traced already holds it in
        // trackingNumber — so there is nothing an operator scan could verify that isn't
        // already known. Reuses linkByAwbScan()'s exact creation/link body via
        // linkAtMapTime(): forward leg only, swapped-AWB guard intact, and — because the
        // order is still unpicked at this exact moment (no order_items allocations exist
        // yet; that INSERT is a line above, allocations only come from FulfillService.
        // scan()) — completeLink()'s piece-transition and order→awaiting_pickup side
        // effects are structural no-ops here, not something this call has to avoid.
        // actorUserId=null: no operator is present at map/auto-commit time, same as the
        // webhook auto-matcher's system-initiated links (ShipmentLinkService.tryMatchDelivery()).
        shipmentLinkService.linkAtMapTime(orderId, trackingNumber, null);

        // PII — reuse, don't reimplement. Same receiver/dropOffAddress shape
        // ShipmentLinkService already parses for every other Bosta delivery
        // (confirmed identical in Step 0 §0.5 — Bosta's delivery payload shape is
        // the same regardless of type.code). Fill-only-if-null, GDPR guard included.
        shipmentLinkService.populateConsigneePiiFromRaw(orderId, tenantId, raw);

        // Record the mapping decision on the exchange row itself.
        // 2026-10-05: an exchange already matched to its original by reference (while it still needed
        // mapping) goes straight to 'matched' once its replacement is mapped.
        jdbc.update(
            "UPDATE exchanges SET outbound_order_id = ?, outbound_variant_id = ?, inbound_variant_id = ?, " +
            "    auto_matched = ?, " +
            "    status = CASE WHEN status = 'mapped' AND matched_order_id IS NOT NULL THEN 'matched' ELSE status END " +
            "WHERE id = ? AND tenant_id = ?",
            orderId, outboundVariantId, inboundVariantId, autoMatched, exchangeId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchangeId", exchangeId.toString());
        result.put("orderId", orderId.toString());
        result.put("status", jdbc.queryForObject(
            "SELECT status FROM exchanges WHERE id = ? AND tenant_id = ?", String.class, exchangeId, tenantId));
        result.put("autoMatched", autoMatched);
        return result;
    }

    private record ExchangeStub(UUID id, String status, String outboundDescription) {}
}
