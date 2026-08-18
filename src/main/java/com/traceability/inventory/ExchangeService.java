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
 * list() returns raw column-labelled maps (same convention as ExceptionService's
 * detectors / ShipmentLinkService.listUnlinked() — snake_case JSON, matches the
 * existing Exceptions.tsx precedent for this shape of endpoint).
 *
 * map() does NOT insert into shipments and does NOT touch orders.is_self_pickup — the
 * inertness guarantee that keeps the new internal order out of the Fulfill queue until
 * Phase 3 creates the forward shipment row (see FulfillService.PICKABLE_ORDERS_FILTER;
 * confirmed in Phase 2 Step 0 §0b).
 */
@Service
public class ExchangeService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShipmentLinkService shipmentLinkService;

    public ExchangeService(JdbcTemplate jdbc, ObjectMapper mapper,
                            ShipmentLinkService shipmentLinkService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.shipmentLinkService = shipmentLinkService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        UUID tenantId = TenantContext.require();

        StringBuilder sql = new StringBuilder(
            "SELECT e.id, e.tracking_number, e.outbound_description, e.inbound_description, " +
            "       e.inbound_description_ar, e.cod, e.goods_value, " +
            "       NULLIF(e.raw #>> '{specs,packageDetails,itemsCount}', '')::int AS outbound_items_count, " +
            "       NULLIF(e.raw #>> '{returnSpecs,packageDetails,itemsCount}', '')::int AS inbound_items_count, " +
            "       COALESCE(" +
            "           NULLIF(e.raw #>> '{receiver,fullName}', ''), " +
            "           NULLIF(trim(concat(e.raw #>> '{receiver,firstName}', ' ', e.raw #>> '{receiver,lastName}')), '')" +
            "       ) AS customer_name, " +
            "       e.raw #>> '{receiver,phone}' AS customer_phone " +
            "FROM exchanges e WHERE e.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY e.created_at ASC");

        return jdbc.queryForList(sql.toString(), params.toArray());
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

        // 1. Claim — first write. Loser (claimed=0) bails before any further writes.
        int claimed = jdbc.update(
            "UPDATE exchanges SET status = 'mapped', updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'needs_mapping'",
            exchangeId, tenantId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exchange is not awaiting mapping");
        }

        // 2. Load the exchange's tracking_number + raw for order construction.
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

        // 3. store_id derived from the OUTBOUND variant's product — not a separate
        //    tenant-store lookup. Disambiguates correctly if a tenant ever connects
        //    more than one Shopify store (variant ownership already decides it).
        UUID storeId = jdbc.query(
            "SELECT p.store_id FROM variants v JOIN products p ON p.id = v.product_id " +
            "WHERE v.id = ? AND v.tenant_id = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            outboundVariantId, tenantId);
        if (storeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outbound variant not found");
        }
        Integer inboundVariantExists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM variants WHERE id = ? AND tenant_id = ?",
            Integer.class, inboundVariantId, tenantId);
        if (inboundVariantExists == null || inboundVariantExists == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inbound variant not found");
        }

        // Guaranteed 1 by construction (ExchangeIngestService only ever creates an
        // exchanges row when both legs' itemsCount == 1) — read from raw defensively
        // rather than hardcoding the literal.
        int quantity = raw.path("specs").path("packageDetails").path("itemsCount").asInt(1);

        // 4. Model-A internal order (§3.3). external_id/number are deterministic from
        //    the tracking number — 'internal:exchange:' can never collide with a real
        //    Shopify GID, so this order is never mistaken for one by any Shopify-facing
        //    code path (confirmed in Phase 0 §0.3 — nothing sweeps orders by store_id
        //    to push to Shopify anyway).
        //    NOT inserting into shipments and NOT touching is_self_pickup here is
        //    deliberate — see class javadoc (Fulfill-queue inertness guarantee).
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

        // 5. PII — reuse, don't reimplement. Same receiver/dropOffAddress shape
        //    ShipmentLinkService already parses for every other Bosta delivery
        //    (confirmed identical in Step 0 §0.5 — Bosta's delivery payload shape is
        //    the same regardless of type.code). Fill-only-if-null, GDPR guard included.
        shipmentLinkService.populateConsigneePiiFromRaw(orderId, tenantId, raw);

        // 6. Record the mapping decision on the exchange row itself.
        jdbc.update(
            "UPDATE exchanges SET outbound_order_id = ?, outbound_variant_id = ?, inbound_variant_id = ? " +
            "WHERE id = ? AND tenant_id = ?",
            orderId, outboundVariantId, inboundVariantId, exchangeId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchangeId", exchangeId.toString());
        result.put("orderId", orderId.toString());
        result.put("status", "mapped");
        return result;
    }
}
