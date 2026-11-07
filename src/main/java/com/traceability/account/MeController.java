package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * GET /api/v1/me — the caller's own identity (name/email/role), tenant-scoped.
 * Any authenticated role, including Worker — this is self-lookup, not a list.
 * Also carries {@code shopifyUpgradeStage} (ShopifyUpgradeNoticeService) — the app-wide upgrade notice,
 * and {@code uiState} (onboarding O1: this user's tours done / pages opened).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService                 svc;
    private final ShopifyUpgradeNoticeService upgradeNotice;
    private final UserUiStateService          uiState;

    public MeController(UserService svc, ShopifyUpgradeNoticeService upgradeNotice, UserUiStateService uiState) {
        this.svc           = svc;
        this.upgradeNotice = upgradeNotice;
        this.uiState       = uiState;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(@AuthenticationPrincipal CustomUserDetails principal) {
        Map<String, Object> self = svc.getSelf(principal.userId());
        if (self == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        // App-wide Shopify upgrade notice: 'upgrade' | 'delete_old_app' | null (always null for a worker).
        self.put("shopifyUpgradeStage", upgradeNotice.stage((String) self.get("role")));
        return self;
    }

    /**
     * POST /api/v1/me/ui-state {tourDone?, pageSeen?} — onboarding O1. Any role (workers take the tours of
     * their own screens). Whitelisted ids only (UserUiStateService); an idempotent append for the caller's
     * own user id. Returns the updated {toursDone, pagesSeen}.
     */
    @PostMapping("/ui-state")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> recordUiState(@RequestBody(required = false) Map<String, Object> body,
                                             @AuthenticationPrincipal CustomUserDetails principal) {
        return uiState.record(principal.userId(), body);
    }
}
