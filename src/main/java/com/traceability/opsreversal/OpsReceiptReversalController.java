package com.traceability.opsreversal;

import com.traceability.review.OpsSecretGuard;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * ONE-OFF (2026-11-05) — remove after the Floe Wear PO-01 reversal has settled. No JWT: OpsSecretFilter +
 * OpsSecretGuard (X-Ops-Secret; 404 when TRACED_OPS_SECRET is unset, 403 when wrong).
 *
 *   POST /api/v1/ops/receipt-reversal   {receiptId, actorUserId, requestedByUserId, apply, allowNegative}
 *                                       starts a dry run (apply=false, the default) or the apply, in the background
 *   GET  /api/v1/ops/receipt-reversal   the run's report + live per-variant Shopify claim outcomes
 */
@RestController
@RequestMapping("/api/v1/ops/receipt-reversal")
public class OpsReceiptReversalController {

    private final OpsSecretGuard guard;
    private final ReceiptReversalService service;

    public OpsReceiptReversalController(OpsSecretGuard guard, ReceiptReversalService service) {
        this.guard = guard;
        this.service = service;
    }

    public record StartRequest(UUID receiptId, UUID actorUserId, UUID requestedByUserId, Boolean apply,
                               Boolean allowNegative) {}

    @PostMapping
    public Map<String, Object> start(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                     @RequestBody StartRequest req) {
        guard.check(secret);
        return service.start(new ReceiptReversalService.Request(req.receiptId(), req.actorUserId(),
            req.requestedByUserId(), Boolean.TRUE.equals(req.apply()), Boolean.TRUE.equals(req.allowNegative())));
    }

    @GetMapping
    public Map<String, Object> report(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret) {
        guard.check(secret);
        return service.report();
    }
}
