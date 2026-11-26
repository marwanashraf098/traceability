package com.traceability.setup;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Wizard entry — PUT /api/v1/setup/purpose {purpose: 'orders' | 'bazar' | 'both'}, owner only, once. */
@RestController
@RequestMapping("/api/v1/setup/purpose")
public class SetupPurposeController {

    private final SetupPurposeService service;

    public SetupPurposeController(SetupPurposeService service) {
        this.service = service;
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> set(@RequestBody PurposeRequest req, @AuthenticationPrincipal CustomUserDetails principal) {
        return service.set(principal.userId(), req == null ? null : req.purpose());
    }

    public record PurposeRequest(String purpose) {}
}
