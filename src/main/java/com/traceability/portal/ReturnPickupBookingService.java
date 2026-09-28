package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.inventory.ExchangeService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.TrackingNumberNormalizer;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Returns portal Step 4c-3 — books the Bosta CUSTOMER_RETURN_PICKUP (type 25) for an approved
 * return request. MODE B AMENDMENT #2: create type 25 only, from an approved request,
 * claim-before-call, businessReference = the order number. No terminate, no edit.
 *
 * {@link #book} (the job):
 *   1. Load everything fresh in one short transaction. A request whose booking_status is
 *      anything but NULL / 'failed' is left alone (never overwrites a booking).
 *   2. Preconditions — any failure → 'failed' + a clear booking_error, and NO Bosta call.
 *   3. Claim: a committed conditional UPDATE to 'pending' (only from NULL or 'failed').
 *      Zero rows → someone else holds it → exit. This, not a prior SELECT, is the guard.
 *   4. The ONE POST (BostaV2Client.createReturnPickup), outside any transaction.
 *   5. Result in a second short transaction: CREATED → 'booked' + request 'pickup_booked'
 *      (+ return_shipment_id when the shipment already exists); NOT_CREATED → 'failed';
 *      AMBIGUOUS → 'failed_ambiguous', never re-POSTed automatically.
 *   6. Read-back ({@link #verifyInTenant}) right after CREATED.
 *
 * Merchant actions ({@link #retry}, {@link #markNotBooked}, {@link #confirmBooked}) run in the
 * request's TenantContext. The job and the sweeper take the tenant id explicitly and run in
 * TenantContext.runAs. All reads/writes also filter tenant_id (defence in depth over RLS).
 *
 * Programmatic transactions (not @Transactional) so tests can construct this service on a
 * real app_user connection. Never logs customer data.
 *
 * Step 5c — MODE B AMENDMENT #3: an approved EXCHANGE request books a Bosta type 30 exchange
 * through the same framework (claim row, statuses, sweeper, read-back, retry / not-booked /
 * confirm-by-tracking), dispatched by request type: its own preconditions, its own payload
 * ({@link BostaV2Client#createExchange} — this class is its only caller), its own read-back
 * checks. On CREATED the exchange is created in Traced right away
 * ({@link ExchangeService#attachForRequest}: exchanges row + internal replacement order +
 * forward leg), so the replacement goes to Pick & Pack without waiting for Bosta's webhook.
 */
@Service
public class ReturnPickupBookingService {

    private static final Logger log = LoggerFactory.getLogger(ReturnPickupBookingService.class);

    static final int DESCRIPTION_MAX = 250;
    static final Duration STUCK_PENDING = Duration.ofMinutes(15);
    static final Duration ORPHAN_AGE = Duration.ofMinutes(2);
    /** Manual tracking entry: Bosta's createdAt may trail our claim time by clock skew. */
    static final Duration CREATED_SKEW = Duration.ofMinutes(2);
    static final String AMBIGUOUS_STUCK =
        "The booking may or may not have reached Bosta — check in Bosta.";

    /**
     * {@code linksRepaired} (4d-1): requests whose return leg the sweeper linked by tracking number.
     * {@code exchangesAttached} (5c): booked exchanges whose Traced exchange the sweeper created.
     */
    public record SweepResult(int enqueued, int markedAmbiguous, int verifyAttempts, int linksRepaired,
                              int exchangesAttached) {}

    private final JdbcTemplate           jdbc;
    private final TransactionTemplate    tx;
    private final BostaV2Client          bosta;
    private final BostaGateway           gateway;
    private final EncryptionService      encryption;
    private final PickupBookingScheduler scheduler;
    private final ObjectMapper           mapper;
    /** Step 4d-1: request history + leg linking, on this service's own JdbcTemplate. */
    private final ReturnRequestLifecycle requests;
    /** Step 5c: creates the Traced exchange for a booked exchange request. Null only in refund-only test wiring. */
    private final ExchangeService        exchanges;

    /** Refund-only wiring (no exchange attach) — kept for existing callers constructing it by hand. */
    public ReturnPickupBookingService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaV2Client bosta,
                                      BostaGateway gateway, EncryptionService encryption,
                                      PickupBookingScheduler scheduler, ObjectMapper mapper) {
        this(jdbc, txm, bosta, gateway, encryption, scheduler, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReturnPickupBookingService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaV2Client bosta,
                                      BostaGateway gateway, EncryptionService encryption,
                                      PickupBookingScheduler scheduler, ObjectMapper mapper,
                                      ExchangeService exchanges) {
        this.exchanges  = exchanges;
        this.jdbc       = jdbc;
        this.tx         = new TransactionTemplate(txm);
        this.bosta      = bosta;
        this.gateway    = gateway;
        this.encryption = encryption;
        this.scheduler  = scheduler;
        this.mapper     = mapper;
        this.requests   = new ReturnRequestLifecycle(jdbc);
    }

    // ── The job ───────────────────────────────────────────────────────────────

    public void book(UUID requestId, UUID tenantId) {
        TenantContext.runAs(tenantId, () -> bookInTenant(requestId, tenantId));
    }

    private void bookInTenant(UUID requestId, UUID tenantId) {
        Context c = tx.execute(s -> loadContext(requestId, tenantId));
        if (c == null) return;                                       // not this tenant's / gone
        if (c.bookingStatus != null && !"failed".equals(c.bookingStatus)) return;

        // Step 5c: dispatched by request type — an exchange books a type 30, never a type 25.
        String failure = c.exchange ? exchangePrecondition(c) : precondition(c);
        if (failure != null) {
            tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'failed', booking_error = ?, booking_attempted_at = now() " +
                    "WHERE id = ? AND tenant_id = ? AND (booking_status IS NULL OR booking_status = 'failed')",
                    failure, requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_failed", null,
                    ReturnRequestLifecycle.meta("error", failure));
                return n;
            });
            log.info("Return pickup booking request={} outcome=PRECONDITION_FAILED", requestId);
            return;
        }

        Integer claimed = tx.execute(s -> jdbc.update(
            "UPDATE return_requests SET booking_status = 'pending', booking_attempted_at = now(), booking_error = NULL " +
            "WHERE id = ? AND tenant_id = ? AND status = 'approved' " +
            "  AND (booking_status IS NULL OR booking_status = 'failed')",
            requestId, tenantId));
        if (claimed == null || claimed != 1) return;                 // someone else holds the claim

        // The ONE POST — no transaction open, no retry.
        BostaV2Client.CreateResult r = c.exchange
            ? bosta.createExchange(encryption.decrypt(c.apiKeyEncrypted), exchangePayload(c))
            : bosta.createReturnPickup(encryption.decrypt(c.apiKeyEncrypted), payload(c));

        switch (r.outcome()) {
            case CREATED -> {
                if (saveBooked(requestId, tenantId, r.deliveryId(), r.trackingNumber(), false, "pending")) {
                    if (c.exchange) attachExchange(requestId, tenantId, r.trackingNumber());
                    verifyInTenant(requestId, tenantId);
                }
            }
            case NOT_CREATED -> tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'failed', booking_error = ? " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = 'pending'",
                    r.message(), requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_failed", null,
                    ReturnRequestLifecycle.meta("error", r.message()));
                return n;
            });
            case AMBIGUOUS -> tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'failed_ambiguous', booking_error = ? " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = 'pending'",
                    r.message() + " Check in Bosta before retrying.", requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_ambiguous", null,
                    ReturnRequestLifecycle.meta("error", r.message()));
                return n;
            });
        }
    }

    /**
     * booked (+ request pickup_booked); links return_shipment_id when a return leg with that
     * tracking number already exists (webhook arrived first). Only from {@code fromStatus}.
     * A tracking number another request already holds → needs_review instead (unique index).
     * Its own short transactions (a unique violation aborts the transaction it happens in, so
     * the needs_review write must be a separate one).
     */
    private boolean saveBooked(UUID requestId, UUID tenantId, String deliveryId, String tracking,
                               boolean verified, String fromStatus) {
        // Step 4d-1: a leg already held by another request (one request per leg) is never taken.
        String legSql =
            "(SELECT s.id FROM shipments s WHERE s.tenant_id = ? AND s.tracking_number = ? " +
            "   AND s.shipment_leg = 'return' " +
            "   AND NOT EXISTS (SELECT 1 FROM return_requests o WHERE o.return_shipment_id = s.id))";
        try {
            Integer n = tx.execute(s -> {
                int updated = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'booked', booking_error = NULL, " +
                    "    bosta_delivery_id = ?, bosta_tracking_number = ?, status = 'pickup_booked', " +
                    "    booking_verified_at = CASE WHEN ? THEN now() END, " +
                    "    link_source = CASE WHEN return_shipment_id IS NULL AND " + legSql + " IS NOT NULL " +
                    "                       THEN 'traced_booking' ELSE link_source END, " +
                    "    return_shipment_id = COALESCE(return_shipment_id, " + legSql + ") " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = ?",
                    deliveryId, tracking, verified, tenantId, tracking, tenantId, tracking,
                    requestId, tenantId, fromStatus);
                if (updated == 1) {
                    requests.event(tenantId, requestId, "pickup_booked", null,
                        ReturnRequestLifecycle.meta("source", "traced_booking", "tracking_number", tracking));
                    UUID leg = jdbc.queryForObject(
                        "SELECT return_shipment_id FROM return_requests WHERE id = ? AND tenant_id = ?",
                        UUID.class, requestId, tenantId);
                    if (leg != null) requests.event(tenantId, requestId, "leg_linked", null,
                        ReturnRequestLifecycle.meta("source", "traced_booking", "shipment_id", leg.toString(),
                            "tracking_number", tracking));
                }
                return updated;
            });
            return n != null && n == 1;
        } catch (DuplicateKeyException e) {
            tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'needs_review', " +
                    "    booking_error = 'Bosta returned a tracking number that is already on another request.' " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = ?", requestId, tenantId, fromStatus);
                if (n == 1) requests.event(tenantId, requestId, "booking_needs_review", null,
                    ReturnRequestLifecycle.meta("error", "tracking number already on another request",
                        "tracking_number", tracking));
                return n;
            });
            return false;
        }
    }

    /**
     * Step 5c — creates the Traced exchange for a booked exchange request (exchanges row,
     * internal replacement order, forward leg). Idempotent (ExchangeService.attachForRequest).
     * A failure after Bosta booked the trip → needs_review naming it; the booking itself stands.
     * Returns true when the exchange exists afterwards.
     */
    private boolean attachExchange(UUID requestId, UUID tenantId, String tracking) {
        if (exchanges == null) return false;
        String error = null;
        try {
            ExchangeService.AttachOutcome o = exchanges.attachForRequest(tenantId, requestId, tracking);
            if (o == ExchangeService.AttachOutcome.ATTACHED) return true;
            if (o == ExchangeService.AttachOutcome.CONFLICT) {
                error = "Bosta booked the exchange, but its tracking number is already on another exchange in Traced.";
            }
        } catch (RuntimeException e) {
            log.warn("Exchange booking request={} attach failed ({})", requestId, e.getClass().getSimpleName());
            error = "Bosta booked the exchange, but Traced couldn't add the replacement to Pick & Pack.";
        }
        if (error != null) {
            String err = error;
            tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'needs_review', booking_error = ? " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = 'booked'", err, requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_needs_review", null,
                    ReturnRequestLifecycle.meta("error", err));
                return n;
            });
        }
        return false;
    }

    // ── Read-back ─────────────────────────────────────────────────────────────

    /**
     * Reads a 'booked', unverified request's delivery back from Bosta (v0 fetchDelivery) and
     * checks type 25, businessReference, cod 0, itemsCount and the customer district
     * (pickupAddress first — Bosta stores the customer there on a CRP — else dropOffAddress).
     * All match → booking_verified_at; any mismatch → needs_review naming the fields (no PII).
     * A fetch failure leaves it unverified for the sweeper.
     */
    private void verifyInTenant(UUID requestId, UUID tenantId) {
        Map<String, Object> r = tx.execute(s -> jdbc.queryForList(
            "SELECT rr.bosta_tracking_number, rr.pickup_district_id, o.number, rr.type, " +
            // 4d-1: items that were booked — a finished (done) item is no longer active but was in the parcel.
            "       (SELECT COUNT(*) FROM return_request_items i WHERE i.request_id = rr.id " +
            "          AND i.item_status IN ('awaiting', 'arrived', 'done')) AS items, " +
            "       (SELECT ca.api_key_encrypted FROM courier_accounts ca WHERE ca.tenant_id = rr.tenant_id " +
            "          AND ca.provider = 'bosta' AND ca.status = 'active' LIMIT 1) AS api_key " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ? AND rr.booking_status = 'booked' " +
            "  AND rr.booking_verified_at IS NULL AND rr.bosta_tracking_number IS NOT NULL",
            requestId, tenantId).stream().findFirst().orElse(null));
        if (r == null || r.get("api_key") == null) return;

        BostaDelivery d;
        try {
            d = gateway.fetchDelivery(encryption.decrypt((String) r.get("api_key")), (String) r.get("bosta_tracking_number"));
        } catch (RuntimeException e) {
            log.info("Return pickup read-back request={} deferred ({})", requestId, e.getClass().getSimpleName());
            return;
        }
        if (d == null || d.raw() == null) return;

        List<String> diffs = new ArrayList<>();
        JsonNode raw = d.raw();
        if ("exchange".equals(r.get("type"))) {
            // Step 5c — type 30: one item each way, cod 0, and the customer on dropOffAddress
            // (on an exchange pickupAddress is the merchant — never read it for the customer).
            if (d.typeCode() != 30) diffs.add("type");
            if (!sameReference(raw.path("businessReference").asText(null), (String) r.get("number"))) diffs.add("businessReference");
            if (raw.path("cod").asDouble(0) != 0) diffs.add("cod");
            if (raw.path("specs").path("packageDetails").path("itemsCount").asInt(-1) != 1) diffs.add("itemsCount");
            if (raw.path("returnSpecs").path("packageDetails").path("itemsCount").asInt(-1) != 1) diffs.add("returnItemsCount");
            if (!Objects.equals(districtId(raw.path("dropOffAddress")), r.get("pickup_district_id"))) diffs.add("district");
        } else {
        if (d.typeCode() != 25) diffs.add("type");
        if (!sameReference(raw.path("businessReference").asText(null), (String) r.get("number"))) diffs.add("businessReference");
        if (raw.path("cod").asDouble(0) != 0) diffs.add("cod");
        JsonNode count = raw.path("returnSpecs").path("packageDetails").path("itemsCount");
        if (count.isMissingNode() || count.isNull()) count = raw.path("specs").path("packageDetails").path("itemsCount");
        if (count.asInt(-1) != ((Number) r.get("items")).intValue()) diffs.add("itemsCount");
        if (!Objects.equals(customerDistrictId(raw), r.get("pickup_district_id"))) diffs.add("district");
        }

        if (diffs.isEmpty()) {
            tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_verified_at = now() " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = 'booked'", requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_verified", null, null);
                return n;
            });
        } else {
            String error = "Bosta's delivery differs from the request: " + String.join(", ", diffs) + ".";
            tx.execute(s -> {
                int n = jdbc.update(
                    "UPDATE return_requests SET booking_status = 'needs_review', booking_error = ? " +
                    "WHERE id = ? AND tenant_id = ? AND booking_status = 'booked'", error, requestId, tenantId);
                if (n == 1) requests.event(tenantId, requestId, "booking_needs_review", null,
                    ReturnRequestLifecycle.meta("fields", diffs));
                return n;
            });
        }
        log.info("Return pickup read-back request={} outcome={}", requestId, diffs.isEmpty() ? "VERIFIED" : "NEEDS_REVIEW");
    }

    static String customerDistrictId(JsonNode raw) {
        String fromPickup = districtId(raw.path("pickupAddress"));
        return fromPickup != null ? fromPickup : districtId(raw.path("dropOffAddress"));
    }

    private static String districtId(JsonNode address) {
        String id = address.path("district").path("_id").asText(null);
        if (id == null || id.isBlank()) id = address.path("districtId").asText(null);
        return id == null || id.isBlank() ? null : id;
    }

    /** Equal as-is, or equal once a leading '#' is dropped on both sides. */
    static boolean sameReference(String bosta, String orderNumber) {
        if (bosta == null || orderNumber == null) return false;
        String a = bosta.trim(), b = orderNumber.trim();
        return a.equals(b) || strip(a).equals(strip(b));
    }

    private static String strip(String s) { return s.startsWith("#") ? s.substring(1) : s; }

    // ── Merchant actions (TenantContext from the request) ─────────────────────

    /** 'failed' → re-enqueue; the job re-claims from 'failed'. 409 otherwise. */
    public void retry(UUID requestId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> r = requireRequest(requestId, tenantId);
        if (!"failed".equals(r.get("booking_status")) || !"approved".equals(r.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a failed booking of an approved request can be retried.");
        }
        scheduler.enqueue(requestId, tenantId);
    }

    /** "It wasn't booked — retry": 'failed_ambiguous' → 'failed', then retry. 409 otherwise. */
    public void markNotBooked(UUID requestId) {
        UUID tenantId = TenantContext.require();
        requireRequest(requestId, tenantId);
        Integer n = tx.execute(s -> {
            int updated = jdbc.update(
                "UPDATE return_requests SET booking_status = 'failed', " +
                "    booking_error = 'Checked in Bosta: it was not booked.' " +
                "WHERE id = ? AND tenant_id = ? AND booking_status = 'failed_ambiguous'", requestId, tenantId);
            if (updated == 1) requests.event(tenantId, requestId, "booking_failed", null,
                ReturnRequestLifecycle.meta("error", "Checked in Bosta: it was not booked."));
            return updated;
        });
        if (n == null || n != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a booking waiting to be checked in Bosta can be marked not booked.");
        }
        retry(requestId);
    }

    /**
     * "It was booked — enter tracking number": only from 'failed_ambiguous'. Bosta must show a
     * type 25 delivery for this order, created after this booking attempt was claimed (minus a
     * small clock allowance). Then booked + verified.
     */
    public void confirmBooked(UUID requestId, String rawTracking) {
        UUID tenantId = TenantContext.require();
        String tracking = rawTracking == null ? null : TrackingNumberNormalizer.normalize(rawTracking.replaceAll("\\s+", ""));
        if (tracking == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid Bosta tracking number.");
        Map<String, Object> r = tx.execute(s -> jdbc.queryForList(
            "SELECT rr.booking_status, rr.booking_attempted_at, o.number, rr.type, " +
            "       (SELECT ca.api_key_encrypted FROM courier_accounts ca WHERE ca.tenant_id = rr.tenant_id " +
            "          AND ca.provider = 'bosta' AND ca.status = 'active' LIMIT 1) AS api_key " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", requestId, tenantId).stream().findFirst().orElse(null));
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        if (!"failed_ambiguous".equals(r.get("booking_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a booking waiting to be checked in Bosta can be confirmed.");
        }
        if (r.get("api_key") == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "No active Bosta account.");

        BostaDelivery d;
        try {
            d = gateway.fetchDelivery(encryption.decrypt((String) r.get("api_key")), tracking);
        } catch (com.traceability.integrations.bosta.DeliveryNotFoundException e) {
            d = null;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't reach Bosta. Please try again.");
        }
        if (d == null || d.raw() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bosta has no delivery with that tracking number.");
        }
        boolean exchange = "exchange".equals(r.get("type"));
        if (exchange && d.typeCode() != 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That delivery isn't a Bosta exchange.");
        }
        if (!exchange && d.typeCode() != 25) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That delivery isn't a customer return pickup.");
        }
        if (!sameReference(d.raw().path("businessReference").asText(null), (String) r.get("number"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That delivery belongs to a different order.");
        }
        Instant created = parseInstant(d.raw().path("createdAt").asText(null));
        Instant claimedAt = ((Timestamp) r.get("booking_attempted_at")).toInstant();
        if (created == null || created.isBefore(claimedAt.minus(CREATED_SKEW))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That delivery was created before this booking attempt.");
        }
        String deliveryId = d.raw().path("_id").asText(null);
        Boolean taken = tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE bosta_tracking_number = ? AND id <> ?)",
            Boolean.class, tracking, requestId));
        if (Boolean.TRUE.equals(taken)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That tracking number is already on another request.");
        }
        if (!saveBooked(requestId, tenantId, deliveryId, tracking, true, "failed_ambiguous")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That tracking number is already on another request, or the request changed.");
        }
        if (exchange) attachExchange(requestId, tenantId, tracking);
    }

    /**
     * Step 5c — "Book now": an approved exchange with no booking yet (typically approved before
     * "Allow exchanges" was switched on, which the sweeper deliberately leaves alone). Enqueues
     * the same job; the job's own claim (NULL → pending) is the guard. 409 otherwise.
     */
    public void bookNow(UUID requestId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> r = tx.execute(s -> jdbc.queryForList(
            "SELECT status::text AS status, booking_status, type FROM return_requests WHERE id = ? AND tenant_id = ?",
            requestId, tenantId).stream().findFirst().orElse(null));
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        if (!"exchange".equals(r.get("type")) || !"approved".equals(r.get("status")) || r.get("booking_status") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an approved exchange that was never booked can be booked now.");
        }
        scheduler.enqueue(requestId, tenantId);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) {
            try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (Exception e2) { return null; }
        }
    }

    private Map<String, Object> requireRequest(UUID requestId, UUID tenantId) {
        Map<String, Object> r = tx.execute(s -> jdbc.queryForList(
            "SELECT status::text AS status, booking_status FROM return_requests WHERE id = ? AND tenant_id = ?",
            requestId, tenantId).stream().findFirst().orElse(null));
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        return r;
    }

    // ── Sweeper (per tenant) ──────────────────────────────────────────────────

    /**
     * (a) approved requests with no booking yet, decided > 2 min ago and after booking was
     *     switched on → enqueue; (b) 'pending' for > 15 min → 'failed_ambiguous';
     * (c) 'booked' but unverified → read-back again; (d) Step 4d-1: link return legs the
     *     booking/webhook race left unlinked ({@link ReturnRequestLifecycle#repairTrackingLinks}).
     */
    public SweepResult sweepTenant(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> {
            // Refunds: decided after pickup booking was switched on. Step 5c — exchanges: decided
            // after "Allow exchanges" was switched on (older ones get "Book now" instead).
            List<UUID> orphans = tx.execute(s -> jdbc.queryForList(
                "SELECT rr.id FROM return_requests rr JOIN tenants t ON t.id = rr.tenant_id " +
                "WHERE rr.tenant_id = ? AND t.portal_pickup_booking AND t.portal_pickup_booking_since IS NOT NULL " +
                "  AND rr.status = 'approved' AND rr.booking_status IS NULL " +
                "  AND rr.decided_at < now() - (interval '1 second' * ?) " +
                "  AND ((rr.type = 'refund' AND rr.decided_at >= t.portal_pickup_booking_since) " +
                "    OR (rr.type = 'exchange' AND t.portal_exchanges_enabled AND t.portal_exchanges_since IS NOT NULL " +
                "        AND rr.decided_at >= t.portal_exchanges_since)) " +
                "ORDER BY rr.decided_at, rr.id",
                UUID.class, tenantId, ORPHAN_AGE.toSeconds()));
            for (UUID id : orphans) scheduler.enqueue(id, tenantId);

            Integer stuck = tx.execute(s -> {
                List<UUID> ids = jdbc.queryForList(
                    "UPDATE return_requests SET booking_status = 'failed_ambiguous', booking_error = ? " +
                    "WHERE tenant_id = ? AND booking_status = 'pending' " +
                    "  AND booking_attempted_at < now() - (interval '1 second' * ?) RETURNING id",
                    UUID.class, AMBIGUOUS_STUCK, tenantId, STUCK_PENDING.toSeconds());
                for (UUID id : ids) requests.event(tenantId, id, "booking_ambiguous", null,
                    ReturnRequestLifecycle.meta("error", AMBIGUOUS_STUCK));
                return ids.size();
            });

            List<UUID> unverified = tx.execute(s -> jdbc.queryForList(
                "SELECT id FROM return_requests WHERE tenant_id = ? AND booking_status = 'booked' " +
                "  AND booking_verified_at IS NULL ORDER BY booking_attempted_at, id",
                UUID.class, tenantId));
            for (UUID id : unverified) verifyInTenant(id, tenantId);

            // (d) Step 4d-1: repair the booking/webhook race — a booked request whose return leg
            // exists but whose return_shipment_id is still NULL gets it.
            Integer repaired = tx.execute(s -> requests.repairTrackingLinks(tenantId));

            // (e) Step 5c: a booked exchange whose Traced exchange doesn't exist yet (the attach
            // right after booking failed or never ran) gets it.
            int attached = 0;
            if (exchanges != null) {
                List<Map<String, Object>> missing = tx.execute(s -> jdbc.queryForList(
                    "SELECT rr.id, rr.bosta_tracking_number FROM return_requests rr " +
                    "WHERE rr.tenant_id = ? AND rr.type = 'exchange' AND rr.booking_status = 'booked' " +
                    "  AND rr.bosta_tracking_number IS NOT NULL " +
                    "  AND NOT EXISTS (SELECT 1 FROM exchanges e WHERE e.return_request_id = rr.id " +
                    "                  AND e.outbound_order_id IS NOT NULL) " +
                    "ORDER BY rr.booking_attempted_at, rr.id", tenantId));
                for (Map<String, Object> m : missing) {
                    if (attachExchange((UUID) m.get("id"), tenantId, (String) m.get("bosta_tracking_number"))) attached++;
                }
            }

            return new SweepResult(orphans.size(), stuck == null ? 0 : stuck, unverified.size(),
                repaired == null ? 0 : repaired, attached);
        });
    }

    // ── Context, preconditions, payload ───────────────────────────────────────

    private static final class Context {
        UUID requestId; String status; String bookingStatus; String reference; String orderNumber;
        boolean redacted; boolean bookingOn; boolean exchange;
        // Step 5c (exchange): the switch, drop-off availability and the one item each way.
        boolean exchangesOn; Boolean dropoffAvailable; boolean replacementInStock;
        String product; String originalVariant; String replacementVariant; UUID replacementVariantId;
        String apiKeyEncrypted; String returnLocationId;
        String districtId; String cityName; Boolean districtAvailable;
        JsonNode drop; JsonNode receiver; String customerName; String customerPhone;
        /** V117: true when the customer typed a different pickup address (drop = that address). */
        boolean customAddress;
        int itemsCount; List<String> itemLines = new ArrayList<>();
    }

    private Context loadContext(UUID requestId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rr.status::text AS status, rr.booking_status, rr.reference, rr.order_id, rr.pickup_district_id, rr.type, " +
            "       o.number, o.customer_name, o.customer_phone, o.pii_redacted_at, t.portal_pickup_booking, " +
            "       t.portal_exchanges_enabled, d.city_name, d.pickup_available, d.dropoff_available, " +
            "       rr.pickup_address_source, rr.custom_first_line, rr.custom_second_line, rr.custom_building_number, " +
            "       rr.custom_floor, rr.custom_apartment " +
            "FROM return_requests rr " +
            "JOIN orders o  ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "JOIN tenants t ON t.id = rr.tenant_id " +
            "LEFT JOIN bosta_districts d ON d.district_id = rr.pickup_district_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", requestId, tenantId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Context c = new Context();
        c.requestId = requestId;
        c.status = (String) r.get("status");
        c.exchange = "exchange".equals(r.get("type"));
        c.bookingStatus = (String) r.get("booking_status");
        c.reference = (String) r.get("reference");
        c.orderNumber = (String) r.get("number");
        c.redacted = r.get("pii_redacted_at") != null;
        c.bookingOn = Boolean.TRUE.equals(r.get("portal_pickup_booking"));
        c.districtId = (String) r.get("pickup_district_id");
        c.cityName = (String) r.get("city_name");
        c.districtAvailable = (Boolean) r.get("pickup_available");
        c.exchangesOn = Boolean.TRUE.equals(r.get("portal_exchanges_enabled"));
        c.dropoffAvailable = (Boolean) r.get("dropoff_available");
        c.customerName = (String) r.get("customer_name");
        c.customerPhone = (String) r.get("customer_phone");

        jdbc.queryForList(
            "SELECT api_key_encrypted, return_business_location_id FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1", tenantId)
            .stream().findFirst().ifPresent(a -> {
                c.apiKeyEncrypted = (String) a.get("api_key_encrypted");
                c.returnLocationId = (String) a.get("return_business_location_id");
            });

        String raw = jdbc.queryForList(
            "SELECT raw::text FROM shipments WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'forward' " +
            "  AND delivered_at IS NOT NULL AND raw IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1",
            String.class, tenantId, r.get("order_id")).stream().findFirst().orElse(null);
        if (raw != null) {
            try {
                JsonNode n = mapper.readTree(raw);
                c.drop = n.path("dropOffAddress");
                c.receiver = n.path("receiver");
            } catch (Exception ignored) { /* treated as no address below */ }
        }
        // V117: a different pickup address the customer typed replaces the delivery address as
        // the address block — same field names, so both payload builders are unchanged. The
        // receiver (name, phone) still comes from the forward leg / the order. The city and
        // district are the request's snapshot either way.
        c.customAddress = "custom".equals(r.get("pickup_address_source"));
        if (c.customAddress) {
            com.fasterxml.jackson.databind.node.ObjectNode a = mapper.createObjectNode();
            putText(a, "firstLine", (String) r.get("custom_first_line"));
            putText(a, "secondLine", (String) r.get("custom_second_line"));
            putText(a, "buildingNumber", (String) r.get("custom_building_number"));
            putText(a, "floor", (String) r.get("custom_floor"));
            putText(a, "apartment", (String) r.get("custom_apartment"));
            c.drop = a;
        }

        jdbc.query(
            "SELECT pr.title AS product, v.title AS variant, COUNT(*) AS qty " +
            "FROM return_request_items i JOIN variants v ON v.id = i.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? AND i.active " +
            "GROUP BY pr.title, v.title ORDER BY pr.title, v.title",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String variant = rs.getString("variant");
                int qty = rs.getInt("qty");
                c.itemsCount += qty;
                c.itemLines.add(rs.getString("product") + (variant != null && !variant.isBlank() ? " / " + variant : "") + " × " + qty);
            },
            requestId, tenantId);

        if (c.exchange) {
            // Step 5c: the one exchanged item — what comes back and what goes out — and whether
            // the replacement is still in stock (VariantStockService, the one stock derivation).
            jdbc.query(
                "SELECT pr.title AS product, v.title AS variant, rv.id AS replacement_id, rv.title AS replacement " +
                "FROM return_request_items i JOIN variants v ON v.id = i.variant_id " +
                "JOIN products pr ON pr.id = v.product_id " +
                "LEFT JOIN variants rv ON rv.id = i.replacement_variant_id AND rv.tenant_id = i.tenant_id " +
                "WHERE i.request_id = ? AND i.tenant_id = ? AND i.active ORDER BY i.created_at, i.id LIMIT 1",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    c.product = rs.getString("product");
                    c.originalVariant = rs.getString("variant");
                    c.replacementVariantId = rs.getObject("replacement_id", UUID.class);
                    c.replacementVariant = rs.getString("replacement");
                },
                requestId, tenantId);
            if (c.replacementVariantId != null) {
                com.traceability.inventory.VariantStockService stock = new com.traceability.inventory.VariantStockService(jdbc);
                c.replacementInStock = stock.forVariant(stock.computeAll(), c.replacementVariantId).available() > 0;
            }
        }
        return c;
    }

    /** Null when every precondition holds; otherwise the booking_error to record. */
    private String precondition(Context c) {
        if (!"approved".equals(c.status)) return "The request isn't approved.";
        if (!c.bookingOn) return "Bosta pickup booking is switched off in Settings → Returns portal.";
        if (c.apiKeyEncrypted == null) return "There is no active Bosta account.";
        if (c.returnLocationId == null) return "Choose where returns go back to in Settings → Returns portal.";
        if (c.districtId == null) return "Choose the customer's pickup area.";
        if (!Boolean.TRUE.equals(c.districtAvailable) || c.cityName == null) {
            return "The chosen pickup area isn't available for Bosta pickup any more — choose another.";
        }
        if (c.redacted) return "The customer's data was deleted for this order (privacy request).";
        String firstLine = c.drop == null ? null : c.drop.path("firstLine").asText(null);
        if (firstLine == null || firstLine.trim().length() <= 5) {
            return c.customAddress
                ? "The pickup address the customer entered is missing or too short."
                : "The delivery address on the order's Bosta shipment is missing or too short.";
        }
        String[] name = receiverName(c);
        if (name[0] == null || receiverPhone(c) == null) return "The customer's name or phone number is missing.";
        if (c.itemsCount == 0) return "The request has no items.";
        return null;
    }

    /**
     * Step 5c — null when every exchange precondition holds; otherwise the booking_error. Same
     * shape as {@link #precondition}; checked fresh on every attempt.
     */
    private String exchangePrecondition(Context c) {
        if (!"approved".equals(c.status)) return "The request isn't approved.";
        if (!c.exchangesOn) return "Exchanges are switched off in Settings → Returns portal.";
        if (!c.bookingOn) return "Bosta pickup booking is switched off in Settings → Returns portal.";
        if (c.apiKeyEncrypted == null) return "There is no active Bosta account.";
        if (c.returnLocationId == null) return "Choose where returns go back to in Settings → Returns portal.";
        if (c.districtId == null) return "Choose the customer's area.";
        if (!Boolean.TRUE.equals(c.districtAvailable) || !Boolean.TRUE.equals(c.dropoffAvailable) || c.cityName == null) {
            return "Bosta can't both deliver to and collect from the chosen area — choose another.";
        }
        if (c.replacementVariantId == null) return "The request has no replacement.";
        if (!c.replacementInStock) return "The replacement is out of stock.";
        if (c.redacted) return "The customer's data was deleted for this order (privacy request).";
        String firstLine = c.drop == null ? null : c.drop.path("firstLine").asText(null);
        if (firstLine == null || firstLine.trim().length() <= 5) {
            return c.customAddress
                ? "The pickup address the customer entered is missing or too short."
                : "The delivery address on the order's Bosta shipment is missing or too short.";
        }
        String[] name = receiverName(c);
        if (name[0] == null || receiverPhone(c) == null) return "The customer's name or phone number is missing.";
        return null;
    }

    /** "RR-XXXXXX: {product} / {replacement variant}" — what goes out (specs). Shared with ExchangeService. */
    public static String exchangeOutboundDescription(String reference, String product, String replacementVariant) {
        return truncate(reference + ": " + titled(product, replacementVariant));
    }

    /** "{product} / {original variant}" — what comes back (returnSpecs). Shared with ExchangeService. */
    public static String exchangeReturnDescription(String product, String originalVariant) {
        return truncate(titled(product, originalVariant));
    }

    private static String titled(String product, String variant) {
        return (product == null ? "" : product) + (variant != null && !variant.isBlank() ? " / " + variant : "");
    }

    private static String truncate(String s) {
        return s.length() > DESCRIPTION_MAX ? s.substring(0, DESCRIPTION_MAX) : s;
    }

    private BostaV2Client.Exchange exchangePayload(Context c) {
        String[] name = receiverName(c);
        return new BostaV2Client.Exchange(
            c.requestId.toString(), c.orderNumber, c.returnLocationId,
            c.drop.path("firstLine").asText().trim(), opt(c.drop, "secondLine"), opt(c.drop, "buildingNumber"),
            opt(c.drop, "floor"), opt(c.drop, "apartment"), c.cityName, c.districtId,
            name[0], name[1], receiverPhone(c),
            exchangeOutboundDescription(c.reference, c.product, c.replacementVariant),
            exchangeReturnDescription(c.product, c.originalVariant),
            "Traced exchange request " + c.reference);
    }

    private BostaV2Client.ReturnPickup payload(Context c) {
        String[] name = receiverName(c);
        String description = c.reference + ": " + String.join(", ", c.itemLines);
        if (description.length() > DESCRIPTION_MAX) description = description.substring(0, DESCRIPTION_MAX);
        return new BostaV2Client.ReturnPickup(
            c.requestId.toString(), c.orderNumber, c.returnLocationId,
            c.drop.path("firstLine").asText().trim(), opt(c.drop, "secondLine"), opt(c.drop, "buildingNumber"),
            opt(c.drop, "floor"), opt(c.drop, "apartment"), c.cityName, c.districtId,
            name[0], name[1], receiverPhone(c),
            c.itemsCount, description, "Traced return request " + c.reference);
    }

    /** {first, last}: the forward leg's receiver, else orders.customer_name split on the first space. */
    private static String[] receiverName(Context c) {
        if (c.receiver != null) {
            String first = opt(c.receiver, "firstName"), last = opt(c.receiver, "lastName");
            if (first != null) return new String[]{first, last};
            String full = opt(c.receiver, "fullName");
            if (full != null) return split(full);
        }
        return c.customerName == null || c.customerName.isBlank() ? new String[]{null, null} : split(c.customerName);
    }

    private static String[] split(String full) {
        String t = full.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? new String[]{t, null} : new String[]{t.substring(0, sp), t.substring(sp + 1).trim()};
    }

    /** Canonical 01XXXXXXXXX: the forward leg's receiver phone, else orders.customer_phone. */
    private static String receiverPhone(Context c) {
        String p = c.receiver == null ? null : ShipmentLinkService.normalizePhone(opt(c.receiver, "phone"));
        return p != null ? p : ShipmentLinkService.normalizePhone(c.customerPhone);
    }

    private static void putText(com.fasterxml.jackson.databind.node.ObjectNode n, String field, String value) {
        if (value != null) n.put(field, value);
    }

    private static String opt(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
