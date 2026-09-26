package com.traceability.portal;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Returns portal Step 4b — the merchant's side of customer return requests: list, detail,
 * approve, reject. Tenant-scoped by TenantContext (request filter) + RLS, with explicit
 * tenant_id filters as defence in depth. No pieces move here; a rejected request only
 * deactivates its items so those pieces are returnable again.
 *
 * Step 4d-1: after approval the lifecycle (leg linking, rest-not-coming, close, request
 * history) is {@link ReturnRequestLifecycle}'s; this service only exposes it.
 */
@Service
public class ReturnRequestService {

    static final int REASON_MAX = 300;
    private static final Set<String> STATUSES = Set.of(
        "requested", "approved", "rejected", "pickup_booked", "received", "refund_pending", "refunded", "cancelled",
        "closed");

    private final JdbcTemplate           jdbc;
    private final PickupAreaService      pickupAreas;
    private final PickupBookingScheduler bookingScheduler;
    private final ReturnRequestLifecycle requests;

    /** Without a scheduler (tests on an app_user connection): approving never enqueues a booking. */
    public ReturnRequestService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReturnRequestService(JdbcTemplate jdbc, PickupBookingScheduler bookingScheduler) {
        this.jdbc             = jdbc;
        // Same JdbcTemplate (not injected), so an app_user-constructed instance reads on it too.
        this.pickupAreas      = new PickupAreaService(jdbc);
        this.bookingScheduler = bookingScheduler;
        this.requests         = new ReturnRequestLifecycle(jdbc);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String status, int page, int size) {
        UUID tenantId = TenantContext.require();
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        String statusFilter = status == null || status.isBlank() ? null : status;
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT rr.id, rr.reference, o.number AS order_number, o.customer_name, rr.status::text AS status, " +
            "       rr.created_at, rr.booking_status, " +
            "       (SELECT COUNT(*) FROM return_request_items i WHERE i.request_id = rr.id) AS item_count, " +
            "       (SELECT array_agg(DISTINCT i.reason_code ORDER BY i.reason_code) " +
            "          FROM return_request_items i WHERE i.request_id = rr.id) AS reason_codes " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.tenant_id = ? AND (?::text IS NULL OR rr.status::text = ?) " +
            "ORDER BY rr.created_at DESC, rr.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id", UUID.class).toString());
                row.put("reference", rs.getString("reference"));
                row.put("orderNumber", rs.getString("order_number"));
                row.put("customerName", rs.getString("customer_name"));
                row.put("itemCount", rs.getInt("item_count"));
                java.sql.Array codes = rs.getArray("reason_codes");
                row.put("reasonCodes", codes == null ? List.of() : Arrays.asList((String[]) codes.getArray()));
                row.put("status", rs.getString("status"));
                row.put("bookingStatus", rs.getString("booking_status"));
                row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return row;
            },
            tenantId, statusFilter, statusFilter, size, page * size);
        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_requests rr WHERE rr.tenant_id = ? AND (?::text IS NULL OR rr.status::text = ?)",
            Integer.class, tenantId, statusFilter, statusFilter);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("total", total);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rr.id, rr.reference, rr.order_id, o.number AS order_number, o.customer_name, " +
            "       rr.type, rr.status::text AS status, rr.customer_email, rr.customer_note, rr.created_at, " +
            "       rr.decided_at, rr.decided_by, u.name AS decided_by_name, rr.rejection_reason, rr.return_shipment_id, " +
            "       o.customer_phone, o.address->>'city' AS address_city, o.address->>'zone' AS address_zone, " +
            "       rr.pickup_city_id, rr.pickup_city_name, rr.pickup_district_id, rr.pickup_district_name, " +
            "       rr.pickup_district_name_ar, rr.booking_status, rr.booking_error, rr.bosta_tracking_number, " +
            "       rr.booking_attempted_at, rr.booking_verified_at, " +
            "       rr.received_at, rr.refund_pending_at, rr.closed_at, rr.closed_by, cu.name AS closed_by_name, " +
            "       rr.close_reason, rr.close_note, rr.link_source, " +
            "       (SELECT s.delivered_at FROM shipments s " +
            "         WHERE s.order_id = rr.order_id AND s.tenant_id = rr.tenant_id " +
            "           AND s.shipment_leg = 'forward' AND s.delivered_at IS NOT NULL " +
            "         ORDER BY s.delivered_at DESC, s.id DESC LIMIT 1) AS delivered_at " +
            "FROM return_requests rr " +
            "JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "LEFT JOIN users u ON u.id = rr.decided_by " +
            "LEFT JOIN users cu ON cu.id = rr.closed_by " +
            "WHERE rr.id = ? AND rr.tenant_id = ?",
            id, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        Map<String, Object> r = rows.get(0);

        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT i.id, i.piece_id AS \"pieceId\", p.short_code AS \"shortCode\", i.variant_id AS \"variantId\", " +
            "       pr.title AS \"productTitle\", v.title AS \"variantTitle\", pr.image_url AS \"imageUrl\", " +
            "       i.reason_code AS \"reasonCode\", i.active, i.item_status AS \"itemStatus\", " +
            "       i.arrived_at AS \"arrivedAt\", i.done_at AS \"doneAt\" " +
            "FROM return_request_items i " +
            "JOIN pieces p    ON p.id = i.piece_id " +
            "JOIN variants v  ON v.id = i.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? " +
            "ORDER BY pr.title, v.title, p.created_at, i.id",
            id, tenantId);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id").toString());
        d.put("reference", r.get("reference"));
        d.put("orderId", r.get("order_id").toString());
        d.put("orderNumber", r.get("order_number"));
        d.put("customerName", r.get("customer_name"));
        d.put("type", r.get("type"));
        d.put("status", r.get("status"));
        d.put("email", r.get("customer_email"));
        d.put("note", r.get("customer_note"));
        // Step 4e-A (M2): phone, delivery date and pickup area for the merchant's drawer.
        d.put("customerPhone", r.get("customer_phone"));
        d.put("deliveredAt", r.get("delivered_at"));
        d.put("pickupCity", r.get("address_city"));
        d.put("pickupZone", r.get("address_zone"));
        // Step 4c-2: the pickup area snapshot (null when none was chosen — the drawer then
        // falls back to pickupCity / pickupZone from the order address above).
        d.put("pickupCityId", r.get("pickup_city_id"));
        d.put("pickupCityName", r.get("pickup_city_name"));
        d.put("pickupDistrictId", r.get("pickup_district_id"));
        d.put("pickupDistrictName", r.get("pickup_district_name"));
        d.put("pickupDistrictNameAr", r.get("pickup_district_name_ar"));
        // Step 4c-3: the Bosta return pickup booking.
        d.put("bookingStatus", r.get("booking_status"));
        d.put("bookingError", r.get("booking_error"));
        d.put("bostaTrackingNumber", r.get("bosta_tracking_number"));
        d.put("bookingAttemptedAt", r.get("booking_attempted_at"));
        d.put("bookingVerifiedAt", r.get("booking_verified_at"));
        d.put("createdAt", r.get("created_at"));
        d.put("decidedAt", r.get("decided_at"));
        d.put("decidedBy", r.get("decided_by"));
        d.put("decidedByName", r.get("decided_by_name"));
        d.put("rejectionReason", r.get("rejection_reason"));
        d.put("returnShipmentId", r.get("return_shipment_id"));
        // Step 4d-1: lifecycle, close outcome, how the return leg was linked, history.
        d.put("linkSource", r.get("link_source"));
        d.put("receivedAt", r.get("received_at"));
        d.put("refundPendingAt", r.get("refund_pending_at"));
        d.put("closedAt", r.get("closed_at"));
        d.put("closedBy", r.get("closed_by"));
        d.put("closedByName", r.get("closed_by_name"));
        d.put("closeReason", r.get("close_reason"));
        d.put("closeNote", r.get("close_note"));
        d.put("items", items);
        d.put("events", requests.events(tenantId, id));
        return d;
    }

    /**
     * requested → approved. Anything else → 409 (unknown id → 404). Step 4c-3: when the tenant
     * books Bosta pickups, the booking job is enqueued only AFTER this transaction commits —
     * a rollback means no job.
     */
    @Transactional
    public void approve(UUID id, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        int updated = jdbc.update(
            "UPDATE return_requests SET status = 'approved', decided_at = now(), decided_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'requested'",
            actorUserId, id, tenantId);
        if (updated != 1) throw notRequested(id, tenantId);
        requests.event(tenantId, id, "approved", actorUserId, null);
        if (bookingScheduler != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, tenantId))) {
            bookingScheduler.enqueueAfterCommit(id, tenantId);
        }
    }

    /**
     * requested → rejected with a reason (required, ≤ 300). Deactivates the request's items in
     * the same transaction, so those pieces are returnable again (lookup counts them).
     */
    @Transactional
    public void reject(UUID id, String reason, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        String r = reason == null ? null : reason.trim();
        if (r == null || r.isEmpty() || r.length() > REASON_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A rejection reason is required (at most " + REASON_MAX + " characters).");
        }
        int updated = jdbc.update(
            "UPDATE return_requests SET status = 'rejected', decided_at = now(), decided_by = ?, rejection_reason = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'requested'",
            actorUserId, r, id, tenantId);
        if (updated != 1) throw notRequested(id, tenantId);
        // 4d-1: items → not_coming (active false — V108 keeps the two in step).
        requests.releaseAllOnReject(tenantId, id);
        requests.event(tenantId, id, "rejected", actorUserId, ReturnRequestLifecycle.meta("reason", r));
    }

    // ── Step 4d-1: lifecycle actions (owner / manager) ───────────────────────

    /** POST /return-requests/{id}/link-leg {shipmentId}. */
    @Transactional
    public void linkLeg(UUID id, UUID shipmentId, UUID actorUserId) {
        requests.linkLegByMerchant(TenantContext.require(), id, shipmentId, actorUserId);
    }

    /** POST /return-requests/{id}/rest-not-coming. */
    @Transactional
    public void restNotComing(UUID id, UUID actorUserId) {
        requests.restNotComing(TenantContext.require(), id, actorUserId);
    }

    /** POST /return-requests/{id}/close {reason, note}. */
    @Transactional
    public void close(UUID id, String reason, String note, UUID actorUserId) {
        requests.close(TenantContext.require(), id, reason, note, actorUserId);
    }

    // ── Step 4c-2: pickup area ───────────────────────────────────────────────

    private static final Set<String> AREA_EDITABLE = Set.of("requested", "approved");
    /** Step 4c-3: once a pickup is booked (or being booked) the area is Bosta's, not ours to change. */
    private static final Set<String> AREA_LOCKED_BOOKING = Set.of("pending", "booked", "needs_review");

    /**
     * GET /return-requests/{id}/pickup-areas — the pickup-available districts of the request's
     * city (its snapshot city, else the city the order was delivered to). Empty districts when
     * the city is unknown or has none. Does not depend on the tenant's booking switch.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> pickupAreas(UUID id) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> rr = requireRequest(id, tenantId);
        Optional<PickupAreaService.CityAreas> areas = cityAreas(tenantId, rr);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cityId", areas.map(PickupAreaService.CityAreas::cityId).orElse(null));
        body.put("cityName", areas.map(PickupAreaService.CityAreas::cityName).orElse(null));
        body.put("cityNameAr", areas.map(PickupAreaService.CityAreas::cityNameAr).orElse(null));
        body.put("districts", areas.map(a -> a.districts().stream().map(PickupAreaService.District::toJson).toList())
            .orElse(List.of()));
        body.put("selectedDistrictId", rr.get("pickup_district_id"));
        body.put("editable", AREA_EDITABLE.contains((String) rr.get("status"))
            && !areaLockedByBooking(rr));
        return body;
    }

    /**
     * PUT /return-requests/{id}/pickup-area — while requested or approved only (409 otherwise);
     * the district must be pickup-available in the request's city (400 otherwise). Stores the
     * same snapshot fields the portal submission does.
     */
    @Transactional
    public void setPickupArea(UUID id, String districtId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> rr = requireRequest(id, tenantId);
        if (!AREA_EDITABLE.contains((String) rr.get("status"))
                || areaLockedByBooking(rr)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The pickup area can only be changed while the request is awaiting a decision or approved.");
        }
        if (districtId == null || districtId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "districtId is required");
        }
        PickupAreaService.CityAreas areas = cityAreas(tenantId, rr).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "No pickup areas are available for this order's city."));
        PickupAreaService.District d = areas.find(districtId.trim()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "That area isn't available for pickup in this city."));
        int updated = jdbc.update(
            "UPDATE return_requests SET pickup_city_id = ?, pickup_city_name = ?, pickup_district_id = ?, " +
            "    pickup_district_name = ?, pickup_district_name_ar = ? " +
            "WHERE id = ? AND tenant_id = ? AND status IN ('requested', 'approved') " +
            "  AND (booking_status IS NULL OR booking_status IN ('failed', 'failed_ambiguous'))",
            areas.cityId(), areas.cityName(), d.id(), d.name(), d.nameAr(), id, tenantId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The pickup area can only be changed while the request is awaiting a decision or approved.");
        }
    }

    private static boolean areaLockedByBooking(Map<String, Object> rr) {
        Object b = rr.get("booking_status");   // Set.of().contains(null) would throw
        return b != null && AREA_LOCKED_BOOKING.contains((String) b);
    }

    private Map<String, Object> requireRequest(UUID id, UUID tenantId) {
        return jdbc.queryForList(
            "SELECT id, order_id, status::text AS status, pickup_city_id, pickup_district_id, booking_status " +
            "FROM return_requests WHERE id = ? AND tenant_id = ?", id, tenantId)
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found"));
    }

    private Optional<PickupAreaService.CityAreas> cityAreas(UUID tenantId, Map<String, Object> rr) {
        String snapshotCity = (String) rr.get("pickup_city_id");
        return snapshotCity != null
            ? pickupAreas.forCity(snapshotCity, null)
            : pickupAreas.forOrder(tenantId, (UUID) rr.get("order_id"));
    }

    private ResponseStatusException notRequested(UUID id, UUID tenantId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE id = ? AND tenant_id = ?)",
            Boolean.class, id, tenantId);
        return Boolean.TRUE.equals(exists)
            ? new ResponseStatusException(HttpStatus.CONFLICT, "Only a request awaiting a decision can be approved or rejected.")
            : new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
    }
}
