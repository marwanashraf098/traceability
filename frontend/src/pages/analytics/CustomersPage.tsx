import { useTranslation } from 'react-i18next'
import { Users, Repeat, Clock, Banknote } from 'lucide-react'
import {
  getCohorts, getCustomerSummary, getCustomersByGovernorate, getCustomerWatch, getTopCustomers,
} from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { AnalyticsCard, AnalyticsGrid, Banner, CardEmpty, Kpi, KpiRow, Note, QueryBlock, RatePill } from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { HBars } from '../../components/analytics/charts/HBars'
import { ptsDelta, relDelta, useMerchant } from './shared'
import { WatchTable } from './WatchTable'

/** Unknown customers (no Shopify customer record) at or above this share get a banner. */
const UNKNOWN_BANNER_SHARE = 0.2
const COHORT_SHADES = ['#eef4fc', '#cde2fb', '#9ec5f4', '#5598e7', '#256abf']

function cohortShade(r: number) {
  const p = r * 100
  return p >= 25 ? COHORT_SHADES[4] : p >= 20 ? COHORT_SHADES[3] : p >= 14 ? COHORT_SHADES[2] : p > 0 ? COHORT_SHADES[1] : COHORT_SHADES[0]
}

export default function CustomersPage() {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const merchant = useMerchant()
  const ck = JSON.stringify({ ...s.params, c: s.compare })
  const summary = useAnalyticsQuery(`customer-summary:${ck}`, sig => getCustomerSummary({ ...s.params, compare: s.compare }, sig))
  const top = useAnalyticsQuery('customers-top', sig => getTopCustomers(undefined, sig))
  const byGov = useAnalyticsQuery('customers-by-governorate', sig => getCustomersByGovernorate(sig))
  const cohorts = useAnalyticsQuery('customers-cohorts', sig => getCohorts(sig))
  const watch = useAnalyticsQuery(merchant.ready && !merchant.noBosta ? 'customer-watch' : null, sig => getCustomerWatch(sig))

  const cur = summary.data?.current, prev = summary.data?.previous
  const loading = !summary.data && !summary.error
  const by = Object.fromEntries((cur?.byClass ?? []).map(c => [c.key, c]))
  const revenue = (k: string) => (merchant.noBosta ? by[k]?.booked : by[k]?.realized) ?? 0
  const total = ['new', 'existing', 'returning', 'unknown'].reduce((a, k) => a + revenue(k), 0)
  const returningShare = total > 0 ? revenue('returning') / total : null
  const unknownShare = cur && cur.customersWhoOrdered > 0 ? cur.unknownCustomers / cur.customersWhoOrdered : 0
  const label = (g: string | null, ar: string | null) => (i18n.language === 'ar' && ar) || g || '—'

  return (
    <div data-testid="analytics-customers" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.customers.title')} subtitle={t('analytics.pages.customers.subtitle')} />
      <AnalyticsGrid>
        {unknownShare >= UNKNOWN_BANNER_SHARE && (
          <Banner testId="unknown-banner">{t('analytics.revenue.unknownShare', { pct: fmt.pct(unknownShare, 0) })}</Banner>
        )}
        <KpiRow>
          <Kpi testId="kpi-customers" icon={Users} tone="blue" label={t('analytics.customers.kpi.who')} loading={loading}
            value={fmt.num(cur?.customersWhoOrdered)} delta={relDelta(fmt, cur?.customersWhoOrdered, prev?.customersWhoOrdered)}
            foot={cur ? t('analytics.customers.kpi.whoFoot', { new: fmt.num(cur.newCustomers), returning: fmt.num(cur.returningCustomers), existing: fmt.num(cur.existingCustomers), unknown: fmt.num(cur.unknownCustomers) }) : undefined} />
          <Kpi testId="kpi-repeat" icon={Repeat} tone="good" label={t('analytics.customers.kpi.repeat')} loading={loading}
            value={fmt.pct(cur?.repeatPurchaseRate)} delta={ptsDelta(fmt, cur?.repeatPurchaseRate, prev?.repeatPurchaseRate)}
            foot={t('analytics.customers.kpi.repeatFoot')} />
          <Kpi testId="kpi-days-between" icon={Clock} tone="blue" label={t('analytics.customers.kpi.between')} loading={loading}
            value={cur?.medianDaysBetweenOrders == null ? '—' : t('analytics.kpi.days', { count: Math.round(cur.medianDaysBetweenOrders), value: fmt.num(cur.medianDaysBetweenOrders, 0) })}
            foot={t('analytics.customers.kpi.betweenFoot')} />
          <Kpi testId="kpi-returning-share" icon={Banknote} tone="good" label={t('analytics.customers.kpi.returningRevenue')} loading={loading}
            value={fmt.pct(returningShare, 0)}
            foot={by.returning?.successRate != null ? t('analytics.customers.kpi.returningFoot', { pct: fmt.pct(by.returning.successRate, 0) }) : undefined} />
        </KpiRow>

        <AnalyticsCard span="s7" title={t('analytics.customers.top')} desc={t('analytics.customers.topDesc')} testId="card-top-customers">
          <QueryBlock q={top} isEmpty={d => d.customers.length === 0} empty={t('analytics.customers.none')}>
            {d => (
              <div className="overflow-x-auto -mx-[18px] px-[18px]">
                <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="top-customers">
                  <thead>
                    <tr className="border-b border-line">
                      <th className="tbl-header text-start ps-0">{t('analytics.delivery.watchCols.customer')}</th>
                      <th className="tbl-header text-start">{t('analytics.delivery.watchCols.governorate')}</th>
                      <th className="tbl-header text-end">{t('analytics.delivery.watchCols.orders')}</th>
                      <th className="tbl-header text-end">{t('analytics.mode.realized')}</th>
                      <th className="tbl-header text-end pe-0">{t('analytics.customers.delivered')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.customers.map(c => (
                      <tr key={c.customerRef} className="border-b border-line last:border-0">
                        <td className="py-[9px] ps-0 pe-2.5"><div className="font-medium">{c.displayName ?? t('analytics.delivery.anonymous')}</div>
                          <div className="text-[12px] text-muted">{t(`analytics.customers.class.${c.customerType}`, { defaultValue: c.customerType })}</div></td>
                        <td className="py-[9px] px-2.5">{label(c.governorate, c.governorateAr)}</td>
                        <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(c.orders)}</td>
                        <td className="py-[9px] px-2.5 text-end tabular-nums">{merchant.noBosta ? '—' : fmt.money(c.realized)}</td>
                        <td className="py-[9px] ps-2.5 pe-0 text-end">{c.successRate == null ? '—' : <RatePill rate={c.successRate} text={fmt.pct(c.successRate, 0)} />}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </QueryBlock>
          {top.data && <Note>{t('analytics.customers.topNote', { total: fmt.num(top.data.totalCustomers) })}</Note>}
        </AnalyticsCard>

        <AnalyticsCard span="s5" title={t('analytics.customers.byGovernorate')} testId="card-repeat-gov"
          desc={byGov.data ? t('analytics.customers.byGovernorateDesc', { min: byGov.data.minCustomers }) : undefined}>
          <QueryBlock q={byGov} isEmpty={d => d.governorates.length === 0} empty={t('analytics.customers.govNone')}>
            {d => <HBars max={Math.max(0.01, ...d.governorates.map(g => g.repeatRate ?? 0))} rows={d.governorates.map(g => ({
              key: g.key, label: label(g.label, g.labelAr), value: g.repeatRate ?? 0, display: fmt.pct(g.repeatRate),
              title: t('analytics.customers.govTip', { repeat: fmt.num(g.repeatCustomers), customers: fmt.num(g.customers) }),
            }))} />}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s7" title={t('analytics.customers.cohorts')} desc={t('analytics.customers.cohortsDesc')} testId="card-cohorts">
          <QueryBlock q={cohorts} isEmpty={d => d.cohorts.length === 0} empty={t('analytics.customers.none')}>
            {d => (
              <div className="overflow-x-auto -mx-[18px] px-[18px]">
                <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="cohorts">
                  <thead>
                    <tr className="border-b border-line">
                      <th className="tbl-header text-start ps-0">{t('analytics.customers.firstOrder')}</th>
                      <th className="tbl-header text-end">{t('analytics.customers.customers')}</th>
                      {[1, 2, 3].map(m => <th key={m} className="tbl-header text-end">{t('analytics.customers.month', { n: m })}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {d.cohorts.map(c => (
                      <tr key={c.month} className="border-b border-line last:border-0">
                        <td className="py-[9px] ps-0 pe-2.5">{new Intl.DateTimeFormat(i18n.language === 'ar' ? 'ar-EG-u-nu-latn' : 'en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${c.month}-01T00:00:00Z`))}</td>
                        <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(c.customers)}</td>
                        {c.orderedAgainPct.map((v, i) => (
                          <td key={i} className="py-[9px] px-2.5 text-end tabular-nums"
                            style={v != null ? { background: cohortShade(v), color: v * 100 >= 20 ? '#fff' : undefined, fontWeight: v * 100 >= 20 ? 600 : undefined } : undefined}
                            title={v != null && !c.monthComplete[i] ? t('analytics.customers.inProgress') : undefined}>
                            {v == null ? <span className="text-muted">–</span> : `${fmt.pct(v, 0)}${c.monthComplete[i] ? '' : '*'}`}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </QueryBlock>
          <Note>{t('analytics.customers.cohortsNote')}</Note>
        </AnalyticsCard>

        <AnalyticsCard span="s5" v2 title={t('analytics.customers.detail')} testId="card-customer-detail">
          <CardEmpty>{t('analytics.customers.detailLater')}</CardEmpty>
        </AnalyticsCard>

        <AnalyticsCard span="s12" title={t('analytics.delivery.watch')} desc={t('analytics.delivery.watchDesc')} testId="card-watch">
          {merchant.noBosta ? <CardEmpty>{t('analytics.customers.watchNeedsBosta')}</CardEmpty> : (
            <QueryBlock q={watch} isEmpty={d => d.customers.length === 0} empty={t('analytics.delivery.watchEmpty')}>
              {d => <WatchTable customers={d.customers} />}
            </QueryBlock>
          )}
        </AnalyticsCard>
      </AnalyticsGrid>
    </div>
  )
}
