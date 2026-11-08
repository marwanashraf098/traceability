package com.traceability.integrations.shopify;

/**
 * Part D P2 (2026-11-08) — the item has no ACTIVE inventory level at the Traced Main location in Shopify (the level is
 * inactive, was never created, or the item is gone). Nothing was sent: a write there would land on a HIDDEN level
 * (dev store 2026-11-08, part-d-p2/activation.py q1c / q1d: an adjust on an inactive level applies with no error and
 * shows only once the level is active again), so Traced never writes to one.
 *
 * <p>Thrown by the gateway's own pre-write read (the increment's baseline read in adjustInventoryQuantities and the
 * damage move's): {@code inventoryItem.inventoryLevel(locationId:)} answers null for an inactive level
 * (part-d-p2/level_shape.py). It is a NEVER_SENT adjust failure — safe to retry once the level is active.
 */
public class ShopifyMainLevelInactiveException extends ShopifyAdjustFailedException {

    /** The skip reason / error prefix every caller records (claim rows, sync rows). */
    public static final String REASON = "main_level_inactive";

    public ShopifyMainLevelInactiveException(String what) {
        super(FailureClass.NEVER_SENT, null, REASON + ": " + what + " not sent — the item has no active inventory "
            + "level at the Traced location in Shopify (inactive or missing); a write there would land on a hidden "
            + "level", null);
    }
}
