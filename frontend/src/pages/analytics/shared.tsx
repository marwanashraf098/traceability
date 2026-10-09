import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getConnections } from '../../api'
import {
  getInventorySyncStatus, getSalesVariants, type CostCoverage, type InventorySyncStatus, type PeriodParams,
} from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { relChange, useFmt, type Fmt } from '../../analytics/format'
import { Banner, type DeltaInfo } from '../../components/analytics/ui'

// Pieces every analytics page shares: the merchant's situation (Bosta or not, other carriers,
// costs), the deltas, and the translations of backend group keys.

/** Orders shipped outside Bosta at or above this share get the "other carrier" banner. */
export const OTHER_CARRIER_BANNER_SHARE = 0.05

export function useMerchant() {
  const connections = useAnalyticsQuery('connections', () => getConnections())
  return {
    /** Known only once /connections answered. */
    ready: connections.data != null,
    noBosta: connections.data != null && !connections.data.bosta.connected,
  }
}

export function relDelta(fmt: Fmt, cur: number | null | undefined, prev: number | null | undefined, goodWhenUp = true): DeltaInfo | null {
  const r = relChange(cur, prev)
  if (r == null) return null
  return { text: fmt.pct(Math.abs(r)), dir: Math.abs(r) < 0.0005 ? 'flat' : r > 0 ? 'up' : 'down', goodWhenUp }
}

export function ptsDelta(fmt: Fmt, cur: number | null | undefined, prev: number | null | undefined, goodWhenUp = true): DeltaInfo | null {
  if (cur == null || prev == null) return null
  const d = cur - prev
  return { text: fmt.pts(d), dir: Math.abs(d) < 0.0005 ? 'flat' : d > 0 ? 'up' : 'down', goodWhenUp }
}

/** "41% of orders in this period (128) shipped outside Bosta" — from the sales totals. */
export function OtherCarrierBanner({ params }: { params: PeriodParams }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const sales = useAnalyticsQuery(`sales-variants:${JSON.stringify(params)}`, sig => getSalesVariants(params, sig))
  const tot = sales.data?.totals
  const share = tot && tot.orders > 0 ? tot.wijhaOrders / tot.orders : 0
  if (!tot || share < OTHER_CARRIER_BANNER_SHARE) return null
  return (
    <Banner testId="other-carrier-banner">
      {t('analytics.otherCarrier.banner', { pct: fmt.pct(share, 0), count: tot.wijhaOrders })}
    </Banner>
  )
}

export function ConnectBostaCard({ testId = 'connect-bosta' }: { testId?: string }) {
  const { t } = useTranslation()
  return (
    <div data-testid={testId} className="col-span-full card p-[18px] flex flex-col gap-1.5">
      <span className="text-[11.5px] uppercase tracking-[0.07em] font-semibold text-muted">{t('analytics.noBosta.title')}</span>
      <span className="text-[13px] text-neutral-text">{t('analytics.noBosta.body')}</span>
      <Link to="/settings?tab=connections" className="text-[12.5px] font-semibold text-trace-blue hover:underline self-start">{t('analytics.noBosta.cta')}</Link>
    </div>
  )
}

export function useInventorySync() {
  return useAnalyticsQuery('inventory-sync-status', sig => getInventorySyncStatus(sig))
}

/**
 * The costed / total line every cost figure carries. Nothing costed → why: Shopify refused the
 * cost field (access denied), the read failed, or the merchant hasn't filled cost per item.
 */
export function CostNote({ coverage, sync, testId = 'cost-note' }: {
  coverage: CostCoverage | null | undefined
  sync: InventorySyncStatus | null | undefined
  testId?: string
}) {
  const { t } = useTranslation()
  const fmt = useFmt()
  if (!coverage) return null
  if (coverage.variantsCosted === 0) {
    const reason = sync?.costStatus === 'access_denied' ? 'accessDenied' : sync?.costStatus === 'error' ? 'readError' : 'missing'
    return <p data-testid={testId} className="text-[12px] text-muted">{t(`analytics.cost.${reason}`)}</p>
  }
  return (
    <p data-testid={testId} className="text-[12px] text-muted">
      {t('analytics.cost.coverage', {
        costed: fmt.num(coverage.variantsCosted), total: fmt.num(coverage.variantsSold), share: fmt.pct(coverage.costedRevenueShare, 0),
      })}
    </p>
  )
}

/** A backend group key (English label) in the page language; unknown keys fall back to the label. */
export function useGroupLabel() {
  const { t, i18n } = useTranslation()
  return (kind: 'channel' | 'payment' | 'reason' | 'productType' | 'governorate', key: string, label: string, labelAr?: string | null) => {
    if (i18n.language === 'ar' && labelAr) return labelAr
    if (kind === 'governorate') return label
    if (kind === 'productType') return key === 'uncategorised' ? t('analytics.labels.productType.uncategorised') : label
    return t(`analytics.labels.${kind}.${key}`, { defaultValue: label })
  }
}
