package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.PieceStatus;
import com.traceability.inventory.ReturnService;
import com.traceability.inventory.TransitionContext;
import com.traceability.tenancy.TenantContext;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 (approved 2026-11-16, BRK-47903-EG) — OrderLineFreeze clause 3 counts a piece as having left custody only when
 * it has a with_courier / delivered event FOR THIS ORDER on a FORWARD leg of this order (self-pickup handover: no leg).
 * A return-intake event from packed / awaiting_pickup (a parcel taken apart before the courier had it — on BROEK, Scan
 * returns used as an unpack after the waybill was deleted in Bosta) never freezes the order.
 *
 *   f1  BRK-47903 shape: packed → awaiting_pickup on 6855433207 (never moved) → return_received ('rto') → restocked
 *       → reused on another order: NOT frozen (the old reading froze it — "tracking change not applied (frozen)")
 *   g1  golden: the piece left with the courier on the order's forward leg (no courier history row: clause 3 alone),
 *       came back and was restocked → still frozen
 *   g2  golden: the forward leg's history moved (clause 1) → still frozen
 *   g3  golden: self-pickup handover (packed → delivered, no shipment), returned and restocked → still frozen
 *   pc  same tenant, an order that only got packed: not frozen (unchanged)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnIntakeFreezeTest {

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
    @Autowired InventoryLedger ledger;
    @Autowired ReturnService   returns;
    @MockBean  BostaGateway    bostaGateway;
    @MockBean  JobScheduler    jobScheduler;

    PackFixtures f;
    UUID packer, variant;

    @BeforeAll
    void shop() {
        f = new PackFixtures(jdbc, "BROEK-d1");
        packer = f.user("Mona", "worker");
        variant = f.variant("Gray Relaxed Fit Denim Jeans", "BROEK-Gray-RFD-6", null);
        jdbc.update("UPDATE locations SET is_fulfillment = true WHERE id = ?", f.location);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** An order with one line, packed through the real pick flow; returns {order, pieceId}. */
    private Object[] packed(String number) {
        UUID o = f.order(number, 0);
        f.item(o, variant, 1);
        String code = f.piece(variant, packer);
        TenantContext.set(f.tenant);
        assertThat(fulfill.scan(o, code, packer).success()).isTrue();
        assertThat(fulfill.complete(o, packer)).isEqualTo(1);
        return new Object[]{o, f.pieceId(code)};
    }

    private UUID bostaLeg(UUID order, String tn) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "SELECT tenant_id, id, 'bosta', ?, 'created' FROM orders WHERE id = ? RETURNING id", UUID.class, tn, order);
    }

    private void move(String piece, PieceStatus from, PieceStatus to, String event, UUID order, UUID shipment, String meta) {
        ledger.transition(piece, from, to, event, packer, new TransitionContext(order, shipment, f.location, order, meta));
    }

    @Test
    void f1_returnIntakeFromAwaitingPickup_neverMovedLeg_notFrozen() {
        Object[] p = packed("BRK-47903-EG");
        UUID o = (UUID) p[0]; String piece = (String) p[1];
        UUID leg = bostaLeg(o, "6855433207");
        move(piece, PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP, "tracking_linked", o, leg, null);
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = 'deleted_in_bosta' WHERE id = ?", leg);
        // What Scan returns did before D3: 'rto' intake from awaiting_pickup, then Restock (allocation released).
        move(piece, PieceStatus.AWAITING_PICKUP, PieceStatus.RETURN_PENDING_INSPECTION, "return_received", o, leg,
            "{\"return_kind\":\"rto\"}");
        returns.restock(piece, f.location, packer);

        assertThat(OrderLineFreeze.isFrozen(jdbc, o, f.tenant)).as("a parcel taken apart never left").isFalse();
    }

    @Test
    void g1_leftWithTheCourierOnTheForwardLeg_thenReturned_staysFrozen() {
        Object[] p = packed("BRK-47247-EG");
        UUID o = (UUID) p[0]; String piece = (String) p[1];
        UUID leg = bostaLeg(o, "1151717987");
        move(piece, PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP, "tracking_linked", o, leg, null);
        move(piece, PieceStatus.AWAITING_PICKUP, PieceStatus.WITH_COURIER, "handed_to_courier", o, leg, null);
        move(piece, PieceStatus.WITH_COURIER, PieceStatus.RETURN_PENDING_INSPECTION, "return_received", o, leg,
            "{\"return_kind\":\"rto\"}");
        returns.restock(piece, f.location, packer);

        assertThat(OrderLineFreeze.isFrozen(jdbc, o, f.tenant)).as("clause 3: it really left").isTrue();
    }

    @Test
    void g2_forwardHistoryMoved_staysFrozen() {
        Object[] p = packed("BRK-47431-EG");
        UUID o = (UUID) p[0];
        UUID leg = bostaLeg(o, "5373943286");
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
            "VALUES (?, ?, 'with_courier', 21)", f.tenant, leg);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, f.tenant)).isTrue();
    }

    @Test
    void g3_selfPickupHandover_returnedAndRestocked_staysFrozen() {
        Object[] p = packed("BRK-48100-EG");
        UUID o = (UUID) p[0]; String piece = (String) p[1];
        move(piece, PieceStatus.PACKED, PieceStatus.DELIVERED, "self_pickup_handover", o, null, null);
        move(piece, PieceStatus.DELIVERED, PieceStatus.RETURN_PENDING_INSPECTION, "return_received", o, null,
            "{\"return_kind\":\"customer_after_delivery\"}");
        returns.restock(piece, f.location, packer);

        assertThat(OrderLineFreeze.isFrozen(jdbc, o, f.tenant)).isTrue();
    }

    @Test
    void pc_packedOnly_notFrozen() {
        Object[] p = packed("BRK-48101-EG");
        assertThat(OrderLineFreeze.isFrozen(jdbc, (UUID) p[0], f.tenant)).isFalse();
    }
}
