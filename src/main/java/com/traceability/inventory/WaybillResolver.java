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
 * packed → on hold → not packable ({@link NotPackable}: self-pickup / leg ended / shipment moved /
 * not the current waybill / gate refuses) → too old → claimed by someone else → OPEN.
 */
@Component
public class WaybillResolver {

    public enum Code {
        OPEN, CANCELLED, ALREADY_PACKED, CLAIMED_BY_OTHER, RETURN_WAYBILL, EXCHANGE_NOT_MAPPED,
        TOO_OLD, NOT_FOUND,
        /** Doesn't normalize to a tracking number and looks like a piece code (a piece label
         *  scanned while waiting for a waybill). */
        NOT_A_WAYBILL,
        /** Doesn't normalize to a tracking number and isn't a piece code either — some other
         *  barcode (an unrecognised waybill barcode, a product barcode, …). */
        UNRECOGNISED_BARCODE,
        /** Order on hold (manual or blocked customer). Not in the original list — see report. */
        ON_HOLD,
        /** The order exists but this waybill can't be packed; {@code detail} carries the
         *  sub-reason ({@link NotPackable}). */
        NOT_PACKABLE
    }

    /** Why a NOT_PACKABLE waybill can't be packed — each has its own message. */
    public enum NotPackable {
        /** The scanned forward leg is terminated / cancelled. */
        LEG_ENDED,
        /** The shipment has moved past 'created' (now, or at any point in its history). */
        ALREADY_MOVING,
        /** A self-pickup order that also carries a waybill — self-pickup stays in the queue. */
        SELF_PICKUP,
        /** The pickable gate refuses it for a reason none of the above name: a status the queue
         *  doesn't pick from (confirmed / picking), or a newer forward leg that ended while this
         *  older one is still active. (An older 'created' leg next to a newer active one can't
         *  exist — V104's ux_active_forward_shipment_per_order — so there is no separate
         *  "not the current waybill" reason.) */
        OTHER
    }

    /**
     * code + what the packer needs to see. orderId/shipmentId/trackingNumber are set whenever the
     * waybill matched an order. who/at: ALREADY_PACKED (packer, pack time), CLAIMED_BY_OTHER
     * (holder), CANCELLED (at = Shopify cancel time, only when known). detail: NOT_FOUND
     * 'unlinked' when Bosta knows the waybill but no order is linked yet; NOT_PACKABLE the
     * {@link NotPackable} name. state: ALREADY_MOVING's shipment state.
     */
    public record Resolution(Code code, UUID orderId, String orderNumber, UUID shipmentId,
                             String trackingNumber, String who, Instant at, String detail,
                             String state, String messageEn, String messageAr) {}

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
            if (looksLikePieceCode(rawScan)) {
                return of(Code.NOT_A_WAYBILL, null, null, null, null, null, null, null,
                    "That's not a waybill. Scan the waybill first to open an order.",
                    "هذا ليس باركود بوليصة. امسح البوليصة أولاً لفتح الطلب.");
            }
            return of(Code.UNRECOGNISED_BARCODE, null, null, null, null, null, null, null,
                "This barcode isn't a waybill we recognise. Try the barcode at the bottom of the waybill (Tracking Number).",
                "هذا الباركود ليس بوليصة نعرفها. جرّب الباركود الموجود أسفل البوليصة (رقم التتبع).");
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

        Resolution notPackable = notPackable(r, orderId, number, label, shipmentId, tn, tenantId);
        if (notPackable != null) return notPackable;

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

    /** The piece-label formats FulfillService.scan() accepts: short code (P + 6+ digits), barcode
     *  ("PC-" + piece id — any "PC-" code is our piece-label family) or the raw ULID piece id
     *  (26 Crockford base-32 chars). Format only — no lookup. */
    private static final java.util.regex.Pattern PIECE_CODE = java.util.regex.Pattern.compile(
        "^(P[0-9]{6,}|PC-[0-9A-Za-z]+|[0-9A-HJKMNP-TV-Z]{26})$");

    static boolean looksLikePieceCode(String rawScan) {
        return rawScan != null && PIECE_CODE.matcher(rawScan.strip()).matches();
    }

    /**
     * Why this (forward, not cancelled, not yet packed, not on hold) waybill can't be packed, or
     * null when it can. Named sub-reasons first, then the queue's own gate as the backstop:
     * self-pickup → leg ended → shipment moved (now, or ever per shipment_status_history — the
     * same evidence complete()'s ALREADY_SHIPPED guard reads) → {@link FulfillService#PICKABLE_SHIPMENT_GATE}
     * (reused verbatim; its LATERAL looks at the order's newest forward leg) refuses → OTHER.
     */
    private Resolution notPackable(Map<String, Object> r, UUID orderId, String number, String label,
                                   UUID shipmentId, String tn, UUID tenantId) {
        String state = (String) r.get("state");

        if (Boolean.TRUE.equals(r.get("is_self_pickup"))) {
            return np(NotPackable.SELF_PICKUP, null, orderId, number, shipmentId, tn,
                "Order " + label + " is a self-pickup order. The customer collects it — pack it from the order queue.",
                "الطلب " + label + " استلام من المتجر. العميل سيستلمه بنفسه — جهّزه من قائمة الطلبات.");
        }
        if ("terminated".equals(state) || "cancelled".equals(state)) {
            return np(NotPackable.LEG_ENDED, state, orderId, number, shipmentId, tn,
                "This waybill for " + label + " was " + state + " at Bosta. Don't use it — give it to a manager.",
                "هذه البوليصة للطلب " + label + " " + ("terminated".equals(state) ? "أُنهيت" : "أُلغيت") +
                " في Bosta. لا تستخدمها — سلّمها للمدير.");
        }
        String moved = movedState(orderId, tenantId, state);
        if (moved != null) {
            return np(NotPackable.ALREADY_MOVING, moved, orderId, number, shipmentId, tn,
                "The shipment for " + label + " has already moved (Bosta: " + moved + "). It can't be packed again.",
                "شحنة الطلب " + label + " تحركت بالفعل (Bosta: " + moved + "). لا يمكن تغليفها مرة أخرى.");
        }
        Boolean gate = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders o " + FulfillService.PICKABLE_SHIPMENT_GATE + "  AND o.id = ?)",
            Boolean.class, tenantId, orderId);
        if (!Boolean.TRUE.equals(gate)) {
            return np(NotPackable.OTHER, null, orderId, number, shipmentId, tn,
                "Order " + label + " can't be packed right now. Give the waybill to a manager.",
                "لا يمكن تغليف الطلب " + label + " الآن. سلّم البوليصة للمدير.");
        }
        return null;
    }

    /** The shipment's state if it has left 'created' — live, or ever in a forward leg's history. */
    private String movedState(UUID orderId, UUID tenantId, String liveState) {
        if (liveState != null && !"created".equals(liveState)) return liveState;
        List<String> past = jdbc.queryForList(
            "SELECT h.internal_state FROM shipment_status_history h " +
            "JOIN shipments s ON s.id = h.shipment_id " +
            "WHERE s.order_id = ? AND s.tenant_id = ? AND s.shipment_leg = 'forward' " +
            "  AND h.internal_state <> 'created' " +
            // history id is a bigserial (insert-ordered); occurred_at first, id only as the tie-break
            "ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1",
            String.class, orderId, tenantId);
        return past.isEmpty() ? null : past.get(0);
    }

    private static Resolution np(NotPackable why, String state, UUID orderId, String number, UUID shipmentId,
                                 String tn, String en, String ar) {
        return new Resolution(Code.NOT_PACKABLE, orderId, number, shipmentId, tn, null, null, why.name(), state, en, ar);
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
        return new Resolution(code, orderId, number, shipmentId, tn, who, at, detail, null, en, ar);
    }
}
