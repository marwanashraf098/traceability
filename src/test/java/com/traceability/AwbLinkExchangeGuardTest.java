package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exchange reference S1 (2026-11-17) — the order-queue AWB link (POST /api/v1/fulfill/{orderId}/link) refuses a
 * Bosta exchange waybill whose replacement isn't mapped yet.
 *
 * <pre>
 *   g1  a packed order with no waybill + the AWB of an unmapped exchange (exchanges row, outbound_order_id NULL)
 *       → 409 EXCHANGE_NOT_MAPPED with the "give it to a manager" words (EN + AR); no shipment, order unchanged
 *   g2  a mapped exchange's own EXC order (packed, its forward leg = the AWB) → links (200), as before
 *   g3  a normal AWB on a packed order with no waybill → links (200), a forward shipment is created, as before
 *   g4  an exchanges row of ANOTHER tenant with that AWB doesn't block this tenant's link
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwbLinkExchangeGuardTest {

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
    @MockBean  ShopifyGateway   shopifyGateway;
    @MockBean  JobScheduler     jobScheduler;

    final class Shop {
        final PackFixtures f;
        final String workerToken;
        final UUID variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            UUID worker = f.user("Ahmed", "worker");
            workerToken = jwt.issueAccessToken(worker, f.tenant, "worker");
            variant = f.variant("Linen shirt", "LS-" + name, null);
        }
        /** A packed order with one line and no forward leg. */
        UUID packedOrder(String number) {
            UUID id = f.orderWith(number, "packed", 0, "EXT-" + UUID.randomUUID());
            f.item(id, variant, 1);
            return id;
        }
        void unmappedExchange(String awb) {
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw) " +
                "VALUES (?, ?, 'needs_mapping', 'Out', 'In', '{}'::jsonb)", f.tenant, awb);
        }
        ResponseEntity<Map> link(UUID orderId, String awb) {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(workerToken);
            h.setContentType(MediaType.APPLICATION_JSON);
            return rest.exchange("http://localhost:" + port + "/api/v1/fulfill/" + orderId + "/link", HttpMethod.POST,
                new HttpEntity<>(Map.of("trackingNumber", awb), h), Map.class);
        }
    }

    @Test
    void g1_unmappedExchangeAwb_refused() {
        Shop s = new Shop("g1");
        UUID order = s.packedOrder("#G1001");
        String awb = PackFixtures.nextTracking();
        s.unmappedExchange(awb);

        ResponseEntity<Map> r = s.link(order, awb);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("EXCHANGE_NOT_MAPPED");
        assertThat((String) r.getBody().get("message_en")).contains("Give it to a manager");
        assertThat((String) r.getBody().get("message_ar")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE tracking_number = ?", Integer.class, awb)).isZero();
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order)).isEqualTo("packed");
    }

    @Test
    void g2_mappedExchange_ownExcOrder_stillLinks() {
        Shop s = new Shop("g2");
        String awb = PackFixtures.nextTracking();
        UUID exc = s.f.orderWith("EXC-" + awb, "packed", 0, "internal:exchange:" + awb);
        s.f.item(exc, s.variant, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", s.f.tenant, exc, awb);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw, " +
            "outbound_order_id) VALUES (?, ?, 'mapped', 'Out', 'In', '{}'::jsonb, ?)", s.f.tenant, awb, exc);

        assertThat(s.link(exc, awb).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void g3_normalAwb_stillLinks() {
        Shop s = new Shop("g3");
        UUID order = s.packedOrder("#G3001");
        String awb = PackFixtures.nextTracking();

        assertThat(s.link(order, awb).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = ? AND shipment_leg = 'forward'",
            UUID.class, awb)).isEqualTo(order);
    }

    @Test
    void g4_anotherTenantsExchange_doesNotBlock() {
        Shop a = new Shop("g4a");
        Shop b = new Shop("g4b");
        String awb = PackFixtures.nextTracking();
        b.unmappedExchange(awb);
        UUID order = a.packedOrder("#G4001");

        assertThat(a.link(order, awb).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
