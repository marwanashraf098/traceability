# Deploy Notes — Traced on Hetzner CX32

**Status**: Prep 2 complete (files written, not yet provisioned).  
**Target**: Hetzner CX32 (4 vCPU, 8 GB RAM) — Ubuntu 24.04.  
**Stack**: Docker Compose — app container + nginx reverse proxy.  
**Database**: Supabase (external, session-mode pooler).  
**Edge**: Cloudflare free (DNS-proxied, TLS offload optional — see TLS section).

---

## Environment variables

All secrets are in `.env` at the repo root on the server.  
**Never commit `.env`** — it is gitignored. Use `.env.example` as the template.

| Variable | Required | Notes |
|---|---|---|
| `DATABASE_URL` | ✅ | Session-mode pooler, port **5432** (not 6543) |
| `APP_DB_USER` | ✅ | `app_user.PROJECT_REF` — RLS-enforced role |
| `APP_DB_PASSWORD` | ✅ | |
| `FLYWAY_DB_URL` | ✅ | Owner-role URL for schema migrations |
| `FLYWAY_DB_USER` | ✅ | `postgres.PROJECT_REF` |
| `FLYWAY_DB_PASSWORD` | ✅ | |
| `JWT_SECRET` | ✅ | Base64, ≥32 bytes (`openssl rand -base64 32`) |
| `APP_ENCRYPTION_KEY` | ✅ | Base64, exactly 32 bytes (`openssl rand -base64 32`) |
| `SHOPIFY_CLIENT_ID` | ✅ | From Shopify Partner Dashboard |
| `SHOPIFY_CLIENT_SECRET` | ✅ | |
| `SHOPIFY_REDIRECT_URI` | ✅ | `https://YOUR_DOMAIN/auth/shopify/callback` |
| `SHOPIFY_APP_URL` | ✅ | `https://YOUR_DOMAIN` |
| `SHOPIFY_WEBHOOK_BASE_URL` | ✅ | `https://YOUR_DOMAIN` |
| `SHOPIFY_API_VERSION` | optional | Default `2026-04` |
| `BOSTA_BASE_URL` | optional | Default `https://app.bosta.co` |
| `SENTRY_DSN` | optional | Leave empty to disable Sentry |
| `SENTRY_ENVIRONMENT` | optional | Default `production` |

---

## TLS / Certificates

Nginx terminates TLS. Certs are issued by Let's Encrypt via **certbot on the host** and
mounted read-only into the nginx container at `/etc/letsencrypt`.

### Cert assumption

The compose mounts:
- `/etc/letsencrypt` → `/etc/letsencrypt:ro`
- `/var/www/certbot`  → `/var/www/certbot:ro` (HTTP-01 challenge webroot)

### First-time cert issuance (run on the host, before `compose up`)

```bash
# Install certbot
sudo apt install -y certbot

# Issue cert (standalone mode — port 80 must be free)
sudo certbot certonly --standalone -d YOUR_DOMAIN --agree-tos -m your@email.com

# Or if you prefer webroot (after nginx is running on port 80 with HTTP-only config first):
# sudo certbot certonly --webroot -w /var/www/certbot -d YOUR_DOMAIN
```

### Auto-renewal (cron on host)

```cron
0 3 * * * certbot renew --quiet && docker compose -f /opt/traced/deploy/docker-compose.yml exec nginx nginx -s reload
```

### Cloudflare TLS mode

If Cloudflare is set to **Full (strict)** mode, the origin cert must be valid.
Let's Encrypt covers this. If you switch to Cloudflare origin certificates instead,
update the cert paths in `deploy/nginx.conf`.

---

## Deploy from scratch

```bash
# 1. Clone repo on the server
git clone https://github.com/marwanashraf098/traceability.git /opt/traced
cd /opt/traced

# 2. Create .env from template
cp .env.example .env
# Edit .env with real values
nano .env

# 3. Replace YOUR_DOMAIN in nginx.conf
sed -i 's/YOUR_DOMAIN/app.yourdomain.com/g' deploy/nginx.conf

# 4. Issue TLS cert (see TLS section above)

# 5. Build and start
docker compose -f deploy/docker-compose.yml up -d --build

# 6. Tail logs
docker compose -f deploy/docker-compose.yml logs -f
```

---

## Day-to-day commands

```bash
# Restart app only (after code push + rebuild)
docker compose -f deploy/docker-compose.yml up -d --build app

# Full restart
docker compose -f deploy/docker-compose.yml restart

# Stop everything
docker compose -f deploy/docker-compose.yml down

# View app logs
docker compose -f deploy/docker-compose.yml logs -f app

# Check health
curl -s https://YOUR_DOMAIN/actuator/health
```

---

## Runbook (placeholder — expand in Prep 3)

- [ ] Server provisioning (Hetzner CX32, firewall rules: 22/80/443 only)
- [ ] Docker + Docker Compose install on Ubuntu 24.04
- [ ] DNS A-record pointing to server IP
- [ ] Cloudflare proxy enable + SSL mode → Full (strict)
- [ ] `.env` filled in and permissions `chmod 600 .env`
- [ ] TLS cert issued
- [ ] First `compose up --build`
- [ ] Shopify webhook re-registration (update `SHOPIFY_WEBHOOK_BASE_URL` → new domain, trigger re-register)
- [ ] Bosta account API key entry via Settings UI
- [ ] Smoke-test: login, create shipment, print AWB

---

## Supabase DB timezone note

The inventory 30-day windows, never-received, and stuck-shipment detectors use
Postgres `now()` on the DB server. The DB must be set to `Africa/Cairo` so these
windows roll over at Cairo midnight (not UTC midnight):

```sql
ALTER DATABASE postgres SET timezone TO 'Africa/Cairo';
```

Run this once in the Supabase SQL editor. Verify with `SHOW timezone;`.
