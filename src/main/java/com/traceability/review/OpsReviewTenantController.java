package com.traceability.review;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Review mode S5 — ops endpoints for the Shopify App Store review tenant. No JWT: every call is
 * gated by OpsSecretGuard (X-Ops-Secret; 404 when TRACED_OPS_SECRET is unset, 403 when wrong).
 *
 *   POST /api/v1/ops/review-tenant              step A — create the tenant through the normal signup
 *                                                (owner + default location) and one PIN worker
 *   POST /api/v1/ops/review-tenant/{id}/seed    step C — seed the fixture; 409 NOT_SIMULATED unless the
 *                                                tenant was flagged (step B, scripts/ops/review-tenant-flag.sql),
 *                                                409 FIXTURE_EXISTS when it already holds the fixture
 *
 * The owner's password is set by the caller, never logged, never echoed back.
 */
@RestController
@RequestMapping("/api/v1/ops/review-tenant")
public class OpsReviewTenantController {

    private final OpsSecretGuard guard;
    private final ReviewTenantService service;

    public OpsReviewTenantController(OpsSecretGuard guard, ReviewTenantService service) {
        this.guard   = guard;
        this.service = service;
    }

    /** The password never appears in toString (so never in a log line or an exception message). */
    public record CreateRequest(String tenantName, String ownerName, String email, String phone,
                                String password, String workerName, String workerPin) {
        @Override public String toString() {
            return "CreateRequest[tenantName=" + tenantName + ", ownerName=" + ownerName + ", email=" + email +
                   ", workerName=" + workerName + ", password=***, workerPin=***]";
        }
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                      @RequestBody CreateRequest req) {
        guard.check(secret);
        return service.create(req);
    }

    @PostMapping("/{tenantId}/seed")
    public Map<String, Object> seed(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                    @PathVariable UUID tenantId) {
        guard.check(secret);
        return service.seed(tenantId);
    }
}
