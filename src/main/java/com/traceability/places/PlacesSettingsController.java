package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Places rollout — Settings › Places "Use places in this store" (PlacesSettingsService). Owner + manager, for every
 * store whatever its switch (a worker gets 403).
 *
 *   GET /api/v1/places/settings   {enabled, setUp}
 *   PUT /api/v1/places/settings   {enabled} → {enabled, setUp}; audit-logged
 */
@RestController
@RequestMapping("/api/v1/places/settings")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class PlacesSettingsController {

    public record SwitchRequest(Boolean enabled) {}

    private final PlacesSettingsService service;

    public PlacesSettingsController(PlacesSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public PlacesSettingsService.Settings get() {
        return service.get();
    }

    @PutMapping
    public PlacesSettingsService.Settings put(@RequestBody(required = false) SwitchRequest body,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        if (body == null || body.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        return service.update(principal.userId(), body.enabled());
    }
}
