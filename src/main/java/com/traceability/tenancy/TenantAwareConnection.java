package com.traceability.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Returns a {@link java.lang.reflect.Proxy} that wraps a real JDBC connection and
 * fires {@code SELECT set_config('app.current_tenant', ?, true)} inside every
 * new transaction.
 *
 * Ordering (see design doc):
 *   1. Call the real connection's {@code setAutoCommit(false)} FIRST — pgJDBC
 *      enters manual-commit mode (actual BEGIN is deferred to the first statement).
 *   2. Issue the set_config statement — pgJDBC sends BEGIN + this statement
 *      together, so the GUC lands inside the transaction.
 *
 * The guard {@code target.getAutoCommit()} ensures the GUC fires only on a
 * true→false transition, never mid-transaction or on setAutoCommit(true).
 */
final class TenantAwareConnection implements InvocationHandler {

    private final Connection target;

    private TenantAwareConnection(Connection target) {
        this.target = target;
    }

    static Connection wrap(Connection conn) {
        return (Connection) Proxy.newProxyInstance(
                TenantAwareConnection.class.getClassLoader(),
                new Class[]{Connection.class},
                new TenantAwareConnection(conn));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("setAutoCommit".equals(method.getName())
                && args != null && args.length == 1
                && args[0] instanceof Boolean autoCommit
                && !autoCommit
                && target.getAutoCommit()) {

            // Step 1: begin the transaction.
            target.setAutoCommit(false);

            // Step 2: fire GUC inside the now-open transaction.
            UUID tenantId = TenantContext.get();
            if (tenantId != null) {
                try (PreparedStatement ps = target.prepareStatement(
                        "SELECT set_config('app.current_tenant', ?, true)")) {
                    ps.setString(1, tenantId.toString());
                    ps.execute();
                }
            }
            return null;
        }

        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) throw sql;
            throw cause;
        }
    }
}
