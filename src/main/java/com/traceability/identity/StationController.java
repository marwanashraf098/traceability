package com.traceability.identity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Worker Station Gate (Phase C) — the frontend gate's name-picker roster.
 */
@RestController
@RequestMapping("/api/v1/station")
public class StationController {

    private final PinService pinService;

    public StationController(PinService pinService) {
        this.pinService = pinService;
    }

    /**
     * GET /api/v1/station/roster — active, PIN-holding users in the caller's tenant.
     * isAuthenticated() only (not role-gated): the device may hold a worker-scoped
     * access token after a reload, and the gate must still be able to list who can
     * PIN in — an OWNER/MANAGER-only endpoint would 403 at the gate itself.
     */
    @GetMapping("/roster")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> roster(@AuthenticationPrincipal CustomUserDetails principal) {
        return pinService.roster(principal.tenantId());
    }
}
