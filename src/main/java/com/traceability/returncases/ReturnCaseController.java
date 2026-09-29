package com.traceability.returncases;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The combined "Returns & exchanges" list (Step 1, backend only — the current Exchanges &
 * Refunds page and its endpoints are unchanged). Owner and manager only, tenant-scoped via
 * TenantContext + RLS. See {@link ReturnCaseService}.
 */
@RestController
@RequestMapping("/api/v1/returns-exchanges")
public class ReturnCaseController {

    private final ReturnCaseService cases;

    public ReturnCaseController(ReturnCaseService cases) {
        this.cases = cases;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> list(@RequestParam(required = false) String stage,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String tile,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "25") int limit) {
        return cases.list(new ReturnCaseService.Filter(stage, type, tile, q), cursor, limit);
    }

    @GetMapping("/counts")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> counts(@RequestParam(required = false) String type,
                                      @RequestParam(required = false) String q) {
        return cases.counts(type, q);
    }
}
