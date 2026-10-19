package com.traceability.courier;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Non-Bosta couriers G2 — Settings → Couriers. Owner + manager read and test; only the owner changes the switch
 * or the default carrier (audit-logged, {@link CourierSettingsService}).
 */
@RestController
@RequestMapping("/api/v1/settings/couriers")
public class CourierSettingsController {

    /** catchUpDays: only with a switch-on — null = start from now (default), 1–7 = also the last N days. */
    public record UpdateRequest(Boolean enabled, String defaultCarrier, Integer catchUpDays) {}

    public record TestRequest(String code) {}

    private final CourierSettingsService settings;
    private final TestWaybillService testWaybill;

    public CourierSettingsController(CourierSettingsService settings, TestWaybillService testWaybill) {
        this.settings = settings;
        this.testWaybill = testWaybill;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public CourierSettingsService.Settings get() {
        return settings.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public CourierSettingsService.Settings update(@RequestBody UpdateRequest req,
                                                  @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        }
        return settings.update(principal.userId(), req.enabled(), req.defaultCarrier(), req.catchUpDays());
    }

    /** What "also include orders from the last N days" would create now (the dry run) — counts per carrier. */
    @GetMapping("/catch-up/preview")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public java.util.Map<String, Object> catchUpPreview(
            @RequestParam(defaultValue = "" + GenericCatchUp.DEFAULT_DAYS) int days) {
        GenericCatchUp.DryRun d = settings.catchUpPreview(days);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("days", days);
        out.put("wouldCreate", d.wouldCreate());
        out.put("keyInUse", d.keyInUse());
        out.put("byCarrier", d.byCarrier());
        return out;
    }

    /** "Test a waybill" — read-only. */
    @PostMapping("/test-waybill")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public TestWaybillService.Result test(@RequestBody TestRequest req,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }
        if (req.code().length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is too long");
        }
        return testWaybill.test(req.code(), principal.userId());
    }
}
