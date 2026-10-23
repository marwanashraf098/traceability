package com.traceability;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import com.traceability.identity.JwtService;
import com.traceability.inventory.ReceivingService;
import com.traceability.labels.LabelCodeException;
import com.traceability.inventory.LabelPdfRenderer;
import com.traceability.inventory.PieceLabelGoldenTest;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.labels.LabelCodeService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.assertj.core.api.ThrowableAssert;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling — NAMED label codes (V176, named since V181 / slice 3; LabelCodeService, /api/v1/label-codes).
 *
 *   f1  flag off → every endpoint 404 and /me flexibleLabeling false; on → /me true, issue works
 *   f2  workers are refused (owner / manager only)
 *   i1  issue: one code per unit, one unbroken P-range, all issued, each tied to the line's variant
 *   i2  several lines → each code carries its own line's variant; a foreign variant / wrong order line refused
 *   i3  idempotency: same key twice → one batch, same codes, the counter claimed once;
 *       same key, different request → 409 IDEMPOTENCY_KEY_REUSED
 *   i4  idempotency under concurrency: the same key from 6 threads → one batch
 *   i5  validation: kind, 0 / empty / 2,001 codes in all, missing key → 400 with a code
 *   c1  issue() alongside batchReceive (8 threads) → no code is ever handed out twice
 *   v1  void: issued → voided; a voided / bound / foreign / unknown code refuses the WHOLE request
 *   v2  void validation (no ids, no reason) → 400
 *   k1  CHECK / UNIQUE constraints reject invalid states (status ⇔ piece_id, ⇔ voided_at, code shape, duplicates)
 *   r1  resolve: issued / bound / voided / unknown, scan-normalized
 *   p1  PDF: one NAMED piece label (code, product, variant, SKU) per still-issued code at 50×25, each barcode
 *       decodes to its code; reprint after a void leaves the voided code out; nothing issued → 422; another
 *       tenant's batch → 404
 *   p2  a code label's PDF has exactly the piece label's fingerprint (PieceLabelGoldenTest.fingerprint, 50×25) —
 *       code labels and piece labels can never drift apart
 *   a1  app_user: issue + void work under RLS; another tenant sees nothing (same-tenant positive control);
 *       no DELETE, no UPDATE of the code / batch rows
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelCodesTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService       jwt;
    @Autowired JdbcTemplate     jdbc;
    @Autowired LabelCodeService labels;
    @Autowired ReceivingService receiving;

    JdbcTemplate        appUserJdbc;
    TransactionTemplate appUserTx;

    UUID tenantA, ownerA, workerA, variantA, variantA2, locationA, variantB;
    UUID tenantB, ownerB;
    /** Minted per request: a token lives 30 min, and a class can run longer than that inside the full suite. */
    private String ownerToken()  { return jwt.issueAccessToken(ownerA, tenantA, "owner"); }
    private String workerToken() { return jwt.issueAccessToken(workerA, tenantA, "worker"); }

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID(); ownerA = UUID.randomUUID(); workerA = UUID.randomUUID();
        tenantB = UUID.randomUUID(); ownerB = UUID.randomUUID();
        variantA = UUID.randomUUID(); locationA = UUID.randomUUID();
        UUID storeA = UUID.randomUUID(), productA = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'LC Tenant A'), (?, 'LC Tenant B')", tenantA, tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
                    "(?, ?, 'Owner A', 'owner-a@lc.test', 'hash', 'owner'), " +
                    "(?, ?, 'Worker A', 'worker-a@lc.test', 'hash', 'worker'), " +
                    "(?, ?, 'Owner B', 'owner-b@lc.test', 'hash', 'owner')",
                    ownerA, tenantA, workerA, tenantA, ownerB, tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'lc-a.myshopify.com', 'disconnected')", storeA, tenantA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'LC-P', 'LC Widget', 'active')", productA, tenantA, storeA);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'LC-V', 'Blue', 'LCBLUE')", variantA, tenantA, productA);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default) " +
                    "VALUES (?, ?, 'LC Warehouse', 'warehouse', true)", locationA, tenantA);
        variantA2 = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'LC-V2', 'Red', 'LCRED')", variantA2, tenantA, productA);
        UUID storeB = UUID.randomUUID(), productB = UUID.randomUUID();
        variantB = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'lc-b.myshopify.com', 'disconnected')", storeB, tenantB);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'LC-PB', 'LC B Widget', 'active')", productB, tenantB, storeB);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'LC-VB', 'Blue', 'LCBBLUE')", variantB, tenantB, productB);


        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        appUserJdbc = new JdbcTemplate(ds);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true, label_width_mm = NULL, label_height_mm = NULL " +
                    "WHERE id IN (?, ?)", tenantA, tenantB);
        TenantContext.set(tenantA);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM label_codes        WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM label_code_batches WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM piece_events  WHERE tenant_id = ?", tenantA);
        jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", tenantA);
        jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", tenantA);
        jdbc.update("DELETE FROM receipts      WHERE tenant_id = ?", tenantA);
    }

    // ── flag + roles ──────────────────────────────────────────────────────────

    @Test
    void f1_flag_off_refuses_every_endpoint_and_me_reports_it() {
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(2), "f1-seed");
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenantA);

        assertThat(me().get("flexibleLabeling")).isEqualTo(false);
        assertThat(post("/api/v1/label-codes/batches", issueBody("pack", 1, "f1-off"), ownerToken()).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getPdf(b.id(), ownerToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/label-codes/void",
                Map.of("codeIds", List.of(b.codes().get(0).id()), "reason", "x"), ownerToken()).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> labels.resolve(b.codes().get(0).code()))
            .hasMessageContaining("404");
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ? AND status = 'issued'", tenantA))
            .as("nothing changed while off").isEqualTo(2);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenantA);
        assertThat(me().get("flexibleLabeling")).isEqualTo(true);
        ResponseEntity<Map> on = post("/api/v1/label-codes/batches", issueBody("pack", 1, "f1-on"), ownerToken());
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) on.getBody().get("codes")).hasSize(1);
    }

    @Test
    void f2_workers_are_refused() {
        assertThat(post("/api/v1/label-codes/batches", issueBody("pack", 1, "f2"), workerToken()).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(1), "f2-seed");
        assertThat(getPdf(b.id(), workerToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/label-codes/void", Map.of("codeIds", List.of(b.codes().get(0).id()), "reason", "x"),
            workerToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── issue ─────────────────────────────────────────────────────────────────

    @Test
    void i1_issue_claims_one_unbroken_range_of_issued_codes() {
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(5), "i1");

        assertThat(b.created()).isTrue();
        assertThat(b.codes()).hasSize(5);
        assertThat(b.codes()).extracting(LabelCodeService.IssuedCode::variantId).containsOnly(variantA);
        List<Long> n = b.codes().stream().map(c -> {
            assertThat(c.code()).matches("P[0-9]{6,}");
            return Long.parseLong(c.code().substring(1));
        }).toList();
        assertThat(n.get(4) - n.get(0)).as("one unbroken range").isEqualTo(4);
        assertThat(count("SELECT count(*) FROM label_codes WHERE batch_id = ? AND status = 'issued' AND kind = 'pack' " +
                         "AND issued_by = ?", b.id(), ownerA)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, tenantA))
            .isEqualTo(n.get(4));
    }

    @Test
    void i2_each_code_carries_its_lines_variant_and_unknown_variants_are_refused() {
        LabelCodeService.Batch b = labels.issue(ownerA, "tag", List.of(
            new LabelCodeService.Line(variantA, 2, null, null), new LabelCodeService.Line(variantA2, 1, null, null)), "i2");
        assertThat(b.codes()).extracting(LabelCodeService.IssuedCode::variantId).containsExactly(variantA, variantA, variantA2);
        assertThat(count("SELECT count(*) FROM label_codes WHERE batch_id = ? AND variant_id = ?", b.id(), variantA2)).isEqualTo(1);
        assertCode(() -> labels.issue(ownerA, "tag", nB(1), "i2-foreign"), LabelCodeException.Code.UNKNOWN_VARIANT);
        assertCode(() -> labels.issue(ownerA, "pack", List.of(new LabelCodeService.Line(variantA, 1, UUID.randomUUID(), UUID.randomUUID())),
            "i2-order"), LabelCodeException.Code.INVALID_ORDER_REF);
    }

    @Test
    void i3_same_key_twice_returns_the_same_batch_and_never_issues_twice() {
        LabelCodeService.Batch first  = labels.issue(ownerA, "pack", n(5), "i3-key");
        long counterAfterFirst = jdbc.queryForObject("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, tenantA);
        LabelCodeService.Batch second = labels.issue(ownerA, "pack", n(5), "i3-key");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.created()).isFalse();
        assertThat(second.codes()).isEqualTo(first.codes());
        assertThat(count("SELECT count(*) FROM label_code_batches WHERE tenant_id = ?", tenantA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenantA)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, tenantA))
            .as("the counter is claimed once").isEqualTo(counterAfterFirst);

        assertThatThrownBy(() -> labels.issue(ownerA, "pack", n(6), "i3-key"))
            .isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(LabelCodeException.Code.IDEMPOTENCY_KEY_REUSED));

        // Over HTTP, the same key answers the same batch.
        ResponseEntity<Map> http = post("/api/v1/label-codes/batches", issueBody("pack", 5, "i3-key"), ownerToken());
        assertThat(http.getBody().get("id")).isEqualTo(first.id().toString());
        assertThat(http.getBody().get("created")).isEqualTo(false);
    }

    @Test
    void i4_same_key_from_concurrent_requests_makes_one_batch() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier go = new CyclicBarrier(threads);
        try {
            List<Future<UUID>> ids = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                ids.add(pool.submit(() -> TenantContext.runAs(tenantA, () -> {
                    go.await();
                    return labels.issue(ownerA, "pack", n(10), "i4-key").id();
                })));
            }
            Set<UUID> distinct = new HashSet<>();
            for (Future<UUID> f : ids) distinct.add(f.get(60, TimeUnit.SECONDS));
            assertThat(distinct).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM label_code_batches WHERE tenant_id = ?", tenantA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenantA)).isEqualTo(10);
    }

    @Test
    void i5_validation_refuses_with_a_code() {
        assertCode(() -> labels.issue(ownerA, "sticker", n(1), "i5a"), LabelCodeException.Code.INVALID_KIND);
        assertCode(() -> labels.issue(ownerA, "pack", n(0), "i5b"), LabelCodeException.Code.INVALID_COUNT);
        assertCode(() -> labels.issue(ownerA, "pack", List.of(), "i5c"), LabelCodeException.Code.INVALID_COUNT);
        assertCode(() -> labels.issue(ownerA, "pack", List.of(new LabelCodeService.Line(variantA, 1500, null, null),
            new LabelCodeService.Line(variantA2, 501, null, null)), "i5d"), LabelCodeException.Code.INVALID_COUNT);  // 2,001 in all
        assertCode(() -> labels.issue(ownerA, "pack", n(1), " "), LabelCodeException.Code.INVALID_IDEMPOTENCY_KEY);
        assertThat(labels.issue(ownerA, "factory", n(2000), "i5-max").codes()).hasSize(2000);

        ResponseEntity<Map> http = post("/api/v1/label-codes/batches", issueBody("pack", 2001, "i5-http"), ownerToken());
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getBody()).containsEntry("code", "INVALID_COUNT").containsKeys("message_en", "message_ar");
    }

    // ── concurrency with receiving ────────────────────────────────────────────

    @Test
    void c1_issue_alongside_batchReceive_never_hands_out_a_code_twice() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier go = new CyclicBarrier(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int k = t;
                fs.add(pool.submit(() -> TenantContext.runAs(tenantA, () -> {
                    go.await();
                    for (int round = 0; round < 3; round++) {
                        if (k % 2 == 0) {
                            labels.issue(ownerA, "pack", n(150), "c1-" + k + "-" + round);
                        } else {
                            UUID s = receiving.createSession(ownerA, locationA, "C1-" + k + "-" + round, null, null);
                            receiving.addLine(s, variantA, 150);
                            receiving.finalize(s, ownerA);
                        }
                    }
                    return null;
                })));
            }
            for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<String> all = jdbc.queryForList(
            "SELECT code FROM label_codes WHERE tenant_id = ? UNION ALL SELECT short_code FROM pieces WHERE tenant_id = ?",
            String.class, tenantA, tenantA);
        assertThat(all).hasSize(threads * 3 * 150);
        assertThat(new HashSet<>(all)).as("no code handed out twice across label codes and pieces").hasSize(all.size());
    }

    // ── void ──────────────────────────────────────────────────────────────────

    @Test
    void v1_void_is_issued_only_and_all_or_nothing() {
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(4), "v1");
        UUID c0 = b.codes().get(0).id(), c1 = b.codes().get(1).id(), c2 = b.codes().get(2).id(), c3 = b.codes().get(3).id();

        assertThat(labels.voidCodes(List.of(c0), "torn")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, void_reason, voided_at IS NOT NULL AS has_time FROM label_codes WHERE id = ?", c0))
            .containsEntry("status", "voided").containsEntry("void_reason", "torn").containsEntry("has_time", true);

        bind(c1);
        TenantContext.set(tenantB);
        UUID foreign = labels.issue(ownerB, "pack", nB(1), "v1-b").codes().get(0).id();
        TenantContext.set(tenantA);

        LabelCodeException e = catchThrowableOfType(
            () -> labels.voidCodes(List.of(c2, c0, c1, foreign, UUID.randomUUID()), "x"), LabelCodeException.class);
        assertThat(e.code()).isEqualTo(LabelCodeException.Code.VOID_REFUSED);
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.details()).extracting(m -> m.get("status"))
            .containsExactly("voided", "bound", "unknown", "unknown");
        assertThat(status(c2)).as("all or nothing: the issued code in a refused request stays issued").isEqualTo("issued");
        assertThat(status(c1)).isEqualTo("bound");
        TenantContext.set(tenantB);
        assertThat(labels.resolve(jdbc.queryForObject("SELECT code FROM label_codes WHERE id = ?", String.class, foreign)).status())
            .as("another tenant's code is untouched").isEqualTo("issued");
        TenantContext.set(tenantA);

        ResponseEntity<Map> http = post("/api/v1/label-codes/void", Map.of("codeIds", List.of(c2, c3), "reason", "spare"), ownerToken());
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getBody()).containsEntry("voided", 2);
        ResponseEntity<Map> again = post("/api/v1/label-codes/void", Map.of("codeIds", List.of(c3), "reason", "spare"), ownerToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "VOID_REFUSED");
    }

    @Test
    void v2_void_validation() {
        UUID c = labels.issue(ownerA, "pack", n(1), "v2").codes().get(0).id();
        assertCode(() -> labels.voidCodes(List.of(), "x"), LabelCodeException.Code.INVALID_VOID_REQUEST);
        assertCode(() -> labels.voidCodes(List.of(c), " "), LabelCodeException.Code.INVALID_VOID_REQUEST);
        assertCode(() -> labels.voidCodes(List.of(c), "x".repeat(101)), LabelCodeException.Code.INVALID_VOID_REQUEST);
        assertThat(status(c)).isEqualTo("issued");
    }

    // ── constraints ───────────────────────────────────────────────────────────

    @Test
    void k1_constraints_reject_invalid_states() {
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(2), "k1");
        UUID c = b.codes().get(0).id();
        String piece = receivePiece();

        assertViolation("UPDATE label_codes SET status = 'bound', bound_at = now() WHERE id = ?", c);          // no piece
        assertViolation("UPDATE label_codes SET piece_id = ? WHERE id = ?", piece, c);                          // issued with a piece
        assertViolation("UPDATE label_codes SET status = 'voided' WHERE id = ?", c);                            // no voided_at
        assertViolation("UPDATE label_codes SET status = 'voided', voided_at = now() WHERE id = ?", c);         // no reason
        assertViolation("UPDATE label_codes SET status = 'bound', piece_id = ? WHERE id = ?", piece, c);        // no bound_at
        assertViolation("UPDATE label_codes SET status = 'lost' WHERE id = ?", c);
        assertViolation("UPDATE label_codes SET code = 'X123456' WHERE id = ?", c);
        assertViolation("UPDATE label_codes SET code = ? WHERE id = ?", b.codes().get(1).code(), c);            // duplicate in tenant
        jdbc.update("UPDATE label_codes SET status = 'bound', piece_id = ?, bound_at = now() WHERE id = ?", piece, c);
        assertViolation("UPDATE label_codes SET status = 'bound', piece_id = ?, bound_at = now() WHERE id = ?",
            piece, b.codes().get(1).id());                                                                      // one code per piece
        assertViolation("INSERT INTO label_code_batches (tenant_id, kind, count, " +
            "idempotency_key, created_by) VALUES (?, 'pack', 2001, 'k1-cap', ?)", tenantA, ownerA);  // > 2,000
        assertViolation("INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by) VALUES (?, 'P999002', 'pack', ?, ?)",
            tenantA, b.id(), ownerA);                                                                           // no variant

        // The same code in another tenant is fine (unique per tenant).
        UUID otherBatch = jdbc.queryForObject("INSERT INTO label_code_batches (tenant_id, kind, count, " +
            "idempotency_key, created_by) VALUES (?, 'pack', 1, 'k1-b', ?) RETURNING id", UUID.class, tenantB, ownerB);
        jdbc.update("INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id) VALUES (?, ?, 'pack', ?, ?, ?)",
            tenantB, b.codes().get(1).code(), otherBatch, ownerB, variantB);
        // A code can't point at another tenant's batch (composite FK).
        assertViolation("INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id) VALUES (?, 'P999001', 'pack', ?, ?, ?)",
            tenantA, otherBatch, ownerA, variantA);
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    @Test
    void r1_resolve_reports_each_status() {
        LabelCodeService.Batch b = labels.issue(ownerA, "tag", n(3), "r1");
        String issued = b.codes().get(0).code();
        labels.voidCodes(List.of(b.codes().get(1).id()), "torn");
        String piece = receivePiece();
        bind(b.codes().get(2).id(), piece);

        assertThat(labels.resolve(issued.toLowerCase()).status()).as("scan-normalized").isEqualTo("issued");
        assertThat(labels.resolve(" " + issued + "\n")).extracting(LabelCodeService.Resolution::status,
            LabelCodeService.Resolution::batchId, LabelCodeService.Resolution::kind).containsExactly("issued", b.id(), "tag");
        assertThat(labels.resolve(b.codes().get(1).code()).status()).isEqualTo("voided");
        assertThat(labels.resolve(b.codes().get(2).code())).extracting(LabelCodeService.Resolution::status,
            LabelCodeService.Resolution::pieceId).containsExactly("bound", piece);
        String receivedCode = jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece);
        assertThat(labels.resolve(receivedCode).status()).as("a received piece's own code is not a label code")
            .isEqualTo("unknown");
        assertThat(labels.resolve("1234567890").status()).isEqualTo("unknown");
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    @Test
    void p1_pdf_prints_still_issued_codes_as_named_labels() throws Exception {
        LabelCodeService.Batch b = labels.issue(ownerA, "pack", n(3), "p1");
        ResponseEntity<byte[]> pdf = getPdf(b.id(), ownerToken());
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertPages(pdf.getBody(), 50f, 25f, b.codes().stream().map(LabelCodeService.IssuedCode::code).toList());

        labels.voidCodes(List.of(b.codes().get(1).id()), "torn");
        assertPages(labels.batchPdf(b.id()), 50f, 25f, List.of(b.codes().get(0).code(), b.codes().get(2).code()));

        // Named labels ignore the tenant's (blank-label era) size setting — always 50×25 like every piece label.
        jdbc.update("UPDATE tenants SET label_width_mm = 40, label_height_mm = 25 WHERE id = ?", tenantA);
        assertPages(labels.batchPdf(b.id()), 50f, 25f, List.of(b.codes().get(0).code(), b.codes().get(2).code()));

        labels.voidCodes(List.of(b.codes().get(0).id(), b.codes().get(2).id()), "done");
        ResponseEntity<Map> none = rest.exchange(base() + "/api/v1/label-codes/batches/" + b.id() + "/pdf",
            HttpMethod.GET, new HttpEntity<>(auth(ownerToken())), Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(none.getBody()).containsEntry("code", "NO_ISSUED_CODES");

        TenantContext.set(tenantB);
        UUID theirs = labels.issue(ownerB, "pack", nB(1), "p1-b").id();
        TenantContext.set(tenantA);
        ResponseEntity<Map> foreign = rest.exchange(base() + "/api/v1/label-codes/batches/" + theirs + "/pdf",
            HttpMethod.GET, new HttpEntity<>(auth(ownerToken())), Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).containsEntry("code", "BATCH_NOT_FOUND");
    }

    @Test
    void p2_a_code_label_has_exactly_the_piece_labels_fingerprint() throws Exception {
        LabelCodeService.Batch b = labels.issue(ownerA, "tag", n(2), "p2");
        List<LabelPdfRenderer.Piece> asPieces = b.codes().stream()
            .map(c -> new LabelPdfRenderer.Piece(c.code(), "LC Widget", "Blue", "LCBLUE")).toList();
        String pieceLabels = PieceLabelGoldenTest.fingerprint(LabelPdfRenderer.render(asPieces, PieceLabelLayout.Spec.of(50f, 25f)));
        assertThat(PieceLabelGoldenTest.fingerprint(labels.batchPdf(b.id()))).isEqualTo(pieceLabels);
    }

    // ── app_user (RLS + privileges) ───────────────────────────────────────────

    @Test
    void a1_app_user_issues_and_voids_under_rls_and_never_deletes() {
        LabelCodeService asApp = new LabelCodeService(appUserJdbc);
        LabelCodeService.Batch b = appUserTx.execute(s -> asApp.issue(ownerA, "pack", n(3), "a1"));
        assertThat(b.codes()).hasSize(3);
        Integer voided = appUserTx.execute(s -> asApp.voidCodes(List.of(b.codes().get(0).id()), "torn"));
        assertThat(voided).isEqualTo(1);

        // Same tenant (positive control): the rows are visible.
        assertThat(appCount("SELECT count(*) FROM label_codes WHERE batch_id = ?", b.id())).isEqualTo(3);
        assertThat(appCount("SELECT count(*) FROM label_code_batches WHERE id = ?", b.id())).isEqualTo(1);

        // Another tenant: nothing, by id or by code; its void refuses the code as unknown.
        TenantContext.set(tenantB);
        assertThat(appCount("SELECT count(*) FROM label_codes WHERE batch_id = ?", b.id())).isZero();
        assertThat(appCount("SELECT count(*) FROM label_code_batches WHERE id = ?", b.id())).isZero();
        LabelCodeService.Resolution seen = appUserTx.execute(s -> asApp.resolve(b.codes().get(1).code()));
        assertThat(seen.status()).isEqualTo("unknown");
        LabelCodeException e = catchThrowableOfType(() -> appUserTx.execute(s ->
            asApp.voidCodes(List.of(b.codes().get(1).id()), "x")), LabelCodeException.class);
        assertThat(e.details()).extracting(m -> m.get("status")).containsExactly("unknown");
        TenantContext.set(tenantA);
        assertThat(status(b.codes().get(1).id())).isEqualTo("issued");

        // No DELETE on either table; no UPDATE of anything but the lifecycle columns; batches immutable.
        assertDenied("DELETE FROM label_codes WHERE id = ?", b.codes().get(1).id());
        assertDenied("DELETE FROM label_code_batches WHERE id = ?", b.id());
        assertDenied("UPDATE label_codes SET code = 'P999999' WHERE id = ?", b.codes().get(1).id());
        assertDenied("UPDATE label_codes SET batch_id = batch_id WHERE id = ?", b.codes().get(1).id());
        assertDenied("UPDATE label_code_batches SET count = 9 WHERE id = ?", b.id());
        assertThat(count("SELECT count(*) FROM label_codes WHERE batch_id = ?", b.id())).isEqualTo(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertPages(byte[] pdf, float wMm, float hMm, List<String> codes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(codes.size());
            String text = new PDFTextStripper().getText(doc);
            for (int i = 0; i < codes.size(); i++) {
                PDPage page = doc.getPage(i);
                assertThat(page.getMediaBox().getWidth()).isCloseTo(wMm * 72f / 25.4f, within(0.01f));
                assertThat(page.getMediaBox().getHeight()).isCloseTo(hMm * 72f / 25.4f, within(0.01f));
                assertThat(decodeBarcode(page)).isEqualTo(codes.get(i));
                assertThat(text).contains(codes.get(i));
            }
            assertThat(text).as("a named label: product, variant, SKU").contains("LC Widget").contains("Blue").contains("LCBLUE");
        }
    }

    private String decodeBarcode(PDPage page) throws Exception {
        for (COSName n : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(n) instanceof PDImageXObject img) {
                BufferedImage bi = img.getImage();
                return new Code128Reader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(bi))))
                    .getText();
            }
        }
        throw new AssertionError("no barcode image on the page");
    }

    private String receivePiece() {
        UUID s = receiving.createSession(ownerA, locationA, "LC-" + UUID.randomUUID(), null, null);
        receiving.addLine(s, variantA, 1);
        receiving.finalize(s, ownerA);
        return jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ?", String.class, s);
    }

    private void bind(UUID codeId) { bind(codeId, receivePiece()); }

    /** Slice 3's InventoryLedger.bindLabel doesn't exist yet — the bound state, as postgres. */
    private void bind(UUID codeId, String pieceId) {
        jdbc.update("UPDATE label_codes SET status = 'bound', piece_id = ?, bound_by = ?, bound_at = now() WHERE id = ?",
            pieceId, ownerA, codeId);
    }

    private Integer appCount(String sql, Object arg) {
        return appUserTx.execute(s -> appUserJdbc.queryForObject(sql, Integer.class, arg));
    }

    private String status(UUID codeId) {
        return jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, codeId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void assertCode(ThrowableAssert.ThrowingCallable call, LabelCodeException.Code code) {
        assertThatThrownBy(call).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(code));
    }

    private void assertViolation(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertDenied(String sql, Object... args) {
        assertThatThrownBy(() -> appUserTx.execute(s -> appUserJdbc.update(sql, args)))
            .as(sql).isInstanceOf(DataAccessException.class)
            .cause().hasMessageContaining("permission denied");
    }

    private Map<String, Object> me() {
        return rest.exchange(base() + "/api/v1/me", HttpMethod.GET, new HttpEntity<>(auth(ownerToken())), Map.class).getBody();
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
    }

    private ResponseEntity<byte[]> getPdf(UUID batchId, String token) {
        return rest.exchange(base() + "/api/v1/label-codes/batches/" + batchId + "/pdf",
            HttpMethod.GET, new HttpEntity<>(auth(token)), byte[].class);
    }

    private Map<String, Object> issueBody(String kind, int count, String key) {
        return Map.of("kind", kind, "lines", List.of(Map.of("variantId", variantA.toString(), "count", count)), "idempotencyKey", key);
    }

    /** n codes of tenant A's variant. */
    private List<LabelCodeService.Line> n(int count) {
        return List.of(new LabelCodeService.Line(variantA, count, null, null));
    }

    /** n codes of tenant B's variant. */
    private List<LabelCodeService.Line> nB(int count) {
        return List.of(new LabelCodeService.Line(variantB, count, null, null));
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
