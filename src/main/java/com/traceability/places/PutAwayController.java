package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.PhoneScanSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse places S2 — Put away and Lookup's Move. Every role (owner, manager, worker); 404 unless the tenant has
 * places (PutAwayService). Settings › Places (/api/v1/places) stays owner + manager.
 *
 *   GET  /api/v1/put-away/summary                      waiting count (sidebar badge) + locations with places
 *   GET  /api/v1/put-away/waiting?locationId=&source=  the waiting list, counts per source, suggested places
 *   GET  /api/v1/put-away/places?locationId=           live shelves and boxes (Move's list)
 *   POST /api/v1/put-away/sessions                     {locationId?} start, or resume the caller's open session there
 *   GET  /api/v1/put-away/sessions                     every open session (owner + manager)
 *   GET  /api/v1/put-away/sessions/{id}
 *   POST /api/v1/put-away/sessions/{id}/scan           {code, relayEventId?} → PLACE_SET | PLACED | MOVED | …
 *   POST /api/v1/put-away/sessions/{id}/close          → what it placed, where (the opener; owner / manager: anyone's)
 *   PUT  /api/v1/put-away/pieces/{pieceId}/place       {placeId?, scan?} Lookup's Move; neither → No place
 */
@RestController
@RequestMapping("/api/v1/put-away")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class PutAwayController {

    public record StartRequest(UUID locationId) {}
    public record ScanRequest(String code, UUID relayEventId) {}
    public record PlaceRequest(UUID placeId, String scan) {}

    private final PutAwayService service;
    private final PhoneScanSource phoneScanSource;

    public PutAwayController(PutAwayService service, PhoneScanSource phoneScanSource) {
        this.service = service;
        this.phoneScanSource = phoneScanSource;
    }

    @GetMapping("/summary")
    public PutAwayService.Summary summary() {
        return service.summary();
    }

    @GetMapping("/waiting")
    public PutAwayService.Waiting waiting(@RequestParam(required = false) UUID locationId,
                                          @RequestParam(required = false) String source,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        return service.waiting(locationId, source, principal.userId());
    }

    @GetMapping("/places")
    public List<PutAwayService.PlaceView> places(@RequestParam(required = false) UUID locationId) {
        return service.holdablePlaces(locationId);
    }

    @PostMapping("/sessions")
    public PutAwayService.Session start(@RequestBody(required = false) StartRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return service.start(principal.userId(), req == null ? null : req.locationId());
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<PutAwayService.OpenSession> openSessions() {
        return service.openSessions();
    }

    @GetMapping("/sessions/{id}")
    public PutAwayService.Session get(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.get(id, principal.userId());
    }

    @PostMapping("/sessions/{id}/scan")
    public PutAwayService.ScanResult scan(@PathVariable UUID id, @RequestBody ScanRequest req,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        String code = req == null ? null : req.code();
        // 'via phone' only when the relay event verifies as the caller's own (PhoneScanSource), never client-sent.
        boolean viaPhone = req != null && phoneScanSource.isPhone(req.relayEventId(), code, principal.userId());
        return service.scan(id, code, principal.userId(), viaPhone);
    }

    @PostMapping("/sessions/{id}/close")
    public PutAwayService.CloseSummary close(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.close(id, principal.userId(), isManager(principal));
    }

    private static boolean isManager(CustomUserDetails principal) {
        return "owner".equalsIgnoreCase(principal.role()) || "manager".equalsIgnoreCase(principal.role());
    }

    @PutMapping("/pieces/{pieceId}/place")
    public PutAwayService.MoveResult place(@PathVariable String pieceId, @RequestBody(required = false) PlaceRequest req,
                                           @AuthenticationPrincipal CustomUserDetails principal) {
        return service.movePiece(pieceId, req == null ? null : req.placeId(), req == null ? null : req.scan(),
            principal.userId());
    }
}
