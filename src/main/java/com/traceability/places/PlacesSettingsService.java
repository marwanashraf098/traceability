package com.traceability.places;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Places rollout (2026-11-01) — Settings › Places "Use places in this store": the merchant's switch for
 * {@code tenants.places_enabled} (default false; owner / manager, the controller gates it). Each change is one
 * audit_log row 'places_switch' (who, when, from → to).
 *
 * <p>Switching OFF deletes nothing: zones, racks, shelves, boxes and every piece's place (pieces.bin_id) are kept and
 * come back when it's switched on again. In the same transaction it ends what can't go on without places: every open
 * put-away session is closed ('closed_by_manager' by the person switching off — placed pieces stay placed), every
 * open place count is cancelled (summary reason 'places_off' — a count changes nothing before Finish), and an open
 * stock take forgets the place it was counting (current_bin_id — its pieces were already placed as they were
 * scanned). Nothing here calls Shopify.
 *
 * <p>{@code setUp}: a location has a live shelf or box ({@link Places#anySetUp}) — until then the rest of the app
 * shows no place feature.
 */
@Service
public class PlacesSettingsService {

    public record Settings(boolean enabled, boolean setUp) {}
    public record Ended(int putAwaySessionsClosed, int placeCountsCancelled, int stockTakesCleared) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PutAwayService putAway;
    private final PlaceCountService counts;

    public PlacesSettingsService(JdbcTemplate jdbc, AuditService audit, PutAwayService putAway, PlaceCountService counts) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.putAway = putAway;
        this.counts = counts;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        UUID tenantId = TenantContext.require();
        return new Settings(Places.enabled(jdbc, tenantId), Places.anySetUp(jdbc, tenantId));
    }

    @Transactional
    public Settings update(UUID actorUserId, boolean enabled) {
        UUID tenantId = TenantContext.require();
        boolean was = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT places_enabled FROM tenants WHERE id = ? FOR UPDATE", Boolean.class, tenantId));
        if (was != enabled) {
            jdbc.update("UPDATE tenants SET places_enabled = ? WHERE id = ?", enabled, tenantId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", was);
            m.put("to", enabled);
            if (!enabled) {
                Ended ended = endOpenWork(tenantId, actorUserId);
                m.put("putAwaySessionsClosed", ended.putAwaySessionsClosed());
                m.put("placeCountsCancelled", ended.placeCountsCancelled());
                m.put("stockTakesCleared", ended.stockTakesCleared());
            }
            audit.record(actorUserId, "places_switch", "tenant", tenantId.toString(), m);
        }
        return get();
    }

    private Ended endOpenWork(UUID tenantId, UUID actorUserId) {
        int sessions = putAway.closeAllOpen(tenantId, actorUserId);
        int placeCounts = counts.cancelAllOpen(tenantId, actorUserId);
        int stockTakes = jdbc.update(
            "UPDATE stock_take_sessions SET current_bin_id = NULL " +
            "WHERE tenant_id = ? AND status = 'open' AND current_bin_id IS NOT NULL", tenantId);
        return new Ended(sessions, placeCounts, stockTakes);
    }
}
