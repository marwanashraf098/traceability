package com.traceability.security;

import com.traceability.TestPostgres;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaKeyHealth;
import com.traceability.integrations.bosta.BostaStatusPollJob;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.notifications.EmailGateway;
import com.traceability.portal.RefundDetailsCipher;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Encryption key rotation K2b — {@link ReEncryptionService} + the ops endpoint, the whole app as app_user (RLS on),
 * writes at v2 (the K2a state). Legacy fixtures are written by the pre-key-ring code (zero key, no V2) — prod today.
 *
 *   d1  dry run (service counts + endpoint without apply) changes nothing; endpoint 403 without / with a wrong secret
 *   m1  every listed column migrates; afterwards: the Shopify token reads and refreshes with the plaintext refresh
 *       token, client id / secret decrypt, the Bosta poll calls Bosta with the plaintext key, refund details read 200
 *   m2  a second apply is a no-op (already_v2, values byte-identical)
 *   c1  CAS race: a row the app rewrites between the read and the write is not clobbered (stores, courier, refund)
 *   k1  a known-bad Bosta key stays known bad (hash moves with the ciphertext); a healthy key stays healthy; a stale
 *       hash of an older key stays as it was (still not known bad)
 *   a1  refund AAD still bound to its own row: copied onto another request it doesn't open (500), own row 200
 *   x1  cross-tenant: a run for tenant X never touches tenant Y (byte-identical, still legacy) — X migrated (positive
 *       control); under X's context Y's rows aren't even visible (RLS)
 *   v1  apply refused when the write version is v1 (service and tenant run) — nothing changes
 *   z1  endpoint apply: results + counts after, legacy 0 for every column; a second apply migrates nothing
 *
 * Revert checks (run by hand, 2026-11-28): the key_auth_fail_key_hash carry-over removed → k1 red; the CAS guard
 * removed from the UPDATEs → c1 red.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ReEncryptionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String OPS_SECRET = "k2-test-ops-secret-0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("app.encryption-write-version", () -> "v2");
        r.add("traced.ops-secret", () -> OPS_SECRET);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** The pre-key-ring code, as prod wrote every value until K2a: legacy zero key, no V2. */
    static final EncryptionService LEGACY_WRITER = new EncryptionService(ZERO_KEY, "", "v1");
    static final String IBAN = "EG380019000500000000263180002";
    static final String DETAILS = "{\"method\":\"bank_transfer\",\"holderName\":\"Mona Adel\",\"bankName\":\"CIB Bank\",\"account\":\"" + IBAN + "\"}";

    @Autowired TestRestTemplate     rest;
    @Autowired JwtService           jwt;
    @Autowired EncryptionService    encryption;
    @Autowired RefundDetailsCipher  refundCipher;
    @Autowired ReEncryptionService  reEncryption;
    @Autowired ShopifyTokenProvider tokenProvider;
    @Autowired BostaStatusPollJob   statusPoll;
    @Autowired JdbcTemplate         appJdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired @FlywayDataSource DataSource ownerDs;
    @Value("${app.encryption-key-v2}") String v2Key;

    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean ShopifyImportJob importJob;
    @MockBean JobScheduler     jobScheduler;
    @MockBean EmailGateway     emailGateway;
    @MockBean BostaGateway     bostaGateway;
    @MockBean BostaV2Client    bostaV2;

    /** Fixtures and assertions only (postgres, BYPASSRLS); the app never uses it. */
    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(71_000_000L);

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(encryption.writeVersion()).isEqualTo(EncryptionService.WriteVersion.V2);
        assertThat(pg.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = 'app_user'", Boolean.class)).isFalse();
    }

    @AfterEach
    void clear() {
        reEncryption.beforeWriteHook = null;
        TenantContext.clear();
    }

    // ── d1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void d1_dryRun_changesNothing_endpointNeedsTheSecret() {
        Fixture f = new Fixture("dry");
        String before = snapshot();

        List<ReEncryptionService.ColumnCounts> counts = reEncryption.counts();
        assertThat(count(counts, "stores", "access_token_encrypted").legacy()).isGreaterThanOrEqualTo(2);
        assertThat(count(counts, "courier_accounts", "api_key_encrypted").legacy()).isGreaterThanOrEqualTo(1);
        assertThat(count(counts, "return_requests", "refund_details_encrypted").legacy()).isGreaterThanOrEqualTo(1);
        assertThat(reEncryption.countsForTenant(f.tenant))
            .extracting(ReEncryptionService.ColumnCounts::legacy).containsExactly(2L, 1L, 1L, 1L, 1L, 1L);

        ResponseEntity<Map> dry = ops(null, null);
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody()).containsEntry("mode", "dry_run").doesNotContainKey("results").containsKey("counts");
        assertThat(ops(Map.of("apply", false), null).getBody()).containsEntry("mode", "dry_run");
        assertThat(snapshot()).as("dry run changes nothing").isEqualTo(before);

        assertThat(rest.exchange("/api/v1/ops/re-encryption", HttpMethod.POST, new HttpEntity<>(Map.of("apply", true), json(null)),
            String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/v1/ops/re-encryption", HttpMethod.POST, new HttpEntity<>(Map.of("apply", true),
            json("wrong")), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(snapshot()).isEqualTo(before);
    }

    // ── m1 / m2 ────────────────────────────────────────────────────────────────────────────

    Fixture migrated;

    @Test
    void m1_everyColumnMigrates_valuesStillWork() {
        Fixture f = new Fixture("mig");
        migrated = f;
        List<ReEncryptionService.ColumnResult> r = reEncryption.applyTenant(f.tenant);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::migrated).containsExactly(2, 1, 1, 1, 1, 1);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::failed).containsOnly(0);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::skippedCas).containsOnly(0);
        assertThat(reEncryption.countsForTenant(f.tenant)).extracting(ReEncryptionService.ColumnCounts::legacy).containsOnly(0L);

        // Every stored value of the tenant is v2 now, none carries the plaintext.
        for (String v : values(f.tenant)) assertThat(v).startsWith("v2:").doesNotContain("shp").doesNotContain(IBAN);

        // Shopify: the token reads; an expired one refreshes with the PLAINTEXT refresh token.
        assertThat(TenantContext.runAs(f.tenant, () -> tokenProvider.getValidToken(f.oauthStore))).isEqualTo("shpat_" + f.tag);
        pg.update("UPDATE stores SET access_token_expires_at = now() - interval '1 minute' WHERE id = ?", f.oauthStore);
        when(shopifyGateway.refreshAccessToken(eq(f.shop), eq("shprt_" + f.tag)))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_after_" + f.tag, "shprt_after_" + f.tag, 3600L, 7776000L, null));
        assertThat(TenantContext.runAs(f.tenant, () -> tokenProvider.getValidToken(f.oauthStore))).isEqualTo("shpat_after_" + f.tag);
        verify(shopifyGateway).refreshAccessToken(f.shop, "shprt_" + f.tag);

        // Custom-app credentials decrypt to the originals.
        Map<String, Object> cc = pg.queryForMap("SELECT client_id_encrypted, api_secret_encrypted, access_token_encrypted " +
            "FROM stores WHERE id = ?", f.ccStore);
        assertThat(encryption.decrypt((String) cc.get("client_id_encrypted"))).isEqualTo("client_" + f.tag);
        assertThat(encryption.decrypt((String) cc.get("api_secret_encrypted"))).isEqualTo("shpss_" + f.tag);
        assertThat(encryption.decrypt((String) cc.get("access_token_encrypted"))).isEqualTo("shpat_cc_" + f.tag);

        // Bosta: the status poll calls Bosta with the plaintext key.
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
        statusPoll.pollAll();
        verify(bostaV2, atLeastOnce()).searchDeliveriesPage(eq("bosta_" + f.tag), anyInt(), anyInt(), anyString());
        verify(bostaV2, never()).searchDeliveriesPage(argThat(k -> k.startsWith("v2:")), anyInt(), anyInt(), anyString());

        // Refund details read through the real endpoint.
        ResponseEntity<Map> details = get("/api/v1/return-requests/" + f.request + "/refund-details", f.ownerToken);
        assertThat(details.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(details.getBody()).containsEntry("account", IBAN).containsEntry("holderName", "Mona Adel");
    }

    @Test
    void m2_secondApply_isANoOp() {
        Fixture f = migrated != null ? migrated : new Fixture("mig2");
        if (migrated == null) reEncryption.applyTenant(f.tenant);
        List<String> before = values(f.tenant);

        List<ReEncryptionService.ColumnResult> r = reEncryption.applyTenant(f.tenant);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::migrated).containsOnly(0);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::skippedCas).containsOnly(0);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::failed).containsOnly(0);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::alreadyV2).containsExactly(2, 1, 1, 1, 1, 1);
        assertThat(values(f.tenant)).as("byte-identical").isEqualTo(before);
    }

    // ── c1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_casRace_appRewriteBetweenReadAndWrite_isNotClobbered() {
        Fixture f = new Fixture("race");
        String appToken = encryption.encrypt("shpat_app_rewrote");
        String appKey = encryption.encrypt("bosta_app_rewrote");
        String appDetails = refundCipher.encrypt(f.tenant, f.request, "{\"account\":\"EG000APP\"}");
        reEncryption.beforeWriteHook = (table, id) -> {
            if (id.equals(f.oauthStore)) pg.update("UPDATE stores SET access_token_encrypted = ? WHERE id = ?", appToken, id);
            if (id.equals(f.courier)) pg.update("UPDATE courier_accounts SET api_key_encrypted = ? WHERE id = ?", appKey, id);
            if (id.equals(f.request)) pg.update("UPDATE return_requests SET refund_details_encrypted = ? WHERE id = ?", appDetails, id);
        };

        List<ReEncryptionService.ColumnResult> r = reEncryption.applyTenant(f.tenant);

        assertThat(pg.queryForObject("SELECT access_token_encrypted FROM stores WHERE id = ?", String.class, f.oauthStore))
            .isEqualTo(appToken);
        assertThat(pg.queryForObject("SELECT api_key_encrypted FROM courier_accounts WHERE id = ?", String.class, f.courier))
            .isEqualTo(appKey);
        assertThat(pg.queryForObject("SELECT refund_details_encrypted FROM return_requests WHERE id = ?", String.class, f.request))
            .isEqualTo(appDetails);
        // The whole stores row was skipped (one UPDATE): its refresh token is still the legacy value, for the next run.
        assertThat(pg.queryForObject("SELECT refresh_token_encrypted FROM stores WHERE id = ?", String.class, f.oauthStore))
            .doesNotStartWith("v2:");
        assertThat(result(r, "stores", "access_token_encrypted").skippedCas()).isEqualTo(1);   // the oauth row
        assertThat(result(r, "stores", "access_token_encrypted").migrated()).isEqualTo(1);     // the cc row (positive control)
        assertThat(result(r, "stores", "refresh_token_encrypted").skippedCas()).isEqualTo(1);
        assertThat(result(r, "courier_accounts", "api_key_encrypted").skippedCas()).isEqualTo(1);
        assertThat(result(r, "return_requests", "refund_details_encrypted").skippedCas()).isEqualTo(1);
        assertThat(r).extracting(ReEncryptionService.ColumnResult::failed).containsOnly(0);

        // A later run picks up what's left.
        reEncryption.beforeWriteHook = null;
        reEncryption.applyTenant(f.tenant);
        assertThat(reEncryption.countsForTenant(f.tenant)).extracting(ReEncryptionService.ColumnCounts::legacy).containsOnly(0L);
        assertThat(encryption.decrypt(pg.queryForObject("SELECT access_token_encrypted FROM stores WHERE id = ?", String.class,
            f.oauthStore))).isEqualTo("shpat_app_rewrote");
    }

    // ── k1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void k1_knownBadBostaKey_staysKnownBad_healthyStaysHealthy() {
        Fixture bad = new Fixture("kbad");
        Fixture healthy = new Fixture("kok");
        Fixture stale = new Fixture("kstale");
        String hashOfCurrent = BostaKeyHealth.ciphertextHashSql("api_key_encrypted");
        pg.update("UPDATE courier_accounts SET key_auth_fail_count = 3, key_auth_fail_first_at = now() - interval '1 hour', " +
            "key_auth_bad_at = now() - interval '30 minutes', key_auth_fail_key_hash = " + hashOfCurrent + " WHERE id = ?", bad.courier);
        pg.update("UPDATE courier_accounts SET key_auth_fail_count = 3, key_auth_fail_first_at = now() - interval '1 hour', " +
            "key_auth_bad_at = now() - interval '30 minutes', key_auth_fail_key_hash = 'hash-of-an-older-key' WHERE id = ?", stale.courier);
        assertThat(knownBad(bad.courier)).isTrue();
        assertThat(knownBad(healthy.courier)).isFalse();
        assertThat(knownBad(stale.courier)).isFalse();

        for (Fixture f : List.of(bad, healthy, stale)) reEncryption.applyTenant(f.tenant);

        assertThat(pg.queryForObject("SELECT api_key_encrypted FROM courier_accounts WHERE id = ?", String.class, bad.courier))
            .startsWith("v2:");
        assertThat(knownBad(bad.courier)).as("known bad stays known bad").isTrue();
        assertThat(pg.queryForObject("SELECT key_auth_fail_count FROM courier_accounts WHERE id = ?", Integer.class, bad.courier))
            .isEqualTo(3);
        assertThat(knownBad(healthy.courier)).as("healthy stays healthy").isFalse();
        assertThat(pg.queryForObject("SELECT key_auth_fail_key_hash FROM courier_accounts WHERE id = ?", String.class,
            healthy.courier)).isNull();
        assertThat(knownBad(stale.courier)).isFalse();
        assertThat(pg.queryForObject("SELECT key_auth_fail_key_hash FROM courier_accounts WHERE id = ?", String.class,
            stale.courier)).isEqualTo("hash-of-an-older-key");
    }

    // ── a1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void a1_refundAad_stillBoundToItsOwnRow() {
        Fixture f = new Fixture("aad");
        UUID other = f.request(null);
        reEncryption.applyTenant(f.tenant);
        String enc = pg.queryForObject("SELECT refund_details_encrypted FROM return_requests WHERE id = ?", String.class, f.request);
        assertThat(enc).startsWith("v2:");

        assertThat(refundCipher.decrypt(f.tenant, f.request, enc)).isEqualTo(DETAILS);      // positive control
        assertThatThrownBy(() -> refundCipher.decrypt(f.tenant, other, enc)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCipher.decrypt(UUID.randomUUID(), f.request, enc)).isInstanceOf(IllegalStateException.class);

        pg.update("UPDATE return_requests SET refund_details_encrypted = ? WHERE id = ?", enc, other);
        assertThat(get("/api/v1/return-requests/" + other + "/refund-details", f.ownerToken).getStatusCode())
            .as("a copied blob never opens on another request").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(get("/api/v1/return-requests/" + f.request + "/refund-details", f.ownerToken).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    // ── x1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_runForXNeverTouchesY() {
        Fixture x = new Fixture("tx");
        Fixture y = new Fixture("ty");
        List<String> yBefore = values(y.tenant);

        reEncryption.applyTenant(x.tenant);

        assertThat(values(y.tenant)).as("tenant Y byte-identical").isEqualTo(yBefore);
        assertThat(values(y.tenant)).allSatisfy(v -> assertThat(v).doesNotStartWith("v2:"));
        assertThat(reEncryption.countsForTenant(x.tenant)).as("positive control: X migrated")
            .extracting(ReEncryptionService.ColumnCounts::legacy).containsOnly(0L);
        assertThat(reEncryption.countsForTenant(y.tenant)).extracting(ReEncryptionService.ColumnCounts::legacy)
            .containsExactly(2L, 1L, 1L, 1L, 1L, 1L);

        // Under X's context (app_user + RLS) Y's rows aren't even visible; X's are (positive control).
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(txm);
        Long yVisible = TenantContext.runAs(x.tenant, () -> tx.execute(s -> appJdbc.queryForObject(
            "SELECT (SELECT count(*) FROM stores WHERE tenant_id = ?) + (SELECT count(*) FROM courier_accounts WHERE tenant_id = ?) " +
            "+ (SELECT count(*) FROM return_requests WHERE tenant_id = ?)", Long.class, y.tenant, y.tenant, y.tenant)));
        Long xVisible = TenantContext.runAs(x.tenant, () -> tx.execute(s -> appJdbc.queryForObject(
            "SELECT count(*) FROM stores WHERE tenant_id = ?", Long.class, x.tenant)));
        assertThat(yVisible).isZero();
        assertThat(xVisible).isEqualTo(2L);
    }

    // ── v1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void v1_applyRefused_whenWriteVersionIsV1() {
        Fixture f = new Fixture("v1");
        String before = snapshot();
        ReEncryptionService atV1 = new ReEncryptionService(new EncryptionService(ZERO_KEY, v2Key, "v1"), refundCipher,
            appJdbc, ownerDs, txm);

        assertThatThrownBy(atV1::apply).isInstanceOf(ReEncryptionService.RefusedException.class)
            .hasMessageContaining("APP_ENCRYPTION_WRITE_VERSION=v2");
        assertThatThrownBy(() -> atV1.applyTenant(f.tenant)).isInstanceOf(ReEncryptionService.RefusedException.class);
        assertThat(atV1.countsForTenant(f.tenant)).as("dry run still answers at v1")
            .extracting(ReEncryptionService.ColumnCounts::legacy).containsExactly(2L, 1L, 1L, 1L, 1L, 1L);
        assertThat(snapshot()).isEqualTo(before);
    }

    // ── z1 (last: migrates every tenant) ───────────────────────────────────────────────────

    @Test
    void z1_endpointApply_thenSecondApplyMigratesNothing() {
        new Fixture("ep");
        ResponseEntity<Map> first = ops(Map.of("apply", true), OPS_SECRET);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("mode", "apply");
        @SuppressWarnings("unchecked") List<Map<String, Object>> results = (List<Map<String, Object>>) first.getBody().get("results");
        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(m -> assertThat(((Number) m.get("failed")).intValue()).isZero());
        assertThat(results.stream().mapToInt(m -> ((Number) m.get("migrated")).intValue()).sum()).isGreaterThanOrEqualTo(7);
        @SuppressWarnings("unchecked") List<Map<String, Object>> after = (List<Map<String, Object>>) first.getBody().get("counts");
        assertThat(after).allSatisfy(m -> assertThat(((Number) m.get("legacy")).longValue()).isZero());
        assertThat(pg.queryForObject("SELECT count(*) FROM stores WHERE access_token_encrypted NOT LIKE 'v2:%' " +
            "OR refresh_token_encrypted NOT LIKE 'v2:%' OR client_id_encrypted NOT LIKE 'v2:%' OR api_secret_encrypted NOT LIKE 'v2:%'",
            Long.class)).isZero();

        String before = snapshot();
        ResponseEntity<Map> second = ops(Map.of("apply", true), OPS_SECRET);
        @SuppressWarnings("unchecked") List<Map<String, Object>> again = (List<Map<String, Object>>) second.getBody().get("results");
        assertThat(again).allSatisfy(m -> assertThat(((Number) m.get("migrated")).intValue()).isZero());
        assertThat(snapshot()).isEqualTo(before);
    }

    // ── fixtures / helpers ─────────────────────────────────────────────────────────────────

    /** One tenant with every column legacy: an OAuth store (access + refresh), a custom-app CC store (client id, secret,
     *  access), a Bosta courier row + a live shipment, an owner, and a return request with refund details. */
    class Fixture {
        final String tag;
        final UUID tenant = UUID.randomUUID(), oauthStore = UUID.randomUUID(), ccStore = UUID.randomUUID();
        final String shop;
        final UUID courier, owner, request;
        final String ownerToken;

        Fixture(String tag) {
            this.tag = tag + IDS.incrementAndGet();
            shop = "k2-" + this.tag + ".myshopify.com";
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "K2 " + this.tag);
            pg.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, connection_type, access_token_encrypted, " +
                "access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at, status, import_status) " +
                "VALUES (?, ?, ?, 'shopify', 'oauth', ?, now() + interval '1 hour', ?, now() + interval '30 days', 'connected', 'pending')",
                oauthStore, tenant, shop, LEGACY_WRITER.encrypt("shpat_" + this.tag), LEGACY_WRITER.encrypt("shprt_" + this.tag));
            pg.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, connection_type, access_token_encrypted, " +
                "access_token_expires_at, client_id_encrypted, api_secret_encrypted, status, import_status) " +
                "VALUES (?, ?, ?, 'shopify', 'custom_app_cc', ?, now() + interval '1 hour', ?, ?, 'disconnected', 'pending')",
                ccStore, tenant, "k2cc-" + this.tag + ".myshopify.com", LEGACY_WRITER.encrypt("shpat_cc_" + this.tag),
                LEGACY_WRITER.encrypt("client_" + this.tag), LEGACY_WRITER.encrypt("shpss_" + this.tag));
            courier = pg.queryForObject("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active') RETURNING id", UUID.class, tenant, LEGACY_WRITER.encrypt("bosta_" + this.tag));
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, tenant,
                "owner-" + UUID.randomUUID() + "@test.com");
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");
            UUID order = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class,
                tenant, oauthStore, "gid://shopify/Order/" + IDS.incrementAndGet(), "#K2-" + this.tag);
            pg.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, raw) " +
                "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, '{\"numberOfAttempts\":0}'::jsonb)",
                tenant, order, String.valueOf(80_000_000L + IDS.incrementAndGet()));
            this.order = order;
            request = request(DETAILS);
        }

        final UUID order;

        /** A return request on the fixture's order; legacy refund details when {@code details} is given. */
        UUID request(String details) {
            UUID id = UUID.randomUUID();
            pg.update("INSERT INTO return_requests (id, tenant_id, order_id, status, reference, refund_method, refund_details_encrypted) " +
                "VALUES (?, ?, ?, 'approved', ?, 'bank_transfer', ?)", id, tenant, order, reference(),
                details == null ? null : LEGACY_WRITER.encrypt(details, tenant + "|" + id));
            return id;
        }
    }

    /** Every encrypted value of a tenant, in a fixed order (assertions only — never logged). */
    private List<String> values(UUID tenant) {
        List<String> out = new ArrayList<>();
        out.addAll(pg.queryForList("SELECT v FROM stores, LATERAL (VALUES (1, access_token_encrypted), (2, refresh_token_encrypted), " +
            "(3, client_id_encrypted), (4, api_secret_encrypted)) AS c(n, v) WHERE tenant_id = ? AND v IS NOT NULL ORDER BY id, n",
            String.class, tenant));
        out.addAll(pg.queryForList("SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? ORDER BY id", String.class, tenant));
        out.addAll(pg.queryForList("SELECT refund_details_encrypted FROM return_requests WHERE tenant_id = ? " +
            "AND refund_details_encrypted IS NOT NULL ORDER BY id", String.class, tenant));
        return out;
    }

    /** md5 over every encrypted value + key-health column in the database. */
    private String snapshot() {
        return pg.queryForObject(
            "SELECT md5(coalesce((SELECT string_agg(concat_ws('|', id, access_token_encrypted, refresh_token_encrypted, " +
            "  client_id_encrypted, api_secret_encrypted), ',' ORDER BY id) FROM stores), '') || '#' || " +
            "coalesce((SELECT string_agg(concat_ws('|', id, api_key_encrypted, key_auth_fail_key_hash, key_auth_bad_at), ',' ORDER BY id) " +
            "  FROM courier_accounts), '') || '#' || " +
            "coalesce((SELECT string_agg(concat_ws('|', id, refund_details_encrypted), ',' ORDER BY id) FROM return_requests), ''))",
            String.class);
    }

    private boolean knownBad(UUID courier) {
        return pg.queryForObject("SELECT " + BostaKeyHealth.knownBadSql("ca") + " FROM courier_accounts ca WHERE ca.id = ?",
            Boolean.class, courier);
    }

    private static ReEncryptionService.ColumnCounts count(List<ReEncryptionService.ColumnCounts> c, String table, String column) {
        return c.stream().filter(x -> x.table().equals(table) && x.column().equals(column)).findFirst().orElseThrow();
    }

    private static ReEncryptionService.ColumnResult result(List<ReEncryptionService.ColumnResult> r, String table, String column) {
        return r.stream().filter(x -> x.table().equals(table) && x.column().equals(column)).findFirst().orElseThrow();
    }

    private ResponseEntity<Map> ops(Map<String, Object> body, String secret) {
        return rest.exchange("/api/v1/ops/re-encryption", HttpMethod.POST,
            new HttpEntity<>(body, json(secret == null ? OPS_SECRET : secret)), Map.class);
    }

    private static HttpHeaders json(String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) h.set("X-Ops-Secret", secret);
        return h;
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }
}
