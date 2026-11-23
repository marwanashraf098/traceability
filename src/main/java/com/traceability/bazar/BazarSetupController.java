package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar B9b — the guided bazar setup (BazarSetupService). Every endpoint answers 404 unless BAZAR_SIGNUP_ENABLED is on,
 * the store signed up to sell at a bazar and bazar is on for it. Picking and receiving: OWNER only; the status, the
 * "lower these" list / CSV and Done: OWNER / MANAGER (the reminder is shown to both).
 */
@RestController
@RequestMapping("/api/v1/bazar-setup")
public class BazarSetupController {

    private final BazarSetupService setup;

    public BazarSetupController(BazarSetupService setup) {
        this.setup = setup;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> status(@AuthenticationPrincipal CustomUserDetails principal) {
        return setup.status(isOwner(principal));
    }

    @GetMapping("/products")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> products() {
        return setup.products();
    }

    @PostMapping("/receive")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> receive(@RequestBody ReceiveRequest req,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        return setup.receive(req.eventId(), req.requestId(), req.lines(), principal.userId());
    }

    @GetMapping("/lower")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> lower() {
        return setup.lowerList();
    }

    @GetMapping("/lower.csv")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> lowerCsv(@RequestParam(required = false) String lang) {
        byte[] body = setup.lowerCsv(lang != null && lang.startsWith("ar")).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bazar-lower-these.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body);
    }

    @PostMapping("/lower-done")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> lowerDone(@AuthenticationPrincipal CustomUserDetails principal) {
        return setup.lowerDone(principal.userId(), isOwner(principal));
    }

    private static boolean isOwner(CustomUserDetails p) {
        return p != null && "owner".equalsIgnoreCase(p.role());
    }

    public record ReceiveRequest(UUID eventId, String requestId, List<BazarSetupService.Line> lines) {}
}
