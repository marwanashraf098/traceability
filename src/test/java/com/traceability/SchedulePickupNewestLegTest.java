package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaPickupService;
import com.traceability.inventory.WaybillResolver;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Schedule pickup (2026-11-04 fix): a scheduled pickup includes a forward shipment only when it is the order's NEWEST
 * forward leg and still waits for the courier (OrderLineFreeze.prePickupWaybillSql) — the stored 'awaiting_pickup'
 * alone is not enough (BRK-46639-EG: its cancelled old waybill and its delivered new one both joined the pickup).
 * The service runs on an app_user connection (RLS on).
 *
 *   s1  one schedule (Traced-managed): a normal created leg is included; a cancelled newest leg, a delivered newest
 *       leg, a leg that moved and was rewound to 'created', and an OLDER created leg behind a newer (cancelled) one
 *       are not —
 *       manifest, parcel count, COD total and the count sent to Bosta createPickup all agree
 *   x1  cross-tenant as app_user: tenant B's schedule holds only B's waybill (positive control), never A's
 *   w1  waybill mode wording: an awaiting_pickup order whose newest waybill already moved says "already shipped"
 *       (same ALREADY_PACKED code, subReason ALREADY_SHIPPED); one still waiting says "already packed"
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulePickupNewestLegTest {

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

    static final ZoneId    CAIRO    = ZoneId.of("Africa/Cairo");
    static final Instant   PINNED   = LocalDate.of(2026, 6, 17).atTime(10, 0).atZone(CAIRO).toInstant();   // a Wednesday
    static final LocalDate THURSDAY = LocalDate.of(2026, 6, 18);

    @Autowired JdbcTemplate      jdbc;   // postgres — fixtures only
    @Autowired EncryptionService encryptionService;
    @Autowired ObjectMapper      mapper;
    @Autowired WaybillResolver   resolver;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;
    @MockBean  Clock             clock;

    BostaPickupService service;   // on app_user
    final AtomicLong seq = new AtomicLong(5_550_000_000L);

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        service = new BostaPickupService(new JdbcTemplate(appDs), new DataSourceTransactionManager(appDs), bostaGateway,
            encryptionService, mapper, clock);
    }

    @BeforeEach
    void stubs() {
        reset(bostaGateway);
        when(clock.instant()).thenReturn(PINNED);
        when(clock.getZone()).thenReturn(CAIRO);
        when(bostaGateway.createPickup(any(), any(), any(), any(), anyInt())).thenReturn("PU-1");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    record T(UUID tenant, UUID store, UUID user) {}

    @Test
    void s1_onlyTheNewestWaybillStillWaitingForTheCourier_isScheduled() {
        T t = tenant("s1");
        UUID normal = order(t, "100.00");
        String normalTn = leg(t, normal, "created", 0, null);
        UUID cancelledNewest = order(t, "200.00");
        leg(t, cancelledNewest, "cancelled", 0, null);
        UUID delivered = order(t, "300.00");                                     // BRK-46639-EG's shape
        leg(t, delivered, "cancelled", 2, null);
        leg(t, delivered, "delivered", 0, "delivered");
        UUID rewound = order(t, "400.00");
        leg(t, rewound, "created", 0, "with_courier");                           // moved, then a webhook rewound it
        // Only one non-cancelled forward leg can exist per order (ux_active_forward_shipment_per_order), so "newest
        // only" bites alone when an OLDER leg is still 'created' and the newest was cancelled: neither goes.
        UUID olderLive = order(t, "500.00");
        leg(t, olderLive, "created", 2, null);
        leg(t, olderLive, "cancelled", 0, null);

        BostaPickupService.PickupManifest m = TenantContext.runAs(t.tenant(), () -> service.schedulePickup(t.tenant(), THURSDAY));

        assertThat(m.shipments()).extracting(BostaPickupService.ManifestLine::trackingNumber)
            .containsExactly(normalTn);
        assertThat(m.parcelCount()).isEqualTo(1);
        assertThat(m.totalCod()).isEqualByComparingTo(new BigDecimal("100.00"));
        verify(bostaGateway).createPickup(any(), any(), any(), any(), eq(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_shipments WHERE tenant_id = ?", Integer.class, t.tenant()))
            .isEqualTo(1);
    }

    @Test
    void x1_crossTenant_appUser_onlyOwnWaybills_positiveControl() {
        T a = tenant("xa"), b = tenant("xb");
        String aTn = leg(a, order(a, "10.00"), "created", 0, null);
        String bTn = leg(b, order(b, "20.00"), "created", 0, null);

        BostaPickupService.PickupManifest mb = TenantContext.runAs(b.tenant(), () -> service.schedulePickup(b.tenant(), THURSDAY));
        assertThat(mb.shipments()).extracting(BostaPickupService.ManifestLine::trackingNumber).containsExactly(bTn);
        BostaPickupService.PickupManifest ma = TenantContext.runAs(a.tenant(), () -> service.schedulePickup(a.tenant(), THURSDAY));
        assertThat(ma.shipments()).as("positive control").extracting(BostaPickupService.ManifestLine::trackingNumber)
            .containsExactly(aTn);
    }

    @Test
    void w1_waybillMode_newestLegMoved_saysAlreadyShipped_otherwiseAlreadyPacked() {
        T t = tenant("w1");
        UUID shipped = order(t, "0");
        leg(t, shipped, "cancelled", 2, null);
        String shippedTn = leg(t, shipped, "delivered", 0, "delivered");
        UUID packed = order(t, "0");
        String packedTn = leg(t, packed, "created", 0, null);

        WaybillResolver.Resolution r1 = TenantContext.runAs(t.tenant(), () -> resolver.resolve(shippedTn, t.user()));
        assertThat(r1.code()).isEqualTo(WaybillResolver.Code.ALREADY_PACKED);
        assertThat(r1.detail()).isEqualTo(WaybillResolver.ALREADY_SHIPPED_DETAIL);
        assertThat(r1.messageEn()).contains("has already shipped").doesNotContain("already packed");

        WaybillResolver.Resolution r2 = TenantContext.runAs(t.tenant(), () -> resolver.resolve(packedTn, t.user()));
        assertThat(r2.code()).isEqualTo(WaybillResolver.Code.ALREADY_PACKED);
        assertThat(r2.detail()).isNull();
        assertThat(r2.messageEn()).contains("already packed");
    }

    @Test
    void o1_opsScript_listsWhatTheOldRuleTookAndTheNewRuleDoesNot() throws Exception {
        T t = tenant("o1");
        UUID brk = order(t, "300.00");                                           // BRK-46639-EG's shape
        String oldTn = leg(t, brk, "cancelled", 2, null);
        String newTn = leg(t, brk, "delivered", 0, "delivered");
        String fineTn = leg(t, order(t, "50.00"), "created", 0, null);          // the new rule still takes it
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/ops/2026-11-04-schedule-pickup-affected.sql"));
        String body = java.util.Arrays.stream(sql.split("\n")).filter(l -> !l.trim().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
        java.util.List<java.util.Map<String, Object>> rows = null;
        for (String stmt : body.split(";")) {
            if (stmt.isBlank()) continue;
            if (stmt.trim().toUpperCase().startsWith("SET ")) { jdbc.execute(stmt); continue; }
            assertThat(stmt.toUpperCase()).doesNotContain("UPDATE ").doesNotContain("DELETE ").doesNotContain("INSERT ");
            rows = jdbc.queryForList(stmt);
        }
        String tenantName = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, t.tenant());
        assertThat(rows).filteredOn(r -> tenantName.equals(r.get("tenant")))
            .extracting(r -> r.get("tracking_number")).containsExactlyInAnyOrder(oldTn, newTn).doesNotContain(fineTn);
    }

    // ── fixtures (postgres) ───────────────────────────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), user = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + tag);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
            "pickup_mode, awb_format, awb_lang, pickup_business_location_id) " +
            "VALUES (?, 'bosta', ?, 'h', 'active', 'TRACED_MANAGED', 'A4', 'ar', 'loc-1')",
            tenant, encryptionService.encrypt("key-" + tag));
        return new T(tenant, store, user);
    }

    private UUID order(T t, String cod) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, " +
            "cod_amount, placed_at) VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, 'cod', ?, now()) RETURNING id",
            UUID.class, t.tenant(), t.store(), "EXT-" + UUID.randomUUID(), "#SP" + seq.incrementAndGet(), new BigDecimal(cod));
    }

    /** A forward leg created daysAgo days ago in the given live state; historyState (if any) is a history row. */
    private String leg(T t, UUID order, String state, int daysAgo, String historyState) {
        String tn = String.valueOf(seq.incrementAndGet());
        UUID id = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, tracking_number, internal_state, cod_amount, " +
            "shipment_leg, created_at) VALUES (?, ?, ?, ?::shipment_internal_state, " +
            "(SELECT cod_amount FROM orders WHERE id = ?), 'forward', now() - make_interval(days => ?)) RETURNING id",
            UUID.class, t.tenant(), order, tn, state, order, daysAgo);
        if (historyState != null) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                "VALUES (?, ?, ?, now() - interval '1 hour')", t.tenant(), id, historyState);
        }
        return tn;
    }
}
