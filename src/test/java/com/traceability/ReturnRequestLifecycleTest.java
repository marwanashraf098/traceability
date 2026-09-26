package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.portal.PortalTokenService;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.portal.ReturnRequestService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Returns Step 4d-1 — linking returned parcels to return requests, attributing and
 * reconciling intake scans, the request lifecycle (received / refund_pending / closed),
 * reservation release, and request history (return_request_events).
 *
 * L* linking (tracking number, race repair, hand-booked auto-match, ambiguity, link-leg),
 * S* intake scans (late approved return, substitute, different variant, exact leg
 * attribution through the canonical rule), C* lifecycle, E* history, X* cross-tenant on a
 * real app_user connection. CRP legs go through the real ingest pipeline
 * (BostaIngestionHelper → BostaWebhookJob.process), type.code 25, state 41 = in transit.
 * The reservation bug itself is ReturnReservationReleaseTest (pre-4d-1 APIs only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnRequestLifecycleTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    private static final String RAW_API_KEY = "lifecycle-api-key";
    private static final String SLUG = "lifecycle-4d1";
    private static final String REF_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired EncryptionService          encryptionService;
    @Autowired BostaWebhookJob            webhookJob;
    @Autowired BostaIngestionHelper       ingestionHelper;
    @Autowired ReturnSessionService       sessions;
    @Autowired ReturnRequestService       requests;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ExceptionService           exceptions;
    @Autowired PortalService              portal;
    @Autowired PortalTokenService         tokens;

    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId, variantId, variant2Id, locationId, ownerId;
    UUID tenantB, storeB, variantB, ownerB;

    ReturnRequestService appUserRequests;
    JdbcTemplate         appUserJdbc;
    TransactionTemplate  appUserTx;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        variant2Id = UUID.randomUUID();
        locationId = UUID.randomUUID();
        ownerId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Lifecycle Tenant', ?, true)",
            tenantId, SLUG);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@lifecycle.test', 'h', 'owner')", ownerId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')", locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'lifecycle.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-LC', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-LC', 'Sand M', 'LIN-SAND-M')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-LC2', 'Olive L', 'LIN-OLIVE-L')", variant2Id, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'lifecycle-hash', 'active')",
                    tenantId, encryptionService.encrypt(RAW_API_KEY));

        tenantB = UUID.randomUUID();
        storeB  = UUID.randomUUID();
        variantB = UUID.randomUUID();
        ownerB  = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Lifecycle Tenant B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner B', 'owner@lifecycle-b.test', 'h', 'owner')", ownerB, tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'lifecycle-b.myshopify.com', 'disconnected')", storeB, tenantB);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-LCB', 'Tote', 'active')", productB, tenantB, storeB);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-LCB', 'Black', 'TOTE-BLK')", variantB, tenantB, productB);

        DataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc     = new JdbcTemplate(appUserDs);
        appUserRequests = new ReturnRequestService(appUserJdbc);
        appUserTx       = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeEach void setCtx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantId, tenantB)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
    }

    // ── L: linking a return leg to its request ──────────────────────────────────

    @Test
    void l1_trackingNumberLinkBeatsAutoMatch() {
        Order o = deliveredOrder(true, variantId, variantId);
        UUID r2 = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(1));
        String tracking = bareTracking();
        UUID r1 = request(o, "pickup_booked", Instant.now().minus(30, ChronoUnit.MINUTES), o.pieces().get(0));
        jdbc.update("UPDATE return_requests SET booking_status = 'booked', bosta_tracking_number = ? WHERE id = ?", tracking, r1);

        ingestCrp(o, tracking, Instant.now(), 41);

        UUID leg = legId(tracking);
        assertThat(col(r1, "return_shipment_id")).isEqualTo(leg);
        assertThat(col(r1, "link_source")).isEqualTo("traced_booking");
        assertThat(col(r2, "return_shipment_id")).as("the auto-match candidate is not given the leg").isNull();
        assertThat(col(r2, "status")).isEqualTo("approved");
        assertThat(eventTypes(r1)).contains("leg_linked");
    }

    @Test
    void l2_sweeperRepairsARaceLeftUnlinkedRequest() {
        Order o = deliveredOrder(true, variantId);
        String tracking = bareTracking();
        UUID r = request(o, "pickup_booked", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));
        jdbc.update("UPDATE return_requests SET booking_status = 'booked', bosta_tracking_number = ?, " +
                    "booking_verified_at = now() WHERE id = ?", tracking, r);
        // Both sides of the race missed: the leg exists, the request isn't linked.
        UUID leg = UUID.randomUUID();
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, ?, 'bosta', ?, 'returning'::shipment_internal_state, 'return')", leg, tenantId, o.id(), tracking);

        ReturnPickupBookingService.SweepResult res = booking.sweepTenant(tenantId);

        assertThat(res.linksRepaired()).isEqualTo(1);
        assertThat(col(r, "return_shipment_id")).isEqualTo(leg);
        assertThat(col(r, "link_source")).isEqualTo("traced_booking");
        Map<String, Object> ev = lastEvent(r, "leg_linked");
        assertThat(ev.get("meta")).asString().contains("\"repaired\": true");
        assertThat(booking.sweepTenant(tenantId).linksRepaired()).as("idempotent").isZero();
    }

    @Test
    void l3_handBookedLeg_exactlyOneCandidate_autoMatched() {
        Order o = deliveredOrder(true, variantId);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));

        String tracking = ingestCrp(o, bareTracking(), Instant.now(), 41);

        assertThat(col(r, "return_shipment_id")).isEqualTo(legId(tracking));
        assertThat(col(r, "link_source")).isEqualTo("auto_matched");
        assertThat(col(r, "status")).isEqualTo("pickup_booked");
        assertThat(eventTypes(r)).containsSubsequence("leg_linked", "pickup_booked");
    }

    @Test
    void l4_handBookedLeg_noCandidate_nothingLinked() {
        Order o = deliveredOrder(true, variantId, variantId);
        UUID requested = request(o, "requested", Instant.now().minus(3, ChronoUnit.HOURS), o.pieces().get(0));
        // Approved, but created after the leg was created in Bosta → not a candidate.
        UUID later = request(o, "approved", Instant.now(), o.pieces().get(1));

        String tracking = ingestCrp(o, bareTracking(), Instant.now().minus(2, ChronoUnit.HOURS), 41);

        assertThat(legId(tracking)).as("the leg is still linked to the order").isNotNull();
        assertThat(col(requested, "return_shipment_id")).isNull();
        assertThat(col(later, "return_shipment_id")).isNull();
        assertThat(col(later, "status")).isEqualTo("approved");
        assertThat(ambiguous()).isEmpty();
    }

    @Test
    void l5_twoCandidates_neverGuessed_exceptionRaised_thenLinkLegResolvesIt() {
        Order o = deliveredOrder(true, variantId, variantId);
        UUID ra = request(o, "approved", Instant.now().minus(2, ChronoUnit.HOURS), o.pieces().get(0));
        UUID rb = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(1));

        String tracking = ingestCrp(o, bareTracking(), Instant.now(), 41);
        UUID leg = legId(tracking);

        assertThat(col(ra, "return_shipment_id")).isNull();
        assertThat(col(rb, "return_shipment_id")).isNull();
        List<Map<String, Object>> ex = ambiguous();
        assertThat(ex).hasSize(1);
        assertThat(ex.get(0).get("severity")).isEqualTo("MEDIUM");
        assertThat(ex.get(0).get("shipment_id")).isEqualTo(leg);
        assertThat((String) ex.get(0).get("descriptionEn"))
            .contains(o.number()).contains(tracking).contains(reference(ra)).contains(reference(rb));
        assertThat((String) ex.get(0).get("descriptionAr")).contains(o.number()).contains(reference(ra));

        requests.linkLeg(ra, leg, ownerId);

        assertThat(col(ra, "return_shipment_id")).isEqualTo(leg);
        assertThat(col(ra, "link_source")).isEqualTo("merchant_selected");
        assertThat(col(ra, "status")).isEqualTo("pickup_booked");
        assertThat(ambiguous()).as("linking takes the leg out of the exception").isEmpty();
        Map<String, Object> ev = lastEvent(ra, "leg_linked");
        assertThat(ev.get("actor")).isEqualTo(ownerId);
    }

    @Test
    void l6_linkLeg_rejectsAnotherOrdersLeg_aForwardLeg_andAnAlreadyLinkedLeg() {
        Order o = deliveredOrder(true, variantId, variantId);
        Order other = deliveredOrder(true, variantId);
        UUID ra = request(o, "approved", Instant.now().minus(2, ChronoUnit.HOURS), o.pieces().get(0));
        UUID rb = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(1));
        UUID otherLeg = legId(ingestCrp(other, bareTracking(), Instant.now(), 41));
        UUID leg = legId(ingestCrp(o, bareTracking(), Instant.now(), 41));   // two candidates → not linked

        assertStatus(400, () -> requests.linkLeg(ra, otherLeg, ownerId));
        assertStatus(400, () -> requests.linkLeg(ra, o.forwardLeg(), ownerId));
        requests.linkLeg(ra, leg, ownerId);
        assertStatus(409, () -> requests.linkLeg(rb, leg, ownerId));
        assertThat(col(rb, "return_shipment_id")).isNull();
    }

    @Test
    void l7_uniqueIndex_stopsTwoRequestsHoldingOneLeg() {
        Order o = deliveredOrder(true, variantId, variantId);
        UUID ra = request(o, "approved", Instant.now().minus(2, ChronoUnit.HOURS), o.pieces().get(0));
        UUID rb = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(1));
        UUID leg = legId(ingestCrp(o, bareTracking(), Instant.now(), 41));
        requests.linkLeg(ra, leg, ownerId);

        assertThatThrownBy(() -> jdbc.update("UPDATE return_requests SET return_shipment_id = ? WHERE id = ?", leg, rb))
            .isInstanceOf(DuplicateKeyException.class);
    }

    // ── S: intake scans ─────────────────────────────────────────────────────────

    @Test
    void s1_lateApprovedReturn_acceptedOutsideTheWindow_andAttributed() {
        Order o = deliveredOrder(false, variantId);
        Order control = deliveredOrder(false, variantId);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));
        UUID item = itemId(r, o.pieces().get(0));

        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + control.pieces().get(0), locationId, ownerId);
        assertThat(pieceStatus(control.pieces().get(0)))
            .as("control: out of window, no request → not accepted").isEqualTo("delivered");

        sessions.scan(s, "PC-" + o.pieces().get(0), locationId, ownerId);

        assertThat(pieceStatus(o.pieces().get(0))).isEqualTo("return_pending_inspection");
        Map<String, Object> meta = returnReceivedMeta(o.pieces().get(0));
        assertThat(meta.get("return_kind")).isEqualTo("request_return");
        assertThat(meta.get("request_id")).isEqualTo(r.toString());
        assertThat(meta.get("request_item_id")).isEqualTo(item.toString());
        assertThat(jdbc.queryForObject("SELECT request_item_id FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            UUID.class, s, o.pieces().get(0))).isEqualTo(item);
        assertThat(itemStatus(item)).isEqualTo("arrived");
        assertThat(col(r, "status")).isEqualTo("received");
        assertThat(col(r, "received_at")).isNotNull();
    }

    @Test
    void s2_sameVariantSubstitute_swapsTheBinding_originalStaysWithTheCustomer() {
        Order o = deliveredOrder(false, variantId, variantId);
        String bound = o.pieces().get(0), arriving = o.pieces().get(1);
        UUID r = request(o, "pickup_booked", Instant.now().minus(1, ChronoUnit.HOURS), bound);
        UUID item = itemId(r, bound);

        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + arriving, locationId, ownerId);

        assertThat(pieceStatus(arriving)).isEqualTo("return_pending_inspection");
        assertThat(jdbc.queryForObject("SELECT piece_id FROM return_request_items WHERE id = ?", String.class, item))
            .isEqualTo(arriving);
        assertThat(itemStatus(item)).isEqualTo("arrived");
        Map<String, Object> ev = lastEvent(r, "item_substituted");
        assertThat(ev.get("meta")).asString().contains(bound).contains(arriving);
        assertThat(returnReceivedMeta(arriving).get("request_item_id")).isEqualTo(item.toString());

        // The original is no longer reserved, and scanning it later is never attached to the request.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_request_items WHERE piece_id = ? AND active",
            Integer.class, bound)).isZero();
        sessions.scan(s, "PC-" + bound, locationId, ownerId);
        assertThat(pieceStatus(bound)).as("out of window, no awaiting item left → not accepted").isEqualTo("delivered");
    }

    @Test
    void s3_differentVariant_acceptedByTodaysRules_notedOnTheRequest_bindsNothing() {
        Order o = deliveredOrder(true, variantId, variant2Id);
        String bound = o.pieces().get(0), other = o.pieces().get(1);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), bound);
        UUID item = itemId(r, bound);

        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + other, locationId, ownerId);

        assertThat(pieceStatus(other)).as("in window → accepted as today").isEqualTo("return_pending_inspection");
        Map<String, Object> meta = returnReceivedMeta(other);
        assertThat(meta.get("return_kind")).isEqualTo("customer_after_delivery");
        assertThat(meta).doesNotContainKey("request_id");
        assertThat(jdbc.queryForObject("SELECT request_item_id FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            UUID.class, s, other)).isNull();
        assertThat(jdbc.queryForObject("SELECT piece_id FROM return_request_items WHERE id = ?", String.class, item))
            .isEqualTo(bound);
        assertThat(itemStatus(item)).isEqualTo("awaiting");
        assertThat(col(r, "status")).isEqualTo("approved");
        Map<String, Object> ev = lastEvent(r, "unexpected_item_received");
        assertThat(ev.get("meta")).asString().contains(other).contains(variant2Id.toString());
    }

    @Test
    void s4_exactLegAttribution_throughTheCanonicalRule() {
        Order o = deliveredOrder(false, variantId, variantId);
        String ta = bareTracking(), tb = bareTracking();
        UUID ra = request(o, "pickup_booked", Instant.now().minus(2, ChronoUnit.HOURS), o.pieces().get(0));
        UUID rb = request(o, "pickup_booked", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(1));
        jdbc.update("UPDATE return_requests SET booking_status = 'booked', bosta_tracking_number = ? WHERE id = ?", ta, ra);
        jdbc.update("UPDATE return_requests SET booking_status = 'booked', bosta_tracking_number = ? WHERE id = ?", tb, rb);
        ingestCrp(o, ta, Instant.now(), 41);
        ingestCrp(o, tb, Instant.now(), 41);
        assertThat(col(ra, "return_shipment_id")).isEqualTo(legId(ta));
        assertThat(col(rb, "return_shipment_id")).isEqualTo(legId(tb));

        // B's item arrives with no AWB scan, both legs still with the courier.
        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + o.pieces().get(1), locationId, ownerId);
        sessions.disposition(s, o.pieces().get(1), "restock", null, locationId, ownerId);
        sessions.close(s, ownerId);

        assertThat(intakeCompletedAt(tb)).as("B: its request's item arrived → exact evidence").isNotNull();
        assertThat(intakeCompletedAt(ta)).as("A: nothing of its request arrived").isNull();
        assertThat(legState(tb)).isEqualTo("returned");
        assertThat(legState(ta)).isEqualTo("returning");
    }

    // ── C: lifecycle ────────────────────────────────────────────────────────────

    @Test
    void c1_receivedWhenAllArrived_eachDispositionReleasesItsItem_refundPendingWhenAllFinal() {
        Order o = deliveredOrder(false, variantId, variantId);
        String p0 = o.pieces().get(0), p1 = o.pieces().get(1);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), p0, p1);
        UUID i0 = itemId(r, p0), i1 = itemId(r, p1);
        UUID s = sessions.createSession(null, ownerId);

        sessions.scan(s, "PC-" + p0, locationId, ownerId);
        assertThat(col(r, "status")).as("one item still awaited").isEqualTo("approved");
        sessions.disposition(s, p0, "restock", null, locationId, ownerId);
        assertThat(itemStatus(i0)).isEqualTo("done");
        assertThat(itemActive(i0)).as("released at once, while the other item is still awaited").isFalse();
        assertThat(itemStatus(i1)).isEqualTo("awaiting");
        assertThat(col(r, "status")).isEqualTo("approved");

        sessions.scan(s, "PC-" + p1, locationId, ownerId);
        assertThat(col(r, "status")).isEqualTo("received");
        assertThat(col(r, "received_at")).isNotNull();
        assertThat(col(r, "refund_pending_at")).isNull();

        sessions.disposition(s, p1, "damaged", "Torn seam", locationId, ownerId);
        assertThat(itemStatus(i1)).isEqualTo("done");
        assertThat(itemActive(i1)).isFalse();
        assertThat(col(r, "status")).isEqualTo("refund_pending");
        assertThat(col(r, "refund_pending_at")).isNotNull();
        assertThat(eventTypes(r)).containsSubsequence(
            "item_arrived", "item_done", "item_arrived", "received", "item_done", "refund_pending");
    }

    @Test
    void c2_mismatchIsNotFinal_blocksRefundPending() {
        Order o = deliveredOrder(false, variantId);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));
        UUID item = itemId(r, o.pieces().get(0));
        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + o.pieces().get(0), locationId, ownerId);
        assertThat(col(r, "status")).isEqualTo("received");

        sessions.disposition(s, o.pieces().get(0), "mismatch", null, locationId, ownerId);

        assertThat(col(r, "status")).isEqualTo("received");
        assertThat(itemStatus(item)).isEqualTo("arrived");
        assertThat(itemActive(item)).isTrue();
    }

    @Test
    void c3_restNotComing_withSomethingArrived_proceeds() {
        Order o = deliveredOrder(false, variantId, variantId);
        String p0 = o.pieces().get(0), p1 = o.pieces().get(1);
        UUID r = request(o, "pickup_booked", Instant.now().minus(1, ChronoUnit.HOURS), p0, p1);
        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, "PC-" + p0, locationId, ownerId);
        sessions.disposition(s, p0, "restock", null, locationId, ownerId);

        requests.restNotComing(r, ownerId);

        UUID i1 = itemId(r, p1);
        assertThat(itemStatus(i1)).isEqualTo("not_coming");
        assertThat(itemActive(i1)).isFalse();
        assertThat(col(r, "status")).isEqualTo("refund_pending");
        assertThat(col(r, "close_reason")).isNull();
        assertThat(eventTypes(r)).containsSubsequence("rest_not_coming", "received", "refund_pending");
        assertStatus(409, () -> requests.restNotComing(r, ownerId));
    }

    @Test
    void c4_restNotComing_withNothingArrived_closes() {
        Order o = deliveredOrder(false, variantId);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));

        requests.restNotComing(r, ownerId);

        assertThat(col(r, "status")).isEqualTo("closed");
        assertThat(col(r, "close_reason")).isEqualTo("rest_not_coming");
        assertThat(col(r, "closed_by")).isEqualTo(ownerId);
        assertThat(col(r, "closed_at")).isNotNull();
        UUID item = itemId(r, o.pieces().get(0));
        assertThat(itemStatus(item)).isEqualTo("not_coming");
        assertThat(itemActive(item)).isFalse();
        assertThat(eventTypes(r)).containsSubsequence("rest_not_coming", "closed");
    }

    @Test
    void c5_closeFromEachAllowedStatus_releasesItems_otherStatusesRefused() {
        for (String status : List.of("approved", "pickup_booked", "received", "refund_pending")) {
            Order o = deliveredOrder(true, variantId, variantId);
            UUID r = request(o, status, Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0), o.pieces().get(1));
            UUID arrived = itemId(r, o.pieces().get(0)), awaiting = itemId(r, o.pieces().get(1));
            jdbc.update("UPDATE return_request_items SET item_status = 'arrived', arrived_at = now() WHERE id = ?", arrived);

            requests.close(r, "no_refund", "Customer kept it", ownerId);

            assertThat(col(r, "status")).as(status).isEqualTo("closed");
            assertThat(col(r, "close_reason")).isEqualTo("no_refund");
            assertThat(col(r, "close_note")).isEqualTo("Customer kept it");
            assertThat(col(r, "closed_by")).isEqualTo(ownerId);
            assertThat(itemStatus(arrived)).isEqualTo("done");
            assertThat(itemStatus(awaiting)).isEqualTo("not_coming");
            assertThat(itemActive(arrived)).isFalse();
            assertThat(itemActive(awaiting)).isFalse();
            assertThat(eventTypes(r)).contains("closed");
        }
        Order o = deliveredOrder(true, variantId);
        UUID requested = request(o, "requested", Instant.now(), o.pieces().get(0));
        assertStatus(409, () -> requests.close(requested, "other", null, ownerId));
        UUID ok = request(deliveredOrder(true, variantId), "approved", Instant.now());
        assertStatus(400, () -> requests.close(ok, "rest_not_coming", null, ownerId));
        assertStatus(400, () -> requests.close(ok, "whatever", null, ownerId));
        assertStatus(400, () -> requests.close(ok, "other", "x".repeat(301), ownerId));
        requests.close(ok, "other", null, ownerId);
        assertStatus(409, () -> requests.close(ok, "other", null, ownerId));
    }

    // ── E: history for the transitions that existed before 4d-1 ────────────────

    @Test
    void e1_eventsForRequestedApprovedRejected_andTheBookingTransitions() {
        Order o = deliveredOrder(true, variantId);
        PortalService.SubmitResult res = portal.submit(SLUG, tokens.issue(tenantId, o.id()), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(variantId, 1, "wrong_size")), null, null)).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        TenantContext.set(tenantId);
        UUID r = jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
        requests.approve(r, ownerId);
        assertThat(eventTypes(r)).containsExactly("requested", "approved");
        assertThat(lastEvent(r, "approved").get("actor")).isEqualTo(ownerId);

        // Booking switched off → precondition failure → booking_failed.
        booking.book(r, tenantId);
        assertThat(eventTypes(r)).endsWith("booking_failed");

        // A claim stuck in 'pending' → the sweeper marks it ambiguous.
        jdbc.update("UPDATE return_requests SET booking_status = 'pending', booking_attempted_at = now() - interval '20 minutes' " +
                    "WHERE id = ?", r);
        booking.sweepTenant(tenantId);
        assertThat(eventTypes(r)).endsWith("booking_ambiguous");

        // The merchant confirms it was booked → pickup_booked.
        String tracking = bareTracking();
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "crp-" + tracking);
        raw.putObject("type").put("code", 25);
        raw.put("businessReference", o.number());
        raw.put("createdAt", Instant.now().toString());
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 10, "CUSTOMER RETURN PICKUP", 0, o.number(), null, raw));
        TenantContext.set(tenantId);
        booking.confirmBooked(r, tracking);
        assertThat(col(r, "status")).isEqualTo("pickup_booked");
        assertThat(eventTypes(r)).endsWith("pickup_booked");

        // Rejection: event + items released as not_coming.
        Order o2 = deliveredOrder(true, variantId);
        UUID r2 = request(o2, "requested", Instant.now(), o2.pieces().get(0));
        requests.reject(r2, "Outside policy", ownerId);
        assertThat(eventTypes(r2)).containsExactly("rejected");
        UUID item = itemId(r2, o2.pieces().get(0));
        assertThat(itemStatus(item)).isEqualTo("not_coming");
        assertThat(itemActive(item)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void e2_detailCarriesLifecycleFieldsAndHistory() {
        Order o = deliveredOrder(false, variantId);
        UUID r = request(o, "approved", Instant.now().minus(1, ChronoUnit.HOURS), o.pieces().get(0));
        requests.restNotComing(r, ownerId);

        Map<String, Object> d = requests.detail(r);

        assertThat(d.get("status")).isEqualTo("closed");
        assertThat(d.get("closeReason")).isEqualTo("rest_not_coming");
        assertThat(d.get("closedByName")).isEqualTo("Owner");
        List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");
        assertThat(items.get(0).get("itemStatus")).isEqualTo("not_coming");
        List<Map<String, Object>> events = (List<Map<String, Object>>) d.get("events");
        assertThat(events).extracting(e -> e.get("type")).containsExactly("rest_not_coming", "closed");
        assertThat(events.get(0).get("actorName")).isEqualTo("Owner");
        assertThat(requests.list("closed", 0, 50).get("total")).isEqualTo(1);
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_linkLeg_crossTenant404_sameTenantControlSucceeds() {
        Order oa = deliveredOrder(true, variantId, variantId);
        UUID ra = request(oa, "approved", Instant.now().minus(2, ChronoUnit.HOURS), oa.pieces().get(0));
        request(oa, "approved", Instant.now().minus(1, ChronoUnit.HOURS), oa.pieces().get(1));
        UUID legA = legId(ingestCrp(oa, bareTracking(), Instant.now(), 41));
        TenantContext.clear();
        BRequest b = bRequest("approved");

        assertStatusAsA(404, () -> appUserRequests.linkLeg(b.request(), b.leg(), ownerId));
        assertStatusAsA(404, () -> appUserRequests.linkLeg(ra, b.leg(), ownerId));
        assertThat(jdbc.queryForObject("SELECT return_shipment_id FROM return_requests WHERE id = ?", UUID.class, b.request()))
            .isNull();
        asA(() -> { appUserRequests.linkLeg(ra, legA, ownerId); return null; });
        assertThat(col(ra, "return_shipment_id")).isEqualTo(legA);
    }

    @Test
    void x2_restNotComing_crossTenant404_sameTenantControlSucceeds() {
        Order oa = deliveredOrder(false, variantId);
        UUID ra = request(oa, "approved", Instant.now().minus(1, ChronoUnit.HOURS), oa.pieces().get(0));
        TenantContext.clear();
        BRequest b = bRequest("approved");

        assertStatusAsA(404, () -> appUserRequests.restNotComing(b.request(), ownerId));
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, b.request()))
            .isEqualTo("approved");
        asA(() -> { appUserRequests.restNotComing(ra, ownerId); return null; });
        assertThat(col(ra, "status")).isEqualTo("closed");
    }

    @Test
    void x3_close_crossTenant404_sameTenantControlSucceeds() {
        Order oa = deliveredOrder(true, variantId);
        UUID ra = request(oa, "received", Instant.now().minus(1, ChronoUnit.HOURS), oa.pieces().get(0));
        TenantContext.clear();
        BRequest b = bRequest("received");

        assertStatusAsA(404, () -> appUserRequests.close(b.request(), "no_refund", null, ownerId));
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, b.request()))
            .isEqualTo("received");
        asA(() -> { appUserRequests.close(ra, "no_refund", null, ownerId); return null; });
        assertThat(col(ra, "status")).isEqualTo("closed");
    }

    @Test
    void x4_eventsTable_rlsIsolated_appendOnly_noContextSeesNothing() {
        Order oa = deliveredOrder(true, variantId);
        UUID ra = request(oa, "approved", Instant.now(), oa.pieces().get(0));
        TenantContext.clear();
        BRequest b = bRequest("approved");
        jdbc.update("INSERT INTO return_request_events (tenant_id, request_id, event_type) VALUES (?, ?, 'approved')",
            tenantId, ra);
        jdbc.update("INSERT INTO return_request_events (tenant_id, request_id, event_type) VALUES (?, ?, 'approved')",
            tenantB, b.request());

        List<UUID> visible = asA(() -> appUserJdbc.queryForList("SELECT request_id FROM return_request_events", UUID.class));
        assertThat(visible).as("positive control + isolation").containsExactly(ra);
        assertStatusAsA(404, () -> appUserRequests.detail(b.request()));
        assertThat(asA(() -> appUserRequests.detail(ra)).get("events")).asList().hasSize(1);
        assertThatThrownBy(() -> asA(() -> appUserJdbc.update(
            "INSERT INTO return_request_events (tenant_id, request_id, event_type) VALUES (?, ?, 'forged')", tenantB, b.request())))
            .as("WITH CHECK refuses another tenant's row").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asA(() -> appUserJdbc.update("UPDATE return_request_events SET event_type = 'x'")))
            .as("append-only for app_user").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asA(() -> appUserJdbc.update("DELETE FROM return_request_events")))
            .isInstanceOf(DataAccessException.class);
        Integer none = appUserTx.execute(s -> appUserJdbc.queryForObject("SELECT COUNT(*) FROM return_request_events", Integer.class));
        assertThat(none).as("app_user without a tenant context sees nothing").isZero();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record Order(UUID id, String number, UUID forwardLeg, List<String> pieces) {}
    record BRequest(UUID request, UUID leg) {}

    private static String bareTracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** Delivered order with one delivered piece per variant given; in or out of the 30-day window. */
    private Order deliveredOrder(boolean inWindow, UUID... variants) {
        String number = "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        int daysAgo = inWindow ? 1 : 60;
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Customer', '01000000000', 'bosta') " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number);
        UUID forward = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - (interval '1 day' * ?))",
            forward, tenantId, orderId, bareTracking(), daysAgo);
        List<String> pieces = new ArrayList<>();
        for (UUID variant : variants) {
            String id = UlidGenerator.generate();
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'delivered'::piece_status, ?, now() - (interval '1 day' * ?))",
                id, tenantId, variant, "PC-" + id, id, orderId, daysAgo);
            UUID itemId = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                itemId, tenantId, orderId, variant);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                tenantId, itemId, id);
            pieces.add(id);
        }
        return new Order(orderId, number, forward, pieces);
    }

    /** A return request in {@code status} binding the given pieces (one awaiting item each). */
    private UUID request(Order o, String status, Instant createdAt, String... pieces) {
        return insertRequest(tenantId, o.id(), status, createdAt, pieces);
    }

    private UUID insertRequest(UUID tenant, UUID orderId, String status, Instant createdAt, String... pieces) {
        StringBuilder ref = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) ref.append(REF_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(REF_ALPHABET.length())));
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, created_at, decided_at) " +
            "VALUES (?, ?, ?::return_request_status, ?, ?, CASE WHEN ? <> 'requested' THEN now() END) RETURNING id",
            UUID.class, tenant, orderId, status, ref.toString(), java.sql.Timestamp.from(createdAt), status);
        for (String p : pieces) {
            UUID variant = jdbc.queryForObject("SELECT variant_id FROM pieces WHERE id = ?", UUID.class, p);
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                        "VALUES (?, ?, ?, ?, 'wrong_size')", tenant, id, p, variant);
        }
        return id;
    }

    /** Tenant B: an order with one delivered piece, a request in {@code status}, and a return leg on that order. */
    private BRequest bRequest(String status) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#9001', 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenantB, storeB, "gid://shopify/Order/" + UUID.randomUUID());
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    piece, tenantB, variantB, "PC-" + piece, piece, order);
        UUID leg = UUID.randomUUID();
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, ?, 'bosta', ?, 'returning'::shipment_internal_state, 'return')", leg, tenantB, order, bareTracking());
        return new BRequest(insertRequest(tenantB, order, status, Instant.now(), piece), leg);
    }

    /** A CRP (type 25, businessReference = order number, Bosta createdAt) through the real ingest pipeline. */
    private String ingestCrp(Order o, String tracking, Instant bostaCreatedAt, int... states) {
        int minute = 10;
        for (int state : states) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("_id", "crp-" + tracking);
            raw.put("trackingNumber", tracking);
            raw.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
            raw.putObject("state").put("code", state);
            raw.put("businessReference", o.number());
            raw.put("createdAt", bostaCreatedAt.toString());
            raw.put("updatedAt", "2026-09-26T10:" + (minute++) + ":00.000Z");
            BostaDelivery d = new BostaDelivery(tracking, state, "CUSTOMER RETURN PICKUP", 0, o.number(), null, raw);
            when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(d);
            boolean enqueued = TenantContext.runAs(tenantId,
                () -> ingestionHelper.ingestDelivery(tenantId, RAW_API_KEY, tracking, "bosta_poll"));
            assertThat(enqueued).as("ingest must enqueue CRP state %d", state).isTrue();
            Long eventId = jdbc.queryForObject(
                "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
                "AND (payload->>'state')::int = ? ORDER BY received_at DESC, id DESC LIMIT 1",
                Long.class, tenantId, tracking, state);
            webhookJob.process(eventId, tenantId);
            TenantContext.set(tenantId);
        }
        return tracking;
    }

    private UUID legId(String tracking) {
        return jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ? AND shipment_leg = 'return'",
            UUID.class, tracking);
    }

    private String legState(String tracking) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, tracking);
    }

    private Object intakeCompletedAt(String tracking) {
        return jdbc.queryForObject("SELECT return_intake_completed_at FROM shipments WHERE tracking_number = ?",
            Object.class, tracking);
    }

    private Object col(UUID requestId, String column) {
        String expr = "status".equals(column) ? "status::text" : column;
        return jdbc.queryForObject("SELECT " + expr + " FROM return_requests WHERE id = ?", Object.class, requestId);
    }

    private String reference(UUID requestId) {
        return jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, requestId);
    }

    private UUID itemId(UUID requestId, String originalPiece) {
        return jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ? AND piece_id = ?",
            UUID.class, requestId, originalPiece);
    }

    private String itemStatus(UUID itemId) {
        return jdbc.queryForObject("SELECT item_status FROM return_request_items WHERE id = ?", String.class, itemId);
    }

    private boolean itemActive(UUID itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM return_request_items WHERE id = ?", Boolean.class, itemId));
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> returnReceivedMeta(String pieceId) {
        String json = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events WHERE piece_id = ? AND event_type = 'return_received' " +
            "ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class, pieceId);
        try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private List<String> eventTypes(UUID requestId) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id",
            String.class, requestId);
    }

    /** Events are ordered by occurred_at (now() is per transaction — equal within one; id breaks ties arbitrarily). */
    private Map<String, Object> lastEvent(UUID requestId, String type) {
        return jdbc.queryForMap(
            "SELECT actor, metadata::text AS meta FROM return_request_events WHERE request_id = ? AND event_type = ? " +
            "ORDER BY occurred_at DESC LIMIT 1", requestId, type);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ambiguous() {
        return (List<Map<String, Object>>) exceptions.listExceptions("return_link_ambiguous", null, 0, 100).get("items");
    }

    private void assertStatus(int status, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    private <T> T asA(Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> appUserTx.execute(s -> body.get()));
    }

    private void assertStatusAsA(int status, Runnable body) {
        assertStatus(status, () -> asA(() -> { body.run(); return null; }));
    }
}
