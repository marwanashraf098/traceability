package com.traceability.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * FR-24: trimmed to the one endpoint that survives the session-based rebuild.
 * intake/pieces-{id}-restock/pieces-{id}-damage/never-received were the old
 * three-tab UI's endpoints, retired along with it (superseded by
 * ReturnSessionController's scan/disposition/analytics). This one stays because
 * Overview's "Awaiting Inspection" tile depends on it (api.ts's
 * getReturnsPendingTotal()) and it's semantically distinct from the new
 * analytics "unassigned pending" count — see ReturnSessionService.analytics()'s
 * javadoc: countPending() is ALL pieces at return_pending_inspection, unassigned
 * pending is those not claimed by any session. Do not repoint Overview to
 * analytics — the numbers mean different things.
 */
@RestController
@RequestMapping("/api/v1/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    /** Manager view: count of all pieces currently at return_pending_inspection. */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PendingPage pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        long total = returnService.countPending();
        return new PendingPage(List.of(), total);
    }

    public record PendingPage(List<Map<String, Object>> items, long total) {}
}
