# Runbook — upgrade a store from the custom app to the official Traced app (OAuth)

Build C (V146). One store at a time. The upgrade keeps the **same `stores` row, the same tenant and all data**;
only the token and `connection_type` change (`custom_app_cc` → `oauth`).

What Traced does on the callback, in this order:
1. Deletes the **old custom app's** webhook subscriptions that point at Traced, using the old app's own
   credentials (stored token, or a fresh client-credentials exchange if it expired).
2. Swaps the token, sets `connection_type = 'oauth'`, clears `api_secret_encrypted` and `client_id_encrypted`.
3. Records the outcome on the row (`oauth_upgraded_from`, `oauth_upgraded_at`, `legacy_webhook_cleanup_status`,
   `legacy_webhook_cleanup_detail`, `legacy_webhook_cleanup_at`) and logs `OAUTH_UPGRADE` / `OAUTH_UPGRADE_CLEANUP`.
4. Drops the cached scope tier, then enqueues the import and the webhook registration with the new token.

A failed cleanup never blocks the upgrade (step 2 still happens); see "If the cleanup failed".

---

## 1. Pre-checks (you)

1. **Prod env** (`~/traceability/.env` on the server):
   - `SHOPIFY_APP_HANDLE=trace-3` — without it, the browser lands on a broken Shopify admin URL after consent
     (the upgrade itself still completes).
   - `SHOPIFY_OAUTH_AVAILABLE=true` — the upgrade banner is shown only when this is on.
   - `SHOPIFY_OAUTH_UPGRADE_SHOPS=<shop>.myshopify.com` — the per-store rollout list (comma-separated). With the
     flag on, only listed stores see the banner; an **empty list means every custom-app store sees it**. Roll out one
     store at a time: add the shop, restart the app, run this runbook to the end (step 3 verified), then add the
     next shop (`SHOPIFY_OAUTH_UPGRADE_SHOPS=a.myshopify.com,b.myshopify.com`) and repeat. The list only controls the
     banner — it does not block an upgrade started any other way.
2. **Deployed**: V146 applied (`SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';` ≥ 146).
3. **The store** (psql as postgres):
   ```sql
   SELECT id, tenant_id, shop_domain, connection_type, status, import_status, access_token_expires_at,
          client_id_encrypted IS NOT NULL AS has_client_id, api_secret_encrypted IS NOT NULL AS has_secret
   FROM stores WHERE shop_domain = '<shop>.myshopify.com';
   ```
   Expect `connection_type = custom_app_cc`, `status = connected`, `has_client_id` and `has_secret` true.
   Note the `id` — it must be the same afterwards.
4. **The custom app must still be installed.** If the merchant already deleted it, the store is disconnected —
   go to "Trap" below instead.
5. Tell the merchant: **do not delete the custom app yet.**

## 2. What the merchant clicks

1. Traced → **Settings → Connections**. The Shopify card shows a box: **"The official Traced app is live — Upgrade
   in one click for a more secure connection. Your data and history stay exactly as they are."**
2. Click **"Upgrade to the official app"** (owner only).
3. Shopify opens the official app's permission screen for their store → click **Install** (or Update).
4. They land in the Shopify admin on the Traced app (the embedded dashboard). Done — nothing else to click.

## 3. Verify after (you)

```sql
SELECT id, connection_type, status, import_status, oauth_upgraded_from, oauth_upgraded_at,
       legacy_webhook_cleanup_status, legacy_webhook_cleanup_detail,
       api_secret_encrypted IS NULL AS secret_cleared, client_id_encrypted IS NULL AS client_id_cleared,
       access_token_scopes
FROM stores WHERE shop_domain = '<shop>.myshopify.com';
```
- Same `id` as before; `connection_type = oauth`; `status = connected`; both `*_cleared` true;
  `oauth_upgraded_from = custom_app_cc`; `access_token_scopes` includes `read_customers`.
- `legacy_webhook_cleanup_status = done` (detail e.g. "deleted 6 of the old app's webhooks").
- `import_status` goes `pending` → `completed` within a few minutes.
- Server log: `OAUTH_UPGRADE shop=… from=custom_app_cc cleanup=done` and
  `Webhook registration complete for store <id>`.
- New orders arrive through the official app:
  ```sql
  SELECT topic, received_at FROM shopify_webhook_events
  WHERE shop_domain = '<shop>.myshopify.com' AND received_at > now() - interval '1 hour' ORDER BY received_at DESC;
  ```
  and the newest orders have `customer_name` filled.

## 4. When the merchant may delete the custom app

**Any time after step 3 shows `connection_type = oauth`** — ideally after the first order webhook has arrived through
the official app. From that point the old app's `app/uninstalled` can no longer disconnect the store (its secret is
gone, and custom-app verification only applies to custom-app rows). Deleting it also removes any of its webhook
subscriptions the cleanup could not.

**Never before the upgrade** — see "Trap".

## 5. If the cleanup failed (`legacy_webhook_cleanup_status = failed`)

The upgrade is complete. The old app may still have subscriptions to Traced: they arrive signed with the old secret
and are rejected (401), so they do nothing but log `Shopify webhook HMAC mismatch`. To clear them:
**ask the merchant to delete the custom app** (step 4) — Shopify removes its subscriptions with it. Traced keeps no
custom-app credentials after the flip, so there is no in-app retry. If they can't delete it, the 401s are harmless; Shopify
may also remove a subscription that keeps failing.

## 6. Trap — the merchant deleted the custom app BEFORE upgrading

The custom app's `app/uninstalled` is signed with the secret Traced still holds, so it is accepted and the store is
**disconnected** (same row, data intact).

What the merchant sees: Settings → Connections → the Shopify card shows not connected, with "This account is linked
to `<shop>`. Only that store can be reconnected." and a **"Connect with Shopify"** button. Orders stop syncing
(no webhooks; import and reconcile skip disconnected stores). The upgrade banner is not shown (it only appears on a
connected store).

Recovery: click **"Connect with Shopify"** (the shop is filled in) → Shopify permission screen → **Install**. The same
row comes back `connected` as `oauth`; `legacy_webhook_cleanup_status = failed` ("could not list…" / "credentials
rejected") — expected, the deleted app's subscriptions are already gone. Then verify as in step 3. Orders placed while
it was disconnected come in through the import (the last 30 days, never before the store's connection date) and
the reconcile job.

## 7. Rollback (back to the custom app)

Only possible while the custom app still exists (not deleted).
1. Set `CUSTOM_APP_CONNECT_ENABLED=true` in prod `.env` and restart.
2. The merchant re-enters the custom app's Client ID / Secret in Settings → Connections (custom-app setup). The same
   row flips back to `custom_app_cc` with the custom-app token and secret.
3. **Leave the official app installed.** Traced's `app/uninstalled` handler disconnects the store by shop domain, whichever
   app sent it — uninstalling the official app would disconnect the store again.
4. The official app's webhook subscriptions keep arriving (verified with Traced's own secret) alongside the custom app's;
   order upserts are idempotent, so duplicates are harmless.
