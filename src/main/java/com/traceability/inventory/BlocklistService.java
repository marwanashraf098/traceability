package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class BlocklistService {

    private final JdbcTemplate       jdbc;
    private final AuditService        audit;
    private final TransactionTemplate tx;

    public BlocklistService(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc  = jdbc;
        this.audit = audit;
        this.tx    = new TransactionTemplate(txm);
    }

    public record BlocklistEntry(String id, String phoneCanonical, String reason,
                                  String source, String createdBy, Instant createdAt) {}

    // ── List ─────────────────────────────────────────────────────────────────

    public List<BlocklistEntry> list() {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> jdbc.query(
            "SELECT b.id, b.phone_canonical, b.reason, b.source::text, " +
            "       u.name AS created_by_name, b.created_at " +
            "FROM blocklist b " +
            "LEFT JOIN users u ON u.id = b.created_by " +
            "WHERE b.tenant_id = ? AND b.active = true " +
            "ORDER BY b.created_at DESC",
            (rs, i) -> new BlocklistEntry(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("phone_canonical"),
                rs.getString("reason"),
                rs.getString("source"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant()),
            tenantId));
    }

    // ── Add ──────────────────────────────────────────────────────────────────

    public BlocklistEntry add(String rawPhone, String reason, UUID actorUserId) {
        if (reason == null || reason.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");

        String canonical = ShipmentLinkService.normalizePhone(rawPhone);
        if (canonical == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Egyptian phone number — expected 01XXXXXXXXX or international equivalent");

        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            // Upsert: if already exists as inactive → reactivate; if active → conflict
            List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, active FROM blocklist WHERE tenant_id = ? AND phone_canonical = ?",
                tenantId, canonical);

            UUID entryId;
            if (!existing.isEmpty()) {
                entryId = (UUID) existing.get(0).get("id");
                boolean isActive = (boolean) existing.get(0).get("active");
                if (isActive)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Phone " + canonical + " is already on the blocklist");
                // Reactivate
                jdbc.update(
                    "UPDATE blocklist SET active = true, reason = ?, created_by = ?, created_at = now() " +
                    "WHERE id = ?",
                    reason, actorUserId, entryId);
            } else {
                entryId = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO blocklist (id, tenant_id, phone_canonical, reason, source, created_by) " +
                    "VALUES (?, ?, ?, ?, 'manual', ?)",
                    entryId, tenantId, canonical, reason, actorUserId);
            }

            audit.record(actorUserId, "blocklist_add", "blocklist", entryId.toString(),
                Map.of("phone", canonical, "reason", reason));

            return new BlocklistEntry(entryId.toString(), canonical, reason, "manual", null, Instant.now());
        });
    }

    // ── Remove (soft-delete) ──────────────────────────────────────────────────

    public void remove(UUID entryId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        tx.execute(s -> {
            int updated = jdbc.update(
                "UPDATE blocklist SET active = false WHERE id = ? AND tenant_id = ? AND active = true",
                entryId, tenantId);
            if (updated == 0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Blocklist entry not found");

            audit.record(actorUserId, "blocklist_remove", "blocklist", entryId.toString(), null);
            return null;
        });
    }

    // ── Gate check ───────────────────────────────────────────────────────────

    /**
     * Checks whether the given canonical phone is on the active blocklist for the current tenant.
     * Returns the block reason if found, null otherwise.
     * Caller must have TenantContext set; runs inside the caller's tx or opens its own.
     */
    public String isBlocked(String canonical, UUID tenantId) {
        if (canonical == null) return null;
        return jdbc.query(
            "SELECT reason FROM blocklist " +
            "WHERE tenant_id = ? AND phone_canonical = ? AND active = true LIMIT 1",
            rs -> rs.next() ? rs.getString("reason") : null,
            tenantId, canonical);
    }

    /**
     * Checks order phone against blocklist and, if blocked, sets on_hold=true.
     * No-op if phone is null (pre-PCD), or if order is already on hold.
     *
     * @param orderId   the order to potentially hold
     * @param rawPhone  raw phone from Shopify/Bosta (may be null)
     * @param tenantId  current tenant
     */
    public void checkAndHoldIfBlocked(UUID orderId, String rawPhone, UUID tenantId) {
        if (rawPhone == null || rawPhone.isBlank()) return;
        String canonical = ShipmentLinkService.normalizePhone(rawPhone);
        if (canonical == null) return;

        String reason = isBlocked(canonical, tenantId);
        if (reason == null) return;

        jdbc.update(
            "UPDATE orders SET on_hold = true, hold_reason = ? " +
            "WHERE id = ? AND tenant_id = ? AND on_hold = false",
            "blocked_customer: " + reason, orderId, tenantId);
    }
}
