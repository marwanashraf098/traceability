-- Enforce that custom_app_cc rows always have both CC credentials present.
-- A row with connection_type='custom_app_cc' but null client_id_encrypted or
-- api_secret_encrypted cannot re-exchange tokens: ShopifyTokenProvider.getValidToken()
-- would reach the CC branch, decrypt null, and produce an NPE. The constraint converts
-- that to a hard DB-level rejection at insert/update time.
--
-- If this migration fails with a constraint violation, there are incoherent rows in
-- production. Identify and fix them before re-applying:
--
--   SELECT shop_domain, connection_type,
--          client_id_encrypted IS NULL AS missing_client_id,
--          api_secret_encrypted IS NULL AS missing_secret
--   FROM stores
--   WHERE connection_type = 'custom_app_cc'
--     AND (client_id_encrypted IS NULL OR api_secret_encrypted IS NULL);
--
-- Resolution: reconnect the affected store via POST /api/v1/shopify/connect/cc
-- or manually UPDATE connection_type = 'oauth' if the store was OAuth-installed.
ALTER TABLE stores ADD CONSTRAINT stores_cc_requires_credentials
    CHECK (connection_type <> 'custom_app_cc'
        OR (client_id_encrypted IS NOT NULL AND api_secret_encrypted IS NOT NULL));
