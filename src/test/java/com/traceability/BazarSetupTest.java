package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.Page;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.VariantStock;
import com.traceability.inventory.LabelService;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Bazar B9b — the guided bazar setup (BazarSetupService), BAZAR_SIGNUP_ENABLED=true. Whole app as app_user, real HTTP.
 *   g1 gate: a normal signup, a bazar signup with bazar off → 404 everywhere; worker 403; manager reads + Done only
 *   p1 products: the catalog with "N in your other locations" = Shopify total − Traced Main; no transaction open during
 *      the read; a failed read hides the counts
 *   r1 receive a mixed pick: one labelled and one count-only receipt through ReceivingService, pieces created by
 *      batchReceive (received events, nothing moved), the +N claimed at Traced Main only, the batch in prefs, one audit
 *   r2 the same request id again → the same receipts, nothing more received; another before Done → 409 LOWER_FIRST
 *   l1 the lower list and CSV: every variant + quantity, count-only rows exactly like labelled ones
 *   d1 Done: stamped once (one audit row), reminder gone; a new batch brings it back
 *   d2 bazarSetup can't be written through PATCH /onboarding/prefs
 *   i1 isolation: another tenant sees no batch and can't reach the first tenant's list
 *   c1 send-by-count on Get ready finds the count-only pieces
 *   o1 label order: a normal receiving session prints by line order then product / size / id, the same every time
 * (BazarSetupFlagOffTest runs with the setting off: 404 everywhere.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSetupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.catalog-backfill.enabled", () -> "false");
        r.add("traced.bazar.signup-enabled", () -> "true");   // BAZAR_SIGNUP_ENABLED=true
    }

    static final String PASSWORD = "pass1234";
    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @LocalServerPort int port;

    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway shopifyLocations;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean ShopifyVariantStockGateway stock;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;
    @MockBean JobScheduler jobs;

    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger seq = new AtomicInteger();
    JdbcTemplate owner;
    RestTemplate http;

    /** One bazar signup, connected and with a small catalog. */
    record Shop(UUID tenant, String token, UUID main, String mainGid, UUID store, UUID shirt, UUID shirtS, UUID shirtM,
                UUID scarf) {}

    @BeforeAll
    void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        http = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
    }

    @BeforeEach
    void mocks() {
        Mockito.reset(shopify, tokens, stock, jobs);
        when(tokens.getValidToken(any())).thenReturn("tok");
        when(shopify.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopify.inactiveMainLevels(any(), any(), any(), anyList())).thenReturn(Set.of());
        when(stock.topVariantsByStock(any(), any(), any(), any(), anyInt())).thenReturn(new Page(List.of(), false, null));
    }

    // ── g1 ───────────────────────────────────────────────────────────────────

    @Test
    void g1_gate_normalSignupAndBazarOff_404_workerForbidden_managerReadsOnly() throws Exception {
        String normalMail = mail("g1n");
        String normal = signup(normalMail, null);
        connect(tenantOf(normalMail));
        for (String[] e : endpoints()) {
            assertThat(send(HttpMethod.valueOf(e[0]), e[1], normal, body(e)).getStatusCode()).as("normal %s", e[1])
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        Shop s = shop("g1");
        owner.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", s.tenant());
        for (String[] e : endpoints()) {
            assertThat(send(HttpMethod.valueOf(e[0]), e[1], s.token(), body(e)).getStatusCode()).as("bazar off %s", e[1])
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
        owner.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", s.tenant());

        String worker = addUserAndLogin(s.tenant(), "worker", mail("g1w"));
        for (String[] e : endpoints()) {
            assertThat(send(HttpMethod.valueOf(e[0]), e[1], worker, body(e)).getStatusCode()).as("worker %s", e[1])
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
        String manager = addUserAndLogin(s.tenant(), "manager", mail("g1m"));
        Map<String, Object> st = json(send(HttpMethod.GET, "/api/v1/bazar-setup", manager, null), HttpStatus.OK);
        assertThat(st).containsEntry("on", true).containsEntry("canSetUp", false).containsEntry("batch", null);
        assertThat(send(HttpMethod.GET, "/api/v1/bazar-setup/products", manager, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, "/api/v1/bazar-setup/receive", manager, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK))
            .containsEntry("canSetUp", true);
        // GET /me says so only when on (the screens ask /bazar-setup only then); never for a worker or a normal store.
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", s.token(), null), HttpStatus.OK)).containsEntry("bazarSetup", true);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", manager, null), HttpStatus.OK)).containsEntry("bazarSetup", true);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", worker, null), HttpStatus.OK)).doesNotContainKey("bazarSetup");
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", normal, null), HttpStatus.OK)).doesNotContainKey("bazarSetup");
    }

    // ── p1 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void p1_products_otherLocations_noTransactionDuringRead_failedReadHides() throws Exception {
        Shop s = shop("p1");
        AtomicBoolean txDuringRead = new AtomicBoolean(false);
        AtomicReference<String> locationAsked = new AtomicReference<>();
        when(stock.topVariantsByStock(any(), any(), any(), any(), anyInt())).thenAnswer(inv -> {
            txDuringRead.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            locationAsked.set(inv.getArgument(2));
            return new Page(List.of(
                new VariantStock(gid(s.shirtM()), 9, 2),     // 9 in Shopify, 2 already at Traced Main → 7 elsewhere
                new VariantStock(gid(s.scarf()), 20, null),  // nothing at Traced Main → all 20 elsewhere
                new VariantStock(gid(s.shirtS()), 0, 0)), false, null);
        });
        Map<String, Object> out = json(send(HttpMethod.GET, "/api/v1/bazar-setup/products", s.token(), null), HttpStatus.OK);
        assertThat(txDuringRead.get()).as("no DB transaction open during the Shopify read").isFalse();
        assertThat(locationAsked.get()).isEqualTo(s.mainGid());
        assertThat(out.get("stockKnown")).isEqualTo(true);
        Map<String, Object> other = otherByVariant(out);
        assertThat(other).containsEntry(s.shirtM().toString(), 7).containsEntry(s.scarf().toString(), 20)
            .containsEntry(s.shirtS().toString(), 0);
        List<Map<String, Object>> products = (List<Map<String, Object>>) out.get("products");
        assertThat(products).extracting(p -> p.get("title")).containsExactly("Knit scarf", "Linen shirt");
        Map<String, Object> scarf = ((List<Map<String, Object>>) products.get(0).get("variants")).get(0);
        assertThat(scarf.get("title")).as("Default Title → null (one size)").isNull();
        assertThat(scarf).doesNotContainKey("externalId");

        // A failed read: the picker still works, the counts are hidden.
        when(stock.topVariantsByStock(any(), any(), any(), any(), anyInt()))
            .thenThrow(new ShopifyVariantStockGateway.VariantStockReadException("THROTTLED"));
        Map<String, Object> failed = json(send(HttpMethod.GET, "/api/v1/bazar-setup/products", s.token(), null), HttpStatus.OK);
        assertThat(failed.get("stockKnown")).isEqualTo(false);
        assertThat(otherByVariant(failed).values()).containsOnlyNulls();
        assertThat((List<?>) failed.get("products")).hasSize(2);
    }

    // ── r1 / r2 ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r1_mixedPick_twoReceiptsThroughReceiving_batchReceiveOnly_plusNAtMainOnly() throws Exception {
        Shop s = shop("r1");
        UUID event = event(s, "r1");
        Map<String, Object> st = receive(s, event, "req-r1", List.of(
            line(s.shirtS(), 2, true), line(s.shirtM(), 1, true), line(s.scarf(), 3, false)), HttpStatus.OK);
        Map<String, Object> batch = (Map<String, Object>) st.get("batch");
        assertThat(batch).containsEntry("eventId", event.toString()).containsEntry("pieces", 6)
            .containsEntry("lowered", false).containsEntry("eventStatus", "draft");
        UUID labelled = UUID.fromString((String) batch.get("labelledReceiptId"));
        UUID countOnly = UUID.fromString((String) batch.get("countOnlyReceiptId"));

        assertThat(owner.queryForMap("SELECT mode, status, location_id FROM receipts WHERE id = ?", labelled))
            .containsEntry("mode", "print_stick").containsEntry("status", "finalized").containsEntry("location_id", s.main());
        assertThat(owner.queryForMap("SELECT mode, status, location_id FROM receipts WHERE id = ?", countOnly))
            .containsEntry("mode", "count_only").containsEntry("status", "finalized").containsEntry("location_id", s.main());
        // Pieces: created by batchReceive — available at Traced Main, one 'received' event each, nothing else.
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND short_code IS NOT NULL", labelled)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND short_code IS NULL", countOnly)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND (status <> 'available' OR current_location_id <> ?)",
            s.tenant(), s.main())).isZero();
        assertThat(owner.queryForList("SELECT DISTINCT e.event_type::text FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.tenant_id = ?", String.class, s.tenant())).containsExactly("received");
        assertThat(count("SELECT count(*) FROM piece_events WHERE tenant_id = ?", s.tenant())).isEqualTo(6);
        // The batch: in prefs, one audit row.
        Map<String, Object> prefs = mapper.readValue(owner.queryForObject(
            "SELECT onboarding_prefs->>'bazarSetup' FROM tenants WHERE id = ?", String.class, s.tenant()), Map.class);
        assertThat(prefs).containsEntry("requestId", "req-r1").containsEntry("loweredAt", null)
            .containsEntry("labelledReceiptId", labelled.toString()).containsEntry("countOnlyReceiptId", countOnly.toString());
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_setup_received'", s.tenant())).isEqualTo(1);
        // Shopify: the existing receiving +N, claimed per variant at Traced Main; no other location ever named.
        await("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'receiving_session' " +
            "AND trigger_id IN (?, ?)", 3, s.tenant(), labelled.toString(), countOnly.toString());
        assertThat(owner.queryForList("SELECT variant_id::text || ':' || delta FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session'", String.class, s.tenant()))
            .containsExactlyInAnyOrder(s.shirtS() + ":2", s.shirtM() + ":1", s.scarf() + ":3");
        assertThat(count("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND location_id <> ?",
            s.tenant(), s.main())).isZero();
        for (Invocation inv : Mockito.mockingDetails(shopify).getInvocations()) {
            String args = Arrays.deepToString(inv.getArguments());
            if (!args.contains("gid://shopify/Location/")) continue;
            assertThat(args.replace(s.mainGid(), "")).as("%s names only Traced Main", inv.getMethod().getName())
                .doesNotContain("gid://shopify/Location/");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void r2_sameRequestId_sameReceipts_noMore_anotherBeforeDone_409() throws Exception {
        Shop s = shop("r2");
        UUID event = event(s, "r2");
        Map<String, Object> first = (Map<String, Object>) receive(s, event, "req-r2",
            List.of(line(s.shirtS(), 2, true), line(s.scarf(), 1, false)), HttpStatus.OK).get("batch");
        Map<String, Object> again = (Map<String, Object>) receive(s, event, "req-r2",
            List.of(line(s.shirtS(), 2, true), line(s.scarf(), 1, false)), HttpStatus.OK).get("batch");
        assertThat(again.get("labelledReceiptId")).isEqualTo(first.get("labelledReceiptId"));
        assertThat(again.get("countOnlyReceiptId")).isEqualTo(first.get("countOnlyReceiptId"));
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", s.tenant())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM receipts WHERE tenant_id = ?", s.tenant())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_setup_received'", s.tenant())).isEqualTo(1);

        ResponseEntity<String> other = send(HttpMethod.POST, "/api/v1/bazar-setup/receive", s.token(),
            Map.of("eventId", event, "requestId", "req-r2-b", "lines", List.of(line(s.shirtM(), 1, true))));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody()).contains("BAZAR_SETUP_LOWER_FIRST");
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", s.tenant())).isEqualTo(3);
        // Nothing picked → 422 and nothing received.
        owner.update("UPDATE tenants SET onboarding_prefs = onboarding_prefs - 'bazarSetup' WHERE id = ?", s.tenant());
        ResponseEntity<String> empty = send(HttpMethod.POST, "/api/v1/bazar-setup/receive", s.token(),
            Map.of("eventId", event, "requestId", "req-r2-c", "lines", List.of()));
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(empty.getBody()).contains("BAZAR_SETUP_NOTHING_PICKED");
        assertThat(count("SELECT count(*) FROM receipts WHERE tenant_id = ?", s.tenant())).isEqualTo(2);
    }

    // ── l1 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_lowerList_andCsv_everyVariant_countOnlyLikeLabelled() throws Exception {
        Shop s = shop("l1");
        UUID event = event(s, "l1");
        receive(s, event, "req-l1", List.of(line(s.shirtS(), 2, true), line(s.shirtM(), 4, false),
            line(s.scarf(), 3, false)), HttpStatus.OK);
        Map<String, Object> list = json(send(HttpMethod.GET, "/api/v1/bazar-setup/lower", s.token(), null), HttpStatus.OK);
        assertThat(list).containsEntry("total", 9).containsEntry("lowered", false);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("rows");
        assertThat(rows).extracting(r -> r.get("productTitle") + "|" + r.get("variantTitle") + "|" + r.get("quantity"))
            .containsExactly("Knit scarf|null|3", "Linen shirt|S|2", "Linen shirt|M|4");
        assertThat(rows).allSatisfy(r -> assertThat(r.keySet())
            .containsExactly("variantId", "productTitle", "variantTitle", "sku", "quantity"));

        ResponseEntity<byte[]> csv = http.exchange(base() + "/api/v1/bazar-setup/lower.csv", HttpMethod.GET,
            new HttpEntity<>(auth(s.token())), byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        assertThat(text.substring(1).split("\r\n")).containsExactly(
            "Product,Size,SKU,Quantity",
            "\"Knit scarf\",\"One size\",\"SCARF-l1\",3",
            "\"Linen shirt\",\"S\",\"SHIRT-S-l1\",2",
            "\"Linen shirt\",\"M\",\"SHIRT-M-l1\",4");
        String ar = new String(http.exchange(base() + "/api/v1/bazar-setup/lower.csv?lang=ar", HttpMethod.GET,
            new HttpEntity<>(auth(s.token())), byte[].class).getBody(), StandardCharsets.UTF_8);
        assertThat(ar).contains("المنتج,المقاس,SKU,الكمية").contains("\"مقاس واحد\"");
    }

    @Test
    void l2_csvCell_defusesFormulas() {
        assertThat(com.traceability.bazar.BazarSetupService.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(com.traceability.bazar.BazarSetupService.csvCell("Linen, white")).isEqualTo("\"Linen, white\"");
        assertThat(com.traceability.bazar.BazarSetupService.csvCell(null)).isEqualTo("");
    }

    // ── d1 / d2 ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void d1_done_stampsOnce_reminderGone_newBatchBringsItBack() throws Exception {
        Shop s = shop("d1");
        UUID event = event(s, "d1");
        receive(s, event, "req-d1", List.of(line(s.shirtS(), 1, true)), HttpStatus.OK);
        String manager = addUserAndLogin(s.tenant(), "manager", mail("d1m"));
        Map<String, Object> done = json(send(HttpMethod.POST, "/api/v1/bazar-setup/lower-done", manager, null), HttpStatus.OK);
        Map<String, Object> batch = (Map<String, Object>) done.get("batch");
        assertThat(batch).containsEntry("lowered", true);
        assertThat(batch.get("loweredAt")).isNotNull();
        UUID managerId = owner.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'manager'", UUID.class, s.tenant());
        assertThat(owner.queryForObject("SELECT onboarding_prefs->'bazarSetup'->>'loweredBy' FROM tenants WHERE id = ?",
            String.class, s.tenant())).isEqualTo(managerId.toString());
        json(send(HttpMethod.POST, "/api/v1/bazar-setup/lower-done", s.token(), null), HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_setup_lowered'", s.tenant()))
            .as("Done again is a no-op").isEqualTo(1);
        assertThat(((Map<String, Object>) json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK)
            .get("batch")).get("lowered")).isEqualTo(true);

        UUID event2 = event(s, "d1b");
        Map<String, Object> next = (Map<String, Object>) receive(s, event2, "req-d1-b",
            List.of(line(s.scarf(), 2, false)), HttpStatus.OK).get("batch");
        assertThat(next).containsEntry("lowered", false).containsEntry("eventId", event2.toString()).containsEntry("pieces", 2);
    }

    @Test
    void d2_bazarSetup_isNotAPatchKey() throws Exception {
        Shop s = shop("d2");
        ResponseEntity<String> r = send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", s.token(),
            Map.of("bazarSetup", Map.of("loweredAt", "2026-11-22T00:00:00Z")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.queryForObject("SELECT onboarding_prefs->'bazarSetup' IS NOT NULL FROM tenants WHERE id = ?", Boolean.class, s.tenant()))
            .isFalse();
        ResponseEntity<String> noList = send(HttpMethod.POST, "/api/v1/bazar-setup/lower-done", s.token(), null);
        assertThat(noList.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noList.getBody()).contains("BAZAR_SETUP_NO_LIST");
    }

    // ── i1 ───────────────────────────────────────────────────────────────────

    @Test
    void i1_anotherTenant_seesNoBatch_andCantUseTheFirstTenantsEvent() throws Exception {
        Shop a = shop("i1a");
        Shop b = shop("i1b");
        UUID eventA = event(a, "i1");
        receive(a, eventA, "req-i1", List.of(line(a.shirtS(), 2, true)), HttpStatus.OK);
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", b.token(), null), HttpStatus.OK)).containsEntry("batch", null);
        assertThat(send(HttpMethod.GET, "/api/v1/bazar-setup/lower", b.token(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // B can't receive into A's event, nor A's variants.
        ResponseEntity<String> r = send(HttpMethod.POST, "/api/v1/bazar-setup/receive", b.token(),
            Map.of("eventId", eventA, "requestId", "req-i1-b", "lines", List.of(line(b.shirtS(), 1, true))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID eventB = event(b, "i1b");
        ResponseEntity<String> r2 = send(HttpMethod.POST, "/api/v1/bazar-setup/receive", b.token(),
            Map.of("eventId", eventB, "requestId", "req-i1-c", "lines", List.of(line(a.shirtS(), 1, true))));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r2.getBody()).contains("BAZAR_VARIANT_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", b.tenant())).isZero();
    }

    // ── c1 ───────────────────────────────────────────────────────────────────

    @Test
    void c1_sendByCount_findsCountOnlyPieces() throws Exception {
        Shop s = shop("c1");
        UUID event = event(s, "c1");
        receive(s, event, "req-c1", List.of(line(s.scarf(), 3, false), line(s.shirtS(), 1, true)), HttpStatus.OK);
        Map<String, Object> r = json(send(HttpMethod.POST, "/api/v1/bazar-events/" + event + "/scan-out-by-count", s.token(),
            Map.of("variant_id", s.scarf(), "quantity", 3, "client_request_id", "c1-send")), HttpStatus.OK);
        assertThat(r.get("success")).as("%s", r).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND variant_id = ? AND short_code IS NULL " +
            "AND status = 'out_on_transfer'", s.tenant(), s.scarf())).isEqualTo(3);
    }

    // ── o1 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void o1_labelOrder_normalReceiving_byLineOrder_thenProductSizeId_stable() throws Exception {
        Shop s = shop("o1");
        // A normal receiving session: scarf, then shirt M, then shirt S — separate requests; line order wins over size.
        Map<String, Object> session = json(send(HttpMethod.POST, "/api/v1/receiving/sessions", s.token(),
            Map.of("locationId", s.main(), "reference", "o1")), HttpStatus.CREATED, HttpStatus.OK);
        String sid = String.valueOf(session.get("sessionId"));
        for (Object[] l : List.of(new Object[]{s.scarf(), 3}, new Object[]{s.shirtM(), 2}, new Object[]{s.shirtS(), 2})) {
            ResponseEntity<String> a = send(HttpMethod.POST, "/api/v1/receiving/sessions/" + sid + "/lines", s.token(),
                Map.of("variantId", l[0], "quantity", l[1]));
            assertThat(a.getStatusCode().is2xxSuccessful()).as(a.getBody()).isTrue();
            Thread.sleep(5);   // the lines' created_at differ, as they do between two taps
        }
        ResponseEntity<String> fin = send(HttpMethod.POST, "/api/v1/receiving/sessions/" + sid + "/finalize", s.token(), null);
        assertThat(fin.getStatusCode().is2xxSuccessful()).as(fin.getBody()).isTrue();
        assertThat(labelOrder(UUID.fromString(sid))).extracting(r -> r.split("#")[0])
            .containsExactly("Knit scarf", "Knit scarf", "Knit scarf", "Linen shirt M", "Linen shirt M", "Linen shirt S", "Linen shirt S");
        assertThat(labelOrder(UUID.fromString(sid))).as("the same every print").isEqualTo(labelOrder(UUID.fromString(sid)));
        List<String> ids = labelOrder(UUID.fromString(sid)).stream().map(r -> r.split("#")[1]).toList();
        assertThat(ids.subList(0, 3)).isSorted();
        assertThat(send(HttpMethod.GET, "/api/v1/receiving/sessions/" + sid + "/labels", s.token(), null).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        // The wizard's receipt (every line added in one transaction): product, then Shopify's size order.
        UUID event = event(s, "o1");
        Map<String, Object> batch = (Map<String, Object>) receive(s, event, "req-o1",
            List.of(line(s.shirtS(), 1, true), line(s.scarf(), 1, true), line(s.shirtM(), 1, true)), HttpStatus.OK).get("batch");
        assertThat(labelOrder(UUID.fromString((String) batch.get("labelledReceiptId")))).extracting(r -> r.split("#")[0])
            .containsExactly("Knit scarf", "Linen shirt S", "Linen shirt M");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Pieces of a receipt in the label order LabelService prints them: "product size#pieceId". */
    private List<String> labelOrder(UUID receipt) {
        return owner.queryForList(
            "SELECT pr.title || CASE WHEN v.title = 'Default Title' THEN '' ELSE ' ' || v.title END || '#' || p.id " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? " + LabelService.LABEL_ORDER_SQL, String.class, receipt);
    }

    private Shop shop(String tag) throws Exception {
        String mail = mail(tag);
        String token = signup(mail, "bazar");
        UUID tenant = tenantOf(mail);
        assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).isTrue();
        UUID store = connect(tenant);
        UUID main = owner.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenant);
        String mainGid = "gid://shopify/Location/" + (70_000_000L + seq.incrementAndGet());
        owner.update("UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked' WHERE id = ?", mainGid, main);
        UUID shirt = product(tenant, store, "Linen shirt");
        UUID scarf = product(tenant, store, "Knit scarf");
        UUID shirtS = variant(tenant, shirt, "S", "SHIRT-S-" + tag);
        UUID shirtM = variant(tenant, shirt, "M", "SHIRT-M-" + tag);
        // Shopify's size order (S before M) — alphabetical would put M first.
        owner.update("UPDATE variants SET raw = jsonb_build_object('position', 1) WHERE id = ?", shirtS);
        owner.update("UPDATE variants SET raw = jsonb_build_object('position', 2) WHERE id = ?", shirtM);
        UUID scarfV = variant(tenant, scarf, "Default Title", "SCARF-" + tag);
        return new Shop(tenant, token, main, mainGid, store, shirt, shirtS, shirtM, scarfV);
    }

    private UUID product(UUID tenant, UUID store, String title) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            id, tenant, store, "gid://shopify/Product/" + id, title);
        return id;
    }

    private UUID variant(UUID tenant, UUID product, String title, String sku) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00)", id, tenant, product, gid(id), title, sku, "gid://shopify/InventoryItem/" + id);
        return id;
    }

    private static String gid(UUID variant) { return "gid://shopify/ProductVariant/" + variant; }

    private UUID event(Shop s, String tag) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("startsOn", "2026-12-01");
        body.put("endsOn", "2026-12-03");
        body.put("locationName", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("paymentMethods", List.of("cash"));
        body.put("sellerRoles", List.of("owner"));
        Map<String, Object> r = json(send(HttpMethod.POST, "/api/v1/bazar-events", s.token(), body), HttpStatus.CREATED);
        return UUID.fromString((String) r.get("id"));
    }

    private Map<String, Object> receive(Shop s, UUID event, String requestId, List<Map<String, Object>> lines,
                                        HttpStatus expect) throws Exception {
        return json(send(HttpMethod.POST, "/api/v1/bazar-setup/receive", s.token(),
            Map.of("eventId", event, "requestId", requestId, "lines", lines)), expect);
    }

    private static Map<String, Object> line(UUID variant, int qty, boolean labels) {
        return Map.of("variantId", variant, "quantity", qty, "labels", labels);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> otherByVariant(Map<String, Object> out) {
        Map<String, Object> m = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) out.get("products")) {
            for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) m.put((String) v.get("id"), v.get("otherLocations"));
        }
        return m;
    }

    private static List<String[]> endpoints() {
        return List.of(new String[]{"GET", "/api/v1/bazar-setup"}, new String[]{"GET", "/api/v1/bazar-setup/products"},
            new String[]{"POST", "/api/v1/bazar-setup/receive"}, new String[]{"GET", "/api/v1/bazar-setup/lower"},
            new String[]{"GET", "/api/v1/bazar-setup/lower.csv"}, new String[]{"POST", "/api/v1/bazar-setup/lower-done"});
    }

    private static Object body(String[] e) {
        return "POST".equals(e[0]) ? Map.of("requestId", "x", "lines", List.of()) : null;
    }

    private String base() { return "http://localhost:" + port; }

    private static String mail(String tag) { return "b9b-" + tag + "-" + System.nanoTime() + "@b9b.test"; }

    @SuppressWarnings("unchecked")
    private String signup(String mail, String intent) {
        Map<String, Object> f = new HashMap<>();
        f.put("tenantName", "Nour Studio");
        f.put("name", "Nour Adel");
        f.put("email", mail);
        f.put("phone", "+201012345678");
        f.put("password", PASSWORD);
        f.put("consent", true);
        if (intent != null) f.put("intent", intent);
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(f, jsonHeaders()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("accessToken");
    }

    private UUID tenantOf(String mail) {
        return owner.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, mail);
    }

    private UUID connect(UUID tenant) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())",
            id, tenant, "b9b-" + seq.incrementAndGet() + "-" + System.nanoTime() + ".myshopify.com", SCOPES);
        return id;
    }

    @SuppressWarnings("unchecked")
    private String login(String mail) {
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mail, "password", PASSWORD), jsonHeaders()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private String addUserAndLogin(UUID tenant, String role, String mail) {
        String hash = owner.queryForObject("SELECT password_hash FROM users WHERE tenant_id = ? AND role = 'owner'", String.class, tenant);
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (gen_random_uuid(), ?, 'M', ?, ?, ?::user_role)",
            tenant, mail, hash, role);
        return login(mail);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ResponseEntity<String> r, HttpStatus... expect) throws Exception {
        assertThat(expect).as(r.getBody()).contains((HttpStatus) HttpStatus.valueOf(r.getStatusCode().value()));
        return mapper.readValue(r.getBody(), Map.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        return http.exchange(base() + path, method, new HttpEntity<>(body, h), String.class);
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private int count(String sql, Object... args) { return owner.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
    }
}
