import { useTranslation } from 'react-i18next'
import { AlertTriangle, ArrowUpRight } from 'lucide-react'
import { tracedLocationAdminUrls } from '../../../api'

/**
 * L1b-1 (2026-11-15) — the owner's fallback when the store uses market-driven shipping: Traced can't add its location
 * to the merchant's shipping rates (and never edits them), so it says so and links to the location's page in Shopify,
 * whose Shipping row opens "Select rates for Traced Main Warehouse" (verified live). Shipping settings is secondary.
 * Shown by the onboarding gate and the connection card — never a "Ready".
 */
export default function ShippingRatesInstruction({ shopDomain, locationNumber }: {
  shopDomain: string | null | undefined
  locationNumber: string | null | undefined
}) {
  const { t } = useTranslation()
  const urls = tracedLocationAdminUrls(shopDomain, locationNumber)
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
    </div>
  )
}
