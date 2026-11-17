package com.traceability;

import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarPricing;
import com.traceability.bazar.BazarSaleService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B2 — quick sell, undo, payment / customer edits, the sales list, the event's today totals, idempotency,
 * the concurrent double scan, seller roles, the flag, RLS (app_user, with a same-tenant positive control) and the
 * append-only payments table.
 */
/**
 * Bazar POS B3 — return (good / damaged) and exchange (up / down / even): piece states, the transfer piece, the money
 * rows (only real money moves: refund = sale price; an exchange moves only its difference), the default refund
 * method, not sold here, double return, rollback of a refused exchange, idempotent retries, today / event totals,
 * the undo + method-change rules for an exchange's new sale, no Shopify rows, and the whole-pound price rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarReturnExchangeTest {

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

    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired JdbcTemplate     jdbc;

    UUID tenantId, ownerId, workerId, mainId;
    UUID varA, varB, varC, varNoPrice;      // 349 / 500 / 200 / none
    BazarSaleService.Actor owner, worker;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Return Co', true)", tenantId);
        ownerId  = user("owner", "Owner Omar");
        workerId = user("worker", "Seller Sara");
        owner  = new BazarSaleService.Actor(ownerId, "owner");
        worker = new BazarSaleService.Actor(workerId, "worker");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-return.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BR', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        varA = variant(productId, "A", "349.00");
        varB = variant(productId, "B", "500.00");
        varC = variant(productId, "C", "200.00");
        varNoPrice = variant(productId, "N", null);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID user(String role, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
            id, tenantId, name, role + "-" + id + "@bazar-return-test.com", role);
        return id;
    }

    private UUID variant(UUID productId, String title, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, ?::numeric)",
            id, tenantId, productId, "VAR-BR-" + title + "-" + id, title, price);
        return id;
    }

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    record Fixture(UUID eventId, UUID transferId, Map<UUID, List<String>> pieces) {
        String p(UUID variant, int i) { return pieces.get(variant).get(i); }
    }

    /** A LIVE event (cash / card / instapay, every role sells) with {@code n} pieces of each variant sent out. */
    private Fixture live(int n, UUID... variants) { return live(List.of("owner", "manager", "worker"), n, variants); }

    private Fixture live(List<String> roles, int n, UUID... variants) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Return Bazar " + (++seq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "Return Hall " + seq,
            List.of("cash", "card", "instapay"), roles, null, null, null), ownerId);
        UUID eventId = (UUID) ev.get("id");
        Map<UUID, List<String>> pieces = new java.util.LinkedHashMap<>();
        for (UUID v : variants) {
            List<String> ps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String p = piece(v);
                assertThat(bazar.scanOut(eventId, "PC-" + p, ownerId, false).success()).isTrue();
                ps.add(p);
            }
            pieces.put(v, ps);
        }
        bazar.markSent(eventId, ownerId);
        bazar.start(eventId, ownerId);
        return new Fixture(eventId, (UUID) ev.get("transfer_id"), pieces);
    }

    private static String crid() { return "req-" + UUID.randomUUID(); }

    private static BazarException err(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e; }
        throw new AssertionError("expected a BazarException");
    }

    private static BazarException.Code codeOf(Runnable r) { return err(r).code(); }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String outcome(UUID transferId, String pieceId) {
        return jdbc.queryForObject("SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, transferId, pieceId);
    }

    private Map<String, Object> line(UUID transferId, UUID variant) {
        return jdbc.queryForMap("SELECT qty_sold, qty_condemned, qty_returned_good FROM transfer_lines " +
            "WHERE transfer_id = ? AND variant_id = ?", transferId, variant);
    }

    private int shopifyRows() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?) " +
            "+ (SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?)", Integer.class, tenantId, tenantId);
    }

    private List<Map<String, Object>> money(UUID saleId) {
        return jdbc.queryForList("SELECT method, amount, kind FROM bazar_payments WHERE sale_id = ? " +
            "ORDER BY created_at, id", saleId);
    }

    private int paymentRows(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bazar_payments p JOIN bazar_sales s ON s.id = p.sale_id " +
            "WHERE s.event_id = ?", Integer.class, eventId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> today(UUID eventId) {
        return (Map<String, Object>) bazar.get(eventId).get("today");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> totals(UUID eventId) {
        return (Map<String, Object>) bazar.get(eventId).get("totals");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> byMethod(Map<String, Object> t) {
        return (Map<String, BigDecimal>) t.get("by_method");
    }

    private UUID sell(Fixture f, String pieceId, String method) {
        return (UUID) sales.sell(f.eventId(), code(pieceId), crid(), method, worker).get("id");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String k) { return (Map<String, Object>) m.get(k); }

    // ── return ───────────────────────────────────────────────────────────────

    @Test
    void returnGood_pieceBackOnTheBazar_outcomeOutstanding_refundByTheMethodPaid_canSellAgain_noShopify() {
        Fixture f = live(1, varA);
        String p = f.p(varA, 0);
        UUID saleId = sell(f, p, "card");
        int shopifyBefore = shopifyRows();

        Map<String, Object> r = sales.returnSale(f.eventId(), code(p), null, "good", crid(), worker);

        assertThat(r.get("replayed")).isEqualTo(false);
        assertThat(sub(r, "sale").get("status")).isEqualTo("returned");
        assertThat(sub(r, "sale").get("return_condition")).isEqualTo("good");
        assertThat(sub(r, "sale").get("returned_by_name")).isEqualTo("Seller Sara");
        assertThat(sub(r, "sale").get("returned_at")).isNotNull();
        assertThat(sub(r, "refund").get("method")).as("default = the method the customer paid with").isEqualTo("card");
        assertThat(sub(r, "refund").get("amount")).isEqualTo(new BigDecimal("349.00"));

        assertThat(pieceStatus(p)).isEqualTo("out_on_transfer");
        assertThat(outcome(f.transferId(), p)).as("outstanding again").isNull();
        assertThat(line(f.transferId(), varA).get("qty_sold")).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT event_type FROM piece_events WHERE piece_id = ? " +
            "AND from_status = 'sold' AND to_status = 'out_on_transfer'", String.class, p)).isEqualTo("bazar_returned");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM piece_events WHERE piece_id = ? " +
            "AND event_type = 'bazar_returned'", UUID.class, p)).isEqualTo(workerId);
        List<Map<String, Object>> m = money(saleId);
        assertThat(m).extracting(x -> x.get("kind")).containsExactly("payment", "refund");
        assertThat(m.get(1).get("method")).isEqualTo("card");
        assertThat(m.get(1).get("amount")).isEqualTo(new BigDecimal("349.00"));

        // Money in, money out: nothing taken today; revenue 0.
        Map<String, Object> t = today(f.eventId());
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("0");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("0");
        assertThat(byMethod(t).get("card")).isEqualByComparingTo("0");
        assertThat(t.get("sales")).isEqualTo(0);
        assertThat(t.get("returns")).isEqualTo(1);
        assertThat(shopifyRows()).as("no Shopify row of any kind").isEqualTo(shopifyBefore);

        // Back on the rail: it sells again (a new sale; the returned one stays).
        UUID again = sell(f, p, null);
        assertThat(again).isNotEqualTo(saleId);
        assertThat(pieceStatus(p)).isEqualTo("sold");
        assertThat((BigDecimal) today(f.eventId()).get("total")).isEqualByComparingTo("349.00");
    }

    @Test
    void returnDamaged_pieceDamaged_transferPieceCondemned_neverSoldAgain() {
        Fixture f = live(1, varA);
        String p = f.p(varA, 0);
        sell(f, p, null);
        int shopifyBefore = shopifyRows();

        Map<String, Object> r = sales.returnSale(f.eventId(), code(p), null, "damaged", crid(), worker);

        assertThat(sub(r, "sale").get("return_condition")).isEqualTo("damaged");
        assertThat(sub(r, "refund").get("method")).isEqualTo("cash");
        assertThat(pieceStatus(p)).isEqualTo("damaged");
        assertThat(outcome(f.transferId(), p)).isEqualTo("condemned");
        assertThat(line(f.transferId(), varA).get("qty_sold")).isEqualTo(0);
        assertThat(line(f.transferId(), varA).get("qty_condemned")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM piece_events WHERE piece_id = ? ORDER BY occurred_at, id",
            String.class, p)).endsWith("bazar_sold", "bazar_returned", "bazar_return_damaged");
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(p), crid(), null, worker)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_HERE);
        assertThat(shopifyRows()).isEqualTo(shopifyBefore);
    }

    @Test
    void return_refundMethodChosen_orRefused_conditionRequired() {
        Fixture f = live(2, varA);
        sell(f, f.p(varA, 0), "cash");
        Map<String, Object> r = sales.returnSale(f.eventId(), code(f.p(varA, 0)), "instapay", "good", crid(), worker);
        assertThat(sub(r, "refund").get("method")).isEqualTo("instapay");
        assertThat(byMethod(today(f.eventId())).get("cash")).isEqualByComparingTo("349.00");
        assertThat(byMethod(today(f.eventId())).get("instapay")).isEqualByComparingTo("-349.00");

        sell(f, f.p(varA, 1), null);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 1)), "bitcoin", "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_PAYMENT_METHOD_NOT_ALLOWED);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 1)), null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 1)), null, "good", " ", worker)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(pieceStatus(f.p(varA, 1))).isEqualTo("sold");
    }

    @Test
    void return_pieceNotSoldHere_isRefused() {
        Fixture f = live(2, varA);
        Fixture other = live(1, varA);
        // Never sold: on the bazar's rail.
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 0)), null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_SOLD_HERE);
        // Undone: it wasn't sold.
        UUID undone = sell(f, f.p(varA, 1), null);
        sales.undo(f.eventId(), undone, owner);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 1)), null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_SOLD_HERE);
        // Sold at ANOTHER bazar.
        sell(other, other.p(varA, 0), null);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(other.p(varA, 0)), null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_SOLD_HERE);
        assertThat(pieceStatus(other.p(varA, 0))).isEqualTo("sold");
        // A piece still in the warehouse.
        String home = piece(varA);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(home), null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_SOLD_HERE);
        // Unknown code.
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), "P0000000", null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_FOUND);
    }

    @Test
    void return_twice_secondIsAlreadyReturned_sameRequestIdReplays() {
        Fixture f = live(1, varA);
        String p = f.p(varA, 0);
        UUID saleId = sell(f, p, null);
        String id = crid();
        Map<String, Object> first = sales.returnSale(f.eventId(), code(p), null, "good", id, worker);

        // The phone retried (weak signal): same request id → the first return, nothing more.
        Map<String, Object> again = sales.returnSale(f.eventId(), code(p), null, "good", id, worker);
        assertThat(again.get("replayed")).isEqualTo(true);
        assertThat(sub(again, "sale").get("id")).isEqualTo(sub(first, "sale").get("id"));
        assertThat(money(saleId)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'bazar_returned'",
            Integer.class, p)).isEqualTo(1);

        // A second return (new request id).
        BazarException e = err(() -> sales.returnSale(f.eventId(), code(p), null, "good", crid(), worker));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_ALREADY_RETURNED);
        assertThat(e.details()).containsEntry("returned_by_name", "Seller Sara");
        assertThat(money(saleId)).hasSize(2);
        // And the returned sale can't be undone / edited.
        assertThat(codeOf(() -> sales.undo(f.eventId(), saleId, owner))).isEqualTo(BazarException.Code.BAZAR_ALREADY_RETURNED);
        assertThat(codeOf(() -> sales.changePayment(f.eventId(), saleId, "card", owner)))
            .isEqualTo(BazarException.Code.BAZAR_ALREADY_RETURNED);
    }

    @Test
    void return_onlySellerRoles_onlyWhileLive() {
        Fixture f = live(List.of("owner", "manager"), 1, varA);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.p(varA, 0)), crid(), null, owner).get("id");
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 0)), null, "good", crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_A_SELLER);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(f.p(varA, 0)), "x", null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_A_SELLER);
        jdbc.update("UPDATE bazar_events SET status = 'ended', ended_at = now(), ended_by = ? WHERE id = ?",
            ownerId, f.eventId());
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 0)), null, "good", crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_sales WHERE id = ?", String.class, saleId))
            .isEqualTo("active");
    }

    // ── exchange ─────────────────────────────────────────────────────────────

    @Test
    void exchangeUp_customerPaysTheDifference_oldCredited_customerCarriedOver() {
        Fixture f = live(1, varA, varB);
        String old = f.p(varA, 0), next = f.p(varB, 0);
        UUID oldSale = sell(f, old, "cash");
        sales.setCustomer(f.eventId(), oldSale, "Nour", "01012345678", worker);
        int shopifyBefore = shopifyRows();

        Map<String, Object> x = sales.exchange(f.eventId(), code(old), code(next), "instapay", null, crid(), worker);

        assertThat(x.get("replayed")).isEqualTo(false);
        assertThat(x.get("difference")).isEqualTo(new BigDecimal("151.00"));
        assertThat(sub(x, "money")).containsEntry("kind", "payment").containsEntry("method", "instapay")
            .containsEntry("amount", new BigDecimal("151.00"));
        Map<String, Object> o = sub(x, "old_sale"), n = sub(x, "new_sale");
        assertThat(o.get("status")).isEqualTo("returned");
        assertThat(o.get("exchanged_for_sale_number")).isEqualTo(n.get("sale_number"));
        assertThat(n.get("status")).isEqualTo("active");
        assertThat(n.get("exchange_of_sale_number")).isEqualTo(o.get("sale_number"));
        assertThat(n.get("price")).isEqualTo(new BigDecimal("500.00"));
        assertThat(n.get("customer_name")).isEqualTo("Nour");
        assertThat(n.get("customer_phone")).as("masked for a worker").isEqualTo("010*****678");
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM bazar_sales WHERE id = ?", String.class, n.get("id")))
            .isEqualTo("01012345678");

        // Money rows: the old sale keeps its payment (no refund: credited); the new sale has only the difference.
        assertThat(money(oldSale)).extracting(r -> r.get("kind")).containsExactly("payment");
        assertThat(money((UUID) n.get("id"))).hasSize(1);
        assertThat(pieceStatus(old)).isEqualTo("out_on_transfer");
        assertThat(outcome(f.transferId(), old)).isNull();
        assertThat(pieceStatus(next)).isEqualTo("sold");
        assertThat(outcome(f.transferId(), next)).isEqualTo("sold");

        Map<String, Object> t = today(f.eventId());
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("500.00");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("500.00");
        assertThat(byMethod(t).get("cash")).isEqualByComparingTo("349.00");
        assertThat(byMethod(t).get("instapay")).isEqualByComparingTo("151.00");
        assertThat(t.get("sales")).isEqualTo(1);
        assertThat(t.get("returns")).as("an exchange is not a return").isEqualTo(0);
        assertThat(t.get("exchanges")).isEqualTo(1);
        assertThat(shopifyRows()).isEqualTo(shopifyBefore);
    }

    @Test
    void exchangeDown_customerGetsTheDifferenceBack_byTheMethodPaid() {
        Fixture f = live(1, varB, varC);
        UUID oldSale = sell(f, f.p(varB, 0), "card");
        Map<String, Object> x = sales.exchange(f.eventId(), code(f.p(varB, 0)), code(f.p(varC, 0)), null, null, crid(),
            worker);
        assertThat(x.get("difference")).isEqualTo(new BigDecimal("-300.00"));
        assertThat(sub(x, "money")).containsEntry("kind", "refund").containsEntry("method", "card")
            .containsEntry("amount", new BigDecimal("300.00"));
        assertThat(money(oldSale)).extracting(r -> r.get("kind")).containsExactly("payment");
        Map<String, Object> t = today(f.eventId());
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("200.00");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("200.00");
        assertThat(byMethod(t).get("card")).isEqualByComparingTo("200.00");
    }

    @Test
    void exchangeEven_noMoneyRow() {
        Fixture f = live(2, varA);
        sell(f, f.p(varA, 0), "cash");
        int before = paymentRows(f.eventId());
        Map<String, Object> x = sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varA, 1)), "card", null, crid(),
            worker);
        assertThat(x.get("difference")).isEqualTo(new BigDecimal("0.00"));
        assertThat(x.get("money")).isNull();
        assertThat(paymentRows(f.eventId())).isEqualTo(before);
        assertThat((BigDecimal) today(f.eventId()).get("total")).isEqualByComparingTo("349.00");
        assertThat(byMethod(today(f.eventId())).get("card")).isEqualByComparingTo("0");
    }

    @Test
    void exchange_oldPieceDamaged_isCondemned() {
        Fixture f = live(1, varA, varB);
        sell(f, f.p(varA, 0), null);
        sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varB, 0)), null, "damaged", crid(), worker);
        assertThat(pieceStatus(f.p(varA, 0))).isEqualTo("damaged");
        assertThat(outcome(f.transferId(), f.p(varA, 0))).isEqualTo("condemned");
    }

    @Test
    void exchange_refused_nothingChanges() {
        Fixture f = live(2, varA, varNoPrice);
        String old = f.p(varA, 0);
        UUID oldSale = sell(f, old, null);
        String soldElsewhere = f.p(varA, 1);
        sell(f, soldElsewhere, null);
        String home = piece(varB);
        int shopifyBefore = shopifyRows();
        int pays = paymentRows(f.eventId());
        int events = jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId);

        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(old), code(home), null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_HERE);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(old), code(soldElsewhere), null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_ALREADY_SOLD);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(old), code(f.p(varNoPrice, 0)), null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_NO_PRICE);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(old), "P0000000", null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_FOUND);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(old), code(old), null, null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(f.p(varNoPrice, 1)), code(f.p(varNoPrice, 0)), null,
            null, crid(), worker))).isEqualTo(BazarException.Code.BAZAR_NOT_SOLD_HERE);

        assertThat(jdbc.queryForObject("SELECT status FROM bazar_sales WHERE id = ?", String.class, oldSale))
            .isEqualTo("active");
        assertThat(pieceStatus(old)).isEqualTo("sold");
        assertThat(outcome(f.transferId(), old)).isEqualTo("sold");
        assertThat(pieceStatus(home)).isEqualTo("available");
        assertThat(paymentRows(f.eventId())).isEqualTo(pays);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId))
            .isEqualTo(events);
        assertThat(shopifyRows()).isEqualTo(shopifyBefore);
    }

    @Test
    void exchange_sameRequestId_replays_oneNewSale() {
        Fixture f = live(1, varA, varB);
        sell(f, f.p(varA, 0), null);
        String id = crid();
        Map<String, Object> first = sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varB, 0)), null, null, id, worker);
        Map<String, Object> again = sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varB, 0)), null, null, id, worker);
        assertThat(again.get("replayed")).isEqualTo(true);
        assertThat(sub(again, "new_sale").get("id")).isEqualTo(sub(first, "new_sale").get("id"));
        assertThat(again.get("money")).isEqualTo(first.get("money"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", Integer.class, f.eventId()))
            .isEqualTo(2);
        assertThat((BigDecimal) today(f.eventId()).get("total")).isEqualByComparingTo("500.00");
        // A request id already used by a plain sale is not an exchange.
        String saleId = crid();
        Fixture g = live(1, varA, varB);
        sales.sell(g.eventId(), code(g.p(varA, 0)), saleId, null, worker);
        assertThat(codeOf(() -> sales.exchange(g.eventId(), code(g.p(varA, 0)), code(g.p(varB, 0)), null, null, saleId,
            worker))).isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
    }

    @Test
    void exchangeNewSale_cannotBeUndone_methodChangeKeepsTheDifference_returnRefundsItsFullPrice() {
        Fixture f = live(1, varA, varB);
        sell(f, f.p(varA, 0), "cash");
        Map<String, Object> x = sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varB, 0)), null, null, crid(), worker);
        UUID newSale = (UUID) sub(x, "new_sale").get("id");

        assertThat(codeOf(() -> sales.undo(f.eventId(), newSale, owner))).isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);

        sales.changePayment(f.eventId(), newSale, "card", worker);
        List<Map<String, Object>> m = money(newSale);
        assertThat(m).extracting(r -> r.get("kind")).containsExactly("payment", "void", "payment");
        assertThat(m.get(2).get("amount")).as("the difference, not the full price").isEqualTo(new BigDecimal("151.00"));
        assertThat(m.get(2).get("method")).isEqualTo("card");
        assertThat((BigDecimal) today(f.eventId()).get("total")).isEqualByComparingTo("500.00");

        // Returning the new piece gives back everything the customer paid (349 + 151).
        Map<String, Object> r = sales.returnSale(f.eventId(), code(f.p(varB, 0)), null, "good", crid(), worker);
        assertThat(sub(r, "refund")).containsEntry("amount", new BigDecimal("500.00")).containsEntry("method", "card");
        Map<String, Object> t = today(f.eventId());
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("0");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("0");
    }

    @Test
    void totals_afterSalesReturnsAndExchanges_todayAndEvent_netCorrectly() {
        Fixture f = live(2, varA, varB, varC);
        sell(f, f.p(varA, 0), "cash");                                     // +349 cash
        sell(f, f.p(varB, 0), "card");                                     // +500 card
        sell(f, f.p(varC, 0), "instapay");                                 // +200 instapay
        sales.returnSale(f.eventId(), code(f.p(varB, 0)), null, "good", crid(), worker);          // −500 card
        sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varC, 1)), null, null, crid(), worker); // −149 cash
        UUID undone = sell(f, f.p(varB, 1), "cash");
        sales.undo(f.eventId(), undone, owner);                            // voided

        for (Map<String, Object> t : List.of(today(f.eventId()), totals(f.eventId()))) {
            assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("400.00");
            assertThat((BigDecimal) t.get("revenue")).as("active sales: 200 + 200").isEqualByComparingTo("400.00");
            assertThat(byMethod(t).get("cash")).isEqualByComparingTo("200.00");
            assertThat(byMethod(t).get("card")).isEqualByComparingTo("0");
            assertThat(byMethod(t).get("instapay")).isEqualByComparingTo("200.00");
            assertThat(t.get("sales")).isEqualTo(2);
            assertThat(t.get("returns")).isEqualTo(1);
            assertThat(t.get("exchanges")).isEqualTo(1);
        }
        Map<String, Object> summary = sales.todaySummary(f.eventId(), worker);
        assertThat((BigDecimal) summary.get("total")).isEqualByComparingTo("400.00");
        assertThat(summary.get("sale_count")).isEqualTo(2);
        assertThat(summary.get("returns")).isEqualTo(1);
        assertThat(summary.get("exchanges")).isEqualTo(1);
    }

    @Test
    void returnedSaleListedWithItsBadges() {
        Fixture f = live(1, varA, varB);
        sell(f, f.p(varA, 0), null);
        sales.exchange(f.eventId(), code(f.p(varA, 0)), code(f.p(varB, 0)), null, null, crid(), worker);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sales.list(f.eventId(), 10, null, worker).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("exchange_of_sale_number")).isNotNull();
        assertThat(items.get(1).get("status")).isEqualTo("returned");
        assertThat(items.get(1).get("exchanged_for_sale_number")).isEqualTo(items.get(0).get("sale_number"));
    }

    // ── piece lookup ─────────────────────────────────────────────────────────

    @Test
    void lookup_statesOfAPieceAtTheEvent_readOnly() {
        Fixture f = live(3, varA);
        String forSale = f.p(varA, 0), sold = f.p(varA, 1), damaged = f.p(varA, 2);
        sell(f, sold, "card");
        sell(f, damaged, null);
        sales.returnSale(f.eventId(), code(damaged), null, "damaged", crid(), worker);
        int pays = paymentRows(f.eventId());

        Map<String, Object> a = sales.lookupPiece(f.eventId(), code(forSale), worker);
        assertThat(a).containsEntry("state", "for_sale").containsEntry("price", new BigDecimal("349.00"))
            .containsEntry("product_title", "Linen Shirt").containsEntry("sale", null);
        Map<String, Object> b = sales.lookupPiece(f.eventId(), code(sold), worker);
        assertThat(b.get("state")).isEqualTo("sold");
        assertThat(sub(b, "sale")).containsEntry("method", "card").containsEntry("seller_name", "Seller Sara");
        assertThat(sales.lookupPiece(f.eventId(), code(damaged), worker).get("state")).isEqualTo("returned");
        assertThat(sales.lookupPiece(f.eventId(), code(piece(varA)), worker).get("state")).isEqualTo("not_here");
        assertThat(paymentRows(f.eventId())).isEqualTo(pays);
        assertThat(pieceStatus(sold)).isEqualTo("sold");
    }

    @Test
    void lookup_anotherTenantsEventOrPiece_isNotFound() {
        Fixture f = live(1, varA);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Return Co', true)", other);
        String theirs = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P9990001', 'available')", theirs, other, varA, "PC-" + theirs);
        assertThat(codeOf(() -> sales.lookupPiece(f.eventId(), "P9990001", worker)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_FOUND);
        TenantContext.set(other);
        assertThat(codeOf(() -> sales.lookupPiece(f.eventId(), code(f.p(varA, 0)),
            new BazarSaleService.Actor(ownerId, "owner")))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_FOUND);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(varA, 0)), null, "good", crid(),
            new BazarSaleService.Actor(ownerId, "owner")))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_FOUND);
    }

    // ── pricing fix ──────────────────────────────────────────────────────────

    @Test
    void prices_wholePounds_ruleWithoutRoundTo_overrideWithPiastersRefused() {
        assertThat(BazarPricing.ruled(new BigDecimal("2045.52"), new BigDecimal("10"), null).toPlainString())
            .as("the live-test price: 1840.97 → 1841").isEqualTo("1841.00");
        assertThat(BazarPricing.ruled(new BigDecimal("349.00"), new BigDecimal("20"), new BigDecimal("0.5")).toPlainString())
            .isEqualTo("279.00");
        Fixture f = live(1, varA);
        assertThat(codeOf(() -> bazar.setOverride(f.eventId(), varA, new BigDecimal("1840.97"), ownerId)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> bazar.setBulkRule(f.eventId(), new BigDecimal("10"), new BigDecimal("2.5"), ownerId)))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        bazar.setOverride(f.eventId(), varA, new BigDecimal("1840.00"), ownerId);
        bazar.setBulkRule(f.eventId(), new BigDecimal("10"), null, ownerId);
        assertThat(jdbc.queryForObject("SELECT price FROM bazar_event_prices WHERE event_id = ? AND variant_id = ?",
            BigDecimal.class, f.eventId(), varA)).isEqualTo(new BigDecimal("1840.00"));
    }
}
