package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.PhoneScanSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Warehouse places S5 — counting one place (PlaceCountService). Every role (workers count shelves). 404 unless the
 * tenant has places; an unknown / another tenant's count → 404 COUNT_NOT_FOUND.
 *
 *   POST /api/v1/place-counts                {binId} or {scan} — start (or join the place's open count)
 *   GET  /api/v1/place-counts/{id}           the count: counters, found / moved here / not found, other scans
 *   POST /api/v1/place-counts/{id}/scan      {code, relayEventId?} — records a scan, changes nothing
 *   POST /api/v1/place-counts/{id}/finish    applies the count (one transaction)
 *   POST /api/v1/place-counts/{id}/cancel    nothing changes
 */
@RestController
@RequestMapping("/api/v1/place-counts")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class PlaceCountController {

    public record StartRequest(UUID binId, String scan) {}
    public record ScanRequest(String code, UUID relayEventId) {}

    private final PlaceCountService service;
    private final PhoneScanSource phoneScanSource;

    public PlaceCountController(PlaceCountService service, PhoneScanSource phoneScanSource) {
        this.service = service;
        this.phoneScanSource = phoneScanSource;
    }

    @PostMapping
    public PlaceCountService.Count start(@RequestBody(required = false) StartRequest req,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        return service.start(principal.userId(), req == null ? null : req.binId(), req == null ? null : req.scan());
    }

    @GetMapping("/{id}")
    public PlaceCountService.Count get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/scan")
    public PlaceCountService.ScanResult scan(@PathVariable UUID id, @RequestBody(required = false) ScanRequest req,
                                             @AuthenticationPrincipal CustomUserDetails principal) {
        String code = req == null ? null : req.code();
        // 'via phone' only when the relay event verifies as the caller's own (PhoneScanSource), never client-sent.
        boolean viaPhone = req != null && phoneScanSource.isPhone(req.relayEventId(), code, principal.userId());
        return service.scan(id, code, principal.userId(), viaPhone);
    }

    @PostMapping("/{id}/finish")
    public PlaceCountService.Count finish(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.finish(id, principal.userId());
    }

    @PostMapping("/{id}/cancel")
    public PlaceCountService.Count cancel(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.cancel(id, principal.userId());
    }
}
