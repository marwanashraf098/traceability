package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Warehouse places S1 — Settings › Places. Owner + manager; 404 unless the tenant has places (PlacesService).
 *
 *   GET  /api/v1/places?locationId=        the location's live places (null → main warehouse), tree order
 *   GET  /api/v1/places/{id}               one place: what's on it, its boxes, its history
 *   POST /api/v1/places                    {locationId?, kind, parentId?, code?, name?} one place
 *   POST /api/v1/places/bulk/preview       {locationId?, zoneId?, racks, shelvesPerRack, floorBoxes, zoneName?}
 *   POST /api/v1/places/bulk               same body, created in one transaction
 *   PUT  /api/v1/places/{id}               {code?, name?} rename
 *   PUT  /api/v1/places/{id}/parent        {parentId} a box moves zone ↔ shelf
 *   PUT  /api/v1/places/{id}/purpose       {purpose: "damaged" | null}
 *   POST /api/v1/places/{id}/archive
 *   POST /api/v1/places/stickers           {placeIds} → PDF (shelves and boxes in / under them)
 */
@RestController
@RequestMapping("/api/v1/places")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class PlacesController {

    public record RenameRequest(String code, String name) {}
    public record MoveRequest(UUID parentId) {}
    public record PurposeRequest(String purpose) {}
    public record StickersRequest(List<UUID> placeIds) {}

    private final PlacesService service;

    public PlacesController(PlacesService service) {
        this.service = service;
    }

    @GetMapping
    public PlacesService.Tree tree(@RequestParam(required = false) UUID locationId) {
        return service.tree(locationId);
    }

    @GetMapping("/{id}")
    public PlacesService.Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<PlacesService.Node> create(@RequestBody PlacesService.CreateRequest req,
                                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.userId(), req));
    }

    @PostMapping("/bulk/preview")
    public PlacesService.BulkPlan bulkPreview(@RequestBody PlacesService.BulkRequest req) {
        return service.bulkPreview(req);
    }

    @PostMapping("/bulk")
    public ResponseEntity<PlacesService.BulkResult> bulk(@RequestBody PlacesService.BulkRequest req,
                                                         @AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.bulkCreate(principal.userId(), req));
    }

    @PutMapping("/{id}")
    public PlacesService.Node rename(@PathVariable UUID id, @RequestBody RenameRequest req,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return service.rename(principal.userId(), id, req.code(), req.name());
    }

    @PutMapping("/{id}/parent")
    public PlacesService.Node move(@PathVariable UUID id, @RequestBody MoveRequest req,
                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return service.move(principal.userId(), id, req.parentId());
    }

    @PutMapping("/{id}/purpose")
    public PlacesService.Node purpose(@PathVariable UUID id, @RequestBody PurposeRequest req,
                                      @AuthenticationPrincipal CustomUserDetails principal) {
        return service.setPurpose(principal.userId(), id, req.purpose());
    }

    @PostMapping("/{id}/archive")
    public PlacesService.Node archive(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.archive(principal.userId(), id);
    }

    @PostMapping("/stickers")
    public ResponseEntity<byte[]> stickers(@RequestBody StickersRequest req) throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"place-stickers.pdf\"")
            .body(service.stickers(req == null ? null : req.placeIds()));
    }
}
