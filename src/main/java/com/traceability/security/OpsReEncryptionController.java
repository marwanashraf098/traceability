package com.traceability.security;

import com.traceability.review.OpsSecretGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encryption key rotation K2b — ONE-OFF re-encryption of the stored legacy values to v2 (see
 * {@link ReEncryptionService}). No JWT: OpsSecretFilter + OpsSecretGuard (X-Ops-Secret; 404 when TRACED_OPS_SECRET is
 * unset, 403 when wrong).
 *
 *   POST /api/v1/ops/re-encryption              (no body / {apply:false}) dry run: legacy + v2 count per column
 *   POST /api/v1/ops/re-encryption {apply:true} migrate, then the same counts after; 409 unless writes are v2
 *
 * Idempotent: a second apply finds only v2 values and changes nothing. Counts only — never a value.
 */
@RestController
@RequestMapping("/api/v1/ops/re-encryption")
public class OpsReEncryptionController {

    private final OpsSecretGuard guard;
    private final ReEncryptionService reEncryption;

    public OpsReEncryptionController(OpsSecretGuard guard, ReEncryptionService reEncryption) {
        this.guard = guard;
        this.reEncryption = reEncryption;
    }

    public record Request(Boolean apply) {}

    @PostMapping
    public Map<String, Object> run(@RequestHeader(value = OpsSecretGuard.HEADER, required = false) String secret,
                                   @RequestBody(required = false) Request req) {
        guard.check(secret);
        boolean apply = req != null && Boolean.TRUE.equals(req.apply());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", apply ? "apply" : "dry_run");
        if (apply) {
            try {
                out.put("results", reEncryption.apply());
            } catch (ReEncryptionService.RefusedException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
        }
        out.put("counts", reEncryption.counts());
        return out;
    }
}
