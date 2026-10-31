package com.traceability.places;

import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PlaceChangeException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Warehouse places S5 — place stickers in the FULL stock take (StockTakeService.scan calls these, inside its own
 * transaction, only for a tenant with places). Scanning a shelf / box sticker of the session's location makes it
 * the place being counted (stock_take_sessions.current_bin_id, V198); every piece scanned after it is put there
 * through InventoryLedger.recordPlaceChange (source 'stock_take'). No sticker scanned → no place ever changes.
 * Counting, classification, finalize, write-offs and the Shopify push never read any of this.
 */
public final class StockTakePlaces {

    private StockTakePlaces() {}

    /**
     * A place sticker scanned in an open stock take: classification 'place_set' (place = the place now counted) or
     * 'place_refused' with reason UNKNOWN_STICKER / PLACE_OTHER_LOCATION / PLACE_ARCHIVED / SCAN_SHELF_OR_BOX (the
     * counted place stays as it was). Nothing is counted.
     */
    public static Map<String, Object> setCountingPlace(JdbcTemplate jdbc, UUID tenantId, UUID sessionId,
                                                       UUID sessionLocationId, String sticker, String barcode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("barcode", barcode);
        out.put("pieceId", null);
        List<UUID> id = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
            UUID.class, tenantId, sticker);
        if (id.isEmpty()) return refused(out, "UNKNOWN_STICKER", null);
        PlacePaths.Place p = PlacePaths.describe(jdbc, tenantId, id.get(0));
        PutAwayService.PlaceView view = PutAwayService.PlaceView.of(p);
        if (!p.locationId().equals(sessionLocationId)) return refused(out, "PLACE_OTHER_LOCATION", view);
        if (p.archived()) return refused(out, "PLACE_ARCHIVED", view);
        if (!"shelf".equals(p.kind()) && !"box".equals(p.kind())) return refused(out, "SCAN_SHELF_OR_BOX", view);
        jdbc.update("UPDATE stock_take_sessions SET current_bin_id = ? WHERE id = ? AND tenant_id = ?",
            p.id(), sessionId, tenantId);
        out.put("classification", "place_set");
        out.put("place", view);
        return out;
    }

    private static Map<String, Object> refused(Map<String, Object> out, String reason, PutAwayService.PlaceView view) {
        out.put("classification", "place_refused");
        out.put("placeRefusal", reason);
        out.put("place", view);
        return out;
    }

    /** The place this stock take is counting now (null: no sticker scanned yet). */
    public static PutAwayService.PlaceView countingPlace(JdbcTemplate jdbc, UUID tenantId, UUID sessionId) {
        List<UUID> bin = jdbc.queryForList(
            "SELECT current_bin_id FROM stock_take_sessions WHERE id = ? AND tenant_id = ? AND current_bin_id IS NOT NULL",
            UUID.class, sessionId, tenantId);
        return bin.isEmpty() ? null : PutAwayService.PlaceView.of(PlacePaths.describe(jdbc, tenantId, bin.get(0)));
    }

    /**
     * A piece scanned while a place is being counted: put there (PLACED / MOVED / ALREADY_HERE), or NOT_PLACED with
     * the ledger's refusal (another location, a status that holds no place, …) — the count itself is unaffected.
     * null when no place is being counted.
     */
    public static Map<String, Object> placeScanned(JdbcTemplate jdbc, UUID tenantId, UUID sessionId, String pieceId,
                                                   UUID actorUserId, boolean viaPhone, String raw) {
        List<UUID> bin = jdbc.queryForList(
            "SELECT current_bin_id FROM stock_take_sessions WHERE id = ? AND tenant_id = ? AND current_bin_id IS NOT NULL",
            UUID.class, sessionId, tenantId);
        if (bin.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("place", PutAwayService.PlaceView.of(PlacePaths.describe(jdbc, tenantId, bin.get(0))));
        try {
            InventoryLedger.PlaceChange ch = new InventoryLedger(jdbc).recordPlaceChange(pieceId, bin.get(0), actorUserId,
                InventoryLedger.PlaceRef.stockTake(sessionId, viaPhone), raw);
            out.put("outcome", switch (ch.kind()) {
                case MOVED -> "MOVED";
                case ALREADY_HERE -> "ALREADY_HERE";
                default -> "PLACED";
            });
        } catch (PlaceChangeException e) {
            out.put("outcome", "NOT_PLACED");
            out.put("reason", e.code().name());
        }
        return out;
    }
}
