package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.ApiException;
import com.traceability.labels.LabelCodeException;
import com.traceability.labels.LabelCodeService;
import com.traceability.labels.TagStockService;
import com.traceability.labels.UnusedLabels;
import com.traceability.tenancy.TenantContext;
import org.assertj.core.api.ThrowableAssert;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 6a — a FACTORY label code becomes a piece only through its factory run's receiving.
 * Before that, no scan path may bind it, and every path that explains an unused label says so.
 *
 *   g1  Tag stock: the scan is REJECTED (FACTORY_LABEL_NOT_RECEIVED); the code stays issued, the untagged
 *       piece of its variant stays untagged; InventoryLedger.bindLabel itself refuses it (the one guard)
 *   g2  Pick &amp; Pack: the scan on an order is rejected with the same code, nothing bound or allocated
 *   g3  Lookup, Stock take, Transfers (scan-out), Scan returns: "Factory label not received yet — receive it in
 *       its factory run first" (EN + AR); a TAG code still answers the Tag stock message (control)
 *   g4  the generic issue endpoint refuses kind factory (only a run's print issues those codes)
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryLabelGuardTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @Autowired InventoryLedger ledger;
    @Autowired TagStockService tagStock;
    @Autowired LabelCodeService labelCodes;
    @Autowired FulfillService fulfill;
    @Autowired LookupService lookup;
    @Autowired StockTakeService stockTakes;
    @Autowired TransferService transfers;
    @Autowired ReturnSessionService returnSessions;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, variant, main, side;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); variant = UUID.randomUUID();
        main = UUID.randomUUID(); side = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'FG Tenant', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', 'fg-o@test.local', 'h', 'owner')",
            owner, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'fg.myshopify.com', 'disconnected')",
            store, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'FG-P', 'Widget', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'FG-V', 'Blue', 'FGBLUE')",
            variant, tenant, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, 'FG Main', 'warehouse', true, true), (?, ?, 'FG Showroom', 'showroom', false, false)",
            main, tenant, side, tenant);
    }

    @BeforeEach
    void each() { TenantContext.set(tenant); }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (String t : List.of("stock_take_scans", "stock_take_expected", "stock_take_scope_variants", "stock_take_sessions",
                "transfer_pieces", "transfers", "return_session_items", "return_sessions", "label_codes", "label_code_batches",
                "label_run_lines", "label_runs",
                "tag_sessions", "piece_events", "pieces", "receipt_lines", "receipts", "piece_counters")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id = ?", tenant);
        }
    }

    @Test
    void g1_tag_stock_refuses_a_factory_code_and_bindLabel_is_the_guard() {
        String untagged = untaggedPiece();
        UUID codeId = factoryCode("P900001");
        UUID session = tagStock.start(owner).id();

        TagStockService.ScanOutcome out = tagStock.scan(session, "P900001", owner, false);
        assertThat(out.outcome()).isEqualTo("REJECTED:FACTORY_LABEL_NOT_RECEIVED");
        assertThat(out.code()).isEqualTo("P900001");
        assertThat(out.messageEn()).isEqualTo(UnusedLabels.FACTORY_MESSAGE_EN);
        assertThat(out.messageAr()).isEqualTo(UnusedLabels.FACTORY_MESSAGE_AR);

        assertThatThrownBy(() -> ledger.bindLabel(codeId, owner, InventoryLedger.BindRef.tagSession(session, false), "P900001"))
            .isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(LabelCodeException.Code.FACTORY_LABEL_NOT_RECEIVED));

        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, codeId)).isEqualTo("issued");
        assertThat(jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, untagged)).isNull();
        assertThat(count("SELECT count(*) FROM piece_events WHERE piece_id = ? AND event_type = 'label_bound'", untagged)).isZero();
    }

    @Test
    void g2_pick_and_pack_refuses_a_factory_code() {
        untaggedPiece();
        UUID codeId = factoryCode("P900002");
        FulfillService.ScanResult r = fulfill.scan(UUID.randomUUID(), "P900002", owner);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("FACTORY_LABEL_NOT_RECEIVED");
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, codeId)).isEqualTo("issued");
        assertThat(count("SELECT count(*) FROM allocations WHERE tenant_id = ?", tenant)).isZero();
    }

    @Test
    void g3_every_other_scan_path_explains_a_factory_code_and_a_tag_code_keeps_its_message() {
        factoryCode("P900003");
        String tagCode = labelCodes.issue(owner, "tag", List.of(new LabelCodeService.Line(variant, 1, null, null)), "fg-tag")
            .codes().get(0).code();

        // Lookup
        Map<String, Object> f = lookup.lookupPiece("P900003", false);
        assertThat(f).containsEntry("type", "label_code").containsEntry("kind", "factory")
            .containsEntry("messageEn", UnusedLabels.FACTORY_MESSAGE_EN).containsEntry("messageAr", UnusedLabels.FACTORY_MESSAGE_AR);
        assertThat(lookup.lookupPiece(tagCode, false)).containsEntry("kind", "tag").containsEntry("messageEn", UnusedLabels.MESSAGE_EN);

        // Stock take
        receivePrintStick(1);
        UUID st = (UUID) stockTakes.openSession("all", null, main, null, owner).get("sessionId");
        assertFactory(() -> stockTakes.scan(st, "P900003", "good", owner, false));
        assertCode(() -> stockTakes.scan(st, tagCode, "good", owner, false), UnusedLabels.CODE);

        // Transfers (scan-out)
        UUID transfer = transfers.createTransfer("showroom", side, null, "t", owner);
        TransferService.ScanOutResult out = transfers.scanOut(transfer, "P900003", owner);
        assertThat(out.code()).isEqualTo(UnusedLabels.FACTORY_CODE);
        assertThat(transfers.scanOut(transfer, tagCode, owner).code()).isEqualTo(UnusedLabels.CODE);

        // Scan returns
        UUID rs = returnSessions.createSession(null, owner);
        assertFactory(() -> returnSessions.scan(rs, "P900003", main, owner, false));
        assertCode(() -> returnSessions.scan(rs, tagCode, main, owner, false), UnusedLabels.CODE);
    }

    @Test
    void g4_the_generic_issue_refuses_kind_factory() {
        assertThatThrownBy(() -> labelCodes.issue(owner, "factory", List.of(new LabelCodeService.Line(variant, 1, null, null)), "fg-f"))
            .isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(LabelCodeException.Code.INVALID_KIND));
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenant)).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A factory code as a run's print issues it — its run, its line (V187), its batch; written directly here. */
    private UUID factoryCode(String code) {
        UUID run = jdbc.queryForObject(
            "INSERT INTO label_runs (tenant_id, factory_name, created_by) VALUES (?, 'FG Factory', ?) RETURNING id",
            UUID.class, tenant, owner);
        UUID line = jdbc.queryForObject(
            "INSERT INTO label_run_lines (tenant_id, run_id, line_no, variant_id, expected_qty) VALUES (?, ?, 1, ?, 1) RETURNING id",
            UUID.class, tenant, run, variant);
        UUID batch = jdbc.queryForObject(
            "INSERT INTO label_code_batches (tenant_id, kind, count, idempotency_key, created_by, run_id) " +
            "VALUES (?, 'factory', 1, ?, ?, ?) RETURNING id", UUID.class, tenant, "fg-" + code, owner, run);
        return jdbc.queryForObject(
            "INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id, run_line_id) " +
            "VALUES (?, ?, 'factory', ?, ?, ?, ?) RETURNING id", UUID.class, tenant, code, batch, owner, variant, line);
    }

    private String untaggedPiece() {
        UUID s = receiving.createSession(owner, main, "FG-CO-" + UUID.randomUUID(), null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variant, 1);
        receiving.finalize(s, owner);
        return jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ?", String.class, s);
    }

    private void receivePrintStick(int n) {
        UUID s = receiving.createSession(owner, main, "FG-PS-" + UUID.randomUUID(), null, null);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, owner);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertFactory(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(e -> {
            ApiException a = (ApiException) e;
            assertThat(a.errorCode()).isEqualTo(UnusedLabels.FACTORY_CODE);
            assertThat(a.messageEn()).isEqualTo(UnusedLabels.FACTORY_MESSAGE_EN);
            assertThat(a.messageAr()).isEqualTo(UnusedLabels.FACTORY_MESSAGE_AR);
        });
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(code));
    }
}
