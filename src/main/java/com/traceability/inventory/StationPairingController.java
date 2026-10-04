package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Q1 — the tablet side of phone-as-scanner, per station (replaces S6's per-pack-session
 * endpoints): pair a phone with THIS tablet ({@code deviceId}, its random localStorage id) and the
 * caller, see / end the pairing, receive the phone's scans (SSE), report each outcome, and name
 * the scanning screen open on the tablet for the phone header. Access: {@code isAuthenticated()},
 * any role; the caller's own pairing only — another worker's pairing on the same tablet reads as
 * 'none' and can't be streamed (403 PAIRING_NOT_YOURS); another tenant's is invisible (RLS).
 */
@RestController
@RequestMapping("/api/v1")
public class StationPairingController {

    private final ScanPairingService svc;
    private final ScanRelayHub       hub;

    public StationPairingController(ScanPairingService svc, ScanRelayHub hub) {
        this.svc = svc;
        this.hub = hub;
    }

    /** New pairing for this tablet (revokes this tablet's and this worker's previous one): the URL the QR encodes. */
    @PostMapping("/station/pairings")
    @PreAuthorize("isAuthenticated()")
    public ScanPairingService.PairingCreated create(@RequestBody DeviceRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return svc.create(req == null ? null : req.deviceId(), p.userId());
    }

    @GetMapping("/station/pairings/current")
    @PreAuthorize("isAuthenticated()")
    public ScanPairingService.PairingStatus current(@RequestParam(required = false) String deviceId,
                                                    @AuthenticationPrincipal CustomUserDetails p) {
        return svc.current(deviceId, p.userId());
    }

    @DeleteMapping("/station/pairings/current")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpair(@RequestParam(required = false) String deviceId, @AuthenticationPrincipal CustomUserDetails p) {
        svc.unpair(deviceId, p.userId());
    }

    /** The scanning screen open on the tablet ("Pick & Pack · #1047"); null / blank clears it. */
    @PutMapping("/station/pairings/current/target")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void target(@RequestParam(required = false) String deviceId, @RequestBody TargetRequest req,
                       @AuthenticationPrincipal CustomUserDetails p) {
        svc.setTarget(deviceId, p.userId(), req == null ? null : req.label());
    }

    /**
     * Every phone pairing the caller holds ends: the station is handed to another worker
     * ({@code worker_switched}, StationProvider.signOutWorker — the default) or has locked back to
     * the PIN gate ({@code station_locked}, StationGate). Path kept from S6.
     */
    @DeleteMapping("/pack-sessions/pairings/mine")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpairMine(@RequestParam(required = false, defaultValue = "worker_switched") String reason,
                           @AuthenticationPrincipal CustomUserDetails p) {
        if (!ScanPairingService.SELF_REVOKE_REASONS.contains(reason)) throw ScanPairException.badReason();
        svc.revokeForUser(p.userId(), reason);
    }

    /**
     * The phone's scans for this tablet's pairing, as Server-Sent Events: {@code pairing} (status)
     * first, then {@code scan} {id, seq, code, createdAt} for each phone scan under 5 s old, plus a
     * heartbeat comment every 20 s. One stream per pairing (so per tablet) — a new one closes the
     * old one. The caller must be the pairing's worker; 409 NO_PAIRING when the tablet has none.
     */
    @GetMapping(value = "/station/relay-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter stream(@RequestParam(required = false) String deviceId,
                             @AuthenticationPrincipal CustomUserDetails p, HttpServletResponse response) {
        UUID pairingId = svc.requireStreamable(deviceId, p.userId());
        response.setHeader("X-Accel-Buffering", "no");          // nginx: don't buffer this response
        response.setHeader("Cache-Control", "no-cache");
        ScanRelayHub.Subscriber sub = hub.subscribe(pairingId, TenantContext.require());
        hub.send(sub, "pairing", svc.statusForStream(pairingId));
        svc.deliver(pairingId);
        return sub.emitter;
    }

    @PostMapping("/station/relay-events/{eventId}/outcome")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void outcome(@PathVariable UUID eventId, @RequestBody OutcomeRequest req,
                        @AuthenticationPrincipal CustomUserDetails p) {
        svc.outcome(p.userId(), eventId, req == null ? null : req.result(), req == null ? null : req.message());
    }

    public record DeviceRequest(String deviceId) {}
    public record TargetRequest(String label) {}
    public record OutcomeRequest(String result, String message) {}
}
