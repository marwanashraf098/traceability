package com.traceability.integrations.bosta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps (stateCode, orderType) → internal shipment state + piece status.
 *
 * Rows are loaded from bosta_state_mappings at construction time (seeded in V2)
 * and cached. No DB access during request/job processing.
 *
 * Lookup priority:
 *   1. (stateCode, type)  — for code 41 SEND vs RTO disambiguation
 *   2. (stateCode, "ALL") — catch-all for type-independent codes
 *   3. Not found → MappedState.exception(stateCode) — alert, never crash
 *
 * bosta_state_mappings has no RLS policy (global lookup table).
 * This constructor is safe to call outside a tenant context.
 */
@Service
public class BostaStateMapper {

    private static final Logger log = LoggerFactory.getLogger(BostaStateMapper.class);

    public record MappedState(
            String shipmentInternalState,
            String pieceStatusAfter,      // nullable — some transitions don't change piece status
            boolean isException) {

        static MappedState of(String shipmentState, String pieceStatus) {
            return new MappedState(shipmentState, pieceStatus, "exception".equals(shipmentState));
        }

        static MappedState unknown(int code) {
            return new MappedState("exception", null, true);
        }
    }

    // key: "<stateCode>:<orderType>"  e.g. "41:SEND", "45:ALL"
    private final Map<String, MappedState> cache = new HashMap<>();

    public BostaStateMapper(JdbcTemplate jdbc) {
        jdbc.query(
            "SELECT state_code, applies_to_order_type, internal_shipment_state, piece_status_after " +
            "FROM bosta_state_mappings",
            rs -> {
                int code = rs.getInt("state_code");
                String type = rs.getString("applies_to_order_type");
                String state = rs.getString("internal_shipment_state");
                String piece = rs.getString("piece_status_after");
                cache.put(code + ":" + type, MappedState.of(state, piece));
            });
        log.debug("Loaded {} Bosta state mappings", cache.size());
    }

    public MappedState map(int stateCode, String orderType) {
        MappedState m = cache.get(stateCode + ":" + orderType);
        if (m == null) m = cache.get(stateCode + ":ALL");
        if (m == null) {
            log.warn("Unknown Bosta state code {} for type '{}' — no mapping found", stateCode, orderType);
            return MappedState.unknown(stateCode);
        }
        return m;
    }
}
