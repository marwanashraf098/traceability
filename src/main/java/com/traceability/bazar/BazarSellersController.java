package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B6b — seller invites. The event's Sellers card (owner / manager): list, candidates, add, a new link,
 * remove, allow workers. The seller's own: their first-open status, setting their PIN once, and the Switch seller
 * roster. Every endpoint answers 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}.
 */
@RestController
@RequestMapping("/api/v1")
public class BazarSellersController {

    private final BazarSellerService sellers;

    public BazarSellersController(BazarSellerService sellers) {
        this.sellers = sellers;
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @GetMapping("/bazar-events/{eventId}/sellers")
    public Map<String, Object> list(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.list(eventId, actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @GetMapping("/bazar-events/{eventId}/sellers/candidates")
    public List<Map<String, Object>> candidates(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.candidates(eventId, actor(p));
    }

    /** {name, phone?} → a new worker; {user_id} → an existing worker. Never a role. */
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/bazar-events/{eventId}/sellers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> add(@PathVariable UUID eventId, @RequestBody AddRequest req,
                                   @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.add(eventId, new BazarSellerService.AddSeller(req.userId(), req.name(), req.phone()), actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/bazar-events/{eventId}/sellers/{userId}/link")
    public Map<String, Object> newLink(@PathVariable UUID eventId, @PathVariable UUID userId,
                                       @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.newLink(eventId, userId, actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @DeleteMapping("/bazar-events/{eventId}/sellers/{userId}")
    public Map<String, Object> remove(@PathVariable UUID eventId, @PathVariable UUID userId,
                                      @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.remove(eventId, userId, actor(p));
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/bazar-events/{eventId}/sellers/allow-workers")
    public Map<String, Object> allowWorkers(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.allowWorkers(eventId, actor(p));
    }

    /** Any signed-in user of the tenant: the seller's first-open screen. */
    @GetMapping("/bazar-events/{eventId}/sellers/me")
    public Map<String, Object> me(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.me(eventId, actor(p));
    }

    /** Any seller of the event (or owner / manager): Switch seller's list. */
    @GetMapping("/bazar-events/{eventId}/sellers/roster")
    public List<Map<String, Object>> roster(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.roster(eventId, actor(p));
    }

    /** The invited worker sets their PIN, once. */
    @PostMapping("/bazar-sellers/me/pin")
    public Map<String, Object> setPin(@RequestBody PinRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return sellers.setPin(req.pin(), actor(p));
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }

    public record AddRequest(@com.fasterxml.jackson.annotation.JsonProperty("user_id")
                             @com.fasterxml.jackson.annotation.JsonAlias("userId") UUID userId,
                             String name, String phone) {}

    public record PinRequest(String pin) {}
}
