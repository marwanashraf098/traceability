package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Warehouse places S5 — Put away › Move a box (BoxMoveService). Every role; 404 unless the tenant has places.
 *
 *   POST /api/v1/put-away/box-moves/box   {box}          the scanned box, checked (step 1)
 *   POST /api/v1/put-away/box-moves       {box, target}  moves the box onto the scanned shelf / zone floor
 */
@RestController
@RequestMapping("/api/v1/put-away/box-moves")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class BoxMoveController {

    public record BoxRequest(String box) {}
    public record MoveRequest(String box, String target) {}

    private final BoxMoveService service;

    public BoxMoveController(BoxMoveService service) {
        this.service = service;
    }

    @PostMapping("/box")
    public BoxMoveService.BoxCheck box(@RequestBody(required = false) BoxRequest req) {
        return service.checkBox(req == null ? null : req.box());
    }

    @PostMapping
    public BoxMoveService.BoxMove move(@RequestBody(required = false) MoveRequest req,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        return service.move(req == null ? null : req.box(), req == null ? null : req.target(), principal.userId());
    }
}
