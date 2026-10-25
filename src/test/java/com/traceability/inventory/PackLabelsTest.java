package com.traceability.inventory;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.labels.LabelCodeException;
import com.traceability.labels.LabelCodeService;
import com.traceability.labels.PackLabelService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Flexible labeling slice 4 — packing labels printed in order sequence, bound at the pack scan.
 *
 *   p1  the print set follows the waybill print's candidates, scope and sort (oldest / newest); orders needing no
 *       label are skipped; the PDF is ONE separator (the order's header) then that order's labels, in that order
 *   p2  only UNALLOCATED units of variants with untagged stock at the main warehouse; capped at the untagged count
 *       across the print, first come first served; the summary counts units packed from labeled stock
 *   p3  never issued twice for the same order item: a second print reuses (reprints) the unbound codes
 *   p4  separator: tracking number then order number; the order number alone without tracking; no barcode
 *   p5  > 2,000 labels → parts of ≤ 2,000 in sequence, never splitting an order (issued in ≤ 2,000-code batches)
 *   p6  scope last = the store's newest waybill print, in its PDF order; one order (self-pickup) on its own
 *   b1  bind + allocate is one transaction: an allocate failure leaves no bind, the code still issued
 *   b2  a label printed for another order of the same variant is allowed and recorded on the event
 *   b3  WRONG_VARIANT names both products; voided → CODE_VOIDED; NO_UNTAGGED_STOCK; a bound code → the normal piece
 *       path; a typed internal code stays refused
 *   b4  the highlighted line is used when it is of the label's variant with room, else the first such line
 *   b5  two packers binding the same variant at once never take the same piece and never wait
 *   e1  queue mode end to end over HTTP: print → PDF → scan labels (and a labeled piece) → complete → packed
 *   e2  waybill mode end to end over HTTP: waybill → scan labels → the order completes
 *   r1  replacement: one code (kind replacement, order refs) as the normal piece label; refused with no untagged stock
 *   s1  stock numbers keep their meaning: a label scan moves on hand / committed / available exactly like a piece
 *       scan; no Shopify call
 *   f1  switch off: the endpoints are 404, the order detail has no label fields, an issued code is "not used yet"
 *   x1  app_user: another tenant's order / codes are invisible (same-tenant positive control)
 *   x2  a portal pre-connect order is never printable (merchant_orders)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class PackLabelsTest {

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
    }

    private static final AtomicLong TRACKING = new AtomicLong(4_100_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PackLabelService packLabels;
    @Autowired LabelCodeService labels;
    @Autowired FulfillService fulfill;
    @Autowired ReceivingService receiving;
    @Autowired VariantStockService variantStock;
    @Autowired ReturnSessionService returnSessions;
    @Autowired LabelService labelService;
    @Autowired LookupService lookup;
    @Autowired InventoryLedger ledger;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @MockBean  ShopifyInventoryService shopifyInventory;
    @MockBean  BostaGateway bostaGateway;
    @MockBean  JobScheduler jobScheduler;

    UUID tenant, owner, worker, store, main, variantA, variantB, variantC;
    UUID other, otherOwner, otherStore, otherMain, otherVariant;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); worker = UUID.randomUUID(); store = UUID.randomUUID();
        main = UUID.randomUUID(); variantA = UUID.randomUUID(); variantB = UUID.randomUUID(); variantC = UUID.randomUUID();
        other = UUID.randomUUID(); otherOwner = UUID.randomUUID(); otherStore = UUID.randomUUID();
        otherMain = UUID.randomUUID(); otherVariant = UUID.randomUUID();
        seed(tenant, owner, store, main, "PL", variantA, variantB, variantC);
        seed(other, otherOwner, otherStore, otherMain, "PX", otherVariant, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', 'pl-w@test.local', 'h', 'worker')",
            worker, tenant);
    }

    private void seed(UUID t, UUID user, UUID st, UUID mainLoc, String p, UUID v1, UUID v2, UUID v3) {
        UUID shirt = UUID.randomUUID(), cap = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            user, t, p.toLowerCase() + "-o@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            st, t, p.toLowerCase() + "-pl.myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, ?, 'Linen Shirt', 'active'), (?, ?, ?, ?, 'Wool Cap', 'active')", shirt, t, st, p + "-P1", cap, t, st, p + "-P2");
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, ?, 'Blue', ?), (?, ?, ?, ?, 'Red', ?), (?, ?, ?, ?, 'Grey', ?)",
            v1, t, shirt, p + "-V1", p + "BLUE", v2, t, shirt, p + "-V2", p + "RED", v3, t, cap, p + "-V3", p + "CAP");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, ?, 'warehouse', true, true)", mainLoc, t, p + " Main");
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true, pick_pack_mode = 'order_queue' WHERE id IN (?, ?)", tenant, other);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (UUID t : List.of(tenant, other)) {
            jdbc.update("DELETE FROM pack_session_orders WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pack_sessions WHERE tenant_id = ?", t);
            for (String table : List.of("return_session_items", "return_session_shipments", "return_sessions", "allocations", "label_codes", "label_code_batches", "piece_events", "pieces", "receipt_lines",
                    "receipts", "piece_counters", "pack_print_batch_items", "pack_print_batches", "shipment_status_history",
                    "shipments", "order_items", "orders")) {
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", t);
            }
        }
        reset(shopifyInventory, bostaGateway);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void untagged(UUID variant, int n) {
        UUID s = receiving.createSession(owner, main, "CO-" + UUID.randomUUID(), null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, owner);
    }

    /** n labeled pieces; their short codes. */
    private List<String> labeled(UUID variant, int n) {
        UUID s = receiving.createSession(owner, main, "PS-" + UUID.randomUUID(), null, null);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, owner);
        return jdbc.queryForList("SELECT short_code FROM pieces WHERE receipt_id = ? ORDER BY short_code", String.class, s);
    }

    /** An order placed minutesAgo minutes ago (candidate sort is created_at), with a Bosta waybill unless noWaybill. */
    private UUID order(String number, int minutesAgo, boolean waybill, Object... variantQty) {
        UUID o = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, payment_method, placed_at, created_at) " +
            "VALUES (?, ?, ?, ?, 'new', 'Mona', 'cod', now() - (? * interval '1 minute'), now() - (? * interval '1 minute')) RETURNING id",
            UUID.class, tenant, store, "EXT-" + UUID.randomUUID(), number, minutesAgo, minutesAgo);
        for (int i = 0; i < variantQty.length; i += 2) {
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                tenant, o, variantQty[i], variantQty[i + 1]);
        }
        if (waybill) {
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", tenant, o, String.valueOf(TRACKING.incrementAndGet()));
        } else {
            jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", o);
        }
        return o;
    }

    private String tracking(UUID order) {
        return jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE order_id = ?", String.class, order);
    }

    private List<UUID> items(UUID order) {
        return jdbc.queryForList("SELECT id FROM order_items WHERE order_id = ? ORDER BY id", UUID.class, order);
    }

    /** The order's issued pack codes, in line then code order (= the PDF order). */
    private List<String> packCodes(UUID order) {
        return jdbc.queryForList("SELECT code FROM label_codes WHERE order_id = ? AND kind = 'pack' AND status = 'issued' " +
            "ORDER BY order_item_id, code", String.class, order);
    }

    private PackLabelService.Request all(String sort) { return new PackLabelService.Request("all", sort, null); }

    private List<UUID> sequence(PackLabelService.Summary s) {
        return s.parts().stream().flatMap(p -> p.orderIds().stream()).toList();
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private static List<String> pageTexts(byte[] pdf) throws IOException {
        List<String> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper strip = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                strip.setStartPage(i); strip.setEndPage(i);
                out.add(strip.getText(doc).trim());
            }
        }
        return out;
    }

    private static boolean pageHasImage(byte[] pdf, int index) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(index);
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().isImageXObject(name)) return true;
            }
            return false;
        }
    }

    // ── printing ──────────────────────────────────────────────────────────────

    @Test
    void p1_same_sequence_as_the_waybill_print_and_orders_needing_none_are_skipped() throws Exception {
        untagged(variantA, 10);
        labeled(variantC, 2);                                   // C: labeled stock only — no label for C
        UUID first = order("#P1", 30, true, variantA, 1);
        UUID onlyC = order("#P2", 20, true, variantC, 1);
        UUID third = order("#P3", 10, true, variantA, 2);

        PackLabelService.Summary oldest = packLabels.preview(all("oldest"));
        assertThat(sequence(oldest)).containsExactly(first, third);
        assertThat(sequence(packLabels.preview(all("newest")))).containsExactly(third, first);

        // Same candidate set as the waybill print itself — never a copy of its rule.
        assertThat(new PackPrintBatchStore(jdbc, 30).candidates("all", "oldest").stream()
            .map(PackPrintBatchStore.Candidate::orderId).toList()).containsExactly(first, onlyC, third);

        PackLabelService.Summary printed = packLabels.print(all("oldest"), owner);
        assertThat(printed.labels()).isEqualTo(3);
        assertThat(printed.orders()).isEqualTo(2);
        List<String> pages = pageTexts(packLabels.pdf(printed.parts().get(0).orderIds()));
        List<String> c1 = packCodes(first), c3 = packCodes(third);
        assertThat(pages).hasSize(5);                          // sep + 1 label, sep + 2 labels — none for #P2, nothing after
        assertThat(pages.get(0)).contains(tracking(first)).contains("#P1");
        assertThat(pages.get(1)).contains(c1.get(0)).contains("Linen Shirt");
        assertThat(pages.get(2)).contains(tracking(third)).contains("#P3");
        assertThat(pages.get(3)).contains(c3.get(0));
        assertThat(pages.get(4)).contains(c3.get(1));
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE order_id = ?", onlyC)).isZero();
    }

    @Test
    void p2_only_unallocated_units_of_untagged_variants_capped_at_the_untagged_count() {
        List<String> blueLabeled = labeled(variantA, 1);
        untagged(variantA, 2);
        UUID first = order("#Q1", 30, true, variantA, 2);       // 1 packed from labeled stock already → 1 unit left
        UUID second = order("#Q2", 20, true, variantA, 3);      // untagged left: 1 → 1 label, 2 units without
        UUID third = order("#Q3", 10, true, variantA, 1);       // nothing left → no label, skipped
        assertThat(fulfill.scan(first, blueLabeled.get(0), owner).success()).isTrue();

        PackLabelService.Summary s = packLabels.print(all("oldest"), owner);
        assertThat(s.labels()).isEqualTo(2);
        assertThat(s.unitsWithoutLabel()).isEqualTo(3);
        assertThat(s.ordersConsidered()).isEqualTo(3);
        assertThat(sequence(s)).containsExactly(first, second);
        assertThat(packCodes(first)).hasSize(1);
        assertThat(packCodes(second)).hasSize(1);
        assertThat(packCodes(third)).isEmpty();
        assertThat(jdbc.queryForMap("SELECT kind, order_item_id FROM label_codes WHERE order_id = ?", first))
            .containsEntry("kind", "pack").containsEntry("order_item_id", items(first).get(0));
    }

    @Test
    void p3_a_second_print_reuses_the_unbound_codes_never_issues_again() throws Exception {
        untagged(variantA, 4);
        UUID o = order("#R1", 10, true, variantA, 2);
        PackLabelService.Summary first = packLabels.print(all("oldest"), owner);
        assertThat(first.reused()).isZero();
        List<String> codes = packCodes(o);

        PackLabelService.Summary again = packLabels.print(all("oldest"), owner);
        assertThat(again.labels()).isEqualTo(2);
        assertThat(again.reused()).isEqualTo(2);
        assertThat(packCodes(o)).isEqualTo(codes);
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", tenant)).isEqualTo(2);
        assertThat(pageTexts(packLabels.pdf(List.of(o))).subList(1, 3))                  // after the order's header
            .satisfiesExactly(p -> assertThat(p).contains(codes.get(0)), p -> assertThat(p).contains(codes.get(1)));
    }

    @Test
    void p4_separator_text_with_and_without_tracking_and_no_barcode() throws Exception {
        untagged(variantA, 2);
        UUID courier = order("#S1", 20, true, variantA, 1);
        UUID pickup = order("#S2", 10, false, variantA, 1);
        packLabels.print(new PackLabelService.Request(null, null, courier), owner);
        packLabels.print(new PackLabelService.Request(null, null, pickup), owner);

        byte[] pdf = packLabels.pdf(List.of(courier, pickup));
        List<String> pages = pageTexts(pdf);
        assertThat(pages).hasSize(4);
        assertThat(pages.get(0).lines().map(String::trim).toList()).containsExactly(tracking(courier), "#S1");
        assertThat(pages.get(2).lines().map(String::trim).toList()).containsExactly("#S2");
        assertThat(pageHasImage(pdf, 1)).as("a label carries its barcode").isTrue();
        assertThat(pageHasImage(pdf, 0)).as("a separator has no barcode").isFalse();
        assertThat(pageHasImage(pdf, 2)).isFalse();
        assertThat(pageHasImage(pdf, 3)).as("the PDF ends with a label, not a separator").isTrue();
    }

    @Test
    void p5_over_2000_labels_split_into_parts_without_splitting_an_order() {
        untagged(variantA, 2200);
        UUID big = order("#T1", 30, true, variantA, 1500);
        UUID mid = order("#T2", 20, true, variantA, 600);
        UUID small = order("#T3", 10, true, variantA, 10);

        PackLabelService.Summary s = packLabels.print(all("oldest"), owner);
        assertThat(s.labels()).isEqualTo(2110);
        assertThat(s.parts()).hasSize(2);
        assertThat(s.parts().get(0)).satisfies(p -> {
            assertThat(p.part()).isEqualTo(1); assertThat(p.of()).isEqualTo(2);
            assertThat(p.labels()).isEqualTo(1500); assertThat(p.orderIds()).containsExactly(big);
        });
        assertThat(s.parts().get(1)).satisfies(p -> {
            assertThat(p.labels()).isEqualTo(610); assertThat(p.orderIds()).containsExactly(mid, small);
        });
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE tenant_id = ? AND kind = 'pack'", tenant)).isEqualTo(2110);
        assertThat(count("SELECT max(count)::int FROM label_code_batches WHERE tenant_id = ?", tenant)).isLessThanOrEqualTo(2000);
        assertThatThrownBy(() -> packLabels.pdf(List.of(big, mid))).isInstanceOf(LabelCodeException.class);
    }

    @Test
    void p6_scope_last_follows_the_newest_waybill_print_and_one_order_prints_alone() {
        untagged(variantA, 5);
        UUID a = order("#U1", 30, true, variantA, 1);
        UUID b = order("#U2", 20, true, variantA, 1);
        UUID c = order("#U3", 10, true, variantA, 1);
        UUID older = jdbc.queryForObject("INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, " +
            "waybill_count, order_guaranteed, created_at) VALUES (?, 1, ?, 'A4', 'oldest', 'new', 1, true, now() - interval '1 hour') RETURNING id",
            UUID.class, tenant, owner);
        UUID newest = jdbc.queryForObject("INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, " +
            "waybill_count, order_guaranteed) VALUES (?, 2, ?, 'A4', 'newest', 'new', 2, true) RETURNING id", UUID.class, tenant, owner);
        jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
            "SELECT ?, tenant_id, order_id, id, tracking_number, 1 FROM shipments WHERE order_id = ?", older, a);
        jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
            "SELECT ?, tenant_id, order_id, id, tracking_number, CASE WHEN order_id = ? THEN 1 ELSE 2 END FROM shipments " +
            "WHERE order_id IN (?, ?)", newest, c, c, b);

        assertThat(sequence(packLabels.preview(new PackLabelService.Request("last", null, null)))).containsExactly(c, b);
        UUID pickup = order("#U4", 5, false, variantA, 2);
        PackLabelService.Summary one = packLabels.print(new PackLabelService.Request(null, null, pickup), owner);
        assertThat(sequence(one)).containsExactly(pickup);
        assertThat(one.labels()).isEqualTo(2);
    }

    // ── binding at the pack scan ──────────────────────────────────────────────

    private List<String> printFor(UUID order) {
        packLabels.print(new PackLabelService.Request(null, null, order), owner);
        return packCodes(order);
    }

    private String labelEvent(String code, String key) {
        return jdbc.queryForObject("SELECT e.metadata ->> '" + key + "' FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.short_code = ? AND e.event_type = 'label_bound'", String.class, code);
    }

    @Test
    void b1_an_allocate_failure_rolls_the_bind_back() {
        untagged(variantA, 1);
        UUID o = order("#V1", 10, true, variantA, 1);
        String code = printFor(o).get(0);
        jdbc.execute("CREATE FUNCTION pl_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'allocate failed'; END $$");
        jdbc.execute("CREATE TRIGGER pl_fail BEFORE INSERT ON allocations FOR EACH ROW EXECUTE FUNCTION pl_fail()");
        try {
            assertThatThrownBy(() -> fulfill.scan(o, code, owner)).hasMessageContaining("allocate failed");
        } finally {
            jdbc.execute("DROP TRIGGER pl_fail ON allocations");
            jdbc.execute("DROP FUNCTION pl_fail()");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE code = ? AND tenant_id = ?", String.class, code, tenant))
            .isEqualTo("issued");
        assertThat(count("SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND short_code IS NULL AND status = 'available'", tenant))
            .isEqualTo(1);
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE tenant_id = ? AND event_type = 'label_bound'", tenant)).isZero();

        // And with nothing in the way the same scan binds and allocates.
        FulfillService.ScanResult r = fulfill.scan(o, code, owner);
        assertThat(r.success()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE short_code = ? AND tenant_id = ?", String.class, code, tenant))
            .isEqualTo("reserved");
        assertThat(labelEvent(code, "label_order_id")).as("printed for this very order").isNull();
    }

    @Test
    void b2_a_label_printed_for_another_order_of_the_same_variant_is_allowed_and_recorded() {
        untagged(variantA, 2);
        UUID printedFor = order("#W1", 20, true, variantA, 1);
        UUID packed = order("#W2", 10, true, variantA, 1);
        String code = printFor(printedFor).get(0);

        FulfillService.ScanResult r = fulfill.scan(packed, code, owner);
        assertThat(r.success()).isTrue();
        assertThat(r.orderItemId()).isEqualTo(items(packed).get(0));
        assertThat(labelEvent(code, "label_order_id")).isEqualTo(printedFor.toString());
        assertThat(jdbc.queryForObject("SELECT e.order_id FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.short_code = ? AND e.event_type = 'label_bound'", UUID.class, code)).isEqualTo(packed);
    }

    @Test
    void b3_wrong_variant_voided_no_stock_bound_and_typed_codes() {
        untagged(variantA, 1);
        untagged(variantB, 1);
        UUID o = order("#X1", 10, true, variantB, 1);
        LabelCodeService.IssuedCode blue = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantA, 1, null, null)), "x1-" + UUID.randomUUID()).codes().get(0);

        FulfillService.ScanResult wrong = fulfill.scan(o, blue.code(), owner);
        assertThat(wrong.code()).isEqualTo("WRONG_VARIANT");
        assertThat(wrong.message()).isEqualTo("This label is for Linen Shirt · Blue, this order needs Linen Shirt · Red");
        assertThat(wrong.messageAr()).contains("Linen Shirt · Blue");

        LabelCodeService.IssuedCode voided = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantB, 1, null, null)), "x2-" + UUID.randomUUID()).codes().get(0);
        labels.voidCodes(List.of(voided.id()), "test");
        assertThat(fulfill.scan(o, voided.code(), owner).code()).isEqualTo("CODE_VOIDED");

        LabelCodeService.IssuedCode cap = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantC, 1, null, null)), "x3-" + UUID.randomUUID()).codes().get(0);
        UUID capOrder = order("#X2", 5, true, variantC, 1);
        FulfillService.ScanResult none = fulfill.scan(capOrder, cap.code(), owner);
        assertThat(none.code()).isEqualTo("NO_UNTAGGED_STOCK");
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, cap.id())).isEqualTo("issued");

        // A bound code is a piece's short code: the normal piece path, unchanged.
        String red = printFor(o).get(0);
        String typedBarcode = jdbc.queryForObject("SELECT barcode FROM pieces WHERE variant_id = ? AND short_code IS NULL",
            String.class, variantB);
        assertThat(fulfill.scan(o, typedBarcode, owner).code()).as("typed internal code").isEqualTo("PIECE_UNTAGGED");
        assertThat(fulfill.scan(o, red, owner).success()).isTrue();
        assertThat(fulfill.scan(o, red, owner).code()).as("bound now — the piece path").isEqualTo("DUPLICATE_SCAN");
    }

    @Test
    void b4_the_highlighted_line_when_it_fits_else_the_first_line_of_the_variant_with_room() {
        untagged(variantA, 3);
        UUID o = order("#Y1", 10, true, variantA, 1, variantA, 1, variantB, 1);
        List<UUID> lines = items(o);
        UUID blueLine1 = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ? ORDER BY id LIMIT 1",
            UUID.class, o, variantA);
        UUID blueLine2 = lines.stream().filter(l -> !l.equals(blueLine1) && jdbc.queryForObject(
            "SELECT variant_id FROM order_items WHERE id = ?", UUID.class, l).equals(variantA)).findFirst().orElseThrow();
        UUID redLine = lines.stream().filter(l -> !l.equals(blueLine1) && !l.equals(blueLine2)).findFirst().orElseThrow();
        List<LabelCodeService.IssuedCode> codes = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantA, 2, null, null)), "y-" + UUID.randomUUID()).codes();

        assertThat(fulfill.scan(o, codes.get(0).code(), owner, blueLine2).orderItemId()).isEqualTo(blueLine2);
        assertThat(fulfill.scan(o, codes.get(1).code(), owner, redLine).orderItemId())
            .as("the red line can't take a blue label — the first blue line with room").isEqualTo(blueLine1);
    }

    @Test
    void b5_two_packers_binding_the_same_variant_never_share_a_piece_and_never_wait() throws Exception {
        untagged(variantA, 2);
        UUID o1 = order("#Z1", 20, true, variantA, 1);
        UUID o2 = order("#Z2", 10, true, variantA, 1);
        String c1 = printFor(o1).get(0), c2 = printFor(o2).get(0);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch aScanned = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> TenantContext.runAs(tenant, () -> tx.execute(st -> {
                String piece = fulfill.scan(o1, c1, owner).pieceId();
                aScanned.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return piece;
            })));
            assertThat(aScanned.await(30, TimeUnit.SECONDS)).isTrue();
            Future<FulfillService.ScanResult> b = pool.submit(() -> TenantContext.runAs(tenant, () -> fulfill.scan(o2, c2, worker)));
            FulfillService.ScanResult bResult = b.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertThat(bResult.success()).isTrue();
            assertThat(bResult.pieceId()).isNotNull().isNotEqualTo(a.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ── end to end ────────────────────────────────────────────────────────────

    @Test
    void e1_queue_mode_print_pdf_scan_labels_and_a_labeled_piece_then_complete() {
        List<String> caps = labeled(variantC, 1);               // C: labeled stock — no label printed for it
        untagged(variantA, 2);
        UUID o = order("#E1", 10, true, variantA, 2, variantC, 1);
        String token = jwt.issueAccessToken(worker, tenant, "worker");

        Map<String, Object> preview = post(token, "/api/v1/pack-labels/preview", Map.of("scope", "all", "sort", "oldest"), Map.class).getBody();
        assertThat(preview).containsEntry("labels", 2).containsEntry("orders", 1).containsEntry("unitsWithoutLabel", 1);
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", tenant)).as("preview issues nothing").isZero();
        Map<String, Object> printed = post(token, "/api/v1/pack-labels/print", Map.of("scope", "all", "sort", "oldest"), Map.class).getBody();
        assertThat(printed).containsEntry("labels", 2);
        ResponseEntity<byte[]> pdf = post(token, "/api/v1/pack-labels/pdf", Map.of("orderIds", List.of(o)), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);

        Map<String, Object> detail = get(token, "/api/v1/fulfill/" + o);
        assertThat(detail.get("labels")).isEqualTo(Map.of("printed", 2, "used", 0));
        for (String code : packCodes(o)) {
            assertThat(post(token, "/api/v1/fulfill/" + o + "/scan", Map.of("barcode", code), Map.class).getBody())
                .containsEntry("success", true);
        }
        assertThat(post(token, "/api/v1/fulfill/" + o + "/scan", Map.of("barcode", caps.get(0)), Map.class).getBody())
            .containsEntry("success", true).containsEntry("allComplete", true);
        assertThat(get(token, "/api/v1/fulfill/" + o).get("labels")).isEqualTo(Map.of("printed", 2, "used", 2));
        assertThat(post(token, "/api/v1/fulfill/" + o + "/complete", Map.of(), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("packed");
    }

    @Test
    void e2_waybill_mode_waybill_then_labels_completes_the_order() {
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", tenant);
        untagged(variantA, 1);
        labeled(variantB, 1);
        UUID o = order("#E2", 10, true, variantA, 1, variantB, 1);
        String red = jdbc.queryForObject("SELECT short_code FROM pieces WHERE variant_id = ? AND short_code IS NOT NULL", String.class, variantB);
        String blue = printFor(o).get(0);
        String token = jwt.issueAccessToken(worker, tenant, "worker");

        UUID session = UUID.fromString((String) post(token, "/api/v1/pack-sessions", null, Map.class).getBody().get("id"));
        assertThat(post(token, "/api/v1/pack-sessions/" + session + "/waybill", Map.of("code", tracking(o)), Map.class).getBody())
            .containsEntry("result", "opened");
        Map<String, Object> first = post(token, "/api/v1/pack-sessions/" + session + "/orders/" + o + "/scan",
            Map.of("code", blue, "orderItemId", items(o).get(0)), Map.class).getBody();
        assertThat(first).containsEntry("status", "scanned");
        Map<String, Object> last = post(token, "/api/v1/pack-sessions/" + session + "/orders/" + o + "/scan", Map.of("code", red), Map.class).getBody();
        assertThat(last).containsEntry("status", "completed");
        // Waybill mode links the scanned waybill on completion, so the order is straight away awaiting pickup.
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("awaiting_pickup");
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void r1_replacement_is_one_named_code_with_the_order_refs_and_needs_untagged_stock() throws Exception {
        untagged(variantA, 1);
        UUID o = order("#R9", 10, true, variantA, 1, variantC, 1);
        String token = jwt.issueAccessToken(worker, tenant, "worker");
        UUID blueLine = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, o, variantA);
        UUID capLine = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, o, variantC);

        ResponseEntity<byte[]> pdf = post(token, "/api/v1/pack-labels/replacement",
            Map.of("orderId", o, "orderItemId", blueLine, "idempotencyKey", "rep-1"), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> code = jdbc.queryForMap("SELECT code, kind, order_id, order_item_id, variant_id FROM label_codes WHERE tenant_id = ?", tenant);
        assertThat(code).containsEntry("kind", "replacement").containsEntry("order_id", o)
            .containsEntry("order_item_id", blueLine).containsEntry("variant_id", variantA);
        assertThat(pageTexts(pdf.getBody())).singleElement().satisfies(p -> assertThat(p).contains((String) code.get("code")));
        assertThat(get(token, "/api/v1/fulfill/" + o).get("labels")).isEqualTo(Map.of("printed", 1, "used", 0));

        ResponseEntity<Map> none = post(token, "/api/v1/pack-labels/replacement",
            Map.of("orderId", o, "orderItemId", capLine, "idempotencyKey", "rep-2"), Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody()).containsEntry("code", "NO_UNTAGGED_STOCK");
    }

    @Test
    void s1_a_label_scan_moves_the_stock_numbers_exactly_like_a_piece_scan_and_never_calls_shopify() {
        untagged(variantA, 3);
        List<String> pieces = labeled(variantB, 3);
        UUID blueOrder = order("#S9", 20, true, variantA, 1);
        UUID redOrder = order("#S8", 10, true, variantB, 1);
        String code = printFor(blueOrder).get(0);
        reset(shopifyInventory);

        var blueBefore = variantStock.forVariant(variantStock.computeAll(), variantA);
        assertThat(fulfill.scan(blueOrder, code, owner).success()).isTrue();
        var blueAfter = variantStock.forVariant(variantStock.computeAll(), variantA);
        var redBefore = variantStock.forVariant(variantStock.computeAll(), variantB);
        assertThat(fulfill.scan(redOrder, pieces.get(0), owner).success()).isTrue();
        var redAfter = variantStock.forVariant(variantStock.computeAll(), variantB);

        assertThat(blueAfter.onHand() - blueBefore.onHand()).isEqualTo(redAfter.onHand() - redBefore.onHand()).isEqualTo(-1);
        assertThat(blueAfter.committed() - blueBefore.committed()).isEqualTo(redAfter.committed() - redBefore.committed()).isEqualTo(-1);
        assertThat(blueAfter.available()).isEqualTo(blueBefore.available());
        verifyNoInteractions(shopifyInventory);
    }

    // ── switch off / isolation ────────────────────────────────────────────────

    @Test
    void f1_switch_off_endpoints_404_detail_unchanged_and_a_code_is_not_used_yet() {
        untagged(variantA, 1);
        UUID o = order("#F1", 10, true, variantA, 1);
        String code = printFor(o).get(0);
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        String token = jwt.issueAccessToken(worker, tenant, "worker");

        assertThat(post(token, "/api/v1/pack-labels/preview", Map.of("scope", "all", "sort", "oldest"), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(token, "/api/v1/pack-labels/pdf", Map.of("orderIds", List.of(o)), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> detail = get(token, "/api/v1/fulfill/" + o);
        assertThat(detail).doesNotContainKey("labels");
        assertThat((Map<String, Object>) ((List<Object>) detail.get("items")).get(0)).doesNotContainKeys("labelsUnused", "untaggedStock");
        Map<String, Object> scan = post(token, "/api/v1/fulfill/" + o + "/scan", Map.of("barcode", code), Map.class).getBody();
        assertThat(scan).containsEntry("code", "LABEL_NOT_USED_YET").doesNotContainKey("messageAr");
    }

    @Test
    void x1_app_user_sees_only_its_own_orders_and_codes() {
        untagged(variantA, 1);
        UUID o = order("#G1", 10, true, variantA, 1);
        String code = printFor(o).get(0);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        PackLabelService appLabels = new PackLabelService(app, new PackPrintBatchStore(app, 30), new LabelCodeService(app));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        TenantContext.set(other);
        assertThatThrownBy(() -> tx.execute(x -> appLabels.preview(new PackLabelService.Request(null, null, o))))
            .isInstanceOfSatisfying(LabelCodeException.class, e -> assertThat(e.code().name()).isEqualTo("ORDER_NOT_PACKABLE"));
        assertThatThrownBy(() -> tx.execute(x -> { try { return appLabels.pdf(List.of(o)); } catch (IOException e) { throw new RuntimeException(e); } }))
            .isInstanceOfSatisfying(LabelCodeException.class, e -> assertThat(e.code().name()).isEqualTo("NO_ISSUED_CODES"));
        assertThat(tx.execute(x -> appLabels.preview(new PackLabelService.Request("all", "oldest", null))).ordersConsidered()).isZero();
        Integer theirs = tx.execute(x -> app.queryForObject("SELECT count(*)::int FROM label_codes WHERE code = ?", Integer.class, code));
        assertThat(theirs).isZero();

        TenantContext.set(tenant);                              // positive control
        assertThat(tx.execute(x -> appLabels.preview(new PackLabelService.Request(null, null, o))).labels()).isEqualTo(1);
        byte[] own = tx.execute(x -> { try { return appLabels.pdf(List.of(o)); } catch (IOException e) { throw new RuntimeException(e); } });
        assertThat(own).isNotEmpty();
    }

    @Test
    void x2_a_portal_pre_connect_order_is_never_printable() {
        untagged(variantA, 1);
        UUID portal = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, shopify_order_gid, placed_at) " +
            "VALUES (?, ?, ?, '#PORTAL', 'new', 'portal_pre_connect', ?, now()) RETURNING id",
            UUID.class, tenant, store, "internal:portal:" + UUID.randomUUID(), "gid://shopify/Order/" + System.nanoTime());
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, portal, variantA);
        assertThatThrownBy(() -> packLabels.print(new PackLabelService.Request(null, null, portal), owner))
            .isInstanceOfSatisfying(LabelCodeException.class, e -> assertThat(e.code().name()).isEqualTo("ORDER_NOT_PACKABLE"));
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE order_id = ?", portal)).isZero();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    // ── "Everything ready to pack" = the whole Pick & Pack queue (live-test fix, 2026-10-25) ──────────────

    /** An order shipped with another courier: a generic forward leg in 'created', with or without a tracking number. */
    private UUID genericOrder(String number, int minutesAgo, String trackingNumber, Object... variantQty) {
        UUID o = order(number, minutesAgo, false, variantQty);
        jdbc.update("UPDATE orders SET is_self_pickup = false WHERE id = ?", o);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, source, " +
            "shopify_fulfillment_id, internal_state, shipment_leg) VALUES (?, ?, 'generic', ?, ?, 'J&T', 'shopify_fulfillment', ?, " +
            "'created', 'forward')", tenant, o, trackingNumber,
            trackingNumber.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT), "gid://shopify/Fulfillment/" + o);
        return o;
    }

    @Test
    void q1_everything_ready_to_pack_is_every_order_in_the_queue_any_courier_no_tracking_or_self_pickup() {
        untagged(variantA, 20);
        UUID bosta = order("#Q1", 50, true, variantA, 1);
        UUID generic = genericOrder("#Q2", 40, "JT-0042 77", variantA, 1);
        UUID noTracking = order("#Q3", 30, true, variantA, 1);
        jdbc.update("UPDATE shipments SET tracking_number = NULL WHERE order_id = ?", noTracking);   // waybill not issued yet
        UUID pickup = order("#Q4", 20, false, variantA, 1);
        UUID noShipment = order("#Q5", 10, false, variantA, 1);
        jdbc.update("UPDATE orders SET is_self_pickup = false WHERE id = ?", noShipment);            // not in the queue

        List<UUID> queue = fulfill.getQueue().stream().map(r -> (UUID) r.get("id")).toList();
        assertThat(queue).containsExactlyInAnyOrder(bosta, generic, noTracking, pickup);

        assertThat(sequence(packLabels.preview(all("oldest")))).containsExactly(bosta, generic, noTracking, pickup);
        assertThat(sequence(packLabels.preview(all("newest")))).containsExactly(pickup, noTracking, generic, bosta);
        PackLabelService.Summary printed = packLabels.print(all("oldest"), owner);
        assertThat(printed.labels()).isEqualTo(4);
        assertThat(printed.queueNeedsLabels()).isFalse();
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE order_id = ?", noShipment))
            .as("an order the queue doesn't list gets no label").isZero();
    }

    @Test
    void q2_waybill_scopes_stay_bosta_only_and_point_at_everything_when_they_find_nothing() {
        untagged(variantA, 20);
        UUID generic = genericOrder("#Q6", 30, "JT-9", variantA, 1);
        UUID pickup = order("#Q7", 20, false, variantA, 1);

        // A store with no Bosta waybill orders: the waybill scopes find nothing and say the queue needs labels.
        PackLabelService.Summary none = packLabels.preview(new PackLabelService.Request("new", "oldest", null));
        assertThat(none.ordersConsidered()).isZero();
        assertThat(none.queueNeedsLabels()).isTrue();
        assertThat(packLabels.preview(new PackLabelService.Request("last", null, null)).queueNeedsLabels()).isTrue();
        assertThat(packLabels.preview(all("oldest")).queueNeedsLabels()).isFalse();
        assertThat(sequence(packLabels.preview(all("oldest")))).containsExactly(generic, pickup);

        // With Bosta waybill orders, "new" is exactly the waybill print's candidates — unchanged.
        UUID bosta1 = order("#Q8", 15, true, variantA, 1);
        UUID bosta2 = order("#Q9", 5, true, variantA, 1);
        PackLabelService.Summary fresh = packLabels.preview(new PackLabelService.Request("new", "oldest", null));
        assertThat(sequence(fresh)).containsExactly(bosta1, bosta2);
        assertThat(fresh.queueNeedsLabels()).isFalse();
        assertThat(new PackPrintBatchStore(jdbc, 30).candidates("new", "oldest").stream()
            .map(PackPrintBatchStore.Candidate::orderId).toList()).containsExactly(bosta1, bosta2);

        // Nothing to label anywhere (no untagged stock left for the queue's lines) → no hint.
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenant);
        PackLabelService.Summary empty = packLabels.preview(new PackLabelService.Request("new", "oldest", null));
        assertThat(empty.orders()).isZero();
        assertThat(empty.queueNeedsLabels()).isFalse();
    }

    @Test
    void q3_the_separator_shows_another_couriers_tracking_number_then_the_order_number() throws Exception {
        untagged(variantA, 2);
        UUID generic = genericOrder("#Q10", 10, "JT-0042 77", variantA, 1);
        PackLabelService.Summary printed = packLabels.print(all("oldest"), owner);
        List<String> pages = pageTexts(packLabels.pdf(printed.parts().get(0).orderIds()));
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).lines().map(String::trim).toList()).containsExactly("JT-0042 77", "#Q10");
        assertThat(sequence(printed)).containsExactly(generic);
    }

    @Test
    void q4_a_part_never_holds_more_orders_than_one_pdf_request_takes() {
        untagged(variantA, PrintJobRunner.MAX_WAYBILLS_PER_JOB + 1);
        for (int i = 0; i <= PrintJobRunner.MAX_WAYBILLS_PER_JOB; i++) order("#M" + i, 600 - i, false, variantA, 1);
        PackLabelService.Summary s = packLabels.preview(all("oldest"));
        assertThat(s.labels()).isEqualTo(PrintJobRunner.MAX_WAYBILLS_PER_JOB + 1);
        assertThat(s.parts()).extracting(p -> p.orderIds().size())
            .containsExactly(PrintJobRunner.MAX_WAYBILLS_PER_JOB, 1);
    }

    @Test
    void q5_print_for_packing_counts_waybills_and_labels_of_the_same_scope_and_sort() {
        untagged(variantA, 10);
        UUID bosta1 = order("#W1", 40, true, variantA, 1);
        UUID generic = genericOrder("#W2", 30, "JT-77", variantA, 2);
        UUID pickup = order("#W3", 20, false, variantA, 1);
        UUID bosta2 = order("#W4", 10, true, variantA, 1);

        PackLabelService.PrintPlan all = packLabels.printPlan(all("oldest"));
        assertThat(all.waybills()).as("Bosta waybills only").isEqualTo(2);
        assertThat(all.labels().labels()).isEqualTo(5);
        assertThat(all.labels().orders()).isEqualTo(4);
        assertThat(sequence(all.labels())).containsExactly(bosta1, generic, pickup, bosta2);
        assertThat(all.lastBatchId()).isNull();
        assertThat(sequence(packLabels.printPlan(all("newest")).labels())).containsExactly(bosta2, pickup, generic, bosta1);

        PackLabelService.PrintPlan fresh = packLabels.printPlan(new PackLabelService.Request("new", "oldest", null));
        assertThat(fresh.waybills()).isEqualTo(2);
        assertThat(sequence(fresh.labels())).containsExactly(bosta1, bosta2);

        UUID batch = jdbc.queryForObject("INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, " +
            "waybill_count, order_guaranteed) VALUES (?, 1, ?, 'A4', 'oldest', 'new', 1, true) RETURNING id", UUID.class, tenant, owner);
        jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
            "SELECT ?, tenant_id, order_id, id, tracking_number, 1 FROM shipments WHERE order_id = ?", batch, bosta2);
        PackLabelService.PrintPlan last = packLabels.printPlan(new PackLabelService.Request("last", null, null));
        assertThat(last.lastBatchId()).isEqualTo(batch);
        assertThat(last.waybills()).isEqualTo(1);
        assertThat(sequence(last.labels())).containsExactly(bosta2);
        assertThat(packLabels.printPlan(new PackLabelService.Request("new", "oldest", null)).waybills())
            .as("the printed one is no longer new").isEqualTo(1);
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", tenant)).as("a plan issues nothing").isZero();
    }

    @Test
    void q6_the_plan_says_when_only_everything_ready_to_pack_has_labels() {
        untagged(variantA, 2);
        genericOrder("#W9", 10, "JT-9", variantA, 1);                       // the queue's only order: another courier
        PackLabelService.PrintPlan fresh = packLabels.printPlan(new PackLabelService.Request("new", "oldest", null));
        assertThat(fresh.labels().labels()).isZero();
        assertThat(fresh.labels().queueNeedsLabels()).isTrue();
        assertThat(packLabels.printPlan(new PackLabelService.Request("last", null, null)).labels().queueNeedsLabels()).isTrue();
        PackLabelService.PrintPlan all = packLabels.printPlan(all("oldest"));
        assertThat(all.labels().labels()).isEqualTo(1);
        assertThat(all.labels().queueNeedsLabels()).isFalse();
    }

    // ── slice 5: returns for pieces labeled at packing ───────────────────────────

    /** count-only → printed → bound at the pack scan → packed → shipped → delivered. The piece id and its code. */
    private String[] packedAndDelivered(String number) {
        untagged(variantA, 1);
        UUID o = order(number, 10, true, variantA, 1);
        String code = printFor(o).get(0);
        assertThat(fulfill.scan(o, code, owner).success()).isTrue();
        fulfill.complete(o, owner);
        String piece = jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, code);
        UUID shipment = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND shipment_leg = 'forward'", UUID.class, o);
        ledger.transition(piece, PieceStatus.PACKED, PieceStatus.WITH_COURIER, "courier_update", owner,
            new TransitionContext(o, shipment, null, o, null, null));      // as the pickup close / Bosta webhook do
        ledger.transition(piece, PieceStatus.WITH_COURIER, PieceStatus.DELIVERED, "courier_update", owner,
            new TransitionContext(o, shipment, null, o, null, null));      // as the pickup close / Bosta webhook do
        return new String[] { piece, code, o.toString() };
    }

    @Test
    void rt1_a_piece_labeled_at_packing_returns_restocks_and_counts_as_return_scan_evidence() {
        String[] p = packedAndDelivered("#RT1");
        String piece = p[0], code = p[1];
        UUID order = UUID.fromString(p[2]);
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_bound'", piece)).isEqualTo(1);
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'returning', 'return') RETURNING id", UUID.class, tenant, order, String.valueOf(TRACKING.incrementAndGet()));

        UUID rs = returnSessions.createSession(null, worker);
        Map<String, Object> scanned = returnSessions.scan(rs, code, main, worker);      // found by its code, like any piece
        assertThat(scanned.toString()).contains(piece);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece))
            .isEqualTo("return_pending_inspection");

        returnSessions.disposition(rs, piece, "restock", null, main, worker, "worker");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece)).as("keeps its code").isEqualTo(code);

        returnSessions.close(rs, worker);
        assertThat(jdbc.queryForObject("SELECT return_intake_completed_at FROM shipments WHERE id = ?", java.time.OffsetDateTime.class, leg))
            .as("the canonical return scan-evidence rule counts the scan").isNotNull();
    }

    @Test
    void rt2_a_torn_label_on_return_reprints_the_same_code() throws Exception {
        String[] p = packedAndDelivered("#RT2");
        String piece = p[0], code = p[1];
        UUID rs = returnSessions.createSession(null, worker);
        String token = jwt.issueAccessToken(worker, tenant, "worker");
        ResponseEntity<byte[]> pdf = post(token, "/api/v1/returns/sessions/" + rs + "/pieces/" + piece + "/reprint-label", Map.of(), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageTexts(pdf.getBody())).singleElement().satisfies(t -> assertThat(t).contains(code).contains("Linen Shirt"));
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece)).isEqualTo(1);
        assertThat(count("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", tenant)).as("no new code").isEqualTo(1);
    }

    @Test
    void rt3_a_failed_reprint_never_leaves_a_label_reprinted_event() throws Exception {
        String[] p = packedAndDelivered("#RT3");
        String piece = p[0];
        UUID rs = returnSessions.createSession(null, worker);
        assertThatThrownBy(() -> returnSessions.reprintLabel(rs, piece, worker, () -> { throw new IOException("printer jammed"); }))
            .isInstanceOf(IOException.class);
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece)).isZero();
        byte[] ok = returnSessions.reprintLabel(rs, piece, worker, () -> labelService.generatePieceLabel(piece, null, null));
        assertThat(ok).isNotEmpty();
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece)).isEqualTo(1);
    }

    @Test
    void rt5_the_old_gated_reprint_records_its_event_only_after_the_pdf_and_keeps_its_gate() throws Exception {
        String[] p = packedAndDelivered("#RT5");
        String piece = p[0], code = p[1];
        UUID order = UUID.fromString(p[2]);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'returning', 'return')", tenant, order, String.valueOf(TRACKING.incrementAndGet()));
        // The gate: a delivered piece is refused, and leaves no event even though its PDF rendered.
        assertThatThrownBy(() -> returnSessions.reprintGated(piece, worker, () -> labelService.generatePieceLabel(piece, null, null)))
            .hasMessageContaining("return_pending_inspection or damaged");
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece)).isZero();

        returnSessions.scan(returnSessions.createSession(null, worker), code, main, worker);   // → return_pending_inspection
        assertThatThrownBy(() -> returnSessions.reprintGated(piece, worker, () -> { throw new IOException("printer jammed"); }))
            .isInstanceOf(IOException.class);
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece))
            .as("a failed print leaves no event").isZero();

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(worker, tenant, "worker"));
        ResponseEntity<byte[]> pdf = rest.exchange("http://localhost:" + port + "/api/v1/returns/pieces/" + piece + "/label",
            HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageTexts(pdf.getBody())).singleElement().satisfies(t -> assertThat(t).contains(code));
        assertThat(count("SELECT count(*)::int FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rt4_lookup_names_the_label_and_where_it_went_on() {
        String[] p = packedAndDelivered("#RT4");
        Map<String, Object> r = lookup.lookupPiece(p[1], false);
        Map<String, Object> bound = ((List<Map<String, Object>>) r.get("timeline")).stream()
            .filter(e -> "label_bound".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertThat(bound).containsEntry("phraseKey", "label_bound_packing").containsEntry("labelCode", p[1])
            .containsEntry("orderNumber", "#RT4").containsEntry("actor", "O");

        // In Tag stock: the event carries the tag session.
        untagged(variantB, 1);
        LabelCodeService.IssuedCode c = labels.issue(owner, "tag", List.of(new LabelCodeService.Line(variantB, 1, null, null)),
            "rt4-tag").codes().get(0);
        ledger.bindLabel(c.id(), owner, InventoryLedger.BindRef.tagSession(UUID.randomUUID(), false), c.code());
        Map<String, Object> tagBound = ((List<Map<String, Object>>) lookup.lookupPiece(c.code(), false).get("timeline")).stream()
            .filter(e -> "label_bound".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertThat(tagBound).containsEntry("phraseKey", "label_bound_tag").containsEntry("labelCode", c.code());
    }

    private <T> ResponseEntity<T> post(String token, String path, Object body, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, h), type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }
}
