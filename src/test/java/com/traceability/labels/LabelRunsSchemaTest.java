package com.traceability.labels;

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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 6a — V187 (factory runs): the schema rules, as app_user.
 *
 *   s1  RLS is enabled AND forced on label_runs / label_run_lines (NULLIF policy: no tenant set → zero rows)
 *   s2  app_user, tenant A: sees its own run, line and factory code (positive control) and NONE of tenant B's;
 *       can't insert a row for tenant B
 *   s3  lifecycle-only grants: app_user may close a run and record a line's close reason; may not change a run's
 *       factory / reference, a line's variant / expected qty, a code's run line / overage; never DELETE
 *   s4  CHECKs: a factory code needs a run line and a non-factory code can't have one; overage only on factory
 *       codes; a factory batch needs a run; expected_qty 1..20,000; a closed run carries closed_at + closed_by;
 *       a close reason carries time + user; one line per variant per run
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelRunsSchemaTest {

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

    @Autowired JdbcTemplate jdbc;
    JdbcTemplate app;
    TransactionTemplate appTx;

    UUID tenantA, tenantB, ownerA, ownerB, variantA, variantB;
    UUID runA, lineA, batchA, codeA, runB, lineB;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID(); tenantB = UUID.randomUUID(); ownerA = UUID.randomUUID(); ownerB = UUID.randomUUID();
        variantA = seedTenant(tenantA, ownerA, "LRA");
        variantB = seedTenant(tenantB, ownerB, "LRB");
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        app = new JdbcTemplate(ds);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private UUID seedTenant(UUID t, UUID owner, String p) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, ?, true)", t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            owner, t, p.toLowerCase() + "-o@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, t, p.toLowerCase() + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Widget', 'active')",
            product, t, store, p + "-P");
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Blue', ?)",
            v, t, product, p + "-V", p + "BLUE");
        return v;
    }

    @BeforeEach
    void each() {
        runA  = run(tenantA, ownerA);
        lineA = line(tenantA, runA, variantA, 1, 10);
        batchA = batch(tenantA, ownerA, "factory", runA);
        codeA = code(tenantA, ownerA, "P800001", "factory", batchA, variantA, lineA);
        runB  = run(tenantB, ownerB);
        lineB = line(tenantB, runB, variantB, 1, 5);
        code(tenantB, ownerB, "P800002", "factory", batch(tenantB, ownerB, "factory", runB), variantB, lineB);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (String t : List.of("label_codes", "label_code_batches", "label_run_lines", "label_runs")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        }
    }

    @Test
    void s1_rls_enabled_and_forced() {
        for (String t : List.of("label_runs", "label_run_lines")) {
            assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class " +
                "WHERE relname = ? AND relnamespace = 'public'::regnamespace", Boolean.class, t)).as(t).isTrue();
            Integer seen = appTx.execute(s -> app.queryForObject("SELECT count(*)::int FROM " + t, Integer.class));
            assertThat(seen).as(t + " with no tenant set").isZero();
        }
    }

    @Test
    void s2_app_user_sees_only_its_tenant() {
        assertThat(asA(() -> app.queryForList("SELECT id FROM label_runs", UUID.class))).as("positive control").containsExactly(runA);
        assertThat(asA(() -> app.queryForList("SELECT id FROM label_run_lines", UUID.class))).containsExactly(lineA);
        assertThat(asA(() -> app.queryForList("SELECT id FROM label_codes WHERE run_line_id IS NOT NULL", UUID.class)))
            .containsExactly(codeA);
        Integer runs = asA(() -> app.queryForObject("SELECT count(*)::int FROM label_runs WHERE id = ?", Integer.class, runB));
        Integer lines = asA(() -> app.queryForObject("SELECT count(*)::int FROM label_run_lines WHERE id = ?", Integer.class, lineB));
        assertThat(runs).isZero();
        assertThat(lines).isZero();
        assertThatThrownBy(() -> asA(() -> app.update(
            "INSERT INTO label_runs (tenant_id, factory_name, created_by) VALUES (?, 'X', ?)", tenantB, ownerB)))
            .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void s3_lifecycle_only_grants() {
        Integer closed = asA(() -> app.update("UPDATE label_runs SET status = 'closed', closed_at = now(), closed_by = ? WHERE id = ?",
            ownerA, runA));
        Integer reason = asA(() -> app.update("UPDATE label_run_lines SET close_reason = 'lost', close_recorded_at = now(), " +
            "close_recorded_by = ? WHERE id = ?", ownerA, lineA));
        assertThat(closed).isEqualTo(1);
        assertThat(reason).isEqualTo(1);

        for (String sql : List.of(
                "UPDATE label_runs SET factory_name = 'Y' WHERE id = '" + runA + "'",
                "UPDATE label_runs SET reference = 'Y' WHERE id = '" + runA + "'",
                "UPDATE label_run_lines SET expected_qty = 3 WHERE id = '" + lineA + "'",
                "UPDATE label_run_lines SET variant_id = variant_id WHERE id = '" + lineA + "'",
                "UPDATE label_codes SET run_line_id = NULL WHERE id = '" + codeA + "'",
                "UPDATE label_codes SET overage = true WHERE id = '" + codeA + "'",
                "UPDATE label_code_batches SET run_id = NULL WHERE id = '" + batchA + "'",
                "DELETE FROM label_runs WHERE id = '" + runA + "'",
                "DELETE FROM label_run_lines WHERE id = '" + lineA + "'")) {
            assertThatThrownBy(() -> asA(() -> app.update(sql))).as(sql).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void s4_checks() {
        UUID packBatch = batch(tenantA, ownerA, "pack", null);
        assertThatThrownBy(() -> code(tenantA, ownerA, "P800010", "factory", batchA, variantA, null))
            .hasMessageContaining("label_codes_factory_has_run_line");
        assertThatThrownBy(() -> code(tenantA, ownerA, "P800011", "pack", packBatch, variantA, lineA))
            .hasMessageContaining("label_codes_factory_has_run_line");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id, overage) " +
            "VALUES (?, 'P800012', 'pack', ?, ?, ?, true)", tenantA, packBatch, ownerA, variantA))
            .hasMessageContaining("label_codes_overage_is_factory");
        assertThatThrownBy(() -> batch(tenantA, ownerA, "factory", null)).hasMessageContaining("label_code_batches_factory_has_run");
        assertThatThrownBy(() -> batch(tenantA, ownerA, "tag", runA)).hasMessageContaining("label_code_batches_factory_has_run");
        assertThatThrownBy(() -> line(tenantA, runA, variantA, 2, 1)).hasMessageContaining("label_run_lines_run_id_variant_id_key");
        UUID other = seedVariant(tenantA);
        assertThatThrownBy(() -> line(tenantA, runA, other, 3, 0)).hasMessageContaining("expected_qty");
        assertThatThrownBy(() -> line(tenantA, runA, other, 3, 20001)).hasMessageContaining("expected_qty");
        assertThat(line(tenantA, runA, other, 3, 20000)).isNotNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE label_runs SET status = 'closed' WHERE id = ?", runA))
            .hasMessageContaining("label_runs_closed_has_time");
        assertThatThrownBy(() -> jdbc.update("UPDATE label_runs SET status = 'closed', closed_at = now() WHERE id = ?", runA))
            .hasMessageContaining("label_runs_closed_has_user");
        assertThatThrownBy(() -> jdbc.update("UPDATE label_run_lines SET close_reason = 'lost' WHERE id = ?", lineA))
            .hasMessageContaining("label_run_lines_reason_has_time");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO label_runs (tenant_id, factory_name, created_by) VALUES (?, '  ', ?)",
            tenantA, ownerA)).hasMessageContaining("factory_name");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private <T> T asA(Supplier<T> body) {
        TenantContext.set(tenantA);
        try {
            return appTx.execute(s -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedVariant(UUID t) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "SELECT ?, tenant_id, product_id, 'LR-X-' || ?, 'Red', 'LRRED' FROM variants WHERE id = ?", v, v.toString(), variantA);
        return v;
    }

    private UUID run(UUID t, UUID owner) {
        return jdbc.queryForObject("INSERT INTO label_runs (tenant_id, factory_name, reference, created_by) " +
            "VALUES (?, 'Nile Textiles', 'PO-7', ?) RETURNING id", UUID.class, t, owner);
    }

    private UUID line(UUID t, UUID run, UUID variant, int no, int qty) {
        return jdbc.queryForObject("INSERT INTO label_run_lines (tenant_id, run_id, line_no, variant_id, expected_qty) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING id", UUID.class, t, run, no, variant, qty);
    }

    private UUID batch(UUID t, UUID owner, String kind, UUID run) {
        return jdbc.queryForObject("INSERT INTO label_code_batches (tenant_id, kind, count, idempotency_key, created_by, run_id) " +
            "VALUES (?, ?, 1, ?, ?, ?) RETURNING id", UUID.class, t, kind, "lr-" + UUID.randomUUID(), owner, run);
    }

    private UUID code(UUID t, UUID owner, String code, String kind, UUID batch, UUID variant, UUID line) {
        return jdbc.queryForObject("INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id, run_line_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, t, code, kind, batch, owner, variant, line);
    }
}
