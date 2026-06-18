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
 * POST /api/v1/exceptions/resolve   — acknowledge / mark resolved (writes audit record)
 * GET  /api/v1/exceptions/resolutions — audit trail of resolved exceptions
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionService svc;

    public ExceptionController(ExceptionService svc) {
        this.svc = svc;
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
}
