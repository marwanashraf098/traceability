package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.portal.PickupAreaService;
import com.traceability.portal.ReturnPickupBookingService;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bosta status poll on the v2 search sorted "-updatedAt" (2026-10-04, V138), and (b): building the delivery
 * from the list item for Traced's own events.
 *
 *   sw1 120 changed in-flight shipments: one walk (pages of 50), all 120 ingested, no v0 fetch, mark advanced
 *   sw2 more than the page cap (180 changes, cap 3 pages): the mark does NOT move, the walk resumes next
 *       cycle and completes
 *   sw3 a page rate limited mid-walk: mark and walk state unchanged
 *   sw4 repeat-page guard (Bosta ignoring page): stop, mark kept, nothing saved
 *   sw5 only in-flight shipments are ingested — not delivered ones, not deliveries Traced doesn't track
 *   sw6 safety net: an in-flight shipment unchecked for > 4 h is fetched; one checked 1 h ago is not
 *   sw7 the poll's own event is applied from the list item (no fetch; raw marked v2-list; exception code from
 *       state.lastExceptionCode); a real Bosta webhook still fetches; a list item with a new attempt fetches
 *   sw8 lazy v0: the pickup-area lookup and the booking job fetch Bosta's v0 delivery (user-facing) when the
 *       forward leg holds a v2 copy
 *   sw9 cross-tenant: a walk never ingests another tenant's shipment
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaStatusPollWalkTest {

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
        r.add("bosta.poll.status-max-pages", () -> "3");
    }

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired EncryptionService          encryptionService;
    @Autowired BostaStatusPollJob         poll;
    @Autowired BostaWebhookJob            webhookJob;
    @Autowired PickupAreaService          pickupAreas;
    @Autowired ReturnPickupBookingService booking;
    @MockBean  BostaV2Client              bostaV2;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /** One tenant's search, newest updated first. */
    static final class FakeSearch {
        final List<ObjectNode> newestFirst = new ArrayList<>();
        boolean ignoresPage;
        Integer rateLimitedOnPage;
        final List<String> requests = new CopyOnWriteArrayList<>();

        List<JsonNode> page(int page, int limit, String sortBy) {
            requests.add(page + ":" + sortBy);
            if (rateLimitedOnPage != null && rateLimitedOnPage == page) throw new BostaRateLimitException(30);
            int p = ignoresPage ? 1 : page;
            List<JsonNode> out = new ArrayList<>();
            for (int i = (p - 1) * limit; i < Math.min(newestFirst.size(), p * limit); i++) out.add(newestFirst.get(i));
            return out;
        }
    }

    private final Map<String, FakeSearch> searches = new ConcurrentHashMap<>();
    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void stub() {
        reset(bostaV2, bostaGateway, jobScheduler);
        searches.clear();
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenAnswer(inv -> {
            FakeSearch f = searches.get((String) inv.getArgument(0));
            return f == null ? List.of() : f.page(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        });
    }

    @AfterEach
    void off() {
        for (UUID t : tenants) jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", t);
        tenants.clear();
    }

    @Test
    void sw1_120Changes_oneWalk_allIngested_noFetch() {
        T t = tenant("sw1");
        for (int i = 0; i < 120; i++) {
            String tn = "81" + String.format("%08d", i);
            shipment(t, tn, "with_courier", null);
            t.search.newestFirst.add(item(tn, 41, base.minusSeconds(i)));
        }

        poll.pollAll();

        assertThat(t.search.requests).containsExactly("1:-updatedAt", "2:-updatedAt", "3:-updatedAt");
        assertThat(events(t, "bosta_poll")).isEqualTo(120);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(mark(t)).isEqualTo(base);
        assertThat(walkPage(t)).isNull();
    }

    @Test
    void sw2_overTheCap_markKept_resumesAndCompletes() {
        T t = tenant("sw2");
        for (int i = 0; i < 180; i++) {   // 4 pages, the last one short — the end of the list
            String tn = "82" + String.format("%08d", i);
            shipment(t, tn, "with_courier", null);
            t.search.newestFirst.add(item(tn, 41, base.minusSeconds(i)));
        }

        poll.pollAll();
        assertThat(events(t, "bosta_poll")).isEqualTo(150);
        assertThat(mark(t)).as("cap hit — mark not moved").isBefore(base.minus(1, ChronoUnit.HOURS));
        assertThat(walkPage(t)).isEqualTo(4);

        poll.pollAll();
        assertThat(events(t, "bosta_poll")).isEqualTo(180);
        assertThat(walkPage(t)).isNull();
        assertThat(mark(t)).isEqualTo(base);
    }

    @Test
    void sw3_rateLimitedMidWalk_stateUnchanged() {
        T t = tenant("sw3");
        setMark(t, base.minus(2, ChronoUnit.HOURS));
        for (int i = 0; i < 80; i++) {
            String tn = "83" + String.format("%08d", i);
            shipment(t, tn, "with_courier", null);
            t.search.newestFirst.add(item(tn, 41, base.minusSeconds(i)));
        }
        t.search.rateLimitedOnPage = 2;

        poll.pollAll();

        assertThat(mark(t)).isEqualTo(base.minus(2, ChronoUnit.HOURS));
        assertThat(walkPage(t)).isNull();
        assertThat(events(t, "bosta_poll")).isEqualTo(50);
    }

    @Test
    void sw4_repeatGuard() {
        T t = tenant("sw4");
        setMark(t, base.minus(2, ChronoUnit.HOURS));
        for (int i = 0; i < 120; i++) {
            String tn = "84" + String.format("%08d", i);
            shipment(t, tn, "with_courier", null);
            t.search.newestFirst.add(item(tn, 41, base.minusSeconds(i)));
        }
        t.search.ignoresPage = true;

        poll.pollAll();

        assertThat(t.search.requests).containsExactly("1:-updatedAt", "2:-updatedAt");
        assertThat(mark(t)).isEqualTo(base.minus(2, ChronoUnit.HOURS));
        assertThat(walkPage(t)).isNull();
    }

    @Test
    void sw5_onlyInFlightIngested() {
        T t = tenant("sw5");
        shipment(t, "8500000001", "with_courier", null);
        shipment(t, "8500000002", "delivered", null);
        t.search.newestFirst.add(item("8500000001", 45, base));
        t.search.newestFirst.add(item("8500000002", 45, base.minusSeconds(1)));
        t.search.newestFirst.add(item("8500000003", 10, base.minusSeconds(2)));   // not Traced's (discovery's job)

        poll.pollAll();

        assertThat(eventsFor(t, "8500000001")).isEqualTo(1);
        assertThat(eventsFor(t, "8500000002")).isZero();
        assertThat(eventsFor(t, "8500000003")).isZero();
    }

    @Test
    void sw6_safetyNet_fetchesOnlyStaleShipments() {
        T t = tenant("sw6");
        shipment(t, "8600000001", "with_courier", base.minus(5, ChronoUnit.HOURS));   // stale
        shipment(t, "8600000002", "with_courier", base.minus(1, ChronoUnit.HOURS));   // checked recently
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv ->
            v0((String) inv.getArgument(1), 41, 0, "2026-10-04T08:00:00.000Z"));

        poll.pollAll();

        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("8600000001"));
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("8600000002"));
    }

    @Test
    void sw7_ownEventFromListItem_realWebhookFetches_newAttemptFetches() throws Exception {
        T t = tenant("sw7");
        shipment(t, "8700000001", "with_courier", null);
        shipment(t, "8700000002", "with_courier", null);
        ObjectNode exception = item("8700000001", 47, base);
        ((ObjectNode) exception.path("state")).put("lastExceptionCode", 21);
        ObjectNode attempted = item("8700000002", 47, base.minusSeconds(1));
        attempted.put("numberOfAttempts", 1);
        t.search.newestFirst.add(exception);
        t.search.newestFirst.add(attempted);
        when(bostaGateway.fetchDelivery(anyString(), eq("8700000002"))).thenReturn(v0("8700000002", 47, 1, "2026-10-04T09:00:00.000Z"));

        poll.pollAll();
        webhookJob.process(eventId(t, "8700000001", "bosta_poll"), t.id);
        webhookJob.process(eventId(t, "8700000002", "bosta_poll"), t.id);

        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("8700000001"));
        Map<String, Object> s1 = jdbc.queryForMap("SELECT provider_state, exception_code, raw->>'_tracedRawShape' AS shape " +
            "FROM shipments WHERE tracking_number = '8700000001'");
        assertThat(s1).containsEntry("provider_state", 47).containsEntry("exception_code", 21).containsEntry("shape", "v2-list");
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("8700000002"));   // a new attempt → v0

        // A real Bosta webhook for the first one still verifies by fetch.
        when(bostaGateway.fetchDelivery(anyString(), eq("8700000001"))).thenReturn(v0("8700000001", 45, 1, "2026-10-04T10:00:00.000Z"));
        Long real = jdbc.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id", Long.class,
            t.id, "{\"trackingNumber\":\"8700000001\",\"state\":45,\"updatedAt\":\"2026-10-04T10:00:00.000Z\"}");
        webhookJob.process(real, t.id);
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("8700000001"));
    }

    @Test
    void sw8_lazyV0_pickupAreaAndBooking() {
        T t = tenant("sw8");
        UUID order = shipment(t, "8800000001", "delivered", null);
        jdbc.update("UPDATE shipments SET delivered_at = now(), raw = ?::jsonb WHERE tracking_number = '8800000001'",
            "{\"trackingNumber\":\"8800000001\",\"_tracedRawShape\":\"v2-list\",\"dropOffAddress\":{}}");
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, district_name, pickup_available, dropoff_available) " +
            "VALUES ('D-SW8', 'C-SW8', 'Cairo', 'Maadi', true, true) ON CONFLICT DO NOTHING");
        AtomicReference<BostaRateLimiter.Priority> priority = new AtomicReference<>();
        when(bostaGateway.fetchDelivery(anyString(), eq("8800000001"))).thenAnswer(inv -> {
            priority.set(BostaRateLimiter.priorityOverride());
            ObjectNode raw = mapper.createObjectNode().put("trackingNumber", "8800000001");
            raw.putObject("dropOffAddress").putObject("city").put("_id", "C-SW8");
            raw.putObject("state").put("code", 45);
            return BostaDelivery.fromRaw("8800000001", raw);
        });

        Optional<PickupAreaService.CityAreas> areas = com.traceability.tenancy.TenantContext.runAs(t.id,
            () -> pickupAreas.forOrder(t.id, order));

        assertThat(areas).isPresent();
        assertThat(priority.get()).as("user-facing").isEqualTo(BostaRateLimiter.Priority.USER_FACING);
        assertThat(jdbc.queryForObject("SELECT raw->>'_tracedRawShape' FROM shipments WHERE tracking_number = '8800000001'",
            String.class)).as("replaced by the v0 copy").isNull();

        // Booking: the forward leg is refreshed before the booking reads its address block.
        jdbc.update("UPDATE shipments SET raw = ?::jsonb WHERE tracking_number = '8800000001'",
            "{\"trackingNumber\":\"8800000001\",\"_tracedRawShape\":\"v2-list\",\"dropOffAddress\":{}}");
        UUID request = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at) " +
            "VALUES (?, ?, 'refund', 'approved', ?, now()) RETURNING id", UUID.class, t.id, order, reference());
        clearInvocations(bostaGateway);
        booking.book(request, t.id);
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("8800000001"));
    }

    @Test
    void sw9_crossTenant_neverIngestsAnotherTenantsShipment() {
        T a = tenant("sw9a");
        T b = tenant("sw9b");
        shipment(b, "8900000001", "with_courier", base);   // B's, checked just now
        a.search.newestFirst.add(item("8900000001", 45, base));   // (artificially) on A's list

        poll.pollAll();

        assertThat(eventsFor(a, "8900000001")).isZero();
        assertThat(eventsFor(b, "8900000001")).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A return-request reference in the V102 format (RR- + 6 of 2-9 / A-Z without I, O). */
    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder b = new StringBuilder("RR-");
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 6; i++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return b.toString();
    }

    record T(UUID id, UUID store, String key, FakeSearch search) {}

    private T tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, id, name + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name));
        FakeSearch f = new FakeSearch();
        searches.put("key-" + name, f);
        tenants.add(id);
        return new T(id, store, "key-" + name, f);
    }

    private UUID shipment(T t, String tn, String state, Instant lastPolled) {
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class, t.id, t.store, "gid://shopify/Order/" + tn, "ORD-" + tn);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, raw, last_polled_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, '{\"numberOfAttempts\":0}'::jsonb, ?)",
            t.id, order, tn, state, lastPolled == null ? null : Timestamp.from(lastPolled));
        return order;
    }

    private ObjectNode item(String tn, int state, Instant updatedAt) {
        ObjectNode i = BostaSearchItems.item(tn, state, base.minus(3, ChronoUnit.DAYS), updatedAt.toString(), "ORD-" + tn);
        i.put("createdAt", base.minus(3, ChronoUnit.DAYS).toString());
        return i;
    }

    private BostaDelivery v0(String tn, int state, int attempts, String updatedAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.put("updatedAt", updatedAt);
        raw.put("numberOfAttempts", attempts);
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", state);
        return BostaDelivery.fromRaw(tn, raw);
    }

    private int events(T t, String source) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT payload->>'trackingNumber') FROM webhook_events " +
            "WHERE tenant_id = ? AND source::text = ?", Integer.class, t.id, source);
    }

    private int eventsFor(T t, String tn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ?",
            Integer.class, t.id, tn);
    }

    private Long eventId(T t, String tn, String source) {
        return jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "AND source::text = ?", Long.class, t.id, tn, source);
    }

    private void setMark(T t, Instant mark) {
        jdbc.update("UPDATE courier_accounts SET poll_mark_at = ? WHERE tenant_id = ?", Timestamp.from(mark), t.id);
    }

    private Instant mark(T t) {
        Timestamp ts = jdbc.queryForObject("SELECT poll_mark_at FROM courier_accounts WHERE tenant_id = ?", Timestamp.class, t.id);
        return ts == null ? null : ts.toInstant();
    }

    private Integer walkPage(T t) {
        return jdbc.queryForObject("SELECT poll_walk_page FROM courier_accounts WHERE tenant_id = ?", Integer.class, t.id);
    }
}
