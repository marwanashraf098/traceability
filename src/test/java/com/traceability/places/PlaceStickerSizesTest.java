package com.traceability.places;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.traceability.TestPostgres;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Place sticker sizes (V199) — SMALL 50×25 mm, A6 (one per page or four per A4 sheet) and A4 place stickers.
 *
 *   z1  every size renders at its page size and its barcode decodes back to the place's Q code — at 203 DPI, and
 *       A4 also at 300 DPI; bar width and quiet zone are within the Code 128 spec
 *   z2  A6 four per A4 sheet: 4 per page in tree order (top left, top right, bottom left, bottom right), the rest on
 *       the next page
 *   z3  Damaged: the red band on every size (a box marked Damaged, and a shelf inside a Damaged zone); the barcode
 *       still decodes; a place not Damaged has no band
 *   z4  defaults per kind (shelf SMALL, box A6, rack A6, zone A4); printing remembers each printed kind's choice for
 *       the tenant only, and leaves the other kinds' choices alone
 *   z5  a whole zone in one PDF: each kind in its own size — 50×25 shelves (with the zone separator), then A6, then
 *       the A4 zone sign; zones and racks are printed now
 *   z6  Arabic: the kind / path / Damaged text in Arabic (Noto Sans Arabic embedded, shaped glyphs), codes unchanged
 *   z7  places off: the plan and the sized print answer 404
 *   z8  a bad size or kind → 400 INVALID_STICKER_CHOICE, nothing remembered
 *
 * With {@code -Dplace.sticker.samples=<dir>} the sample PDFs are also written to that directory (nothing is written
 * without it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaceStickerSizesTest {

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

    @MockBean JobScheduler jobScheduler;

    @Autowired JdbcTemplate  jdbc;
    @Autowired PlacesService places;

    static final float MM = 72f / 25.4f;

    record T(UUID tenant, UUID user, UUID main) {}

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── z1: every size decodes ─────────────────────────────────────────────────

    @Test
    void z1_everySize_rendersAtItsPageSize_andDecodesToTheQCode() throws Exception {
        T t = tenant("z1", true);
        bulk(t, 1, 2, 1);
        UUID shelf = id(t, "Z1-R01-S2"), box = id(t, "BOX-01"), rack = id(t, "Z1-R01"), zone = id(t, "Z1");

        byte[] small = print(t, List.of(shelf), Map.of("shelf", choice("SMALL", null)), "en");
        try (PDDocument doc = Loader.loadPDF(small)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertSize(doc.getPage(0), 50, 25);
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(shelf));
            assertThat(decode(doc, 0, 300)).isEqualTo(scan(shelf));
        }
        save("shelf-small.pdf", small);

        byte[] a6 = print(t, List.of(box), Map.of("box", choice("A6", "single")), "en");
        try (PDDocument doc = Loader.loadPDF(a6)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertSize(doc.getPage(0), 105, 148);
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(box));
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Box").contains("BOX-01").contains("Z1 floor").contains("Main Warehouse")
                .contains(scan(box));
        }
        save("box-a6.pdf", a6);

        byte[] a4 = print(t, List.of(box), Map.of("box", choice("A4", null)), "en");
        try (PDDocument doc = Loader.loadPDF(a4)) {
            assertSize(doc.getPage(0), 210, 297);
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(box));
            assertThat(decode(doc, 0, 300)).isEqualTo(scan(box));
        }
        save("box-a4.pdf", a4);

        byte[] shelfA6 = print(t, List.of(shelf), Map.of("shelf", choice("A6", "single")), "en");
        try (PDDocument doc = Loader.loadPDF(shelfA6)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Shelf").contains("Z1-R01-S2").contains("Z1 › R01 › S2");
        }

        byte[] rackA6 = print(t, List.of(rack), Map.of("rack", choice("A6", "single")), "en");
        try (PDDocument doc = Loader.loadPDF(rackA6)) {
            assertThat(doc.getNumberOfPages()).as("the rack itself, nothing under it").isEqualTo(1);
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(rack));
            assertThat(new PDFTextStripper().getText(doc)).contains("Rack").contains("Z1 › R01");
        }

        byte[] zoneA4 = print(t, List.of(zone), Map.of("zone", choice("A4", null)), "en");
        try (PDDocument doc = Loader.loadPDF(zoneA4)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertSize(doc.getPage(0), 210, 297);
            assertThat(decode(doc, 0, 300)).isEqualTo(scan(zone));
            assertThat(new PDFTextStripper().getText(doc)).contains("Zone").contains("Z1");
        }
        save("zone-a4.pdf", zoneA4);

        // Bar width and quiet zone: A6 0.50 mm, A4 1.00 mm (whole dots at 203 dpi), 10 modules of quiet zone.
        float innerA6 = (105 - 16) * MM, innerA4 = (210 - 32) * MM;
        assertThat(com.traceability.inventory.PlaceSignsProbe.barWidthMm(scan(box), innerA6, false)).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.002f));
        assertThat(com.traceability.inventory.PlaceSignsProbe.barWidthMm(scan(box), innerA4, true)).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.002f));
        assertThat(com.traceability.inventory.PlaceSignsProbe.quietModules()).isEqualTo(10);
    }

    // ── z2: A6 four per A4 sheet ───────────────────────────────────────────────

    @Test
    void z2_a6Sheet_fourPerPage_inTreeOrder() throws Exception {
        T t = tenant("z2", true);
        bulk(t, 0, 0, 6);
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 6; i++) expected.add(scan(id(t, String.format("BOX-%02d", i))));

        byte[] pdf = print(t, List.of(id(t, "Z1")), Map.of("box", choice("A6", "sheet")), "en");
        save("box-a6-sheet.pdf", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).as("6 boxes → 4 + 2").isEqualTo(2);
            assertSize(doc.getPage(0), 210, 297);
            assertSize(doc.getPage(1), 210, 297);
            List<String> found = new ArrayList<>();
            for (int page = 0; page < 2; page++) {
                for (int cell = 0; cell < 4; cell++) {
                    String code = decodeCell(doc, page, cell);
                    if (code != null) found.add(code);
                }
            }
            assertThat(found).as("reading order, tree order").containsExactlyElementsOf(expected);
            assertThat(decodeCell(doc, 1, 2)).as("the second sheet's bottom row is empty").isNull();
        }
    }

    // ── z3: Damaged band ───────────────────────────────────────────────────────

    @Test
    void z3_damaged_redBandOnEverySize_barcodeStillDecodes() throws Exception {
        T t = tenant("z3", true);
        bulk(t, 1, 1, 2);
        UUID box = id(t, "BOX-01"), clean = id(t, "BOX-02"), shelf = id(t, "Z1-R01-S1");
        as(t, () -> places.setPurpose(t.user(), box, "damaged"));

        for (String size : List.of("SMALL", "A6", "A4")) {
            byte[] pdf = print(t, List.of(box), Map.of("box", choice(size, "single")), "en");
            if (size.equals("A6")) save("box-a6-damaged.pdf", pdf);
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                assertThat(new PDFTextStripper().getText(doc)).as(size).contains("DAMAGED");
                assertThat(isRed(bandPixel(doc, size))).as(size + " band is red").isTrue();
                assertThat(decode(doc, 0, 203)).as(size).isEqualTo(scan(box));
            }
            byte[] plain = print(t, List.of(clean), Map.of("box", choice(size, "single")), "en");
            try (PDDocument doc = Loader.loadPDF(plain)) {
                assertThat(new PDFTextStripper().getText(doc)).as(size).doesNotContain("DAMAGED");
                assertThat(isRed(bandPixel(doc, size))).as(size + " no band").isFalse();
            }
        }
        byte[] sheet = print(t, List.of(box), Map.of("box", choice("A6", "sheet")), "en");
        try (PDDocument doc = Loader.loadPDF(sheet)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("DAMAGED");
            assertThat(decodeCell(doc, 0, 0)).isEqualTo(scan(box));
        }

        // A shelf inside a Damaged zone is a Damaged place too.
        as(t, () -> places.setPurpose(t.user(), id(t, "Z1"), "damaged"));
        byte[] inZone = print(t, List.of(shelf), Map.of("shelf", choice("SMALL", null)), "en");
        try (PDDocument doc = Loader.loadPDF(inZone)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("DAMAGED");
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(shelf));
        }
    }

    // ── z4: defaults and the remembered choice ─────────────────────────────────

    @Test
    void z4_defaultsPerKind_rememberedPerTenant() throws Exception {
        T t = tenant("z4", true), other = tenant("z4o", true);
        bulk(t, 1, 1, 1);
        bulk(other, 1, 1, 1);
        UUID zone = id(t, "Z1");

        PlacesService.StickerPlan plan = as(t, () -> places.stickerPlan(List.of(zone)));
        assertThat(plan.counts()).containsExactly(Map.entry("zone", 1), Map.entry("rack", 1), Map.entry("shelf", 1),
            Map.entry("box", 1));
        assertThat(plan.choices()).containsExactly(
            Map.entry("zone", choice("A4", null)), Map.entry("rack", choice("A6", "single")),
            Map.entry("shelf", choice("SMALL", null)), Map.entry("box", choice("A6", "single")));

        print(t, List.of(zone), Map.of("box", choice("a6", "SHEET"), "shelf", choice("A4", null)), "en");
        PlacesService.StickerPlan after = as(t, () -> places.stickerPlan(List.of(zone)));
        assertThat(after.choices()).containsExactly(
            Map.entry("zone", choice("A4", null)), Map.entry("rack", choice("A6", "single")),
            Map.entry("shelf", choice("A4", null)), Map.entry("box", choice("A6", "sheet")));

        // Only printed kinds are remembered: a rack choice with no rack in the selection changes nothing.
        print(t, List.of(id(t, "BOX-01")), Map.of("box", choice("SMALL", null), "rack", choice("A4", null)), "en");
        PlacesService.StickerPlan third = as(t, () -> places.stickerPlan(List.of(zone)));
        assertThat(third.choices().get("box")).isEqualTo(choice("SMALL", null));
        assertThat(third.choices().get("rack")).isEqualTo(choice("A6", "single"));

        PlacesService.StickerPlan theirs = as(other, () -> places.stickerPlan(List.of(id(other, "Z1"))));
        assertThat(theirs.choices()).as("another tenant keeps the defaults").isEqualTo(plan.choices());
    }

    // ── z5: a whole zone, mixed sizes, one PDF ─────────────────────────────────

    @Test
    void z5_wholeZone_eachKindInItsSize_onePdf() throws Exception {
        T t = tenant("z5", true);
        bulk(t, 1, 2, 2);
        UUID zone = id(t, "Z1");
        byte[] pdf = print(t, List.of(zone), Map.of(
            "zone", choice("A4", null), "rack", choice("A6", "single"),
            "shelf", choice("SMALL", null), "box", choice("A6", "single")), "en");
        save("zone-mixed.pdf", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            // separator + 2 shelves (50×25), rack + 2 boxes (A6), zone (A4)
            assertThat(doc.getNumberOfPages()).isEqualTo(7);
            int[][] sizes = {{50, 25}, {50, 25}, {50, 25}, {105, 148}, {105, 148}, {105, 148}, {210, 297}};
            for (int i = 0; i < sizes.length; i++) assertSize(doc.getPage(i), sizes[i][0], sizes[i][1]);
            List<String> decoded = new ArrayList<>();
            for (int i = 1; i < 7; i++) decoded.add(decode(doc, i, i == 6 ? 300 : 203));
            assertThat(decoded).containsExactly(scan(id(t, "Z1-R01-S1")), scan(id(t, "Z1-R01-S2")),
                scan(id(t, "Z1-R01")), scan(id(t, "BOX-01")), scan(id(t, "BOX-02")), scan(zone));
        }

        // Leaving a kind out leaves it out.
        byte[] shelvesOnly = print(t, List.of(zone), Map.of("shelf", choice("SMALL", null)), "en");
        try (PDDocument doc = Loader.loadPDF(shelvesOnly)) {
            assertThat(doc.getNumberOfPages()).as("separator + 2 shelves").isEqualTo(3);
        }
    }

    // ── z6: Arabic ─────────────────────────────────────────────────────────────

    @Test
    void z6_arabic_kindPathAndDamagedInArabic() throws Exception {
        T t = tenant("z6", true);
        bulk(t, 1, 2, 1);
        UUID shelf = id(t, "Z1-R01-S2"), box = id(t, "BOX-01");
        as(t, () -> places.setPurpose(t.user(), box, "damaged"));
        byte[] pdf = print(t, List.of(shelf, box),
            Map.of("shelf", choice("A6", "single"), "box", choice("A6", "single")), "ar");
        save("arabic-a6.pdf", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            for (int i = 0; i < 2; i++) {
                Set<String> fonts = new HashSet<>();
                for (COSName n : doc.getPage(i).getResources().getFontNames()) {
                    fonts.add(doc.getPage(i).getResources().getFont(n).getName());
                }
                assertThat(fonts).as("page " + i).anyMatch(f -> f.contains("NotoSansArabic"));
            }
            String text = Normalizer.normalize(new PDFTextStripper().getText(doc), Normalizer.Form.NFKC);
            assertThat(text).contains("Z1-R01-S2").contains("Z1 › R01 › S2").contains("BOX-01").contains("Z1");
            for (String word : List.of("رف", "صندوق", "أرضية", "تالف")) {
                assertThat(containsWord(text, word)).as(word).isTrue();
            }
            assertThat(text).doesNotContain("Shelf").doesNotContain("DAMAGED").doesNotContain("?");
            assertThat(decode(doc, 0, 203)).isEqualTo(scan(shelf));
            assertThat(decode(doc, 1, 203)).isEqualTo(scan(box));
        }
    }

    // ── z7: places off ─────────────────────────────────────────────────────────

    @Test
    void z7_placesOff_planAndSizedPrint404() {
        T t = tenant("z7", false);
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, ?, 'zone', 'Z1', 'Q770001')",
            zone, t.tenant(), t.main());
        assertThatThrownBy(() -> as(t, () -> places.stickerPlan(List.of(zone))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> print(t, List.of(zone), Map.of("zone", choice("A4", null)), "en"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    // ── z8: bad choices ────────────────────────────────────────────────────────

    @Test
    void z8_badChoice_400_nothingRemembered() throws Exception {
        T t = tenant("z8", true);
        bulk(t, 0, 0, 1);
        UUID box = id(t, "BOX-01");
        for (Map<String, PlacesService.KindChoice> bad : List.of(
                Map.of("box", choice("A5", null)), Map.of("box", choice("A6", "eight")),
                Map.of("bin", choice("A6", null)), Map.of("box", choice(null, null)))) {
            assertThatThrownBy(() -> print(t, List.of(box), bad, "en"))
                .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_STICKER_CHOICE);
        }
        assertThatThrownBy(() -> print(t, List.of(box), Map.of(), "en"))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.NO_STICKERS);
        assertThatThrownBy(() -> print(t, List.of(box), Map.of("zone", choice("A4", null)), "en"))
            .as("no place of the chosen kinds").extracting(e -> ((PlaceException) e).code())
            .isEqualTo(PlaceException.Code.NO_STICKERS);
        assertThat(jdbc.queryForObject("SELECT place_sticker_prefs::text FROM tenants WHERE id = ?", String.class, t.tenant()))
            .isEqualTo("{}");
    }

    // ── z9: the bulk-print ticks, remembered per kind per tenant ───────────────

    @Test
    void z9_bulkTicks_rememberedPerKind_perTenant_onlyOnAPrint() throws Exception {
        T t = tenant("z9", true), other = tenant("z9o", true);
        bulk(t, 1, 1, 1);
        bulk(other, 1, 1, 1);
        UUID zone = id(t, "Z1");
        assertThat(as(t, () -> places.stickerPlan(List.of(zone))).bulkTicks()).as("nothing remembered yet").isEmpty();

        // The person ticked zone and unticked box on a bulk print: both remembered, the sizes too.
        as(t, () -> places.stickers(new PlacesService.StickerRequest(List.of(zone),
            Map.of("zone", choice("A4", null), "shelf", choice("SMALL", null)), "en",
            Map.of("zone", true, "box", false))));
        assertThat(as(t, () -> places.stickerPlan(List.of(zone))).bulkTicks())
            .containsExactly(Map.entry("zone", true), Map.entry("box", false));

        // A later change of one kind keeps the others.
        as(t, () -> places.stickers(new PlacesService.StickerRequest(List.of(zone),
            Map.of("shelf", choice("SMALL", null)), "en", Map.of("zone", false))));
        assertThat(as(t, () -> places.stickerPlan(List.of(zone))).bulkTicks())
            .containsExactly(Map.entry("zone", false), Map.entry("box", false));

        // A direct print (no ticks) and a refused print change nothing; a bad kind is 400.
        print(t, List.of(zone), Map.of("zone", choice("A4", null)), "en");
        assertThatThrownBy(() -> as(t, () -> places.stickers(new PlacesService.StickerRequest(List.of(zone),
            Map.of("shelf", choice("SMALL", null)), "en", Map.of("bin", true)))))
            .extracting(e -> ((PlaceException) e).code()).isEqualTo(PlaceException.Code.INVALID_STICKER_CHOICE);
        assertThat(as(t, () -> places.stickerPlan(List.of(zone))).bulkTicks())
            .containsExactly(Map.entry("zone", false), Map.entry("box", false));

        assertThat(as(other, () -> places.stickerPlan(List.of(id(other, "Z1")))).bulkTicks())
            .as("another tenant remembers nothing").isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private byte[] print(T t, List<UUID> ids, Map<String, PlacesService.KindChoice> kinds, String lang) throws Exception {
        return as(t, () -> places.stickers(new PlacesService.StickerRequest(ids, kinds, lang)));
    }

    private static PlacesService.KindChoice choice(String size, String mode) {
        return new PlacesService.KindChoice(size, mode);
    }

    private void bulk(T t, int racks, int shelves, int boxes) {
        as(t, () -> places.bulkCreate(t.user(), new PlacesService.BulkRequest(t.main(), null, racks, shelves, boxes, null)));
    }

    private UUID id(T t, String code) {
        return jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND code = ?", UUID.class, t.tenant(), code);
    }

    private String scan(UUID bin) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, bin);
    }

    private <R> R as(T t, java.util.concurrent.Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), main = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "Stickers " + name, placesOn);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, tag + "@stickers.test");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        return new T(tenant, user, main);
    }

    private static void assertSize(PDPage page, float wMm, float hMm) {
        PDRectangle box = page.getMediaBox();
        assertThat(box.getWidth() / MM).isCloseTo(wMm, org.assertj.core.data.Offset.offset(0.05f));
        assertThat(box.getHeight() / MM).isCloseTo(hMm, org.assertj.core.data.Offset.offset(0.05f));
    }

    private static String decode(PDDocument doc, int page, int dpi) throws Exception {
        return decode(new PDFRenderer(doc).renderImageWithDPI(page, dpi));
    }

    /** One quarter of an A4 sheet at 203 DPI; null when nothing decodes there. */
    private static String decodeCell(PDDocument doc, int page, int cell) throws Exception {
        BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, 203);
        int w = img.getWidth() / 2, h = img.getHeight() / 2;
        BufferedImage part = img.getSubimage((cell % 2) * w, (cell / 2) * h, w, h);
        try {
            return decode(part);
        } catch (com.google.zxing.NotFoundException e) {
            return null;
        }
    }

    private static String decode(BufferedImage img) throws Exception {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result r = new MultiFormatReader().decode(
            new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints);
        return r.getText();
    }

    /** A pixel inside where the band is: SMALL at the bottom-left, A6 / A4 at the top-left of the band. */
    private static int bandPixel(PDDocument doc, String size) throws Exception {
        int dpi = 100;
        BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, dpi);
        float px = dpi / 25.4f;
        return switch (size) {
            case "SMALL" -> img.getRGB(Math.round(2 * px), img.getHeight() - Math.round(1 * px));
            case "A6" -> img.getRGB(Math.round(10 * px), Math.round(9 * px));
            default -> img.getRGB(Math.round(20 * px), Math.round(18 * px));
        };
    }

    private static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return r > 150 && g < 90 && b < 90;
    }

    /**
     * The word in the extracted text, in logical or visual (reversed) order. The font maps its one yeh glyph back to
     * Farsi yeh (U+06CC) on extraction, so that reads as Arabic yeh here.
     */
    private static boolean containsWord(String text, String word) {
        String compact = text.replaceAll("\\s+", "").replace('\u06CC', '\u064A');
        return compact.contains(word) || compact.contains(new StringBuilder(word).reverse().toString());
    }

    private static void save(String name, byte[] pdf) throws Exception {
        String dir = System.getProperty("place.sticker.samples");
        if (dir == null || dir.isBlank()) return;
        Files.createDirectories(Path.of(dir));
        Files.write(Path.of(dir, name), pdf);
    }
}
