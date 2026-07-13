package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pickup-sessions")
@PreAuthorize("isAuthenticated()")
public class PickupSessionController {

    private final PickupSessionService service;

    public PickupSessionController(PickupSessionService service) {
        this.service = service;
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    record OpenRequest(String scheduledDate, String scheduledTimeSlot, String notes) {}

    @PostMapping
    public ResponseEntity<Map<String, String>> openSession(
            @RequestBody OpenRequest req,
            @AuthenticationPrincipal CustomUserDetails user) {

        LocalDate date = LocalDate.parse(req.scheduledDate());
        UUID sessionId = service.openSession(
            user.tenantId(), user.userId(), date, req.scheduledTimeSlot(), req.notes());
        return ResponseEntity.ok(Map.of("sessionId", sessionId.toString()));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<PickupSessionService.SessionSummary> listSessions(
            @AuthenticationPrincipal CustomUserDetails user) {
        return service.listSessions(user.tenantId());
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public PickupSessionService.SessionDetail getSession(
            @PathVariable UUID id,
            @AuthenticationPrincipal CustomUserDetails user) {
        return service.getSession(user.tenantId(), id);
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    record ScanRequest(String trackingNumber) {}

    @PostMapping("/{id}/scans")
    public ResponseEntity<Map<String, Object>> scan(
            @PathVariable UUID id,
            @RequestBody ScanRequest req,
            @AuthenticationPrincipal CustomUserDetails user) {

        PickupSessionService.ScanResult result =
            service.scan(user.tenantId(), id, user.userId(), req.trackingNumber());

        return ResponseEntity.ok(Map.of(
            "outcome", result.outcome().name(),
            "entry",   result.entry() != null ? result.entry() : Map.of()
        ));
    }

    // ── Remove scan ───────────────────────────────────────────────────────────

    @DeleteMapping("/{id}/scans/{shipmentId}")
    public ResponseEntity<Void> removeScan(
            @PathVariable UUID id,
            @PathVariable UUID shipmentId,
            @AuthenticationPrincipal CustomUserDetails user) {

        service.removeScan(user.tenantId(), id, shipmentId);
        return ResponseEntity.noContent().build();
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/close")
    public ResponseEntity<Map<String, Object>> closeSession(
            @PathVariable UUID id,
            @AuthenticationPrincipal CustomUserDetails user) {

        PickupSessionService.CloseResult result =
            service.closeSession(user.tenantId(), id, user.userId());

        return ResponseEntity.ok(Map.of(
            "shipmentsClosed",  result.shipmentsClosed(),
            "pieceExceptions",  result.pieceExceptions()
        ));
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/manifest")
    public PickupSessionService.ManifestData getManifest(
            @PathVariable UUID id,
            @AuthenticationPrincipal CustomUserDetails user) {
        return service.getManifestData(user.tenantId(), id);
    }
}
