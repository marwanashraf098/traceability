package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/lookup")
public class LookupController {

    private final LookupService service;

    public LookupController(LookupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
    public Map<String, Object> lookup(
            @RequestParam String q,
            @AuthenticationPrincipal CustomUserDetails principal) {
        boolean isWorker = "worker".equalsIgnoreCase(principal.role());
        String trimmed = q.trim();
        if (trimmed.startsWith("PC-")) {
            return service.lookupPiece(trimmed, isWorker);
        }
        return service.lookupTracking(trimmed);
    }
}
