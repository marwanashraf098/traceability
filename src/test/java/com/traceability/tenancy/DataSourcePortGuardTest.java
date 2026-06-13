package com.traceability.tenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the transaction-pooler port guard in DataSourceConfig.
 * No Spring context or Testcontainers — pure logic test.
 */
class DataSourcePortGuardTest {

    @Test
    void port6543_transactionPooler_throws() {
        assertThatThrownBy(() ->
            DataSourceConfig.rejectTransactionPooler(
                "jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:6543/postgres"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("transaction-mode pooler")
            .hasMessageContaining("6543");
    }

    @Test
    void port5432_sessionPooler_ok() {
        assertThatNoException().isThrownBy(() ->
            DataSourceConfig.rejectTransactionPooler(
                "jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:5432/postgres"));
    }

    @Test
    void port5432_directHost_ok() {
        assertThatNoException().isThrownBy(() ->
            DataSourceConfig.rejectTransactionPooler(
                "jdbc:postgresql://db.jtkzpjaangjtkrepkqdz.supabase.co:5432/postgres"));
    }

    @Test
    void noPort_defaultsTo5432_ok() {
        assertThatNoException().isThrownBy(() ->
            DataSourceConfig.rejectTransactionPooler(
                "jdbc:postgresql://localhost/traceability"));
    }
}
