package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Discovery on Bosta's v2 delivery search (2026-10-03). The fake is the contract the prod probe proved
 * (BROEK + Femine): POST /api/v2/deliveries/search, sortBy "-createdAt" newest created first, the
 * requested limit honoured, page 2 continuing exactly after page 1, count always 0 — the end of the
 * list is an empty or short page.
 *
 *   v1 a same-second batch of 120 (Femine-style): pages 1–3 of 50, every delivery discovered, the mark
 *      advanced to the batch; the request is sortBy "-createdAt", limit 50
 *   v2 a backlog deeper than the page cap: the mark is NOT advanced, the walk stores where it stopped;
 *      the next run resumes, finds the rest, completes and advances the mark
 *   v3 deliveries created while a walk is interrupted: the next run takes the new head first, then
 *      continues the walk shifted by them — nothing lost
 *   v4 repeat-page guard, Bosta ignoring `page` (same first / last again): stop, mark not moved, nothing
 *      saved, each delivery ingested once
 *   v5 repeat-page guard, a page of nothing new (all already seen this run, different order): stop,
 *      mark not moved
 *   v6 walk-state reset (V134): an interrupted v0 walk (page 31) is dropped; the first v2 run starts at
 *      page 1 and walks back to the existing mark
 *   v7 the list item is used directly: no per-delivery fetch; trackingNumber, state, type, updatedAt,
 *      businessReference, uniqueBusinessReference, shopifyInfo.orderId and creationTimestamp reach the
 *      ingested event
 *   v8 an item without what ingest needs (no updatedAt, or a (state, type) the mapper doesn't know)
 *      falls back to one fetch — never dropped
 *   v9 cross-tenant: one tenant's capped walk and mark never touch another's
 *   v10 a search page that is rate limited: the walk stops, mark and walk state unchanged; the next
 *      run finishes it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDiscoveryPagingTest {

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
        r.add("bosta.poll.inter-fetch-delay-ms",          () -> "0");
        r.add("bosta.poll.discovery-max-items-per-cycle", () -> "2000");
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaDiscoveryPollJob discoveryPollJob;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  BostaV2Client         bostaV2;
    @MockBean  JobScheduler          jobScheduler;

    /** One tenant's Bosta account behind the v2 search: deliveries newest created first. */
    static final class FakeSearch {
        final List<ObjectNode> newestFirst = new ArrayList<>();
        boolean ignoresPage;        // answers page 1 whatever page is asked for
        boolean page2RepeatsPage1;  // page 2 = page 1's items, reversed (first / last differ)
        Integer rateLimitedOnPage;
        final List<String> requests = new CopyOnWriteArrayList<>();   // "page:limit:sortBy"

        List<JsonNode> page(int page, int limit, String sortBy) {
            requests.add(page + ":" + limit + ":" + sortBy);
            if (rateLimitedOnPage != null && rateLimitedOnPage == page) throw new BostaRateLimitException(60);
            if (page2RepeatsPage1 && page == 2) {
                List<JsonNode> rev = new ArrayList<>(slice(1, limit));
                Collections.reverse(rev);
                return rev;
            }
            return slice(ignoresPage ? 1 : page, limit);
        }

        private List<JsonNode> slice(int page, int limit) {
            int from = (page - 1) * limit;
            List<JsonNode> out = new ArrayList<>();
            for (int i = from; i < Math.min(newestFirst.size(), from + limit); i++) out.add(newestFirst.get(i));
            return out;
        }

        /** Adds n deliveries created at the same second (a Shopify-app batch), newest first, on top. */
        void batch(String prefix, int n, Instant at) {
            List<ObjectNode> b = new ArrayList<>();
            for (int i = n; i >= 1; i--) b.add(BostaSearchItems.item(prefix + String.format("%05d", i), 10, at));
            newestFirst.addAll(0, b);
        }

        List<Integer> pagesRequested() {
            return requests.stream().map(r -> Integer.parseInt(r.split(":")[0])).toList();
        }
    }

    private final Map<String, FakeSearch> accounts = new ConcurrentHashMap<>();
    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stubBosta() {
        reset(bostaGateway, bostaV2);
        accounts.clear();
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenAnswer(inv -> {
            FakeSearch l = accounts.get((String) inv.getArgument(0));
            return l == null ? List.of() : l.page(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        });
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            ObjectNode raw = mapper.createObjectNode();
            raw.put("trackingNumber", tn);
            raw.put("updatedAt", "2026-10-03T10:00:00.000Z");
            raw.putObject("type").put("code", 10).put("value", "Send");
            raw.putObject("state").put("code", 10);
            return new BostaDelivery(tn, 10, "SEND", 0, "REF-" + tn, null, raw);
        });
    }

    @AfterEach
    void deactivate() {
        for (UUID t : tenants) jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", t);
        tenants.clear();
    }

    @Test
    void v1_sameSecondBatchOf120_allDiscovered_markAdvanced() {
        UUID t = tenant("v1");
        Instant at = hoursAgo(1);
        accounts.get("key-v1").batch("71", 120, at);

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(120);
        assertThat(accounts.get("key-v1").requests)
            .as("newest created first, 50 per page; page 3 (20 items) is short — the end of the list")
            .containsExactly("1:50:-createdAt", "2:50:-createdAt", "3:50:-createdAt");
        assertThat(walkPage(t)).as("complete").isNull();
        assertThat(markAt(t)).isEqualTo(at);
    }

    @Test
    void v2_backlogDeeperThanCap_markKept_nextRunResumesAndCompletes() {
        UUID t = tenant("v2");
        Instant at = hoursAgo(2);
        accounts.get("key-v2").batch("72", 600, at);   // 12 pages > cap 10

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(500);
        assertThat(markAt(t)).as("cap hit — the mark stays the first run's seed").isBefore(at);
        assertThat(walkPage(t)).isEqualTo(11);

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(600);
        assertThat(walkPage(t)).as("complete").isNull();
        assertThat(markAt(t)).as("advanced after the complete walk").isEqualTo(at);
    }

    @Test
    void v3_newDeliveriesDuringAnInterruptedWalk_nothingLost() {
        UUID t = tenant("v3");
        Instant at = hoursAgo(3);
        FakeSearch l = accounts.get("key-v3");
        l.batch("74", 520, at);
        discoveryPollJob.discoverAll();
        assertThat(walkPage(t)).isEqualTo(11);

        l.batch("75", 60, at.plusSeconds(600));   // 60 new on top, the walk's pages shift by 1.2
        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(580);
        assertThat(walkPage(t)).isNull();
        assertThat(markAt(t)).isEqualTo(at.plusSeconds(600));
    }

    @Test
    void v4_repeatGuard_bostaIgnoresPage_stopsWithoutMovingTheMark() {
        UUID t = tenant("v4");
        Instant mark = hoursAgo(5);
        setMark(t, mark);
        FakeSearch l = accounts.get("key-v4");
        l.batch("76", 120, hoursAgo(1));
        l.ignoresPage = true;

        discoveryPollJob.discoverAll();

        assertThat(l.pagesRequested()).as("stops at the first repeated page").containsExactly(1, 2);
        assertThat(markAt(t)).as("mark not moved").isEqualTo(mark);
        assertThat(walkPage(t)).as("no walk position saved").isNull();
        assertThat(discovered(t)).as("page 1's deliveries").isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ?", Integer.class, t))
            .as("once each").isEqualTo(50);
    }

    @Test
    void v5_repeatGuard_pageOfNothingNew_stopsWithoutMovingTheMark() {
        UUID t = tenant("v5");
        Instant mark = hoursAgo(5);
        setMark(t, mark);
        FakeSearch l = accounts.get("key-v5");
        l.batch("77", 120, hoursAgo(1));
        l.page2RepeatsPage1 = true;

        discoveryPollJob.discoverAll();

        assertThat(l.pagesRequested()).containsExactly(1, 2);
        assertThat(markAt(t)).isEqualTo(mark);
        assertThat(walkPage(t)).isNull();
    }

    @Test
    void v6_walkStateReset_firstV2RunStartsAtPage1_andWalksBackToTheMark() throws Exception {
        UUID t = tenant("v6");
        Instant mark = hoursAgo(4);
        FakeSearch l = accounts.get("key-v6");
        l.batch("78", 30, hoursAgo(5));    // before the mark (minus overlap): where the walk stops
        l.batch("79", 70, hoursAgo(2));    // after the mark: new
        // An interrupted V133 (v0, 10 per page) walk: page 31, begun after everything listed here.
        jdbc.update("UPDATE courier_accounts SET discovery_mark_at = ?, discovery_walk_page = 31, " +
            "discovery_walk_newest_at = ? WHERE tenant_id = ?", Timestamp.from(mark), Timestamp.from(hoursAgo(1)), t);

        // What V134 does at deploy.
        jdbc.execute(new String(new ClassPathResource("db/migration/V134__bosta_discovery_v2_walk_reset.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        discoveryPollJob.discoverAll();

        assertThat(l.pagesRequested()).as("pages 1 and 2 — never the v0 walk's page").containsExactly(1, 2);
        assertThat(discovered(t)).as("the 70 after the mark, and page 2's older 30 (idempotent)").isEqualTo(100);
        assertThat(walkPage(t)).isNull();
        assertThat(markAt(t)).isEqualTo(hoursAgo(2));
    }

    @Test
    void v7_listItemUsedDirectly_noPerDeliveryFetch_fieldsReachTheEvent() throws Exception {
        UUID t = tenant("v7");
        Instant created = hoursAgo(1);
        ObjectNode item = BostaSearchItems.item("1177840993", 24, created, "2026-10-03T14:16:49.635Z", "BRK-44871-EG");
        item.put("uniqueBusinessReference", "BRK-44871-EG-1");
        item.putObject("shopifyInfo").put("orderId", "18914721661207");
        accounts.get("key-v7").newestFirst.add(item);

        discoveryPollJob.discoverAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        JsonNode payload = mapper.readTree(jdbc.queryForObject(
            "SELECT payload::text FROM webhook_events WHERE tenant_id = ? AND source::text = 'bosta_poll_discovery'",
            String.class, t));
        assertThat(payload.path("trackingNumber").asText()).isEqualTo("1177840993");
        assertThat(payload.path("state").asInt()).isEqualTo(24);
        assertThat(payload.path("type").asText()).isEqualTo("SEND");
        assertThat(payload.path("updatedAt").asText()).isEqualTo("2026-10-03T14:16:49.635Z");
        assertThat(payload.path("businessReference").asText()).isEqualTo("BRK-44871-EG");
        assertThat(payload.path("uniqueBusinessReference").asText()).isEqualTo("BRK-44871-EG-1");
        assertThat(payload.path("shopifyOrderId").asText()).isEqualTo("18914721661207");
        assertThat(payload.path("creationTimestamp").asLong()).isEqualTo(created.toEpochMilli());
    }

    @Test
    void v8_itemWithoutWhatIngestNeeds_fallsBackToOneFetch() {
        UUID t = tenant("v8");
        FakeSearch l = accounts.get("key-v8");
        l.newestFirst.add(BostaSearchItems.item("5829813860", 24, hoursAgo(1)));         // complete
        l.newestFirst.add(BostaSearchItems.needsFetch("9432163061", 24, hoursAgo(1)));   // no updatedAt
        l.newestFirst.add(BostaSearchItems.item("2251220237", 999, hoursAgo(1)));        // unmapped state

        discoveryPollJob.discoverAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("5829813860"));
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("9432163061"));
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("2251220237"));
        assertThat(discovered(t)).as("all three ingested").isEqualTo(3);
    }

    @Test
    void v9_crossTenant_capAndMarkAreIndependent() {
        UUID a = tenant("v9a");
        UUID b = tenant("v9b");
        Instant atB = base.minus(30, ChronoUnit.MINUTES);
        accounts.get("key-v9a").batch("80", 600, hoursAgo(1));
        accounts.get("key-v9b").batch("81", 5, atB);

        discoveryPollJob.discoverAll();

        assertThat(walkPage(a)).isEqualTo(11);
        assertThat(walkPage(b)).isNull();
        assertThat(markAt(b)).isEqualTo(atB);
        assertThat(markAt(a)).isBefore(atB);
        assertThat(discovered(b)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? " +
            "AND payload->>'trackingNumber' LIKE '81%'", Integer.class, a)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? " +
            "AND payload->>'trackingNumber' LIKE '80%'", Integer.class, b)).isZero();
    }

    @Test
    void v10_searchPageRateLimited_walkStops_markAndStateUnchanged() {
        UUID t = tenant("v10");
        Instant mark = hoursAgo(5);
        setMark(t, mark);
        FakeSearch l = accounts.get("key-v10");
        l.batch("82", 120, hoursAgo(1));
        l.rateLimitedOnPage = 2;

        discoveryPollJob.discoverAll();

        assertThat(markAt(t)).isEqualTo(mark);
        assertThat(walkPage(t)).isNull();
        assertThat(discovered(t)).as("page 1 ingested; the rest next run").isEqualTo(50);

        l.rateLimitedOnPage = null;
        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(120);
        assertThat(markAt(t)).isEqualTo(hoursAgo(1));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Whole seconds — Postgres keeps microseconds and creationTimestamp is milliseconds. */
    private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private Instant hoursAgo(int h) {
        return base.minus(h, ChronoUnit.HOURS);
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            UUID.randomUUID(), id, name + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name));
        accounts.put("key-" + name, new FakeSearch());
        tenants.add(id);
        return id;
    }

    private void setMark(UUID t, Instant mark) {
        jdbc.update("UPDATE courier_accounts SET discovery_mark_at = ? WHERE tenant_id = ?", Timestamp.from(mark), t);
    }

    private int discovered(UUID t) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT payload->>'trackingNumber') FROM webhook_events " +
            "WHERE tenant_id = ? AND source::text = 'bosta_poll_discovery'", Integer.class, t);
    }

    private Integer walkPage(UUID t) {
        return jdbc.queryForObject("SELECT discovery_walk_page FROM courier_accounts WHERE tenant_id = ?", Integer.class, t);
    }

    private Instant markAt(UUID t) {
        Timestamp ts = jdbc.queryForObject("SELECT discovery_mark_at FROM courier_accounts WHERE tenant_id = ?",
            Timestamp.class, t);
        return ts == null ? null : ts.toInstant();
    }
}
