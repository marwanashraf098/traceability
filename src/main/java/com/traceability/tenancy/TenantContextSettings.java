package com.traceability.tenancy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies {@code tenancy.runas-switch-guard} (warn | throw; default warn) to
 * {@link TenantContext#setSwitchPolicy} at startup. Tests run with throw
 * (src/test/resources/application.properties and TenantContextTestExtension).
 */
@Component
public class TenantContextSettings {

    public TenantContextSettings(@Value("${tenancy.runas-switch-guard:warn}") String guard) {
        TenantContext.setSwitchPolicy("throw".equalsIgnoreCase(guard.trim())
            ? TenantContext.SwitchPolicy.THROW : TenantContext.SwitchPolicy.WARN);
    }
}
