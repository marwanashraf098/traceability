package com.traceability.demo;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Owner-authed manual trigger for the demo tenant's reseed — runs the exact same
 * ensureBootstrapped() + reseed() sequence DemoReseedJob's every-30-minutes cron
 * tick runs, on demand, so a fixture change is verifiable immediately instead of
 * waiting up to 30 minutes (that cron has been unreliable throughout this build).
 *
 * SAFETY (the reason this can be "any owner", not just the demo owner): neither
 * ensureBootstrapped() nor reseed() takes a tenant id parameter, and neither reads
 * TenantContext — reseed()'s resolveAndAssertDemoTenant() resolves the target
 * SOLELY via `SELECT id FROM tenants WHERE is_demo = true`, then hard-asserts
 * exactly one row, not a forbidden pilot id, and that it equals the fixed
 * DEMO_TENANT_ID constant (DemoSeeder.java). A real owner's own ambient
 * TenantContext (set by TenantContextFilter from their JWT) is never consulted by
 * either method, so calling this endpoint as a real owner reseeds the DEMO
 * tenant — never the caller's own tenant. There is no code path here, or in
 * DemoSeeder, through which a real tenant's data could be touched. @PreAuthorize
 * only needs to establish "an authenticated owner", not "the demo owner
 * specifically" — the safety comes from DemoSeeder's own assertion, not from a
 * check in this controller.
 */
@RestController
@RequestMapping("/api/v1/demo")
@PreAuthorize("hasRole('OWNER')")
public class DemoAdminController {

    private final DemoSeeder demoSeeder;

    public DemoAdminController(DemoSeeder demoSeeder) {
        this.demoSeeder = demoSeeder;
    }

    /**
     * Synchronous, not enqueued — the fixture is small (a single tenant, low
     * hundreds of rows) and reseed() completes in well under a second in
     * practice (see DemoSeederTest's back-to-back reseed() calls). Returning
     * {reseeded: true} only once the reseed has actually happened, on the same
     * request, is what makes the result "verifiable immediately" — an async/
     * enqueued version would have to return before the reseed ran, making that
     * claim false at response time.
     */
    @PostMapping("/reseed")
    public Map<String, Object> reseed() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();
        return Map.of("reseeded", true, "tenantId", DemoSeeder.DEMO_TENANT_ID.toString());
    }
}
