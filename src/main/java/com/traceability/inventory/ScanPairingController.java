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
 * S6 — the tablet side of phone-as-scanner: pair a phone with the caller's open waybill pack
 * session, see / end the pairing, receive the phone's scans (SSE) and report each outcome.
 * Access: {@code isAuthenticated()}, the session's owner only (403 SESSION_NOT_YOURS otherwise,
 * 404 for another tenant's session — RLS), as every pack-session endpoint.
 */
@RestController
@RequestMapping("/api/v1/pack-sessions")
public class ScanPairingController {

    private final ScanPairingService svc;
    private final ScanRelayHub       hub;

    public ScanPairingController(ScanPairingService svc, ScanRelayHub hub) {
        this.svc = svc;
        this.hub = hub;
    }

    /** New pairing (revokes the previous one): the URL the QR encodes. */
    @PostMapping("/{id}/pairings")
    @PreAuthorize("isAuthenticated()")
    public ScanPairingService.PairingCreated create(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p) {
        return svc.create(id, p.userId());
    }

    @GetMapping("/{id}/pairings/current")
    @PreAuthorize("isAuthenticated()")
    public ScanPairingService.PairingStatus current(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p) {
        return svc.current(id, p.userId());
    }

    @DeleteMapping("/{id}/pairings/current")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpair(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p) {
        svc.unpair(id, p.userId());
    }

    /** The station is handed to another worker: end every phone pairing the caller holds. */
    @DeleteMapping("/pairings/mine")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpairMine(@AuthenticationPrincipal CustomUserDetails p) {
        svc.revokeForUser(p.userId(), "worker_switched");
    }

    /**
     * The phone's scans for this session, as Server-Sent Events: {@code pairing} (status) first,
     * then {@code scan} {id, seq, code, createdAt} for each phone scan under 5 s old, plus a
     * heartbeat comment every 20 s. One stream per session — a new one closes the old one.
     */
    @GetMapping(value = "/{id}/relay-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter stream(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p,
                             HttpServletResponse response) {
        svc.requireStreamable(id, p.userId());
        response.setHeader("X-Accel-Buffering", "no");          // nginx: don't buffer this response
        response.setHeader("Cache-Control", "no-cache");
        ScanRelayHub.Subscriber sub = hub.subscribe(id, TenantContext.require());
        hub.send(sub, "pairing", svc.statusForStream(id));
        svc.deliver(id);
        return sub.emitter;
    }

    @PostMapping("/{id}/relay-events/{eventId}/outcome")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void outcome(@PathVariable UUID id, @PathVariable UUID eventId, @RequestBody OutcomeRequest req,
                        @AuthenticationPrincipal CustomUserDetails p) {
        svc.outcome(id, p.userId(), eventId, req.result(), req.message());
    }

    public record OutcomeRequest(String result, String message) {}
}
