# Traced — Production Provisioning Runbook

**Target**: Hetzner CX32 (4 vCPU, 8 GB RAM) · Ubuntu 24.04  
**Stack**: Docker Compose — two services: `app` (Spring Boot) + `nginx` (TLS reverse proxy)  
**Database**: Supabase (external). No Postgres container in this stack.  
**Edge**: Cloudflare free (DNS-proxied)

All paths are relative to the repo root unless stated otherwise.

---

## 0. Pre-flight (do this BEFORE touching the server)

### 0.1 Generate the encryption key — ONCE, FOREVER

```bash
openssl rand -base64 32
```

> **⚠ CRITICAL — read before proceeding.**  
> This key (`APP_ENCRYPTION_KEY`) is the AES-256-GCM envelope key that encrypts every
> Shopify and Bosta API token stored in Supabase. It is checked at startup:
> `EncryptionService` throws `IllegalStateException` if the decoded key is not exactly 32 bytes
> — the app refuses to start with the wrong value.
>
> **Back it up off the server immediately** (password manager or secrets vault).  
> If the key is lost or rotated between deploys, every encrypted token row in the database
> becomes permanently undecryptable. The only recovery is to re-connect every tenant's
> Shopify store and Bosta account from scratch.  
> **Never rotate this key** without first re-encrypting all `api_key_encrypted` and
> `access_token_encrypted` rows.

The application.yml default (`AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`, 44 A-characters =
all-zero 32 bytes) **must not be used in production**. The app will start with the default but
all stored tokens will be encrypted under the wrong key.

### 0.2 Fill in `.env`

```bash
cp .env.example .env
chmod 600 .env    # owner-read only — do this before writing secrets in
```

Then edit `.env`. The full variable reference is in `.env.example` with a comment on each.
Grouped summary:

**Database / Flyway** (all [OPERATOR-SUPPLIED])
| Variable | What to put |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:5432/postgres?user=app_user.[PROJECT_REF]&password=[PASSWORD]&sslmode=require` |
| `APP_DB_USER` | `app_user.[PROJECT_REF]` |
| `APP_DB_PASSWORD` | [OPERATOR-SUPPLIED] |
| `FLYWAY_DB_URL` | Same pooler with `postgres.[PROJECT_REF]` credentials, or direct host if on a plan with IPv4 |
| `FLYWAY_DB_USER` | `postgres.[PROJECT_REF]` |
| `FLYWAY_DB_PASSWORD` | [OPERATOR-SUPPLIED] |

> **Port warning**: use pooler port **5432** (session mode). Port 6543 is transaction mode —
> `SET LOCAL app.current_tenant` is reset between statements, RLS returns zero rows silently.
> `DataSourceConfig` throws `IllegalStateException` at startup if port 6543 is detected.

**JWT + Encryption** (both secrets)
| Variable | What to put |
|---|---|
| `JWT_SECRET` | `openssl rand -base64 32` |
| `APP_ENCRYPTION_KEY` | Value from step 0.1 |

**Shopify OAuth** ([OPERATOR-SUPPLIED] from Shopify Partner Dashboard → Apps → API credentials)
| Variable | What to put |
|---|---|
| `SHOPIFY_CLIENT_ID` | [OPERATOR-SUPPLIED] |
| `SHOPIFY_CLIENT_SECRET` | [OPERATOR-SUPPLIED] |
| `SHOPIFY_REDIRECT_URI` | `https://[DOMAIN]/auth/shopify/callback` |
| `SHOPIFY_APP_URL` | `https://[DOMAIN]` |
| `SHOPIFY_WEBHOOK_BASE_URL` | `https://[DOMAIN]` |
| `SHOPIFY_API_VERSION` | `2026-04` (default; bump when upgrading) |

**Bosta**
| Variable | What to put |
|---|---|
| `BOSTA_BASE_URL` | `https://app.bosta.co` (default is correct; only change for staging) |

> Note: per-tenant Bosta API keys are stored encrypted in Supabase, not in `.env`.
> They are entered by the operator via the Settings UI after first login.

**Sentry** (optional — leave `SENTRY_DSN=` empty to disable)
| Variable | What to put |
|---|---|
| `SENTRY_DSN` | DSN from Sentry project → Settings → Client Keys |
| `SENTRY_ENVIRONMENT` | `production` |

### 0.3 Update Shopify Partner Dashboard

In Shopify Partner Dashboard → Apps → your app → App setup:
- **App URL**: `https://[DOMAIN]`
- **Allowed redirection URL**: `https://[DOMAIN]/auth/shopify/callback`

These must match `SHOPIFY_APP_URL` and `SHOPIFY_REDIRECT_URI` exactly.

### 0.4 Have ready

- Domain name pointing to the server IP (see §2)
- Hetzner account + SSH key uploaded
- Supabase project ref and credentials

---

## 1. Provision the VPS

### 1.1 Create the Hetzner server

- Type: **CX32** (4 vCPU / 8 GB RAM / 80 GB disk)
- Location: **Nuremberg** (nbg1) or **Helsinki** (hel1) — pick closest to Egypt for latency
- Image: **Ubuntu 24.04**
- Add your SSH public key during creation

### 1.2 Initial server hardening

```bash
ssh root@[SERVER_IP]

# Create a non-root sudo user
adduser traced
usermod -aG sudo traced

# Copy SSH key to new user
rsync --archive --chown=traced:traced ~/.ssh /home/traced/

# Disable password auth
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh

# Switch to the new user for the rest
su - traced
```

### 1.3 Install Docker

```bash
sudo apt update && sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update && sudo apt install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker traced
newgrp docker           # reload group membership; or log out and back in
docker --version        # verify
```

### 1.4 Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

> **Bosta webhook source IPs**: Bosta sends inbound webhooks from `34.89.199.241` and
> `35.246.223.19`. These are already reachable on port 443 with the rule above — no special
> allowlist is needed for the endpoint to work. You may add dedicated `ufw allow from` rules
> as a belt-and-suspenders hardening measure, but it is not required.

---

## 2. DNS + TLS (must complete before first `compose up`)

### 2.1 DNS

In Cloudflare:
- Add an **A record**: `[DOMAIN]` → `[SERVER_IP]`
- Set proxy status to **Proxied** (orange cloud) — this enables Cloudflare's WAF and DDoS
  protection, and is required for the `CF-Connecting-IP` header that `deploy/nginx.conf` uses
  for real-IP extraction

> **CF SSL mode**: Set Cloudflare SSL/TLS → Overview → mode to **Full (strict)**.
> "Flexible" would mean Cloudflare talks HTTP to nginx, which breaks HSTS and leaks traffic.

### 2.2 Issue TLS certificate

> **⚠ nginx will NOT start without certs.** `deploy/docker-compose.yml` mounts
> `/etc/letsencrypt:/etc/letsencrypt:ro` into the nginx container. If the cert files are
> absent, nginx exits immediately and the `depends_on: service_healthy` condition is never met.
> **Issue the cert on the host BEFORE running compose up.**

`deploy/nginx.conf` serves the certbot HTTP-01 challenge at `/.well-known/acme-challenge/`
from `/var/www/certbot`. Issue using standalone mode (simpler for first-time):

```bash
sudo apt install -y certbot

# Standalone mode — runs its own temporary HTTP server on port 80.
# Port 80 must be free (nothing bound yet — this runs before compose up).
sudo certbot certonly --standalone \
  -d [DOMAIN] \
  --agree-tos \
  -m [OPERATOR-SUPPLIED: your-email@example.com]
```

Certs land at:
- `/etc/letsencrypt/live/[DOMAIN]/fullchain.pem`
- `/etc/letsencrypt/live/[DOMAIN]/privkey.pem`

These are the exact paths `deploy/nginx.conf` references (lines `ssl_certificate` and
`ssl_certificate_key`).

### 2.3 Patch `YOUR_DOMAIN` in nginx.conf

`deploy/nginx.conf` contains four occurrences of `YOUR_DOMAIN` (server_name, ssl_certificate,
ssl_certificate_key). Replace all of them:

```bash
cd /opt/traced   # or wherever you cloned the repo
sed -i 's/YOUR_DOMAIN/[DOMAIN]/g' deploy/nginx.conf
```

Verify:
```bash
grep YOUR_DOMAIN deploy/nginx.conf   # must return nothing
```

### 2.4 Auto-renewal cron

```bash
sudo crontab -e
```

Add:
```cron
0 3 * * * certbot renew --quiet && docker compose -f /opt/traced/deploy/docker-compose.yml exec nginx nginx -s reload
```

---

## 3. Database prep (Supabase)

### 3.1 Set database timezone

The FR-15.1 30-day inventory windows, never-received detector, and stuck-shipment detector all
use Postgres `now()` server-side. Without this step they roll over at midnight UTC instead of
midnight Cairo (2-hour error on a 3-day window is material):

```sql
-- Run in Supabase SQL Editor
ALTER DATABASE postgres SET timezone TO 'Africa/Cairo';
-- Verify:
SHOW timezone;
```

### 3.2 Enable PITR (NFR-5)

In Supabase Dashboard → Project Settings → Add-ons → **Point in Time Recovery**.

- Enable PITR on the project
- PITR window: minimum 7 days (select what your plan supports)
- This is the primary backup strategy — there is no other backup mechanism

**Restore procedure** (if needed):
1. Supabase Dashboard → Backups → select the recovery point
2. Restore to a new Supabase project (Supabase does not in-place restore — it creates a new project)
3. During restore: take the `app` container down (`docker compose -f deploy/docker-compose.yml stop app`) so no writes land during recovery
4. Update `DATABASE_URL`, `APP_DB_USER/PASSWORD`, `FLYWAY_DB_URL`, `FLYWAY_DB_USER/PASSWORD` in `.env` to point to the restored project
5. Bring `app` back up — Flyway will detect existing migrations and skip them (idempotent; `baseline-on-migrate: false` is correct for a restored DB that already has the schema)
6. Re-run §3.1 (ALTER timezone) on the restored project

### 3.3 First-boot Flyway behaviour

Flyway runs automatically on app startup. The first boot applies all **28 migrations** (V1→V28).
This is why the Docker healthcheck has `start_period: 90s` — cold-start migration against
Supabase across the network takes 30–60 seconds. The container is not considered unhealthy
during this window; nginx simply waits.

If any migration fails, the app exits (Flyway default). Check logs:
```bash
docker compose -f deploy/docker-compose.yml logs app | grep -i "flyway\|migration\|error"
```

---

## 4. Bring-up

### 4.1 Clone repo and copy secrets

```bash
git clone https://github.com/marwanashraf098/traceability.git /opt/traced
cd /opt/traced
cp .env.example .env
chmod 600 .env
nano .env    # fill in all values per §0.2
```

### 4.2 First `compose up`

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

The `--build` forces a full Docker build (Node 22 Vite → Maven JAR → eclipse-temurin:21-jre-alpine).
**Expected build time**: 5–10 minutes on first run (Maven downloads dependencies); subsequent
rebuilds are fast because dependency layers are cached.

### 4.3 Watch the healthcheck

```bash
# Stream all logs during startup
docker compose -f deploy/docker-compose.yml logs -f

# In a second terminal — watch container health states
watch 'docker compose -f /opt/traced/deploy/docker-compose.yml ps'
```

Expected sequence:
1. `app` container starts → `health: starting` (up to 90 seconds while Flyway runs)
2. `app` transitions to `healthy` (Docker ran `wget -q --spider http://localhost:8080/actuator/health` and got HTTP 200)
3. `nginx` container starts (it waited on `depends_on: app: condition: service_healthy`)

> **If `app` stays `unhealthy` past 90s:**
>
> ```bash
> docker compose -f deploy/docker-compose.yml logs app | tail -50
> ```
>
> Common causes and where to look:
>
> | Symptom in logs | Likely cause | Fix |
> |---|---|---|
> | `IllegalStateException: APP_ENCRYPTION_KEY must decode to exactly 32 bytes` | Wrong or missing `APP_ENCRYPTION_KEY` | Fix `.env`, restart |
> | `IllegalStateException: JDBC URL port 6543` | `DATABASE_URL` uses transaction-mode pooler | Change port to 5432 |
> | `FlywayException` / migration error | Migration SQL failed against Supabase | Check migration SQL, fix manually in Supabase if partial |
> | `HikariPool ... Connection refused` | Supabase unreachable | Check Supabase status; verify `DATABASE_URL` host/credentials |
> | `401` or `403` on `/actuator/health` | Would mean SecurityConfig regression | Should not happen; `permitAll` is set |

### 4.4 Verify

```bash
# Health endpoint (returns {"status":"UP"} when healthy)
curl -s https://[DOMAIN]/actuator/health

# SPA loads
curl -sI https://[DOMAIN]/ | head -5
# Expect: HTTP/2 200, content-type: text/html

# HSTS header present
curl -sI https://[DOMAIN]/ | grep -i strict-transport
# Expect: strict-transport-security: max-age=31536000; includeSubDomains

# Confirm HTTP redirects to HTTPS
curl -sI http://[DOMAIN]/ | head -3
# Expect: HTTP/1.1 301, Location: https://[DOMAIN]/
```

---

## 5. Post-deploy wiring

### 5.1 Register Bosta webhook

> Bosta requires the webhook URL to be registered in Bosta Seller Lab or via the Bosta API.
> The app does NOT auto-register with Bosta on startup (unlike Shopify).

1. Log in to the Traced app as Owner
2. Navigate to Settings → Courier Accounts → Connect Bosta
3. Call `POST /api/v1/bosta/connect` with your Bosta API key
   - The app generates a CSPRNG per-tenant webhook secret, stores it encrypted in Supabase, and returns it **once** in the response body
   - If the secret is lost: re-call `/bosta/connect` to regenerate (this invalidates the old secret)
4. In Bosta Seller Lab (or via Bosta support): register the webhook URL:
   - URL: `https://[DOMAIN]/api/v1/webhooks/bosta`
   - Secret: the value returned by step 3
5. Bosta will call this endpoint on every delivery state change

### 5.2 Verify Shopify webhooks

Shopify webhooks are registered **automatically** when a merchant completes the OAuth flow.
The `RegisterShopifyWebhooksJob` fires as a JobRunr background job and registers these topics:

```
orders/create · orders/updated · orders/cancelled
products/create · products/update · app/uninstalled
```

Callback URLs follow the pattern `https://[DOMAIN]/webhooks/shopify/{type}/{action}`, e.g.:
`https://[DOMAIN]/webhooks/shopify/orders/create`

To verify after a merchant connects:
```bash
docker compose -f deploy/docker-compose.yml logs app | grep -i "webhook.*registered\|RegisterShopify"
```

GDPR mandatory endpoints (`customers/data_request`, `customers/redact`, `shop/redact`) are
handled by the same `ShopifyWebhookController` at the same URL pattern and must also be
registered in the Shopify Partner Dashboard under **App setup → GDPR webhooks**.
These must be in place before Shopify's PCD review can pass.

---

## 6. Deferred Bosta items (do with a throwaway delivery after live)

These two items were intentionally deferred to post-live because they require a live Bosta
account and an irreversible API call:

### 6.1 Cancel endpoint probe

> **⚠ Irreversible.** Only use a throwaway delivery `_id` — one created specifically for this
> test, never a real customer parcel.

Capture the three response shapes for `DELETE /api/v0/deliveries/{id}/terminate` (or whichever
cancel verb Bosta exposes on v0):
1. Success: delivery successfully cancelled
2. Terminal-state error: delivery already delivered/returned — cannot cancel
3. Already-cancelled error: idempotent re-cancel

Record the HTTP status codes, error codes, and response body structure for each shape. Then:
- Implement `BostaGateway.cancelDelivery()`
- Wire the `awaiting_pickup` → cancel branch in `BostaPickupService`
- Add the three shapes as fixture files under `src/test/resources/bosta/`

### 6.2 API version consistency check

The gateway is configured with `bosta.api-version: v2` (in `application.yml`) but the live
probe confirmed v0 is what the pilot account actually works against. Audit all
`BostaHttpGateway` path constructions to confirm which version string each endpoint uses and
whether v2 paths work on this account. Resolve the v0/v2 split before scaling to more tenants.

---

## 7. End-to-end verification

One full order cycle, either real or using Shopify's test-order feature:

| Step | What to check | Where to look if it fails |
|---|---|---|
| **1. Shopify order created** | Order appears in Traced with status `new` | `docker logs app \| grep "ingestOrder\|upsertOrder"` |
| **2. Operator creates Bosta delivery** | Bosta delivery created, `shipments` row inserted, order → `in_progress` | Traced UI → order detail; Bosta Seller Lab |
| **3. Mode-B match fires** | Webhook from Bosta triggers job; `shipments.bosta_delivery_id` populated | `docker logs app \| grep "tryMatchDelivery\|Mode-B"` |
| **4. AWB print** | `POST /api/v1/bosta/awb/print` returns PDF bytes | Response body is a PDF (starts with `%PDF`); check `BostaHttpGateway` logs |
| **5. Pickup manifest** | `POST /api/v1/bosta/pickup/schedule` returns manifest with shipment listed | Check `BostaPickupService` logs |
| **6. Delivery state → delivered** | Bosta sends state 45; `ShipmentLinkService` transitions order → `delivered` | `docker logs app \| grep "state.*45\|delivered"` |
| **7. Custody timeline** | Piece events show full chain: received → reserved → packed → with_courier → delivered | Traced UI → order → timeline |

---

## 8. Rollback

### 8.1 If bring-up fails (no data written yet)

```bash
docker compose -f deploy/docker-compose.yml down
```

Fix the issue (see §4.3 troubleshooting table), then re-run `compose up -d --build`.

### 8.2 Rollback to a previous image after a bad deploy

```bash
# Bring down the current stack
docker compose -f deploy/docker-compose.yml down

# Revert to the previous git commit
git log --oneline -5          # find the last good commit
git checkout [GOOD_COMMIT]    # or git revert HEAD

# Rebuild and start from the previous commit
docker compose -f deploy/docker-compose.yml up -d --build
```

> **Data safety note**: Supabase is external to the Docker stack. `compose down` does not
> touch the database. A failed deployment or rollback does not lose data.

### 8.3 Failed Flyway migration (partial schema state)

If Flyway fails mid-migration, the app exits and leaves the schema in a partial state.
Flyway marks the failed version in `flyway_schema_history` with `success = false`.

Recovery:
1. Fix the migration SQL in the repo
2. In Supabase SQL Editor, manually complete or roll back the partial DDL from the failed migration
3. Delete the failed row: `DELETE FROM flyway_schema_history WHERE success = false;`
4. Redeploy — Flyway will re-run the fixed migration

Do NOT set `baseline-on-migrate: true` as a workaround — it marks all existing migrations as
applied, which will silently skip future migrations that were never run.

---

## 9. Go-live checklist

Before handing the URL to pilots:

- [ ] `curl https://[DOMAIN]/actuator/health` returns `{"status":"UP"}`
- [ ] SPA loads at `https://[DOMAIN]/`
- [ ] HTTP → HTTPS redirect works
- [ ] Owner account created and can log in
- [ ] Bosta API key entered and webhook secret registered in Bosta Seller Lab
- [ ] At least one Shopify store connected (OAuth flow completes → webhooks registered)
- [ ] Supabase PITR enabled
- [ ] `APP_ENCRYPTION_KEY` backed up off-server
- [ ] `SENTRY_DSN` set and a test event visible in Sentry dashboard
- [ ] GDPR webhooks (`customers/data_request`, `customers/redact`, `shop/redact`) registered
      in Shopify Partner Dashboard
- [ ] Shopify PCD (Protected Customer Data) review submitted or in-flight
      (required for `customer_name` / `customer_phone` / `address` fields on orders)
- [ ] Supabase DB timezone confirmed: `SHOW timezone;` → `Africa/Cairo`
- [ ] End-to-end cycle (§7) completed with one test order

---

## Quick reference

```bash
# Deploy / rebuild
docker compose -f deploy/docker-compose.yml up -d --build

# Restart app only (after a git pull + rebuild)
docker compose -f deploy/docker-compose.yml up -d --build app

# Tail logs
docker compose -f deploy/docker-compose.yml logs -f [app|nginx]

# Stop everything (data safe — DB is external)
docker compose -f deploy/docker-compose.yml down

# Container health states
docker compose -f deploy/docker-compose.yml ps

# Health check
curl -s https://[DOMAIN]/actuator/health

# Renew cert manually
sudo certbot renew && docker compose -f deploy/docker-compose.yml exec nginx nginx -s reload
```
