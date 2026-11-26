package com.traceability;

import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B1 — events (create / send / start), prices, the flag, and RLS on the two new tables (app_user, with a
 * same-tenant positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarEventServiceTest {

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

    @Autowired BazarService bazar;
    @Autowired JdbcTemplate jdbc;

    UUID tenantId, otherTenantId, offTenantId, actorId;
    UUID mainId, variantA, variantB, variantNoPrice, productId;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        offTenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantA = UUID.randomUUID();
        variantB = UUID.randomUUID();
        variantNoPrice = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Co', true)", tenantId);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Bazar Co', true)", otherTenantId);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'No Bazar Co', false)", offTenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'owner@bazar-event-test.com', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-event.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BE', 'Linen Dress', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BE-A', 'S', 349.00)", variantA, tenantId, productId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BE-B', 'M', 330.00)", variantB, tenantId, productId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BE-N', 'L', NULL)", variantNoPrice, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private BazarService.CreateEvent req(UUID locationId, String locationName, String mode) {
        return new BazarService.CreateEvent("Zamalek Bazar " + (++seq), LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 3), locationId, locationName, List.of("cash", "instapay"),
            List.of("owner", "manager", "worker"), mode, null, null);
    }

    private Map<String, Object> newEvent() {
        return bazar.create(req(null, "Bazar Hall " + (++seq), null), actorId);
    }

    private UUID eventLocation(String mode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_sync_mode) " +
            "VALUES (?, ?, ?, 'event', false, false, ?)", id, tenantId, "Event Spot " + (++seq), mode);
        return id;
    }

    private static BazarException.Code codeOf(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e.code(); }
        throw new AssertionError("expected a BazarException");
    }

    private Map<String, Object> priceRow(List<Map<String, Object>> prices, UUID variant) {
        return prices.stream().filter(p -> variant.equals(p.get("variant_id"))).findFirst().orElseThrow();
    }

    private void sendOut(UUID eventId, UUID... variants) {
        for (UUID v : variants) {
            assertThat(bazar.scanOut(eventId, "PC-" + piece(v), actorId, false).success()).isTrue();
        }
        bazar.markSent(eventId, actorId);
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_newLocation_isATracedOnlyEventLocation_andTheEventIsDraft() {
        Map<String, Object> ev = newEvent();
        assertThat(ev.get("status")).isEqualTo("draft");
        assertThat(ev.get("transfer_status")).isEqualTo("preparing");
        assertThat(ev.get("payment_methods")).isEqualTo(List.of("cash", "instapay"));
        Map<String, Object> loc = jdbc.queryForMap(
            "SELECT type::text AS type, is_fulfillment, shopify_location_id, shopify_sync_status, shopify_sync_mode " +
            "FROM locations WHERE id = ?", ev.get("location_id"));
        assertThat(loc.get("type")).isEqualTo("event");
        assertThat(loc.get("is_fulfillment")).isEqualTo(false);
        assertThat(loc.get("shopify_location_id")).isNull();
        assertThat(loc.get("shopify_sync_status")).isEqualTo("unsynced");
        assertThat(loc.get("shopify_sync_mode")).isEqualTo("remove");
    }

    @Test
    void create_leaveMode_inTheRequest_makesTheLocationInLeaveMode() {
        Map<String, Object> ev = bazar.create(req(null, "Leave Hall", "leave"), actorId);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM locations WHERE id = ?", String.class,
            ev.get("location_id"))).isEqualTo("leave");
        assertThat(ev.get("online_stock")).isEqualTo("leave");
    }

    @Test
    void create_reusesALocationInLeaveMode_keepsLeave_orSwitchesItToRemoveWhenRemoveIsChosen() {
        UUID loc = eventLocation("leave");
        assertThat(bazar.create(req(loc, null, "leave"), actorId).get("location_id")).isEqualTo(loc);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM locations WHERE id = ?", String.class, loc))
            .isEqualTo("leave");

        // Another leave location, no unsent bazar there: the new bazar chose remove → the location switches to remove.
        UUID other = eventLocation("leave");
        Map<String, Object> ev = bazar.create(req(other, null, "remove"), actorId);
        assertThat(ev.get("location_id")).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM locations WHERE id = ?", String.class, other))
            .isEqualTo("remove");
        assertThat(ev.get("online_stock")).isEqualTo("remove");
    }

    @Test
    void create_reusesAnEventLocation_butNotAWarehouseOrShowroom() {
        UUID loc = eventLocation("remove");
        assertThat(bazar.create(req(loc, null, null), actorId).get("location_id")).isEqualTo(loc);
        assertThat(codeOf(() -> bazar.create(req(mainId, null, null), actorId)))
            .isEqualTo(BazarException.Code.BAZAR_LOCATION_NOT_EVENT);
    }

    @Test
    void create_rejectsBadInput() {
        assertThat(codeOf(() -> bazar.create(new BazarService.CreateEvent("X", LocalDate.of(2026, 12, 3),
            LocalDate.of(2026, 12, 1), null, "Backwards Hall", null, null, null, null, null), actorId)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> bazar.create(new BazarService.CreateEvent("X", LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 1), null, "Bitcoin Hall", List.of("bitcoin"), null, null, null, null), actorId)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> bazar.create(new BazarService.CreateEvent("X", LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 1), null, "Main Warehouse", null, null, null, null, null), actorId)))
            .isEqualTo(BazarException.Code.BAZAR_LOCATION_NAME_TAKEN);
    }

    // ── send ─────────────────────────────────────────────────────────────────

    @Test
    void markSent_isTheExistingSend_snapshotRemove_andAShopifyClaimRow() {
        Map<String, Object> ev = newEvent();
        UUID eventId = (UUID) ev.get("id");
        sendOut(eventId, variantA, variantA, variantB);
        Map<String, Object> t = jdbc.queryForMap("SELECT status, shopify_sync_mode FROM transfers WHERE id = ?",
            ev.get("transfer_id"));
        assertThat(t.get("status")).isEqualTo("sent");
        assertThat(t.get("shopify_sync_mode")).isEqualTo("remove");
        // The existing claimSend ran: one transfer_shopify_syncs row (skipped here — the test main isn't linked).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?",
            Integer.class, ev.get("transfer_id"))).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) bazar.get(eventId).get("counts");
        assertThat(counts).containsEntry("sent", 3).containsEntry("at_bazar", 3).containsEntry("sold", 0)
            .containsEntry("back", 0).containsEntry("lost", 0);
    }

    @Test
    void markSent_afterTheLocationWasSwitchedToLeave_sendsWithSnapshotLeave_andNoClaimRow() {
        Map<String, Object> ev = newEvent();
        UUID eventId = (UUID) ev.get("id");
        assertThat(bazar.scanOut(eventId, "PC-" + piece(variantA), actorId, false).success()).isTrue();
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", ev.get("location_id"));
        bazar.markSent(eventId, actorId);
        Map<String, Object> t = jdbc.queryForMap("SELECT status, shopify_sync_mode FROM transfers WHERE id = ?",
            ev.get("transfer_id"));
        assertThat(t.get("status")).isEqualTo("sent");
        assertThat(t.get("shopify_sync_mode")).isEqualTo("leave");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?",
            Integer.class, ev.get("transfer_id"))).isZero();
    }

    // ── start ────────────────────────────────────────────────────────────────

    @Test
    void start_refusesAnUnsentTransfer() {
        Map<String, Object> ev = newEvent();
        UUID eventId = (UUID) ev.get("id");
        assertThat(bazar.scanOut(eventId, "PC-" + piece(variantA), actorId, false).success()).isTrue();
        assertThat(codeOf(() -> bazar.start(eventId, actorId))).isEqualTo(BazarException.Code.BAZAR_TRANSFER_NOT_SENT);
        assertThat(bazar.get(eventId).get("status")).isEqualTo("draft");
    }

    @Test
    void start_goesLive_onlyOneLiveEventPerLocation() {
        UUID loc = eventLocation("remove");
        UUID first = (UUID) bazar.create(req(loc, null, null), actorId).get("id");
        UUID second = (UUID) bazar.create(req(loc, null, null), actorId).get("id");
        sendOut(first, variantA);
        sendOut(second, variantB);

        Map<String, Object> live = bazar.start(first, actorId);
        assertThat(live.get("status")).isEqualTo("live");
        assertThat(live.get("started_at")).isNotNull();

        assertThat(codeOf(() -> bazar.start(second, actorId)))
            .isEqualTo(BazarException.Code.BAZAR_LOCATION_HAS_LIVE_EVENT);
        assertThat(codeOf(() -> bazar.start(first, actorId))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_DRAFT);
    }

    @Test
    void oneLivePerLocation_isAlsoADatabaseRule() {
        UUID loc = eventLocation("remove");
        UUID a = (UUID) bazar.create(req(loc, null, null), actorId).get("id");
        UUID b = (UUID) bazar.create(req(loc, null, null), actorId).get("id");
        jdbc.update("UPDATE bazar_events SET status = 'live', started_at = now() WHERE id = ?", a);
        Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
            jdbc.update("UPDATE bazar_events SET status = 'live', started_at = now() WHERE id = ?", b));
    }

    // ── prices ───────────────────────────────────────────────────────────────

    @Test
    void prices_defaultIsTheShopifyPrice_bulkRuleAppliesToAll_overrideWins() {
        UUID eventId = (UUID) newEvent().get("id");
        for (UUID v : List.of(variantA, variantB, variantNoPrice)) {
            assertThat(bazar.scanOut(eventId, "PC-" + piece(v), actorId, false).success()).isTrue();
        }

        List<Map<String, Object>> p = bazar.listPrices(eventId);
        assertThat(p).hasSize(3);
        assertThat(priceRow(p, variantA).get("price")).isEqualTo(new BigDecimal("349.00"));
        assertThat(priceRow(p, variantA).get("source")).isEqualTo("default");
        assertThat(priceRow(p, variantNoPrice).get("price")).as("no Shopify price → no bazar price yet").isNull();

        // Override B, then a bulk rule: A follows the rule, B keeps its override.
        bazar.setOverride(eventId, variantB, new BigDecimal("199.00"), actorId);
        p = bazar.setBulkRule(eventId, new BigDecimal("20"), new BigDecimal("10"), actorId);
        assertThat(priceRow(p, variantA).get("price")).isEqualTo(new BigDecimal("280.00"));
        assertThat(priceRow(p, variantA).get("source")).isEqualTo("bulk");
        assertThat(priceRow(p, variantB).get("price")).isEqualTo(new BigDecimal("199.00"));
        assertThat(priceRow(p, variantB).get("source")).isEqualTo("override");
        assertThat(priceRow(p, variantNoPrice).get("price")).isNull();

        // A variant that joins after the rule is priced by the rule.
        UUID variantC = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, ?, 'XL', 500.00)", variantC, tenantId, productId, "VAR-BE-C-" + variantC);
        assertThat(bazar.scanOut(eventId, "PC-" + piece(variantC), actorId, false).success()).isTrue();
        assertThat(priceRow(bazar.listPrices(eventId), variantC).get("price")).isEqualTo(new BigDecimal("400.00"));

        // Clearing B's override puts it back on the rule (330 × 0.8 = 264 → 260).
        p = bazar.clearOverride(eventId, variantB, actorId);
        assertThat(priceRow(p, variantB).get("price")).isEqualTo(new BigDecimal("260.00"));
        assertThat(priceRow(p, variantB).get("source")).isEqualTo("bulk");

        // Removing the rule: back to Shopify's prices.
        p = bazar.setBulkRule(eventId, null, null, actorId);
        assertThat(priceRow(p, variantA).get("price")).isEqualTo(new BigDecimal("349.00"));
        assertThat(priceRow(p, variantA).get("source")).isEqualTo("default");

        // No Shopify price: can be overridden, can't fall back.
        bazar.setOverride(eventId, variantNoPrice, new BigDecimal("150.00"), actorId);
        assertThat(codeOf(() -> bazar.clearOverride(eventId, variantNoPrice, actorId)))
            .isEqualTo(BazarException.Code.BAZAR_NO_DEFAULT_PRICE);
    }

    @Test
    void prices_areNeverWrittenToShopify() {
        UUID eventId = (UUID) newEvent().get("id");
        assertThat(bazar.scanOut(eventId, "PC-" + piece(variantA), actorId, false).success()).isTrue();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments", Integer.class);
        bazar.setBulkRule(eventId, new BigDecimal("10"), null, actorId);
        bazar.setOverride(eventId, variantA, new BigDecimal("1.00"), actorId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments", Integer.class))
            .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT price FROM variants WHERE id = ?", BigDecimal.class, variantA))
            .isEqualTo(new BigDecimal("349.00"));
    }

    // ── flag ─────────────────────────────────────────────────────────────────

    @Test
    void flagOff_everyMethodAnswersNotEnabled() {
        TenantContext.set(offTenantId);
        assertThat(codeOf(() -> bazar.list())).isEqualTo(BazarException.Code.BAZAR_NOT_ENABLED);
        assertThat(codeOf(() -> bazar.create(req(null, "Off Hall", null), actorId)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_ENABLED);
        assertThat(codeOf(() -> bazar.get(UUID.randomUUID()))).isEqualTo(BazarException.Code.BAZAR_NOT_ENABLED);
        assertThat(codeOf(() -> bazar.start(UUID.randomUUID(), actorId))).isEqualTo(BazarException.Code.BAZAR_NOT_ENABLED);
    }

    @Test
    void anotherTenantsEvent_isNotFound() {
        UUID eventId = (UUID) newEvent().get("id");
        TenantContext.set(otherTenantId);
        assertThat(codeOf(() -> bazar.get(eventId))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_FOUND);
    }

    // ── RLS (app_user) ───────────────────────────────────────────────────────

    @Test
    void rls_appUser_seesOnlyItsOwnEventsAndPrices() {
        UUID eventId = (UUID) newEvent().get("id");
        assertThat(bazar.scanOut(eventId, "PC-" + piece(variantA), actorId, false).success()).isTrue();

        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String evSql = "SELECT COUNT(*) FROM bazar_events WHERE id = ?";
        String prSql = "SELECT COUNT(*) FROM bazar_event_prices WHERE event_id = ?";

        TenantContext.set(otherTenantId);
        assertThat((Integer) tx.execute(s -> app.queryForObject(evSql, Integer.class, eventId))).isZero();
        assertThat((Integer) tx.execute(s -> app.queryForObject(prSql, Integer.class, eventId))).isZero();

        TenantContext.clear();
        assertThat((Integer) tx.execute(s -> app.queryForObject(evSql, Integer.class, eventId))).as("no tenant set").isZero();

        TenantContext.set(tenantId);   // same-tenant positive control
        assertThat((Integer) tx.execute(s -> app.queryForObject(evSql, Integer.class, eventId))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> app.queryForObject(prSql, Integer.class, eventId))).isEqualTo(1);
    }

    @Test
    void appUser_cannotDeleteEventsOrPrices() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String table : List.of("bazar_events", "bazar_event_prices")) {
            Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                tx.execute(s -> app.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId)));
        }
    }
}
