package com.traceability.bazar;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bazar B8 — "online stock" per bazar: Remove from online stock ('remove', the default — the send's −N leaves
 * Shopify's Traced Main count, a good leftover's +1 brings it back) or Keep showing online ('leave' — no Shopify write
 * for this bazar's send, sales, returns, damaged, lost or leftovers; TransferShopifySync.claimSend writes no row and the
 * transfer's shopify_sync_mode snapshot is the record). Main reconcile still lowers Shopify for pieces sold / lost /
 * damaged at a Keep bazar (approved 2026-11-22: those pieces are really gone).
 *
 * The choice lives on the bazar's location ({@code locations.shopify_sync_mode}) until Mark sent snapshots it on the
 * transfer, which is final. ONE rule for "an unsent bazar at this location" — its event's transfer is still
 * 'preparing' — used by BazarService (create / reuse / change) and the Settings › Locations switch
 * (LocationController): while such a bazar exists, the location's mode is that bazar's choice and nothing else may
 * change it to the other mode.
 */
public final class BazarOnlineStock {

    private BazarOnlineStock() {}

    public static final String REMOVE = "remove", LEAVE = "leave";
    public static final Set<String> MODES = Set.of(REMOVE, LEAVE);

    /** Unsent bazars at the location (transfer still 'preparing'), other than {@code exceptEventId} (may be null). */
    public static int unsentBazarsAt(JdbcTemplate jdbc, UUID tenantId, UUID locationId, UUID exceptEventId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM bazar_events e JOIN transfers t ON t.id = e.transfer_id AND t.tenant_id = e.tenant_id " +
            "WHERE e.tenant_id = ? AND e.location_id = ? AND t.status = 'preparing' AND e.deleted_at IS NULL AND e.id IS DISTINCT FROM ?",
            Integer.class, tenantId, locationId, exceptEventId);
        return n == null ? 0 : n;
    }

    /**
     * 409 — another bazar at this location that hasn't been sent yet has the other online-stock choice. U1b: names the
     * bazar in the way (the newest such one) — {@code details} {blocking_event_id, blocking_event_name} — so the screen
     * can link to it, and says the choice belongs to the whole location.
     */
    public static BazarException conflict(JdbcTemplate jdbc, UUID tenantId, UUID locationId, UUID exceptEventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.name FROM bazar_events e JOIN transfers t ON t.id = e.transfer_id AND t.tenant_id = e.tenant_id " +
            "WHERE e.tenant_id = ? AND e.location_id = ? AND t.status = 'preparing' AND e.deleted_at IS NULL " +
            "AND e.id IS DISTINCT FROM ? ORDER BY e.created_at DESC, e.id DESC LIMIT 1",
            tenantId, locationId, exceptEventId);
        if (rows.isEmpty()) return conflict(null, null);
        return conflict((UUID) rows.get(0).get("id"), (String) rows.get(0).get("name"));
    }

    static BazarException conflict(UUID blockingId, String blockingName) {
        Map<String, Object> d = null;
        String en = "Another bazar at this location", ar = "يوجد بازار آخر في هذا الموقع";
        if (blockingId != null) {
            d = new LinkedHashMap<>();
            d.put("blocking_event_id", blockingId);
            d.put("blocking_event_name", blockingName);
            en = "\"" + blockingName + "\" is at this location too";
            ar = "بازار «" + blockingName + "» في هذا الموقع أيضًا";
        }
        return new BazarException(BazarException.Code.BAZAR_ONLINE_STOCK_CONFLICT,
            en + ", hasn't been sent yet and has the other online stock choice. The choice applies to the whole " +
            "location — send or change that bazar first, or pick another location.",
            ar + " ولم يُرسَل بعد واختياره للمخزون أونلاين مختلف. الاختيار ينطبق على الموقع كله — " +
            "أرسل ذلك البازار أو غيّر اختياره أولًا، أو اختر موقعًا آخر.",
            HttpStatus.CONFLICT, d);
    }

    /** 409 — the choice can't change once the stock was sent (the transfer's snapshot is final). */
    public static BazarException locked() {
        return new BazarException(BazarException.Code.BAZAR_ONLINE_STOCK_LOCKED,
            "The stock was already sent — the online stock choice can't be changed for this bazar.",
            "تم إرسال البضاعة بالفعل — لا يمكن تغيير اختيار المخزون أونلاين لهذا البازار.",
            HttpStatus.CONFLICT);
    }

    static BazarException invalidMode() {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT,
            "Choose Remove from online stock or Keep showing online.",
            "اختر «إزالة من المخزون أونلاين» أو «إبقاؤها ظاهرة أونلاين».", HttpStatus.BAD_REQUEST);
    }
}
