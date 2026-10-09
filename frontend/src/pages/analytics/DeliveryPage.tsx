import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Truck, AlertTriangle, Clock } from 'lucide-react'
import {
  getCustomerWatch, getDeliverySummary, getFailureReasons, getProductExtras, getRevenueBreakdown, type BreakdownGroup,
} from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { DataTable, type DataTableSort } from '../../components/ui'
import {
  AnalyticsCard, AnalyticsGrid, CardEmpty, Kpi, KpiRow, Note, QueryBlock, RatePill,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { LineChart } from '../../components/analytics/charts/LineChart'
import { HBars } from '../../components/analytics/charts/HBars'
import { CHART } from '../../components/analytics/charts/chartUtils'
import { ConnectBostaCard, OtherCarrierBanner, ptsDelta, relDelta, useGroupLabel, useMerchant } from './shared'
import { WatchTable } from './WatchTable'

/** Products need this many orders before their failure rate is shown (the mockup's "20+ orders"). */
const MIN_ORDERS_FOR_FAILURE_RATE = 20

function rateColor(r: number | null) {
  if (r == null) return CHART.grey
  return r * 100 < 68 ? CHART.red : r * 100 < 78 ? CHART.amber : CHART.realized
}

type GovRow = BreakdownGroup & { id: string }

function GovernorateTable({ groups, mode }: { groups: BreakdownGroup[]; mode: 'booked' | 'realized' }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const label = useGroupLabel()
  const [sort, setSort] = useState<DataTableSort>({ key: 'orders', dir: 'desc' })
  const rows = useMemo(() => {
    const val = (g: BreakdownGroup) => {
      switch (sort.key) {
        case 'failed': return g.failedOrders
        case 'success': return g.successRate ?? -1
        case 'revenue': return mode === 'realized' ? g.realized : g.booked
        case 'name': return 0
        default: return g.orders
      }
    }
    const out: GovRow[] = groups.map(g => ({ ...g, id: g.key }))
    out.sort((a, b) => sort.key === 'name'
      ? label('governorate', a.key, a.label, a.labelAr).localeCompare(label('governorate', b.key, b.label, b.labelAr)) * (sort.dir === 'asc' ? 1 : -1)
      : (val(a) - val(b)) * (sort.dir === 'asc' ? 1 : -1))
    return out
  }, [groups, sort, mode, label])
  return (
    <div className="-mx-[18px] px-[18px]" data-testid="governorate-table">
      <DataTable<GovRow>
        rows={rows}
        sort={sort}
        onSort={key => setSort(s => ({ key, dir: s.key === key && s.dir === 'desc' ? 'asc' : 'desc' }))}
        columns={[
          { key: 'name', header: t('analytics.delivery.gov.name'), sortable: true, render: r => label('governorate', r.key, r.label, r.labelAr) },
          { key: 'orders', header: t('analytics.delivery.gov.orders'), sortable: true, align: 'end', render: r => fmt.num(r.orders) },
          { key: 'failed', header: t('analytics.delivery.gov.failed'), sortable: true, align: 'end', render: r => fmt.num(r.failedOrders) },
          {
            key: 'success', header: t('analytics.delivery.gov.success'), sortable: true, align: 'end', render: r => (
              <span className="inline-flex items-center gap-2 justify-end">
                <span className="inline-block w-[90px] h-2 rounded bg-elevated overflow-hidden align-middle" aria-hidden="true">
                  <i className="block h-full rounded" style={{ width: `${(r.successRate ?? 0) * 100}%`, background: rateColor(r.successRate) }} />
                </span>
                <RatePill rate={r.successRate} text={fmt.pct(r.successRate)} />
              </span>
            ),
          },
          { key: 'revenue', header: t(`analytics.mode.${mode}`), sortable: true, align: 'end', render: r => fmt.moneyK(mode === 'realized' ? r.realized : r.booked) },
        ]}
      />
    </div>
  )
}

const days = (hours: number | null | undefined) => (hours == null ? null : hours / 24)

export default function DeliveryPage() {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const merchant = useMerchant()
  const rtl = i18n.language === 'ar'
  const label = useGroupLabel()
  const ck = JSON.stringify({ ...s.params, c: s.compare })
  const cq = { ...s.params, compare: s.compare }
  const on = merchant.ready && !merchant.noBosta

  const summary = useAnalyticsQuery(on ? `delivery-summary:${ck}` : null, sig => getDeliverySummary(cq, sig))
  const reasons = useAnalyticsQuery(on ? `failure-reasons:${ck}` : null, sig => getFailureReasons(cq, sig))
  const byGov = useAnalyticsQuery(on ? `breakdown:governorate:${ck}` : null, sig => getRevenueBreakdown({ ...cq, by: 'governorate' }, sig))
  const extras = useAnalyticsQuery(on ? `product-extras:${ck}` : null, sig => getProductExtras(cq, sig))
  const watch = useAnalyticsQuery(on ? 'customer-watch' : null, sig => getCustomerWatch(sig))

  const cur = summary.data?.current, prev = summary.data?.previous
  const loading = !summary.data && !summary.error
  const dDays = (h: number | null | undefined) => (h == null ? '—' : t('analytics.kpi.days', { count: days(h)!, value: fmt.num(days(h), 1) }))
  const speedDelta = (c: number | null | undefined, p: number | null | undefined) => {
    const d = relDelta(fmt, c, p, false)
    return d && { ...d, text: t('analytics.kpi.days', { count: Math.abs((c! - p!) / 24), value: fmt.num(Math.abs((c! - p!) / 24), 1) }) }
  }

  return (
    <div data-testid="analytics-delivery" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.delivery.title')} subtitle={t('analytics.pages.delivery.subtitle')} showMode={on} />
      <AnalyticsGrid>
        {merchant.noBosta ? <ConnectBostaCard /> : (
          <>
            <OtherCarrierBanner params={s.params} />
            <KpiRow>
              <Kpi testId="kpi-success" icon={Truck} tone="good" label={t('analytics.kpi.successRate')} loading={loading}
                value={fmt.pct(cur?.successRate)} delta={ptsDelta(fmt, cur?.successRate, prev?.successRate)}
                foot={cur ? t('analytics.delivery.kpi.successFoot', { delivered: fmt.num(cur.delivered), attempted: fmt.num(cur.delivered + cur.failed) }) : undefined} />
              <Kpi testId="kpi-failed" icon={AlertTriangle} tone="bad" label={t('analytics.delivery.kpi.failed')} loading={loading}
                value={fmt.num(cur?.failed)} delta={relDelta(fmt, cur?.failed, prev?.failed, false)}
                foot={cur ? t('analytics.delivery.kpi.failedFoot', { value: fmt.money(cur.lostSalesValue) }) : undefined} />
              <Kpi testId="kpi-to-handed" icon={Clock} tone="blue" label={t('analytics.delivery.kpi.toHanded')} loading={loading}
                value={dDays(cur?.avgHoursOrderToHanded)} delta={speedDelta(cur?.avgHoursOrderToHanded, prev?.avgHoursOrderToHanded)}
                foot={cur ? t('analytics.delivery.kpi.toHandedFoot', { count: cur.handedOrders, orders: fmt.num(cur.handedOrders) }) : undefined} />
              <Kpi testId="kpi-to-delivered" icon={Truck} tone="blue" label={t('analytics.delivery.kpi.toDelivered')} loading={loading}
                value={dDays(cur?.avgHoursHandedToDelivered)} delta={speedDelta(cur?.avgHoursHandedToDelivered, prev?.avgHoursHandedToDelivered)}
                foot={cur ? t('analytics.delivery.kpi.toDeliveredFoot', {
                  cairo: fmt.num(days(cur.avgHoursHandedToDeliveredCairoGiza), 1), other: fmt.num(days(cur.avgHoursHandedToDeliveredOther), 1),
                }) : undefined} />
            </KpiRow>

            <AnalyticsCard span="s7" title={t('analytics.delivery.byGovernorate')} desc={t('analytics.delivery.byGovernorateDesc')} testId="card-governorates">
              <QueryBlock q={byGov} isEmpty={d => d.current.groups.length === 0} empty={t('analytics.state.noOrders')}>
                {d => <GovernorateTable groups={d.current.groups} mode={s.mode} />}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s5" title={t('analytics.delivery.reasons')} desc={t('analytics.delivery.reasonsDesc')} testId="card-reasons">
              <QueryBlock q={reasons} isEmpty={d => d.current.failedLegs === 0} empty={t('analytics.delivery.noFailures')}>
                {d => (
                  <>
                    {d.current.reasons.length > 0 && (
                      <HBars color={CHART.red} rows={d.current.reasons.map(r => ({
                        key: r.reason, label: label('reason', r.reason, r.reason), value: r.count, display: fmt.pct(r.share, 0),
                        title: t('analytics.delivery.reasonTip', { count: fmt.num(r.count), total: fmt.num(d.current.withReason) }),
                      }))} />
                    )}
                    <Note>{t('analytics.delivery.reasonsCoverage', {
                      with: fmt.num(d.current.withReason), failed: fmt.num(d.current.failedLegs), pct: fmt.pct(d.current.coverage, 0),
                    })}</Note>
                  </>
                )}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s7" title={t('analytics.delivery.weekly')} desc={t('analytics.delivery.weeklyDesc')} testId="card-weekly">
              <QueryBlock q={summary} lines={5} isEmpty={d => d.current.weeklyTrend.filter(w => w.successRate != null).length < 2} empty={t('analytics.delivery.weeklyEmpty')}>
                {d => {
                  const weeks = d.current.weeklyTrend.filter(w => w.successRate != null)
                  return (
                    <LineChart rtl={rtl} height={210} every={1} ariaLabel={t('analytics.delivery.weekly')}
                      formatY={v => `${fmt.num(v, 0)}%`} labels={weeks.map(w => fmt.day(w.weekStart))}
                      series={[{ name: t('analytics.kpi.successRate'), color: CHART.realized, area: true, values: weeks.map(w => (w.successRate ?? 0) * 100), format: v => `${fmt.num(v, 1)}%` }]} />
                  )
                }}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s5" title={t('analytics.delivery.mostFailed')} desc={t('analytics.delivery.mostFailedDesc', { min: MIN_ORDERS_FOR_FAILURE_RATE })} testId="card-most-failed">
              <QueryBlock q={extras}>
                {d => {
                  const rows = d.current.mostFailed.filter(p => p.orders >= MIN_ORDERS_FOR_FAILURE_RATE && p.failedOrders > 0)
                    .sort((a, b) => (b.failureRate ?? 0) - (a.failureRate ?? 0)).slice(0, 6)
                  if (rows.length === 0) return <CardEmpty>{t('analytics.delivery.mostFailedEmpty', { min: MIN_ORDERS_FOR_FAILURE_RATE })}</CardEmpty>
                  return <HBars color={CHART.red} rows={rows.map(p => ({
                    key: p.productId, label: p.title, value: p.failureRate ?? 0, display: fmt.pct(p.failureRate),
                    title: t('analytics.delivery.mostFailedTip', { failed: fmt.num(p.failedOrders), orders: fmt.num(p.orders) }),
                  }))} />
                }}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s7" title={t('analytics.delivery.watch')} desc={t('analytics.delivery.watchDesc')} testId="card-watch">
              <QueryBlock q={watch} isEmpty={d => d.customers.length === 0} empty={t('analytics.delivery.watchEmpty')}>
                {d => <WatchTable customers={d.customers} />}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s5" title={t('analytics.delivery.speed')} desc={t('analytics.delivery.speedDesc')} testId="card-speed">
              <QueryBlock q={summary} isEmpty={d => d.current.fulfillmentSpeed.every(b => b.orders === 0)} empty={t('analytics.state.noOrders')}>
                {d => {
                  const total = d.current.fulfillmentSpeed.reduce((a, b) => a + b.orders, 0)
                  const first = d.current.fulfillmentSpeed[0], last = d.current.fulfillmentSpeed[d.current.fulfillmentSpeed.length - 1]
                  return (
                    <>
                      <HBars rows={d.current.fulfillmentSpeed.map(b => ({
                        key: b.bucket, label: t(`analytics.delivery.speedBuckets.${b.bucket}`), value: b.orders,
                        display: fmt.pct(total > 0 ? b.orders / total : null, 0),
                        extra: <RatePill rate={b.successRate} text={fmt.pct(b.successRate, 0)} />,
                        title: t('analytics.delivery.speedTip', { orders: fmt.num(b.orders), success: fmt.pct(b.successRate) }),
                      }))} />
                      {first?.successRate != null && last?.successRate != null && first.orders > 0 && last.orders > 0 && (
                        <Note>{t('analytics.delivery.speedNote', { fast: fmt.pct(first.successRate, 0), slow: fmt.pct(last.successRate, 0) })}</Note>
                      )}
                    </>
                  )
                }}
              </QueryBlock>
            </AnalyticsCard>
          </>
        )}
      </AnalyticsGrid>
    </div>
  )
}
