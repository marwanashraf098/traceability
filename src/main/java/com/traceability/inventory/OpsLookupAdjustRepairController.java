package com.traceability.inventory;

import com.traceability.review.OpsSecretGuard;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * D10 — POST /api/v1/ops/repair/lookup-adjust-2026-10-10/{tenantId}?apply=false|true. No JWT: gated by
 * OpsSecretGuard (X-Ops-Secret; 404 when TRACED_OPS_SECRET is unset, 403 when wrong). Dry run unless
 * apply=true. Called by scripts/repair-lookup-adjust-2026-10-10 — see LookupAdjustRepairService.
 */
@RestController
@RequestMapping("/api/v1/ops/repair/lookup-adjust-2026-10-10")
public class OpsLookupAdjustRepairController {

    private final OpsSecretGuard guard;
    private final LookupAdjustRepairService repair;

    public OpsLookupAdjustRepairController(OpsSecretGuard guard, LookupAdjustRepairService repair) {
        this.guard  = guard;
        this.repair = repair;
    }

    @PostMapping("/{tenantId}")
    public Map<String, Object> run(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                   @PathVariable UUID tenantId,
                                   @RequestParam(defaultValue = "false") boolean apply) {
        guard.check(secret);
        return repair.run(tenantId, apply);
    }
}
