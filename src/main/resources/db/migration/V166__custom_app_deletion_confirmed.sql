-- ============================================================
-- V166 — App-wide Shopify upgrade notification: "I've deleted the old custom app"
--
-- After a custom-app → official OAuth upgrade (Build C, V146 oauth_upgraded_from), the merchant is asked
-- app-wide to delete the old custom app from their Shopify Dev Dashboard. The owner confirms it once:
--   custom_app_deletion_confirmed_at  when the owner pressed "I've deleted it" (NULL until then)
--   custom_app_deletion_confirmed_by  the owner who confirmed
-- Written only by ShopifyUpgradeNoticeService.confirmDeletion (a conditional UPDATE: upgraded OAuth row,
-- not yet confirmed). NULL on every other store.
--
-- No new table, no grant change, no RLS change (stores' tenant_isolation policy covers the columns).
-- ============================================================

ALTER TABLE stores
    ADD COLUMN custom_app_deletion_confirmed_at timestamptz,
    ADD COLUMN custom_app_deletion_confirmed_by uuid REFERENCES users(id);
