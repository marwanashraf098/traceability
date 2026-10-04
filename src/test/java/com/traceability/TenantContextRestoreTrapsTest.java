package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.identity.PinService;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaStatusPollJob;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.notifications.EmailGateway;
import com.traceability.notifications.ExceptionDigestJob;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The five traps of the TenantContext.runAs clear-on-exit bug (diagnosis 2026-10-03, §3) — each
 * passes with runAs restoring the previous tenant and fails with the old clear-on-exit runAs:
 * a print batch over HTTP records its row (S2 workaround removed); a PIN switch whose service
 * runs a runAs still revokes the outgoing worker's phone pairing; the digest still reports
 * when detection starts with a runAs helper; the status poll still stamps last_polled_at on
 * every shipment when the ingest callee runs a runAs; the Shopify OAuth service leaves the
 * request's tenant in place. The digest and poll traps run on an app_user connection — on the
 * BYPASSRLS test connection a lost tenant is invisible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantContextRestoreTrapsTest {

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
    @Autowired JdbcTemplate          jdbc;
    @Autowired JwtService            jwt;
    @Autowired EncryptionService     encryption;
    @Autowired ShopifyOAuthService   oauth;
    @Autowired ShipmentLinkService   shipmentLinkService;
    @Autowired Clock                 clock;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  PinService            pinService;
    @MockBean  JobScheduler          jobScheduler;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private DriverManagerDataSource appUserRaw() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
    }

    private HttpResponse<String> post(String path, String token, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    // ── 1. print batch over HTTP records its row ─────────────────────────────

    @Test
    void printBatchOverHttp_recordsItsBatchRow_withTheS2WorkaroundRemoved() throws Exception {
        PackFixtures f = new PackFixtures(jdbc, "Trap print");
        UUID owner = f.user("Owner", "owner");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
                    "VALUES (?, 'bosta', ?, 'trap-hash', 'active'::courier_account_status, 'A6', 'ar')",
            f.tenant, encryption.encrypt("trap-key"));
        String tn = f.forward(f.order("#T1", 0));
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenAnswer(inv -> new AwbPrintResult(pdf(inv.getArgument(1)), null));

        HttpResponse<String> r = post("/api/v1/fulfill/print-batches", jwt.issueAccessToken(owner, f.tenant, "owner"),
            Map.of("scope", "all", "paper", "A6", "sort", "oldest"));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("waybillCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batches WHERE tenant_id = ?", Integer.class, f.tenant))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tracking_number FROM pack_print_batch_items WHERE tenant_id = ?", String.class, f.tenant))
            .isEqualTo(tn);
    }

    // ── 2. PIN switch still revokes the outgoing worker's phone ──────────────

    @Test
    void pinSwitch_whosePinServiceRunsARunAs_stillRevokesTheOutgoingWorkersPairing() throws Exception {
        PackFixtures f = new PackFixtures(jdbc, "Trap pin");
        UUID outgoing = f.user("Ahmed", "worker");
        UUID next = f.user("Mona", "worker");
        UUID session = jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, f.tenant, outgoing);
        UUID pairing = jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, pack_session_id, station_user_id, pair_code_hash, device_secret_hash, " +
            "                           pair_code_expires_at, claimed_at, expires_at) " +
            "VALUES (?, ?, ?, ?, ?, now() + interval '2 minutes', now(), now() + interval '12 hours') RETURNING id",
            UUID.class, f.tenant, session, outgoing, "trap-pc-" + UUID.randomUUID(), "trap-ds-" + UUID.randomUUID());
        // A PinService that does its work inside a runAs — as any tenant-scoped lookup would.
        when(pinService.switchPin(any(), any(), any())).thenAnswer(inv -> {
            UUID tenant = inv.getArgument(0);
            TenantContext.runAs(tenant, () -> "looked up the worker");
            return new TokenResponse(jwt.issueAccessToken(next, tenant, "worker"), "refresh-" + UUID.randomUUID());
        });

        HttpResponse<String> r = post("/api/v1/auth/pin", jwt.issueAccessToken(outgoing, f.tenant, "worker"),
            new PinRequest(next.toString(), "1234"));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT revoked_reason FROM scan_pairings WHERE id = ?", String.class, pairing))
            .as("the outgoing worker's phone is unpaired").isEqualTo("worker_switched");
    }

    // ── 3. digest still reports when detection starts with a runAs helper ────

    @Test
    void digest_detectionStartingWithARunAsHelper_stillReportsTheTenantsExceptions() {
        PackFixtures f = new PackFixtures(jdbc, "Trap digest");
        String ownerEmail = "trap-digest-" + f.tenant + "@test.com";
        jdbc.update("INSERT INTO users (tenant_id, name, email, password_hash, role) VALUES (?, 'Owner', ?, 'x', 'owner'::user_role)",
            f.tenant, ownerEmail);
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, status, on_hold, hold_reason) " +
                    "VALUES (?, ?, ?, 'new'::order_status, true, 'trap hold')", f.tenant, f.store, "EXT-" + UUID.randomUUID());

        TenantAwareDataSource ds = new TenantAwareDataSource(appUserRaw());
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        // Detection whose first step is a helper that runs in its own runAs; the detectors after
        // it must still see this tenant. Each call in its own transaction, as the @Transactional
        // proxy of the real bean gives it (this instance is built by hand on app_user).
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ExceptionService detection = new ExceptionService(appJdbc, clock, shipmentLinkService) {
            @Override public List<Map<String, Object>> detectAllOpen() {
                return appTx.execute(s -> {
                    TenantContext.runAs(TenantContext.require(), () -> "a helper");
                    return super.detectAllOpen();
                });
            }
            @Override public OpenExceptionCounts countOpenExceptionsBySeverity() {
                return appTx.execute(s -> super.countOpenExceptionsBySeverity());
            }
        };
        EmailGateway email = mock(EmailGateway.class);
        ExceptionDigestJob job = new ExceptionDigestJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"),
            appJdbc, detection, email, new DataSourceTransactionManager(ds), clock);

        job.run();

        verify(email).send(eq(ownerEmail), anyString(), anyString());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM exception_notifications WHERE tenant_id = ? AND channel = 'digest'", Integer.class, f.tenant))
            .isGreaterThan(0);
    }

    // ── 4. status poll stamps every shipment ─────────────────────────────────

    @Test
    void statusPoll_ingestCalleeRunningARunAs_stillStampsLastPolledAtOnEveryShipment() throws Exception {
        PackFixtures f = new PackFixtures(jdbc, "Trap poll");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'trap-poll-hash', 'active'::courier_account_status)",
            f.tenant, encryption.encrypt("trap-poll-key"));
        for (int i = 0; i < 3; i++) f.forward(f.order("#P" + i, 0));

        BostaIngestionHelper ingest = mock(BostaIngestionHelper.class);
        when(ingest.ingestDelivery(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            TenantContext.runAs((UUID) inv.getArgument(0), () -> "fetched + enqueued");
            return false;
        });
        TenantAwareDataSource ds = new TenantAwareDataSource(appUserRaw());
        BostaStatusPollJob job = new BostaStatusPollJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"),
            new JdbcTemplate(ds), new DataSourceTransactionManager(ds), encryption, ingest, 200, 0, true);

        job.pollAll();

        verify(ingest, times(3)).ingestDelivery(eq(f.tenant), anyString(), anyString(), anyString(), any());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND last_polled_at IS NULL", Integer.class, f.tenant))
            .as("every shipment stamped").isZero();
    }

    // ── 5. Shopify OAuth leaves the request's tenant in place ────────────────

    @Test
    void shopifyOAuth_initiateAndSessionTokenRefresh_leaveTheRequestsTenantInPlace() {
        UUID tenant = UUID.randomUUID();             // no store yet: a first connect may name any shop
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Trap oauth')", tenant);
        TenantContext.set(tenant);                   // as TenantContextFilter does for the OWNER's request

        String nonce = oauth.initiateOAuth(tenant, "trap-" + UUID.randomUUID().toString().substring(0, 8) + ".myshopify.com", null);
        assertThat(nonce).isNotBlank();
        assertThat(TenantContext.get()).as("after initiateOAuth").isEqualTo(tenant);

        boolean refreshed = oauth.acquireOrRefreshViaSessionToken(tenant, "no-such-store.myshopify.com", "raw-session-token");
        assertThat(refreshed).isFalse();
        assertThat(TenantContext.get()).as("after acquireOrRefreshViaSessionToken").isEqualTo(tenant);
    }

    private static byte[] pdf(List<String> trackings) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (String t : trackings) {
                PDPage page = new PDPage();
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("AWB " + t);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }
}
