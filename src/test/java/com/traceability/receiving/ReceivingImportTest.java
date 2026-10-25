package com.traceability.receiving;

import com.traceability.ApiException;
import com.traceability.identity.JwtService;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Receiving import (slice 2b) — Shopify inventory export → a receiving session's lines.
 *
 *   f1  the sample export as-is: several locations → the whole file refused, nothing written
 *   f2  one location of the sample, values filled, columns SHUFFLED (headers matched by name): SKU,
 *       Handle + options, Default Title, title fallback; not stocked / 0 / empty skipped; negative and
 *       non-integer unmatched; gift card; not in Traced; duplicate rows; the "same as Shopify" flag
 *   f3  the same table as .xlsx → the same preview
 *   f4  SKU rules: a SKU shared by two variants falls through to Handle + options; a unique SKU wins
 *   a1  apply adds to an existing line and creates the others; nothing is received (no pieces, no
 *       Shopify) until finalize; finalize is today's (codes, +N once)
 *   a2  preview writes nothing; apply validation (foreign / unknown variant, bad quantity, empty)
 *   g1  count-only session: import refused with the flag off, allowed with it; print & stick always allowed
 *   e1  whole-file errors: not an export, old .xls, too large, zip bomb, unreadable zip, not UTF-8, readable 400s
 *   t1  template (.xlsx and .csv): Shopify's headers in order, active variants only, sorted by title then
 *       options, Location = main warehouse, On hand (current) = available there, On hand (new) empty
 *   t2  template round-trip: download → fill two rows → import → preview has exactly those two
 *   x1  tenant isolation under app_user: another tenant's catalog never matches (same-tenant positive control);
 *       another tenant's session is 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingImportTest {

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

    static final String FIXTURE = "/fixtures/shopify/inventory_export_sample.csv";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingImportService imports;
    @Autowired ReceivingService receiving;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, main, other, otherOwner, otherMain;
    final Map<String, UUID> v = new HashMap<>();   // "handle|option" → variant id

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); main = UUID.randomUUID();
        other = UUID.randomUUID(); otherOwner = UUID.randomUUID(); otherMain = UUID.randomUUID();
        UUID store = seedTenant(tenant, owner, main, "RI");
        UUID otherStore = seedTenant(other, otherOwner, otherMain, "RX");

        product(tenant, store, "the-archived-snowboard", "The Archived Snowboard", "active", null, List.of("Title"),
            new String[][] { { "Default Title", null } });
        product(tenant, store, "the-complete-snowboard", "The Complete Snowboard", "active", null, List.of("Color"),
            new String[][] { { "Ice", null }, { "Dawn", null }, { "Powder", null }, { "Electric", null }, { "Sunset", null } });
        product(tenant, store, "gift-card", "Gift Card", "active", "gift", List.of("Denominations"),
            new String[][] { { "$10", null }, { "$25", null }, { "$50", null }, { "$100", null } });
        product(tenant, store, "the-multi-managed-snowboard", "The Multi-managed Snowboard", "active", null, List.of("Title"),
            new String[][] { { "Default Title", "sku-managed-1" } });
        product(tenant, store, "the-3p-fulfilled-snowboard", "The 3p Fulfilled Snowboard", "active", null, List.of("Title"),
            new String[][] { { "Default Title", "sku-hosted-1" } });
        // Imported through the GraphQL catalog: no handle / options stored — matched by title + variant title.
        product(tenant, store, null, "The Minimal Snowboard", "active", null, null, new String[][] { { "Default Title", null } });
        product(tenant, store, "the-draft-snowboard", "The Draft Snowboard", "draft", null, List.of("Title"),
            new String[][] { { "Default Title", null } });
        // Same handle, another tenant — must never match.
        product(other, otherStore, "the-hidden-snowboard", "The Hidden Snowboard", "active", null, List.of("Title"),
            new String[][] { { "Default Title", null } });
    }

    private UUID seedTenant(UUID t, UUID user, UUID mainLoc, String p) {
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            user, t, p.toLowerCase() + "@import.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, t, p.toLowerCase() + "-import.myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, ?, 'warehouse', true, true)",
            mainLoc, t, p + " Main");
        return store;
    }

    /** variants: {option value or "Default Title", sku}. productType "gift" → a gift card product. */
    private void product(UUID t, UUID store, String handle, String title, String status, String productType,
                         List<String> optionNames, String[][] variants) {
        UUID pid = UUID.randomUUID();
        String raw = null;
        if (handle != null) {
            StringBuilder opts = new StringBuilder("[");
            for (int i = 0; i < optionNames.size(); i++) opts.append(i > 0 ? "," : "").append("{\"name\":\"").append(optionNames.get(i)).append("\"}");
            opts.append("]");
            raw = "{\"handle\":\"" + handle + "\",\"options\":" + opts + ("gift".equals(productType) ? ",\"gift_card\":true" : "") + "}";
        }
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw, image_url) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)", pid, t, store, "P-" + pid, title, status, raw, "https://img/" + (handle == null ? "x" : handle));
        for (String[] vv : variants) {
            UUID vid = UUID.randomUUID();
            String vraw = handle == null ? null : "{\"option1\":\"" + vv[0] + "\"}";
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, raw) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                vid, t, pid, "V-" + vid, vv[0], vv[1], vraw);
            v.put((handle == null ? title : handle) + "|" + vv[0] + (t.equals(other) ? "|other" : ""), vid);
        }
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id IN (?, ?)", tenant, other);
        reset(shopifyInventory);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (UUID t : List.of(tenant, other)) {
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM receipts WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_counters WHERE tenant_id = ?", t);
            jdbc.update("UPDATE variants SET sku = NULL WHERE tenant_id = ? AND sku = 'DUP-1'", t);
        }
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private byte[] sample() throws Exception {
        try (var in = getClass().getResourceAsStream(FIXTURE)) { return in.readAllBytes(); }
    }

    /** The sample, parsed by name, kept to one location, with "On hand (new)" set per handle|option1. */
    private List<List<String>> oneLocation(Map<String, String> onHandNew) throws Exception {
        List<List<String>> all = ShopifyInventoryFile.readCsv(sample());
        List<String> h = all.get(0);
        int loc = h.indexOf("Location"), handle = h.indexOf("Handle"), opt = h.indexOf("Option1 Value"), nw = h.indexOf("On hand (new)");
        List<List<String>> out = new ArrayList<>();
        out.add(h);
        for (List<String> row : all.subList(1, all.size())) {
            if (!"Shop location".equals(row.get(loc))) continue;
            List<String> r = new ArrayList<>(row);
            r.set(nw, onHandNew.getOrDefault(row.get(handle) + "|" + row.get(opt), ""));
            out.add(r);
        }
        return out;
    }

    /** Columns reversed — the import must match headers by name. */
    private static List<List<String>> reversed(List<List<String>> table) {
        return table.stream().map(r -> { List<String> c = new ArrayList<>(r); Collections.reverse(c); return c; }).toList();
    }

    private UUID session(String mode) {
        return receiving.createSession(owner, main, "IMP-" + UUID.randomUUID(), null, null, mode);
    }

    private static final Map<String, String> VALUES = Map.ofEntries(
        Map.entry("the-archived-snowboard|Default Title", "5"),
        Map.entry("the-complete-snowboard|Ice", "3"),
        Map.entry("the-complete-snowboard|Dawn", "10"),          // = On hand (current) → flagged
        Map.entry("the-complete-snowboard|Powder", "not stocked"),
        Map.entry("the-complete-snowboard|Electric", "0"),
        Map.entry("the-complete-snowboard|Sunset", "-2"),
        Map.entry("gift-card|$10", "4"),
        Map.entry("the-minimal-snowboard|Default Title", "7"),   // no handle in Traced → title rule
        Map.entry("the-multi-managed-snowboard|Default Title", "2"),
        Map.entry("the-3p-fulfilled-snowboard|Default Title", "1.5"),
        Map.entry("selling-plans-ski-wax|Selling Plans Ski Wax", "3"),
        Map.entry("the-videographer-snowboard|Default Title", "1"));

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void f1_the_sample_export_has_several_locations_and_is_refused() throws Exception {
        UUID s = session(null);
        assertCode(() -> imports.preview(s, "inventory_export_1.csv", sample()), "IMPORT_SEVERAL_LOCATIONS");
        ResponseEntity<Map> http = upload(s, "inventory_export_1.csv", sample());
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getBody()).containsEntry("message_en", "This file has several locations. Export one location only from Shopify.");
        assertThat(count("SELECT count(*) FROM receipt_lines WHERE receipt_id = ?", s)).isZero();
    }

    @Test
    void f2_one_location_with_shuffled_columns() throws Exception {
        UUID s = session(null);
        List<List<String>> table = oneLocation(VALUES);
        // A second copy of the videographer row → both rows are duplicates (it's not in Traced either, so
        // use the archived one: a real Traced variant twice).
        List<String> header = table.get(0);
        List<String> dupe = new ArrayList<>(table.stream().filter(r -> r.get(0).equals("the-archived-snowboard")).findFirst().orElseThrow());
        dupe.set(header.indexOf("On hand (new)"), "1");
        List<List<String>> withDup = new ArrayList<>(table);
        withDup.add(dupe);

        ReceivingImportService.Preview p = imports.preview(s, "shop.csv", ShopifyInventoryFile.writeCsv(reversed(withDup)));

        assertThat(p.location()).isEqualTo("Shop location");
        Map<UUID, ReceivingImportService.MatchedRow> m = new HashMap<>();
        p.matched().forEach(r -> m.put(r.variantId(), r));
        assertThat(m.keySet()).containsExactlyInAnyOrder(v.get("the-complete-snowboard|Ice"), v.get("the-complete-snowboard|Dawn"),
            v.get("The Minimal Snowboard|Default Title"), v.get("the-multi-managed-snowboard|Default Title"));
        assertThat(m.get(v.get("the-complete-snowboard|Ice"))).extracting("quantity", "matchedBy", "sameAsShopify").containsExactly(3, "handle", false);
        assertThat(m.get(v.get("the-complete-snowboard|Dawn"))).extracting("quantity", "sameAsShopify").containsExactly(10, true);
        assertThat(m.get(v.get("The Minimal Snowboard|Default Title"))).extracting("quantity", "matchedBy").containsExactly(7, "title");
        assertThat(m.get(v.get("the-multi-managed-snowboard|Default Title"))).extracting("quantity", "matchedBy").containsExactly(2, "sku");
        assertThat(p.units()).isEqualTo(3 + 10 + 7 + 2);

        Map<String, String> reasons = new TreeMap<>();
        p.unmatched().forEach(r -> reasons.merge(r.handle() + "|" + r.options(), r.reason(), (a, b) -> a + "," + b));
        assertThat(reasons).containsEntry("the-complete-snowboard|Sunset", "negative")
            .containsEntry("gift-card|$10", "gift_card")
            .containsEntry("the-3p-fulfilled-snowboard|Default Title", "not_integer")
            .containsEntry("selling-plans-ski-wax|Selling Plans Ski Wax", "not_in_traced")
            .containsEntry("the-videographer-snowboard|Default Title", "not_in_traced")
            .containsEntry("the-archived-snowboard|Default Title", "duplicate,duplicate");
        // not stocked + 0 + every empty "On hand (new)" of the other 13 rows are skipped, not listed.
        assertThat(p.skipped()).isEqualTo(25 - 12 + 2);
        assertThat(count("SELECT count(*) FROM receipt_lines WHERE receipt_id = ?", s)).as("preview writes nothing").isZero();
    }

    @Test
    void c1_an_empty_on_hand_new_column_is_named_and_on_hand_current_is_used_only_when_asked() throws Exception {
        UUID s = session(null);
        byte[] raw = ShopifyInventoryFile.writeCsv(oneLocation(Map.of()));     // a raw export: "On hand (new)" empty

        ReceivingImportService.Preview p = imports.preview(s, "raw.csv", raw);
        assertThat(p.newColumnEmpty()).isTrue();
        assertThat(p.quantityColumn()).isEqualTo("new");
        assertThat(p.matched()).isEmpty();

        ReceivingImportService.Preview cur = imports.preview(s, "raw.csv", raw, "current");
        assertThat(cur.quantityColumn()).isEqualTo("current");
        assertThat(cur.newColumnEmpty()).as("still describes the file").isTrue();
        assertThat(cur.matched()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.quantity()).isEqualTo(r.onHandCurrent());
            assertThat(r.sameAsShopify()).as("the quantity IS Shopify's number").isFalse();
        });
        assertThat(cur.matched()).filteredOn(r -> r.variantId().equals(v.get("the-complete-snowboard|Dawn")))
            .singleElement().extracting(ReceivingImportService.MatchedRow::quantity).isEqualTo(10);
        assertThat(cur.units()).isEqualTo(cur.matched().stream().mapToInt(ReceivingImportService.MatchedRow::quantity).sum());
        // Same matching / skipping rules: the gift card is still refused, nothing goes to a row twice.
        assertThat(cur.unmatched()).extracting(ReceivingImportService.UnmatchedRow::reason).doesNotContain("duplicate");

        // Over HTTP: column=current.
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(raw) { @Override public String getFilename() { return "raw.csv"; } });
        body.add("column", "current");
        HttpHeaders h = auth();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> http = rest.exchange(base() + "/api/v1/receiving/sessions/" + s + "/import/preview", HttpMethod.POST,
            new HttpEntity<>(body, h), Map.class);
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getBody()).containsEntry("quantityColumn", "current").containsEntry("units", cur.units());

        assertCode(() -> imports.preview(s, "raw.csv", raw, "available"), "IMPORT_BAD_COLUMN");
        assertThat(count("SELECT count(*) FROM receipt_lines WHERE receipt_id = ?", s)).as("previews write nothing").isZero();
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", tenant)).isZero();
    }

    @Test
    void c2_a_normal_file_is_unaffected() throws Exception {
        UUID s = session(null);
        ReceivingImportService.Preview p = imports.preview(s, "shop.csv", ShopifyInventoryFile.writeCsv(oneLocation(VALUES)));
        assertThat(p.newColumnEmpty()).isFalse();
        assertThat(p.quantityColumn()).isEqualTo("new");
        assertThat(p.matched()).extracting(ReceivingImportService.MatchedRow::variantId)
            .contains(v.get("the-complete-snowboard|Ice"), v.get("the-complete-snowboard|Dawn"));
        assertThat(p.matched()).filteredOn(ReceivingImportService.MatchedRow::sameAsShopify).hasSize(1);   // Dawn, as before
        // Only "not stocked" rows (and no other stocked row) → not "empty".
        Map<String, String> notStocked = new HashMap<>();
        oneLocation(Map.of()).stream().skip(1).forEach(r -> notStocked.put(r.get(0) + "|" + r.get(3), "not stocked"));
        assertThat(imports.preview(s, "ns.csv", ShopifyInventoryFile.writeCsv(oneLocation(notStocked))).newColumnEmpty()).isFalse();
    }

    @Test
    void f3_xlsx_gives_the_same_preview() throws Exception {
        UUID s = session(null);
        List<List<String>> table = oneLocation(VALUES);
        ReceivingImportService.Preview csv = imports.preview(s, "a.csv", ShopifyInventoryFile.writeCsv(table));
        ReceivingImportService.Preview xlsx = imports.preview(s, "a.xlsx", ShopifyInventoryFile.writeXlsx(reversed(table), Set.of()));
        assertThat(xlsx.matched()).isEqualTo(csv.matched());
        assertThat(xlsx.unmatched()).isEqualTo(csv.unmatched());
        assertThat(xlsx.skipped()).isEqualTo(csv.skipped());
    }

    @Test
    void f4_a_shared_sku_falls_through_to_handle_and_options() throws Exception {
        UUID s = session(null);
        // Two variants share "DUP-1": the SKU can't decide, Handle + options can.
        jdbc.update("UPDATE variants SET sku = 'DUP-1' WHERE id IN (?, ?)",
            v.get("the-complete-snowboard|Ice"), v.get("the-archived-snowboard|Default Title"));
        List<List<String>> t = oneLocation(Map.of("the-complete-snowboard|Ice", "4"));
        int sku = t.get(0).indexOf("SKU");
        t.stream().skip(1).filter(r -> r.get(0).equals("the-complete-snowboard") && r.contains("Ice")).forEach(r -> r.set(sku, "DUP-1"));
        ReceivingImportService.Preview p = imports.preview(s, "a.csv", ShopifyInventoryFile.writeCsv(t));
        assertThat(p.matched()).singleElement().extracting("variantId", "matchedBy")
            .containsExactly(v.get("the-complete-snowboard|Ice"), "handle");

        // A SKU only one variant has wins, whatever the handle says.
        List<List<String>> u = oneLocation(Map.of("the-videographer-snowboard|Default Title", "6"));
        u.stream().skip(1).filter(r -> r.get(0).equals("the-videographer-snowboard")).forEach(r -> r.set(sku, "sku-managed-1"));
        assertThat(imports.preview(s, "b.csv", ShopifyInventoryFile.writeCsv(u)).matched()).singleElement()
            .extracting("variantId", "matchedBy").containsExactly(v.get("the-multi-managed-snowboard|Default Title"), "sku");
    }

    @Test
    void a1_apply_adds_to_existing_lines_and_nothing_is_received_until_finalize() {
        UUID s = session(null);
        UUID archived = v.get("the-archived-snowboard|Default Title"), ice = v.get("the-complete-snowboard|Ice");
        receiving.addLine(s, archived, 2);
        ReceivingImportService.Applied a = imports.apply(s, List.of(
            new ReceivingImportService.ApplyLine(archived, 5), new ReceivingImportService.ApplyLine(ice, 3)));
        assertThat(a).extracting("linesAdded", "linesUpdated", "units").containsExactly(1, 1, 8);
        assertThat(jdbc.queryForObject("SELECT quantity FROM receipt_lines WHERE receipt_id = ? AND variant_id = ?", Integer.class, s, archived))
            .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT quantity FROM receipt_lines WHERE receipt_id = ? AND variant_id = ?", Integer.class, s, ice))
            .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ?", s)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, s)).isEqualTo("open");
        verifyNoInteractions(shopifyInventory);

        // Finalize is today's: pieces with codes, Shopify +N once.
        assertThat(receiving.finalize(s, owner)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND short_code IS NOT NULL", s)).isEqualTo(10);
        verify(shopifyInventory, times(1)).onReceivingSessionClose(tenant, s, main, Map.of(archived, 7, ice, 3));
    }

    @Test
    void a2_apply_validation() {
        UUID s = session(null);
        UUID archived = v.get("the-archived-snowboard|Default Title");
        assertCode(() -> imports.apply(s, List.of()), "IMPORT_NOTHING_TO_ADD");
        assertCode(() -> imports.apply(s, List.of(new ReceivingImportService.ApplyLine(archived, 0))), "IMPORT_BAD_QUANTITY");
        assertCode(() -> imports.apply(s, List.of(new ReceivingImportService.ApplyLine(archived, ReceivingImportService.MAX_QUANTITY + 1))),
            "IMPORT_BAD_QUANTITY");
        assertCode(() -> imports.apply(s, List.of(new ReceivingImportService.ApplyLine(v.get("the-hidden-snowboard|Default Title|other"), 1))),
            "IMPORT_UNKNOWN_VARIANT");
        assertCode(() -> imports.apply(s, List.of(new ReceivingImportService.ApplyLine(UUID.randomUUID(), 1))), "IMPORT_UNKNOWN_VARIANT");
        assertThat(count("SELECT count(*) FROM receipt_lines WHERE receipt_id = ?", s)).isZero();

        receiving.addLine(s, archived, 1);
        receiving.finalize(s, owner);
        assertThatThrownBy(() -> imports.apply(s, List.of(new ReceivingImportService.ApplyLine(archived, 1)))).hasMessageContaining("409");
    }

    @Test
    void g1_count_only_import_needs_the_flag_print_and_stick_never_does() throws Exception {
        UUID co = session(ReceivingService.MODE_COUNT_ONLY);
        UUID ps = session(null);
        byte[] file = ShopifyInventoryFile.writeCsv(oneLocation(Map.of("the-complete-snowboard|Ice", "2")));
        assertThat(imports.preview(co, "a.csv", file).matched()).hasSize(1);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        assertCode(() -> imports.preview(co, "a.csv", file), "COUNT_ONLY_NOT_AVAILABLE");
        assertCode(() -> imports.apply(co, List.of(new ReceivingImportService.ApplyLine(v.get("the-complete-snowboard|Ice"), 2))),
            "COUNT_ONLY_NOT_AVAILABLE");
        assertThat(imports.preview(ps, "a.csv", file).matched()).hasSize(1);
        assertThat(imports.apply(ps, List.of(new ReceivingImportService.ApplyLine(v.get("the-complete-snowboard|Ice"), 2))).units()).isEqualTo(2);
    }

    @Test
    void e1_whole_file_errors_are_readable() throws Exception {
        UUID s = session(null);
        assertCode(() -> imports.preview(s, "a.csv", "Name,Qty\nShirt,3\n".getBytes(StandardCharsets.UTF_8)), "IMPORT_NOT_SHOPIFY_EXPORT");
        assertCode(() -> imports.preview(s, "a.xls", new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0 }), "IMPORT_OLD_EXCEL");
        assertCode(() -> imports.preview(s, "a.csv", new byte[ShopifyInventoryFile.MAX_BYTES + 1]), "IMPORT_FILE_TOO_LARGE");
        assertCode(() -> imports.preview(s, "a.csv", new byte[0]), "IMPORT_EMPTY_FILE");
        assertCode(() -> imports.preview(s, "a.csv", new byte[] { 'H', (byte) 0xC3, (byte) 0x28 }), "IMPORT_NOT_UTF8");
        assertCode(() -> imports.preview(s, "a.xlsx", "PK\u0003\u0004garbage".getBytes(StandardCharsets.ISO_8859_1)), "IMPORT_UNREADABLE");

        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bomb)) {
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 70; i++) z.write(zeros);    // 70 MB of zeros → ~70 KB zipped
            z.closeEntry();
        }
        assertThat(bomb.size()).isLessThan(ShopifyInventoryFile.MAX_BYTES);
        assertCode(() -> imports.preview(s, "bomb.xlsx", bomb.toByteArray()), "IMPORT_FILE_TOO_LARGE");

        ResponseEntity<Map> http = upload(s, "a.csv", "Name,Qty\n".getBytes(StandardCharsets.UTF_8));
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getBody()).containsEntry("code", "IMPORT_NOT_SHOPIFY_EXPORT");
        assertThat((String) http.getBody().get("message_en")).startsWith("This doesn't look like a Shopify inventory export — missing columns: Handle, Title");
        assertThat((String) http.getBody().get("message_ar")).isNotBlank();
    }

    @Test
    void t1_template_has_shopifys_layout_and_traced_stock() throws Exception {
        UUID s = session(null);
        receiving.addLine(s, v.get("the-complete-snowboard|Ice"), 4);
        receiving.finalize(s, owner);

        for (String format : List.of("xlsx", "csv")) {
            ResponseEntity<byte[]> r = rest.exchange(base() + "/api/v1/receiving/import/template?format=" + format,
                HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<List<String>> t = "xlsx".equals(format) ? ShopifyInventoryFile.readXlsx(r.getBody()) : ShopifyInventoryFile.readCsv(r.getBody());
            assertThat(t.get(0)).as(format).containsExactlyElementsOf(ShopifyInventoryFile.SHOPIFY_HEADERS);
            List<String> titles = t.stream().skip(1).map(row -> row.get(1)).distinct().toList();
            assertThat(titles).as("active only, sorted by title").containsExactly("Gift Card", "The 3p Fulfilled Snowboard",
                "The Archived Snowboard", "The Complete Snowboard", "The Minimal Snowboard", "The Multi-managed Snowboard");
            List<String> complete = t.stream().skip(1).filter(row -> row.get(1).equals("The Complete Snowboard")).map(row -> row.get(3)).toList();
            assertThat(complete).as("then options").containsExactly("Dawn", "Electric", "Ice", "Powder", "Sunset");
            List<String> ice = t.stream().filter(row -> row.size() > 3 && row.get(3).equals("Ice")).findFirst().orElseThrow();
            assertThat(ice.get(0)).isEqualTo("the-complete-snowboard");
            assertThat(ice.get(2)).isEqualTo("Color");
            assertThat(ice.get(11)).isEqualTo("RI Main");
            assertThat(ice.get(17)).isEqualTo("4");
            assertThat(ice.size() <= 18 || ice.get(18).isEmpty()).as("On hand (new) empty").isTrue();
            List<String> minimal = t.stream().filter(row -> row.size() > 1 && row.get(1).equals("The Minimal Snowboard")).findFirst().orElseThrow();
            assertThat(minimal.get(0)).as("no stored handle → empty").isEmpty();
            assertThat(minimal.get(2)).isEqualTo("Title");
            assertThat(minimal.get(3)).isEqualTo("Default Title");
        }
    }

    @Test
    void t2_template_round_trip() throws Exception {
        UUID s = session(null);
        ResponseEntity<byte[]> r = rest.exchange(base() + "/api/v1/receiving/import/template", HttpMethod.GET,
            new HttpEntity<>(auth()), byte[].class);
        List<List<String>> t = new ArrayList<>(ShopifyInventoryFile.readXlsx(r.getBody()).stream().map(row -> {
            List<String> c = new ArrayList<>(row);
            while (c.size() < ShopifyInventoryFile.SHOPIFY_HEADERS.size()) c.add("");
            return c;
        }).toList());
        int nw = t.get(0).indexOf("On hand (new)");
        for (List<String> row : t) {
            if (row.get(1).equals("The Minimal Snowboard")) row.set(nw, "12");
            if (row.get(3).equals("Sunset")) row.set(nw, "3");
        }
        ReceivingImportService.Preview p = imports.preview(s, "filled.xlsx", ShopifyInventoryFile.writeXlsx(t, Set.of(17, 18)));
        assertThat(p.unmatched()).isEmpty();
        assertThat(p.matched()).extracting("variantId", "quantity").containsExactlyInAnyOrder(
            tuple(v.get("The Minimal Snowboard|Default Title"), 12), tuple(v.get("the-complete-snowboard|Sunset"), 3));
        assertThat(p.skipped()).isEqualTo(t.size() - 1 - 2);
    }

    @Test
    void x1_another_tenants_catalog_never_matches_under_app_user() throws Exception {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        ReceivingImportService asApp = new ReceivingImportService(new JdbcTemplate(ds));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        UUID s = session(null);
        byte[] file = ShopifyInventoryFile.writeCsv(oneLocation(Map.of(
            "the-hidden-snowboard|Default Title", "3", "the-archived-snowboard|Default Title", "2")));
        ReceivingImportService.Preview p = tx.execute(x -> asApp.preview(s, "a.csv", file));
        assertThat(p.matched()).singleElement().extracting("variantId").isEqualTo(v.get("the-archived-snowboard|Default Title"));   // positive control
        assertThat(p.unmatched()).singleElement().extracting("handle", "reason").containsExactly("the-hidden-snowboard", "not_in_traced");

        TenantContext.set(other);
        assertThatThrownBy(() -> tx.execute(x -> asApp.preview(s, "a.csv", file))).hasMessageContaining("404");
        UUID theirs = receiving.createSession(otherOwner, otherMain, "RX-1", null, null);
        TenantContext.set(tenant);
        assertThatThrownBy(() -> imports.apply(theirs, List.of(new ReceivingImportService.ApplyLine(v.get("the-archived-snowboard|Default Title"), 1))))
            .hasMessageContaining("404");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map> upload(UUID session, String name, byte[] bytes) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return name; } });
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));   // fresh: a token lives 30 min, a class can run longer in the full suite
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(base() + "/api/v1/receiving/sessions/" + session + "/import/preview", HttpMethod.POST,
            new HttpEntity<>(body, h), Map.class);
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));   // fresh: a token lives 30 min, a class can run longer in the full suite
        return h;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(code));
    }

    private String base() { return "http://localhost:" + port; }
}
