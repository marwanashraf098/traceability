-- FR-17: store the scopes that were in effect when a token was issued.
-- Used by acquireOrRefreshViaSessionToken() to detect scope mismatches and
-- force a re-exchange when the app's declared scope list expands (e.g. adding
-- write_inventory). Without this, a fresh token issued before the new scope
-- was added would be reused indefinitely, silently missing the new permission.
-- NULL means unknown (tokens issued before this migration) → forces re-exchange.
ALTER TABLE stores ADD COLUMN access_token_scopes text;
