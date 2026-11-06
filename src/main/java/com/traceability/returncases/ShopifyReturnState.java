package com.traceability.returncases;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shopify-native returns S2 — the ONE derivation of a Shopify return's state as Traced shows it (order card chip,
 * Returns list "Shopify status"), from the mirrored return status (V206 shopify_returns.status) and the
 * fulfillment orders of its EXCHANGE lines (shopify_fulfillment_orders whose lines are the order lines the exchange
 * lines became). Read-only: nothing here decides stock, reservation or picking.
 *
 * <pre>
 *   return CANCELED                          → cancelled
 *   return DECLINED                          → declined
 *   return CLOSED                            → closed
 *   return REQUESTED                         → requested
 *   otherwise (OPEN), over the exchange FOs that aren't CANCELLED / INCOMPLETE:
 *     none                                   → open
 *     any ON_HOLD                            → on_hold (+ the hold reasons of the held FOs, e.g. AWAITING_PAYMENT)
 *     any OPEN / IN_PROGRESS                 → released (Shopify released the replacement for fulfillment)
 *     all CLOSED                             → shipped (every replacement fulfilled)
 *     anything else (SCHEDULED, unknown)     → open
 * </pre>
 */
public final class ShopifyReturnState {

    private ShopifyReturnState() {}

    public static final String REQUESTED = "requested";
    public static final String OPEN = "open";
    public static final String ON_HOLD = "on_hold";
    public static final String RELEASED = "released";
    public static final String SHIPPED = "shipped";
    public static final String CLOSED = "closed";
    public static final String DECLINED = "declined";
    public static final String CANCELLED = "cancelled";

    /** One exchange fulfillment order: its Shopify status and hold reasons. */
    public record Fo(String status, List<String> holdReasons) {}

    /** The state, and the hold reasons (non-empty only for on_hold). */
    public record Derived(String state, List<String> holdReasons) {}

    /** True for the states that end the return in Shopify (closed / declined / cancelled). */
    public static boolean finished(String returnStatus) {
        String s = returnStatus == null ? "" : returnStatus;
        return s.equals("CLOSED") || s.equals("DECLINED") || s.equals("CANCELED");
    }

    public static Derived derive(String returnStatus, List<Fo> exchangeFos) {
        String s = returnStatus == null ? "" : returnStatus;
        switch (s) {
            case "CANCELED": return new Derived(CANCELLED, List.of());
            case "DECLINED": return new Derived(DECLINED, List.of());
            case "CLOSED":   return new Derived(CLOSED, List.of());
            case "REQUESTED": return new Derived(REQUESTED, List.of());
            default: break;
        }
        List<Fo> live = new ArrayList<>();
        for (Fo f : exchangeFos == null ? List.<Fo>of() : exchangeFos) {
            String st = f.status() == null ? "" : f.status();
            if (!st.equals("CANCELLED") && !st.equals("INCOMPLETE")) live.add(f);
        }
        if (live.isEmpty()) return new Derived(OPEN, List.of());
        Set<String> holds = new LinkedHashSet<>();
        boolean held = false, released = false, allClosed = true;
        for (Fo f : live) {
            switch (f.status()) {
                case "ON_HOLD" -> {
                    held = true;
                    if (f.holdReasons() != null) holds.addAll(f.holdReasons());
                }
                case "OPEN", "IN_PROGRESS" -> released = true;
                default -> { }
            }
            if (!"CLOSED".equals(f.status())) allClosed = false;
        }
        if (held) return new Derived(ON_HOLD, List.copyOf(holds));
        if (released) return new Derived(RELEASED, List.of());
        if (allClosed) return new Derived(SHIPPED, List.of());
        return new Derived(OPEN, List.of());
    }
}
