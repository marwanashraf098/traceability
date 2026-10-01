package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — finishes an order in a waybill session, in ONE transaction of its own:
 * FulfillService.complete() (reserved → packed, 'pack' events, order → packed) then
 * ShipmentLinkService.linkByAwbScan() with the waybill scanned when the order was opened
 * (packed → awaiting_pickup, 'tracking_linked' events carrying that raw scan + the pack session
 * id; order → awaiting_pickup) — exactly where queue mode ends after its link dialog.
 *
 * GUARD (approved 2026-10-01, Step 0 gate option a): before linking, the normalized tracking
 * number of the waybill scanned at open must be a FORWARD shipment row on this order. Then
 * linkByAwbScan always takes its verify branch (or the "already this order's shipment" branch
 * when the leg ended meanwhile) and never reaches its new-shipment branch, whose
 * fetchAndStoreProviderDeliveryId() makes a synchronous Bosta call inside the transaction
 * (ShipmentLinkService — pre-existing, out of scope, see PROGRESS Follow-ups). Guard fails →
 * {@link CompleteFailed} WAYBILL_NOT_ON_ORDER, the transaction rolls back, nothing completes and
 * the claim stays with the packer.
 *
 * Any failure (guard, complete()'s ALREADY_SHIPPED / not-fully-scanned refusals, a swapped-label
 * mismatch) rolls the whole thing back; {@link PackSessionService} turns it into complete_failed
 * and the packer can retry.
 */
@Component
public class PackCompleter {

    /** complete+link refused or failed; {@code code} goes back to the packer as the reason. */
    public static class CompleteFailed extends RuntimeException {
        private final String code;
        public CompleteFailed(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public record Packed(UUID orderId, String orderNumber, String customerName, int pieces) {}

    private final JdbcTemplate        jdbc;
    private final PackSessionStore    store;
    private final FulfillService      fulfill;
    private final ShipmentLinkService link;

    public PackCompleter(JdbcTemplate jdbc, PackSessionStore store, FulfillService fulfill, ShipmentLinkService link) {
        this.jdbc    = jdbc;
        this.store   = store;
        this.fulfill = fulfill;
        this.link    = link;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Packed completeAndLink(UUID sessionId, UUID orderId, UUID userId) {
        UUID tenantId = TenantContext.require();
        PackSessionStore.Session s = store.load(sessionId, userId, tenantId, true, true);
        PackSessionStore.requireOpenOrder(s, orderId);

        String rawWaybill = s.currentWaybillScan();
        String tn = TrackingNumberNormalizer.normalize(rawWaybill);
        Boolean onOrder = tn != null && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tracking_number = ? AND order_id = ? " +
            "               AND tenant_id = ? AND shipment_leg = 'forward')",
            Boolean.class, tn, orderId, tenantId));
        if (!Boolean.TRUE.equals(onOrder)) {
            throw new CompleteFailed("WAYBILL_NOT_ON_ORDER",
                "The waybill that opened this order is no longer this order's waybill.");
        }

        int pieces = fulfill.complete(orderId, userId);
        link.linkByAwbScan(orderId, rawWaybill, userId, "{\"pack_session_id\":\"" + sessionId + "\"}");

        String status = jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        if (!"awaiting_pickup".equals(status)) {
            throw new CompleteFailed("NOT_LINKED", "The order didn't reach awaiting pickup (" + status + ").");
        }

        store.record(sessionId, tenantId, orderId, rawWaybill, "packed", null);
        store.clearOpenOrder(sessionId, tenantId);
        PackClaim.release(jdbc, orderId, tenantId, userId);

        Map<String, Object> o = jdbc.queryForMap("SELECT number, customer_name FROM orders WHERE id = ?", orderId);
        return new Packed(orderId, (String) o.get("number"), (String) o.get("customer_name"), pieces);
    }
}
