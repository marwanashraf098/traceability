package com.traceability;

import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Registered for EVERY test (junit-platform.properties: extension autodetection, listed in
 * META-INF/services/org.junit.jupiter.api.extension.Extension). Since TenantContext.runAs
 * restores the previous tenant instead of clearing it (2026-10-04), nothing clears a tenant a
 * test set by hand any more — so this does, after each test, and puts the runAs tenant-switch
 * guard back to THROW (the test policy; a test may switch it to WARN for itself).
 */
public class TenantContextTestExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        TenantContext.setSwitchPolicy(TenantContext.SwitchPolicy.THROW);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantContext.clear();
        TenantContext.setSwitchPolicy(TenantContext.SwitchPolicy.THROW);
    }
}
