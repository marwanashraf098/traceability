package com.traceability.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Review mode S5 — the ops endpoints' only gate (they carry no JWT; SecurityConfig permits
 * /api/v1/ops/**). TRACED_OPS_SECRET (traced.ops-secret) unset or blank → 404, as if the endpoints
 * did not exist; a missing or wrong X-Ops-Secret header → 403. Constant-time compare
 * (MessageDigest.isEqual). The secret and the header are never logged.
 */
@Component
public class OpsSecretGuard {

    public static final String HEADER = "X-Ops-Secret";

    private final byte[] secret;

    public OpsSecretGuard(@Value("${traced.ops-secret:}") String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    public void check(String header) {
        if (secret == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(secret, given)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
