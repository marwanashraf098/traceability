package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-2.2: User CRUD endpoints.
 *
 * Permission rules (enforced here and in UserService):
 *   GET /users          — Owner, Manager
 *   POST /users         — Owner (any role); Manager (Worker/Manager only, not Owner)
 *   PATCH /users/{id}   — Owner (any target); Manager (non-Owner targets only)
 *   POST /{id}/deactivate — Owner (any target); Manager (non-Owner targets only)
 *   DELETE endpoint     — NONE. Deactivate only, never hard-delete.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService svc;

    public UserController(UserService svc) {
        this.svc = svc;
    }

    public record CreateUserRequest(String name, String email, String role,
                                    String password, String pin) {}

    public record UpdateUserRequest(String name, String role) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> list(@AuthenticationPrincipal CustomUserDetails principal) {
        return svc.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @RequestBody CreateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.create(
            principal.userId(), principal.role(),
            req.name(), req.email(), req.role(), req.password(), req.pin());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @PathVariable UUID id,
            @RequestBody UpdateUserRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.update(principal.userId(), principal.role(), id, req.name(), req.role());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal CustomUserDetails principal) {
        svc.deactivate(principal.userId(), principal.role(), id);
    }
}
