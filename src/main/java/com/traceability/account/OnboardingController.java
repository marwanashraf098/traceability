package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * FR-1.2: Onboarding checklist state.
 *
 * All 5 steps are DERIVED from real table signals — not a manually-toggled flag.
 *
 * Signal map:
 *   ① Connect Shopify  → stores.status = 'connected'
 *   ② Connect Bosta    → courier_accounts.status = 'active'
 *   ③ Initial import   → stores.import_status = 'completed'
 *   ④ Test label       → label_reprints (any row for tenant)
 *                        Note: initial label print at finalization is not separately tracked;
 *                        label_reprints tracks explicit reprints. Steps ④ and ⑤ often
 *                        complete simultaneously since the first reprint follows the first session.
 *   ⑤ First receiving  → receipts.finalized_at IS NOT NULL (any finalized session)
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;

    public OnboardingController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    /**
     * GET /api/v1/onboarding/status
     *
     * Returns each of the 5 checklist steps with status "done" or "pending",
     * derived from real signals.
     *
     * Response shape:
     * {
     *   "steps": [
     *     { "key": "connect_shopify",  "label": "Connect Shopify",       "status": "done"|"pending" },
     *     { "key": "connect_bosta",    "label": "Connect Bosta",         "status": "done"|"pending" },
     *     { "key": "initial_import",   "label": "Run product import",    "status": "done"|"pending" },
     *     { "key": "test_label",       "label": "Print a test label",    "status": "done"|"pending", "signal": "label_reprints" },
     *     { "key": "first_receiving",  "label": "Complete first receiving session", "status": "done"|"pending" }
     *   ],
     *   "allDone": bool
     * }
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> status(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();

        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {

            // ① Shopify connected
            boolean shopifyDone = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM stores WHERE tenant_id = ? AND status = 'connected')",
                Boolean.class, tenantId));

            // ② Bosta connected
            boolean bostaDone = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
                Boolean.class, tenantId));

            // ③ Import completed
            boolean importDone = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM stores " +
                "WHERE tenant_id = ? AND import_status = 'completed')",
                Boolean.class, tenantId));

            // ④ Test label (explicit reprint — closest available signal)
            boolean labelDone = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM label_reprints WHERE tenant_id = ?)",
                Boolean.class, tenantId));

            // ⑤ First receiving session finalized
            boolean receivingDone = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM receipts " +
                "WHERE tenant_id = ? AND finalized_at IS NOT NULL)",
                Boolean.class, tenantId));

            List<Map<String, Object>> steps = new ArrayList<>();
            steps.add(step("connect_shopify",  "Connect Shopify store",       shopifyDone));
            steps.add(step("connect_bosta",    "Connect Bosta courier",       bostaDone));
            steps.add(step("initial_import",   "Run product & order import",  importDone));
            steps.add(step("test_label",       "Print a test label",          labelDone));
            steps.add(step("first_receiving",  "Complete first receiving session", receivingDone));

            boolean allDone = shopifyDone && bostaDone && importDone && labelDone && receivingDone;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("steps",   steps);
            result.put("allDone", allDone);
            return result;
        }));
    }

    private static Map<String, Object> step(String key, String label, boolean done) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key",    key);
        m.put("label",  label);
        m.put("status", done ? "done" : "pending");
        return m;
    }
}
