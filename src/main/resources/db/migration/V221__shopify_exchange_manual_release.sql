-- ============================================================
-- V221 — Shopify-native returns S3d: "Release reservation" (approved 2026-11-07).
--
-- An owner / manager may end a Shopify-native exchange shadow's reservation by hand (ShopifyShadowOrders.
-- releaseManually — audited) when Traced can't explain why it is still waiting (the #1009 safety net: alert
-- shopify_exchange_still_waiting after 7 days). The reason is its own end_reason / stop_reason, sticky: the reconcile
-- never revives a manually released shadow. CHECKs widened only; no data change.
-- ============================================================

ALTER TABLE shopify_exchange_shadows DROP CONSTRAINT shopify_exchange_shadows_end_reason_check;
ALTER TABLE shopify_exchange_shadows
    ADD CONSTRAINT shopify_exchange_shadows_end_reason_check
        CHECK (end_reason IN ('declined', 'cancelled', 'all_lines_ended', 'manual_release'));

ALTER TABLE shopify_exchange_shadow_lines DROP CONSTRAINT shopify_exchange_shadow_lines_stop_reason_check;
ALTER TABLE shopify_exchange_shadow_lines
    ADD CONSTRAINT shopify_exchange_shadow_lines_stop_reason_check
        CHECK (stop_reason IN ('shipped_in_shopify', 'fo_cancelled', 'stood_down',
                               'declined', 'cancelled', 'closed_in_shopify', 'manual_release'));
