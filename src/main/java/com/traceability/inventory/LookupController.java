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
     * Three formats are recognised:
     *   • Short code: "P" + exactly 6 digits (e.g. "P000001") — FR-19 labels, 0.333mm/module.
     *   • Legacy PC-: "PC-<ULID>" — old label batches still in field use.
     *   • Bare ULID:  26-char Crockford base-32 string — labels after the MARGIN=0 fix, pre-FR-19.
     *
     * Bosta AWBs are pure digits or hub-prefixed strings with dashes (e.g. "D-07-2944282510").
     * Neither can collide with any of the three piece formats above — the routing table is clean.
     */
    public static boolean isPieceQuery(String q) {
        if (q.startsWith("PC-")) return true;
        // Short code: exactly "P" followed by 6 digits (length 7)
        if (q.length() == 7 && q.charAt(0) == 'P') {
            boolean allDigits = true;
            for (int i = 1; i < 7; i++) {
                if (q.charAt(i) < '0' || q.charAt(i) > '9') { allDigits = false; break; }
            }
            if (allDigits) return true;
        }
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
