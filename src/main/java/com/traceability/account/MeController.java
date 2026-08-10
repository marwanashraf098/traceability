package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * GET /api/v1/me — the caller's own identity (name/email/role), tenant-scoped.
 * Any authenticated role, including Worker — this is self-lookup, not a list.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService svc;

    public MeController(UserService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(@AuthenticationPrincipal CustomUserDetails principal) {
        Map<String, Object> self = svc.getSelf(principal.userId());
        if (self == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return self;
    }
}
