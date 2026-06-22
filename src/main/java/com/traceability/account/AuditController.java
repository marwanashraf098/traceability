package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditController {

    private final AuditService svc;

    public AuditController(AuditService svc) {
        this.svc = svc;
    }

    /**
     * GET /api/v1/audit-log
     * Owner/Manager only — Workers have no access to audit history.
     *
     * Query params (all optional):
     *   action  — filter by action name (exact match)
     *   actor   — filter by actor user UUID
     *   from    — ISO-8601 instant, inclusive
     *   to      — ISO-8601 instant, exclusive
     *   page    — 0-based (default 0)
     *   size    — page size (default 50, max 200)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal CustomUserDetails principal) {

        int safeSize = Math.min(size, 200);
        return svc.list(action, actor, from, to, page, safeSize);
    }
}
