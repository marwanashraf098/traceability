import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, ArrowUpRight, Check } from 'lucide-react'
import { tracedLocationAdminUrls, confirmTracedShippingRates, getRoleFromToken, TransferCommandError } from '../../../api'

/**
 * L1b-1 (2026-11-15) — the owner's fallback when the store uses market-driven shipping: Traced can't add its location
 * to the merchant's shipping rates (and never edits them), so it says so and links to the location's page in Shopify,
 * whose Shipping row opens "Select rates for Traced Main Warehouse" (verified live). Shipping settings is secondary.
 * Shown by the onboarding gate and the connection card — never a "Ready". Owner only: "I've added it" records it and
 * hides this (readiness 'owner_confirmed', "Confirmed by you") — still not Ready (L1b-1 review).
 */
export default function ShippingRatesInstruction({ shopDomain, locationNumber, onConfirmed }: {
  shopDomain: string | null | undefined
  locationNumber: string | null | undefined
  /** Called after the owner's "I've added it" was recorded (the parent reloads). */
  onConfirmed?: () => void | Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const urls = tracedLocationAdminUrls(shopDomain, locationNumber)
  const isOwner = getRoleFromToken() === 'owner'
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

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
      <p className="text-xs text-muted">{t('tracedShipping.addRates.cantConfirm')}</p>
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
      {isOwner ? (
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
      )}
    </div>
  )
}

/** L1b-1 review — after the owner's "I've added it": no instruction, a quiet line. Not Ready. */
export function ShippingRatesConfirmed() {
  const { t } = useTranslation()
  return (
    <div className="space-y-1" data-testid="traced-shipping-owner-confirmed">
      <p className="text-small font-medium text-primary flex items-center gap-1.5">
        <Check className="w-4 h-4 text-success flex-shrink-0" aria-hidden="true" />
        {t('tracedShipping.confirmed.title')}
      </p>
      <p className="text-xs text-muted">{t('tracedShipping.confirmed.body')}</p>
    </div>
  )
}
