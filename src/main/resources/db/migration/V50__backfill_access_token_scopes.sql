-- Pre-V49 stores have access_token_scopes = NULL. V49's scope-aware freshness check
-- treats NULL as "must re-exchange" which destroys grandfathered non-expiring tokens
-- that Shopify no longer issues (Jumi / mmi24e-fx is the live affected store).
--
-- Safe backfill: only touches stores that are already 'connected' (token currently
-- working) and have no recorded scopes. Backfill value = app's current declared scope
-- list (application.yml shopify.scopes). scopesMatch will return true → no re-exchange.
-- On the next naturally-triggered exchange the real granted scopes will overwrite this.
UPDATE stores
SET access_token_scopes = 'read_products,read_orders,read_fulfillments,read_customers'
WHERE status = 'connected'
  AND access_token_scopes IS NULL;
