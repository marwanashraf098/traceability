package com.traceability.inventory;

/**
 * The thresholds stock-take finalize enforces before it applies a count (Marawan, 2026-10-01) —
 * the ONE place they live; the finalize plan (StockTakeReconciliationService.plan) and the review
 * screen read them from the plan, never re-derive them.
 *
 *   - A session with no piece scans can never be finalized (400): a blind finalize would write the
 *     whole store off.
 *   - Typed confirmation (the user types the write-off count) when coverage of the expected free
 *     stock is below {@link #MIN_COVERAGE}, or the write-offs exceed {@link #MAX_WRITE_OFF_SHARE}
 *     of it.
 */
public final class StockTakeFinalizePolicy {

    private StockTakeFinalizePolicy() {}

    /** Scanned share of the expected free stock (available / damaged / on_hold at open) below which
     *  finalize needs typed confirmation. */
    public static final double MIN_COVERAGE = 0.80;

    /** Write-offs above this share of the expected free stock need typed confirmation. */
    public static final double MAX_WRITE_OFF_SHARE = 0.10;

    /** True when finalizing this plan needs the user to type the write-off count — never when it
     *  writes nothing off (there is no count to confirm). */
    public static boolean requiresTypedConfirmation(int expectedFree, int scannedFree, int writeOffs) {
        if (expectedFree == 0 || writeOffs == 0) return false;
        double coverage = (double) scannedFree / expectedFree;
        return coverage < MIN_COVERAGE || writeOffs > MAX_WRITE_OFF_SHARE * expectedFree;
    }
}
