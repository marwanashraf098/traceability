package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lazy v0 for shipments.raw (2026-10-04): a shipment updated from a v2 search item stores that item
 * (marked {@link BostaListItemCache#SHAPE_V2}), which lacks some of Bosta's v0 fields. A reader that
 * needs the full v0 shape calls this first: the delivery is fetched from v0 — USER_FACING, someone is
 * waiting — and stored over the v2 copy (only while it is still the v2 copy). Not marked → nothing.
 * Call it outside any transaction you hold (it makes an HTTP call). Failures leave the v2 copy and
 * return false.
 */
@Component
public class ShipmentRawRefresher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentRawRefresher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway bostaGateway;
    private final EncryptionService encryptionService;

    public ShipmentRawRefresher(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaGateway bostaGateway,
                                EncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.bostaGateway = bostaGateway;
        this.encryptionService = encryptionService;
    }

    /** Replaces a v2-list raw with the v0 delivery. True when it did. */
    public boolean refreshV0(UUID tenantId, UUID shipmentId) {
        return Boolean.TRUE.equals(TenantContext.runAs(tenantId, () -> {
            List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
                "SELECT tracking_number FROM shipments WHERE id = ? AND tenant_id = ? " +
                "  AND raw->>'" + BostaListItemCache.SHAPE_FIELD + "' = '" + BostaListItemCache.SHAPE_V2 + "'",
                shipmentId, tenantId));
            if (rows == null || rows.isEmpty()) return false;
            String tn = (String) rows.get(0).get("tracking_number");
            String encryptedKey = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, tenantId));
            if (encryptedKey == null) return false;
            BostaDelivery d;
            try {
                String key = encryptionService.decrypt(encryptedKey);
                d = BostaRateLimiter.userFacing(() -> bostaGateway.fetchDelivery(key, tn));
            } catch (Exception e) {
                log.warn("Lazy v0 refresh of {} failed — keeping the v2 copy: {}", tn, e.toString());
                return false;
            }
            if (d == null || d.raw() == null) return false;
            Integer n = tx.execute(s -> {
                int updated = jdbc.update(
                    "UPDATE shipments SET raw = ?::jsonb WHERE id = ? AND tenant_id = ? " +
                    "  AND raw->>'" + BostaListItemCache.SHAPE_FIELD + "' = '" + BostaListItemCache.SHAPE_V2 + "'",
                    d.raw().toString(), shipmentId, tenantId);
                if (updated > 0) ShipmentSettlement.apply(jdbc, shipmentId, d.raw());
                return updated;
            });
            return n != null && n > 0;
        }));
    }

    /** The order's newest delivered forward leg, refreshed if it holds a v2 copy (booking reads it). */
    public boolean refreshDeliveredForwardLeg(UUID tenantId, UUID orderId) {
        UUID id = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT id FROM shipments WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'forward' " +
            "  AND delivered_at IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, orderId)));
        return id != null && refreshV0(tenantId, id);
    }
}
