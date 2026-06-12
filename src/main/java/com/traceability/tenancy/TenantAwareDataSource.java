package com.traceability.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the application DataSource so every connection is intercepted by
 * {@link TenantAwareConnection}, which fires SET LOCAL at transaction start.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return TenantAwareConnection.wrap(getTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return TenantAwareConnection.wrap(
                getTargetDataSource().getConnection(username, password));
    }
}
