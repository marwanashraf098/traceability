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
        // A piece scan typed with Caps Lock or an Arabic layout is routed and looked up in its
        // normalized form (ScanInputNormalizer). Order and tracking queries keep the trimmed text.
        String piece = ScanInputNormalizer.normalizePiece(trimmed);
        if (isPieceQuery(piece)) {
            return service.lookupPiece(piece, isWorker);
        }
        // Warehouse places S2: a place sticker (Q + 6 digits) of a tenant with places answers "that's a place";
        // anything else carries on exactly as before (no tracking number or order is shaped like one).
        String placeSticker = ScanInputNormalizer.normalizePlace(trimmed);
        if (placeSticker != null) {
            Map<String, Object> place = service.lookupPlaceSticker(placeSticker);
            if (place != null) return place;
        }
        // '#'-prefixed is unambiguously an order query — no tracking-number format uses '#'.
        if (trimmed.startsWith("#")) {
            return service.lookupOrder(trimmed);
        }
        // Bare digits / an arbitrary string are ambiguous — a Bosta AWB can also be a plain
        // digit string (see isPieceQuery()'s javadoc). Preserve every existing tracking lookup
        // unchanged by trying it FIRST; only fall back to an order-number lookup (bare '1042',
        // or a full custom Shopify order name) when tracking finds nothing.
        try {
            return service.lookupTracking(trimmed);
        } catch (LookupNotFoundException e) {
            return service.lookupOrder(trimmed);
        }
    }

    /**
     * Returns true when the scanned string is a piece barcode rather than an AWB or order number.
     *
     * Three formats are recognised:
     *   • Short code: "P" + 6 or more digits (e.g. "P000001", "P1000000") — FR-19 labels,
     *     0.333mm/module. 6 is batchReceive's MINIMUM width (%06d): a tenant's millionth piece is
     *     P1000000. Same shape as ScanInputNormalizer and WaybillResolver.looksLikePieceCode.
     *   • Legacy PC-: "PC-<ULID>" — old label batches still in field use.
     *   • Bare ULID:  26-char Crockford base-32 string — labels after the MARGIN=0 fix, pre-FR-19.
     *
     * Bosta AWBs are pure digits or hub-prefixed strings with dashes (e.g. "D-07-2944282510").
     * Neither can collide with any of the three piece formats above — the routing table is clean.
     */
    public static boolean isPieceQuery(String q) {
        if (q.startsWith("PC-")) return true;
        // Short code: "P" followed by 6 or more digits (length 7+)
        if (q.length() >= 7 && q.charAt(0) == 'P') {
            boolean allDigits = true;
            for (int i = 1; i < q.length(); i++) {
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
