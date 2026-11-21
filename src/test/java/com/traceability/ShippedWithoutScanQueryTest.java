package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.OrderReopen;
import com.traceability.tenancy.TenantAwareDataSource;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-21 — the rewritten shipped_without_scan query (ExceptionService.SHIPPED_WITHOUT_SCAN_SQL) returns
 * exactly the rows of the 2026-11-16 query (kept here verbatim, built from the same helpers) on one fixture set that
 * walks every clause of the rule, as app_user under RLS:
 *
 *   hit_plain        old leg never moved (history 'created'), pieces packed on it, new leg at Bosta 24, nothing on it
 *   hit_deleted      BROEK: the old leg MOVED but was deleted in Bosta → still dead → alert
 *   hit_cancelRow    the old leg's only rows are created + cancelled (ended before it moved) → dead → alert
 *   hit_newHistory   new leg still provider_state 10 but its history moved → alert
 *   hit_twoOld       two old legs superseded by one new leg → ONE row, the later supersede (DISTINCT ON)
 *   no_moved         old leg moved, not deleted → no alert
 *   no_newScanned    a piece was scanned onto the new leg → no alert
 *   no_cancelled     order cancelled → no alert
 *   no_notNewest     a newer forward leg exists → no alert
 *   no_held          a piece of the order is with_courier → no alert
 *   no_resolved      resolved → no alert
 *   no_newIdle       new leg at 10 with no movement → no alert
 *   no_noOldPieces   nothing was ever packed on the old leg → no alert
 *   tenant B         the hit_plain shape for another tenant → never in tenant A's answer (and vice versa)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShippedWithoutScanQueryTest {

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

    @Autowired JdbcTemplate jdbc;   // postgres, fixtures only
    @MockBean  JobScheduler jobScheduler;

    /** The 2026-11-16 query, verbatim (ExceptionService before the incident rewrite). */
    static final String OLD_SQL =
            "SELECT DISTINCT ON (n.id) 'shipped_without_scan' AS type, 'HIGH' AS severity, 'shipment' AS subject_type, " +
            "       n.order_id, o.number AS order_number, n.id AS shipment_id, n.tracking_number, " +
            "       s.tracking_number AS old_tracking_number, n.provider_state, s.superseded_at AS occurred_at, " +
            "       'shipped_without_scan:' || n.id AS subject_key " +
            "FROM shipments s " +
            "JOIN shipments n ON n.id = s.superseded_by_shipment_id AND n.tenant_id = s.tenant_id " +
            "JOIN merchant_orders o ON o.id = n.order_id AND o.tenant_id = n.tenant_id " +
            "WHERE s.tenant_id = ? AND n.provider = 'bosta' AND n.shipment_leg = 'forward' " +
            "  AND o.status::text <> 'cancelled' " +
            "  AND " + OrderLineFreeze.newestForwardLegSql("n") + " " +
            "  AND " + OrderReopen.legDeadSql("s") + " " +
            "  AND EXISTS (SELECT 1 FROM piece_events pe_o WHERE pe_o.shipment_id = s.id AND pe_o.order_id = s.order_id) " +
            "  AND (coalesce(n.provider_state, 0) > 10 OR " + OrderLineFreeze.legMovedSql("n") + ") " +
            "  AND NOT EXISTS (SELECT 1 FROM piece_events pe_n WHERE pe_n.shipment_id = n.id) " +
            "  AND " + OrderReopen.noPieceHeldSql("o") + " " +
            "  AND NOT EXISTS (SELECT 1 FROM exception_resolutions er WHERE er.tenant_id = n.tenant_id " +
            "        AND er.exception_type = 'shipped_without_scan' AND er.subject_key = 'shipped_without_scan:' || n.id) " +
            "ORDER BY n.id, s.superseded_at DESC";

    JdbcTemplate        appJdbc;
    TransactionTemplate appTx;
    UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
    final Map<String, UUID> newLeg = new LinkedHashMap<>();
    final Map<String, String> oldTracking = new HashMap<>();
    long tn = 690000000L;
    final Instant base = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    record Fx(UUID tenant, UUID store, UUID variant) {}

    Fx tenant(UUID t) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'SwsQ')", t);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
                store, t, "sws-" + t.toString().substring(0, 8) + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'P')",
                product, t, store, "gid://shopify/Product/" + t);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')",
                variant, t, product, "gid://shopify/ProductVariant/" + t);
        return new Fx(t, store, variant);
    }

    UUID order(Fx f, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?::order_status, 'cod', now())",
                id, f.tenant, f.store, "gid://shopify/Order/" + id, "#" + name, status);
        return id;
    }

    UUID leg(Fx f, UUID order, String state, int providerState, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, shipment_leg, tracking_number, internal_state, " +
                    "    provider_state, created_at) VALUES (?, ?, ?, 'bosta', 'forward', ?, ?::shipment_internal_state, ?, ?)",
                id, f.tenant, order, String.valueOf(tn++), state, providerState, Timestamp.from(createdAt));
        return id;
    }

    void history(Fx f, UUID shipment, String... states) {
        int i = 0;
        for (String st : states)
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) VALUES (?, ?, ?, ?)",
                    f.tenant, shipment, st, Timestamp.from(base.plus(i++, ChronoUnit.HOURS)));
    }

    /** An old leg of {@code order}, cancelled and superseded by {@code by}; optionally deleted in Bosta. */
    UUID oldLeg(Fx f, UUID order, UUID by, boolean deleted, Instant supersededAt, String... hist) {
        UUID id = UUID.randomUUID();
        String trk = String.valueOf(tn++);
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, shipment_leg, tracking_number, internal_state, " +
                    "    provider_state, created_at, superseded_at, superseded_by_shipment_id, superseded_reason, " +
                    "    provider_deleted_at, provider_deleted_reason) " +
                    "VALUES (?, ?, ?, 'bosta', 'forward', ?, 'cancelled', 10, ?, ?, ?, 'shopify_tracking_edit', ?, ?)",
                id, f.tenant, order, trk, Timestamp.from(supersededAt.minus(2, ChronoUnit.DAYS)), Timestamp.from(supersededAt), by,
                deleted ? Timestamp.from(supersededAt) : null, deleted ? "deleted_in_bosta" : null);
        history(f, id, hist);
        oldTracking.put(id.toString(), trk);
        return id;
    }

    String piece(Fx f, UUID order, String status) {
        String id = "pc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_order_id) VALUES (?, ?, ?, ?, ?::piece_status, ?)",
                id, f.tenant, f.variant, id, status, order);
        return id;
    }

    void pieceEvent(Fx f, String piece, UUID order, UUID shipment) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, shipment_id, occurred_at) VALUES (?, ?, 'pack', ?, ?, now())",
                f.tenant, piece, order, shipment);
    }

    /** The common shape; each case then bends one clause. Returns the new leg. */
    UUID scenario(Fx f, String name, String orderStatus, int newProviderState, String newState, boolean oldDeleted,
                  String[] oldHist, boolean packOld) {
        UUID o = order(f, name, orderStatus);
        UUID n = leg(f, o, newState, newProviderState, base.plus(5, ChronoUnit.DAYS));
        UUID s = oldLeg(f, o, n, oldDeleted, base.plus(4, ChronoUnit.DAYS), oldHist);
        if (packOld) pieceEvent(f, piece(f, o, "available"), o, s);
        newLeg.put(f.tenant + "/" + name, n);
        return n;
    }

    static final String[] CREATED = {"created"};

    @BeforeAll
    void setup() {
        Fx a = tenant(tenantA), b = tenant(tenantB);

        scenario(a, "hit_plain", "awaiting_pickup", 24, "with_courier", false, CREATED, true);
        scenario(a, "hit_deleted", "awaiting_pickup", 24, "with_courier", true, new String[]{"created", "with_courier"}, true);
        scenario(a, "hit_cancelRow", "awaiting_pickup", 24, "with_courier", false, new String[]{"created", "cancelled"}, true);
        UUID nh = scenario(a, "hit_newHistory", "awaiting_pickup", 10, "created", false, CREATED, true);
        history(a, nh, "created", "with_courier");
        // two old legs, one new leg: the later supersede wins
        UUID o2 = order(a, "hit_twoOld", "awaiting_pickup");
        UUID n2 = leg(a, o2, "with_courier", 24, base.plus(6, ChronoUnit.DAYS));
        UUID s2a = oldLeg(a, o2, n2, false, base.plus(3, ChronoUnit.DAYS), CREATED);
        UUID s2b = oldLeg(a, o2, n2, false, base.plus(5, ChronoUnit.DAYS), CREATED);
        pieceEvent(a, piece(a, o2, "available"), o2, s2a);
        pieceEvent(a, piece(a, o2, "available"), o2, s2b);
        newLeg.put(tenantA + "/hit_twoOld", n2);

        scenario(a, "no_moved", "awaiting_pickup", 24, "with_courier", false, new String[]{"created", "with_courier"}, true);
        UUID ns = scenario(a, "no_newScanned", "awaiting_pickup", 24, "with_courier", false, CREATED, true);
        pieceEvent(a, piece(a, null, "available"), jdbc.queryForObject("SELECT order_id FROM shipments WHERE id = ?", UUID.class, ns), ns);
        scenario(a, "no_cancelled", "cancelled", 24, "with_courier", false, CREATED, true);
        UUID nn = scenario(a, "no_notNewest", "awaiting_pickup", 24, "with_courier", false, CREATED, true);
        UUID onn = jdbc.queryForObject("SELECT order_id FROM shipments WHERE id = ?", UUID.class, nn);
        leg(a, onn, "cancelled", 10, base.plus(7, ChronoUnit.DAYS));       // a newer (ended) forward leg
        UUID nhd = scenario(a, "no_held", "awaiting_pickup", 24, "with_courier", false, CREATED, true);
        piece(a, jdbc.queryForObject("SELECT order_id FROM shipments WHERE id = ?", UUID.class, nhd), "with_courier");
        UUID nr = scenario(a, "no_resolved", "awaiting_pickup", 24, "with_courier", false, CREATED, true);
        UUID manager = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, 'M', 'manager')", manager, tenantA);
        jdbc.update("INSERT INTO exception_resolutions (tenant_id, exception_type, subject_key, resolved_by) " +
                    "VALUES (?, 'shipped_without_scan', ?, ?)", tenantA, "shipped_without_scan:" + nr, manager);
        scenario(a, "no_newIdle", "awaiting_pickup", 10, "created", false, CREATED, true);
        scenario(a, "no_noOldPieces", "awaiting_pickup", 24, "with_courier", false, CREATED, false);

        scenario(b, "hit_plain", "awaiting_pickup", 24, "with_courier", false, CREATED, true);

        TenantAwareDataSource ds = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTx   = new TransactionTemplate(new DataSourceTransactionManager(ds));
        appTx.setReadOnly(true);
    }

    List<Map<String, Object>> run(String sql, UUID tenant) {
        return com.traceability.tenancy.TenantContext.runAs(tenant,
                (java.util.concurrent.Callable<List<Map<String, Object>>>) () -> appTx.execute(s -> appJdbc.queryForList(sql, tenant)));
    }

    Set<String> keys(List<Map<String, Object>> rows) {
        Set<String> k = new TreeSet<>();
        for (Map<String, Object> r : rows) k.add((String) r.get("subject_key"));
        return k;
    }

    String key(UUID tenant, String name) { return "shipped_without_scan:" + newLeg.get(tenant + "/" + name); }

    @Test
    void sameRowsAsTheOldQuery_everyClause() {
        List<Map<String, Object>> oldA = run(OLD_SQL, tenantA), newA = run(ExceptionService.SHIPPED_WITHOUT_SCAN_SQL, tenantA);
        assertThat(newA).isEqualTo(oldA);   // same rows, same columns, same values, same order

        assertThat(keys(newA)).containsExactlyInAnyOrder(
                key(tenantA, "hit_plain"), key(tenantA, "hit_deleted"), key(tenantA, "hit_cancelRow"),
                key(tenantA, "hit_newHistory"), key(tenantA, "hit_twoOld"));

        Map<String, Object> two = newA.stream().filter(r -> r.get("subject_key").equals(key(tenantA, "hit_twoOld"))).findFirst().orElseThrow();
        UUID later = jdbc.queryForObject("SELECT id FROM shipments WHERE superseded_by_shipment_id = ? ORDER BY superseded_at DESC LIMIT 1",
                UUID.class, newLeg.get(tenantA + "/hit_twoOld"));
        assertThat(two.get("old_tracking_number")).isEqualTo(oldTracking.get(later.toString()));
        assertThat(two.keySet()).containsExactly("type", "severity", "subject_type", "order_id", "order_number", "shipment_id",
                "tracking_number", "old_tracking_number", "provider_state", "occurred_at", "subject_key");
    }

    @Test
    void tenantIsolation_bothQueriesAgree() {
        List<Map<String, Object>> oldB = run(OLD_SQL, tenantB), newB = run(ExceptionService.SHIPPED_WITHOUT_SCAN_SQL, tenantB);
        assertThat(newB).isEqualTo(oldB);
        assertThat(keys(newB)).containsExactly(key(tenantB, "hit_plain"));
        assertThat(keys(run(ExceptionService.SHIPPED_WITHOUT_SCAN_SQL, tenantA))).doesNotContain(key(tenantB, "hit_plain"));
    }
}
