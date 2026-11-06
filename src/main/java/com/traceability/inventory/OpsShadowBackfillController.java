package com.traceability.inventory;

import com.traceability.review.OpsSecretGuard;
import com.traceability.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S3b — ONE-OFF backfill at deploy: shadow orders for the open, approved Shopify returns with
 * exchange lines that the mirror already holds (new ones come from the webhook hooks). Per tenant, run under that
 * tenant (app_user + RLS — no cross-tenant read). No JWT: OpsSecretFilter + OpsSecretGuard (X-Ops-Secret; 404 when
 * TRACED_OPS_SECRET is unset, 403 when wrong).
 *
 *   POST /api/v1/ops/shopify-shadows/backfill   {tenantId, apply}   apply=false (default): counts only, nothing written
 */
@RestController
@RequestMapping("/api/v1/ops/shopify-shadows/backfill")
public class OpsShadowBackfillController {

    private final OpsSecretGuard guard;
    private final ShopifyShadowOrders shadows;

    public OpsShadowBackfillController(OpsSecretGuard guard, ShopifyShadowOrders shadows) {
        this.guard = guard;
        this.shadows = shadows;
    }

    public record Request(UUID tenantId, Boolean apply) {}

    @PostMapping
    public Map<String, Object> run(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                   @RequestBody Request req) {
        guard.check(secret);
        if (req == null || req.tenantId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        boolean apply = Boolean.TRUE.equals(req.apply());
        ShopifyShadowOrders.BackfillCounts c = TenantContext.runAs(req.tenantId(), () -> shadows.backfill(!apply));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", req.tenantId());
        out.put("mode", apply ? "apply" : "dry_run");
        out.put("originalOrders", c.orders());
        out.put(apply ? "shadowsCreated" : "shadowsToCreate", c.shadowsToCreate());
        out.put(apply ? "unitsKeptAside" : "unitsToKeepAside", c.unitsToKeepAside());
        return out;
    }
}
