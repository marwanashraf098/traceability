package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.SimulatedWaybillRenderer;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S2 regression guard — the REAL print path (a tenant WITH an active Bosta account,
 * how Jumi prints daily) after BostaAwbService's steps 2–3 were extracted into loadPrintable().
 * Passes unchanged against the pre-S2 BostaAwbService (main 94c4a4c) and the S2 one.
 *
 *   g1 single print (/bosta/awb/print): mass-awb gets exactly the printable tracking numbers, in the
 *      caller's order, duplicates dropped, in chunks of 49 (49 + 3), with the account's awb_format /
 *      awb_lang; exclusions (delivered / returned = NON_PRINTABLE_STATES, CRP type 25) in input order;
 *      unknown and other-tenant ids skipped silently; result shape {pdfBase64List, emailMessage,
 *      exceptions} with one PDF per chunk
 *   g2 batch print (/fulfill/print-batches): one mass-awb call with the batch's tracking numbers in
 *      sort order (oldest first), the requested paper; result shape unchanged (merged PDF, page order
 *      verified, waybillCount, remainingCount, batch recorded)
 *
 * (The UNLINKED exclusion can't be exercised: shipments.order_id is NOT NULL, so no shipment is ever
 * unlinked — it was unreachable before the refactor too.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealPrintPathRegressionTest {

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
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwt;
    @Autowired EncryptionService encryption;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    private static final String API_KEY = "real-tenant-bosta-key";

    private final class Real {
        final PackFixtures f;
        final String token;
        Real(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                        "VALUES (?, 'bosta', ?, ?, 'active', 'A6', 'ar')",
                        f.tenant, encryption.encrypt(API_KEY), "hash-" + UUID.randomUUID());
            token = jwt.issueAccessToken(f.user("Owner", "owner"), f.tenant, "owner");
        }
        /** A forward shipment on a fresh 'new' order; returns {shipmentId, trackingNumber}. */
        Object[] ship(String number, String state, String rawJson, int placedDaysAgo) {
            UUID order = f.order(number, placedDaysAgo);
            String tn = PackFixtures.nextTracking();
            UUID id = jdbc.queryForObject(
                "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
                UUID.class, f.tenant, order, tn, state, rawJson);
            return new Object[]{id, tn};
        }
    }

    @BeforeEach
    void stubBosta() {
        reset(bostaGateway);
        // A real, text-bearing PDF per chunk (one page per tracking number, as Bosta returns).
        when(bostaGateway.printMassAwb(anyString(), anyList(), any(), any())).thenAnswer(inv -> {
            List<String> tns = inv.getArgument(1);
            List<SimulatedWaybillRenderer.Waybill> pages = tns.stream()
                .map(tn -> new SimulatedWaybillRenderer.Waybill(tn, null, null, null, null, null)).toList();
            return new AwbPrintResult(SimulatedWaybillRenderer.render(pages, "A4"), null);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void g1_singlePrint_samePayloadsOrderChunkingExclusionsAndShape() {
        Real r = new Real("G1");
        Real other = new Real("G1other");

        // Create in one order, ask in the REVERSE order — so "caller's order" can't pass by
        // coinciding with the database's (insertion) order.
        List<Object[]> created = new ArrayList<>();
        for (int i = 0; i < 52; i++) created.add(r.ship("#G1-" + i, "created", null, 1));
        Object[] delivered = r.ship("#G1-del", "delivered", null, 1);
        Object[] crp       = r.ship("#G1-crp", "created", "{\"type\":{\"code\":25,\"value\":\"CRP\"}}", 1);
        Object[] returned  = r.ship("#G1-ret", "returned", null, 1);
        UUID foreign       = (UUID) other.ship("#G1-x", "created", null, 1)[0];
        Collections.reverse(created);

        List<UUID> ids = new ArrayList<>();
        List<String> expectedPrintable = new ArrayList<>();
        List<Map<String, Object>> expectedExclusions = new ArrayList<>();
        for (int i = 0; i < created.size(); i++) {
            Object[] s = created.get(i);
            ids.add((UUID) s[0]);
            expectedPrintable.add((String) s[1]);
            if (i == 10) {   // interleave the non-printables, a duplicate and foreign / unknown ids
                ids.add((UUID) returned[0]);
                ids.add((UUID) crp[0]);
                ids.add((UUID) s[0]);        // duplicate → dropped
                ids.add(foreign);            // another tenant's → skipped
                ids.add(UUID.randomUUID());  // unknown → skipped
                ids.add((UUID) delivered[0]);
                expectedExclusions.add(Map.of("trackingNumber", returned[1],  "reason", "NON_PRINTABLE_STATE:returned"));
                expectedExclusions.add(Map.of("trackingNumber", crp[1],       "reason", "NON_PRINTABLE_TYPE:CRP"));
                expectedExclusions.add(Map.of("trackingNumber", delivered[1], "reason", "NON_PRINTABLE_STATE:delivered"));
            }
        }

        ResponseEntity<Map> resp = rest.exchange(base() + "/api/v1/bosta/awb/print", HttpMethod.POST,
            new HttpEntity<>(Map.of("shipmentIds", ids), auth(r.token)), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.OK);

        // mass-awb: exact tracking numbers, caller's order, chunks of 49, account format / lang.
        ArgumentCaptor<List<String>> chunks = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway, times(2)).printMassAwb(eq(API_KEY), chunks.capture(), eq("A6"), eq("ar"));
        assertThat(chunks.getAllValues().get(0)).containsExactlyElementsOf(expectedPrintable.subList(0, 49));
        assertThat(chunks.getAllValues().get(1)).containsExactlyElementsOf(expectedPrintable.subList(49, 52));
        verifyNoMoreInteractions(bostaGateway);

        // Result shape + exclusions in input order.
        Map<String, Object> body = resp.getBody();
        assertThat(body.keySet()).containsExactlyInAnyOrder("pdfBase64List", "emailMessage", "exceptions");
        assertThat((List<String>) body.get("pdfBase64List")).hasSize(2);
        assertThat(body.get("emailMessage")).isNull();
        assertThat((List<Map<String, Object>>) body.get("exceptions")).containsExactlyElementsOf(expectedExclusions);

        // Exclusions are still recorded on the shipment rows.
        assertThat(jdbc.queryForList("SELECT awb_print_failed_reason FROM shipments WHERE tenant_id = ? " +
                                     "AND awb_print_failed_reason IS NOT NULL ORDER BY awb_print_failed_reason",
                                     String.class, r.f.tenant))
            .containsExactly("NON_PRINTABLE_STATE:delivered", "NON_PRINTABLE_STATE:returned", "NON_PRINTABLE_TYPE:CRP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void g2_batchPrint_samePayloadOrderAndShape() {
        Real r = new Real("G2");
        String newest = (String) r.ship("#G2-new", "created", null, 1)[1];
        String oldest = (String) r.ship("#G2-old", "created", null, 9)[1];
        String middle = (String) r.ship("#G2-mid", "created", null, 4)[1];
        r.ship("#G2-moving", "with_courier", null, 2);                    // not a batch candidate

        ResponseEntity<Map> resp = rest.exchange(base() + "/api/v1/fulfill/print-batches", HttpMethod.POST,
            new HttpEntity<>(Map.of("scope", "new", "paper", "A4", "sort", "oldest"), auth(r.token)), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<List<String>> chunk = ArgumentCaptor.forClass(List.class);
        verify(bostaGateway, times(1)).printMassAwb(eq(API_KEY), chunk.capture(), eq("A4"), eq("ar"));
        assertThat(chunk.getValue()).containsExactly(oldest, middle, newest);
        verifyNoMoreInteractions(bostaGateway);

        Map<String, Object> body = resp.getBody();
        assertThat(body.keySet()).containsExactlyInAnyOrder("batchId", "batchNo", "waybillCount", "candidateCount",
            "remainingCount", "orderGuaranteed", "pdfBase64", "excluded", "message");
        assertThat(body.get("waybillCount")).isEqualTo(3);
        assertThat(body.get("remainingCount")).isEqualTo(0);
        assertThat(body.get("orderGuaranteed")).isEqualTo(true);
        assertThat(body.get("pdfBase64")).isNotNull();
        assertThat((List<Object>) body.get("excluded")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batch_items WHERE tenant_id = ?",
            Integer.class, r.f.tenant)).isEqualTo(3);
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
