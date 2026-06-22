import { useState, useEffect, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { getConnections, shopifyInitiate, bostaConnect, ConnectionsStatus } from '../api'

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
        </div>
      )}
    </div>
  )
}
