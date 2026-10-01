package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — waybill pack sessions.
 *
 * Access: {@code isAuthenticated()}, as every Pick &amp; Pack endpoint; the actor is the JWT user
 * (the station worker after a PIN switch). A session belongs to the user who started it — any
 * other user gets 403 SESSION_NOT_YOURS, another tenant's session 404 (RLS).
 */
@RestController
@RequestMapping("/api/v1/pack-sessions")
public class PackSessionController {

    private final PackSessionStore   store;
    private final PackSessionService svc;

    public PackSessionController(PackSessionStore store, PackSessionService svc) {
        this.store = store;
        this.svc   = svc;
    }

    /** Start a waybill session, or resume the caller's open one (incl. its open order). */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public PackSessionStore.SessionView start(@AuthenticationPrincipal CustomUserDetails p) {
        UUID id = store.start(p.userId());
        return store.view(id, p.userId());
    }

    /** Pick &amp; Pack page tiles: packed today, and the caller's open session (Resume). */
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public PackSessionStore.Summary summary(@AuthenticationPrincipal CustomUserDetails p) {
        return store.summary(p.userId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public PackSessionStore.SessionView view(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p) {
        return store.view(id, p.userId());
    }

    @PostMapping("/{id}/waybill")
    @PreAuthorize("isAuthenticated()")
    public PackSessionStore.WaybillOutcome waybill(@PathVariable UUID id, @RequestBody CodeRequest req,
                                                   @AuthenticationPrincipal CustomUserDetails p) {
        return store.openWaybill(id, req.code(), p.userId());
    }

    @PostMapping("/{id}/orders/{orderId}/scan")
    @PreAuthorize("isAuthenticated()")
    public PackSessionService.ScanResponse scan(@PathVariable UUID id, @PathVariable UUID orderId,
                                                @RequestBody CodeRequest req,
                                                @AuthenticationPrincipal CustomUserDetails p) {
        return svc.scan(id, orderId, req.code(), p.userId());
    }

    /** Retry complete+link after complete_failed. */
    @PostMapping("/{id}/orders/{orderId}/complete")
    @PreAuthorize("isAuthenticated()")
    public PackSessionService.ScanResponse complete(@PathVariable UUID id, @PathVariable UUID orderId,
                                                    @AuthenticationPrincipal CustomUserDetails p) {
        return svc.retryComplete(id, orderId, p.userId());
    }

    /** Undo one scanned piece while the order is open. */
    @DeleteMapping("/{id}/orders/{orderId}/scan/{pieceId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unscan(@PathVariable UUID id, @PathVariable UUID orderId, @PathVariable String pieceId,
                       @AuthenticationPrincipal CustomUserDetails p) {
        store.unscan(id, orderId, pieceId, p.userId());
    }

    @PostMapping("/{id}/orders/{orderId}/set-aside")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> setAside(@PathVariable UUID id, @PathVariable UUID orderId,
                                        @RequestBody SetAsideRequest req,
                                        @AuthenticationPrincipal CustomUserDetails p) {
        int returned = store.setAside(id, orderId, req.reason(), p.userId());
        return Map.of("piecesReturned", returned);
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails p) {
        store.end(id, p.userId());
    }

    public record CodeRequest(String code) {}
    public record SetAsideRequest(String reason) {}
}
