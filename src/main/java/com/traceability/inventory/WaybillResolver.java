package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — what a scanned waybill means in waybill mode. Read-only.
 *
 * raw scan → {@link TrackingNumberNormalizer} → this tenant's shipment by tracking number
 * (RLS: another store's waybill is simply not found) → one outcome code. OPEN only when the
 * order passes {@link FulfillService#PICKABLE_SHIPMENT_GATE} (reused verbatim, never edited) for
 * the scanned waybill, sits inside the queue's lookback window and has no live claim by someone
 * else. Committed type-30 exchange replacement orders (internal:exchange:&lt;AWB&gt;) carry a normal
 * forward leg, so they open like any order.
 *
 * Checks run most-specific first: not a waybill → unknown → return leg → cancelled → already
 * packed → on hold → not packable (old leg / shipment moving / self-pickup) → too old → claimed
 * by someone else → OPEN.
 */
@Component
public class WaybillResolver {

    public enum Code {
        OPEN, CANCELLED, ALREADY_PACKED, CLAIMED_BY_OTHER, RETURN_WAYBILL, EXCHANGE_NOT_MAPPED,
        TOO_OLD, NOT_FOUND, NOT_A_WAYBILL,
        /** Order on hold (manual or blocked customer). Not in the original list — see report. */
        ON_HOLD,
        /** The order exists but this waybill can't be packed: an older/ended leg, the shipment is
         *  already moving, or a self-pickup order. Not in the original list — see report. */
        NOT_PACKABLE
    }

    /**
     * code + what the packer needs to see. orderId/shipmentId/trackingNumber are set whenever the
     * waybill matched an order. who/at: ALREADY_PACKED (packer, pack time), CLAIMED_BY_OTHER
     * (holder), CANCELLED (at = Shopify cancel time, only when known). detail: NOT_FOUND
     * 'unlinked' when Bosta knows the waybill but no order is linked yet.
     */
    public record Resolution(Code code, UUID orderId, String orderNumber, UUID shipmentId,
                             String trackingNumber, String who, Instant at, String detail,
                             String messageEn, String messageAr) {}

    private final JdbcTemplate jdbc;
    private final int          lookbackDays;

    public WaybillResolver(JdbcTemplate jdbc,
                           @Value("${shopify.import.lookback-days:30}") int lookbackDays) {
        this.jdbc         = jdbc;
        this.lookbackDays = lookbackDays;
    }

    @Transactional(readOnly = true)
    public Resolution resolve(String rawScan, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        String tn = TrackingNumberNormalizer.normalize(rawScan);
        if (tn == null) {
            return of(Code.NOT_A_WAYBILL, null, null, null, null, null, null, null,
                "That's not a waybill. Scan the waybill first to open an order.",
                "هذا ليس باركود بوليصة. امسح البوليصة أولاً لفتح الطلب.");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.id AS shipment_id, s.shipment_leg, s.internal_state::text AS state, s.created_at AS s_created, " +
            "       o.id AS order_id, o.number, o.status::text AS status, o.cancel_requested_at, " +
            "       o.raw ->> 'cancelled_at' AS shopify_cancelled_at, o.on_hold, o.is_self_pickup, " +
            "       (o.placed_at > now() - (? * INTERVAL '1 day')) AS in_window " +
            "FROM shipments s JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.tracking_number = ? AND s.tenant_id = ?",
            lookbackDays, tn, tenantId);

        if (rows.isEmpty()) return notLinked(tn, tenantId);

        Map<String, Object> r = rows.get(0);
        UUID   orderId    = (UUID) r.get("order_id");
        UUID   shipmentId = (UUID) r.get("shipment_id");
        String number     = (String) r.get("number");
        String status     = (String) r.get("status");
        String label      = number != null ? number : orderId.toString().substring(0, 8);

        if ("return".equals(r.get("shipment_leg"))) {
            return of(Code.RETURN_WAYBILL, orderId, number, shipmentId, tn, null, null, null,
                "This is a return waybill for " + label + ". Use Returns instead.",
                "هذه بوليصة مرتجع للطلب " + label + ". استخدم شاشة المرتجعات.");
        }

        if ("cancelled".equals(status) || r.get("cancel_requested_at") != null) {
            Instant at = parseInstant((String) r.get("shopify_cancelled_at"));
            return of(Code.CANCELLED, orderId, number, shipmentId, tn, null, at, null,
                "Order " + label + " was cancelled. Put the waybill to one side and give it to a manager.",
                "تم إلغاء الطلب " + label + ". ضع البوليصة جانباً وسلّمها للمدير.");
        }

        if (!List.of("new", "confirmed", "ready_to_pick", "picking").contains(status)) {
            Map<String, Object> packer = lastPack(orderId, tenantId);
            String who = packer != null ? (String) packer.get("name") : null;
            Instant at = packer != null ? ((java.sql.Timestamp) packer.get("created_at")).toInstant() : null;
            return of(Code.ALREADY_PACKED, orderId, number, shipmentId, tn, who, at, null,
                "Order " + label + " is already packed" + (who != null ? " by " + who : "") + ". Probably a double print.",
                "الطلب " + label + " مغلّف بالفعل" + (who != null ? " بواسطة " + who : "") + ". غالباً طُبعت البوليصة مرتين.");
        }

        if (Boolean.TRUE.equals(r.get("on_hold"))) {
            return of(Code.ON_HOLD, orderId, number, shipmentId, tn, null, null, null,
                "Order " + label + " is on hold. Give the waybill to a manager.",
                "الطلب " + label + " موقوف. سلّم البوليصة للمدير.");
        }

        if (!passesGateOnThisWaybill(orderId, shipmentId, tenantId)) {
            return of(Code.NOT_PACKABLE, orderId, number, shipmentId, tn, null, null, null,
                "Order " + label + " can't be packed with this waybill — it isn't the order's current waybill, " +
                "or the shipment has already moved. Give it to a manager.",
                "لا يمكن تغليف الطلب " + label + " بهذه البوليصة — ليست البوليصة الحالية للطلب، أو تحركت الشحنة بالفعل. سلّمها للمدير.");
        }

        if (!Boolean.TRUE.equals(r.get("in_window"))) {
            return of(Code.TOO_OLD, orderId, number, shipmentId, tn, null, null, null,
                "Order " + label + " is older than " + lookbackDays + " days. Open it from Orders.",
                "الطلب " + label + " أقدم من " + lookbackDays + " يوماً. افتحه من شاشة الطلبات.");
        }

        PackClaim.Holder holder = PackClaim.heldByOther(jdbc, orderId, tenantId, actorUserId);
        if (holder != null) {
            String who = holder.name();
            return of(Code.CLAIMED_BY_OTHER, orderId, number, shipmentId, tn, who, null, null,
                "Order " + label + " is being packed by " + (who != null ? who : "another packer") + " right now.",
                "الطلب " + label + " يجهّزه " + (who != null ? who : "موظف آخر") + " الآن.");
        }

        return of(Code.OPEN, orderId, number, shipmentId, tn, null, null, null, null, null);
    }

    /**
     * The queue's own gate for this order, plus "the scanned waybill IS the order's latest forward
     * leg" (the gate's LATERAL looks at that leg's state only). Self-pickup passes the gate without
     * a shipment, so it's excluded here explicitly — self-pickup stays in the order queue.
     */
    private boolean passesGateOnThisWaybill(UUID orderId, UUID shipmentId, UUID tenantId) {
        Boolean ok = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders o " + FulfillService.PICKABLE_SHIPMENT_GATE +
            "  AND o.id = ? AND o.is_self_pickup = false " +
            "  AND ? = (SELECT s.id FROM shipments s " +
            "           WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward' " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "           ORDER BY s.created_at DESC, s.id DESC LIMIT 1))",
            Boolean.class, tenantId, orderId, shipmentId);
        return Boolean.TRUE.equals(ok);
    }

    private Resolution notLinked(String tn, UUID tenantId) {
        Integer exchange = jdbc.queryForObject(
            "SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tn);
        if (exchange != null && exchange > 0) {
            return of(Code.EXCHANGE_NOT_MAPPED, null, null, null, tn, null, null, null,
                "This exchange waybill isn't mapped to a replacement order yet. Give it to a manager.",
                "بوليصة الاستبدال هذه لم تُربط بطلب بديل بعد. سلّمها للمدير.");
        }
        Integer unlinked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ? AND resolved = false",
            Integer.class, tenantId, tn);
        if (unlinked != null && unlinked > 0) {
            return of(Code.NOT_FOUND, null, null, null, tn, null, null, "unlinked",
                "Bosta knows this waybill, but it isn't linked to an order yet. Give it to a manager.",
                "Bosta تعرف هذه البوليصة، لكنها غير مرتبطة بطلب بعد. سلّمها للمدير.");
        }
        return of(Code.NOT_FOUND, null, null, null, tn, null, null, null,
            "No order in this store uses this waybill. Check it's a Bosta waybill.",
            "لا يوجد طلب في هذا المتجر يستخدم هذه البوليصة. تأكد أنها بوليصة Bosta.");
    }

    /** Who packed the order and when — the latest 'pack' event on it. */
    private Map<String, Object> lastPack(UUID orderId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT u.name, pe.created_at FROM piece_events pe LEFT JOIN users u ON u.id = pe.actor_user_id " +
            "WHERE pe.order_id = ? AND pe.tenant_id = ? AND pe.event_type = 'pack' " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "ORDER BY pe.created_at DESC, pe.id DESC LIMIT 1",
            orderId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception e) { return null; }
    }

    private static Resolution of(Code code, UUID orderId, String number, UUID shipmentId, String tn,
                                 String who, Instant at, String detail, String en, String ar) {
        return new Resolution(code, orderId, number, shipmentId, tn, who, at, detail, en, ar);
    }
}
