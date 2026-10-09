import { useTranslation } from 'react-i18next'
import { BarChart3, ShoppingBag, Banknote, Percent } from 'lucide-react'
import {
  getCustomerSummary, getProfitByType, getProfitSummary, getRevenueBreakdown, getRevenueDiscounts, getRevenueHeatmap,
  getRevenueSummary, type Breakdown, type BreakdownBy, type Compared, type DiscountRow, type RevenueSummary,
} from '../../analyticsApi'
import { useAnalyticsQuery, type QueryState } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import {
  AnalyticsCard, AnalyticsGrid, CardEmpty, Kpi, KpiRow, Note, QueryBlock, RatePill, SectionTitle, SourceChip,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { LineChart } from '../../components/analytics/charts/LineChart'
import { HBars } from '../../components/analytics/charts/HBars'
import { Waterfall, type WaterfallStep } from '../../components/analytics/charts/Waterfall'
import { Funnel } from '../../components/analytics/charts/Funnel'
import { Heatmap } from '../../components/analytics/charts/Heatmap'
import { CHART } from '../../components/analytics/charts/chartUtils'
import {
  ConnectBostaCard, CostNote, OtherCarrierBanner, relDelta, useGroupLabel, useInventorySync, useMerchant,
} from './shared'

const WF = { blue: CHART.blue, grey: CHART.grey, amber: CHART.amber, red: CHART.red, green: CHART.realized, slate: '#8a94a6' }
const FUNNEL_COLORS = ['#86b6ef', '#5598e7', '#2a78d6', CHART.realized]

function Breakdowns({ by, title, desc, mode, q, pills = true }: {
  by: BreakdownBy; title: string; desc: string; mode: 'booked' | 'realized'; q: QueryState<Compared<Breakdown>>; pills?: boolean
}) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const label = useGroupLabel()
  return (
    <AnalyticsCard span="s6" title={title} desc={desc} testId={`card-by-${by}`}>
      <QueryBlock q={q} isEmpty={d => d.current.groups.length === 0} empty={t('analytics.state.noOrders')}>
        {d => {
          const val = (g: Breakdown['groups'][number]) => (mode === 'realized' ? g.realized : g.booked)
          const rows = [...d.current.groups].sort((a, b) => val(b) - val(a)).slice(0, 12).map(g => ({
            key: g.key,
            label: label(by, g.key, g.label, g.labelAr),
            value: val(g),
            display: fmt.moneyK(val(g)),
            extra: pills ? <RatePill rate={g.successRate} text={fmt.pct(g.successRate)} /> : undefined,
            title: t('analytics.revenue.groupTip', {
              booked: fmt.money(g.booked), realized: fmt.money(g.realized), orders: fmt.num(g.orders), success: fmt.pct(g.successRate),
            }),
          }))
          return <HBars rows={rows} />
        }}
      </QueryBlock>
    </AnalyticsCard>
  )
}

function DiscountTable({ codes, automatic }: { codes: DiscountRow[]; automatic: DiscountRow | null }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const rows = [...codes, ...(automatic && automatic.orders > 0 ? [automatic] : [])]
  return (
    <div className="overflow-x-auto -mx-[18px] px-[18px]">
      <table className="w-full text-[13px] whitespace-nowrap" data-testid="discount-codes">
        <thead>
          <tr className="border-b border-line">
            <th className="tbl-header text-start ps-0">{t('analytics.revenue.codes.code')}</th>
            <th className="tbl-header text-end">{t('analytics.revenue.codes.orders')}</th>
            <th className="tbl-header text-end">{t('analytics.revenue.codes.revenue')}</th>
            <th className="tbl-header text-end">{t('analytics.revenue.codes.cost')}</th>
            <th className="tbl-header text-end">{t('analytics.revenue.codes.delivered')}</th>
            <th className="tbl-header text-end pe-0">{t('analytics.revenue.codes.perCost')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const auto = r === automatic
            return (
              <tr key={auto ? '__automatic' : `${r.code}-${i}`} className="border-b border-line last:border-0" data-testid={auto ? 'discount-automatic' : undefined}>
                <td className="py-[9px] ps-0 pe-2.5">{auto ? <span className="italic text-neutral-text">{t('analytics.revenue.codes.automatic')}</span> : <span className="font-mono text-[12px]">{r.code}</span>}</td>
                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.orders)}</td>
                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.money(r.booked)}</td>
                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.money(r.discountCost)}</td>
                <td className="py-[9px] px-2.5 text-end"><RatePill rate={r.successRate} text={fmt.pct(r.successRate)} /></td>
                <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums">{r.revenuePerCost == null ? '—' : `${fmt.num(r.revenuePerCost, 1)}×`}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function waterfallSteps(s: RevenueSummary, t: (k: string, o?: Record<string, unknown>) => string, fmt: ReturnType<typeof useFmt>): WaterfallStep[] {
  const w = s.waterfall
  const steps: WaterfallStep[] = [
    { key: 'gross', label: t('analytics.revenue.wf.gross'), value: w.gross, total: true, color: WF.blue, note: t('analytics.revenue.wf.grossNote') },
    { key: 'discounts', label: t('analytics.revenue.wf.discounts'), value: -w.discounts, color: WF.grey, note: t('analytics.revenue.wf.discountsNote') },
    { key: 'pipeline', label: t('analytics.revenue.wf.pipeline'), value: -(w.inTransit + w.notShipped), color: WF.amber,
      note: t('analytics.revenue.wf.pipelineNote', { transit: fmt.money(w.inTransit), notShipped: fmt.money(w.notShipped) }) },
  ]
  if (w.otherCarrier > 0) {
    steps.push({ key: 'otherCarrier', label: t('analytics.revenue.wf.otherCarrier'), value: -w.otherCarrier, color: WF.slate, note: t('analytics.revenue.wf.otherCarrierNote') })
  }
  steps.push(
    { key: 'failed', label: t('analytics.revenue.wf.failed'), value: -(w.refused + w.otherTerminal), color: WF.red, note: t('analytics.revenue.wf.failedNote') },
    { key: 'returns', label: t('analytics.revenue.wf.returns'), value: -w.returns, color: WF.red, note: t('analytics.revenue.wf.returnsNote') },
    { key: 'net', label: t('analytics.revenue.wf.net'), value: w.netRealized, total: true, hero: true, color: WF.green, note: t('analytics.revenue.wf.netNote') },
  )
  return steps
}

export default function RevenuePage() {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const merchant = useMerchant()
  const rtl = i18n.language === 'ar'
  const pk = JSON.stringify(s.params)
  const ck = JSON.stringify({ ...s.params, c: s.compare })
  const cq = { ...s.params, compare: s.compare }
  // Realized counts Bosta deliveries only — without Bosta every figure is booked.
  const mode = merchant.noBosta ? 'booked' : s.mode

  const revenue = useAnalyticsQuery(`revenue-summary:${ck}`, sig => getRevenueSummary(cq, sig))
  const byChannel = useAnalyticsQuery(`breakdown:channel:${ck}`, sig => getRevenueBreakdown({ ...cq, by: 'channel' }, sig))
  const byPayment = useAnalyticsQuery(`breakdown:payment:${ck}`, sig => getRevenueBreakdown({ ...cq, by: 'payment' }, sig))
  const byGov = useAnalyticsQuery(`breakdown:governorate:${ck}`, sig => getRevenueBreakdown({ ...cq, by: 'governorate' }, sig))
  const byType = useAnalyticsQuery(`breakdown:productType:${ck}`, sig => getRevenueBreakdown({ ...cq, by: 'productType' }, sig))
  const discounts = useAnalyticsQuery(`discounts:${ck}`, sig => getRevenueDiscounts(cq, sig))
  const heatmap = useAnalyticsQuery(`heatmap:${ck}`, sig => getRevenueHeatmap(cq, sig))
  const customers = useAnalyticsQuery(`customer-summary:${ck}`, sig => getCustomerSummary(cq, sig))
  const profit = useAnalyticsQuery(`profit-summary:${pk}`, sig => getProfitSummary(s.params, sig))
  const profitByType = useAnalyticsQuery(`profit-by-type:${pk}`, sig => getProfitByType(s.params, sig))
  const sync = useInventorySync()

  const cur = revenue.data?.current, prev = revenue.data?.previous
  const loadingRev = !revenue.data && !revenue.error
  const costed = (profit.data?.coverage.variantsCosted ?? 0) > 0

  return (
    <div data-testid="analytics-revenue" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.revenue.title')} subtitle={t('analytics.pages.revenue.subtitle')} showMode={!merchant.noBosta} />
      <AnalyticsGrid>
        <OtherCarrierBanner params={s.params} />
        <KpiRow>
          <Kpi testId="kpi-gross" icon={BarChart3} tone="blue" label={t('analytics.revenue.kpi.gross')} loading={loadingRev}
            value={fmt.moneyK(cur?.grossSales)} delta={relDelta(fmt, cur?.grossSales, prev?.grossSales)} foot={t('analytics.revenue.kpi.grossFoot')} />
          <Kpi testId="kpi-booked" icon={ShoppingBag} tone="blue" label={t('analytics.revenue.kpi.booked')} loading={loadingRev}
            value={fmt.moneyK(cur?.booked)} delta={relDelta(fmt, cur?.booked, prev?.booked)} foot={t('analytics.kpi.afterDiscounts')}
            chip={cur && cur.approximateLines > 0 ? <SourceChip title={t('analytics.chip.approximateTip')}>{t('analytics.chip.approximate')}</SourceChip> : undefined} />
          <Kpi testId="kpi-realized" icon={Banknote} tone={merchant.noBosta ? 'grey' : 'good'} hero={!merchant.noBosta} label={t('analytics.revenue.kpi.realized')} loading={loadingRev}
            value={merchant.noBosta ? '—' : fmt.moneyK(cur?.realized)} delta={merchant.noBosta ? null : relDelta(fmt, cur?.realized, prev?.realized)}
            foot={merchant.noBosta ? t('analytics.kpi.realizedNeedsBosta') : t('analytics.revenue.kpi.realizedFoot')} />
          {!merchant.noBosta && (
            <Kpi testId="kpi-realized-share" icon={Percent} tone="good" label={t('analytics.revenue.kpi.share')} loading={loadingRev}
              value={fmt.pct(cur?.realizedShare)} foot={t('analytics.revenue.kpi.shareFoot')} />
          )}
          <Kpi testId="kpi-gross-margin" icon={Percent} tone={costed ? 'good' : 'grey'} label={t('analytics.revenue.kpi.margin')} loading={!profit.data && !profit.error}
            value={costed ? fmt.pct(profit.data?.grossMargin) : '—'}
            foot={<CostNote coverage={profit.data?.coverage} sync={sync.data} testId="margin-cost-note" />} />
        </KpiRow>

        {merchant.noBosta ? <ConnectBostaCard /> : (
          <>
            <AnalyticsCard span="s7" title={t('analytics.revenue.waterfall')} desc={t('analytics.revenue.waterfallDesc')} testId="card-waterfall">
              <QueryBlock q={revenue} lines={6} isEmpty={d => d.current.orders === 0} empty={t('analytics.state.noOrders')}>
                {d => (
                  <>
                    <Waterfall steps={waterfallSteps(d.current, t, fmt)} format={fmt.money} formatAxis={fmt.axis} rtl={rtl} ariaLabel={t('analytics.revenue.waterfall')} />
                    <div className="flex flex-wrap gap-x-3.5 gap-y-1.5 text-[12px] text-neutral-text">
                      {([['blue', 'start'], ['grey', 'given'], ['amber', 'notYet'], ...(d.current.waterfall.otherCarrier > 0 ? [['slate', 'elsewhere']] : []), ['red', 'lost'], ['green', 'kept']] as Array<[keyof typeof WF, string]>)
                        .map(([c, k]) => <span key={k} className="inline-flex items-center gap-1.5"><i className="inline-block w-2.5 h-2.5 rounded-[3px]" style={{ background: WF[c] }} />{t(`analytics.revenue.wf.legend.${k}`)}</span>)}
                    </div>
                  </>
                )}
              </QueryBlock>
            </AnalyticsCard>
            <AnalyticsCard span="s5" title={t('analytics.revenue.funnel')} desc={t('analytics.revenue.funnelDesc')} testId="card-funnel">
              <QueryBlock q={revenue} isEmpty={d => d.current.funnel.ordered === 0} empty={t('analytics.state.noOrders')}>
                {d => {
                  const f = d.current.funnel
                  const steps = (['ordered', 'fulfilled', 'delivered', 'paidToYou'] as const).map((k, i) => ({
                    key: k, label: t(`analytics.revenue.funnelSteps.${k}`), value: f[k], color: FUNNEL_COLORS[i],
                    display: t('analytics.revenue.funnelOrders', { count: f[k], value: fmt.num(f[k]) }),
                  }))
                  return <Funnel steps={steps} conversion={r => <><b className="text-neutral-text">{fmt.pct(r)}</b> {t('analytics.revenue.funnelContinue')}</>} />
                }}
              </QueryBlock>
            </AnalyticsCard>
          </>
        )}

        <AnalyticsCard span="s12" title={t('analytics.summary.perDay')} testId="card-per-day"
          desc={cur && prev
            ? t('analytics.revenue.perDayCompare', {
              booked: relDelta(fmt, cur.booked, prev.booked)?.text ?? '—', bookedDir: cur.booked >= prev.booked ? '+' : '−',
              realized: relDelta(fmt, cur.realized, prev.realized)?.text ?? '—', realizedDir: cur.realized >= prev.realized ? '+' : '−',
              days: s.days,
            })
            : t('analytics.summary.perDayDesc')}>
          <QueryBlock q={revenue} lines={6} isEmpty={d => d.current.orders === 0} empty={t('analytics.state.noOrders')}>
            {d => (
              <LineChart rtl={rtl} width={1240} height={280} ariaLabel={t('analytics.summary.perDay')} formatY={fmt.axis} labels={d.current.daily.map(x => fmt.day(x.date))}
                series={[
                  { name: t('analytics.mode.booked'), color: CHART.booked, values: d.current.daily.map(x => x.booked), format: fmt.money },
                  ...(merchant.noBosta ? [] : [{ name: t('analytics.mode.realized'), color: CHART.realized, values: d.current.daily.map(x => x.realized), area: true, format: fmt.money }]),
                ]} />
            )}
          </QueryBlock>
        </AnalyticsCard>

        <SectionTitle>{t('analytics.revenue.breakdowns', { mode: t(`analytics.mode.${mode}`) })}</SectionTitle>
        <Breakdowns by="channel" mode={mode} q={byChannel} title={t('analytics.revenue.byChannel')} desc={t('analytics.revenue.byChannelDesc')} />
        <Breakdowns by="payment" mode={mode} q={byPayment} title={t('analytics.revenue.byPayment')} desc={t('analytics.revenue.byPaymentDesc')} />
        <Breakdowns by="governorate" mode={mode} q={byGov} title={t('analytics.revenue.byGovernorate')} desc={t('analytics.revenue.byGovernorateDesc')} />
        <Breakdowns by="productType" mode={mode} q={byType} pills={false} title={t('analytics.revenue.byProductType')} desc={t('analytics.revenue.byProductTypeDesc')} />

        <AnalyticsCard span="s7" title={t('analytics.revenue.codes.title')} desc={t('analytics.revenue.codes.desc')} testId="card-discounts">
          <QueryBlock q={discounts} isEmpty={d => d.current.codes.length === 0 && !(d.current.automatic && d.current.automatic.orders > 0)} empty={t('analytics.revenue.codes.none')}>
            {d => <DiscountTable codes={d.current.codes} automatic={d.current.automatic} />}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s5" title={t('analytics.revenue.newVsReturning')} desc={t('analytics.revenue.newVsReturningDesc')} testId="card-new-returning">
          <QueryBlock q={customers} isEmpty={d => d.current.customersWhoOrdered === 0} empty={t('analytics.state.noOrders')}>
            {d => {
              const classes = ['new', 'existing', 'returning', 'unknown'] as const
              const colors = { new: CHART.blue, existing: '#5598e7', returning: '#eb6834', unknown: CHART.grey }
              const by = Object.fromEntries(d.current.byClass.map(c => [c.key, c]))
              const val = (k: string) => (mode === 'realized' ? by[k]?.realized ?? 0 : by[k]?.booked ?? 0)
              const total = classes.reduce((a, k) => a + val(k), 0)
              const unknownShare = d.current.customersWhoOrdered > 0 ? d.current.unknownCustomers / d.current.customersWhoOrdered : 0
              return (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    {classes.filter(k => (by[k]?.customers ?? 0) > 0).map(k => (
                      <div key={k} className="flex flex-col gap-0.5" data-testid={`class-${k}`}>
                        <span className="text-[12px] text-muted">{t(`analytics.customers.class.${k}`)}</span>
                        <span className="text-[18px] font-semibold">{fmt.moneyK(val(k))}</span>
                        <span className="text-[12px] text-muted">{t('analytics.revenue.classFoot', {
                          share: fmt.pct(total > 0 ? val(k) / total : null, 0), count: by[k].customers, customers: fmt.num(by[k].customers),
                          success: fmt.pct(by[k].successRate, 0),
                        })}</span>
                      </div>
                    ))}
                  </div>
                  {total > 0 && (
                    <div className="flex h-3.5 rounded overflow-hidden gap-0.5" role="img" aria-label={t('analytics.revenue.newVsReturning')}>
                      {classes.map(k => val(k) > 0 && <i key={k} className="block h-full" style={{ width: `${(val(k) / total) * 100}%`, background: colors[k] }} />)}
                    </div>
                  )}
                  {unknownShare >= 0.2 && <Note>{t('analytics.revenue.unknownShare', { pct: fmt.pct(unknownShare, 0) })}</Note>}
                </>
              )
            }}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s7" title={t('analytics.revenue.heatmap')} desc={t('analytics.revenue.heatmapDesc')} testId="card-heatmap">
          <QueryBlock q={heatmap} isEmpty={d => d.current.cells.every(c => c.orders === 0)} empty={t('analytics.state.noOrders')}>
            {d => (
              <Heatmap cells={d.current.cells} fewer={t('analytics.revenue.fewer')} more={t('analytics.revenue.more')}
                dayLabel={wd => t(`analytics.weekday.${wd}`)}
                cellTitle={(wd, h, avg) => t('analytics.revenue.heatTip', { day: t(`analytics.weekday.${wd}`), hour: String(h).padStart(2, '0'), avg: fmt.num(avg, 1) })} />
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s5" title={t('analytics.revenue.marginByType')} desc={t('analytics.revenue.marginByTypeDesc')} testId="card-margin-type">
          <QueryBlock q={profitByType}>
            {d => d.coverage.variantsCosted === 0
              ? <CardEmpty><CostNote coverage={d.coverage} sync={sync.data} testId="margin-type-cost-note" /></CardEmpty>
              : (
                <>
                  <HBars max={1} rows={d.types.filter(x => x.grossMargin != null).map(x => ({
                    key: x.productType,
                    label: x.productType === 'uncategorised' ? t('analytics.labels.productType.uncategorised') : x.productType,
                    value: Math.max(0, x.grossMargin ?? 0),
                    display: fmt.pct(x.grossMargin),
                    title: t('analytics.revenue.marginTip', { costed: fmt.num(x.costedUnits), kept: fmt.num(x.keptUnits) }),
                  }))} color={CHART.realized} />
                  <CostNote coverage={d.coverage} sync={sync.data} testId="margin-type-cost-note" />
                </>
              )}
          </QueryBlock>
        </AnalyticsCard>
      </AnalyticsGrid>
    </div>
  )
}
