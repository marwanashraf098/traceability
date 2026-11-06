package com.traceability.portal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Returns portal fees F4 — the ONE rule for the COD on a Traced-booked Bosta type 30 exchange (Mode B amendment #3,
 * COD, approved 2026-11-05): cod = the request's FROZEN collect amount ({@code return_request_amounts.collect_cod},
 * written at submit — never recomputed from live settings), ≥ 0 and ≤ 30,000, verified on read-back.
 *
 * Zero (exactly as before F4) when: fees are switched off ({@code traced.portal-fees.settings-enabled}), the request
 * isn't an exchange (a type 25 refund pickup is always cod 0 — never negative), the order is a portal pre-connect
 * order, or nothing is frozen. Non-Bosta (G4) requests are never booked with Bosta at all. What was actually sent
 * is stored on {@code return_requests.booking_cod} (V215) by the booking claim; the read-back and every display read
 * that column, never this rule again.
 */
public final class ExchangeCod {

    /** Bosta's COD limit. */
    public static final BigDecimal LIMIT = PortalAmounts.COD_LIMIT;

    /** The frozen collect of the request {@code rr} (an exchange row only); NULL when none was frozen. */
    public static final String FROZEN_SQL =
        "(SELECT a.collect_cod FROM return_request_amounts a " +
        "  WHERE a.request_id = rr.id AND a.tenant_id = rr.tenant_id AND a.fee_kind = 'exchange')";

    private ExchangeCod() {}

    /** The COD to send on this request's type 30 (0 = none). May exceed {@link #LIMIT} — the caller refuses that. */
    public static BigDecimal toSend(boolean feesOn, boolean exchange, boolean preConnectOrder, BigDecimal frozen) {
        if (!feesOn || !exchange || preConnectOrder || frozen == null || frozen.signum() <= 0) return BigDecimal.ZERO;
        return frozen.setScale(2, RoundingMode.HALF_UP);
    }

    /** Within Bosta's limits: 0 ≤ cod ≤ 30,000. */
    public static boolean allowed(BigDecimal cod) {
        return cod != null && cod.signum() >= 0 && cod.compareTo(LIMIT) <= 0;
    }

    /** What the booking claim stores on {@code booking_cod}: the amount when one is sent within the limit, else NULL. */
    public static BigDecimal column(BigDecimal cod) {
        return cod != null && cod.signum() > 0 && allowed(cod) ? cod : null;
    }

    /** Bosta's cod equals the one sent (to the piastre); nothing sent = 0. */
    public static boolean matches(BigDecimal sent, BigDecimal bosta) {
        BigDecimal expected = sent == null ? BigDecimal.ZERO : sent;
        BigDecimal actual = bosta == null ? BigDecimal.ZERO : bosta;
        return expected.setScale(2, RoundingMode.HALF_UP).compareTo(actual.setScale(2, RoundingMode.HALF_UP)) == 0;
    }
}
