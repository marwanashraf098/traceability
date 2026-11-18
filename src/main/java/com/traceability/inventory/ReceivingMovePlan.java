package com.traceability.inventory;

/**
 * Receiving move — THE per-line math (Step 0 §6, approved by Marawan 2026-11-12). Pure: no database, no Shopify. The
 * preview (M1) and the send (M2, recomputed from a fresh read on every attempt) both use it — never re-derive it.
 *
 * <p>A = the source's AVAILABLE (null = Shopify has no active level there), C = committed (never touched — it is never in
 * "available", so it is never moved), q = units received on the line. Traced Main always gets +q (and q pieces).
 *
 * <pre>
 * | Case              | Choice          | Source delta | Source after | Shopify total change |
 * | q = A             | —               | −q           | 0            | 0                    |
 * | q &lt; A             | Keep them there | −q           | A−q          | 0                    |
 * | q &lt; A             | Set source to 0 | −A           | 0            | −(A−q)               |
 * | q &gt; A, A &gt; 0      | —               | −A           | 0            | +(q−A)               |
 * | A ≤ 0 or no level | —               | 0            | unchanged    | +q                   |
 * | q = 0             | —               | line not allowed                                    |
 * </pre>
 * "Set source to 0" removes A−q units Traced never holds — a real write-off at the source, by explicit choice only; the
 * choice matters only when q &lt; A.
 */
public final class ReceivingMovePlan {

    private ReceivingMovePlan() {}

    public static final String KEEP = "keep";
    public static final String ZERO = "zero";

    /** The six cases, as the preview names them. */
    public enum Case { ALL_MOVED, KEEP_REST, SET_TO_ZERO, MORE_THAN_SHOPIFY, NOTHING_AT_SOURCE, NOT_ALLOWED }

    /**
     * @param sourceAfter null when the source is left unchanged with no level (or the line isn't allowed)
     */
    public record Line(Case kase, int mainDelta, int sourceDelta, Integer sourceAfter, int totalChange) {}

    /**
     * @param q         units received on the line (summed per variant by the caller)
     * @param available the source's available, or null when Shopify has no active level there
     * @param choice    {@link #KEEP} (default) or {@link #ZERO}; anything else is KEEP
     */
    public static Line line(int q, Integer available, String choice) {
        if (q <= 0) return new Line(Case.NOT_ALLOWED, 0, 0, null, 0);
        if (available == null || available <= 0) return new Line(Case.NOTHING_AT_SOURCE, q, 0, available, q);
        int a = available;
        if (q == a) return new Line(Case.ALL_MOVED, q, -q, 0, 0);
        if (q > a) return new Line(Case.MORE_THAN_SHOPIFY, q, -a, 0, q - a);
        if (ZERO.equals(choice)) return new Line(Case.SET_TO_ZERO, q, -a, 0, -(a - q));
        return new Line(Case.KEEP_REST, q, -q, a - q, 0);
    }
}
