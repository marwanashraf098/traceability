package com.traceability.places;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places S5 — the Inventory place filter's ONE definition of "pieces in this place": a zone or a rack →
 * every place under it (racks, shelves, boxes on its shelves or its floor); a shelf → that shelf only, a box → that
 * box only (a box on a shelf is its own place — the S5 counting rule). Callers filter {@code p.bin_id = ANY(?)}
 * (pieces_bin_idx); "No place yet" is {@code p.bin_id IS NULL}. The place must be in the chosen location.
 */
public final class PlaceScope {

    private PlaceScope() {}

    /** 404 (bodyless) unless the tenant has places — for an existing endpoint that gained a place filter. */
    public static void require(JdbcTemplate jdbc, UUID tenantId) {
        Places.require(jdbc, tenantId);
    }

    /** The place ids the filter covers; PLACE_NOT_FOUND for a place outside the location / tenant. */
    public static List<UUID> binIds(JdbcTemplate jdbc, UUID tenantId, UUID locationId, UUID placeId) {
        if (locationId == null) throw PlaceException.locationRequired();
        List<String> kind = jdbc.queryForList(
            "SELECT kind FROM bins WHERE id = ? AND tenant_id = ? AND location_id = ?",
            String.class, placeId, tenantId, locationId);
        if (kind.isEmpty()) throw PlaceException.placeNotFound();
        if ("shelf".equals(kind.get(0)) || "box".equals(kind.get(0))) return List.of(placeId);
        return jdbc.queryForList(
            "WITH RECURSIVE sub AS ( " +
            "  SELECT id FROM bins WHERE id = ? AND tenant_id = ? " +
            "  UNION ALL " +
            "  SELECT b.id FROM bins b JOIN sub ON b.parent_id = sub.id WHERE b.tenant_id = ? " +
            ") SELECT id FROM sub", UUID.class, placeId, tenantId, tenantId);
    }
}
