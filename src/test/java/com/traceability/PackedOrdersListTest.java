package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.PackedOrderService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unpack-order S2 — GET /api/v1/fulfill/packed: packed and still in the warehouse, both packing modes.
 *   l1  queue-mode packed (status packed), linked (awaiting_pickup) and self-pickup pending are listed; a closed
 *       pickup (pieces with_courier, order status STILL awaiting_pickup) is not — status alone can't say it;
 *       cancel-requested and unpacked / never-packed orders are not.
 *   l2  an open pickup session holding the order is named on its row.
 *   l3  COD flag: Bosta leg + COD order whose amounts differ only (never a generic leg).
 *   l4  every role reads it (worker 200); another tenant's packed order is never listed.
 *   x1  app_user under RLS: own packed order listed (positive control), the other tenant's not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackedOrdersListTest {

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
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    final class Shop {
        final PackFixtures f;
        final UUID manager, worker;
        final String managerToken, workerToken;
        final UUID variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            manager = f.user("Mona", "manager");
            worker = f.user("Ahmed", "worker");
            managerToken = jwt.issueAccessToken(manager, f.tenant, "manager");
            workerToken = jwt.issueAccessToken(worker, f.tenant, "worker");
            variant = f.variant("Linen shirt", "LS-" + name, null);
        }
        Order order(String number, int qty) {
            UUID id = f.order(number, 1);
            f.item(id, variant, qty);
            String tn = f.forward(id);
            String[] pieces = new String[qty];
            for (int i = 0; i < qty; i++) pieces[i] = f.piece(variant, worker);
            return new Order(id, tn, pieces);
        }
        /** Queue mode: scan every piece, Complete → status packed, pieces packed. */
        void pack(Order o) {
            for (String p : o.pieces()) {
                Map<?, ?> r = call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/scan", Map.of("barcode", p)).getBody();
                assertThat(r.get("success")).as("scan " + p + " → " + r).isEqualTo(true);
            }
            assertThat(call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/complete", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        }
        /** The waybill link after pack → awaiting_pickup (pieces and order). */
        void link(Order o) {
            assertThat(call(workerToken, HttpMethod.POST, "/api/v1/fulfill/" + o.id() + "/link",
                Map.of("trackingNumber", o.tracking())).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        UUID openPickup() {
            Map<?, ?> r = call(workerToken, HttpMethod.POST, "/api/v1/pickup-sessions",
                Map.of("scheduledDate", LocalDate.now().toString(), "scheduledTimeSlot", "10-12")).getBody();
            return UUID.fromString((String) r.get("sessionId"));
        }
        void pickupScan(UUID pickup, Order o) {
            Map<?, ?> r = call(workerToken, HttpMethod.POST, "/api/v1/pickup-sessions/" + pickup + "/scans",
                Map.of("trackingNumber", o.tracking())).getBody();
            assertThat(r.get("outcome")).isEqualTo("ACCEPTED");
        }
        void closePickup(UUID pickup) {
            assertThat(call(workerToken, HttpMethod.POST, "/api/v1/pickup-sessions/" + pickup + "/close", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    record Order(UUID id, String tracking, String[] pieces) {}

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void l1_packedLinkedAndSelfPickupListed_closedPickupCancelRequestedAndUnpackedNot() {
        Shop s = new Shop("L1");
        Order packed = s.order("#P1", 2);
        s.pack(packed);
        Order linked = s.order("#P2", 1);
        s.pack(linked);
        s.link(linked);
        Order selfPickup = s.order("#P3", 1);
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", selfPickup.id());
        s.pack(selfPickup);
        Order shipped = s.order("#P4", 1);
        s.pack(shipped);
        s.link(shipped);
        UUID pickup = s.openPickup();
        s.pickupScan(pickup, shipped);
        s.closePickup(pickup);
        Order cancelRequested = s.order("#P5", 1);
        s.pack(cancelRequested);
        jdbc.update("UPDATE orders SET cancel_requested_at = now() WHERE id = ?", cancelRequested.id());
        s.order("#P6", 1);                                       // never packed

        assertThat(status(shipped.id())).as("a closed pickup leaves the order status").isEqualTo("awaiting_pickup");
        assertThat(pieceStatus(shipped.pieces()[0], s)).isEqualTo("with_courier");

        List<Map<String, Object>> rows = list(s.workerToken);
        assertThat(rows).extracting(r -> r.get("orderNumber")).containsExactlyInAnyOrder("#P1", "#P2", "#P3");
        Map<String, Object> p1 = row(rows, "#P1");
        assertThat(p1.get("status")).isEqualTo("packed");
        assertThat(p1.get("pieces")).isEqualTo(2);
        assertThat(p1.get("trackingNumber")).isEqualTo(packed.tracking());
        assertThat(p1.get("packedByName")).isEqualTo("Ahmed");
        assertThat(p1.get("packedAt")).isNotNull();
        assertThat(p1.get("selfPickup")).isEqualTo(false);
        assertThat(row(rows, "#P2").get("status")).isEqualTo("awaiting_pickup");
        assertThat(row(rows, "#P3").get("selfPickup")).isEqualTo(true);
        assertThat(row(rows, "#P3").get("status")).isEqualTo("self_pickup_pending");
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void l2_openPickupSessionIsNamedOnTheRow() {
        Shop s = new Shop("L2");
        Order o = s.order("#S1", 1);
        s.pack(o);
        s.link(o);
        UUID pickup = s.openPickup();
        s.pickupScan(pickup, o);

        Map<String, Object> r = row(list(s.managerToken), "#S1");
        assertThat(r.get("pickupSessionId")).isEqualTo(pickup.toString());
        assertThat(r.get("pickupDate")).isEqualTo(LocalDate.now().toString());
        assertThat(r.get("pickupTimeSlot")).isEqualTo("10-12");
    }

    @Test
    void l3_codFlag_bostaCodOrderWithDifferentAmountsOnly() {
        Shop s = new Shop("L3");
        Order differs = s.order("#C1", 1);
        s.pack(differs);
        jdbc.update("UPDATE shipments SET cod_amount = 1000.00 WHERE tracking_number = ?", differs.tracking());
        Order same = s.order("#C2", 1);
        s.pack(same);
        jdbc.update("UPDATE shipments SET cod_amount = 1250.00 WHERE tracking_number = ?", same.tracking());
        Order prepaid = s.order("#C3", 1);
        s.pack(prepaid);
        jdbc.update("UPDATE shipments SET cod_amount = 1000.00 WHERE tracking_number = ?", prepaid.tracking());
        jdbc.update("UPDATE orders SET payment_method = 'prepaid' WHERE id = ?", prepaid.id());
        Order generic = s.order("#C4", 1);
        s.pack(generic);
        jdbc.update("UPDATE shipments SET cod_amount = 1000.00, provider = 'generic', carrier_name = 'Aramex', " +
                    "tracking_key = tracking_number WHERE tracking_number = ?", generic.tracking());

        List<Map<String, Object>> rows = list(s.managerToken);
        Map<String, Object> c1 = row(rows, "#C1");
        assertThat(c1.get("codMismatch")).isEqualTo(true);
        assertThat(((Number) c1.get("bostaCod")).doubleValue()).isEqualTo(1000.00);
        assertThat(((Number) c1.get("orderCod")).doubleValue()).isEqualTo(1250.00);
        assertThat(row(rows, "#C2").get("codMismatch")).isEqualTo(false);
        assertThat(row(rows, "#C3").get("codMismatch")).isEqualTo(false);
        Map<String, Object> c4 = row(rows, "#C4");
        assertThat(c4.get("codMismatch")).as("generic courier: no COD flag").isEqualTo(false);
        assertThat(c4.get("courier")).isEqualTo("generic");
        assertThat(c4.get("carrierName")).isEqualTo("Aramex");
    }

    @Test
    void l4_everyRoleReads_otherTenantNeverListed() {
        Shop mine = new Shop("L4a");
        Shop theirs = new Shop("L4b");
        Order m = mine.order("#M1", 1);
        mine.pack(m);
        Order t = theirs.order("#T1", 1);
        theirs.pack(t);

        assertThat(list(mine.workerToken)).as("a worker reads it (200)").extracting(r -> r.get("orderNumber")).containsExactly("#M1");
        assertThat(list(theirs.managerToken)).extracting(r -> r.get("orderNumber")).containsExactly("#T1");
    }

    @Test
    void x1_appUser_underRls_ownListed_otherTenantNot() {
        Shop mine = new Shop("X1a");
        Shop theirs = new Shop("X1b");
        Order m = mine.order("#XM", 1);
        mine.pack(m);
        Order t = theirs.order("#XT", 1);
        theirs.pack(t);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PackedOrderService svc = new PackedOrderService(app);

        TenantContext.set(mine.f.tenant);
        try {
            List<PackedOrderService.PackedOrderRow> rows = tx.execute(st -> svc.list());
            assertThat(rows).as("same-tenant positive control").extracting(PackedOrderService.PackedOrderRow::orderNumber)
                .containsExactly("#XM");
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String token) {
        ResponseEntity<List> r = rest.exchange("http://localhost:" + port + "/api/v1/fulfill/packed", HttpMethod.GET,
            new HttpEntity<>(headers(token)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody();
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String number) {
        return rows.stream().filter(r -> number.equals(r.get("orderNumber"))).findFirst()
            .orElseThrow(() -> new AssertionError(number + " not listed in " + rows));
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

    private String pieceStatus(String shortCode, Shop s) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(shortCode));
    }
}
