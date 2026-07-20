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
        if (isPieceQuery(trimmed)) {
            return service.lookupPiece(trimmed, isWorker);
        }
        return service.lookupTracking(trimmed);
    }

    /**
     * Returns true when the scanned string is a piece barcode rather than an AWB or order number.
     *
     * Two formats are recognised:
     *   • Legacy:  "PC-<ULID>" — the PC- prefix written by old label batches still in the field.
     *   • Current: bare 26-char Crockford base-32 ULID — encoded by labels after the barcode fix.
     *
     * Bosta AWBs are pure digits (≤13 chars); order numbers are short numeric strings.
     * Neither can collide with a 26-char Crockford string, so the length+alphabet check is
     * unambiguous without touching the database.
     */
    public static boolean isPieceQuery(String q) {
        if (q.startsWith("PC-")) return true;
        if (q.length() != 26) return false;
        for (int i = 0; i < 26; i++) {
            char c = q.charAt(i);
            // Crockford base-32 alphabet: 0-9, A-Z excluding I, L, O, U
            boolean valid = (c >= '0' && c <= '9')
                         || (c >= 'A' && c <= 'H')
                         || (c == 'J') || (c == 'K')
                         || (c == 'M') || (c == 'N')
                         || (c >= 'P' && c <= 'T')
                         || (c >= 'V' && c <= 'Z');
            if (!valid) return false;
        }
        return true;
    }
}
