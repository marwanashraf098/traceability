package com.traceability.bazar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS — the ONE answer to "does this tenant have bazar": {@code tenants.bazar_enabled} (V231; default true
 * since V244 — ops can still switch a tenant off). Off → every bazar endpoint answers 404 BAZAR_NOT_ENABLED.
 */
public final class Bazar {

    private Bazar() {}

    /** A tenants row the caller can't see (no / another tenant context under RLS) reads as off, never an error. */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        List<Boolean> on = jdbc.queryForList(
            "SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }

    /**
     * B6 — GET /me's {@code bazarLive}: the tenant's live bazar event the caller may sell at (their role is in the
     * event's {@code seller_roles}) and wasn't removed from (B6b), else null. Several live events (one per location) → the most recently started.
     * {@code day} / {@code dayCount}: which day of the event today (Africa/Cairo) is; {@code businessDay}: today's
     * Cairo date (the phone filters "today's" sales by it); {@code paymentMethods}: the event's enabled methods
     * (a worker can't read the event itself — the sell screen's method chips come from here).
     */
    public static Map<String, Object> liveFor(JdbcTemplate jdbc, UUID tenantId, UUID userId, String role) {
        if (role == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.name, e.starts_on, e.ends_on, e.payment_methods, " +
            "       ((now() AT TIME ZONE 'Africa/Cairo')::date)::text AS business_day " +
            "FROM bazar_events e WHERE e.tenant_id = ? AND e.status = 'live' AND e.deleted_at IS NULL AND ? = ANY (e.seller_roles) " +
            "  AND NOT " + BazarSellers.REMOVED_FROM_E_SQL + " " +
            "ORDER BY e.started_at DESC NULLS LAST, e.created_at DESC, e.id DESC LIMIT 1",
            tenantId, role.toLowerCase(), userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        LocalDate start = ((java.sql.Date) r.get("starts_on")).toLocalDate();
        LocalDate end   = ((java.sql.Date) r.get("ends_on")).toLocalDate();
        LocalDate today = LocalDate.parse((String) r.get("business_day"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", r.get("id"));
        out.put("name", r.get("name"));
        out.put("day", ChronoUnit.DAYS.between(start, today) + 1);
        out.put("dayCount", ChronoUnit.DAYS.between(start, end) + 1);
        out.put("businessDay", r.get("business_day"));
        out.put("paymentMethods", toList(r.get("payment_methods")));
        return out;
    }

    private static List<String> toList(Object pgArray) {
        try {
            if (pgArray instanceof Array a) return List.of((String[]) a.getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return List.of();
    }
}
