package com.traceability.inventory;

/**
 * L1b-1 (2026-11-15) — THE rule for "can the Traced Main Warehouse ship online orders?" (never re-derive it). One SQL
 * expression over the location row (alias {@code l}), used by the onboarding gate row (ConnectionsController), the
 * connection card (LocationController.list) and the read-only audit. Values, in the order they are decided:
 *
 * <ul>
 *   <li>{@code not_linked} — no Shopify location yet (the gate shows "Setting up…")</li>
 *   <li>{@code location_inactive} — deactivated / deleted in Shopify</li>
 *   <li>{@code online_off} — "fulfil online orders" is off (owner: Turn back on)</li>
 *   <li>{@code add_to_shipping_rates} — market-driven shipping: no delivery-profile call can make it ship, and Traced
 *       can't confirm it either (L1b-2) — the owner makes sure the location is in its shipping rates in Shopify</li>
 *   <li>{@code owner_confirmed} — market-driven, and the OWNER said "I've added it": the instruction is hidden
 *       ("Confirmed by you"). NOT Ready — the move-feature gate needs a status Traced verified itself (L1b-2).</li>
 *   <li>{@code unknown} — the shipping model or fulfils-online wasn't read yet / the read failed, or (legacy) the
 *       delivery-profile membership was never read LIVE ({@code shopify_delivery_profile_checked_at} NULL — before L1
 *       joining was manual, so the stored V67 status can be wrong both ways) — NEVER Ready, shown "Checking…"</li>
 *   <li>{@code profile_removed} — legacy model, taken out of the General profile after it had been in it (Turn back on)</li>
 *   <li>{@code ready} — legacy model, active, fulfils online orders, in the General profile per a LIVE read</li>
 *   <li>{@code activate_needed} — legacy model, never in the profile (the existing Activate fulfillment item)</li>
 * </ul>
 * Market-driven stores are never 'ready' until L1b-2 can read their rates.
 */
public final class TracedShippingReadiness {

    private TracedShippingReadiness() {}

    public static final String READY = "ready";
    public static final String ADD_TO_SHIPPING_RATES = "add_to_shipping_rates";
    public static final String UNKNOWN = "unknown";
    public static final String OWNER_CONFIRMED = "owner_confirmed";

    /** For a row aliased {@code l} of locations (the is_fulfillment row). */
    public static final String SQL =
        "CASE WHEN l.shopify_sync_status <> 'linked' OR l.shopify_location_id IS NULL THEN 'not_linked' " +
        "     WHEN l.shopify_location_state <> 'active' THEN 'location_inactive' " +
        "     WHEN l.shopify_online_fulfillment_status = 'off' THEN 'online_off' " +
        "     WHEN l.shopify_shipping_model = 'market_driven' AND l.shopify_shipping_owner_confirmed_at IS NOT NULL " +
        "          THEN 'owner_confirmed' " +
        "     WHEN l.shopify_shipping_model = 'market_driven' THEN 'add_to_shipping_rates' " +
        "     WHEN l.shopify_shipping_model <> 'legacy' THEN 'unknown' " +
        "     WHEN l.shopify_online_fulfillment_status <> 'on' THEN 'unknown' " +
        "     WHEN l.shopify_delivery_profile_checked_at IS NULL THEN 'unknown' " +
        "     WHEN l.shopify_delivery_profile_status = 'activated' THEN 'ready' " +
        "     WHEN l.shopify_delivery_profile_activated_at IS NOT NULL THEN 'profile_removed' " +
        "     ELSE 'activate_needed' END";
}
