import { useState, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import {
  getConnections,
  bostaConnect, bostaRegenerateSecret, bostaSync, bostaGetSyncStatus,
  ConnectionsStatus, BostaBackfillStatus,
} from '../../api'
import { Button } from '../../components/ui'
import { CopyRow } from './connections/CopyRow'
import ShopifyConnectionCard from './connections/ShopifyConnectionCard'
import BostaSetupWizard from './connections/BostaSetupWizard'

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

// ── Webhook secret reveal panel (shown once after connect or regenerate) ──────
//
// Restyled to sit under the same step-shell heading/spacing as BostaSetupWizard
// (StepBody-style h4 + muted body, no bordered/tinted box) for visual parity
// with the Shopify wizard's step content — cosmetic only. The show-once secret
// contract, the CopyRows, and the underlying values are unchanged.
//
// The "authorization key name" Bosta's webhook config asks for is the fixed
// HTTP header name "Authorization" — a literal constant, not a per-store
// value, so it is never passed through t() (translating an HTTP header name
// would break the paste-in). It gets its own CopyRow for copy-paste parity
// with the other two rows; the two rows that actually carry secret material
// remain the Bearer value and the raw secret.

function WebhookSecretReveal({ secret, onDone }: { secret: string; onDone: () => void }) {
  const { t } = useTranslation()
  const webhookUrl = `${window.location.origin}/api/v1/webhooks/bosta`
  const authKeyValue = `Bearer ${secret}`

  return (
    <div className="space-y-4">
      <div>
        <h4 className="text-body-lg font-semibold text-primary mb-1">
          {t('connections.bosta.webhookSetup')}
        </h4>
        <p className="text-small font-medium text-warning flex items-center gap-1.5">
          <span aria-hidden className="flex-shrink-0">⚠</span>
          {t('connections.bosta.secretWarning')}
        </p>
        <p className="text-xs text-muted mt-0.5">
          {t('connections.bosta.secretWarningDetail')}
        </p>
      </div>

      {/* Copyable rows */}
      <div className="space-y-3">
        <CopyRow
          label={t('connections.bosta.webhookUrlLabel')}
          value={webhookUrl}
          copyLabel={t('connections.bosta.copy')}
          copiedLabel={t('connections.bosta.copied')}
        />
        <CopyRow
          label={t('connections.bosta.authKeyNameLabel')}
          value="Authorization"
          copyLabel={t('connections.bosta.copy')}
          copiedLabel={t('connections.bosta.copied')}
        />
        <CopyRow
          label={t('connections.bosta.authKeyLabel')}
          value={authKeyValue}
          hint={t('connections.bosta.authKeyHint')}
          copyLabel={t('connections.bosta.copy')}
          copiedLabel={t('connections.bosta.copied')}
        />
        <CopyRow
          label={t('connections.bosta.rawSecretLabel')}
          value={secret}
          copyLabel={t('connections.bosta.copy')}
          copiedLabel={t('connections.bosta.copied')}
        />
      </div>

      <Button variant="primary" className="w-full" onClick={onDone}>
        {t('connections.bosta.doneBtn')}
      </Button>
    </div>
  )
}

// ── Bosta card — state machine wrapper ─────────────────────────────────────────
//
// uiState mirrors ShopifyConnectionCard's override/derived-state pattern, scaled
// down to what Bosta actually needs: no OAuth review, no needs_reauth/error
// equivalent (courier_accounts.status never leaves 'active' in practice — see
// ConnectionsController), no choose-method fork (Bosta has one connect path).
// `override` holds local wizard state; null means "trust bosta.connected".
// Clearing it (onBack, or after a successful connect) always resolves back to
// whichever real state applies — 'connected' if already connected (the
// Reconnect path), 'disconnected' otherwise — with no separate exit target
// needed for either case.

type BostaUiState = 'disconnected' | 'wizard' | 'connected'

function BostaCard({ bosta, onConnected }: { bosta: ConnectionsStatus['bosta']; onConnected: () => Promise<void> }) {
  const { t } = useTranslation()
  const [override,       setOverride]      = useState<'wizard' | null>(null)
  const [syncing,        setSyncing]       = useState(false)
  const [syncError,      setSyncError]     = useState('')
  const [syncStatus,     setSyncStatus]    = useState<BostaBackfillStatus | null>(null)
  const [revealSecret,   setRevealSecret]  = useState<string | null>(null)
  const [regenerating,   setRegenerating]  = useState(false)
  const [regenerateErr,  setRegenerateErr] = useState('')

  const uiState: BostaUiState = override ?? (bosta.connected ? 'connected' : 'disconnected')

  useEffect(() => {
    if (bosta.connected) {
      bostaGetSyncStatus().then(setSyncStatus).catch(() => {/* non-critical */})
    }
  }, [bosta.connected])

  // Passed to BostaSetupWizard as onConnect — errors propagate back to the
  // wizard's own step-5 form so it can show them inline, exactly as the old
  // single-form handleConnect did.
  async function handleWizardConnect(apiKey: string) {
    return bostaConnect(apiKey)
  }

  // Passed to BostaSetupWizard as onSuccess. onConnected() (the parent's
  // getConnections() reload) is awaited BEFORE clearing override, so
  // bosta.connected is already true by the time uiState re-derives — avoids a
  // one-frame flash back to 'disconnected' (same ordering as
  // ShopifyConnectionCard.handleWizardSubmit).
  async function handleWizardSuccess(webhookSecret: string) {
    await onConnected()
    setOverride(null)
    setRevealSecret(webhookSecret)  // show-once reveal panel
  }

  async function handleSync() {
    setSyncError('')
    setSyncing(true)
    try {
      await bostaSync()
      setTimeout(() => {
        bostaGetSyncStatus().then(setSyncStatus).catch(() => {/* non-critical */})
      }, 1500)
    } catch {
      setSyncError(t('connections.bosta.syncError'))
    } finally {
      setSyncing(false)
    }
  }

  const handleRegenerate = useCallback(async () => {
    if (!window.confirm(
      `${t('connections.bosta.regenerateConfirmTitle')}\n\n${t('connections.bosta.regenerateConfirmMsg')}`
    )) return
    setRegenerateErr('')
    setRegenerating(true)
    try {
      const result = await bostaRegenerateSecret()
      setRevealSecret(result.webhookSecret)
    } catch {
      setRegenerateErr(t('connections.bosta.regenerateError'))
    } finally {
      setRegenerating(false)
    }
  }, [t])

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

      {/* One-time secret reveal panel — shown after connect or regenerate */}
      {revealSecret && (
        <WebhookSecretReveal
          secret={revealSecret}
          onDone={() => setRevealSecret(null)}
        />
      )}

      {uiState === 'connected' && !revealSecret && (
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

          {/* Sync + management section */}
          <div className="pt-2 border-t border-line/40 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <button
                onClick={handleSync}
                disabled={syncing}
                title={t('connections.bosta.syncTooltip')}
                className="btn btn-outline text-small"
              >
                {syncing ? t('connections.bosta.syncing') : t('connections.bosta.syncBtn')}
              </button>
              <button
                onClick={() => { setOverride('wizard'); setRevealSecret(null) }}
                className="btn btn-ghost text-small"
              >
                {t('connections.bosta.reconnect')}
              </button>
              <button
                onClick={handleRegenerate}
                disabled={regenerating}
                className="btn btn-ghost text-small text-muted"
              >
                {regenerating ? t('connections.bosta.regenerating') : t('connections.bosta.regenerateBtn')}
              </button>
            </div>
            {syncError && <p className="text-xs text-danger">{syncError}</p>}
            {regenerateErr && <p className="text-xs text-danger">{regenerateErr}</p>}
            {syncStatus && (
              <p className="text-xs text-muted">
                {t('connections.bosta.lastSync')}:{' '}
                {syncStatus.lastBackfillAt
                  ? new Date(syncStatus.lastBackfillAt).toLocaleString()
                  : t('connections.never')}
                {syncStatus.lastBackfillEnqueued > 0 && (
                  <> &middot; {t('connections.bosta.syncQueued', { count: syncStatus.lastBackfillEnqueued })}</>
                )}
              </p>
            )}
          </div>
        </div>
      )}

      {uiState === 'disconnected' && (
        <div className="space-y-3">
          <p className="text-small text-muted">{t('connections.bosta.connectTitle')}</p>
          <Button variant="primary" className="w-full" onClick={() => setOverride('wizard')}>
            {t('connections.bosta.connectBtn')}
          </Button>
        </div>
      )}

      {uiState === 'wizard' && (
        <BostaSetupWizard
          onBack={() => setOverride(null)}
          onConnect={handleWizardConnect}
          onSuccess={handleWizardSuccess}
        />
      )}
    </div>
  )
}

// ── Connections tab ────────────────────────────────────────────────────────────
//
// readOnly (Manager only — Owner and Worker never pass true here: Worker never
// mounts this tab at all) wraps the whole grid in a native <fieldset disabled>.
// That cascades disabled to every descendant input/button/select without touching
// any of the cards' own gating or data logic above — status stays fully visible,
// only the interactive connect/edit/copy controls stop responding. `display:
// contents` keeps the fieldset out of the box model so it doesn't break the
// sm:grid-cols-2 layout of its children.

export default function ConnectionsTab({ readOnly }: { readOnly: boolean }) {
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

  // Polls getConnections() while the async install chain (OAuth callback -> JobRunr
  // import -> Traced Main Warehouse location provisioning) is still in flight, so
  // ShopifyConnectionCard's importStatus/lastSyncAt props actually change once it
  // finishes — without this, a user who reaches this tab before the import job runs
  // would be stuck on a stale pre-import snapshot until a manual page reload (see the
  // fulfillment-activation-card race). Deliberately silent: on a transient poll
  // failure it just retries next tick, never touches the page-level `error` state or
  // unmounts the cards (status stays truthy throughout — see the comment on the
  // fieldset gate below for why that matters).
  useEffect(() => {
    if (!status?.shopify.connected || status.shopify.importStatus === 'completed') return
    const id = setInterval(async () => {
      try {
        setStatus(await getConnections())
      } catch {
        // transient — retry next tick
      }
    }, 3000)
    return () => clearInterval(id)
  }, [status?.shopify.connected, status?.shopify.importStatus])

  // Spinner gates on `!status` (first load only), and the fieldset gates on
  // `status` alone (not `!loading`) — deliberately, not the obvious `loading &&
  // status && ...` split. A REload (any card's onConnected/reload prop, e.g.
  // BostaCard after a successful connect) sets loading=true again while status
  // still holds the previous good value; if the fieldset also required
  // `!loading`, that reload would unmount ShopifyConnectionCard/BostaCard for
  // its duration and remount them fresh once it resolves — silently discarding
  // any local child state set in the same tick as the reload call (e.g.
  // BostaCard's post-connect `setRevealSecret`, which react 18 batches into the
  // SAME commit as the reload's `setLoading(true)`, so the update lands on the
  // fiber that's being torn down and is gone by the time the remount happens).
  // Confirmed via RTL repro: the webhook-secret reveal never rendered under the
  // old `!loading && status` gate, in either await- or fire-and-forget-style
  // reload calls. Once `status` exists, the grid stays mounted through every
  // subsequent reload; only the very first load (status still null) blocks on
  // the spinner.
  return (
    <div className="space-y-6">
      {loading && !status && (
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

      {status && (
        <fieldset disabled={readOnly} className="contents border-0 p-0 m-0">
          <div className="grid gap-4 sm:grid-cols-2">
            <ShopifyConnectionCard
              shopify={status.shopify}
              customAppAvailable={status.customAppAvailable}
              oauthAvailable={status.oauthAvailable}
              shopifySetup={status.shopifySetup}
              reload={load}
            />
            <BostaCard bosta={status.bosta} onConnected={load} />
          </div>
        </fieldset>
      )}
    </div>
  )
}
