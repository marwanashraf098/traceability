package com.traceability.places;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Warehouse places S5 — "Where is it" (WhereIsItService). Every role (Lookup is used by workers); 404 unless the
 * tenant has places.
 *
 *   GET /api/v1/places/where/{variantId}   per location: every place holding the variant, counts, "No place yet"
 */
@RestController
@RequestMapping("/api/v1/places/where")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class WhereIsItController {

    private final WhereIsItService service;

    public WhereIsItController(WhereIsItService service) {
        this.service = service;
    }

    @GetMapping("/{variantId}")
    public WhereIsItService.Where where(@PathVariable UUID variantId) {
        return service.where(variantId);
    }
}
