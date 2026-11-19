package com.traceability.bazar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Bazar POS B6b — the ONE answer to "was this user removed from this event's sellers" (bazar_event_sellers.removed_at,
 * V238). A removed seller can't sell, return, exchange or scan back at that event and the event isn't their
 * {@code bazarLive}; a user who was never added keeps the pre-B6b rule (their role in the event's seller_roles).
 */
public final class BazarSellers {

    private BazarSellers() {}

    /** SQL over an event aliased {@code e}; one {@code ?} = the user id. */
    static final String REMOVED_FROM_E_SQL =
        "EXISTS (SELECT 1 FROM bazar_event_sellers bs WHERE bs.event_id = e.id AND bs.user_id = ? " +
        "AND bs.removed_at IS NOT NULL)";

    static boolean removed(JdbcTemplate jdbc, UUID eventId, UUID userId) {
        if (eventId == null || userId == null) return false;
        Boolean r = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bazar_event_sellers WHERE event_id = ? AND user_id = ? " +
            "AND removed_at IS NOT NULL)", Boolean.class, eventId, userId);
        return Boolean.TRUE.equals(r);
    }
}
