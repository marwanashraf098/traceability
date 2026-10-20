package com.traceability.courier;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Non-Bosta couriers G3 — "Mark delivered" (owner / manager) from the Orders list, and the carriers the list can
 * filter generic orders by ("With Aramex"). Only generic legs in with_courier move; everything else is reported
 * per order and left alone. One audit_log row per order actually delivered. Idempotent: a second mark of the same
 * orders finds them delivered and writes nothing.
 */
@Service
public class GenericDeliveryService {

    public static final String AUDIT_MARK_DELIVERED = "generic_mark_delivered";
    static final int MAX_ORDERS = 200;

    private final JdbcTemplate jdbc;
    private final GenericLegTransitions legs;
    private final AuditService audit;

    public GenericDeliveryService(JdbcTemplate jdbc, GenericLegTransitions legs, AuditService audit) {
        this.jdbc = jdbc;
        this.legs = legs;
        this.audit = audit;
    }

    public record CarrierCount(String carrier, long withCourier) {}

    /** Carriers of this tenant's generic legs now with the courier, with how many — the Orders filter's options. */
    @Transactional(readOnly = true)
    public List<CarrierCount> carriersWithCourier() {
        UUID tenantId = TenantContext.require();
        return jdbc.query(
            "SELECT COALESCE(NULLIF(btrim(s.carrier_name), ''), '') AS carrier, COUNT(*) AS n " +
            "FROM shipments s JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.internal_state = 'with_courier' " +
            "GROUP BY 1 ORDER BY 2 DESC, 1",
            (rs, i) -> new CarrierCount(rs.getString("carrier"), rs.getLong("n")), tenantId);
    }

    public record MarkResult(UUID orderId, String orderNumber, String outcome) {}

    /**
     * Marks each order's generic leg delivered (source 'manual'). Outcomes per order: DELIVERED,
     * ALREADY_DELIVERED, NOT_WITH_COURIER (any other state), NO_GENERIC_SHIPMENT (not this tenant's, or not a
     * generic order). Owner / manager only (the controller).
     */
    @Transactional
    public List<MarkResult> markDelivered(List<UUID> orderIds, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (orderIds == null || orderIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderIds is required");
        }
        if (orderIds.size() > MAX_ORDERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_ORDERS + " orders at once");
        }
        List<MarkResult> out = new ArrayList<>();
        for (UUID orderId : new LinkedHashSet<>(orderIds)) {
            Map<String, Object> leg = jdbc.queryForList(
                "SELECT s.id, s.carrier_name, s.tracking_number, o.number FROM shipments s " +
                "JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
                "WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
                "  AND s.internal_state NOT IN ('terminated', 'cancelled') " +
                "ORDER BY s.created_at DESC, s.id DESC LIMIT 1", tenantId, orderId).stream().findFirst().orElse(null);
            if (leg == null) {
                out.add(new MarkResult(orderId, null, "NO_GENERIC_SHIPMENT"));
                continue;
            }
            String number = (String) leg.get("number");
            GenericLegTransitions.Delivered r = legs.deliver(tenantId, (UUID) leg.get("id"),
                GenericLegTransitions.SOURCE_MANUAL, actorUserId);
            String outcome = switch (r) {
                case DELIVERED, DELIVERED_INFERRED_HANDOVER -> "DELIVERED";
                case ALREADY_DELIVERED -> "ALREADY_DELIVERED";
                default -> "NOT_WITH_COURIER";
            };
            if ("DELIVERED".equals(outcome)) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("order_number", number);
                meta.put("shipment_id", leg.get("id").toString());
                meta.put("tracking_number", leg.get("tracking_number"));
                meta.put("carrier", leg.get("carrier_name"));
                audit.record(actorUserId, AUDIT_MARK_DELIVERED, "order", orderId.toString(), meta);
            }
            out.add(new MarkResult(orderId, number, outcome));
        }
        return out;
    }
}
