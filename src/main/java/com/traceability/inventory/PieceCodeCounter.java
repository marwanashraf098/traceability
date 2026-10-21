package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The ONE claim on a tenant's short-code sequence (piece_counters, V55). Used by
 * InventoryLedger.batchReceive (received pieces) and LabelCodeService.issue (blank label codes), so
 * a blank code and a received piece can never get the same short code.
 *
 * One {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}: the conflicting row is locked until the
 * claiming transaction ends, so concurrent claims serialize and each gets its own unbroken range.
 * A rolled-back claim leaves a gap — harmless, codes are unique, not gapless.
 */
public final class PieceCodeCounter {

    private PieceCodeCounter() {}

    /** Claims {@code n} (≥ 1) sequential values for the tenant; returns the FIRST value of the range. */
    public static long claim(JdbcTemplate jdbc, UUID tenantId, int n) {
        if (n < 1) throw new IllegalArgumentException("claim needs n >= 1");
        Long lastValue = jdbc.queryForObject(
            "INSERT INTO piece_counters (tenant_id, last_value) VALUES (?, ?) " +
            "ON CONFLICT (tenant_id) DO UPDATE " +
            "SET last_value = piece_counters.last_value + EXCLUDED.last_value " +
            "RETURNING last_value",
            Long.class, tenantId, (long) n);
        return lastValue - n + 1;
    }

    /** "P" + the value, at least 6 digits (P000123, P1000000). */
    public static String format(long value) {
        return "P" + String.format("%06d", value);
    }
}
