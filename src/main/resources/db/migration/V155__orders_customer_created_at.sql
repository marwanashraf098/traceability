-- V155 — Analytics slice 6 (customers): when Shopify created the order's customer, as a STORED
-- generated column (the V149 rule: total functions, no read-time raw parsing).
--
-- REST orders carry customer.created_at, GraphQL imports customer.createdAt. A customer created
-- BEFORE the store connected to Traced is an "existing" customer; one created on or after it is
-- "new" on their first order; no date (no customer object, e.g. phone-only orders) is "unknown".
-- GDPR redaction (V143) removes the customer object from raw, so this becomes NULL with it.
ALTER TABLE orders
    ADD COLUMN customer_created_at timestamptz GENERATED ALWAYS AS (
        CASE WHEN COALESCE(raw #>> '{customer,created_at}', raw #>> '{customer,createdAt}') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
             THEN analytics_ts(COALESCE(raw #>> '{customer,created_at}', raw #>> '{customer,createdAt}')) END) STORED;
