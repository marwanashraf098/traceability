package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pre-connect filter inside discovery (2026-10-04): decided on the v2 list item (createdAt,
 * businessReference, shopifyInfo) with the same rules as the webhook job's filter, so an ignored
 * delivery costs no Bosta fetch at all.
 *
 *   pc1 a pre-connect delivery: ZERO fetches, no job, exactly one processed 'ignored_pre_connect' row;
 *       deliveries that aren't pre-connect (created after the cutoff; or a reference that resolves —
 *       here only after the ':') are ingested as before
 *   pc2 the same ignored delivery changing state on the next run: still zero fetches, no new row
 *   pc3 a NULL cutoff (Jumi) is never filtered — and the "already ignored" check is per tenant: the same
 *       tracking number ignored for tenant A is ingested for tenant B
 *   pc4 cross-tenant: two tenants with cutoffs each record their OWN ignored row — one tenant's row never
 *       counts as "already ignored" for another
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDiscoveryPreConnectTest {

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
        r.add("bosta.poll.inter-fetch-delay-ms", () -> "0");
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaDiscoveryPollJob discoveryPollJob;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  BostaV2Client         bostaV2;
    @MockBean  JobScheduler          jobScheduler;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Map<String, List<JsonNode>> lists = new ConcurrentHashMap<>();
    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stub() {
        reset(bostaGateway, bostaV2, jobScheduler);
        lists.clear();
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenAnswer(inv ->
            (int) inv.getArgument(1) == 1 ? lists.getOrDefault((String) inv.getArgument(0), List.of()) : List.of());
    }

    @AfterEach
    void deactivate() {
        for (UUID t : tenants) jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", t);
        tenants.clear();
    }

    @Test
    void pc1_preConnectIgnoredWithoutAFetch_othersIngested() {
        UUID a = tenant("pc1", now.minus(1, ChronoUnit.DAYS));
        order(a, "BRK-44898-EG");
        String pre = "7100000001", colon = "7100000002", post = "7100000003";
        lists.put("key-pc1", List.of(
            item(post,  10, now.minus(1, ChronoUnit.HOURS), "BRK-50000-EG", "2026-10-04T00:10:00.000Z"),
            item(pre,   45, now.minus(3, ChronoUnit.DAYS),  "BRK-40001-EG", "2026-10-03T09:00:00.000Z"),
            item(colon, 45, now.minus(3, ChronoUnit.DAYS),  "BRK-40002-EG:BRK-44898-EG", "2026-10-03T09:00:00.000Z")));

        discoveryPollJob.discoverAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(rows(a, pre)).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("processed");
            assertThat(r.get("error")).isEqualTo("ignored_pre_connect: " + pre);
        });
        assertThat(rows(a, colon)).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("pending"));
        assertThat(rows(a, post)).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("pending"));
        verify(jobScheduler, times(2)).enqueue(any(JobLambda.class));   // colon + post; nothing for pre
    }

    @Test
    void pc2_ignoredDeliveryChangingState_noFetch_noNewRow() {
        UUID a = tenant("pc2", now.minus(1, ChronoUnit.DAYS));
        String pre = "7200000001";
        lists.put("key-pc2", List.of(item(pre, 24, now.minus(3, ChronoUnit.DAYS), "BRK-40001-EG", "2026-10-03T09:00:00.000Z")));
        discoveryPollJob.discoverAll();
        assertThat(rows(a, pre)).hasSize(1);

        lists.put("key-pc2", List.of(item(pre, 46, now.minus(3, ChronoUnit.DAYS), "BRK-40001-EG", "2026-10-04T08:00:00.000Z")));
        discoveryPollJob.discoverAll();

        assertThat(rows(a, pre)).as("still one row").hasSize(1);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }

    @Test
    void pc3_nullCutoffNeverFiltered_andAlreadyIgnoredIsPerTenant() {
        UUID a = tenant("pc3a", now.minus(1, ChronoUnit.DAYS));
        UUID jumi = tenant("pc3b", null);
        String same = "7300000001";
        // Same tracking number in both lists (artificial — Bosta's are unique); a different updatedAt so the
        // global (source, idem key) index doesn't collide: what's tested is the per-tenant "already ignored".
        lists.put("key-pc3a", List.of(item(same, 45, now.minus(30, ChronoUnit.DAYS), "BRK-40001-EG", "2026-09-04T09:00:00.000Z")));
        lists.put("key-pc3b", List.of(item(same, 45, now.minus(30, ChronoUnit.DAYS), "BRK-40001-EG", "2026-09-05T09:00:00.000Z")));

        discoveryPollJob.discoverAll();

        assertThat(rows(a, same)).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("processed"));
        assertThat(rows(jumi, same)).as("NULL cutoff: ingested, never filtered")
            .singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("pending"));
    }

    @Test
    void pc4_alreadyIgnored_isPerTenant() {
        UUID a = tenant("pc4a", now.minus(1, ChronoUnit.DAYS));
        UUID b = tenant("pc4b", now.minus(1, ChronoUnit.DAYS));
        String same = "7400000001";
        lists.put("key-pc4a", List.of(item(same, 45, now.minus(30, ChronoUnit.DAYS), "BRK-40001-EG", "2026-09-04T09:00:00.000Z")));
        lists.put("key-pc4b", List.of(item(same, 45, now.minus(30, ChronoUnit.DAYS), "BRK-40001-EG", "2026-09-05T09:00:00.000Z")));

        discoveryPollJob.discoverAll();

        assertThat(rows(a, same)).singleElement().satisfies(r -> assertThat((String) r.get("error")).startsWith("ignored_pre_connect"));
        assertThat(rows(b, same)).singleElement().satisfies(r -> assertThat((String) r.get("error")).startsWith("ignored_pre_connect"));
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode item(String tn, int state, Instant createdAt, String reference, String updatedAt) {
        ObjectNode i = BostaSearchItems.item(tn, state, createdAt, updatedAt, reference);
        i.put("createdAt", createdAt.toString());
        return i;
    }

    private UUID tenant(String name, Instant cutoff) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", UUID.randomUUID(), id, name + "-" + id + ".myshopify.com",
            cutoff == null ? null : Timestamp.from(cutoff));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name));
        tenants.add(id);
        return id;
    }

    private void order(UUID tenant, String number) {
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, tenant);
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status)", tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number);
    }

    private List<Map<String, Object>> rows(UUID tenant, String tn) {
        return jdbc.queryForList("SELECT status::text AS status, error FROM webhook_events " +
            "WHERE tenant_id = ? AND payload->>'trackingNumber' = ? ORDER BY id", tenant, tn);
    }
}
