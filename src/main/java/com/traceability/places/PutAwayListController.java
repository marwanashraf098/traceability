package com.traceability.places;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Warehouse places S4 — put-away lists (PutAwayListService). Every role, like S2's put away — except a transfer's
 * list, which is owner / manager like every transfer screen (403 for a worker — decided in the
 * service after the places switch, so a tenant without places gets 404 for every role). An unknown flow / another
 * tenant's → 404 SOURCE_NOT_FOUND. The session's scans and close are S2's endpoints.
 *
 *   GET  /api/v1/put-away/lists/{source}/{sourceId}            the list (source: receiving | tag_session |
 *                                                              return_session | transfer) + the caller's open session
 *   POST /api/v1/put-away/lists/{source}/{sourceId}/sessions   start, or resume the caller's open session on it
 *   GET  /api/v1/put-away/sessions/{id}/list                   the list behind the caller's own session (scoped: false
 *                                                              for a manual one)
 */
@RestController
@RequestMapping("/api/v1/put-away")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class PutAwayListController {

    private final PutAwayListService service;

    public PutAwayListController(PutAwayListService service) {
        this.service = service;
    }

    @GetMapping("/lists/{source}/{sourceId}")
    public PutAwayListService.PutAwayList list(@PathVariable String source, @PathVariable UUID sourceId,
                                               @AuthenticationPrincipal CustomUserDetails principal) {
        return service.list(source, sourceId, principal.userId(), isManager(principal));
    }

    @PostMapping("/lists/{source}/{sourceId}/sessions")
    public PutAwayService.Session start(@PathVariable String source, @PathVariable UUID sourceId,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return service.start(source, sourceId, principal.userId(), isManager(principal));
    }

    @GetMapping("/sessions/{id}/list")
    public PutAwayListService.SessionList sessionList(@PathVariable UUID id,
                                                      @AuthenticationPrincipal CustomUserDetails principal) {
        return service.sessionList(id, principal.userId());
    }

    private static boolean isManager(CustomUserDetails principal) {
        return "owner".equalsIgnoreCase(principal.role()) || "manager".equalsIgnoreCase(principal.role());
    }
}
