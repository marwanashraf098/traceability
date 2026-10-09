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
    private final RefundSuggestionService suggestions;
    private final PortalLogoService logos;

    public ReturnsPortalAdminController(ReturnRequestService requests, PortalSettingsService settings,
                                        ReturnLocationService returnLocations, ReturnPickupBookingService booking,
                                        RefundSuggestionService suggestions, PortalLogoService logos) {
        this.logos           = logos;
        this.requests        = requests;
        this.settings        = settings;
        this.returnLocations = returnLocations;
        this.booking         = booking;
        this.suggestions     = suggestions;
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

    /** Step 5c — "Book now": an approved exchange that was never booked → book it (same claim path). 409 otherwise. */
    @PostMapping("/return-requests/{id}/booking/book-now")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void bookNow(@PathVariable UUID id) {
        booking.bookNow(id);
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

    /** Step 5b (X6): exchange → refund (customer agreed), then approve. */
    @PostMapping("/return-requests/{id}/switch-to-refund")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void switchToRefund(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        requests.switchToRefundAndApprove(id, principal.userId());
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

    // ── Step 4d-2: refunds (owner / manager; a worker gets 403) ──────────────

    @GetMapping("/return-requests/{id}/refund-suggestion")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> refundSuggestion(@PathVariable UUID id) {
        return suggestions.suggest(id);
    }

    public record RecordRefundRequest(String method, java.math.BigDecimal amount, java.time.LocalDate refundedOn,
                                      String reference, String note) {}

    @PostMapping("/return-requests/{id}/refunds")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> recordRefund(@PathVariable UUID id, @RequestBody(required = false) RecordRefundRequest body,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
        RecordRefundRequest b = body == null ? new RecordRefundRequest(null, null, null, null, null) : body;
        UUID refundId = requests.recordRefund(id, b.method(), b.amount(), b.refundedOn(), b.reference(), b.note(),
            principal.userId());
        return Map.of("id", refundId.toString());
    }

    public record VoidRefundRequest(String note) {}

    @PostMapping("/return-requests/{id}/refunds/{refundId}/void")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void voidRefund(@PathVariable UUID id, @PathVariable UUID refundId,
                           @RequestBody(required = false) VoidRefundRequest body,
                           @AuthenticationPrincipal CustomUserDetails principal) {
        requests.voidRefund(id, refundId, body == null ? null : body.note(), principal.userId());
    }

    @PostMapping("/return-requests/{id}/mark-refunded")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRefunded(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        requests.markRefunded(id, principal.userId());
    }

    public record ItemArrivedRequest(String condition) {}

    /** Step 6a — an untracked item arrived (sellable / damaged). Owner / manager. */
    @PostMapping("/return-requests/{id}/items/{itemId}/arrived")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void itemArrived(@PathVariable UUID id, @PathVariable UUID itemId,
                            @RequestBody(required = false) ItemArrivedRequest body,
                            @AuthenticationPrincipal CustomUserDetails principal) {
        requests.itemArrived(id, itemId, body == null ? null : body.condition(), principal.userId());
    }

    @PostMapping("/return-requests/{id}/items/{itemId}/arrived/undo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undoItemArrived(@PathVariable UUID id, @PathVariable UUID itemId,
                                @AuthenticationPrincipal CustomUserDetails principal) {
        requests.undoItemArrived(id, itemId, principal.userId());
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

    /**
     * P1 — upload the portal logo (multipart field "file"). Saved at once (not via "Save changes"):
     * checked and re-encoded first (PNG, transparency kept, at most 600 px wide, no metadata), then
     * it replaces any previous upload. Errors are field errors on "logo": LOGO_REQUIRED,
     * LOGO_TOO_LARGE (over 2 MB), LOGO_TYPE (not PNG / JPG / WebP, whatever the file is called),
     * LOGO_PIXELS, LOGO_UNREADABLE. Answers the settings, as GET does.
     */
    @PutMapping("/tenant/portal-settings/logo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> putLogo(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file) {
        try {
            byte[] upload;
            if (file == null || file.isEmpty()) {
                upload = null;
            } else if (file.getSize() > PortalLogoService.MAX_BYTES) {
                // Refused before the bytes are read into memory.
                throw new PortalSettingsService.FieldException(HttpStatus.BAD_REQUEST, "logo", "LOGO_TOO_LARGE",
                    "The logo can be at most 2 MB.");
            } else {
                upload = file.getBytes();
            }
            logos.save(logos.process(upload));   // processed before the transaction opens
            return ResponseEntity.ok(settings.get());
        } catch (PortalSettingsService.FieldException e) {
            return fieldError(e);
        } catch (java.io.IOException e) {
            return fieldError(new PortalSettingsService.FieldException(HttpStatus.BAD_REQUEST, "logo",
                "LOGO_UNREADABLE", "This file couldn't be read as an image."));
        }
    }

    /** P1 — remove the uploaded logo (at once; the portal falls back to the Shopify link, else the store name). */
    @DeleteMapping("/tenant/portal-settings/logo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> deleteLogo() {
        logos.remove();
        return settings.get();
    }

    /**
     * P1 — the uploaded logo's bytes for the settings preview (works while the portal is off,
     * unlike the public endpoint). 404 when there is none.
     */
    @GetMapping("/tenant/portal-settings/logo")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> getLogo() {
        return logos.current()
            .map(a -> ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(a.info().contentType()))
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(a.bytes()))
            .orElseGet(() -> ResponseEntity.notFound().build());
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
