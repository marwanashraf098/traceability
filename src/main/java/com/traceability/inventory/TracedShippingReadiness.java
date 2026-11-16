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
 *   <li>{@code ready} (market-driven, L1b-2) — Traced READ the primary market's rates and one lists Traced Main
 *       ({@code shopify_market_shipping_status = 'covered'}, MarketShippingCoverage) and it fulfils online orders</li>
 *   <li>{@code unknown} (market-driven) — covered, but fulfils-online wasn't read as on</li>
 *   <li>{@code add_to_shipping_rates} — market-driven: Traced read the primary market's rates in full and none lists
 *       Traced Main ('not_covered' — this beats the owner's "I've added it"), or Traced can't read them (no verdict,
 *       no read_markets, never read) and the owner hasn't confirmed. The owner adds the location to a shipping rate in
 *       Shopify; Traced never edits rates.</li>
 *   <li>{@code owner_confirmed} — market-driven, no verdict from Traced, and the OWNER said "I've added it": the
 *       instruction is hidden ("Confirmed by you"). NOT Ready — the move-feature gate (M1) opens on 'ready' only.</li>
 *   <li>{@code unknown} — the shipping model or fulfils-online wasn't read yet / the read failed, or (legacy) the
 *       delivery-profile membership was never read LIVE ({@code shopify_delivery_profile_checked_at} NULL — before L1
 *       joining was manual, so the stored V67 status can be wrong both ways) — NEVER Ready, shown "Checking…"</li>
 *   <li>{@code profile_removed} — legacy model, taken out of the General profile after it had been in it — the owner
 *       adds it back in Shopify (instruction + deep link)</li>
 *   <li>{@code ready} — legacy model, active, fulfils online orders, in the General profile per a LIVE read</li>
 *   <li>{@code activate_needed} — legacy model, never in the profile: the owner adds it to the shipping profile in
 *       Shopify (L1b-2: Traced no longer joins it — instruction + deep link)</li>
 * </ul>
 * Traced never writes a delivery profile or a shipping rate (DeliveryProfileMutationGuardTest): legacy readiness comes
 * from the membership READ, market-driven readiness from the Markets READ.
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
        "     WHEN l.shopify_shipping_model = 'market_driven' AND l.shopify_market_shipping_status = 'covered' " +
        "          AND l.shopify_online_fulfillment_status = 'on' THEN 'ready' " +
        "     WHEN l.shopify_shipping_model = 'market_driven' AND l.shopify_market_shipping_status = 'covered' THEN 'unknown' " +
        "     WHEN l.shopify_shipping_model = 'market_driven' AND l.shopify_market_shipping_status = 'not_covered' " +
        "          THEN 'add_to_shipping_rates' " +
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
