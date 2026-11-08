package com.traceability.portal;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S5a — the drawer's Shopify actions and the owner's switch. Every endpoint answers 404 for a
 * tenant not listed in TRACED_SHOPIFY_RETURNS_SYNC_TENANTS (GET of the switch answers {visible:false}), and 404 for a
 * request that isn't the caller's.
 */
@RestController
@RequestMapping("/api/v1")
public class ShopifyReturnSyncController {

    private final ShopifyReturnSyncService sync;
    private final ShopifyReturnSyncScheduler scheduler;
    private final ShopifyReturnSyncSettingsService settings;
    private final ShopifyReturnSyncRollout rollout;
    private final JdbcTemplate jdbc;

    public ShopifyReturnSyncController(ShopifyReturnSyncService sync, ShopifyReturnSyncScheduler scheduler,
                                       ShopifyReturnSyncSettingsService settings, ShopifyReturnSyncRollout rollout,
                                       JdbcTemplate jdbc) {
        this.sync = sync;
        this.scheduler = scheduler;
        this.settings = settings;
        this.rollout = rollout;
        this.jdbc = jdbc;
    }

    public record SwitchRequest(Boolean enabled) {}

    @GetMapping("/tenant/shopify-returns-sync")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> getSettings() {
        return settings.get();
    }

    @PutMapping("/tenant/shopify-returns-sync")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> putSettings(@RequestBody(required = false) SwitchRequest body,
                                           @AuthenticationPrincipal CustomUserDetails principal) {
        if (body == null || body.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        }
        return settings.set(body.enabled(), principal.userId());
    }

    /**
     * "Try again" (failed — also after the automatic attempts ran out / not sure it was created / not synced): a fresh
     * cycle, enqueued right away. A refusal is a coded 409 the drawer shows (never a silent no-op).
     */
    @PostMapping("/return-requests/{id}/shopify-sync/retry")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = own(id);
        Map<String, Object> out = sync.retry(tenantId, id, principal == null ? null : principal.userId());
        scheduler.nudge(tenantId, id);
        return out;
    }

    /** "Check again" — runs the next Shopify step now (e.g. look for an unconfirmed return). */
    @PostMapping("/return-requests/{id}/shopify-sync/check")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void check(@PathVariable UUID id) {
        UUID tenantId = own(id);
        scheduler.nudge(tenantId, id);
    }

    /** "I checked it in Shopify" — dismisses what waits for a person. */
    @PostMapping("/return-requests/{id}/shopify-sync/checked")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checked(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = own(id);
        sync.markChecked(tenantId, id, principal.userId());
    }

    private UUID own(UUID id) {
        UUID tenantId = TenantContext.require();
        if (!rollout.listed(tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE id = ? AND tenant_id = ?)", Boolean.class, id, tenantId);
        if (!Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return tenantId;
    }
}
