package com.traceability;

import com.traceability.integrations.bosta.ShipmentSettlement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 3 — V148's backfill of the settlement columns from the payloads already held.
 *
 * Flyway to V147, seed shipments with the real payload shapes, then migrate the rest so V148's
 * UPDATE runs on pre-existing rows. Every backfilled row must equal what ShipmentSettlement.extract()
 * (the Java writer) produces from the same payload — one definition, two implementations.
 */
@Testcontainers
class SettlementBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void backfill_matchesTheJavaExtractor_andSetsStatus() throws Exception {
        assertThat(Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("147").load().migrate().success).isTrue();

        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        Map<String, String> payloads = new LinkedHashMap<>();
        payloads.put("paid", SettlementPayloads.DELIVERED_PAID);
        payloads.put("rto", SettlementPayloads.RTO_DEPOSITED);
        payloads.put("crp", SettlementPayloads.CRP_PAID_WITH_BATCH);
        payloads.put("exchange", SettlementPayloads.EXCHANGE_PROMO);
        payloads.put("v2", SettlementPayloads.V2_ITEM_NULL_WALLET);
        payloads.put("unsettled", SettlementPayloads.DELIVERED_UNSETTLED);
        payloads.put("garbage", "{\"shipmentFees\":\"x\",\"wallet\":{\"cashCycle\":{\"deposited_at\":\"nope\"," +
                                "\"deposited_amt\":\"1.5\"},\"cashout\":{\"transaction_id\":\"WEDCOD31FEB26\"," +
                                "\"next_cashout_date\":\"soon\"}}}");
        payloads.put("noWallet", "{\"state\":{\"code\":45}}");
        Map<String, UUID> ids = new LinkedHashMap<>();

        try (Connection c = conn()) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'SettleBackfill')", tenant);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'settle-bf.myshopify.com', 'disconnected')", store, tenant);
            int n = 0;
            for (Map.Entry<String, String> e : payloads.entrySet()) {
                UUID order = UUID.randomUUID(), ship = UUID.randomUUID();
                exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, now())", order, tenant, store, "EXT-" + order, "#BF" + n);
                exec(c, "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, raw) " +
                        "VALUES (?, ?, ?, ?, 'delivered', ?::jsonb)", ship, tenant, order, "55500" + (n++), e.getValue());
                ids.put(e.getKey(), ship);
            }
            // A row with no raw at all.
            UUID order = UUID.randomUUID(), ship = UUID.randomUUID();
            exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at) " +
                    "VALUES (?, ?, ?, ?, '#BFnull', 'new'::order_status, now())", order, tenant, store, "EXT-" + order);
            exec(c, "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
                    "VALUES (?, ?, ?, '5550099', 'created')", ship, tenant, order);
            ids.put("noRaw", ship);
        }

        assertThat(Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate().success).isTrue();

        try (Connection c = conn()) {
            assertThat(status(c, ids.get("paid"))).isEqualTo("paid");
            assertThat(status(c, ids.get("rto"))).isEqualTo("deposited");
            assertThat(status(c, ids.get("crp"))).isEqualTo("paid");
            assertThat(status(c, ids.get("exchange"))).isEqualTo("deposited");
            assertThat(status(c, ids.get("v2"))).isEqualTo("none");
            assertThat(status(c, ids.get("unsettled"))).isEqualTo("none");
            assertThat(status(c, ids.get("garbage"))).as("a txn id is a payout, even when its date won't parse")
                    .isEqualTo("paid");
            assertThat(status(c, ids.get("noWallet"))).isEqualTo("none");
            assertThat(status(c, ids.get("noRaw"))).isEqualTo("none");

            Map<String, Object> paid = row(c, ids.get("paid"));
            assertThat((BigDecimal) paid.get("deposited_amt")).isEqualByComparingTo("885.12");
            assertThat(paid.get("cashout_date").toString()).isEqualTo("2026-09-09");
            Map<String, Object> rto = row(c, ids.get("rto"));
            assertThat((BigDecimal) rto.get("deposited_amt")).isEqualByComparingTo("-77.52");
            assertThat(rto.get("next_cashout_date").toString()).isEqualTo("2026-09-09");
            Map<String, Object> crp = row(c, ids.get("crp"));
            assertThat((BigDecimal) crp.get("cashout_amount")).isEqualByComparingTo("67854.59");
            assertThat(crp.get("cashout_date").toString()).isEqualTo("2026-08-24");
            assertThat(row(c, ids.get("noRaw")).get("settlement_refreshed_at")).isNull();

            // Parity: SQL backfill == Java extractor, field by field.
            for (Map.Entry<String, String> e : payloads.entrySet()) {
                ShipmentSettlement.Fields f = ShipmentSettlement.extract(ShipmentSettlementTest.json(e.getValue()));
                Map<String, Object> r = row(c, ids.get(e.getKey()));
                String k = e.getKey();
                assertThat(r.get("deposited_at") == null ? null : ((java.sql.Timestamp) r.get("deposited_at")).toInstant())
                        .as(k + " deposited_at").isEqualTo(f.depositedAt());
                eq(k, "deposited_amt", r, f.depositedAmt());
                eq(k, "cod_settled", r, f.codSettled());
                eq(k, "bosta_fees", r, f.bostaFees());
                eq(k, "shipping_fees", r, f.shippingFees());
                eq(k, "vat", r, f.vat());
                eq(k, "opening_package_fees", r, f.openingPackageFees());
                eq(k, "collection_fees", r, f.collectionFees());
                eq(k, "insurance_fees", r, f.insuranceFees());
                eq(k, "flex_ship_fees", r, f.flexShipFees());
                eq(k, "promotion_discount", r, f.promotionDiscount());
                eq(k, "shipment_fees_quoted", r, f.shipmentFeesQuoted());
                eq(k, "cashout_amount", r, f.cashoutAmount());
                assertThat(r.get("cash_cycle_id")).as(k + " cash_cycle_id").isEqualTo(f.cashCycleId());
                assertThat(r.get("cashout_txn_id")).as(k + " cashout_txn_id").isEqualTo(f.cashoutTxnId());
                assertThat(date(r.get("cashout_date"))).as(k + " cashout_date").isEqualTo(f.cashoutDate());
                assertThat(date(r.get("next_cashout_date"))).as(k + " next_cashout_date").isEqualTo(f.nextCashoutDate());
                assertThat(r.get("settlement_status")).as(k + " status").isEqualTo(f.provenStatus());
            }
        }
    }

    private static void eq(String k, String col, Map<String, Object> r, BigDecimal expected) {
        BigDecimal actual = (BigDecimal) r.get(col);
        if (expected == null) assertThat(actual).as(k + " " + col).isNull();
        else assertThat(actual).as(k + " " + col).isEqualByComparingTo(expected);
    }

    private static LocalDate date(Object o) {
        return o == null ? null : ((java.sql.Date) o).toLocalDate();
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String status(Connection c, UUID id) throws Exception {
        return (String) row(c, id).get("settlement_status");
    }

    private static Map<String, Object> row(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM shipments WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    m.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return m;
            }
        }
    }

    private static void exec(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.execute();
        }
    }
}
