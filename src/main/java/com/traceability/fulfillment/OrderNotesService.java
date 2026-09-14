package com.traceability.fulfillment;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order Notes — append-only free-text notes attached to an order (drawer Notes tab).
 * Add-only this pass: no edit, no delete. Author is resolved at read time via
 * created_by -> users.name, the same convention OrderController.timeline() uses for
 * piece_events.actor_user_id -> users.name.
 */
@Service
public class OrderNotesService {

    private static final int MAX_BODY_LENGTH = 2000;

    private final JdbcTemplate jdbc;

    public OrderNotesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OrderNote(String id, String body, String authorName, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<OrderNote> list(UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOrder(orderId, tenantId);

        return jdbc.query(
            "SELECT n.id, n.body, n.created_at, u.name AS author_name " +
            "FROM order_notes n " +
            "LEFT JOIN users u ON u.id = n.created_by " +
            "WHERE n.order_id = ? AND n.tenant_id = ? " +
            "ORDER BY n.created_at DESC, n.id DESC",
            (rs, i) -> new OrderNote(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("body"),
                rs.getString("author_name"),
                rs.getTimestamp("created_at").toInstant()),
            orderId, tenantId);
    }

    @Transactional
    public OrderNote add(UUID orderId, String body, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOrder(orderId, tenantId);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must not be blank");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "body must be " + MAX_BODY_LENGTH + " characters or fewer");
        }

        String authorName = jdbc.query(
            "SELECT name FROM users WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            actorUserId, tenantId);

        UUID noteId = UUID.randomUUID();
        Timestamp createdAt = jdbc.queryForObject(
            "INSERT INTO order_notes (id, tenant_id, order_id, body, created_by) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING created_at",
            Timestamp.class, noteId, tenantId, orderId, trimmed, actorUserId);

        return new OrderNote(noteId.toString(), trimmed, authorName, createdAt.toInstant());
    }

    private void requireOrder(UUID orderId, UUID tenantId) {
        Boolean exists = jdbc.query(
            "SELECT EXISTS(SELECT 1 FROM orders WHERE id = ? AND tenant_id = ?)",
            rs -> { rs.next(); return rs.getBoolean(1); },
            orderId, tenantId);
        if (exists == null || !exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }
}
