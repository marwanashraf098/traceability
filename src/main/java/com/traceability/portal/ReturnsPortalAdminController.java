package com.traceability.portal;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Returns portal Step 4b — merchant endpoints (owner and manager only): return requests
 * (list / detail / approve / reject), portal settings, and the per-variant non-returnable flag.
 */
@RestController
@RequestMapping("/api/v1")
public class ReturnsPortalAdminController {

    private final ReturnRequestService  requests;
    private final PortalSettingsService settings;

    public ReturnsPortalAdminController(ReturnRequestService requests, PortalSettingsService settings) {
        this.requests = requests;
        this.settings = settings;
    }

    // ── Return requests ──────────────────────────────────────────────────────

    @GetMapping("/return-requests")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0")  int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return requests.list(status, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/return-requests/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return requests.detail(id);
    }

    @PostMapping("/return-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        requests.approve(id, principal.userId());
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/return-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body,
                       @AuthenticationPrincipal CustomUserDetails principal) {
        requests.reject(id, body == null ? null : body.reason(), principal.userId());
    }

    // ── Portal settings ──────────────────────────────────────────────────────

    @GetMapping("/tenant/portal-settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> getSettings() {
        return settings.get();
    }

    @PutMapping("/tenant/portal-settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> putSettings(@RequestBody(required = false) PortalSettingsService.Settings body) {
        return settings.update(body);
    }

    public record NonReturnableRequest(Boolean value) {}

    @PutMapping("/variants/{id}/non-returnable")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setNonReturnable(@PathVariable UUID id, @RequestBody(required = false) NonReturnableRequest body) {
        if (body == null || body.value() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "value is required");
        }
        settings.setNonReturnable(id, body.value());
    }
}
