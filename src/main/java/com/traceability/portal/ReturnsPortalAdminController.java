package com.traceability.portal;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns portal Step 4b — merchant endpoints (owner and manager only): return requests
 * (list / detail / approve / reject; 4d-1: link-leg / rest-not-coming / close), portal
 * settings, and the per-variant non-returnable flag.
 */
@RestController
@RequestMapping("/api/v1")
public class ReturnsPortalAdminController {

    private final ReturnRequestService  requests;
    private final PortalSettingsService settings;
    private final ReturnLocationService returnLocations;
    private final ReturnPickupBookingService booking;

    public ReturnsPortalAdminController(ReturnRequestService requests, PortalSettingsService settings,
                                        ReturnLocationService returnLocations, ReturnPickupBookingService booking) {
        this.requests        = requests;
        this.settings        = settings;
        this.returnLocations = returnLocations;
        this.booking         = booking;
    }

    // ── Step 4c-3: Bosta return pickup booking ───────────────────────────────

    /** 'failed' → book again (the job re-claims). 409 otherwise. */
    @PostMapping("/return-requests/{id}/booking/retry")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryBooking(@PathVariable UUID id) {
        booking.retry(id);
    }

    /** "It wasn't booked — retry": 'failed_ambiguous' → 'failed' → book again. 409 otherwise. */
    @PostMapping("/return-requests/{id}/booking/not-booked")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void markNotBooked(@PathVariable UUID id) {
        booking.markNotBooked(id);
    }

    public record ConfirmBookingRequest(String trackingNumber) {}

    /** "It was booked — enter tracking number": checked against Bosta, then booked + verified. */
    @PostMapping("/return-requests/{id}/booking/confirm")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmBooking(@PathVariable UUID id, @RequestBody(required = false) ConfirmBookingRequest body) {
        booking.confirmBooked(id, body == null ? null : body.trackingNumber());
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

    // ── Step 4d-1: return request lifecycle ──────────────────────────────────

    public record LinkLegRequest(UUID shipmentId) {}

    /** Link a courier-return (type 25) leg of the same order to this request. */
    @PostMapping("/return-requests/{id}/link-leg")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void linkLeg(@PathVariable UUID id, @RequestBody(required = false) LinkLegRequest body,
                        @AuthenticationPrincipal CustomUserDetails principal) {
        requests.linkLeg(id, body == null ? null : body.shipmentId(), principal.userId());
    }

    /** Every item still awaited → not coming; nothing ever arrived → the request closes. */
    @PostMapping("/return-requests/{id}/rest-not-coming")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restNotComing(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        requests.restNotComing(id, principal.userId());
    }

    public record CloseRequest(String reason, String note) {}

    /** Close without a refund: reason no_refund | other, optional note (≤ 300). */
    @PostMapping("/return-requests/{id}/close")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest body,
                      @AuthenticationPrincipal CustomUserDetails principal) {
        requests.close(id, body == null ? null : body.reason(), body == null ? null : body.note(), principal.userId());
    }

    public record PickupAreaRequest(String districtId) {}

    /** Step 4c-2 — the pickup-available districts of the request's city, for "Change area". */
    @GetMapping("/return-requests/{id}/pickup-areas")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> pickupAreas(@PathVariable UUID id) {
        return requests.pickupAreas(id);
    }

    /** Step 4c-2 — change the request's pickup area (requested / approved only). */
    @PutMapping("/return-requests/{id}/pickup-area")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPickupArea(@PathVariable UUID id, @RequestBody(required = false) PickupAreaRequest body) {
        requests.setPickupArea(id, body == null ? null : body.districtId());
    }

    // ── Portal settings ──────────────────────────────────────────────────────

    @GetMapping("/tenant/portal-settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> getSettings() {
        return settings.get();
    }

    @PutMapping("/tenant/portal-settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> putSettings(@RequestBody(required = false) PortalSettingsService.Settings body) {
        try {
            // Step 4c-2: a new return location is checked against Bosta BEFORE the settings
            // transaction opens (no connection held across the HTTP call).
            ReturnLocationService.Location location =
                body != null && body.returnLocationId() != null && !body.returnLocationId().isBlank()
                    ? returnLocations.resolveForSave(body.returnLocationId())
                    : null;
            return ResponseEntity.ok(settings.update(body, location));
        } catch (PortalSettingsService.FieldException e) {
            return fieldError(e);
        }
    }

    /** Step 4c-2 — the tenant's Bosta pickup locations, for "Returns go back to". */
    @GetMapping("/tenant/bosta/return-locations")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<?> returnLocations() {
        try {
            List<Map<String, Object>> list = returnLocations.list();
            return ResponseEntity.ok(list);
        } catch (PortalSettingsService.FieldException e) {
            return fieldError(e);
        }
    }

    /**
     * Answered here with a body (the global ResponseStatusException handler is bodyless), so
     * the settings page can put the error next to the right field.
     */
    private static ResponseEntity<Map<String, Object>> fieldError(PortalSettingsService.FieldException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("field", e.field());
        err.put("error", e.code());
        err.put("message", e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(err);
    }

    /** Step 4e-A — variant list for the "products that can't be returned" setting. */
    @GetMapping("/variants")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> listVariants(@RequestParam(required = false) String search,
                                            @RequestParam(defaultValue = "0")  int page,
                                            @RequestParam(defaultValue = "25") int size) {
        return settings.listVariants(search, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
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
