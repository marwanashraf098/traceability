import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, ArrowUpRight, Check, RefreshCw } from 'lucide-react'
import {
  tracedLocationAdminUrls, confirmTracedShippingRates, checkTracedShipping, getRoleFromToken, TransferCommandError,
} from '../../../api'

/**
 * The owner's instruction when Traced Main Warehouse isn't in the store's shipping yet. Traced never edits shipping
 * itself — no delivery-profile write, no rate edit (L1b-2, 2026-11-16) — it says what to do, links to the exact page in
 * Shopify, and reads the result (hourly, or "Check again").
 *
 * Wording by the store's shipping model:
 *  - legacy → "shipping profile": a deep link to the General profile (fallback: shipping settings).
 *  - market-driven / unknown → "shipping rates": the location's page in Shopify, whose Shipping row opens "Select rates
 *    for Traced Main Warehouse" (verified live); shipping settings is secondary. The owner's "I've added it"
 *    (L1b-1 review) stays for stores Traced can't read — never once Traced has READ that the rates miss it.
 * Shown by the onboarding gate and the connection card — never a "Ready".
 */
export default function ShippingRatesInstruction({
  shopDomain, locationNumber, profileNumber, model, removed, marketStatus, notAddedFor, marketsRequestUrl,
  onConfirmed, onChecked,
}: {
  shopDomain: string | null | undefined
  locationNumber: string | null | undefined
  /** Legacy: the General shipping profile's numeric id. */
  profileNumber?: string | null
  /** 'legacy' → "shipping profile" wording; anything else → "shipping rates". */
  model?: string | null
  /** Legacy: it was in the profile and was taken out. */
  removed?: boolean
  /** Market-driven: what Traced read ('not_covered' → it checked and the rates miss it). */
  marketStatus?: string | null
  notAddedFor?: string[] | null
  /** Owner, market-driven, read_markets not granted: Shopify's optional-scope request URL. */
  marketsRequestUrl?: string | null
  /** Called after the owner's "I've added it" was recorded (the parent reloads). */
  onConfirmed?: () => void | Promise<void>
  /** Called after a "Check again" read (the parent reloads). */
  onChecked?: () => void | Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const urls = tracedLocationAdminUrls(shopDomain, locationNumber, profileNumber)
  const role = getRoleFromToken()
  const isOwner = role === 'owner'
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  if (model === 'legacy') {
    return (
      <div className="space-y-2" data-testid="traced-shipping-add-profile">
        <p className="text-small font-medium text-primary flex items-center gap-1.5">
          <AlertTriangle className="w-4 h-4 text-warning flex-shrink-0" aria-hidden="true" />
          {removed ? t('tracedShipping.addProfile.removedTitle') : t('tracedShipping.addProfile.title')}
        </p>
        <p className="text-xs text-muted">{t('tracedShipping.addProfile.body')}</p>
        <p className="text-xs text-muted">{t('tracedShipping.addProfile.cantShip')}</p>
        {urls && (
          <a href={urls.profile} target="_blank" rel="noopener noreferrer"
            className="btn btn-brand text-small inline-flex items-center gap-1" data-testid="traced-shipping-open-profile">
            {t('tracedShipping.addProfile.openProfile')}
            <ArrowUpRight size={14} className="rtl:-scale-x-100" aria-hidden="true" />
          </a>
        )}
        <ShippingCheckButton onChecked={onChecked} />
      </div>
    )
  }

  const notCovered = marketStatus === 'not_covered'

  async function confirm() {
    setError('')
    setSaving(true)
    try {
      await confirmTracedShippingRates()
      await onConfirmed?.()
    } catch (err) {
      setError(err instanceof TransferCommandError
        ? (i18n.language === 'ar' ? err.messageAr : err.messageEn)
        : t('tracedShipping.addRates.confirmError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-2" data-testid="traced-shipping-add-rates">
      <p className="text-small font-medium text-primary flex items-center gap-1.5">
        <AlertTriangle className="w-4 h-4 text-warning flex-shrink-0" aria-hidden="true" />
        {t('tracedShipping.addRates.title')}
      </p>
      <p className="text-xs text-muted">{t('tracedShipping.addRates.body')}</p>
      <p className="text-xs text-muted" data-testid={notCovered ? 'traced-shipping-not-covered' : undefined}>
        {notCovered ? t('tracedShipping.addRates.notCovered') : t('tracedShipping.addRates.cantConfirm')}
      </p>
      <NotAddedFor markets={notAddedFor} />
      {urls && (
        <div className="flex flex-wrap items-center gap-3">
          <a href={urls.location} target="_blank" rel="noopener noreferrer"
            className="btn btn-brand text-small inline-flex items-center gap-1" data-testid="traced-shipping-open-location">
            {t('tracedShipping.addRates.openLocation')}
            <ArrowUpRight size={14} className="rtl:-scale-x-100" aria-hidden="true" />
          </a>
          <a href={urls.shipping} target="_blank" rel="noopener noreferrer"
            className="text-small text-trace-blue hover:text-trace-blue-hover" data-testid="traced-shipping-open-settings">
            {t('tracedShipping.addRates.openShipping')}
          </a>
        </div>
      )}
      {isOwner && marketsRequestUrl && (
        <div className="space-y-1" data-testid="traced-shipping-allow-markets">
          <p className="text-xs text-muted">{t('tracedShipping.addRates.allowMarketsBody')}</p>
          <a href={marketsRequestUrl} target="_blank" rel="noopener noreferrer"
            className="text-small text-trace-blue hover:text-trace-blue-hover inline-flex items-center gap-1"
            data-testid="traced-shipping-allow-markets-link">
            {t('tracedShipping.addRates.allowMarkets')}
            <ArrowUpRight size={14} className="rtl:-scale-x-100" aria-hidden="true" />
          </a>
        </div>
      )}
      {/* "I've added it" is the owner's word for a store Traced can't read; a verified 'not_covered' beats it. */}
      {!notCovered && (isOwner ? (
        <div className="space-y-1">
          <button type="button" onClick={confirm} disabled={saving}
            className="btn btn-outline text-small inline-flex items-center gap-1" data-testid="traced-shipping-confirm-btn">
            <Check size={14} aria-hidden="true" />
            {t('tracedShipping.addRates.confirm')}
          </button>
          {error && <p role="alert" className="text-xs text-danger">{error}</p>}
        </div>
      ) : (
        <p className="text-xs text-muted" data-testid="traced-shipping-confirm-owner-only">
          {t('tracedShipping.addRates.ownerOnly')}
        </p>
      ))}
      <ShippingCheckButton onChecked={onChecked} />
    </div>
  )
}

/** L1b-1 review — after the owner's "I've added it": no instruction, a quiet line. Not Ready. */
export function ShippingRatesConfirmed({ onChecked }: { onChecked?: () => void | Promise<void> } = {}) {
  const { t } = useTranslation()
  return (
    <div className="space-y-1" data-testid="traced-shipping-owner-confirmed">
      <p className="text-small font-medium text-primary flex items-center gap-1.5">
        <Check className="w-4 h-4 text-success flex-shrink-0" aria-hidden="true" />
        {t('tracedShipping.confirmed.title')}
      </p>
      <p className="text-xs text-muted">{t('tracedShipping.confirmed.body')}</p>
      <ShippingCheckButton onChecked={onChecked} />
    </div>
  )
}

/** L1b-2 — what covers it, and the other markets that miss it (information only, never a blocker). */
export function ShippingCoverage({ coveredBy, notAddedFor }: { coveredBy?: string | null; notAddedFor?: string[] | null }) {
  const { t } = useTranslation()
  if (!coveredBy && !(notAddedFor && notAddedFor.length)) return null
  return (
    <div className="space-y-1" data-testid="traced-shipping-coverage">
      {coveredBy && (
        <p className="text-xs text-muted" data-testid="traced-shipping-covered-by">
          {t('tracedShipping.coveredBy', { rate: coveredBy })}
        </p>
      )}
      <NotAddedFor markets={notAddedFor} />
    </div>
  )
}

function NotAddedFor({ markets }: { markets?: string[] | null }) {
  const { t, i18n } = useTranslation()
  if (!markets || markets.length === 0) return null
  const list = markets.join(i18n.language === 'ar' ? '، ' : ', ')
  return (
    <p className="text-xs text-muted" data-testid="traced-shipping-not-added-for" title={t('tracedShipping.notAddedForHint')}>
      <bdi>{t('tracedShipping.notAddedFor', { markets: list })}</bdi>
    </p>
  )
}

/**
 * L1b-2 — "Check again" (owner / manager): Traced READS its location's shipping now (membership on legacy, the
 * store's markets on market-driven). One per 2 minutes per store — inside that window it says when. Nothing in Shopify
 * changes.
 */
export function ShippingCheckButton({ onChecked }: { onChecked?: () => void | Promise<void> }) {
  const { t, i18n } = useTranslation()
  const role = getRoleFromToken()
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  if (role !== 'owner' && role !== 'manager') return null

  async function check() {
    setNote('')
    setBusy(true)
    try {
      const r = await checkTracedShipping()
      if (r.rateLimited) {
        const at = r.nextAllowedAt ? new Date(r.nextAllowedAt) : null
        setNote(at && !Number.isNaN(at.getTime())
          ? t('tracedShipping.check.rateLimited', {
              time: at.toLocaleTimeString(i18n.language === 'ar' ? 'ar' : 'en', { hour: '2-digit', minute: '2-digit' }),
            })
          : t('tracedShipping.check.rateLimitedSoon'))
      } else {
        await onChecked?.()
      }
    } catch {
      setNote(t('tracedShipping.check.error'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-1">
      <button type="button" onClick={check} disabled={busy}
        className="btn btn-outline text-small inline-flex items-center gap-1" data-testid="traced-shipping-check-btn">
        <RefreshCw size={14} aria-hidden="true" className={busy ? 'animate-spin motion-reduce:animate-none' : undefined} />
        {busy ? t('tracedShipping.check.checking') : t('tracedShipping.check.button')}
      </button>
      {note && <p role="status" className="text-xs text-muted" data-testid="traced-shipping-check-note">{note}</p>}
    </div>
  )
}
