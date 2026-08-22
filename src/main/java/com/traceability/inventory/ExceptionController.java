package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Exceptions center (FR-15.3).
 *
 * GET  /api/v1/exceptions           — paginated open-exception list, sorted CRITICAL→LOW then oldest
 * GET  /api/v1/exceptions/count     — open-exception count (shell notification bell)
 * POST /api/v1/exceptions/resolve   — acknowledge / mark resolved (writes audit record)
 * GET  /api/v1/exceptions/resolutions — audit trail of resolved exceptions
 * POST /api/v1/exceptions/void-hold-sync/repush — FR-13.x manual repush of a failed
 *      void_correction/hold_enter Shopify decrement (does NOT resolve the exception —
 *      call /resolve separately once the operator confirms the repush succeeded)
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionService        svc;
    private final ShopifyInventoryService shopifyInventory;

    public ExceptionController(ExceptionService svc, ShopifyInventoryService shopifyInventory) {
        this.svc = svc;
        this.shopifyInventory = shopifyInventory;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return svc.listExceptions(type, severity, page, Math.min(size, 200));
    }

    /**
     * count — unchanged shape/meaning, the shell nav badge keeps reading this key as-is.
     * critical/warning added alongside for the Overview dashboard's severity split
     * (CRITICAL → critical, HIGH+MEDIUM+LOW → warning) — backward-compatible addition.
     */
    @GetMapping("/count")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> count() {
        var counts = svc.countOpenExceptionsBySeverity();
        return Map.of("count", counts.total(), "critical", counts.critical(), "warning", counts.warning());
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(
            @RequestBody ResolveRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.resolve(req.exceptionType(), req.subjectKey(), principal.userId(), req.note());
    }

    @GetMapping("/resolutions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> resolutions(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return svc.listResolutions(page, Math.min(size, 200));
    }

    public record ResolveRequest(String exceptionType, String subjectKey, String note) {}

    public record RepushRequest(String triggerType, String triggerId) {}

    @PostMapping("/void-hold-sync/repush")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void repushVoidHoldSync(@RequestBody RepushRequest req) {
        shopifyInventory.repushFailedVoidOrHold(req.triggerType(), req.triggerId());
    }
}
