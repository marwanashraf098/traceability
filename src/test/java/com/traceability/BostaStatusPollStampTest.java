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
 * The status walk's last_polled_at stamp (2026-10-21 write-churn pass): one UPDATE per page, and only for a
 * shipment whose stamp is older than an hour — the walk re-reads each tenant's top page every 3 minutes, and
 * stamping every item rewrote the same wide shipment rows ~480× a day.
 *
 *   ps1 a hot shipment (on the walk's page every cycle) is stamped at most once an hour: a NULL or 2 h old
 *       stamp is written, a 10-minute-old one is left as it is, and a second cycle rewrites no row (xmin)
 *   ps2 the safety net still rotates (one fetch per cycle with max-per-cycle 1, oldest first), and a shipment
 *       the walk shows is kept out of it even when its stamp was the oldest
 *   ps3 a rate limit still skips the stamp — on the walk (the items before the 429 are stamped, the rest not)
 *       and on the safety net's fetch
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaStatusPollStampTest {

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
        r.add("bosta.poll.status-max-per-cycle", () -> "1");
    }

    @Autowired JdbcTemplate       jdbc;
    @Autowired ObjectMapper       mapper;
    @Autowired EncryptionService  encryptionService;
    @Autowired BostaStatusPollJob poll;
    @MockBean  BostaV2Client      bostaV2;
    @MockBean  BostaGateway       bostaGateway;
    @MockBean  JobScheduler       jobScheduler;

    private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Map<String, List<JsonNode>> searches = new ConcurrentHashMap<>();
    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stub() {
        reset(bostaV2, bostaGateway, jobScheduler);
        searches.clear();
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenAnswer(inv -> {
            List<JsonNode> all = searches.getOrDefault((String) inv.getArgument(0), List.of());
            int page = inv.getArgument(1), limit = inv.getArgument(2);
            return all.subList(Math.min(all.size(), (page - 1) * limit), Math.min(all.size(), page * limit));
        });
    }

    @AfterEach
    void off() {
        for (UUID t : tenants) jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", t);
        tenants.clear();
    }

    @Test
    void ps1_hotShipment_stampedAtMostOnceAnHour() {
        UUID t = tenant("ps1");
        shipment(t, "9100000001", null);
        shipment(t, "9100000002", base.minus(10, ChronoUnit.MINUTES));
        shipment(t, "9100000003", base.minus(2, ChronoUnit.HOURS));
        page(t, item("9100000001", 0), item("9100000002", 1), item("9100000003", 2));
        Timestamp tenMinutesAgo = polledAt("9100000002");

        poll.pollAll();

        assertThat(polledAt("9100000001")).as("never stamped → stamped").isNotNull();
        assertThat(polledAt("9100000002")).as("stamped 10 min ago → left as it is").isEqualTo(tenMinutesAgo);
        assertThat(polledAt("9100000003").toInstant()).as("stamped 2 h ago → rewritten")
            .isAfter(base.minus(1, ChronoUnit.MINUTES));
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());

        Map<String, String> xmin = xmin("9100000001", "9100000002", "9100000003");
        poll.pollAll();   // the same page again, as every 3 minutes in prod
        assertThat(xmin("9100000001", "9100000002", "9100000003")).as("no shipment row rewritten").isEqualTo(xmin);
    }

    @Test
    void ps2_safetyNetStillRotates_walkedShipmentKeptOut() {
        UUID t = tenant("ps2");
        shipment(t, "9200000001", base.minus(7, ChronoUnit.HOURS));   // the oldest stamp — but on the walk's page
        shipment(t, "9200000002", base.minus(6, ChronoUnit.HOURS));
        shipment(t, "9200000003", base.minus(5, ChronoUnit.HOURS));
        page(t, item("9200000001", 0));
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv ->
            v0((String) inv.getArgument(1)));

        poll.pollAll();
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("9200000002"));
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("9200000003"));

        poll.pollAll();
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("9200000003"));

        poll.pollAll();
        verify(bostaGateway, times(2)).fetchDelivery(anyString(), anyString());   // both fresh now: nothing more
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("9200000001"));
        assertThat(polledAt("9200000001").toInstant()).as("the walk stamped it").isAfter(base.minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void ps3_rateLimit_skipsTheStamp_onTheWalkAndOnTheSafetyNet() {
        UUID t = tenant("ps3");
        shipment(t, "9300000001", null);
        shipment(t, "9300000002", null);
        shipment(t, "9300000003", null);
        ObjectNode needsFetch = item("9300000002", 1);
        needsFetch.remove("updatedAt");   // incomplete list item → one v0 fetch, which Bosta rate limits
        page(t, item("9300000001", 0), needsFetch, item("9300000003", 2));
        when(bostaGateway.fetchDelivery(anyString(), eq("9300000002"))).thenThrow(new BostaRateLimitException(30));

        poll.pollAll();

        assertThat(polledAt("9300000001")).as("checked before the 429").isNotNull();
        assertThat(polledAt("9300000002")).as("the rate-limited item").isNull();
        assertThat(polledAt("9300000003")).as("not reached").isNull();

        UUID u = tenant("ps3b");
        Instant stale = base.minus(5, ChronoUnit.HOURS);
        shipment(u, "9300000004", stale);
        when(bostaGateway.fetchDelivery(anyString(), eq("9300000004"))).thenThrow(new BostaRateLimitException(30));

        poll.pollAll();

        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("9300000004"));
        assertThat(polledAt("9300000004").toInstant()).as("safety net 429 → not stamped").isEqualTo(stale);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, id, name + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name + "-" + id));
        searches.put("key-" + name + "-" + id, new ArrayList<>());
        tenants.add(id);
        return id;
    }

    private void page(UUID t, JsonNode... items) {
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, t);
        searches.get("key-" + name + "-" + t).addAll(List.of(items));
    }

    private void shipment(UUID t, String tn, Instant lastPolled) {
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, t);
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class, t, store, "gid://shopify/Order/" + tn, "ORD-" + tn);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, raw, last_polled_at) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, '{\"numberOfAttempts\":0}'::jsonb, ?)",
            t, order, tn, lastPolled == null ? null : Timestamp.from(lastPolled));
    }

    private ObjectNode item(String tn, int secondsAgo) {
        ObjectNode i = BostaSearchItems.item(tn, 41, base.minus(3, ChronoUnit.DAYS),
            base.minusSeconds(secondsAgo).toString(), "ORD-" + tn);
        i.put("createdAt", base.minus(3, ChronoUnit.DAYS).toString());
        return i;
    }

    private BostaDelivery v0(String tn) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.put("updatedAt", "2026-10-04T08:00:00.000Z");
        raw.put("numberOfAttempts", 0);
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 41);
        return BostaDelivery.fromRaw(tn, raw);
    }

    private Timestamp polledAt(String tn) {
        return jdbc.queryForObject("SELECT last_polled_at FROM shipments WHERE tracking_number = ?", Timestamp.class, tn);
    }

    private Map<String, String> xmin(String... tns) {
        Map<String, String> out = new TreeMap<>();
        for (String tn : tns) {
            out.put(tn, jdbc.queryForObject("SELECT xmin::text FROM shipments WHERE tracking_number = ?", String.class, tn));
        }
        return out;
    }
}
