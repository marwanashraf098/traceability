package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin/ops one-shot incident reversals. Not linked from any frontend page — Marawan
 * triggers these directly after reviewing the corresponding diagnosis, same shape as
 * {@link ShopifyInventorySyncController}'s OWNER-only operator actions.
 */
@RestController
@RequestMapping("/api/v1/admin/incidents")
@PreAuthorize("hasRole('OWNER')")
public class IncidentReversalController {

    private final PickErrorReversalService pickErrorReversalService;

    public IncidentReversalController(PickErrorReversalService pickErrorReversalService) {
        this.pickErrorReversalService = pickErrorReversalService;
    }

    /**
     * One-shot reversal of the 2026-08-23 erroneous pick/pack on order #2212102474
     * (piece 01KZ1JSQRY1JSKZPTNB126AP7M). Hardcoded to this single incident — see
     * {@link PickErrorReversalService}. Idempotent only in the sense that a second call
     * will throw StateConflictException (piece is no longer awaiting_pickup) rather than
     * silently no-op or double-apply.
     */
    @PostMapping("/2212102474-pick-error/reverse")
    public Map<String, Object> reversePickError20260823(
            @AuthenticationPrincipal CustomUserDetails principal) {
        pickErrorReversalService.reverse(principal.userId());
        return Map.of("status", "reversed", "order", "2212102474", "piece", "01KZ1JSQRY1JSKZPTNB126AP7M");
    }
}
