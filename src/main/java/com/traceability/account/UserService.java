package com.traceability.account;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * FR-2.2: User CRUD — create, update role/name, deactivate.
 *
 * Rules enforced:
 *   - Manager cannot create or modify an Owner.
 *   - Deactivate only — no hard delete (custody history references users).
 *   - Deactivated users are excluded from auth_lookup_user (active = true filter in V1).
 *   - Workers get a PIN; non-Workers get a password.
 *   - Every mutation writes to audit_log.
 */
@Service
public class UserService {

    private final JdbcTemplate    jdbc;
    private final PasswordEncoder encoder;
    private final AuditService    audit;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc    = jdbc;
        this.encoder = encoder;
        this.audit   = audit;
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT id, name, email, role, active, created_at " +
            "FROM users WHERE tenant_id = ? ORDER BY created_at",
            tenantId);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> create(UUID actorUserId, String actorRole,
                                      String name, String email,
                                      String role, String password, String pin) {
        UUID tenantId = TenantContext.require();
        validateTargetRoleAccess(actorRole, role, "create");

        // role validation
        if (!Set.of("owner", "manager", "worker").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "role must be owner, manager, or worker");
        }

        String passwordHash = null;
        String pinHash      = null;

        if ("worker".equals(role)) {
            if (pin == null || pin.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workers require a PIN");
            }
            pinHash = encoder.encode(pin);
        } else {
            if (password == null || password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Non-worker users require a password");
            }
            if (password.length() < 8) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 8 characters");
            }
            passwordHash = encoder.encode(password);
        }

        UUID newId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, pin_code, role) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::user_role)",
            newId, tenantId, name, email, passwordHash, pinHash, role);

        audit.record(actorUserId, "user_create", "user", newId.toString(),
            Map.of("name", name, "role", role, "email", email != null ? email : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",   newId.toString());
        result.put("name", name);
        result.put("role", role);
        return result;
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public void update(UUID actorUserId, String actorRole, UUID targetId,
                       String name, String role) {
        UUID tenantId = TenantContext.require();

        String currentRole = jdbc.query(
            "SELECT role FROM users WHERE id = ? AND tenant_id = ? AND active = true",
            rs -> rs.next() ? rs.getString("role") : null,
            targetId, tenantId);
        if (currentRole == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        // Cannot touch an Owner unless actor is also Owner
        validateTargetRoleAccess(actorRole, currentRole, "modify");
        if (role != null) {
            validateTargetRoleAccess(actorRole, role, "assign role");
        }

        if (name != null && !name.isBlank()) {
            jdbc.update("UPDATE users SET name = ? WHERE id = ? AND tenant_id = ?",
                name, targetId, tenantId);
        }
        if (role != null && !role.isBlank()) {
            if (!Set.of("owner", "manager", "worker").contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be owner, manager, or worker");
            }
            jdbc.update("UPDATE users SET role = ?::user_role WHERE id = ? AND tenant_id = ?",
                role, targetId, tenantId);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (name != null) meta.put("new_name", name);
        if (role != null) meta.put("new_role", role);
        meta.put("previous_role", currentRole);
        audit.record(actorUserId, "user_update", "user", targetId.toString(), meta);
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    @Transactional
    public void deactivate(UUID actorUserId, String actorRole, UUID targetId) {
        UUID tenantId = TenantContext.require();

        String currentRole = jdbc.query(
            "SELECT role FROM users WHERE id = ? AND tenant_id = ? AND active = true",
            rs -> rs.next() ? rs.getString("role") : null,
            targetId, tenantId);
        if (currentRole == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "User not found or already deactivated");
        }

        // Manager cannot deactivate an Owner
        validateTargetRoleAccess(actorRole, currentRole, "deactivate");

        // An owner cannot deactivate themselves (would lock them out)
        if (actorUserId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot deactivate your own account");
        }

        jdbc.update("UPDATE users SET active = false WHERE id = ? AND tenant_id = ?",
            targetId, tenantId);

        audit.record(actorUserId, "user_deactivate", "user", targetId.toString(),
            Map.of("deactivated_role", currentRole));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateTargetRoleAccess(String actorRole, String targetRole, String verb) {
        if ("manager".equals(actorRole) && "owner".equals(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Managers cannot " + verb + " an Owner account");
        }
    }
}
