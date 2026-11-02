package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.WaybillResolver;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D7 — a Bosta delivery ended before the courier ever had it (48 Terminated, 49 Canceled, 104 Archived) must not
 * read as "this order shipped". Bosta's history rows are kept exactly as Bosta sent them; only the readers change,
 * through ONE predicate (OrderLineFreeze.movementRowSql): the freeze, FulfillService's ALREADY_SHIPPED guard and
 * WaybillResolver's moved state.
 *
 *   e*  created → 49 / 48 / 104 → a new Bosta leg: not frozen, pick + complete works, the new waybill opens in
 *       waybill mode (the old waybill is still refused as ended)
 *   g1  golden: picked up (with_courier) and then terminated → still frozen, ALREADY_SHIPPED, ALREADY_MOVING (terminated)
 *   g2  golden: a pre-pickup exception (47) still freezes — D7 reads only cancelled / terminated differently
 *   pc  same tenant, an order whose only leg never ended: unchanged
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaEndedLegFreezeTest {

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

    @Autowired JdbcTemplate    jdbc;
    @Autowired FulfillService  fulfill;
    @Autowired WaybillResolver resolver;
    @MockBean  BostaGateway    bostaGateway;
    @MockBean  JobScheduler    jobScheduler;

    @AfterEach void clear() { TenantContext.clear(); }

    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name, boolean waybillMode) {
            f = new PackFixtures(jdbc, name);
            if (waybillMode) jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Dress", "DR-" + name, null);
        }
        UUID order(String number) {
            UUID id = f.order(number, 0);
            f.item(id, variant, 1);
            return id;
        }
    }

    // ── e: ended before it moved → a new waybill packs ─────────────────────────────────────────

    @ParameterizedTest(name = "Bosta {0} → {1}")
    @CsvSource({"49, cancelled", "48, terminated", "104, cancelled"})
    void e_endedBeforeMoving_newLeg_picksAndCompletes(int code, String state) {
        Shop s = new Shop("e" + code, false);
        UUID o = s.order("#50" + code);
        UUID old = bostaLeg(o, "42661" + code + "53", Instant.now().minusSeconds(7200));
        history(old, "created", 10, Instant.now().minusSeconds(7000));
        end(old, state, code, Instant.now().minusSeconds(3600));
        bostaLeg(o, "83848" + code + "26", Instant.now().minusSeconds(600));

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).as("not frozen").isFalse();
        FulfillService.ScanResult scan = fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer);
        assertThat(scan.code()).isNotEqualTo("ALREADY_SHIPPED");
        assertThat(scan.success()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);
        assertThat(historyRows(old)).as("Bosta's history kept as sent").containsExactly("created:10", state + ":" + code);
    }

    @ParameterizedTest(name = "Bosta {0} → {1}, waybill mode")
    @CsvSource({"49, cancelled", "48, terminated", "104, cancelled"})
    void e_endedBeforeMoving_newWaybillOpens_oldStillRefused(int code, String state) {
        Shop s = new Shop("w" + code, true);
        UUID o = s.order("#51" + code);
        String oldTn = "13457" + code + "66";
        UUID old = bostaLeg(o, oldTn, Instant.now().minusSeconds(7200));
        history(old, "created", 10, Instant.now().minusSeconds(7000));
        end(old, state, code, Instant.now().minusSeconds(3600));
        String newTn = "20362" + code + "50";
        bostaLeg(o, newTn, Instant.now().minusSeconds(600));

        TenantContext.set(s.f.tenant);
        WaybillResolver.Resolution r = resolver.resolve(newTn, s.packer);
        assertThat(r.code()).isEqualTo(WaybillResolver.Code.OPEN);
        assertThat(r.orderId()).isEqualTo(o);
        WaybillResolver.Resolution oldScan = resolver.resolve(oldTn, s.packer);
        assertThat(oldScan.code()).isEqualTo(WaybillResolver.Code.NOT_PACKABLE);
        assertThat(oldScan.detail()).isEqualTo(WaybillResolver.NotPackable.LEG_ENDED.name());
    }

    // ── g: goldens — real movement still freezes ────────────────────────────────────────────────

    @Test
    void g1_pickedUpThenTerminated_staysFrozen() {
        Shop q = new Shop("g1q", false), w = new Shop("g1w", true);
        for (Shop s : new Shop[]{q, w}) {
            UUID o = s.order("#52001");
            UUID old = bostaLeg(o, s == q ? "9730639058" : "9730639059", Instant.now().minusSeconds(7200));
            history(old, "created", 10, Instant.now().minusSeconds(7000));
            history(old, "with_courier", 21, Instant.now().minusSeconds(5000));
            end(old, "terminated", 48, Instant.now().minusSeconds(3600));
            String newTn = s == q ? "4491317452" : "4491317453";
            bostaLeg(o, newTn, Instant.now().minusSeconds(600));

            TenantContext.set(s.f.tenant);
            assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isTrue();
            if (s == q) {
                assertThat(fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer).code()).isEqualTo("ALREADY_SHIPPED");
            } else {
                WaybillResolver.Resolution r = resolver.resolve(newTn, s.packer);
                assertThat(r.code()).isEqualTo(WaybillResolver.Code.NOT_PACKABLE);
                assertThat(r.detail()).isEqualTo(WaybillResolver.NotPackable.ALREADY_MOVING.name());
                assertThat(r.state()).as("the end row still names the move").isEqualTo("terminated");
            }
            TenantContext.clear();
        }
    }

    @Test
    void g2_prePickupException_stillFreezes() {
        Shop s = new Shop("g2", false);
        UUID o = s.order("#52002");
        UUID leg = bostaLeg(o, "5512340987", Instant.now().minusSeconds(7200));
        history(leg, "created", 10, Instant.now().minusSeconds(7000));
        history(leg, "exception", 47, Instant.now().minusSeconds(3600));

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isTrue();
        assertThat(fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer).code()).isEqualTo("ALREADY_SHIPPED");
    }

    // ── pc: same tenant, nothing ended ──────────────────────────────────────────────────────────

    @Test
    void pc_sameTenant_unendedLeg_unchanged() {
        Shop s = new Shop("pc", true);
        UUID ended = s.order("#53001");
        UUID old = bostaLeg(ended, "7700112233", Instant.now().minusSeconds(7200));
        end(old, "cancelled", 49, Instant.now().minusSeconds(3600));
        UUID o = s.order("#53002");
        UUID leg = bostaLeg(o, "7700112234", Instant.now().minusSeconds(600));
        history(leg, "created", 10, Instant.now().minusSeconds(500));

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isFalse();
        assertThat(resolver.resolve("7700112234", s.packer).code()).isEqualTo(WaybillResolver.Code.OPEN);
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'order_queue' WHERE id = ?", s.f.tenant);
        assertThat(fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer).success()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private UUID bostaLeg(UUID order, String tn, Instant createdAt) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, created_at) " +
            "SELECT tenant_id, id, 'bosta', ?, 'created', ? FROM orders WHERE id = ? RETURNING id",
            UUID.class, tn, Timestamp.from(createdAt), order);
    }

    /** A history row as BostaWebhookJob.applyMappedState writes it (internal state + Bosta's code). */
    private void history(UUID shipment, String state, int code, Instant at) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state, occurred_at) " +
            "SELECT tenant_id, id, ?::shipment_internal_state, ?, ? FROM shipments WHERE id = ?",
            state, code, Timestamp.from(at), shipment);
    }

    /** Bosta ends the delivery: the leg's state and its history row. */
    private void end(UUID shipment, String state, int code, Instant at) {
        jdbc.update("UPDATE shipments SET internal_state = ?::shipment_internal_state, provider_state = ? WHERE id = ?",
            state, code, shipment);
        history(shipment, state, code, at);
    }

    private java.util.List<String> historyRows(UUID shipment) {
        return jdbc.queryForList("SELECT internal_state::text || ':' || provider_state FROM shipment_status_history " +
            "WHERE shipment_id = ? ORDER BY occurred_at, id", String.class, shipment);
    }
}
