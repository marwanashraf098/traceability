package com.traceability.places;

import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places S5 — "Move a box" in Put away: scan a BOX sticker, then a shelf sticker (the box goes onto that
 * shelf) or a zone sticker (onto that zone's floor). Same location only. The move itself is PlacesService.move
 * (bins.parent_id + one bin_events 'moved' row with the actor, from → to); the pieces in the box keep their bin_id
 * and follow it — no piece row changes. Every role (workers move boxes). Refused: an archived box or target, a rack
 * or box as target, the box itself, another location's place. 404 unless the tenant has places.
 */
@Service
public class BoxMoveService {

    /** {@code outcome}: MOVED, or ALREADY_THERE (the box was already on that target — nothing written). */
    public record BoxMove(String outcome, PutAwayService.PlaceView box, PutAwayService.PlaceView from,
                          PutAwayService.PlaceView to) {}
    /** The scanned box, checked (the first step of the mode). */
    public record BoxCheck(PutAwayService.PlaceView box, UUID locationId, String locationName, int pieces) {}

    private final JdbcTemplate jdbc;
    private final PlacesService places;

    public BoxMoveService(JdbcTemplate jdbc, PlacesService places) {
        this.jdbc = jdbc;
        this.places = places;
    }

    @Transactional(readOnly = true)
    public BoxCheck checkBox(String boxScan) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        PlacePaths.Place box = box(tenantId, boxScan);
        Integer pieces = jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND bin_id = ?",
            Integer.class, tenantId, box.id());
        return new BoxCheck(PutAwayService.PlaceView.of(box), box.locationId(), locationName(tenantId, box.locationId()),
            pieces == null ? 0 : pieces);
    }

    @Transactional
    public BoxMove move(String boxScan, String targetScan, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        PlacePaths.Place box = box(tenantId, boxScan);
        PlacePaths.Place target = bySticker(tenantId, targetScan);
        if (target.id().equals(box.id())) throw PlaceException.boxOntoItself();
        if (!"shelf".equals(target.kind()) && !"zone".equals(target.kind())) throw PlaceException.boxTarget();
        if (!target.locationId().equals(box.locationId())) {
            throw PlaceException.otherLocation(locationName(tenantId, target.locationId()));
        }
        if (target.archived()) throw PlaceException.archived();
        UUID fromParent = jdbc.queryForObject("SELECT parent_id FROM bins WHERE id = ? AND tenant_id = ?",
            UUID.class, box.id(), tenantId);
        if (target.id().equals(fromParent)) {
            PutAwayService.PlaceView view = PutAwayService.PlaceView.of(box);
            return new BoxMove("ALREADY_THERE", view, PutAwayService.PlaceView.of(target), PutAwayService.PlaceView.of(target));
        }
        places.move(actorUserId, box.id(), target.id());
        return new BoxMove("MOVED", PutAwayService.PlaceView.of(PlacePaths.describe(jdbc, tenantId, box.id())),
            PutAwayService.PlaceView.of(PlacePaths.describe(jdbc, tenantId, fromParent)), PutAwayService.PlaceView.of(target));
    }

    private PlacePaths.Place box(UUID tenantId, String scan) {
        PlacePaths.Place box = bySticker(tenantId, scan);
        if (!"box".equals(box.kind())) throw PlaceException.notABox();
        if (box.archived()) throw PlaceException.archived();
        return box;
    }

    private PlacePaths.Place bySticker(UUID tenantId, String scan) {
        String sticker = scan == null ? null : ScanInputNormalizer.normalizePlace(scan);
        if (sticker == null) throw PlaceException.unknownSticker();
        List<UUID> id = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
            UUID.class, tenantId, sticker);
        if (id.isEmpty()) throw PlaceException.unknownSticker();
        return PlacePaths.describe(jdbc, tenantId, id.get(0));
    }

    private String locationName(UUID tenantId, UUID locationId) {
        List<String> n = jdbc.queryForList("SELECT name FROM locations WHERE id = ? AND tenant_id = ?",
            String.class, locationId, tenantId);
        return n.isEmpty() ? "" : n.get(0);
    }
}
