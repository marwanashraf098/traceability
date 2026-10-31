package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.UnpackOrderException;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unpack-order S3 (approved 2026-10-31) — POST /api/v1/fulfill/{orderId}/unpack-order.
 *   u1  queue mode: packed → unpacked (pieces available, 'unpacked' events with the reason, allocations released,
 *       order 'new', claim cleared, edit conflict cleared, audit row) → back in the queue, off the packed list.
 *   u2  waybill mode, the real case: packed (awaiting_pickup), Shopify removes a line (edit conflict, qty 0) →
 *       unpack → the SAME waybill re-opens the order in a pack session (today: ALREADY_PACKED), the removed piece is
 *       refused (REMOVED_IN_SHOPIFY), the kept one completes it → awaiting_pickup; the print-batch stamp stays and the
 *       batch item reads waiting while unpacked; a later edit raises the alert again.
 *   u3  self-pickup: self_pickup_pending → 'new' with is_self_pickup kept — the self-pickup queue, never the
 *       waybill queue (the resolver answers SELF_PICKUP).
 *   u4  guards, each 409 with EN + AR and nothing changed: not packed, cancel requested, Lookup exchange order,
 *       left custody (closed pickup), waybill ended, waybill deleted in Bosta, open pickup session (names it),
 *       held by another packer.
 *   u5  a ledger StateConflict on the 2nd piece rolls the whole order back.
 *   u6  after unpack, a pickup scan of its waybill is refused NOT_PACKED and writes nothing.
 *   u7  reasons: default customer_edited_order; "other" needs a note (≤ 200); piece history shows the reason.
 *   u8  permissions: worker 403; another tenant 404 (same-tenant positive control).
 *   x1  app_user under RLS: own order unpacks (positive control), another tenant's order is 404.
 * No Shopify and no Bosta call in any of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnpackOrderTest {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired FulfillService   fulfill;
    @Autowired LookupService    lookup;
    @Autowired ObjectMapper     mapper;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  ShopifyGateway   shopifyGateway;
    @MockBean  JobScheduler     jobScheduler;

    final class Shop {
        final PackFixtures f;
        final UUID manager, worker;
        final String managerToken, workerToken;
        final UUID variantA, variantB;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            manager = f.user("Mona", "manager");
            worker = f.user("Ahmed", "worker");
            managerToken = jwt.issueAccessToken(manager, f.tenant, "manager");
            workerToken = jwt.issueAccessToken(worker, f.tenant, "worker");
            variantA = f.variant("Linen shirt", "LS-" + name, null);
            variantB = f.variant("Cotton cap", "CC-" + name, null);
        }
        /** One unit of A and (two lines) one unit of B, a 'created' forward leg, one piece per unit. */
        Order order(String number, boolean twoLines) {
            UUID id = f.order(number, 1);
            f.item(id, variantA, 1);
            if (twoLines) f.item(id, variantB, 1);
            String tn = f.forward(id);
            String a = f.piece(variantA, worker);
            String b = twoLines ? f.piece(variantB, worker) : null;
            return new Order(id, tn, a, b);
        }
        void scan(Order o, String code) {
            Map<?, ?> r = call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/scan", Map.of("barcode", code)).getBody();
            assertThat(r.get("success")).as("scan " + code + " → " + r).isEqualTo(true);
        }
        /** Queue mode: scan every piece, Complete → order packed. */
        void pack(Order o) {
            scan(o, o.pieceA());
            if (o.pieceB() != null) scan(o, o.pieceB());
            assertThat(call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/complete", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        }
        void link(Order o) {
            assertThat(call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/link",
                Map.of("trackingNumber", o.tracking())).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        UUID openPickup() {
            Map<?, ?> r = call(workerToken, HttpMethod.POST, "/api/v1/pickup-sessions",
                Map.of("scheduledDate", LocalDate.now().toString(), "scheduledTimeSlot", "10-12")).getBody();
            return UUID.fromString((String) r.get("sessionId"));
        }
        Map<?, ?> pickupScan(UUID pickup, Order o) {
            return call(workerToken, HttpMethod.POST, "/api/v1/pickup-sessions/" + pickup + "/scans",
                Map.of("trackingNumber", o.tracking())).getBody();
        }
        ResponseEntity<Map> unpack(String token, Order o, Map<String, Object> body) {
            return call(token, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/unpack-order", body);
        }
    }

    record Order(UUID id, String tracking, String pieceA, String pieceB) {}

    @BeforeEach
    void resetMocks() { reset(bostaGateway, shopifyGateway); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── u1 ────────────────────────────────────────────────────────────────────

    @Test
    void u1_queueMode_unpackPutsTheOrderBackInTheQueue() {
        Shop s = new Shop("U1");
        Order o = s.order("#U1", true);
        s.pack(o);
        assertThat(status(o.id())).isEqualTo("packed");
        jdbc.update("UPDATE orders SET shopify_edit_conflict_at = now(), shopify_edit_conflict_diff = '{\"removed\":[\"x\"]}', " +
                    "locked_by = ?, locked_at = now() - interval '30 minutes' WHERE id = ?", s.worker, o.id());

        ResponseEntity<Map> r = s.unpack(s.managerToken, o, Map.of());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("pieces")).isEqualTo(2);
        assertThat(r.getBody().get("previousStatus")).isEqualTo("packed");
        assertThat(r.getBody().get("status")).isEqualTo("new");

        assertThat(status(o.id())).isEqualTo("new");
        assertThat(pieceStatus(s, o.pieceA())).isEqualTo("available");
        assertThat(pieceStatus(s, o.pieceB())).isEqualTo("available");
        assertThat(count("SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                         "WHERE oi.order_id = ? AND a.status IN ('active','packed')", o.id())).isZero();
        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT from_status::text AS f, to_status::text AS t, actor_user_id, metadata ->> 'reason' AS reason, " +
            "       metadata ->> 'scope' AS scope FROM piece_events WHERE order_id = ? AND event_type = 'unpacked'", o.id());
        assertThat(events).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("f")).isEqualTo("packed");
            assertThat(e.get("t")).isEqualTo("available");
            assertThat(e.get("actor_user_id")).isEqualTo(s.manager);
            assertThat(e.get("reason")).isEqualTo("customer_edited_order");
            assertThat(e.get("scope")).isEqualTo("order");
        });
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT locked_by, shopify_edit_conflict_at, shopify_edit_conflict_diff FROM orders WHERE id = ?", o.id());
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("shopify_edit_conflict_at")).as("edit conflict cleared on unpack").isNull();
        assertThat(row.get("shopify_edit_conflict_diff")).isNull();
        Map<String, Object> audit = jdbc.queryForMap(
            "SELECT actor_user_id, metadata ->> 'reason' AS reason, metadata ->> 'previousStatus' AS prev, " +
            "       metadata ->> 'shopifyEditDiff' AS diff, jsonb_array_length(metadata -> 'pieceIds') AS n " +
            "FROM audit_log WHERE action = 'order_unpacked' AND target_id = ?", o.id().toString());
        assertThat(audit.get("actor_user_id")).isEqualTo(s.manager);
        assertThat(audit.get("reason")).isEqualTo("customer_edited_order");
        assertThat(audit.get("prev")).isEqualTo("packed");
        assertThat((String) audit.get("diff")).contains("removed");
        assertThat(audit.get("n")).isEqualTo(2);

        assertThat(queueIds(s)).contains(o.id().toString());
        assertThat(packedList(s)).doesNotContain(o.id().toString());

        // Repack: the normal scan / Complete — a worker may.
        s.pack(o);
        assertThat(status(o.id())).isEqualTo("packed");
        verifyNoInteractions(bostaGateway, shopifyGateway);
    }

    // ── u2 ────────────────────────────────────────────────────────────────────

    @Test
    void u2_waybillMode_customerRemovedAnItem_unpackThenTheSameWaybillRepacksTheCurrentLines() {
        Shop s = new Shop("U2");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
        Order o = s.order("#U2", true);
        UUID batch = jdbc.queryForObject(
            "INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, waybill_count, order_guaranteed) " +
            "VALUES (?, 1, ?, 'A6', 'oldest', 'new', 1, true) RETURNING id", UUID.class, s.f.tenant, s.worker);
        jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
                    "SELECT ?, tenant_id, order_id, id, tracking_number, 1 FROM shipments WHERE tracking_number = ?",
                    batch, o.tracking());

        UUID session = startPack(s);
        assertThat(packPost(s, "/" + session + "/waybill", Map.of("code", o.tracking())).get("result")).isEqualTo("opened");
        packPost(s, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieceA()));
        assertThat(packPost(s, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieceB())).get("status"))
            .isEqualTo("completed");
        assertThat(status(o.id())).isEqualTo("awaiting_pickup");
        assertThat(pieceStatus(s, o.pieceB())).isEqualTo("awaiting_pickup");
        assertThat(batchItemState(s)).isEqualTo("packed");

        // The customer removes the cap in Shopify (packed → the edit only raises the alert; the line goes to 0).
        TenantContext.runAs(s.f.tenant, () -> {
            fulfill.handleShopifyLineItemEdit(o.id(), "awaiting_pickup", "{\"removed\":[\"cap\"]}", List.of(), Map.of());
            return null;
        });
        jdbc.update("UPDATE order_items SET quantity = 0 WHERE order_id = ? AND variant_id = ?", o.id(), s.variantB);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NOT NULL", o.id())).isOne();

        Map<String, Object> rejected = packPost(s, "/" + session + "/waybill", Map.of("code", o.tracking()));
        assertThat(rejected.get("code")).as("before unpack").isEqualTo("ALREADY_PACKED");

        assertThat(s.unpack(s.managerToken, o, Map.of("reason", "customer_edited_order")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pieceStatus(s, o.pieceA())).isEqualTo("available");
        assertThat(pieceStatus(s, o.pieceB())).isEqualTo("available");
        assertThat(count("SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NULL", o.id())).isOne();
        assertThat(count("SELECT COUNT(*) FROM pack_print_batch_items WHERE batch_id = ?", batch)).as("print stamp stays").isOne();
        assertThat(batchItemState(s)).as("the batch item waits again").isEqualTo("waiting");

        Map<String, Object> reopened = packPost(s, "/" + session + "/waybill", Map.of("code", o.tracking()));
        assertThat(reopened.get("result")).as("the same waybill opens it again: " + reopened).isEqualTo("opened");
        Map<String, Object> removed = packPost(s, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieceB()));
        assertThat(removed.get("status")).isEqualTo("rejected");
        assertThat(((Map<?, ?>) removed.get("scan")).get("code")).isEqualTo("REMOVED_IN_SHOPIFY");
        Map<String, Object> done = packPost(s, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieceA()));
        assertThat(done.get("status")).isEqualTo("completed");
        assertThat(status(o.id())).isEqualTo("awaiting_pickup");
        assertThat(pieceStatus(s, o.pieceA())).isEqualTo("awaiting_pickup");
        assertThat(pieceStatus(s, o.pieceB())).as("the removed piece stays in stock").isEqualTo("available");
        assertThat(batchItemState(s)).isEqualTo("packed");

        // A later edit of the repacked order raises the alert again (the key / resolve logic are unchanged).
        TenantContext.runAs(s.f.tenant, () -> {
            fulfill.handleShopifyLineItemEdit(o.id(), "awaiting_pickup", "{\"changed\":[\"shirt\"]}", List.of(), Map.of());
            return null;
        });
        assertThat(count("SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NOT NULL", o.id())).isOne();
        verifyNoInteractions(bostaGateway, shopifyGateway);
    }

    // ── u3 ────────────────────────────────────────────────────────────────────

    @Test
    void u3_selfPickup_goesBackToTheSelfPickupQueue_notTheWaybillQueue() {
        Shop s = new Shop("U3");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
        Order o = s.order("#U3", false);
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", o.id());
        s.pack(o);
        assertThat(status(o.id())).isEqualTo("self_pickup_pending");

        assertThat(s.unpack(s.managerToken, o, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = jdbc.queryForMap("SELECT status::text AS status, is_self_pickup FROM orders WHERE id = ?", o.id());
        assertThat(row.get("status")).isEqualTo("new");
        assertThat(row.get("is_self_pickup")).as("is_self_pickup carries it back to the self-pickup queue").isEqualTo(true);
        Map<String, Object> queueRow = queue(s).stream().filter(q -> o.id().toString().equals(q.get("id"))).findFirst().orElseThrow();
        assertThat(queueRow.get("is_self_pickup")).isEqualTo(true);

        UUID session = startPack(s);
        Map<String, Object> scanned = packPost(s, "/" + session + "/waybill", Map.of("code", o.tracking()));
        assertThat(scanned.get("subReason")).as("not the waybill queue: " + scanned).isEqualTo("SELF_PICKUP");

        s.pack(o);
        assertThat(status(o.id())).isEqualTo("self_pickup_pending");
    }

    // ── u4 guards ─────────────────────────────────────────────────────────────

    @Test
    void u4_guards_each409_nothingChanged() {
        Shop s = new Shop("U4");

        Order notPacked = s.order("#G-NEW", false);
        refused(s, notPacked, "NOT_PACKED");

        Order cancel = s.order("#G-CANCEL", false);
        s.pack(cancel);
        jdbc.update("UPDATE orders SET cancel_requested_at = now() WHERE id = ?", cancel.id());
        refused(s, cancel, "CANCEL_REQUESTED");

        Order lookupEx = s.order("#G-LOOKUP", false);
        s.pack(lookupEx);
        UUID original = s.f.order("#G-ORIG", 3);
        jdbc.update("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, origin, replacement_order_id) " +
                    "VALUES (?, ?, 'exchange', 'approved', 'RR-LKUPXZ', 'lookup', ?)", s.f.tenant, original, lookupEx.id());
        refused(s, lookupEx, "LOOKUP_EXCHANGE");

        Order shipped = s.order("#G-SHIPPED", false);
        s.pack(shipped);
        s.link(shipped);
        UUID closed = s.openPickup();
        assertThat(s.pickupScan(closed, shipped).get("outcome")).isEqualTo("ACCEPTED");
        assertThat(call(s.workerToken, HttpMethod.POST, "/api/v1/pickup-sessions/" + closed + "/close", Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        refused(s, shipped, "LEFT_CUSTODY");

        Order ended = s.order("#G-ENDED", false);
        s.pack(ended);
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE tracking_number = ?", ended.tracking());
        refused(s, ended, "WAYBILL_ENDED");

        Order deleted = s.order("#G-DELETED", false);
        s.pack(deleted);
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' WHERE tracking_number = ?", deleted.tracking());
        refused(s, deleted, "WAYBILL_ENDED");

        Order inPickup = s.order("#G-PICKUP", false);
        s.pack(inPickup);
        s.link(inPickup);
        UUID open = s.openPickup();
        assertThat(s.pickupScan(open, inPickup).get("outcome")).isEqualTo("ACCEPTED");
        Map<String, Object> body = refused(s, inPickup, "IN_PICKUP_SESSION");
        assertThat((Map<String, Object>) body.get("details")).containsEntry("pickupSessionId", open.toString())
            .containsEntry("pickupDate", LocalDate.now().toString()).containsEntry("pickupTimeSlot", "10-12");
        assertThat((String) body.get("message_en")).contains("Remove its waybill from that pickup session first");
        // Removing the scan through the existing endpoint lets the unpack through — no auto-removal.
        UUID shipment = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, inPickup.tracking());
        call(s.workerToken, HttpMethod.DELETE, "/api/v1/pickup-sessions/" + open + "/scans/" + shipment, null);
        assertThat(s.unpack(s.managerToken, inPickup, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);

        Order held = s.order("#G-HELD", false);
        s.pack(held);
        s.f.claim(held.id(), s.worker, 1);
        Map<String, Object> heldBody = refused(s, held, "HELD_BY_PACKER");
        assertThat((String) heldBody.get("message_en")).startsWith("Ahmed has order #G-HELD open");

        verifyNoInteractions(bostaGateway, shopifyGateway);
    }

    // ── u5 ────────────────────────────────────────────────────────────────────

    @Test
    void u5_aStateConflictOnTheSecondPiece_rollsTheWholeOrderBack() {
        Shop s = new Shop("U5");
        Order o = s.order("#U5", true);
        s.pack(o);
        // The second piece is not where its packed allocation says (a concurrent writer moved it).
        jdbc.update("UPDATE pieces SET status = 'damaged' WHERE id = ?", s.f.pieceId(o.pieceB()));
        Snapshot before = snapshot(o.id());

        ResponseEntity<Map> r = s.unpack(s.managerToken, o, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("STATE_CONFLICT");
        assertThat(snapshot(o.id())).as("all or nothing").isEqualTo(before);
        assertThat(pieceStatus(s, o.pieceA())).isEqualTo("packed");
    }

    // ── u6 ────────────────────────────────────────────────────────────────────

    @Test
    void u6_afterUnpack_aPickupScanOfItsWaybillIsRefused_andWritesNothing() {
        Shop s = new Shop("U6");
        Order o = s.order("#U6", false);
        s.pack(o);
        s.link(o);
        assertThat(s.unpack(s.managerToken, o, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID pickup = s.openPickup();
        assertThat(s.pickupScan(pickup, o).get("outcome")).isEqualTo("NOT_PACKED");
        assertThat(count("SELECT COUNT(*) FROM pickup_shipments WHERE pickup_id = ?", pickup)).isZero();
    }

    // ── u7 ────────────────────────────────────────────────────────────────────

    @Test
    void u7_reasons_otherNeedsANote_andPieceHistoryShowsTheReason() {
        Shop s = new Shop("U7");
        Order o = s.order("#U7", false);
        s.pack(o);
        Snapshot before = snapshot(o.id());

        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("reason", "other"), Map.of("reason", "other", "note", "x".repeat(201)), Map.of("reason", "nope"))) {
            ResponseEntity<Map> r = s.unpack(s.managerToken, o, bad);
            assertThat(r.getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).isEqualTo("BAD_REASON");
            assertThat(r.getBody().get("message_ar")).isNotNull();
        }
        assertThat(snapshot(o.id())).isEqualTo(before);

        assertThat(s.unpack(s.managerToken, o, Map.of("reason", "other", "note", "  Wrong size sent  ")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        Map<String, Object> history = TenantContext.runAs(s.f.tenant, () -> lookup.lookupPiece(o.pieceA(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = ((List<Map<String, Object>>) history.get("timeline")).get(0);
        assertThat(latest.get("phraseKey")).isEqualTo("unpacked_order");
        assertThat(latest.get("unpackReason")).isEqualTo("other");
        assertThat(latest.get("unpackNote")).isEqualTo("Wrong size sent");
        assertThat(latest.get("orderNumber")).isEqualTo("#U7");

        s.pack(o);
        assertThat(s.unpack(s.managerToken, o, Map.of("reason", "packed_wrong_item")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> again = TenantContext.runAs(s.f.tenant, () -> lookup.lookupPiece(o.pieceA(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> top = ((List<Map<String, Object>>) again.get("timeline")).get(0);
        assertThat(top.get("unpackReason")).isEqualTo("packed_wrong_item");
        assertThat(top.get("unpackNote")).isNull();
    }

    // ── u8 ────────────────────────────────────────────────────────────────────

    @Test
    void u8_workerForbidden_otherTenantNotFound_sameTenantPositiveControl() {
        Shop mine = new Shop("U8a");
        Shop theirs = new Shop("U8b");
        Order o = mine.order("#U8", false);
        mine.pack(o);
        Snapshot before = snapshot(o.id());

        assertThat(mine.unpack(mine.workerToken, o, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mine.unpack(theirs.managerToken, o, Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(snapshot(o.id())).isEqualTo(before);
        assertThat(mine.unpack(mine.managerToken, o, Map.of()).getStatusCode()).as("same-tenant positive control")
            .isEqualTo(HttpStatus.OK);
    }

    // ── x1 ────────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_underRls_ownOrderUnpacks_otherTenantsIsNotFound() {
        Shop mine = new Shop("X1a");
        Shop theirs = new Shop("X1b");
        Order m = mine.order("#XM", false);
        mine.pack(m);
        Order t = theirs.order("#XT", false);
        theirs.pack(t);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        FulfillService appFulfill = new FulfillService(app, new InventoryLedger(app), new AuditService(app, mapper), 30);

        TenantContext.set(mine.f.tenant);
        try {
            assertThatThrownBy(() -> tx.execute(st -> appFulfill.unpackOrder(t.id(), mine.manager, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Order not found");
            FulfillService.UnpackOrderResult r = tx.execute(st -> appFulfill.unpackOrder(m.id(), mine.manager, null, null));
            assertThat(r.pieces()).as("same-tenant positive control (app_user grants)").isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        assertThat(status(m.id())).isEqualTo("new");
        assertThat(status(t.id())).isEqualTo("packed");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'order_unpacked' AND target_id = ?", m.id().toString())).isOne();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    record Snapshot(String status, List<String> pieces, List<String> allocations, int events, int audits) {}

    private Snapshot snapshot(UUID orderId) {
        return new Snapshot(status(orderId),
            jdbc.queryForList("SELECT p.status::text FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                "JOIN pieces p ON p.id = a.piece_id WHERE oi.order_id = ? ORDER BY p.id", String.class, orderId),
            jdbc.queryForList("SELECT a.status::text FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                "WHERE oi.order_id = ? ORDER BY a.id", String.class, orderId),
            count("SELECT COUNT(*) FROM piece_events WHERE order_id = ?", orderId),
            count("SELECT COUNT(*) FROM audit_log WHERE target_id = ?", orderId.toString()));
    }

    /** 409 (or the given code) with EN + AR text, and nothing changed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> refused(Shop s, Order o, String code) {
        Snapshot before = snapshot(o.id());
        ResponseEntity<Map> r = s.unpack(s.managerToken, o, Map.of());
        assertThat(r.getStatusCode()).as(code + " → " + r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo(code);
        assertThat((String) r.getBody().get("message_en")).isNotBlank();
        assertThat((String) r.getBody().get("message_ar")).isNotBlank();
        assertThat(snapshot(o.id())).as(code + ": nothing changed").isEqualTo(before);
        return r.getBody();
    }

    private UUID startPack(Shop s) {
        return UUID.fromString((String) packPost(s, "", null).get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> packPost(Shop s, String path, Object body) {
        ResponseEntity<Map> r = call(s.workerToken, HttpMethod.POST, "/api/v1/pack-sessions" + path, body);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queue(Shop s) {
        ResponseEntity<List> r = rest.exchange("http://localhost:" + port + "/api/v1/fulfill/queue", HttpMethod.GET,
            new HttpEntity<>(headers(s.workerToken)), List.class);
        return (List<Map<String, Object>>) r.getBody();
    }

    private List<Object> queueIds(Shop s) {
        return queue(s).stream().map(q -> q.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object> packedList(Shop s) {
        ResponseEntity<List> r = rest.exchange("http://localhost:" + port + "/api/v1/fulfill/packed", HttpMethod.GET,
            new HttpEntity<>(headers(s.workerToken)), List.class);
        return ((List<Map<String, Object>>) r.getBody()).stream().map(q -> q.get("orderId")).toList();
    }

    /** The one print-batch item's state on today's batch list (PackListRules.BATCH_ITEM_STATE_SQL). */
    @SuppressWarnings("unchecked")
    private String batchItemState(Shop s) {
        ResponseEntity<List> r = rest.exchange("http://localhost:" + port + "/api/v1/fulfill/print-batches/today", HttpMethod.GET,
            new HttpEntity<>(headers(s.workerToken)), List.class);
        Map<String, Object> b = ((List<Map<String, Object>>) r.getBody()).get(0);
        for (String k : List.of("packed", "waiting", "setAside", "cancelled")) {
            if (((Number) b.get(k)).intValue() == 1) return k;
        }
        return "none";
    }

    private ResponseEntity<Map> call(String token, HttpMethod method, String path, Object body) {
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private String pieceStatus(Shop s, String shortCode) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(shortCode));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
