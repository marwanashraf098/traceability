package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.Flyway;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V169 — the named non-Bosta carriers have one SQL definition (other_known_carriers(), used by
 * shipping_carrier_of) and one Java mirror (FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS). They must match.
 */
@Testcontainers
class GenericCarrierListParityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("traceability_test")
                .withUsername("postgres").withPassword("postgres");

    @Test
    void javaAndSqlListsMatch_andClassifyTheSame() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));

        java.util.List<String> sql = jdbc.queryForList("SELECT unnest(other_known_carriers())", String.class);
        assertThat(sql).doesNotHaveDuplicates();
        assertThat(new HashSet<>(sql)).isEqualTo(FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS);

        for (String name : Set.of("Mylerz", " J&T Express ", "WIJHA", "Aramex", "Other", "QP Express", "DHL Express")) {
            String expected = FulfillmentTrackingCapture.carrierClass(name, null);
            String fromSql = jdbc.queryForObject(
                "SELECT CASE WHEN lower(trim(?)) = ANY (other_known_carriers()) THEN 'other_known' ELSE 'unknown' END",
                String.class, name);
            assertThat(fromSql).as(name).isEqualTo(expected);
        }
    }
}
