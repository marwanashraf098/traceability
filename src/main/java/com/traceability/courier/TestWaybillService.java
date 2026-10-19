package com.traceability.courier;

import com.traceability.inventory.TrackingNumberNormalizer;
import com.traceability.inventory.WaybillResolver;
import com.traceability.inventory.WaybillScanKeys;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Non-Bosta couriers G2 — Settings → Couriers → "Test a waybill". READ-ONLY: one read-only transaction
 * (the database refuses any write), nothing stored, nothing logged with the value.
 *
 * For a scanned (or typed) value it shows the raw value, its Bosta key ({@link TrackingNumberNormalizer}), its
 * generic key ({@link WaybillScanKeys#genericKey}), the barcode format (a hardware scanner can't tell — always
 * "unknown" in G2), where the value appears — Shopify fulfillments captured for this store
 * (order_fulfillment_tracking) and shipments — and ONE plain verdict, EN + AR. Waybill mode's own answer for it
 * ({@link WaybillResolver}) is included so the merchant sees whether the order would open right now.
 */
@Service
public class TestWaybillService {

    public enum Verdict {
        /** A shipment carries it — waybill mode opens this order. */
        OPENS_ORDER,
        /** Both a Bosta waybill and another courier's — never guessed. */
        AMBIGUOUS,
        /** Shopify has it on an order, but Traced made no shipment of it. */
        FULFILLMENT_ONLY,
        /** Nobody has it. */
        NO_FULFILLMENT,
        /** A piece label, an unreadable value, or a fragment of a known tracking number. */
        WRONG_BARCODE,
        /** Arabic keyboard layout: letters lost. */
        KEYBOARD_LAYOUT
    }

    public record FulfillmentMatch(String orderNumber, String carrier, String trackingNumber, String status) {}

    public record ShipmentMatch(String orderNumber, String provider, String carrier, String trackingNumber,
                                String state) {}

    public record Result(String raw, String bostaKey, String genericKey, String format,
                         List<FulfillmentMatch> fulfillments, List<ShipmentMatch> shipments,
                         Verdict verdict, String verdictEn, String verdictAr,
                         String waybillModeCode, String waybillModeEn, String waybillModeAr,
                         String cameraHintEn, String cameraHintAr) {}

    static final String CAMERA_HINT_EN =
        "Scanning with the phone camera? It reads Code 128 and QR barcodes only. If the phone doesn't beep on this " +
        "waybill, its barcode type can't be read by the camera — use a hardware scanner.";
    static final String CAMERA_HINT_AR =
        "تستخدم كاميرا الهاتف؟ تقرأ باركود Code 128 و QR فقط. إن لم يُصدر الهاتف صوتاً على هذه البوليصة فنوع الباركود " +
        "لا تقرؤه الكاميرا — استخدم قارئ باركود.";

    private final JdbcTemplate jdbc;
    private final WaybillResolver resolver;

    public TestWaybillService(JdbcTemplate jdbc, WaybillResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public Result test(String raw, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        String value = raw == null ? "" : raw;
        String bostaKey = TrackingNumberNormalizer.normalize(value);
        String genericKey = WaybillScanKeys.genericKey(value);

        List<FulfillmentMatch> fulfillments = jdbc.query(
            "SELECT o.number, t.carrier_raw, t.tracking_number, t.fulfillment_status " +
            "FROM order_fulfillment_tracking t JOIN merchant_orders o ON o.id = t.order_id AND o.tenant_id = t.tenant_id " +
            "WHERE t.tenant_id = ? AND (t.tracking_number = ? OR " + keySql("t.tracking_number") + " = ?) " +
            "ORDER BY t.first_seen_at DESC, t.id DESC LIMIT 10",
            (rs, i) -> new FulfillmentMatch(rs.getString("number"), rs.getString("carrier_raw"),
                rs.getString("tracking_number"), rs.getString("fulfillment_status")),
            tenantId, bostaKey == null ? "" : bostaKey, genericKey == null ? "" : genericKey);

        List<ShipmentMatch> shipments = jdbc.query(
            "SELECT o.number, s.provider::text AS provider, s.carrier_name, s.tracking_number, s.internal_state::text AS state " +
            "FROM shipments s JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND ((s.provider = 'bosta' AND s.tracking_number = ?) " +
            "                           OR (s.provider = 'generic' AND s.tracking_key = ?)) " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 10",
            (rs, i) -> new ShipmentMatch(rs.getString("number"), rs.getString("provider"),
                "bosta".equals(rs.getString("provider")) ? "Bosta" : rs.getString("carrier_name"),
                rs.getString("tracking_number"), rs.getString("state")),
            tenantId, bostaKey == null ? "" : bostaKey, genericKey == null ? "" : genericKey);

        WaybillResolver.Resolution mode = resolver.resolve(value, actorUserId);

        Verdict v;
        String en, ar;
        boolean bostaShip = shipments.stream().anyMatch(s -> "bosta".equals(s.provider()));
        boolean genericShip = shipments.stream().anyMatch(s -> "generic".equals(s.provider()));
        if (WaybillResolver.looksLikePieceCode(value)) {
            v = Verdict.WRONG_BARCODE;
            en = "This is a piece label, not a waybill. Scan the waybill's tracking-number barcode.";
            ar = "هذا ملصق قطعة وليس بوليصة. امسح باركود رقم التتبع على البوليصة.";
        } else if (WaybillScanKeys.holdsArabicLetter(value)) {
            v = Verdict.KEYBOARD_LAYOUT;
            en = WaybillScanKeys.LAYOUT_EN;
            ar = WaybillScanKeys.LAYOUT_AR;
        } else if (bostaShip && genericShip) {
            v = Verdict.AMBIGUOUS;
            en = WaybillScanKeys.AMBIGUOUS_EN;
            ar = WaybillScanKeys.AMBIGUOUS_AR;
        } else if (!shipments.isEmpty()) {
            String n = shipments.get(0).orderNumber();
            v = Verdict.OPENS_ORDER;
            en = "This waybill will open order " + n;
            ar = "هذه البوليصة ستفتح الطلب " + n;
        } else if (!fulfillments.isEmpty()) {
            String n = fulfillments.get(0).orderNumber();
            v = Verdict.FULFILLMENT_ONLY;
            en = "Shopify has this number on order " + n + ", but it isn't a shipment in Traced yet — check \"I ship with " +
                 "other couriers\" is on, and that the order is still open.";
            ar = "رقم التتبع هذا موجود في Shopify على الطلب " + n + "، لكنه ليس شحنة في Traced بعد — تأكد من تفعيل " +
                 "\"أشحن مع شركات أخرى\" وأن الطلب ما زال مفتوحاً.";
        } else if (wrongBarcode(tenantId, bostaKey, genericKey)) {
            v = Verdict.WRONG_BARCODE;
            en = "This looks like the wrong barcode — try the other one on the label.";
            ar = "يبدو أن هذا باركود خاطئ — جرّب الباركود الآخر على الملصق.";
        } else {
            v = Verdict.NO_FULFILLMENT;
            en = "Shopify has no fulfillment with this number yet.";
            ar = "لا يوجد في Shopify شحن بهذا الرقم بعد.";
        }

        return new Result(value, bostaKey, genericKey, "unknown", fulfillments, shipments, v, en, ar,
            mode.code().name(), mode.messageEn(), mode.messageAr(), CAMERA_HINT_EN, CAMERA_HINT_AR);
    }

    /**
     * The wrong barcode of a label: nothing readable, a key too short to be a tracking number, or a value that is
     * PART of (or contains) a tracking number this store already knows — e.g. a reference or a route code
     * printed on the same waybill.
     */
    private boolean wrongBarcode(UUID tenantId, String bostaKey, String genericKey) {
        if (bostaKey == null && genericKey == null) return true;
        String key = genericKey != null ? genericKey : bostaKey;
        if (key.length() < 6) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM order_fulfillment_tracking t WHERE t.tenant_id = ? " +
            "  AND (" + keySql("t.tracking_number") + " LIKE '%' || ? || '%' " +
            "       OR ? LIKE '%' || " + keySql("t.tracking_number") + " || '%') " +
            "  AND length(" + keySql("t.tracking_number") + ") >= 6)",
            Boolean.class, tenantId, key, key));
    }

    /** SQL mirror of GenericTrackingKey for a stored PLAIN tracking number (no URL): digits folded, A–Z0–9, upper. */
    private static String keySql(String col) {
        return "upper(regexp_replace(translate(" + col + ", '٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹', '01234567890123456789'), " +
               "'[^A-Za-z0-9]', '', 'g'))";
    }

}
