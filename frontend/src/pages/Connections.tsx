import { useState, useEffect, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { getConnections, shopifyInitiate, shopifyCustomConnect, bostaConnect, ConnectionsStatus } from '../api'

// ── Status badge helpers ──────────────────────────────────────────────────────

function ConnectedBadge({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-small font-medium text-success">
      <span className="w-2 h-2 rounded-full bg-success flex-shrink-0" />
      {label}
    </span>
  )
}

function DisconnectedBadge({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-small text-muted">
      <span className="w-2 h-2 rounded-full bg-line flex-shrink-0" />
      {label}
    </span>
  )
}

// ── Shopify card ──────────────────────────────────────────────────────────────

function ShopifyCard({ shopify }: { shopify: ConnectionsStatus['shopify'] }) {
  const { t } = useTranslation()
  const [shop,      setShop]      = useState('')
  const [loading,   setLoading]   = useState(false)
  const [error,     setError]     = useState('')

  const SHOP_RE = /^[a-zA-Z0-9][a-zA-Z0-9-]*\.myshopify\.com$/

  async function handleConnect(e: FormEvent) {
    e.preventDefault()
    setError('')
    const trimmed = shop.trim()
    if (!SHOP_RE.test(trimmed)) {
      setError(t('connections.shopify.domainInvalid'))
      return
    }
    setLoading(true)
    try {
      const res = await shopifyInitiate(trimmed)
      window.location.href = res.consentUrl
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('400')) {
        setError(t('connections.shopify.domainInvalid'))
      } else {
        setError(t('connections.shopify.error'))
      }
      setLoading(false)
    }
  }

  return (
    <div className="card p-5 space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          {/* Shopify bag icon */}
          <div className="w-10 h-10 rounded-xl bg-[#5a31f4]/10 border border-[#5a31f4]/20 flex items-center justify-center flex-shrink-0">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#5a31f4" strokeWidth="1.75">
              <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
              <line x1="3" y1="6" x2="21" y2="6"/>
              <path d="M16 10a4 4 0 01-8 0"/>
            </svg>
          </div>
          <h2 className="text-h3 text-primary">{t('connections.shopify.title')}</h2>
        </div>
        {shopify.connected
          ? <ConnectedBadge label={t('connections.shopify.connected')} />
          : <DisconnectedBadge label={t('connections.shopify.disconnected')} />
        }
      </div>

      {shopify.connected ? (
        <div className="space-y-2 text-small">
          <div className="flex gap-2">
            <span className="text-muted w-28 flex-shrink-0">{t('connections.shopify.shopDomain')}</span>
            <span className="text-primary font-medium">{shopify.shopDomain}</span>
          </div>
          {shopify.importStatus && (
            <div className="flex gap-2">
              <span className="text-muted w-28 flex-shrink-0">{t('connections.shopify.importStatus')}</span>
              <span className="text-primary">{shopify.importStatus}</span>
            </div>
          )}
          <div className="flex gap-2">
            <span className="text-muted w-28 flex-shrink-0">{t('connections.shopify.lastSync')}</span>
            <span className="text-primary">
              {shopify.lastSyncAt
                ? new Date(shopify.lastSyncAt).toLocaleString()
                : t('connections.never')}
            </span>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <p className="text-small text-muted">{t('connections.shopify.connectTitle')}</p>

          {error && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}

          <form onSubmit={handleConnect} className="space-y-3">
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="shopDomain">
                {t('connections.shopify.shopLabel')}
              </label>
              <input
                id="shopDomain"
                type="text"
                value={shop}
                onChange={e => setShop(e.target.value)}
                className="input"
                placeholder={t('connections.shopify.shopPlaceholder')}
                dir="ltr"
                disabled={loading}
                autoComplete="off"
              />
            </div>
            <button
              type="submit"
              disabled={loading || !shop.trim()}
              className="btn btn-brand"
            >
              {loading ? t('connections.shopify.connecting') : t('connections.shopify.connectBtn')}
            </button>
          </form>
        </div>
      )}
    </div>
  )
}

// ── Bosta card ────────────────────────────────────────────────────────────────

function BostaCard({ bosta, onConnected }: { bosta: ConnectionsStatus['bosta']; onConnected: () => void }) {
  const { t } = useTranslation()
  const [apiKey,    setApiKey]    = useState('')
  const [loading,   setLoading]   = useState(false)
  const [error,     setError]     = useState('')
  const [showForm,  setShowForm]  = useState(false)

  async function handleConnect(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (!apiKey.trim()) return
    setLoading(true)
    try {
      await bostaConnect(apiKey.trim())
      setApiKey('')
      setShowForm(false)
      onConnected()
    } catch {
      setError(t('connections.bosta.error'))
    } finally {
      setLoading(false)
    }
  }

  const showConnect = !bosta.connected || showForm

  return (
    <div className="card p-5 space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-accent/10 border border-accent/20 flex items-center justify-center flex-shrink-0">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--color-accent, #f59e0b)" strokeWidth="1.75">
              <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>
            </svg>
          </div>
          <h2 className="text-h3 text-primary">{t('connections.bosta.title')}</h2>
        </div>
        {bosta.connected
          ? <ConnectedBadge label={t('connections.bosta.connected')} />
          : <DisconnectedBadge label={t('connections.bosta.disconnected')} />
        }
      </div>

      {bosta.connected && !showForm && (
        <div className="space-y-2 text-small">
          {bosta.businessName && (
            <div className="flex gap-2">
              <span className="text-muted w-28 flex-shrink-0">{t('connections.bosta.businessName')}</span>
              <span className="text-primary font-medium">{bosta.businessName}</span>
            </div>
          )}
          {bosta.pickupMode && (
            <div className="flex gap-2">
              <span className="text-muted w-28 flex-shrink-0">{t('connections.bosta.pickupMode')}</span>
              <span className="text-primary">{bosta.pickupMode}</span>
            </div>
          )}
          <button
            onClick={() => setShowForm(true)}
            className="btn btn-outline text-small mt-2"
          >
            {t('connections.bosta.reconnect')}
          </button>
        </div>
      )}

      {showConnect && (
        <div className="space-y-3">
          <p className="text-small text-muted">{t('connections.bosta.connectTitle')}</p>

          {error && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}

          <form onSubmit={handleConnect} className="space-y-3">
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="bostaApiKey">
                {t('connections.bosta.apiKeyLabel')}
              </label>
              <input
                id="bostaApiKey"
                type="password"
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
                className="input"
                placeholder={t('connections.bosta.apiKeyPlaceholder')}
                dir="ltr"
                disabled={loading}
                autoComplete="off"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={loading || !apiKey.trim()}
                className="btn btn-brand"
              >
                {loading ? t('connections.bosta.connecting') : t('connections.bosta.connectBtn')}
              </button>
              {bosta.connected && (
                <button
                  type="button"
                  onClick={() => { setShowForm(false); setError('') }}
                  className="btn btn-ghost"
                >
                  {t('common.cancel')}
                </button>
              )}
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ── Shopify Custom App card (DEV/pilot only, shown when customAppAvailable=true) ─────────

function ShopifyCustomAppCard({
  shopifyCustomApp,
  onConnected,
}: {
  shopifyCustomApp: ConnectionsStatus['shopifyCustomApp']
  onConnected: () => void
}) {
  const [shopDomain,  setShopDomain]  = useState('')
  const [adminToken,  setAdminToken]  = useState('')
  const [apiSecret,   setApiSecret]   = useState('')
  const [loading,     setLoading]     = useState(false)
  const [error,       setError]       = useState('')
  const [showForm,    setShowForm]    = useState(false)

  const SHOP_RE = /^[a-zA-Z0-9][a-zA-Z0-9-]*\.myshopify\.com$/

  async function handleConnect(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (!SHOP_RE.test(shopDomain.trim())) {
      setError('Invalid shop domain — must end with .myshopify.com')
      return
    }
    if (!adminToken.trim().startsWith('shpat_')) {
      setError('Admin token must start with shpat_ (permanent token). Rotating tokens are not supported.')
      return
    }
    if (!apiSecret.trim()) {
      setError('API secret is required')
      return
    }
    setLoading(true)
    try {
      await shopifyCustomConnect(shopDomain.trim(), adminToken.trim(), apiSecret.trim())
      setShopDomain('')
      setAdminToken('')
      setApiSecret('')
      setShowForm(false)
      onConnected()
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('400')) {
        setError('Invalid credentials or rotating token detected. Use a permanent shpat_ admin token.')
      } else if (msg.includes('403')) {
        setError('Custom-app connection is not enabled in this environment.')
      } else if (msg.includes('409')) {
        setError('This shop domain is already connected to a different account.')
      } else {
        setError('Connection failed. Check your credentials and try again.')
      }
    } finally {
      setLoading(false)
    }
  }

  const showConnect = !shopifyCustomApp.connected || showForm

  return (
    <div className="card p-5 space-y-4 border-2 border-amber-200 bg-amber-50/30">
      {/* Header */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-amber-100 border border-amber-300 flex items-center justify-center flex-shrink-0">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#d97706" strokeWidth="1.75">
              <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
              <line x1="3" y1="6" x2="21" y2="6"/>
              <path d="M16 10a4 4 0 01-8 0"/>
            </svg>
          </div>
          <div>
            <h2 className="text-h3 text-primary">Shopify (Custom App)</h2>
            <span className="text-xs text-amber-700 font-medium">PILOT / TEMPORARY</span>
          </div>
        </div>
        {shopifyCustomApp.connected
          ? <ConnectedBadge label="Connected" />
          : <DisconnectedBadge label="Not connected" />
        }
      </div>

      {/* Amber banner when connected */}
      {shopifyCustomApp.connected && !showForm && (
        <div className="text-xs text-amber-800 bg-amber-100 border border-amber-300 rounded px-3 py-2">
          Warning: This is a temporary custom-app connection. It will be replaced by the full OAuth integration before production launch.
        </div>
      )}

      {/* Connected state */}
      {shopifyCustomApp.connected && !showForm && (
        <div className="space-y-2 text-small">
          <div className="flex gap-2">
            <span className="text-muted w-28 flex-shrink-0">Shop domain</span>
            <span className="text-primary font-medium">{shopifyCustomApp.shopDomain}</span>
          </div>
          {shopifyCustomApp.importStatus && (
            <div className="flex gap-2">
              <span className="text-muted w-28 flex-shrink-0">Import status</span>
              <span className="text-primary">{shopifyCustomApp.importStatus}</span>
            </div>
          )}
          <div className="flex gap-2">
            <span className="text-muted w-28 flex-shrink-0">Last sync</span>
            <span className="text-primary">
              {shopifyCustomApp.lastSyncAt
                ? new Date(shopifyCustomApp.lastSyncAt).toLocaleString()
                : 'Never'}
            </span>
          </div>
          <button
            onClick={() => setShowForm(true)}
            className="btn btn-outline text-small mt-2"
          >
            Reconnect
          </button>
        </div>
      )}

      {/* Connect form */}
      {showConnect && (
        <div className="space-y-3">
          <p className="text-small text-muted">
            Connect using a custom app from Shopify Partner Dashboard.
            Required scopes: <code className="text-xs bg-line/20 px-1 rounded">read_orders, read_products, read_fulfillments, write_webhooks</code>
          </p>
          <p className="text-xs text-amber-700">
            Admin token must start with <code className="bg-amber-100 px-1 rounded">shpat_</code> (permanent, non-rotating).
          </p>

          {error && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}

          <form onSubmit={handleConnect} className="space-y-3">
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="customShopDomain">
                Shop domain
              </label>
              <input
                id="customShopDomain"
                type="text"
                value={shopDomain}
                onChange={e => setShopDomain(e.target.value)}
                className="input"
                placeholder="your-store.myshopify.com"
                dir="ltr"
                disabled={loading}
                autoComplete="off"
              />
            </div>
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="customAdminToken">
                Admin API access token (shpat_...)
              </label>
              <input
                id="customAdminToken"
                type="password"
                value={adminToken}
                onChange={e => setAdminToken(e.target.value)}
                className="input"
                placeholder="shpat_..."
                dir="ltr"
                disabled={loading}
                autoComplete="off"
              />
            </div>
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="customApiSecret">
                API secret key
              </label>
              <input
                id="customApiSecret"
                type="password"
                value={apiSecret}
                onChange={e => setApiSecret(e.target.value)}
                className="input"
                placeholder="API secret from Partner Dashboard"
                dir="ltr"
                disabled={loading}
                autoComplete="off"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={loading || !shopDomain.trim() || !adminToken.trim() || !apiSecret.trim()}
                className="btn btn-brand"
              >
                {loading ? 'Connecting...' : 'Connect custom app'}
              </button>
              {shopifyCustomApp.connected && (
                <button
                  type="button"
                  onClick={() => { setShowForm(false); setError('') }}
                  className="btn btn-ghost"
                >
                  Cancel
                </button>
              )}
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ── Connections page ──────────────────────────────────────────────────────────

export default function Connections() {
  const { t } = useTranslation()
  const [status,  setStatus]  = useState<ConnectionsStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      setStatus(await getConnections())
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('connections.title')}</h1>
        <p className="text-small text-muted mt-1">{t('connections.subtitle')}</p>
      </div>

      {loading && (
        <div className="flex items-center justify-center py-16">
          <svg className="animate-spin w-6 h-6 text-brand" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
          </svg>
        </div>
      )}

      {!loading && error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
          {error}
        </div>
      )}

      {!loading && status && (
        <div className="grid gap-4 sm:grid-cols-2">
          <ShopifyCard shopify={status.shopify} />
          <BostaCard   bosta={status.bosta} onConnected={load} />
          {status.customAppAvailable && (
            <ShopifyCustomAppCard
              shopifyCustomApp={status.shopifyCustomApp}
              onConnected={load}
            />
          )}
        </div>
      )}
    </div>
  )
}
