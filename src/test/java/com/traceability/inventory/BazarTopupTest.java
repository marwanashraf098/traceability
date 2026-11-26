package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bazar top-up (V245, approved 2026-11-27, design B) — more pieces for a LIVE bazar after Mark sent, on its SAME
 * transfer, one batch at a time. A tenant whose Main Warehouse is linked and counted, the Shopify gateway mocked.
 *
 *   r1  Remove: the batch's list moves nothing and writes nothing; Send claims exactly its N pieces once (its own claim
 *       row, its own push key ≠ the first send's), the pieces are on the bazar at once; a replay of Send changes nothing
 *   k1  Keep: a batch Send writes no claim and calls nothing
 *   p1  prices: a new size gets the bulk rule's price, a size with no Shopify price stays priceless (no_price_count),
 *       an override already set is untouched
 *   e1  End event with two batches: counts, "batches", and each good leftover's +1 (first-send and batch pieces alike)
 *   e2  End event is refused while a batch is open; discard → End works
 *   g1  refusals: draft / ended bazar, an empty batch, a listed piece that changed (named, nothing sent), retries by
 *       request id (count add, remove), a piece already on the list / on a transfer
 *   s1  shared rule — Main reconcile's away-piece claim join matches on the batch
 *   s2  shared rule — the void_hold_sync_failed key is per batch (the first send's key unchanged)
 *   s3  shared rule — the return +1's seed branch uses the batch's send time
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarTopupTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate     jdbc;
    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired BazarDayService  days;
    @Autowired BazarEndService  end;
    @Autowired ExceptionService exceptions;
    @Autowired TransferShopifySync transferSync;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    UUID tenant, owner, main, variant, variant2, variantNoPrice;
    String traced;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        main = UUID.randomUUID();
        variant = UUID.randomUUID();
        variant2 = UUID.randomUUID();
        variantNoPrice = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = "bazar-topup-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Topup', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, "owner-" + tenant + "@bazar-topup.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        for (Object[] v : List.of(new Object[]{variant, "Red", "SKU-RED", new BigDecimal("500.00")},
                                  new Object[]{variant2, "Blue", "SKU-BLUE", new BigDecimal("1000.00")},
                                  new Object[]{variantNoPrice, "Green", "SKU-GREEN", null})) {
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", v[0], tenant, product, "gid://shopify/ProductVariant/" + v[0], v[1], v[2],
                "gid://shopify/InventoryItem/" + v[0], v[3]);
        }
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 4, 'initial_seed', 'seed', '{}'::jsonb, " +
            "'applied', now() - interval '3 hours')", tenant, UUID.randomUUID(), variant, main);
    }

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        TenantContext.set(tenant);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── r1 ────────────────────────────────────────────────────────────────

    @Test
    void r1_remove_theListMovesNothing_sendLowersExactlyItsPiecesOnce_ownKey_replayChangesNothing() throws Exception {
        String a = piece(variant), b = piece(variant);
        Ev ev = liveBazar("R1", null, List.of(a, b));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.transfer);
        clearInvocations(shopify);   // the first send's −2 went out; count only what the batch does from here
        String c = piece(variant), d = piece(variant, false), e = piece(variant, false);

        Map<String, Object> opened = bazar.openBatch(ev.id, owner);
        UUID batch = (UUID) opened.get("id");
        assertThat(opened.get("batch_no")).isEqualTo(2);
        assertThat(bazar.openBatch(ev.id, owner).get("id")).as("one open batch: the same one again").isEqualTo(batch);
        assertThat(bazar.batchScan(ev.id, batch, code(c), owner).success()).isTrue();
        assertThat(bazar.batchAddCount(ev.id, batch, variant, 2, "add-r1", owner).pieceIds()).containsExactly(d, e);

        // The list moved nothing: still available at Main, no claim, no Shopify call, not sellable at the bazar.
        assertThat(count("SELECT COUNT(*) FROM pieces WHERE id IN (?, ?, ?) AND status = 'available' AND current_location_id = ?",
            c, d, e, main)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ?", ev.transfer)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", ev.transfer)).isEqualTo(1);
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());

        Map<String, Object> sent = bazar.sendBatch(ev.id, batch, "send-r1", owner);
        assertThat(sent.get("sent")).isEqualTo(3);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE batch_id = ? AND status = 'pushed'", 1, batch);
        Map<String, Object> claim = jdbc.queryForMap("SELECT deltas::text AS deltas, piece_ids::text AS pieces " +
            "FROM transfer_shopify_syncs WHERE batch_id = ?", batch);
        assertThat((String) claim.get("pieces")).contains(c, d, e).doesNotContain(a).doesNotContain(b);
        assertThat((String) claim.get("deltas")).contains("\"" + variant + "\": 3");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), eq(traced), any(), key.capture());
        String firstKey = ShopifyGateway.idempotencyKey(tenant, "transfer_out", ev.transfer.toString(), null, traced);
        assertThat(key.getValue()).as("a batch never shares the first send's key").isNotEqualTo(firstKey)
            .isEqualTo(ShopifyGateway.idempotencyKey(tenant, "transfer_out", ev.transfer + ":batch:" + batch, null, traced));
        assertThat(count("SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND batch_id = ? AND outcome IS NULL",
            ev.transfer, batch)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM pieces WHERE id IN (?, ?, ?) AND status = 'out_on_transfer'", c, d, e)).isEqualTo(3);

        // On the bazar at once: a batch piece sells.
        sales.sell(ev.id, code(c), "sell-r1", null, actor());
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) bazar.get(ev.id).get("counts");
        assertThat(counts.get("sent")).isEqualTo(5);
        assertThat(counts.get("sold")).isEqualTo(1);

        // Replay: same answer, nothing more.
        Map<String, Object> again = bazar.sendBatch(ev.id, batch, "send-r1", owner);
        assertThat(again.get("replayed")).isEqualTo(true);
        transferSync.sweep(tenant);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", ev.transfer)).isEqualTo(2);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        // Another send of the same batch (a new id) is refused: it's sent.
        assertThat(catchThrowableOfType(() -> bazar.sendBatch(ev.id, batch, "send-r1-b", owner), BazarException.class).code())
            .isEqualTo(BazarException.Code.BAZAR_BATCH_NOT_OPEN);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) bazar.get(ev.id).get("batches");
        assertThat(batches).extracting(m -> m.get("batch_no") + ":" + m.get("pieces")).containsExactly("1:2", "2:3");
    }

    // ── k1 ────────────────────────────────────────────────────────────────

    @Test
    void k1_keep_aBatchSendWritesNothing() throws Exception {
        String a = piece(variant);
        Ev ev = liveBazar("K1", "leave", List.of(a));
        String c = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        bazar.batchScan(ev.id, batch, code(c), owner);
        bazar.sendBatch(ev.id, batch, "send-k1", owner);
        Thread.sleep(500);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", ev.transfer)).isZero();
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, ev.transfer))
            .isEqualTo("leave");
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        assertThat(count("SELECT COUNT(*) FROM pieces WHERE id = ? AND status = 'out_on_transfer'", c)).isEqualTo(1);
    }

    // ── p1 ────────────────────────────────────────────────────────────────

    @Test
    void p1_newSizeGetsTheBulkPrice_noShopifyPriceStaysPriceless_overrideUntouched() throws Exception {
        String a = piece(variant);
        Ev ev = liveBazar("P1", null, List.of(a));
        bazar.setBulkRule(ev.id, new BigDecimal("20"), new BigDecimal("5"), owner);
        bazar.setOverride(ev.id, variant, new BigDecimal("444"), owner);
        assertThat(bazar.get(ev.id).get("no_price_count")).isEqualTo(0);
        String blue = piece(variant2), green = piece(variantNoPrice), red = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        for (String p : List.of(blue, green, red)) assertThat(bazar.batchScan(ev.id, batch, code(p), owner).success()).isTrue();
        bazar.sendBatch(ev.id, batch, "send-p1", owner);

        Map<UUID, Map<String, Object>> byVariant = new HashMap<>();
        for (Map<String, Object> r : bazar.listPrices(ev.id)) byVariant.put((UUID) r.get("variant_id"), r);
        assertThat(((BigDecimal) byVariant.get(variant2).get("price"))).isEqualByComparingTo("800");
        assertThat(byVariant.get(variant2).get("source")).isEqualTo("bulk");
        assertThat(byVariant.get(variantNoPrice).get("price")).isNull();
        assertThat(((BigDecimal) byVariant.get(variant).get("price"))).isEqualByComparingTo("444");
        assertThat(byVariant.get(variant).get("source")).isEqualTo("override");
        assertThat(bazar.get(ev.id).get("no_price_count")).isEqualTo(1);
    }

    // ── e1 / e2 ───────────────────────────────────────────────────────────

    @Test
    void e1_endEventWithTwoBatches_theEquationHolds_everyGoodLeftoverGetsOnePlusOne() throws Exception {
        String a = piece(variant), b = piece(variant);
        Ev ev = liveBazar("E1", null, List.of(a, b));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.transfer);
        String c = piece(variant), d = piece(variant), e = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        for (String p : List.of(c, d, e)) bazar.batchScan(ev.id, batch, code(p), owner);
        bazar.sendBatch(ev.id, batch, "send-e1", owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE batch_id = ? AND status = 'pushed'", 1, batch);

        sales.sell(ev.id, code(a), "e1-s1", null, actor());
        sales.sell(ev.id, code(c), "e1-s2", null, actor());
        closeToday(ev, "e1");
        end.end(ev.id, actor());
        end.scanBack(ev.id, code(b), "e1-back-b", actor());
        end.scanBack(ev.id, code(d), "e1-back-d", actor());
        end.markLost(ev.id, List.of(e), actor());
        end.closeTransfer(ev.id, actor());

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) bazar.get(ev.id).get("counts");
        assertThat(counts).containsEntry("sent", 5).containsEntry("sold", 2).containsEntry("back", 2)
            .containsEntry("lost", 1).containsEntry("at_bazar", 0);
        String applied = "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return' AND status = 'applied' AND split_part(trigger_id, ':', 1) IN (?, ?)";
        await(applied, 2, tenant, b, d);
        assertThat(jdbc.queryForList("SELECT split_part(trigger_id, ':', 1) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'transfer_return' AND split_part(trigger_id, ':', 2) = ?",
            String.class, tenant, ev.transfer.toString())).containsExactlyInAnyOrder(b, d);
    }

    @Test
    void e2_endEventIsRefusedWhileABatchIsOpen_discardThenEnd() throws Exception {
        String a = piece(variant);
        Ev ev = liveBazar("E2", null, List.of(a));
        String c = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        bazar.batchScan(ev.id, batch, code(c), owner);
        BazarException ex = catchThrowableOfType(() -> end.end(ev.id, actor()), BazarException.class);
        assertThat(ex.code()).isEqualTo(BazarException.Code.BAZAR_TOPUP_OPEN);
        assertThat(ex.messageEn()).contains("Send or discard");
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_events WHERE id = ?", String.class, ev.id)).isEqualTo("live");
        bazar.discardBatch(ev.id, batch, owner);
        assertThat(count("SELECT COUNT(*) FROM pieces WHERE id = ? AND status = 'available' AND current_location_id = ?", c, main))
            .isEqualTo(1);
        end.end(ev.id, actor());
        assertThat(catchThrowableOfType(() -> bazar.openBatch(ev.id, owner), BazarException.class).code())
            .as("can't top up an ended bazar").isEqualTo(BazarException.Code.BAZAR_EVENT_ENDED);
    }

    // ── g1 ────────────────────────────────────────────────────────────────

    @Test
    void g1_refusalsAndRetries() throws Exception {
        UUID gv = newVariant("G1");   // own size: other tests leave free pieces of the class's sizes at Main
        String a = piece(gv);
        Map<String, Object> draft = bazar.create(new BazarService.CreateEvent("G1 draft", LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 2), null, "G1 draft hall", List.of("cash"), List.of("owner"), null, null, null), owner);
        assertThat(catchThrowableOfType(() -> bazar.openBatch((UUID) draft.get("id"), owner), BazarException.class).code())
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);

        Ev ev = liveBazar("G1", null, List.of(a));
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        assertThat(catchThrowableOfType(() -> bazar.sendBatch(ev.id, batch, "g1-empty", owner), BazarException.class).code())
            .isEqualTo(BazarException.Code.BAZAR_BATCH_EMPTY);
        assertThat(bazar.batchScan(ev.id, batch, code(a), owner).code()).as("on the bazar already: not available")
            .isEqualTo("WRONG_STATUS");
        String c = piece(gv), x = piece(gv, false);
        assertThat(bazar.batchScan(ev.id, batch, code(c), owner).success()).isTrue();
        assertThat(bazar.batchScan(ev.id, batch, code(c), owner).code()).isEqualTo("ALREADY_IN_BATCH");
        // Count add: retry-safe, and never a piece already listed.
        assertThat(bazar.batchAddCount(ev.id, batch, gv, 1, "g1-add", owner).pieceIds()).containsExactly(x);
        assertThat(bazar.batchAddCount(ev.id, batch, gv, 1, "g1-add", owner).replayed()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM transfer_send_batch_items WHERE batch_id = ? AND removed_at IS NULL", batch))
            .isEqualTo(2);
        assertThat(catchThrowableOfType(() -> bazar.batchAddCount(ev.id, batch, gv, 1, "g1-add-2", owner),
            BazarException.class).code()).as("nothing else free (c is listed)").isEqualTo(BazarException.Code.BAZAR_NOT_ENOUGH_AT_MAIN);

        // A listed piece taken in between (an online order): nothing sent, the piece named.
        jdbc.update("UPDATE pieces SET status = 'reserved' WHERE id = ?", x);
        BazarException changed = catchThrowableOfType(() -> bazar.sendBatch(ev.id, batch, "g1-send", owner), BazarException.class);
        assertThat(changed.code()).isEqualTo(BazarException.Code.BAZAR_BATCH_PIECES_CHANGED);
        assertThat(changed.details().get("blocked_codes")).as("no label → named by its id").isEqualTo(List.of(x));
        assertThat(count("SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ?", ev.transfer)).isEqualTo(1);
        // Remove it (by code, retry-safe), then the same request id sends.
        assertThat(bazar.batchRemove(ev.id, batch, null, gv, 1, "g1-rm", owner).pieceIds())
            .as("newest listed first").containsExactly(x);
        assertThat(bazar.batchRemove(ev.id, batch, null, gv, 1, "g1-rm", owner).replayed()).isTrue();
        assertThat(bazar.batchRemove(ev.id, batch, code(c), null, null, "g1-rm-c", owner).pieceIds()).containsExactly(c);
        assertThat(bazar.batchScan(ev.id, batch, code(c), owner).success()).as("listed again").isTrue();
        assertThat(bazar.sendBatch(ev.id, batch, "g1-send", owner).get("sent")).isEqualTo(1);   // c
        jdbc.update("UPDATE pieces SET status = 'available' WHERE id = ?", x);
    }

    // ── u1 ────────────────────────────────────────────────────────────────

    /** U1c's rule in a batch (approved 2026-11-27): by count lists only pieces without labels — fewer than asked →
     *  "N added · M have labels — scan them" (M leaves out labelled pieces already on the list); none without a label
     *  → U1c's BAZAR_PIECES_HAVE_LABELS refusal. Revert check: the picker without {@code short_code IS NULL} → red. */
    @Test
    void u1_byCountInABatch_onASizeWithLabelledPieces_listsOnlyUnlabelled() throws Exception {
        UUID uv = newVariant("U1");
        String first = piece(uv);
        Ev ev = liveBazar("U1", null, List.of(first));
        String n1 = piece(uv, false), n2 = piece(uv, false);
        String l1 = piece(uv), l2 = piece(uv), l3 = piece(uv);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        assertThat(bazar.batchScan(ev.id, batch, code(l1), owner).success()).isTrue();

        TransferService.CountResult r = bazar.batchAddCount(ev.id, batch, uv, 4, "u1-add", owner);
        assertThat(r.pieceIds()).containsExactly(n1, n2);
        assertThat(r.code()).isEqualTo("SENT_PARTIAL");
        assertThat(r.messageEn()).isEqualTo("2 added · 2 have labels — scan them");
        assertThat(r.messageAr()).contains("2").contains("امسحها");
        assertThat(r.labelledAtMain()).as("l2 and l3 — l1 is listed already").isEqualTo(2);
        assertThat(r.labelledCodes()).isNull();

        BazarException ex = catchThrowableOfType(() -> bazar.batchAddCount(ev.id, batch, uv, 1, "u1-add-2", owner),
            BazarException.class);
        assertThat(ex.code()).isEqualTo(BazarException.Code.BAZAR_PIECES_HAVE_LABELS);
        assertThat(ex.details().get("labelled_at_main")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM transfer_send_batch_items WHERE batch_id = ? AND removed_at IS NULL", batch))
            .isEqualTo(3);
        for (String p : List.of(l2, l3)) assertThat(bazar.batchScan(ev.id, batch, code(p), owner).success()).isTrue();
        assertThat(bazar.sendBatch(ev.id, batch, "u1-send", owner).get("sent")).isEqualTo(5);
    }

    // ── shared rules ──────────────────────────────────────────────────────

    /** s1: a piece of a batch whose claim is still queued is in flight — never "counted away" on the first send's
     *  pushed claim. Revert check: drop the batch match from the claim join → red (away_counted 1). */
    @Test
    void s1_mainReconcile_theAwayPiecesClaimIsItsOwnBatchs() throws Exception {
        UUID v = newVariant("S1");
        String a = piece(v);
        Ev ev = liveBazar("S1", null, List.of(a));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.transfer);
        String c = piece(v);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        bazar.batchScan(ev.id, batch, code(c), owner);
        bazar.sendBatch(ev.id, batch, "send-s1", owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE batch_id = ? AND status = 'pushed'", 1, batch);
        // The batch's −1 not confirmed yet (as right after Send).
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'queued', pushed_at = NULL WHERE batch_id = ?", batch);
        Map<String, Object> r = new NamedParameterJdbcTemplate(jdbc).queryForMap(MainReconcileRules.EVALUATE_SQL,
            new MapSqlParameterSource("t", tenant).addValue("main", main).addValue("mainGid", traced)
                .addValue("mainNum", traced.substring(traced.lastIndexOf('/') + 1)).addValue("ids", "{" + v + "}")
                .addValue("cancelWindowMinutes", 60).addValue("settle", false).addValue("holdMinutes", 60));
        assertThat(((Number) r.get("away_counted")).intValue())
            .as("a (lowered) and c (in flight) are both not counted away").isZero();
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'pushed', pushed_at = now() WHERE batch_id = ?", batch);
    }

    /** s2: two claims of one transfer that both need a person → two alerts, two keys; resolving the batch's leaves the
     *  first send's open, and the first send's key is the pre-V245 one. Revert check: key without the batch → red. */
    @Test
    void s2_voidHoldSyncFailed_keyIsPerBatch() throws Exception {
        String a = piece(variant);
        Ev ev = liveBazar("S2", null, List.of(a));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.transfer);
        String c = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        bazar.batchScan(ev.id, batch, code(c), owner);
        bazar.sendBatch(ev.id, batch, "send-s2", owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE batch_id = ? AND status = 'pushed'", 1, batch);
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'failed_ambiguous' WHERE transfer_id = ?", ev.transfer);

        String firstKey = "void_hold_sync_failed:transfer_out:" + ev.transfer;
        String batchKey = firstKey + ":batch:" + batch;
        assertThat(transferAlertKeys(ev.transfer)).containsExactlyInAnyOrder(firstKey, batchKey);
        exceptions.resolve("void_hold_sync_failed", batchKey, owner, "checked in Shopify");
        assertThat(transferAlertKeys(ev.transfer)).containsExactly(firstKey);
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'pushed' WHERE transfer_id = ?", ev.transfer);
        exceptions.resolve("void_hold_sync_failed", firstKey, owner, "test cleanup");
    }

    /** s3: a store seeded AFTER the first send but BEFORE the batch was sent — the seed counted the batch piece at Main,
     *  so with no pushed claim holding it (here: the batch claim skipped) its return is not +1. Revert check: the
     *  transfer's send time (departedAtSql) instead of the batch's → red (counted). */
    @Test
    void s3_returnPlusOne_usesTheBatchsSendTime() throws Exception {
        String a = piece(variant);
        Ev ev = liveBazar("S3", null, List.of(a));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.transfer);
        String c = piece(variant);
        UUID batch = (UUID) bazar.openBatch(ev.id, owner).get("id");
        bazar.batchScan(ev.id, batch, code(c), owner);
        bazar.sendBatch(ev.id, batch, "send-s3", owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE batch_id = ? AND status = 'pushed'", 1, batch);
        // First send 2 h ago; the tenant's (only) seed 1 h ago (the class's 3 h-old seed moved here); the batch now; its
        // claim skipped (nothing was taken out of Shopify for c).
        jdbc.update("UPDATE transfers SET sent_at = now() - interval '2 hours' WHERE id = ?", ev.transfer);
        jdbc.update("UPDATE shopify_inventory_adjustments SET created_at = now() - interval '1 hour' " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed'", tenant);
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'skipped', reason = 'test' WHERE batch_id = ?", batch);
        String sql = "SELECT " + TransferShopifySync.RETURN_COUNTED_SQL + " AS counted FROM transfer_pieces tp_out " +
            "JOIN transfers t_out ON t_out.id = tp_out.transfer_id WHERE tp_out.piece_id = ? AND t_out.id = ?";
        assertThat(jdbc.queryForObject(sql, Boolean.class, c, ev.transfer))
            .as("the batch piece left after the seed: not counted out").isFalse();
        assertThat(jdbc.queryForObject(sql, Boolean.class, a, ev.transfer))
            .as("control: the first-send piece is (its claim was pushed)").isTrue();
        jdbc.update("UPDATE shopify_inventory_adjustments SET created_at = now() - interval '3 hours' " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed'", tenant);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    record Ev(UUID id, UUID transfer) {}

    /** A live bazar whose first send holds {@code pieces}. {@code mode} null = Remove. */
    private Ev liveBazar(String name, String mode, List<String> pieces) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Topup " + name, LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 2), null, "Topup hall " + name, List.of("cash"), List.of("owner"), mode, null, null), owner);
        UUID id = (UUID) ev.get("id");
        for (String p : pieces) assertThat(bazar.scanOut(id, code(p), owner, false).success()).isTrue();
        bazar.markSent(id, owner);
        bazar.start(id, owner);
        return new Ev(id, (UUID) ev.get("transfer_id"));
    }

    private void closeToday(Ev ev, String key) {
        LocalDate today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        days.close(ev.id, today, new BazarDayService.Entered(new BigDecimal("1000"), null, null, null, BigDecimal.ZERO),
            null, "close-" + key, actor());
    }

    private BazarSaleService.Actor actor() { return new BazarSaleService.Actor(owner, "owner"); }

    private UUID newVariant(String name) {
        UUID v = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT product_id FROM variants WHERE id = ?", UUID.class, variant);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 300.00)", v, tenant, product, "gid://shopify/ProductVariant/" + v, name,
            "SKU-" + name, "gid://shopify/InventoryItem/" + v);
        return v;
    }

    /** A labelled piece received at Main the real way (its receipt's +1 applied). */
    private String piece(UUID v) { return piece(v, true); }

    /** {@code labelled} false: a count-only piece (no label code) — the only kind a count lists (U1c). */
    private String piece(UUID v, boolean labelled) {
        String id = String.format("01BAZARTOPUP%014d", seq.incrementAndGet());
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'applied')",
            tenant, UUID.randomUUID(), v, main, receipt.toString());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?, now() - interval '2 hours' + (? * interval '1 second'))",
            id, tenant, v, "BT-" + id, labelled ? "T" + (4000000 + seq.get()) : null, main, receipt, seq.get());
        return id;
    }

    private String code(String id) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    private List<String> transferAlertKeys(UUID transferId) {
        return exceptions.detectAllOpen().stream()
            .filter(e -> "void_hold_sync_failed".equals(e.get("type")) && "transfer_out".equals(e.get("trigger_type")))
            .map(e -> (String) e.get("subject_key"))
            .filter(k -> k.contains(transferId.toString())).toList();
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
        Thread.sleep(300);
    }
}
