package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.WaybillResolver;
import com.traceability.inventory.WaybillResolver.Code;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Hotfix (production, Jumi 2026-10-01): Bosta's TOP waybill barcode reads as
 * "G - 0 2 - 8 4 8 4 8 0 5 6 9 9". It now resolves to the order and packs end to end; a barcode
 * that is neither a waybill nor a piece code gets its own UNRECOGNISED_BARCODE answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwbSpacedBarcodeTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired WaybillResolver  resolver;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    /** "8484805699" → "G - 0 2 - 8 4 8 4 8 0 5 6 9 9" — the way Bosta's top barcode reads. */
    private static String spaced(String tn) {
        return "G - 0 2 - " + String.join(" ", tn.split(""));
    }

    @Test
    void spacedTopBarcode_resolvesToTheOrder() {
        PackFixtures f = new PackFixtures(jdbc, "Spaced");
        UUID me = f.user("Ahmed", "worker");
        UUID order = f.order("#S1", 1);
        String tn = f.forward(order);

        WaybillResolver.Resolution r = TenantContext.runAs(f.tenant, () -> resolver.resolve(spaced(tn), me));
        assertThat(r.code()).isEqualTo(Code.OPEN);
        assertThat(r.orderId()).isEqualTo(order);
    }

    @Test
    void pieceCodeVsUnrecognisedBarcode_getDifferentAnswers() {
        PackFixtures f = new PackFixtures(jdbc, "Split");
        UUID me = f.user("Ahmed", "worker");
        for (String piece : List.of("P000123", "PC-01HZX4K9T2B7QW3M5N8R6Y1V0D", "01HZX4K9T2B7QW3M5N8R6Y1V0D")) {
            assertThat(TenantContext.runAs(f.tenant, () -> resolver.resolve(piece, me)).code())
                .as(piece).isEqualTo(Code.NOT_A_WAYBILL);
        }
        for (String other : List.of("G - 0 2 - 8 4 X 4", "ABC-XYZ", "hello world")) {
            WaybillResolver.Resolution r = TenantContext.runAs(f.tenant, () -> resolver.resolve(other, me));
            assertThat(r.code()).as(other).isEqualTo(Code.UNRECOGNISED_BARCODE);
            assertThat(r.messageEn()).contains("bottom of the waybill");
            assertThat(r.messageAr()).isNotBlank();
        }
    }

    @Test
    void spacedTopBarcode_packsEndToEnd_rawScanKept_neverCallsBosta() {
        PackFixtures f = new PackFixtures(jdbc, "SpacedPack");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
        UUID packer = f.user("Ahmed", "worker");
        String token = jwt.issueAccessToken(packer, f.tenant, "worker");
        UUID variant = f.variant("Shirt", "S-1", null);
        UUID order = f.order("#S2", 1);
        f.item(order, variant, 1);
        String tn = f.forward(order);
        String piece = f.piece(variant, packer);

        UUID session = UUID.fromString((String) post(token, "", null).get("id"));
        String raw = spaced(tn);
        assertThat(post(token, "/" + session + "/waybill", Map.of("code", raw)).get("result")).isEqualTo("opened");
        assertThat(post(token, "/" + session + "/orders/" + order + "/scan", Map.of("code", piece)).get("status"))
            .isEqualTo("completed");

        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order))
            .isEqualTo("awaiting_pickup");
        assertThat(jdbc.queryForList("SELECT raw_scan FROM piece_events WHERE order_id = ? AND event_type = 'tracking_linked'",
            String.class, order)).containsExactly(raw);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    private Map<String, Object> post(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/pack-sessions" + path,
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }
}
