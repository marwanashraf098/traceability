package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
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
 * Discovery paging (V133, 2026-10-03). A fake Bosta list that — like prod — returns at most 10
 * items per page whatever pageSize asks for.
 *
 *   pg1 pages offset by the REQUESTED page size (prod's observed behaviour): a 45-delivery batch → all 45
 *   pg2 pages offset by the RETURNED size → all 45
 *   pg3 a backlog deeper than the page cap: the mark is NOT advanced, the walk stores where it stopped;
 *       the next run resumes, finds the rest, completes and advances the mark
 *   pg4 deliveries created while a walk is interrupted: the next run takes the new head first, then
 *       continues the walk shifted by them — nothing lost
 *   pg5 overlap / dedup: a delivery repeated across pages (list shifted mid-walk) is fetched once
 *   pg6 cross-tenant: one tenant's capped walk and mark never touch another's
 *   pg7 a page shorter than requested mid-list is recorded once per tenant per day
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
    @MockBean  JobScheduler          jobScheduler;

    enum Offset { REQUESTED, RETURNED }

    /** One tenant's Bosta account: deliveries newest first, pages capped at 10. */
    static final class FakeList {
        final List<BostaGateway.SlimDelivery> newestFirst = new ArrayList<>();
        final Offset offset;
        final Map<Integer, Integer> shortPages = new HashMap<>();   // page → item count
        final Map<Integer, String>  repeatOnPage = new HashMap<>(); // page → tracking repeated at its top
        FakeList(Offset offset) { this.offset = offset; }

        List<BostaGateway.SlimDelivery> page(int page, int size) {
            int per = Math.min(10, size);
            int from = (page - 1) * (offset == Offset.REQUESTED ? size : per);
            int count = shortPages.getOrDefault(page, per);
            List<BostaGateway.SlimDelivery> out = new ArrayList<>();
            String repeat = repeatOnPage.get(page);
            if (repeat != null) newestFirst.stream().filter(d -> d.trackingNumber().equals(repeat)).findFirst().ifPresent(out::add);
            for (int i = from; i < Math.min(newestFirst.size(), from + count); i++) out.add(newestFirst.get(i));
            return out;
        }

        /** Adds n deliveries created at the same second (a Shopify-app batch), newest first, on top. */
        void batch(String prefix, int n, Instant at) {
            List<BostaGateway.SlimDelivery> b = new ArrayList<>();
            for (int i = n; i >= 1; i--) b.add(new BostaGateway.SlimDelivery(prefix + String.format("%05d", i), 10, "SEND", at));
            newestFirst.addAll(0, b);
        }
    }

    private final Map<String, FakeList> accounts = new ConcurrentHashMap<>();
    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stubBosta() {
        reset(bostaGateway);
        accounts.clear();
        when(bostaGateway.listDeliveriesPage(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            FakeList l = accounts.get((String) inv.getArgument(0));
            return l == null ? List.of() : l.page(inv.getArgument(1), inv.getArgument(2));
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
    void pg1_offsetByRequestedSize_batchOf45_allDiscovered() {
        UUID t = tenant("pg1", new FakeList(Offset.REQUESTED));
        accounts.get("key-pg1").batch("71", 45, Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS));

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(45);
        assertThat(walkPage(t)).as("complete").isNull();
    }

    @Test
    void pg2_offsetByReturnedSize_batchOf45_allDiscovered() {
        UUID t = tenant("pg2", new FakeList(Offset.RETURNED));
        accounts.get("key-pg2").batch("72", 45, Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS));

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(45);
    }

    @Test
    void pg3_backlogDeeperThanCap_markKept_nextRunResumesAndCompletes() {
        UUID t = tenant("pg3", new FakeList(Offset.REQUESTED));
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
        accounts.get("key-pg3").batch("73", 450, at);   // 45 pages > cap 30

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(300);
        Instant seed = markAt(t);
        assertThat(seed).as("cap hit — the mark stays the first run's seed").isBefore(at);
        assertThat(walkPage(t)).isEqualTo(31);

        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(450);
        assertThat(walkPage(t)).as("complete").isNull();
        assertThat(markAt(t)).as("advanced after the complete walk").isEqualTo(at);
    }

    @Test
    void pg4_newDeliveriesDuringAnInterruptedWalk_nothingLost() {
        UUID t = tenant("pg4", new FakeList(Offset.REQUESTED));
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(3, ChronoUnit.HOURS);
        FakeList l = accounts.get("key-pg4");
        l.batch("74", 400, at);
        discoveryPollJob.discoverAll();
        assertThat(walkPage(t)).isEqualTo(31);

        l.batch("75", 15, at.plusSeconds(600));   // 15 new on top, the walk's pages shift by 1.5
        discoveryPollJob.discoverAll();

        assertThat(discovered(t)).isEqualTo(415);
        assertThat(walkPage(t)).isNull();
        assertThat(markAt(t)).isEqualTo(at.plusSeconds(600));
    }

    @Test
    void pg5_repeatedAcrossPages_fetchedOnce() {
        UUID t = tenant("pg5", new FakeList(Offset.RETURNED));
        FakeList l = accounts.get("key-pg5");
        l.batch("76", 25, Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS));
        l.repeatOnPage.put(2, l.newestFirst.get(9).trackingNumber());   // page 2 repeats page 1's last

        discoveryPollJob.discoverAll();

        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq(l.newestFirst.get(9).trackingNumber()));
        assertThat(discovered(t)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ?", Integer.class, t))
            .as("one event per delivery").isEqualTo(25);
    }

    @Test
    void pg6_crossTenant_capAndMarkAreIndependent() {
        UUID a = tenant("pg6a", new FakeList(Offset.REQUESTED));
        UUID b = tenant("pg6b", new FakeList(Offset.REQUESTED));
        Instant atB = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(30, ChronoUnit.MINUTES);
        accounts.get("key-pg6a").batch("77", 450, Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS));
        accounts.get("key-pg6b").batch("78", 5, atB);

        discoveryPollJob.discoverAll();

        assertThat(walkPage(a)).isEqualTo(31);
        assertThat(walkPage(b)).isNull();
        assertThat(markAt(b)).isEqualTo(atB);
        assertThat(markAt(a)).isBefore(atB);
        assertThat(discovered(b)).isEqualTo(5);
        verify(bostaGateway, never()).fetchDelivery(eq("key-pg6a"), startsWith("78"));
        verify(bostaGateway, never()).fetchDelivery(eq("key-pg6b"), startsWith("77"));
    }

    @Test
    void pg7_shortPageMidList_recordedOncePerDay() {
        UUID t = tenant("pg7", new FakeList(Offset.RETURNED));
        FakeList l = accounts.get("key-pg7");
        l.batch("79", 30, Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS));
        l.shortPages.put(2, 7);

        discoveryPollJob.discoverAll();

        assertThat(jdbc.queryForObject("SELECT discovery_short_page_logged_on = current_date FROM courier_accounts " +
            "WHERE tenant_id = ?", Boolean.class, t)).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID tenant(String name, FakeList list) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            UUID.randomUUID(), id, name + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name));
        accounts.put("key-" + name, list);
        tenants.add(id);
        return id;
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
