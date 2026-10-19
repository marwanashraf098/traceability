package com.traceability.inventory;

import com.traceability.courier.GenericShipments;
import com.traceability.courier.GenericTrackingKey;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Non-Bosta couriers G2 — the ONE place a scanned waybill is turned into its generic (non-Bosta) match.
 * Shared by the four scan call sites: {@link WaybillResolver} (waybill mode opens an order),
 * {@link PackSessionStore#scanPiece} (a waybill scanned while packing), {@link PackCompleter} (the
 * waybill that opened the order is still this order's) and {@link ShipmentLinkService}'s AWB link step.
 *
 * Two keys, in this order — every caller keeps its own Bosta code exactly as it was:
 * <ol>
 *   <li>a piece-shaped scan ({@link WaybillResolver#looksLikePieceCode}) is never a waybill — no generic
 *       lookup at all (the piece-code regex wins first, as before);</li>
 *   <li>the Bosta key ({@link TrackingNumberNormalizer}) against provider = 'bosta' rows — the caller's own,
 *       unchanged query;</li>
 *   <li>then the generic key ({@link GenericTrackingKey}) against this tenant's provider = 'generic' rows —
 *       ONLY while generic keys apply to the tenant ({@link #enabled}: switched on, or still holding a live
 *       generic shipment); {@link #lookup} answers {@link Kind#OFF} without a key lookup otherwise;</li>
 *   <li>both matched (necessarily different shipments) → {@link #AMBIGUOUS_EN} — never guess;</li>
 *   <li>the caller's pickable / order checks stay the final word.</li>
 * </ol>
 *
 * Arabic keyboard layout: a scanner typing through an Arabic layout turns letters into Arabic glyphs
 * (S2 recovers them at the frontend when its capture is on). {@link GenericTrackingKey} would silently drop
 * those glyphs and keep only the digits — a different, shorter key that could match the wrong order — so a
 * scan still holding an Arabic letter is {@link Kind#LAYOUT}, never looked up. Arabic-Indic digits are
 * folded as everywhere (S1).
 *
 * Not a bean: callers pass their own JdbcTemplate (caller's connection, tenant context, transaction).
 */
public final class WaybillScanKeys {

    private WaybillScanKeys() {}

    public enum Kind {
        /** Generic keys don't apply to the tenant ({@link #enabled} false) — nothing was looked up. */
        OFF,
        /** Piece-shaped scan — not a waybill. */
        PIECE,
        /** Holds Arabic letters (keyboard layout) — not looked up. */
        LAYOUT,
        /** Nothing left after normalization. */
        NO_KEY,
        /** A key, no generic shipment carries it. */
        NONE,
        /** A key, and this tenant's generic shipment {@link Generic#match}. */
        MATCH
    }

    /** One generic forward shipment (the per-tenant tracking_key is unique — V169). */
    public record Match(UUID shipmentId, UUID orderId, String trackingNumber, String key, String carrierName,
                        String state) {}

    public record Generic(Kind kind, String key, Match match) {
        public boolean matched() { return kind == Kind.MATCH; }
    }

    private static final Generic OFF = new Generic(Kind.OFF, null, null);

    /**
     * Do generic keys apply to this tenant's scans? While it is switched on, or — after it was switched
     * off — while it still has a live generic forward shipment (those orders must stay packable; nothing
     * new is created). A tenant that never had a generic shipment and is off: false, and no lookup runs.
     * The probe rides V169's partial index (tenant_id, tracking_key) WHERE provider = 'generic'.
     */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT t.generic_courier_enabled OR EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = t.id " +
            "  AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.internal_state NOT IN ('terminated', 'cancelled')) " +
            "FROM tenants t WHERE t.id = ?", Boolean.class, tenantId));
    }

    /** The generic match for a raw scan — {@link Kind#OFF} (no key lookup) unless {@link #enabled}. */
    public static Generic lookup(JdbcTemplate jdbc, UUID tenantId, String rawScan) {
        if (!enabled(jdbc, tenantId)) return OFF;
        return lookupEnabled(jdbc, tenantId, rawScan);
    }

    /** As {@link #lookup}, for a caller that has already read the flag (true). */
    public static Generic lookupEnabled(JdbcTemplate jdbc, UUID tenantId, String rawScan) {
        if (rawScan == null || WaybillResolver.looksLikePieceCode(rawScan)) return new Generic(Kind.PIECE, null, null);
        if (holdsArabicLetter(rawScan)) return new Generic(Kind.LAYOUT, null, null);
        String key = GenericTrackingKey.of(rawScan);
        if (key == null) return new Generic(Kind.NO_KEY, null, null);
        List<Match> rows = jdbc.query(
            "SELECT s.id, s.order_id, s.tracking_number, s.tracking_key, s.carrier_name, s.internal_state::text AS state " +
            "FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.provider = 'generic' AND s.tracking_key = ? AND s.shipment_leg = 'forward' " +
            // a cancelled / terminated leg next to a live one for the same key can't exist (V169 unique per
            // tenant + key), so this is at most one row; live first only as a guard
            "ORDER BY (s.internal_state IN ('terminated', 'cancelled')), s.created_at DESC, s.id DESC LIMIT 1",
            (rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("tracking_number"), rs.getString("tracking_key"), rs.getString("carrier_name"),
                rs.getString("state")),
            tenantId, key);
        return rows.isEmpty() ? new Generic(Kind.NONE, key, null) : new Generic(Kind.MATCH, key, rows.get(0));
    }

    /** Arabic-block letters (not digits — those are folded): the scan came through an Arabic layout. */
    public static boolean holdsArabicLetter(String raw) {
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** "WJ-12345" → "WJ12345"; null when nothing is left. The generic key, for display (Test a waybill). */
    public static String genericKey(String rawScan) {
        return rawScan == null ? null : GenericTrackingKey.of(rawScan);
    }

    // ── Courier-neutral texts (EN + AR) — used only where a generic shipment or a switched-on tenant is involved ──

    public static final String AMBIGUOUS_EN =
        "This waybill matches two shipments — a Bosta one and another courier's. Give it to a manager.";
    public static final String AMBIGUOUS_AR =
        "هذه البوليصة تطابق شحنتين — واحدة لـ Bosta وأخرى لشركة شحن أخرى. سلّمها للمدير.";

    public static final String NOT_FOUND_EN =
        "No order in this store uses this waybill. If it's another courier's waybill, check its tracking number is on the order in Shopify.";
    public static final String NOT_FOUND_AR =
        "لا يوجد طلب في هذا المتجر يستخدم هذه البوليصة. إن كانت لشركة شحن أخرى، تأكد أن رقم التتبع مضاف للطلب في Shopify.";

    public static final String UNRECOGNISED_EN =
        "This barcode isn't a waybill we recognise. Try the other barcode on the waybill (the tracking number).";
    public static final String UNRECOGNISED_AR =
        "هذا الباركود ليس بوليصة نعرفها. جرّب الباركود الآخر على البوليصة (رقم التتبع).";

    public static final String LAYOUT_EN =
        "This scan came through an Arabic keyboard layout, so its letters were lost. Switch the keyboard to English and scan again.";
    public static final String LAYOUT_AR =
        "وصل هذا المسح بلوحة مفاتيح عربية فضاعت حروفه. حوّل لوحة المفاتيح إلى الإنجليزية وامسح مرة أخرى.";
}
