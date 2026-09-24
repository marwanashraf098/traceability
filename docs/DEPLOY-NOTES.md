# Traced — Production Provisioning Runbook

**Target**: Hetzner CX32 (4 vCPU, 8 GB RAM) · Ubuntu 24.04  
**Stack**: Docker Compose — two services: `app` (Spring Boot) + `nginx` (TLS reverse proxy)  
**Database**: Supabase (external). No Postgres container in this stack.  
**Edge**: none — DNS at GoDaddy points straight at the server; nginx terminates TLS (no Cloudflare)

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

> **Local .env warning**: `APP_DB_USER` in `.env` **must** include the Supabase project-ref suffix
> (e.g. `app_user.jtkzpjaangjtkrepkqdz`) — omitting the suffix causes the Supabase pooler to reject
> the connection and silently fall back to `postgres`, bypassing RLS in local dev.
> Local `.env` must mirror prod so the `app_user` / RLS path is exercised locally.

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

DNS is at **GoDaddy**; there is **no Cloudflare** (or any other proxy) in front of the server.
Each hostname is a plain **A record** → `[SERVER_IP]`:
`app.tracedtech.com`, `tracedtech.com`, `www.tracedtech.com`, `returns.tracedtech.com`.
Clients connect to nginx directly, so nginx's `$remote_addr` is the real client IP. nginx has
no `real_ip` directives; see §11 for how the client IP and host reach the app.

### 2.2 Issue TLS certificates (as the server is today)

> **⚠ nginx will NOT start with a missing certificate.** `nginx -t` fails hard on any
> `ssl_certificate` path that doesn't exist, and nginx validates the WHOLE file — one missing
> certificate takes down every host. A new host's port-443 block is only added to
> `deploy/nginx.conf` **after** its certificate exists (see §10 for the returns host).

All certificates are issued by the host's **system certbot** in **webroot** mode. nginx serves
the HTTP-01 challenge from `/var/www/certbot` on every port-80 block
(`location /.well-known/acme-challenge/`), and `deploy/docker-compose.yml` mounts
`/var/www/certbot` and `/etc/letsencrypt` read-only into the nginx container.

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d app.tracedtech.com
sudo certbot certonly --webroot -w /var/www/certbot -d tracedtech.com -d www.tracedtech.com
sudo certbot certonly --webroot -w /var/www/certbot -d returns.tracedtech.com   # §10
```

| Certificate | Names |
|---|---|
| `/etc/letsencrypt/live/app.tracedtech.com/` | app.tracedtech.com |
| `/etc/letsencrypt/live/tracedtech.com/` | tracedtech.com, www.tracedtech.com |
| `/etc/letsencrypt/live/returns.tracedtech.com/` | returns.tracedtech.com (§10) |

> **Do not use `--standalone`.** It needs port 80 free, which it never is while nginx runs, so a
> standalone certificate cannot renew. **2026-09-24:** the app.tracedtech.com certificate was
> found expiring the same day — its renewal config was `authenticator = standalone` (from the
> original first-boot issuance) and every renewal had failed to bind port 80. It was re-issued
> via `--webroot`, which also switched its renewal config to webroot. Check any certificate with
> `sudo grep authenticator /etc/letsencrypt/renewal/*.conf` — every line must say `webroot`.

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

### 2.4 Auto-renewal (certbot.timer + deploy hook)

Renewal is the **system `certbot.timer`** (installed by the certbot package; runs
`certbot renew` twice a day). There is **no root crontab** entry — do not add one.

- `certbot renew` replays each certificate's saved method, so every certificate issued with
  `--webroot -w /var/www/certbot` renews through the running nginx with no downtime.
- A **deploy hook** at `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` reloads nginx
  after each successful renewal, so the new certificate is picked up.

Checks:
```bash
systemctl list-timers | grep certbot                     # timer active, next run shown
sudo certbot renew --dry-run                             # every certificate must succeed
sudo grep authenticator /etc/letsencrypt/renewal/*.conf  # all 'webroot'
ls -l /etc/letsencrypt/renewal-hooks/deploy/             # reload-nginx.sh present, executable
```

The repository on the server is at **`/home/traced/traceability`**.

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

### 3.2 Confirm daily backups are active (NFR-5)

**Pilot backup strategy**: Supabase Pro includes **daily backups with 7-day retention** at no
extra cost. No add-on or manual step is required — they are enabled by default on Pro.

**RPO (Recovery Point Objective)**: up to ~24 hours of data loss in a worst-case scenario
(the period since the last nightly snapshot). In practice most of this window is partially
reconstructable: Shopify is the authoritative order source (re-import), Bosta state can be
re-synced, and physical stock can be rescanned.

Confirm in Supabase Dashboard → **Database → Backups** — you should see a list of daily
snapshots. No action needed if they are already listed.

**Restore procedure** (if needed):
1. Supabase Dashboard → Database → Backups → pick the daily snapshot to restore from
2. Click **Restore** — this replaces the entire project database with the snapshot in-place
   (unlike PITR, no new project is created)
3. Before the restore begins, stop the app to prevent writes landing mid-restore:
   `docker compose -f deploy/docker-compose.yml stop app`
4. Wait for the restore to complete (Supabase emails when done)
5. Bring the app back up — it reconnects to the same project URL, no `.env` changes needed:
   `docker compose -f deploy/docker-compose.yml start app`
6. Re-run §3.1 (`ALTER DATABASE postgres SET timezone TO 'Africa/Cairo';`) — restoring a
   backup may reset the GUC to the project default

> **To upgrade to PITR at scale**: requires the Supabase PITR add-on + Small compute
> (~$110/mo total). Note that enabling PITR **replaces** daily backups — you cannot run both
> simultaneously. Defer this until pilot has validated the product.

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
- [ ] Supabase daily backups visible in Dashboard → Database → Backups (included with Pro, no action needed)
- [ ] `APP_ENCRYPTION_KEY` backed up off-server
- [ ] `SENTRY_DSN` set and a test event visible in Sentry dashboard
- [ ] GDPR webhooks (`customers/data_request`, `customers/redact`, `shop/redact`) registered
      in Shopify Partner Dashboard
- [ ] Shopify PCD (Protected Customer Data) review submitted or in-flight
      (required for `customer_name` / `customer_phone` / `address` fields on orders)
- [ ] Supabase DB timezone confirmed: `SHOW timezone;` → `Africa/Cairo`
- [ ] End-to-end cycle (§7) completed with one test order

---

## 10. Returns portal host — returns.tracedtech.com (Step 4e-B)

The customer returns portal is served on its own host. nginx maps `/{slug}` to the app's
`/portal.html` and proxies only `/assets/` and `/api/v1/portal/**`; everything else is 404 there.
It ships in **two deploys** so the new certificate can be issued in between.

**1. DNS** — GoDaddy A record `returns.tracedtech.com` → `[SERVER_IP]`. *(Done.)*
Check: `dig +short returns.tracedtech.com` returns the server IP.

**2. Deploy group 1** (everything except the returns port-443 block: the portal bundle,
`GET /portal.html` permitAll, portal rate limit 30r/m burst 15 with a JSON 429, and the returns
**port-80** block). It has no certificate dependency.
```bash
cd /home/traced/traceability
git pull
sudo docker compose -f deploy/docker-compose.yml up -d --build app
sudo docker compose -f deploy/docker-compose.yml restart nginx   # re-reads nginx.conf
curl -sI http://returns.tracedtech.com/ | head -3                # 301 → https://returns.tracedtech.com/
```

**3. Issue the certificate** (webroot — nginx keeps running):
```bash
sudo certbot certonly --webroot -w /var/www/certbot -d returns.tracedtech.com
sudo ls /etc/letsencrypt/live/returns.tracedtech.com/          # fullchain.pem, privkey.pem
sudo grep authenticator /etc/letsencrypt/renewal/returns.tracedtech.com.conf   # webroot
```

**4. Deploy group 2** (the single commit adding the returns port-443 block). Test the NEW file
before restarting — `nginx -t` inside the running container would read the file it started
with, because `nginx.conf` is a single-file bind mount:
```bash
cd /home/traced/traceability
git pull
sudo docker run --rm --network deploy_internal \
  -v "$PWD/deploy/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v /etc/letsencrypt:/etc/letsencrypt:ro \
  nginx:1.27-alpine nginx -t          # network: the compose network (`docker network ls`)
# only if the test is successful:
sudo docker compose -f deploy/docker-compose.yml restart nginx
```

**5. Verify:**
```bash
curl -sI https://returns.tracedtech.com/[slug] | grep -iE '^HTTP|content-security|x-frame'
curl -s  https://returns.tracedtech.com/api/v1/portal/[slug]/config          # JSON config
curl -sI https://returns.tracedtech.com/api/v1/auth/login | head -1          # 404
curl -sI https://returns.tracedtech.com/actuator/health | head -1            # 404
sudo certbot renew --dry-run
```

---

## 11. nginx — client identity, unknown hosts, testing a change

**Client IP and host (no CDN in front).** Every location that proxies to the app overwrites
the identity headers instead of passing client values through:

| Header | Value sent to the app |
|---|---|
| `Host`, `X-Forwarded-Host` | `$host` — always one of the real server names (see default servers) |
| `X-Forwarded-Proto` | `https` |
| `X-Real-IP`, `X-Forwarded-For` | `$remote_addr` (overwritten, never appended) |
| `Forwarded`, `CF-Connecting-IP`, `CF-Ray` | `""` (dropped) |

The app runs `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`): it trusts
`X-Forwarded-For/-Proto/-Host` only from an internal proxy address (the Docker network — the
app's port is never published), takes the rightmost non-proxy `X-Forwarded-For` entry as the
client, and ignores the RFC 7239 `Forwarded` header. So the demo rate limit and
`demo_leads.ip` see the real client IP, and redirects are `https://app.tracedtech.com/…`.

**Default servers.** `listen 80 default_server` → `return 444` (connection closed, no
response); `listen 443 ssl default_server` → `ssl_reject_handshake on` (no certificate needed).
A request whose Host/SNI isn't one of `app.tracedtech.com`, `returns.tracedtech.com`,
`tracedtech.com`, `www.tracedtech.com` never reaches the app. A valid SNI with a different
`Host:` header gets nginx's own `421`. Adding a new hostname = add its own server blocks
(port 80 with the ACME location first; port 443 only after its certificate exists — §10).

**Testing an nginx change before restarting.** `deploy/nginx.conf` is a single-file bind mount:
`nginx -t` inside the running container reads the file the container started with, not the one
`git pull` just wrote. Test the new file in a one-off container on the compose network, with
the real certificates:
```bash
cd /home/traced/traceability
sudo docker run --rm --network deploy_internal \
  -v "$PWD/deploy/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v /etc/letsencrypt:/etc/letsencrypt:ro \
  nginx:1.27-alpine nginx -t      # network name: `docker network ls` (compose project + _internal)
# only if "test is successful":
sudo docker compose -f deploy/docker-compose.yml restart nginx
```
After restarting, check each host:
```bash
curl -sI https://app.tracedtech.com/ | head -1                    # 200
curl -sI https://returns.tracedtech.com/[slug] | head -1           # 200
curl -sI https://tracedtech.com/ | head -1                         # 200
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: nope.example' http://[SERVER_IP]/   # 000 (444)
```

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

# Renew certs manually (normally certbot.timer does this; the deploy hook reloads nginx)
sudo certbot renew
```
