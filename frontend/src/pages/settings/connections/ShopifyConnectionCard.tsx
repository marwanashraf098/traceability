import { useState, useEffect, useCallback, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { Check, AlertTriangle, ArrowUpRight, X } from 'lucide-react'
import { Badge, Button, Input, Spinner } from '../../../components/ui'
import SetupWizard from './SetupWizard'
import {
  shopifyInitiate, shopifyCustomConnect, shopifyDisconnect, TransferCommandError,
  listLocations, getShopifyInventoryReconcileReport, activateShopifyFulfillment,
  needsFulfillmentActivation,
  ConnectionsStatus, LocationRow,
} from '../../../api'

const SHOP_RE = /^[a-zA-Z0-9][a-zA-Z0-9-]*\.myshopify\.com$/

type UiState =
  | 'disconnected' | 'choose' | 'guide' | 'connecting'
  | 'connected-ea' | 'connected-official' | 'attention'

// custom_app (legacy dev-only /connect) has no official/early-access distinction of its
// own in the product model — it's treated as "early access" for badge purposes, same as
// custom_app_cc, since neither is the public OAuth app.
function deriveUiState(shopify: ConnectionsStatus['shopify']): UiState {
  if (shopify.connected) {
    return shopify.connectionType === 'oauth' ? 'connected-official' : 'connected-ea'
  }
  if (shopify.status === 'needs_reauth' || shopify.status === 'error') return 'attention'
  return 'disconnected'
}

// Codes the OAuth install/callback redirect puts in ?shopify_error= (ShopifyOAuthController
// .BrowserError). The URL value is only ever used to pick one of these keys — it's never
// rendered itself; anything else (unknown, empty, tampered) falls back to the generic message.
const CALLBACK_ERROR_CODES = ['SHOP_LINKED_ELSEWHERE', 'SHOP_MISMATCH', 'INSTALL_EXPIRED', 'INSTALL_FAILED']

function callbackErrorKey(code: string): string {
  return CALLBACK_ERROR_CODES.includes(code)
    ? `connections.shopify.callbackError.${code}`
    : 'connections.shopify.callbackError.generic'
}

function ShopifyLogo() {
  return (
    <div className="w-10 h-10 rounded-xl bg-[#5a31f4]/10 border border-[#5a31f4]/20 flex items-center justify-center flex-shrink-0">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#5a31f4" strokeWidth="1.75">
        <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" />
        <line x1="3" y1="6" x2="21" y2="6" />
        <path d="M16 10a4 4 0 01-8 0" />
      </svg>
    </div>
  )
}

function connectErrorMessage(err: unknown, isAr: boolean, fallback: string): string {
  if (err instanceof TransferCommandError) return isAr ? err.messageAr : err.messageEn
  return fallback
}

// ── Fulfillment activation checklist item (FR-17 v2) ───────────────────────────
//
// Shown only once Part A has already linked the Traced Main Warehouse to Shopify
// (automatic, at connect) — separate and deliberately NOT automatic itself. Disabled
// until the reconcile report shows nothing left to seed, and behind an explicit
// confirmation checkbox — activating makes the location count toward storefront
// availability, so an un-zeroed old location oversells.

function FulfillmentActivationItem({
  shopifyConnected, importStatus, lastSyncAt,
}: {
  shopifyConnected: boolean
  importStatus: string | null
  lastSyncAt: string | null
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [loading,    setLoading]    = useState(true)
  const [loadError,  setLoadError]  = useState(false)
  const [location,   setLocation]   = useState<LocationRow | null>(null)
  const [seedingDone, setSeedingDone] = useState(false)
  const [confirmed,  setConfirmed]  = useState(false)
  const [activating, setActivating] = useState(false)
  const [errorMsg,   setErrorMsg]   = useState('')

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      const locations   = await listLocations()
      const fulfillment = locations.find(l => l.is_fulfillment) ?? null
      setLocation(fulfillment)

      if (fulfillment
          && fulfillment.shopify_sync_status === 'linked'
          && fulfillment.shopify_delivery_profile_status !== 'activated') {
        try {
          const report = await getShopifyInventoryReconcileReport()
          setSeedingDone(report.rows.every(r => r.action !== 'seed'))
        } catch {
          setSeedingDone(false)
        }
      }
    } catch {
      setLocation(null)
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  // Re-runs whenever the parent's import-status snapshot changes (ConnectionsTab polls
  // getConnections() while an import is in flight) — not mount-once. This is what makes
  // the card appear once Part A's async location-provisioning job actually finishes,
  // instead of forever reflecting whatever `listLocations()` returned at first mount.
  useEffect(() => { load() }, [load, importStatus, lastSyncAt])

  async function handleActivate() {
    setErrorMsg('')
    setActivating(true)
    try {
      await activateShopifyFulfillment()
      setConfirmed(false)
      await load()
    } catch (err) {
      if (err instanceof TransferCommandError) {
        setErrorMsg(isAr ? err.messageAr : err.messageEn)
      } else {
        setErrorMsg(t('connections.shopify.fulfillmentActivation.genericError'))
      }
    } finally {
      setActivating(false)
    }
  }

  // A failed listLocations()/reconcile fetch must not look identical to "not ready yet" —
  // distinct state, with its own retry, checked before the "nothing to show" early return.
  if (loadError) {
    return (
      <div className="pt-2 border-t border-line/40 space-y-2" data-testid="fulfillment-activation-error">
        <p role="alert" className="text-xs text-danger">
          {t('connections.shopify.fulfillmentActivation.loadError')}
        </p>
        <button
          type="button"
          onClick={load}
          className="btn btn-outline text-small"
          data-testid="fulfillment-retry-btn"
        >
          {t('connections.shopify.fulfillmentActivation.retry')}
        </button>
      </div>
    )
  }

  // Nothing to show until Part A has linked the location — no automatic trigger exists
  // for that step from here, so a permanently-disabled item would just confuse.
  if (loading || !location || location.shopify_sync_status !== 'linked') return null

  // Shared predicate with Overview's prompt card (api.ts) — never a second derivation.
  const isActivated = !needsFulfillmentActivation(shopifyConnected, location)
  const canActivate  = seedingDone && confirmed && !activating

  return (
    <div className="pt-2 border-t border-line/40 space-y-2" data-testid="fulfillment-activation">
      <p className="text-small font-medium text-primary">
        {t('connections.shopify.fulfillmentActivation.title')}
      </p>

      {isActivated ? (
        <p
          data-testid="fulfillment-activated"
          className="text-small text-success flex items-center gap-1.5"
        >
          <span className="w-2 h-2 rounded-full bg-success flex-shrink-0" />
          {t('connections.shopify.fulfillmentActivation.live')}
        </p>
      ) : (
        <div className="space-y-2">
          {!seedingDone && (
            <p className="text-xs text-muted" data-testid="fulfillment-waiting-seed">
              {t('connections.shopify.fulfillmentActivation.waitingForSeed')}
            </p>
          )}

          <label className="flex items-start gap-2 text-xs text-muted">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={e => setConfirmed(e.target.checked)}
              disabled={!seedingDone || activating}
              className="mt-0.5 flex-shrink-0"
              data-testid="fulfillment-confirm-checkbox"
            />
            <span>{t('connections.shopify.fulfillmentActivation.confirmCopy')}</span>
          </label>

          {errorMsg && (
            <p role="alert" className="text-xs text-danger">{errorMsg}</p>
          )}

          <button
            type="button"
            onClick={handleActivate}
            disabled={!canActivate}
            className="btn btn-brand text-small"
            data-testid="activate-fulfillment-btn"
          >
            {activating
              ? t('connections.shopify.fulfillmentActivation.activating')
              : t('connections.shopify.fulfillmentActivation.activateBtn')}
          </button>
        </div>
      )}
    </div>
  )
}

// ── Shopify connection card — state machine wrapper ────────────────────────────
//
// uiState splits into two kinds: server-derived (disconnected / connected-ea /
// connected-official / attention — recomputed from `shopify` on every prop update)
// and local-only UI states (choose / guide / connecting) that exist only while the
// user is mid-flow and have no server representation. `override` holds the local
// state when set; null means "trust the server-derived value". Every successful
// write (connect, disconnect) calls `reload()` (the parent's getConnections()
// re-fetch) and then clears the override, letting the freshly-derived state show.

export default function ShopifyConnectionCard({
  shopify, customAppAvailable, oauthAvailable, shopifySetup, reload,
}: {
  shopify: ConnectionsStatus['shopify']
  customAppAvailable: boolean
  oauthAvailable: boolean
  shopifySetup: ConnectionsStatus['shopifySetup']
  reload: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [override, setOverride] = useState<UiState | null>(null)
  const [wizardKey, setWizardKey] = useState(0)
  const [wizardStartStep, setWizardStartStep] = useState(1)
  const [wizardError, setWizardError] = useState('')

  // Step 10's fields live here, not in SetupWizard — that component fully unmounts
  // while uiState is 'connecting', so anything it owned locally would be lost on a
  // failed submit. Cleared only on success (handleWizardSubmit) or on actually leaving
  // the wizard (closeWizard) — never on a failed-submit retry (openWizard, used by
  // handleWizardSubmit's catch branch, deliberately does not touch these).
  const [wizardShopDomain, setWizardShopDomain] = useState('')
  const [wizardClientId, setWizardClientId] = useState('')
  const [wizardClientSecret, setWizardClientSecret] = useState('')

  const [reviewerShop, setReviewerShop] = useState('')
  const [reviewerLoading, setReviewerLoading] = useState(false)
  const [reviewerError, setReviewerError] = useState('')

  const [upgrading, setUpgrading] = useState(false)
  const [upgradeError, setUpgradeError] = useState('')

  const [disconnecting, setDisconnecting] = useState(false)
  const [disconnectError, setDisconnectError] = useState('')

  const [attentionReconnecting, setAttentionReconnecting] = useState(false)
  const [attentionError, setAttentionError] = useState('')

  // A failed Shopify connect lands back here as ?shopify_error=CODE. Captured once on
  // mount, then stripped from the URL (replace, so Back/refresh don't re-show it).
  const [searchParams, setSearchParams] = useSearchParams()
  const [callbackError, setCallbackError] = useState<string | null>(() => searchParams.get('shopify_error'))
  useEffect(() => {
    if (!searchParams.has('shopify_error')) return
    setSearchParams(prev => {
      const next = new URLSearchParams(prev)
      next.delete('shopify_error')
      return next
    }, { replace: true })
  }, [searchParams, setSearchParams])

  const uiState: UiState = override ?? deriveUiState(shopify)

  // Reopens the wizard preserving whatever the merchant already typed — used only by
  // handleWizardSubmit's catch branch (a failed submit lands back on step 10 with the
  // same values and an error, not a blank form).
  function openWizard(startStep: number, error = '') {
    setWizardStartStep(startStep)
    setWizardError(error)
    setWizardKey(k => k + 1)
    setOverride('guide')
  }

  // Opens the wizard fresh — "Set it up myself" from choose, or "Reconnect" for a
  // custom_app_cc store from attention. Clears any leftover field values from a
  // previous session (there shouldn't be any at this point, but this is the one path
  // that's explicitly "starting over", so it's the right place to guarantee it).
  function openFreshWizard(startStep: number) {
    setWizardShopDomain('')
    setWizardClientId('')
    setWizardClientSecret('')
    openWizard(startStep)
  }

  // Leaving the wizard back to "choose" (Back at step 1) — clears the fields, matching
  // "clear only on success or on leaving the wizard".
  function closeWizard() {
    setWizardShopDomain('')
    setWizardClientId('')
    setWizardClientSecret('')
    setOverride('choose')
  }

  async function handleReviewerConnect(e: FormEvent) {
    e.preventDefault()
    setReviewerError('')
    const trimmed = reviewerShop.trim()
    if (!SHOP_RE.test(trimmed)) {
      setReviewerError(t('connections.shopify.domainInvalid'))
      return
    }
    setReviewerLoading(true)
    try {
      const res = await shopifyInitiate(trimmed)
      window.location.href = res.consentUrl
    } catch (err) {
      setReviewerError(connectErrorMessage(err, isAr, t('connections.shopify.error')))
      setReviewerLoading(false)
    }
  }

  async function handleOAuthConnect(shopDomain: string, onError: (msg: string) => void, endLoading: () => void) {
    try {
      const res = await shopifyInitiate(shopDomain)
      window.location.href = res.consentUrl
    } catch (err) {
      onError(connectErrorMessage(err, isAr, t('connections.shopify.error')))
      endLoading()
    }
  }

  async function handleUpgrade() {
    if (!shopify.shopDomain) return
    setUpgradeError('')
    setUpgrading(true)
    await handleOAuthConnect(shopify.shopDomain, setUpgradeError, () => setUpgrading(false))
  }

  async function handleAttentionReconnect() {
    if (!shopify.shopDomain) return
    if (shopify.connectionType === 'oauth') {
      setAttentionError('')
      setAttentionReconnecting(true)
      await handleOAuthConnect(shopify.shopDomain, setAttentionError, () => setAttentionReconnecting(false))
    } else {
      // custom_app / custom_app_cc: reconnect is re-submitting fresh credentials —
      // reopen the wizard at the connect form, not the whole 10-step walkthrough.
      openFreshWizard(10)
    }
  }

  async function handleDisconnect() {
    if (!shopify.storeId) return
    if (!window.confirm(t('connections.shopify.disconnectConfirm', { shop: shopify.shopDomain }))) return
    setDisconnectError('')
    setDisconnecting(true)
    try {
      await shopifyDisconnect(shopify.storeId)
      await reload()
      setOverride(null)
    } catch {
      setDisconnectError(t('connections.shopify.disconnectError'))
    } finally {
      setDisconnecting(false)
    }
  }

  async function handleWizardSubmit(shopDomain: string, clientId: string, clientSecret: string) {
    setOverride('connecting')
    try {
      await shopifyCustomConnect(shopDomain, clientId, clientSecret)
      await reload()
      setOverride(null)
      setWizardShopDomain('')
      setWizardClientId('')
      setWizardClientSecret('')
    } catch (err) {
      // Deliberately does NOT clear wizardShopDomain/clientId/clientSecret — the
      // merchant retries from step 10 with everything they already typed still there.
      openWizard(10, connectErrorMessage(err, isAr, t('connections.shopify.error')))
    }
  }

  const statusPillTone = uiState === 'attention' ? 'warning' : uiState.startsWith('connected') ? 'success' : 'neutral'
  const statusPillLabel =
    uiState === 'attention' ? t('connections.shopify.actionNeeded')
    : uiState.startsWith('connected') ? t('connections.shopify.connected')
    : t('connections.shopify.disconnected')

  return (
    <div className="card p-5 space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <ShopifyLogo />
          <h2 className="text-h3 text-primary">{t('connections.shopify.title')}</h2>
        </div>
        <Badge tone={statusPillTone} label={statusPillLabel} />
      </div>

      {callbackError !== null && (
        <div role="alert" className="flex items-start gap-3 rounded-xl border border-warning/25 bg-warning/10 p-3">
          <AlertTriangle size={18} className="text-warning flex-shrink-0 mt-0.5" />
          <p className="text-small text-primary flex-1">{t(callbackErrorKey(callbackError))}</p>
          <button
            type="button"
            onClick={() => setCallbackError(null)}
            aria-label={t('connections.shopify.callbackError.dismiss')}
            className="text-muted hover:text-primary flex-shrink-0"
          >
            <X size={16} />
          </button>
        </div>
      )}

      {uiState === 'disconnected' && (
        <div className="space-y-3">
          {customAppAvailable && (
            <div className="rounded-xl border border-brand/20 bg-brand/5 p-4 space-y-2">
              <span className="inline-block text-xs font-semibold text-brand bg-brand/10 rounded px-2 py-0.5">
                {t('connections.shopify.merchant.badge')}
              </span>
              <h4 className="text-body-lg font-semibold text-primary">{t('connections.shopify.merchant.title')}</h4>
              <p className="text-small text-muted">{t('connections.shopify.merchant.body')}</p>
              <Button variant="primary" className="w-full" onClick={() => setOverride('choose')}>
                {t('connections.shopify.merchant.cta')}
              </Button>
            </div>
          )}

          <div className="rounded-xl border border-line p-4 space-y-2">
            <span className="inline-block text-xs font-semibold text-muted bg-elevated rounded px-2 py-0.5">
              {t('connections.shopify.reviewer.badge')}
            </span>
            <h4 className="text-body-lg font-semibold text-primary">{t('connections.shopify.reviewer.title')}</h4>
            <p className="text-small text-muted">{t('connections.shopify.reviewer.body')}</p>

            {reviewerError && (
              <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
                {reviewerError}
              </div>
            )}

            <form onSubmit={handleReviewerConnect} className="space-y-2">
              <Input
                type="text"
                value={reviewerShop}
                onChange={e => setReviewerShop(e.target.value)}
                placeholder={t('connections.shopify.shopPlaceholder')}
                dir="ltr"
                disabled={reviewerLoading}
                autoComplete="off"
                aria-label={t('connections.shopify.shopLabel')}
              />
              <Button type="submit" variant="outline" className="w-full" disabled={reviewerLoading || !reviewerShop.trim()}>
                {reviewerLoading ? t('connections.shopify.connecting') : t('connections.shopify.reviewer.cta')}
              </Button>
            </form>
          </div>
        </div>
      )}

      {uiState === 'choose' && (
        <div className="space-y-3">
          <button
            type="button"
            onClick={() => setOverride('disconnected')}
            className="text-small text-muted hover:text-primary font-medium"
          >
            {t('common.back')}
          </button>
          <h3 className="text-h4 text-primary">{t('connections.shopify.choose.title')}</h3>
          <p className="text-small text-muted">{t('connections.shopify.choose.subtitle')}</p>

          <button
            type="button"
            onClick={() => window.open(import.meta.env.VITE_CALENDLY_SETUP_URL, '_blank', 'noopener')}
            className="w-full flex items-start gap-3 rounded-xl border border-line p-4 hover:border-brand/40 hover:bg-brand/5 transition-colors text-start"
          >
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <strong className="text-body font-semibold text-primary">{t('connections.shopify.choose.call.title')}</strong>
                <span className="text-xs font-semibold text-brand bg-brand/10 rounded px-1.5 py-0.5">
                  {t('connections.shopify.choose.call.recommend')}
                </span>
              </div>
              <p className="text-small text-muted mt-0.5">{t('connections.shopify.choose.call.body')}</p>
            </div>
            <ArrowUpRight size={18} className="text-muted flex-shrink-0" />
          </button>

          <button
            type="button"
            onClick={() => openFreshWizard(1)}
            className="w-full flex items-start gap-3 rounded-xl border border-line p-4 hover:border-brand/40 hover:bg-brand/5 transition-colors text-start"
          >
            <div className="flex-1 min-w-0">
              <strong className="text-body font-semibold text-primary">{t('connections.shopify.choose.self.title')}</strong>
              <p className="text-small text-muted mt-0.5">{t('connections.shopify.choose.self.body')}</p>
            </div>
            <ArrowUpRight size={18} className="text-muted flex-shrink-0" />
          </button>
        </div>
      )}

      {uiState === 'guide' && (
        <SetupWizard
          key={wizardKey}
          shopifySetup={shopifySetup}
          initialStep={wizardStartStep}
          initialError={wizardError}
          shopDomain={wizardShopDomain}
          clientId={wizardClientId}
          clientSecret={wizardClientSecret}
          onShopDomainChange={setWizardShopDomain}
          onClientIdChange={setWizardClientId}
          onClientSecretChange={setWizardClientSecret}
          onBack={closeWizard}
          onSubmit={handleWizardSubmit}
        />
      )}

      {uiState === 'connecting' && (
        <div className="py-8 text-center space-y-3">
          <Spinner size={28} />
          <div>
            <h3 className="text-h4 text-primary">{t('connections.shopify.connectingPanel.title')}</h3>
            <p className="text-small text-muted mt-1">{t('connections.shopify.connectingPanel.subtitle')}</p>
          </div>
        </div>
      )}

      {(uiState === 'connected-ea' || uiState === 'connected-official') && (
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <span className="w-9 h-9 rounded-full bg-success text-white flex items-center justify-center flex-shrink-0">
              <Check size={18} strokeWidth={2.5} />
            </span>
            <div className="flex-1 min-w-0">
              <strong className="block text-body font-semibold text-primary">{t('connections.shopify.connectedPanel.title')}</strong>
              <span className="block text-small text-muted" dir="ltr">{shopify.shopDomain}</span>
            </div>
            <Badge
              tone={uiState === 'connected-official' ? 'success' : 'warning'}
              label={uiState === 'connected-official'
                ? t('connections.shopify.connectedPanel.badgeOfficial')
                : t('connections.shopify.connectedPanel.badgeEa')}
            />
          </div>

          <div className="flex gap-6 py-3 border-y border-line text-small">
            <div>
              <div className="text-xs text-muted">{t('connections.shopify.lastSync')}</div>
              <div className="text-primary font-medium mt-0.5">
                {shopify.lastSyncAt ? new Date(shopify.lastSyncAt).toLocaleString() : t('connections.never')}
              </div>
            </div>
            {shopify.importStatus && (
              <div>
                <div className="text-xs text-muted">{t('connections.shopify.importStatus')}</div>
                <div className="text-primary font-medium mt-0.5">{shopify.importStatus}</div>
              </div>
            )}
          </div>

          <FulfillmentActivationItem
            shopifyConnected={shopify.connected}
            importStatus={shopify.importStatus}
            lastSyncAt={shopify.lastSyncAt}
          />

          {uiState === 'connected-ea' && oauthAvailable && (
            <div className="rounded-xl border border-brand/20 bg-brand/5 p-4 space-y-2">
              <h4 className="text-body font-semibold text-primary">{t('connections.shopify.connectedPanel.upgradeBanner.title')}</h4>
              <p className="text-small text-muted">{t('connections.shopify.connectedPanel.upgradeBanner.body')}</p>
              {upgradeError && (
                <p role="alert" className="text-small text-danger">{upgradeError}</p>
              )}
              <Button variant="primary" size="sm" onClick={handleUpgrade} disabled={upgrading}>
                {upgrading ? t('connections.shopify.connecting') : t('connections.shopify.connectedPanel.upgradeBanner.cta')}
              </Button>
            </div>
          )}

          <div className="pt-1 space-y-2">
            {disconnectError && (
              <p role="alert" className="text-xs text-danger">{disconnectError}</p>
            )}
            <button
              type="button"
              onClick={handleDisconnect}
              disabled={disconnecting || !shopify.storeId}
              className="text-small text-muted hover:text-danger font-medium"
              data-testid="shopify-disconnect-btn"
            >
              {disconnecting ? t('connections.shopify.disconnecting') : t('connections.shopify.disconnectBtn')}
            </button>
          </div>
        </div>
      )}

      {uiState === 'attention' && (
        <div className="space-y-4">
          <div className="flex items-start gap-3">
            <span className="w-9 h-9 rounded-xl bg-warning/10 text-warning flex items-center justify-center flex-shrink-0">
              <AlertTriangle size={18} />
            </span>
            <div>
              <h3 className="text-h4 text-primary">{t('connections.shopify.attention.title')}</h3>
              <p className="text-small text-muted mt-0.5">{t('connections.shopify.attention.body')}</p>
            </div>
          </div>
          {attentionError && (
            <p role="alert" className="text-small text-danger">{attentionError}</p>
          )}
          <Button variant="primary" onClick={handleAttentionReconnect} disabled={attentionReconnecting}>
            {attentionReconnecting ? t('connections.shopify.connecting') : t('connections.shopify.attention.cta')}
          </Button>
        </div>
      )}
    </div>
  )
}
