package com.traceability.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * FR-2.6: Privileged-action audit log — single write path for all privileged actions.
 *
 * The audit_log table is append-only at the DB level (app_user has INSERT+SELECT only).
 * This service is the only write path; never INSERT into audit_log directly elsewhere.
 *
 * Caller must ensure TenantContext is set before calling record().
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
    }

    /**
     * Record a privileged action in the audit log.
     *
     * @param actorUserId  the user performing the action (null for system actions)
     * @param action       snake_case action name, e.g. "user_create", "convert_to_self_pickup"
     * @param targetType   entity type, e.g. "user", "order" — null if not entity-scoped
     * @param targetId     entity UUID as string — null if not entity-scoped
     * @param metadata     additional context (serialised to JSON); null allowed
     */
    public void record(UUID actorUserId, String action,
                       String targetType, String targetId,
                       Map<String, Object> metadata) {
        UUID tenantId = TenantContext.require();
        String metaJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metaJson = mapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("audit_log: failed to serialize metadata for action {}: {}", action, e.getMessage());
            }
        }
        jdbc.update(
            "INSERT INTO audit_log (tenant_id, actor_user_id, action, target_type, target_id, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            tenantId, actorUserId, action, targetType, targetId, metaJson);
    }

    /**
     * Paginated audit log for GET /api/v1/audit-log.
     *
     * Filters: action, actorUserId, from (inclusive), to (exclusive). All optional.
     * Returns {total, page, size, items[]}.
     */
    public Map<String, Object> list(String actionFilter, UUID actorFilter,
                                    Instant from, Instant to,
                                    int page, int size) {
        UUID tenantId = TenantContext.require();

        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE al.tenant_id = ?");
        params.add(tenantId);

        if (actionFilter != null && !actionFilter.isBlank()) {
            where.append(" AND al.action = ?");
            params.add(actionFilter);
        }
        if (actorFilter != null) {
            where.append(" AND al.actor_user_id = ?");
            params.add(actorFilter);
        }
        if (from != null) {
            where.append(" AND al.created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND al.created_at < ?");
            params.add(Timestamp.from(to));
        }

        String countSql = "SELECT COUNT(*) FROM audit_log al " + where;
        int total = jdbcInt(countSql, params.toArray());

        String dataSql =
            "SELECT al.id, al.actor_user_id, u.name AS actor_name, " +
            "       al.action, al.target_type, al.target_id, al.metadata, al.created_at " +
            "FROM audit_log al " +
            "LEFT JOIN users u ON u.id = al.actor_user_id " +
            where +
            " ORDER BY al.created_at DESC " +
            "LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(size);
        dataParams.add((long) page * size);

        List<Map<String, Object>> items = jdbc.queryForList(dataSql, dataParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page",  page);
        result.put("size",  size);
        result.put("items", items);
        return result;
    }

    private int jdbcInt(String sql, Object[] params) {
        Integer n = jdbc.queryForObject(sql, Integer.class, params);
        return n != null ? n : 0;
    }
}
