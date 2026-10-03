package com.traceability.integrations.bosta;

import java.util.regex.Pattern;

/**
 * Review mode — the reserved tracking-number range for simulated shipments. The ONE place
 * the range is defined.
 *
 * Reserved: exactly 13 digits starting with {@code 777}. Real Bosta tracking numbers seen in
 * production are 8–10 digits and the public demo fixture uses 12-digit {@code 999…} numbers,
 * so the range collides with neither. Still all digits, so it survives
 * {@code TrackingNumberNormalizer} unchanged (a letter prefix would be stripped).
 *
 * A reserved number is never sent to Bosta: {@link BostaHttpGateway} refuses it before any
 * HTTP call.
 */
public final class SimulatedTracking {

    private SimulatedTracking() {}

    private static final Pattern RESERVED = Pattern.compile("^777\\d{10}$");

    public static boolean isReserved(String trackingNumber) {
        return trackingNumber != null && RESERVED.matcher(trackingNumber).matches();
    }
}
