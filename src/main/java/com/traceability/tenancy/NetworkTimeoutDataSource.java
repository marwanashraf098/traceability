package com.traceability.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;

/**
 * Hands out the target's connections with {@link Connection#setNetworkTimeout} set — in pgjdbc that is the
 * socket read timeout for this connection only. Used for JobRunr's storage (JobRunrConfig) so no JobRunr DB
 * call can block forever on a dead socket. HikariCP records the change and puts the pool's own timeout back
 * when the connection is returned, so other users of the same pool (Flyway, owner jobs) are unaffected.
 */
public final class NetworkTimeoutDataSource extends DelegatingDataSource {

    private static final Executor DIRECT = Runnable::run;   // pgjdbc doesn't use it; must not be null
    private final int timeoutMs;

    public NetworkTimeoutDataSource(DataSource target, int timeoutMs) {
        super(target);
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTimeout(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTimeout(super.getConnection(username, password));
    }

    private Connection withTimeout(Connection c) throws SQLException {
        try {
            c.setNetworkTimeout(DIRECT, timeoutMs);
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }
}
