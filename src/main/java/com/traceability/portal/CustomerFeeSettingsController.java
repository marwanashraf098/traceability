package com.traceability.portal;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Returns portal fees F1 — Settings → Returns portal → Customer fees + Exchange pricing. Owner + manager read,
 * owner writes (money). Data only in F1 — see {@link CustomerFeeSettingsService}.
 */
@RestController
@RequestMapping("/api/v1/tenant/portal-settings/customer-fees")
public class CustomerFeeSettingsController {

    public record ReasonsRequest(List<String> reasons) {}

    private final CustomerFeeSettingsService fees;

    public CustomerFeeSettingsController(CustomerFeeSettingsService fees) {
        this.fees = fees;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public CustomerFeeSettingsService.Settings get() {
        return fees.get();
    }

    /** Bosta cities, or the districts of {@code cityId} — for the area override picker. */
    @GetMapping("/areas")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<CustomerFeeSettingsService.Area> areas(@RequestParam(value = "cityId", required = false) String cityId) {
        return fees.areas(cityId);
    }

    @PutMapping("/rules/{kind}")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings putRule(@PathVariable String kind,
                                                       @RequestBody(required = false) CustomerFeeSettingsService.RuleInput body,
                                                       @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.putRule(principal.userId(), kind, body);
    }

    @PutMapping("/merchant-fault-reasons")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings putReasons(@RequestBody(required = false) ReasonsRequest body,
                                                          @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.putMerchantFaultReasons(principal.userId(), body == null ? null : body.reasons());
    }

    @PutMapping("/exchange-pricing")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings putExchangePricing(
            @RequestBody(required = false) CustomerFeeSettingsService.ExchangePricingInput body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.putExchangePricing(principal.userId(), body);
    }

    @PostMapping("/area-overrides")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings createOverride(
            @RequestBody(required = false) CustomerFeeSettingsService.OverrideInput body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.createOverride(principal.userId(), body);
    }

    @PutMapping("/area-overrides/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings updateOverride(
            @PathVariable UUID id, @RequestBody(required = false) CustomerFeeSettingsService.OverrideInput body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.updateOverride(principal.userId(), id, body);
    }

    @DeleteMapping("/area-overrides/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerFeeSettingsService.Settings deleteOverride(@PathVariable UUID id,
                                                              @AuthenticationPrincipal CustomUserDetails principal) {
        return fees.deleteOverride(principal.userId(), id);
    }
}
