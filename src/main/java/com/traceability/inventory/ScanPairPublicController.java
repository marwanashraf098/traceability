package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * S6 — the phone side of phone-as-scanner. PUBLIC (no login; SecurityConfig permits
 * /api/v1/scan-pair/**): the phone holds only its one-time pair code, then its device secret
 * (header {@code X-Device-Secret}). Each request resolves that secret through hatch #15 with no
 * tenant set, then sets the pairing's tenant for this request only — set here, cleared in a
 * finally, never nested and never via TenantContext.runAs (whose restore-on-exit bug clears an
 * outer context: there is none on this path, the request is unauthenticated, and nothing below
 * calls runAs). Every invalid / used / expired / revoked secret: 401 PAIRING_ENDED.
 */
@RestController
@RequestMapping("/api/v1/scan-pair")
public class ScanPairPublicController {

    static final String DEVICE_SECRET_HEADER = "X-Device-Secret";

    private final ScanPairingService svc;

    public ScanPairPublicController(ScanPairingService svc) {
        this.svc = svc;
    }

    /** Pair code (from the QR) → device secret, returned once; the code can't be used again. */
    @PostMapping("/claim")
    public ScanPairingService.Claimed claim(@RequestBody ClaimRequest req,
                                            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return asPairing("pair_code", req == null ? null : req.pairCode(),
            r -> svc.claim(r, ScanPairingService.deviceLabel(userAgent)));
    }

    /** One phone scan; idempotent on seq. */
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestHeader(value = DEVICE_SECRET_HEADER, required = false) String secret,
                                    @RequestBody ScanRequest req) {
        return asPairing("device_secret", secret,
            r -> Map.of("eventId", svc.scan(r, req == null ? null : req.seq(), req == null ? null : req.code())));
    }

    /** The phone polls this (≤ ~4 s) for the tablet's verdict. */
    @GetMapping("/scan/{eventId}")
    public ScanPairingService.EventStatus scanStatus(@RequestHeader(value = DEVICE_SECRET_HEADER, required = false) String secret,
                                                     @PathVariable UUID eventId) {
        return asPairing("device_secret", secret, r -> svc.eventStatus(r, eventId));
    }

    /** Header context: connected to whose station, and the order open there. */
    @GetMapping("/status")
    public ScanPairingService.PhoneContext status(@RequestHeader(value = DEVICE_SECRET_HEADER, required = false) String secret) {
        return asPairing("device_secret", secret, svc::phoneStatus);
    }

    private <T> T asPairing(String kind, String secret, Function<ScanPairingService.Resolved, T> body) {
        if (TenantContext.get() != null) {
            // Unreachable on this public path (no JWT → no tenant). Refuse rather than overwrite one.
            throw new IllegalStateException("scan-pair request arrived with a tenant already set");
        }
        ScanPairingService.Resolved r = svc.resolve(kind, secret);
        if (r == null) throw ScanPairException.ended();
        TenantContext.set(r.tenantId());
        try {
            return body.apply(r);
        } finally {
            TenantContext.clear();
        }
    }

    public record ClaimRequest(String pairCode) {}
    public record ScanRequest(Long seq, String code) {}
}
