package com.traceability.account;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.util.*;

/**
 * Onboarding O1 (V220) — per-user UI state: first-time tours done (or skipped) and sidebar pages opened
 * (the "blue dot" for pages never opened). The ONE writer of {@code user_ui_state}.
 *
 * Keyed by the CALLER's own user id from the JWT — never a request field. On a station tablet the PIN
 * switch (POST /auth/pin) mints a token for the switched-in worker, so their state is theirs, not the
 * signed-in owner's. Ids are whitelisted ({@link #TOURS}, {@link #PAGES}); anything else → 400, nothing
 * written. Writes are an idempotent append: a repeat leaves the arrays unchanged.
 */
@Service
public class UserUiStateService {

    /** First-time tours (O5). */
    public static final Set<String> TOURS = Set.of(
        "pickups", "scan_returns", "stock_take", "transfers", "pick_pack_mode");

    /** Sidebar routes (Sidebar.tsx: owner / manager items, the worker items, Settings, Analytics). */
    public static final Set<String> PAGES = Set.of(
        "overview", "exceptions", "orders", "fulfill", "pickups", "inventory", "receiving", "put_away",
        "transfers", "stock_take", "exchanges", "returns", "settings", "worker_home", "analytics");

    static final Set<String> BODY_KEYS = Set.of("tourDone", "pageSeen");

    private final JdbcTemplate jdbc;

    public UserUiStateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {toursDone, pagesSeen} for one user — empty lists when the user has no row yet. Caller's transaction. */
    public static Map<String, Object> read(JdbcTemplate jdbc, UUID tenantId, UUID userId) {
        Map<String, Object> out = jdbc.query(
            "SELECT tours_done, pages_seen FROM user_ui_state WHERE tenant_id = ? AND user_id = ?",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("toursDone", list(rs.getArray(1)));
                m.put("pagesSeen", list(rs.getArray(2)));
                return m;
            },
            tenantId, userId);
        if (out != null) return out;
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("toursDone", List.of());
        empty.put("pagesSeen", List.of());
        return empty;
    }

    @Transactional
    public Map<String, Object> record(UUID userId, Map<String, Object> body) {
        UUID tenantId = TenantContext.require();
        if (body == null || body.isEmpty()) throw bad("give tourDone or pageSeen");
        for (String k : body.keySet()) if (!BODY_KEYS.contains(k)) throw bad("unknown field: " + k);
        String tour = id(body.get("tourDone"), TOURS, "tourDone", body.containsKey("tourDone"));
        String page = id(body.get("pageSeen"), PAGES, "pageSeen", body.containsKey("pageSeen"));

        jdbc.update("""
            INSERT INTO user_ui_state (tenant_id, user_id, tours_done, pages_seen)
            VALUES (?, ?,
                    CASE WHEN ?::text IS NULL THEN '{}'::text[] ELSE ARRAY[?::text] END,
                    CASE WHEN ?::text IS NULL THEN '{}'::text[] ELSE ARRAY[?::text] END)
            ON CONFLICT (tenant_id, user_id) DO UPDATE SET
                tours_done = CASE WHEN ?::text IS NULL OR ?::text = ANY (user_ui_state.tours_done)
                                  THEN user_ui_state.tours_done
                                  ELSE array_append(user_ui_state.tours_done, ?::text) END,
                pages_seen = CASE WHEN ?::text IS NULL OR ?::text = ANY (user_ui_state.pages_seen)
                                  THEN user_ui_state.pages_seen
                                  ELSE array_append(user_ui_state.pages_seen, ?::text) END,
                updated_at = now()
            """,
            tenantId, userId, tour, tour, page, page,
            tour, tour, tour, page, page, page);
        return read(jdbc, tenantId, userId);
    }

    private static String id(Object v, Set<String> allowed, String field, boolean present) {
        if (!present) return null;
        if (!(v instanceof String s) || !allowed.contains(s)) throw bad(field + " is not a known id");
        return s;
    }

    private static List<String> list(Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        return Arrays.asList((String[]) a.getArray());
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
