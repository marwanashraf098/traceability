-- ============================================================
-- V103 — Returns portal Step 4e-A: merchant branding for the customer portal
--
-- portal_logo_url: a Shopify Files CDN link only (the merchant uploads the logo in Shopify
--   → Content → Files and pastes its link) — we never host or fetch arbitrary URLs.
-- portal_brand_color: #RRGGBB.
-- portal_policy_text: free text shown to the customer, max 2000 characters.
-- All three are optional; nothing is backfilled.
-- ============================================================

ALTER TABLE tenants
    ADD COLUMN portal_logo_url    text
        CONSTRAINT tenants_portal_logo_url_shopify_cdn CHECK (portal_logo_url LIKE 'https://cdn.shopify.com/%'),
    ADD COLUMN portal_brand_color text
        CONSTRAINT tenants_portal_brand_color_hex CHECK (portal_brand_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN portal_policy_text text
        CONSTRAINT tenants_portal_policy_text_length CHECK (char_length(portal_policy_text) <= 2000);
