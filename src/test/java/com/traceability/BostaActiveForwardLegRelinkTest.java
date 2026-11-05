package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-05 — BROEK BRK-47264-EG: the merchant deleted the order's Bosta delivery and booked a new one; the new
 * delivery stayed unlinked (NO_MATCH) behind the dead leg for hours. Now:
 *   ACTIVE_FORWARD_LEG reason → instant one-shot check of the old leg (ActiveForwardLegCheckJob) → "not found" +
 *   the re-booked delivery = confirmed on the first check (BostaDeletionService.confirmOnFirstCheck) → closed the
 *   deletion way → BostaRelink re-links the new delivery from its stored raw.
 *
 *   a1  the BRK-47264-EG shape end to end: live leg → new delivery, same reference → ACTIVE_FORWARD_LEG + check
 *       enqueued → check: 400 → old leg cancelled (no history row) → new waybill linked → packable in waybill mode on
 *       the new waybill, the old one refused "deleted in Bosta"
 *   a2  the check finds the old delivery still in Bosta → no close, no link, row 'old_delivery_live', MEDIUM
 *       unmatched_delivery alert says "two Bosta waybills"
 *   a3  old leg packed / left custody / in an open pickup session → fast path refused and recorded, leg stays
 *       'created' (suspected) — the normal two-step path decides
 *   a4  a relink after a normal confirmation refuses a packed order and records it
 *   a5  a reference matching two orders → never linked (refused:identity)
 *   a6  the BRK-45321-EG shape: cancelled order, dead leg, stranded delivery → never linked (refused:order_closed)
 *   a7  Bosta 48 / 49 / 104 on the old leg → the stranded delivery is re-linked
 *   a9  old delivery live → at most one lookup per order per hour (same delivery re-ingested, a second one)
 *   a10 reconcile attempts reset only when a Bosta FORWARD leg links (not a return leg, not with no link)
 *   a8  same-tenant controls: a normal new order links as before; another order's live leg + unlinked row are
 *       untouched; a deletion with no re-booked delivery keeps the normal two-step path (NO_NEW_DELIVERY)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaActiveForwardLegRelinkTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    @LocalServerPort int port;
    @Autowired TestRestTemplate          rest;
    @Autowired JdbcTemplate              jdbc;
    @Autowired JwtService                jwt;
    @Autowired EncryptionService         encryption;
    @Autowired ObjectMapper              mapper;
    @Autowired BostaWebhookJob           webhookJob;
    @Autowired BostaDeletionService      deletion;
    @Autowired ActiveForwardLegCheckJob  check;
    @Autowired BostaRelink               relink;
    @Autowired ExceptionService          exceptions;
    @MockBean  BostaGateway              gateway;
    @MockBean  BostaV2Client             bostaV2;
    @MockBean  JobScheduler              jobScheduler;

    static final String KEY = "bosta-key-relink";

    /** tracking number → Bosta's current raw; missing = "Delivery not found" (400). */
    private final Map<String, JsonNode> bosta = new ConcurrentHashMap<>();

    @BeforeEach
    void stub() {
        reset(gateway, bostaV2, jobScheduler);
        when(gateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            JsonNode raw = bosta.get((String) inv.getArgument(1));
            if (raw == null) throw new DeliveryNotFoundException((String) inv.getArgument(1));
            return BostaDelivery.fromRaw(inv.getArgument(1), raw);
        });
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
    }

    @AfterEach void clear() { TenantContext.clear(); }

    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, lookup_ok_at) " +
                        "VALUES (?, 'bosta', ?, ?, 'active', now())", f.tenant, encryption.encrypt(KEY), "wh-" + f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Denim jeans", "DJ-" + name, "https://cdn.shopify.com/s/files/jeans.jpg");
        }
        /** One unit, a piece on the shelf, a 'created' Bosta leg that Bosta knows (state 10). */
        Order order(String number) {
            UUID id = f.order(number, 0);
            f.item(id, variant, 1);
            String tn = f.forward(id);
            inBosta(tn, number, 10);
            String piece = f.piece(variant, packer);
            return new Order(id, number, tn, jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn), piece);
        }
    }

    record Order(UUID id, String number, String tracking, UUID shipment, String piece) {}

    // ── a1 ────────────────────────────────────────────────────────────────────

    @Test
    void a1_brk47264Shape_instantCheckConfirms_newWaybillLinked_packableOnNew_oldRefused() {
        Shop s = new Shop("AFL1");
        Order o = s.order("BRK-47264-EG");
        String newTn = PackFixtures.nextTracking();

        // The merchant deletes the old delivery in Bosta and books a new one for the same order.
        bosta.remove(o.tracking());
        inBosta(newTn, o.number(), 10);
        webhook(s, newTn, 10);

        Map<String, Object> u = unlinked(newTn);
        assertThat(u.get("match_reason")).isEqualTo(ShipmentLinkService.REASON_ACTIVE_FORWARD_LEG);
        verify(jobScheduler).enqueue(any(JobLambda.class));

        check.check(s.f.tenant, newTn, 1);                                 // what the enqueued job runs

        Map<String, Object> old = leg(o.tracking());
        assertThat(old.get("internal_state")).isEqualTo("cancelled");
        assertThat(old.get("provider_deleted_reason")).isEqualTo("deleted_in_bosta");
        assertThat(count("SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?", o.shipment())).isZero();
        Map<String, Object> fresh = leg(newTn);
        assertThat(fresh.get("order_id")).isEqualTo(o.id());
        assertThat(fresh.get("internal_state")).isEqualTo("created");
        u = unlinked(newTn);
        assertThat(u.get("resolved")).isEqualTo(true);
        assertThat(u.get("relink_outcome")).isEqualTo(BostaRelink.LINKED);

        // Waybill mode: the new waybill opens the order, the old one is refused.
        String token = jwt.issueAccessToken(s.packer, s.f.tenant, "worker");
        UUID session = UUID.fromString((String) pack(token, HttpMethod.POST, "", null).getBody().get("id"));
        Map<String, Object> refused = pack(token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", o.tracking())).getBody();
        assertThat(refused.get("result")).isEqualTo("rejected");
        assertThat(refused.get("subReason")).isEqualTo("DELETED_IN_BOSTA");
        Map<String, Object> opened = pack(token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", newTn)).getBody();
        assertThat(opened.get("result")).isEqualTo("opened");
        assertThat(pack(token, HttpMethod.POST, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.piece())).getBody().get("status"))
            .isEqualTo("completed");                                       // one unit: the scan completes the order
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(o.piece())))
            .isEqualTo("awaiting_pickup");
        assertThat(jdbc.queryForObject("SELECT pe.shipment_id FROM piece_events pe WHERE pe.piece_id = ? " +
            "ORDER BY pe.created_at DESC, pe.id DESC LIMIT 1", UUID.class, s.f.pieceId(o.piece())))
            .as("packed onto the NEW waybill").isEqualTo(jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, newTn));
    }

    // ── a2 ────────────────────────────────────────────────────────────────────

    @Test
    void a2_oldDeliveryStillInBosta_noClose_noLink_twoWaybillsAlert() {
        Shop s = new Shop("AFL2");
        Order o = s.order("BRK-47302-EG");
        String newTn = PackFixtures.nextTracking();
        inBosta(newTn, o.number(), 10);                                    // the old one is NOT deleted
        webhook(s, newTn, 10);

        check.check(s.f.tenant, newTn, 1);

        assertThat(leg(o.tracking()).get("internal_state")).isEqualTo("created");
        assertThat(leg(o.tracking()).get("provider_not_found_at")).isNull();
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", newTn)).isZero();
        Map<String, Object> u = unlinked(newTn);
        assertThat(u.get("resolved")).isEqualTo(false);
        assertThat(u.get("relink_outcome")).isEqualTo(ActiveForwardLegCheckJob.OLD_DELIVERY_LIVE);
        List<Map<String, Object>> alerts = alerts(s, "unmatched_delivery");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("severity")).isEqualTo("MEDIUM");
        assertThat((String) alerts.get(0).get("descriptionEn")).contains("two Bosta waybills").contains(newTn);
    }

    // ── a3 ────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"packed", "left_custody", "pickup_session"})
    void a3_oldLegPackedOrLeftCustodyOrInPickup_fastPathRefused_recorded(String shape) {
        Shop s = new Shop("AFL3-" + shape);
        Order o = s.order("BRK-473" + shape.length() + "-EG");
        String pieceId = s.f.pieceId(o.piece());
        switch (shape) {
            case "packed" -> allocate(o, pieceId, "packed");
            case "left_custody" -> allocate(o, pieceId, "with_courier");
            default -> {
                UUID pickup = jdbc.queryForObject("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'open') RETURNING id",
                    UUID.class, s.f.tenant);
                jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)", pickup, o.shipment(), s.f.tenant);
            }
        }
        String newTn = PackFixtures.nextTracking();
        bosta.remove(o.tracking());
        inBosta(newTn, o.number(), 10);
        webhook(s, newTn, 10);

        check.check(s.f.tenant, newTn, 1);

        Map<String, Object> old = leg(o.tracking());
        assertThat(old.get("internal_state")).isEqualTo("created");
        assertThat(old.get("provider_deleted_at")).isNull();
        assertThat(old.get("provider_not_found_at")).as("suspected — the normal path continues").isNotNull();
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", newTn)).isZero();
        String expected = switch (shape) {
            case "packed" -> "refused:packed";
            case "left_custody" -> "refused:frozen";
            default -> "refused:pickup_session";
        };
        assertThat(unlinked(newTn).get("relink_outcome")).isEqualTo(expected);
    }

    // ── a4 ────────────────────────────────────────────────────────────────────

    @Test
    void a4_relinkAfterNormalConfirmation_packedOrder_refusedAndRecorded() {
        Shop s = new Shop("AFL4");
        Order o = s.order("BRK-47304-EG");
        allocate(o, s.f.pieceId(o.piece()), "packed");
        String newTn = PackFixtures.nextTracking();
        inBosta(newTn, o.number(), 10);
        webhook(s, newTn, 10);
        // The normal two-step confirmation (two counted 400s ≥ 30 min apart): packed pieces aren't "out" → closed.
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() - INTERVAL '31 minutes', not_found_count = 2, " +
            "last_not_found_at = now() WHERE id = ?", o.shipment());
        BostaDeletionService.Confirmation c = TenantContext.runAs(s.f.tenant, () -> deletion.tryConfirm(s.f.tenant, o.shipment()));
        assertThat(c).isEqualTo(BostaDeletionService.Confirmation.CLOSED);

        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", newTn)).isZero();
        assertThat(unlinked(newTn).get("relink_outcome")).isEqualTo("refused:packed");
        assertThat(unlinked(newTn).get("resolved")).isEqualTo(false);
    }

    // ── a5 ────────────────────────────────────────────────────────────────────

    @Test
    void a5_referenceMatchingTwoOrders_neverLinked() {
        Shop s = new Shop("AFL5");
        Order o = s.order("BRK-47305-EG");
        UUID twin = s.f.order("BRK-47305-EG", 0);                         // same number, another order
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?", o.shipment());
        String newTn = PackFixtures.nextTracking();
        long id = storedUnlinked(s, newTn, o.number(), "AMBIGUOUS_MULTI");

        List<BostaRelink.Outcome> out = relink.relinkStrandedUnlinked(s.f.tenant, o.id(), "test");

        assertThat(out).extracting(BostaRelink.Outcome::outcome).containsExactly("refused:identity");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", newTn)).isZero();
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id = ?", twin)).isZero();
        assertThat(jdbc.queryForObject("SELECT resolved FROM unlinked_bosta_deliveries WHERE id = ?", Boolean.class, id)).isFalse();
    }

    // ── a6 ────────────────────────────────────────────────────────────────────

    @Test
    void a6_brk45321Shape_cancelledOrder_neverLinked() {
        Shop s = new Shop("AFL6");
        Order o = s.order("BRK-45321-EG");
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", o.id());
        String newTn = PackFixtures.nextTracking();
        long id = storedUnlinked(s, newTn, o.number(), ShipmentLinkService.REASON_ACTIVE_FORWARD_LEG);
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() - INTERVAL '31 minutes', not_found_count = 2, " +
            "last_not_found_at = now() WHERE id = ?", o.shipment());

        BostaDeletionService.Confirmation c = TenantContext.runAs(s.f.tenant, () -> deletion.tryConfirm(s.f.tenant, o.shipment()));

        assertThat(c).isEqualTo(BostaDeletionService.Confirmation.CLOSED);
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", newTn)).isZero();
        assertThat(jdbc.queryForObject("SELECT relink_outcome FROM unlinked_bosta_deliveries WHERE id = ?", String.class, id))
            .isEqualTo("refused:order_closed");
    }

    // ── a7 ────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {48, 49, 104})
    void a7_bostaEndedTheOldLeg_strandedDeliveryRelinked(int endedCode) {
        Shop s = new Shop("AFL7-" + endedCode);
        Order o = s.order("BRK-477" + endedCode + "-EG");
        String newTn = PackFixtures.nextTracking();
        inBosta(newTn, o.number(), 10);
        webhook(s, newTn, 10);
        assertThat(unlinked(newTn).get("match_reason")).isEqualTo(ShipmentLinkService.REASON_ACTIVE_FORWARD_LEG);

        inBosta(o.tracking(), o.number(), endedCode);                      // Bosta ends the old delivery
        webhook(s, o.tracking(), endedCode);

        assertThat(leg(o.tracking()).get("internal_state")).isIn("cancelled", "terminated");
        assertThat(leg(newTn).get("order_id")).isEqualTo(o.id());
        assertThat(unlinked(newTn).get("resolved")).isEqualTo(true);
        assertThat(unlinked(newTn).get("relink_outcome")).isEqualTo(BostaRelink.LINKED);
    }

    // ── a8 ────────────────────────────────────────────────────────────────────

    @Test
    void a8_sameTenantControls_normalOrdersUnchanged() {
        Shop s = new Shop("AFL8");
        // (1) A new order with no leg links on first sight, exactly as before — no check enqueued.
        UUID plain = s.f.order("BRK-48001-EG", 0);
        s.f.item(plain, s.variant, 1);
        String plainTn = PackFixtures.nextTracking();
        inBosta(plainTn, "BRK-48001-EG", 10);
        webhook(s, plainTn, 10);
        assertThat(leg(plainTn).get("order_id")).isEqualTo(plain);
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));

        // (2) Another order: live leg + an unrelated unlinked row — untouched by a relink of order (3).
        Order other = s.order("BRK-48002-EG");
        long otherRow = storedUnlinked(s, PackFixtures.nextTracking(), "BRK-48002-EG", ShipmentLinkService.REASON_ACTIVE_FORWARD_LEG);

        // (3) A deletion with no re-booked delivery: the fast path finds no new delivery, the leg stays suspected.
        Order lone = s.order("BRK-48003-EG");
        bosta.remove(lone.tracking());
        TenantContext.runAs(s.f.tenant, () -> deletion.observeNotFound(s.f.tenant, lone.shipment()));
        BostaDeletionService.Confirmation c = TenantContext.runAs(s.f.tenant,
            () -> deletion.confirmOnFirstCheck(s.f.tenant, lone.shipment(), "0000000001", new String[1]));
        assertThat(c).isEqualTo(BostaDeletionService.Confirmation.NO_NEW_DELIVERY);
        assertThat(leg(lone.tracking()).get("internal_state")).isEqualTo("created");
        assertThat(leg(lone.tracking()).get("provider_deleted_at")).isNull();
        relink.relinkStrandedUnlinked(s.f.tenant, lone.id(), "test");

        assertThat(leg(other.tracking()).get("internal_state")).isEqualTo("created");
        Map<String, Object> row = jdbc.queryForMap("SELECT resolved, relink_outcome FROM unlinked_bosta_deliveries WHERE id = ?", otherRow);
        assertThat(row.get("resolved")).isEqualTo(false);
        assertThat(row.get("relink_outcome")).isNull();
    }

    // ── a9 ────────────────────────────────────────────────────────────────────

    @Test
    void a9_oldDeliveryLive_atMostOneLookupPerOrderPerHour() {
        Shop s = new Shop("AFL9");
        Order o = s.order("BRK-47309-EG");
        String newTn = PackFixtures.nextTracking();
        inBosta(newTn, o.number(), 10);
        webhook(s, newTn, 10);
        check.check(s.f.tenant, newTn, 1);
        verify(gateway, times(1)).fetchDelivery(eq(KEY), eq(o.tracking()));
        assertThat(unlinked(newTn).get("relink_outcome")).isEqualTo(ActiveForwardLegCheckJob.OLD_DELIVERY_LIVE);

        // Re-ingest of the same delivery (a new Bosta state) → no new check enqueued.
        clearInvocations(jobScheduler);
        inBosta(newTn, o.number(), 11);
        webhook(s, newTn, 11);
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
        // A check that runs anyway, and a second re-booked delivery of the same order → no lookup either.
        check.check(s.f.tenant, newTn, 1);
        String newTn2 = PackFixtures.nextTracking();
        inBosta(newTn2, o.number(), 10);
        webhook(s, newTn2, 10);
        check.check(s.f.tenant, newTn2, 1);
        verify(gateway, times(1)).fetchDelivery(eq(KEY), eq(o.tracking()));

        // An hour later the order may be looked up again.
        jdbc.update("UPDATE unlinked_bosta_deliveries SET relink_at = now() - INTERVAL '61 minutes' WHERE tenant_id = ?", s.f.tenant);
        check.check(s.f.tenant, newTn2, 1);
        verify(gateway, times(2)).fetchDelivery(eq(KEY), eq(o.tracking()));
    }

    // ── a10 ───────────────────────────────────────────────────────────────────

    @Test
    void a10_reconcileAttemptsReset_onlyWhenABostaForwardLegLinks() {
        Shop s = new Shop("AFL10");
        // (1) An order that used its 10 reconcile attempts before Bosta's delivery showed up → linked → back to 0.
        UUID late = s.f.order("BRK-47310-EG", 0);
        s.f.item(late, s.variant, 1);
        maxAttempts(late);
        String tn = PackFixtures.nextTracking();
        inBosta(tn, "BRK-47310-EG", 10);
        webhook(s, tn, 10);
        assertThat(leg(tn).get("order_id")).isEqualTo(late);
        assertThat(attempts(late)).isZero();
        assertThat(jdbc.queryForObject("SELECT bosta_link_last_check FROM orders WHERE id = ?", Object.class, late)).isNull();

        // (2) A customer-return pickup (a Bosta RETURN leg) links to an order at the limit → its counter is kept.
        UUID returned = s.f.order("BRK-47311-EG", 0);
        s.f.item(returned, s.variant, 1);
        s.f.leg(returned, "forward", "delivered", null);
        maxAttempts(returned);
        String crp = PackFixtures.nextTracking();
        inBosta(crp, "BRK-47311-EG", 10);
        ((ObjectNode) bosta.get(crp)).putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        webhook(s, crp, 10);
        assertThat(jdbc.queryForObject("SELECT shipment_leg::text FROM shipments WHERE tracking_number = ?", String.class, crp))
            .isEqualTo("return");
        assertThat(attempts(returned)).isEqualTo(10);

        // (3) An order nothing links to keeps its counter (no backfill).
        UUID idle = s.f.order("BRK-47312-EG", 0);
        maxAttempts(idle);
        assertThat(attempts(idle)).isEqualTo(10);
    }

    private void maxAttempts(UUID order) {
        jdbc.update("UPDATE orders SET bosta_link_attempts = 10, bosta_link_last_check = now() WHERE id = ?", order);
    }

    private int attempts(UUID order) {
        return jdbc.queryForObject("SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, order);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void inBosta(String tn, String reference, int state) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", reference);
        raw.put("createdAt", Instant.now().toString());
        raw.put("updatedAt", Instant.now().toString());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", state).put("value", "x");
        bosta.put(tn, raw);
    }

    /** A real Bosta webhook for {@code tn}: a 'bosta' webhook_events row, processed (verify-by-fetch). */
    private void webhook(Shop s, String tn, int state) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state", state);
        payload.put("type", "SEND");
        payload.put("updatedAt", Instant.now().toString() + "-" + UUID.randomUUID());
        Long id = jdbc.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.f.tenant, payload.toString());
        webhookJob.process(id, s.f.tenant);
        assertThat(jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id))
            .as("the event is processed, not failed").isEqualTo("processed");
    }

    /** An unlinked SEND row with a stored raw (as recordUnlinked writes it). */
    private long storedUnlinked(Shop s, String tn, String reference, String reason) {
        inBosta(tn, reference, 10);
        return jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "bosta_order_type, raw, match_reason, matcher_version) VALUES (?, ?, ?, 10, 'SEND', ?::jsonb, ?, 'v-test') RETURNING id",
            Long.class, s.f.tenant, tn, reference, bosta.get(tn).toString(), reason);
    }

    /** A packed allocation of the order's line to the piece, the piece at {@code pieceStatus}. */
    private void allocate(Order o, String pieceId, String pieceStatus) {
        jdbc.update("UPDATE pieces SET status = ?::piece_status WHERE id = ?", pieceStatus, pieceId);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
            "SELECT ?, oi.id, oi.tenant_id, 'packed' FROM order_items oi WHERE oi.order_id = ?", pieceId, o.id());
    }

    private Map<String, Object> leg(String tn) {
        return jdbc.queryForMap("SELECT order_id, internal_state::text AS internal_state, provider_not_found_at, " +
            "provider_deleted_at, provider_deleted_reason FROM shipments WHERE tracking_number = ?", tn);
    }

    private Map<String, Object> unlinked(String tn) {
        return jdbc.queryForMap("SELECT match_reason, resolved, relink_outcome FROM unlinked_bosta_deliveries " +
            "WHERE tracking_number = ? ORDER BY id DESC LIMIT 1", tn);
    }

    private List<Map<String, Object>> alerts(Shop s, String type) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).toList();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> pack(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/pack-sessions" + path, method, new HttpEntity<>(body, h), Map.class);
    }
}
