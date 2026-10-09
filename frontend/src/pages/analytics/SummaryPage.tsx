import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Banknote, ShoppingBag, Tag, Truck, Receipt, Clock } from 'lucide-react'
import {
  getAlerts, getCashForecast, getDeliverySummary, getFees, getPipeline, getRevenueSummary, getSalesVariants,
  getSalesVariantsDaily, getStockSummary, getStockVariants, type AnalyticsAlert, type CashForecast, type Pipeline,
  type VariantSales, type VariantStock,
} from '../../analyticsApi'
import { getConnections } from '../../api'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { relChange, useFmt, type Fmt } from '../../analytics/format'
import {
  AnalyticsCard, AnalyticsGrid, Banner, CardEmpty, CardError, CardSkeleton, Kpi, KpiRow, Note, Pill, QueryBlock,
  SectionTitle, SourceChip, TextLink, type DeltaInfo,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { LineChart } from '../../components/analytics/charts/LineChart'
import { Sparkline } from '../../components/analytics/charts/Sparkline'
import { HBars } from '../../components/analytics/charts/HBars'
import { CHART } from '../../components/analytics/charts/chartUtils'
import { cn } from '../../components/ui'
import { alertLink, analyticsLink } from './links'

/** Orders shipped outside Bosta at or above this share get the "other carrier" banner. */
const OTHER_CARRIER_BANNER_SHARE = 0.05
const TOP_SKUS = 5

function relDelta(fmt: Fmt, cur: number | null | undefined, prev: number | null | undefined, goodWhenUp = true): DeltaInfo | null {
  const r = relChange(cur, prev)
  if (r == null) return null
  return { text: fmt.pct(Math.abs(r)), dir: Math.abs(r) < 0.0005 ? 'flat' : r > 0 ? 'up' : 'down', goodWhenUp }
}

function ptsDelta(fmt: Fmt, cur: number | null | undefined, prev: number | null | undefined): DeltaInfo | null {
  if (cur == null || prev == null) return null
  const d = cur - prev
  return { text: fmt.pts(d), dir: Math.abs(d) < 0.0005 ? 'flat' : d > 0 ? 'up' : 'down' }
}

// ── Cash pipeline ───────────────────────────────────────────────────────────

function Stage({ step, amount, meta, calc, fill, bank = false, testId }: {
  step: string; amount: string; meta: string; calc?: string; fill?: number | null; bank?: boolean; testId: string
}) {
  return (
    <div data-testid={testId} className={cn('px-[18px] py-4 flex flex-col gap-1.5 min-w-0 relative', bank ? 'bg-[#e8f6ed]' : 'bg-surface')}>
      <span className={cn('text-[11.5px] uppercase tracking-[0.07em] font-semibold', bank ? 'text-success-text' : 'text-muted')}>{step}</span>
      <span className={cn('font-mono font-semibold tracking-[-0.02em] leading-[1.15] break-words', bank ? 'text-success-text text-[28px]' : 'text-warning-text text-[24px]')}>{amount}</span>
      <span className="text-[12.5px] text-neutral-text">{meta}</span>
      {calc && <span className="text-[12px] text-muted">{calc}</span>}
      {fill != null && (
        <div className="h-1.5 rounded bg-elevated overflow-hidden mt-1" aria-hidden="true">
          <i className="block h-full rounded bg-warning" style={{ width: `${Math.min(100, Math.max(0, fill * 100))}%` }} />
        </div>
      )}
    </div>
  )
}

function PipelineStrip({ p, noBosta }: { p: Pipeline; noBosta: boolean }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const nf = p.notFulfilled
  const notFulfilled = (
    <Stage testId="stage-not-fulfilled" step={t('analytics.summary.pipe.notFulfilled')}
      amount={fmt.money(nf.expected ?? nf.value)}
      meta={t('analytics.summary.pipe.notFulfilledMeta', { count: nf.count, value: fmt.money(nf.value) })}
      calc={nf.expected != null ? t('analytics.summary.pipe.expectedCalc') : t('analytics.summary.pipe.faceValueCalc')}
      fill={nf.expected != null && nf.value > 0 ? nf.expected / nf.value : null} />
  )
  if (noBosta) {
    return (
      <div className="col-span-full card p-0 overflow-hidden grid gap-px bg-line grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,3fr)]" data-testid="pipeline">
        {notFulfilled}
        <ConnectBosta />
      </div>
    )
  }
  const it = p.inTransit, aw = p.awaitingPayout, bank = p.inYourBank
  return (
    <div className="col-span-full card p-0 overflow-hidden grid gap-px bg-line grid-cols-1 sm:grid-cols-2 xl:grid-cols-4" data-testid="pipeline">
      {notFulfilled}
      <Stage testId="stage-in-transit" step={t('analytics.summary.pipe.inTransit')} amount={fmt.money(it.expected ?? it.value)}
        meta={t('analytics.summary.pipe.inTransitMeta', { count: it.count, value: fmt.money(it.value) })}
        calc={it.expected != null ? t('analytics.summary.pipe.inTransitCalc') : t('analytics.summary.pipe.faceValueCalc')}
        fill={it.expected != null && it.value > 0 ? it.expected / it.value : null} />
      <Stage testId="stage-awaiting" step={t('analytics.summary.pipe.awaiting')} amount={fmt.money(aw.deposited)}
        meta={aw.nextCashoutDate
          ? t('analytics.summary.pipe.awaitingMetaNext', { count: aw.count, date: fmt.dayLong(aw.nextCashoutDate) })
          : t('analytics.summary.pipe.awaitingMeta', { count: aw.count })}
        calc={aw.deliveredNotYetSettled > 0
          ? t('analytics.summary.pipe.notSettled', { count: aw.deliveredNotYetSettled, value: fmt.money(aw.deliveredNotYetSettledEstimate) })
          : t('analytics.summary.pipe.awaitingCalc')} />
      <Stage testId="stage-bank" bank step={t('analytics.summary.pipe.bank')} amount={fmt.money(bank.deposited)}
        meta={t('analytics.summary.pipe.bankMeta', { count: bank.shipments, payouts: bank.payouts })}
        calc={bank.lastTransferDate ? t('analytics.summary.pipe.lastTransfer', { date: fmt.day(bank.lastTransferDate) }) : undefined} />
    </div>
  )
}

function ConnectBosta() {
  const { t } = useTranslation()
  return (
    <div data-testid="connect-bosta" className="bg-surface px-[18px] py-4 flex flex-col gap-1.5 justify-center">
      <span className="text-[11.5px] uppercase tracking-[0.07em] font-semibold text-muted">{t('analytics.noBosta.title')}</span>
      <span className="text-[13px] text-neutral-text">{t('analytics.noBosta.body')}</span>
      <Link to="/settings?tab=connections" className="text-[12.5px] font-semibold text-trace-blue hover:underline self-start">{t('analytics.noBosta.cta')}</Link>
    </div>
  )
}

// ── Needs attention ─────────────────────────────────────────────────────────

const ALERT_KIND: Record<string, 'crit' | 'warn' | 'info'> = {
  stuck_with_bosta: 'crit', never_picked_up: 'warn', delivered_not_paid: 'warn', low_success_governorates: 'info', sells_out_soon: 'warn',
}

function alertDetail(a: AnalyticsAlert, fmt: Fmt, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (a.key === 'low_success_governorates') {
    return (a.details ?? []).slice(0, 4).map(d => `${(fmt.lang === 'ar' && d.labelAr) || d.label} ${fmt.pct(d.successRate)}`).join(' · ')
  }
  if (a.key === 'sells_out_soon') {
    return (a.skus ?? []).slice(0, 4).map(s => `${s.productTitle} ${s.variantTitle}`).join(', ')
  }
  return a.amount != null && a.amount > 0 ? t(`analytics.alerts.${a.key}.detail`, { amount: fmt.money(a.amount) }) : ''
}

function NeedsAttention({ alerts, search }: { alerts: AnalyticsAlert[]; search: string }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const open = alerts.filter(a => a.count > 0)
  if (open.length === 0) return <CardEmpty>{t('analytics.alerts.none')}</CardEmpty>
  return (
    <div className="flex flex-col gap-2" data-testid="alerts">
      {open.map(a => {
        const kind = ALERT_KIND[a.key] ?? 'info'
        const to = alertLink(a.key, search)
        const body = (
          <>
            <span className={cn('w-[22px] h-[22px] rounded-md grid place-items-center font-bold text-[12px]',
              kind === 'crit' ? 'bg-critical/10 text-critical-text' : kind === 'warn' ? 'bg-warning/15 text-warning-text' : 'bg-trace-blue/10 text-trace-blue-hover')}
              aria-hidden="true">{kind === 'info' ? 'i' : '!'}</span>
            <span className="min-w-0">
              <b className="block font-semibold text-[13.5px] text-primary">{t(`analytics.alerts.${a.key}.title`, { count: a.count })}</b>
              <span className="text-muted text-[12.5px]">{alertDetail(a, fmt, t)}</span>
            </span>
            {to && <span className="text-trace-blue text-[12.5px] font-semibold whitespace-nowrap self-center">{t('analytics.alerts.view')}</span>}
          </>
        )
        const cls = 'grid grid-cols-[22px_minmax(0,1fr)_auto] gap-2.5 items-start px-3 py-2.5 rounded-lg bg-elevated'
        return to
          ? <Link key={a.key} to={to} data-testid={`alert-${a.key}`} className={cn(cls, 'hover:bg-trace-blue/5')}>{body}</Link>
          : <div key={a.key} data-testid={`alert-${a.key}`} className={cls}>{body}</div>
      })}
    </div>
  )
}

// ── Expected cash in ────────────────────────────────────────────────────────

function ExpectedCash({ f }: { f: CashForecast }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  if (!f.forecast) return <CardEmpty>{t(`analytics.summary.cash.reason.${f.reason ?? 'payout_lag_unknown'}`)}</CardEmpty>
  const rows = f.forecast.buckets.map(b => ({
    key: b.key,
    label: t(`analytics.summary.cash.${b.key}`),
    value: b.amount,
    display: fmt.money(b.amount),
    title: t('analytics.summary.cash.tip', { awaiting: fmt.money(b.awaitingPayout), transit: fmt.money(b.inTransit) }),
  }))
  return (
    <>
      <HBars rows={rows} color={CHART.amber} />
      {f.forecast.later > 0 && <Note>{t('analytics.summary.cash.later', { amount: fmt.money(f.forecast.later) })}</Note>}
      <Note>{t('analytics.summary.cash.note', {
        weekday: fmt.dayLong(f.forecast.method.nextPayoutDate),
        lag: fmt.num(f.forecast.method.medianLagDays, 1),
      })}</Note>
    </>
  )
}

// ── Top SKUs ────────────────────────────────────────────────────────────────

function TopSkus({ rows, daily, stock, mode, rtl }: {
  rows: VariantSales[]
  daily: Map<string, number[]> | null
  stock: { byId: Map<string, VariantStock>; source: 'pieces' | 'shopify' } | null
  mode: 'booked' | 'realized'
  rtl: boolean
}) {
  const { t } = useTranslation()
  const fmt = useFmt()
  return (
    <div className="overflow-x-auto -mx-[18px] px-[18px]">
      <table className="w-full text-[13px]" data-testid="top-skus">
        <thead>
          <tr className="border-b border-line">
            <th className="tbl-header text-start ps-0">{t('analytics.summary.top.sku')}</th>
            <th className="tbl-header text-start">{t('analytics.summary.top.perDay')}</th>
            <th className="tbl-header text-end">{t('analytics.summary.top.units')}</th>
            <th className="tbl-header text-end">{t('analytics.summary.top.revenue')}</th>
            {stock && (
              <th className="tbl-header text-end pe-0">
                {t('analytics.summary.top.stockLeft')}
                {stock.source === 'shopify' && <span className="ms-1.5 align-middle"><SourceChip>{t('analytics.stock.sourceShopify')}</SourceChip></span>}
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rows.map(r => {
            const s = stock?.byId.get(r.variantId)
            const cover = s?.daysOfCover
            return (
              <tr key={r.variantId} className="border-b border-line last:border-0">
                <td className="py-[9px] ps-0 pe-2.5 min-w-0">
                  <div className="font-medium text-primary truncate max-w-[220px]">{r.productTitle}</div>
                  <div className="text-[12px] text-muted truncate max-w-[220px]">{r.variantTitle}{r.sku ? ` · ${r.sku}` : ''}</div>
                </td>
                <td className="py-[9px] px-2.5">
                  {daily ? <Sparkline values={daily.get(r.variantId) ?? []} rtl={rtl} /> : <span className="text-muted">—</span>}
                </td>
                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.soldUnits)}</td>
                <td className="py-[9px] px-2.5 text-end tabular-nums">
                  {fmt.moneyK(mode === 'realized' ? r.netRevenue : r.grossRevenue)}
                  {r.approximateLines > 0 && <span className="ms-1.5 align-middle"><SourceChip title={t('analytics.chip.approximateTip')}>{t('analytics.chip.approximate')}</SourceChip></span>}
                </td>
                {stock && (
                  <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums" title={s ? t('analytics.summary.top.onHand', { count: s.stockUsed }) : undefined}>
                    {s == null ? '—' : cover == null
                      ? t('analytics.summary.top.unitsLeft', { count: s.stockUsed })
                      : cover < 7
                        ? <Pill kind="warn">{t('analytics.summary.top.days', { count: Math.round(cover) })}</Pill>
                        : t('analytics.summary.top.days', { count: Math.round(cover) })}
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function SummaryPage() {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const rtl = i18n.language === 'ar'
  const pk = JSON.stringify(s.params)
  const ck = JSON.stringify({ ...s.params, c: s.compare })

  const connections = useAnalyticsQuery('connections', () => getConnections())
  const pipeline = useAnalyticsQuery(`pipeline:${pk}`, sig => getPipeline(s.params, sig))
  const revenue = useAnalyticsQuery(`revenue-summary:${ck}`, sig => getRevenueSummary({ ...s.params, compare: s.compare }, sig))
  const delivery = useAnalyticsQuery(`delivery-summary:${ck}`, sig => getDeliverySummary({ ...s.params, compare: s.compare }, sig))
  const fees = useAnalyticsQuery(`fees:${pk}`, sig => getFees(s.params, sig))
  const forecast = useAnalyticsQuery('cash-forecast', sig => getCashForecast(sig))
  const alerts = useAnalyticsQuery(`alerts:${pk}`, sig => getAlerts(s.params, sig))
  const sales = useAnalyticsQuery(`sales-variants:${pk}`, sig => getSalesVariants(s.params, sig))
  const stockSummary = useAnalyticsQuery(`stock-summary:${pk}`, sig => getStockSummary(s.params, sig))
  const stock = useAnalyticsQuery('stock-variants:500', sig => getStockVariants({ limit: 500 }, sig))

  const noBosta = connections.data != null && !connections.data.bosta.connected
  const top = useMemo(() => {
    const v = sales.data?.variants ?? []
    const val = (r: VariantSales) => (s.mode === 'realized' ? r.netRevenue : r.grossRevenue)
    return [...v].filter(r => val(r) > 0 || r.soldUnits > 0).sort((a, b) => val(b) - val(a)).slice(0, TOP_SKUS)
  }, [sales.data, s.mode])
  const topIds = top.map(r => r.variantId)
  const daily = useAnalyticsQuery(topIds.length ? `sales-daily:${topIds.join(',')}:${pk}` : null,
    sig => getSalesVariantsDaily(topIds, s.params, sig))
  const dailyMap = useMemo(() => daily.data ? new Map(daily.data.variants.map(v => [v.variantId, v.units])) : null, [daily.data])
  const stockMap = useMemo(() => stock.data?.hasPieces
    ? { byId: new Map(stock.data.variants.map(v => [v.variantId, v])), source: stock.data.stockSource }
    : null, [stock.data])

  const cur = revenue.data?.current, prev = revenue.data?.previous
  const revValue = cur ? (s.mode === 'realized' ? cur.realized : cur.booked) : undefined
  const revPrev = prev ? (s.mode === 'realized' ? prev.realized : prev.booked) : undefined
  const aov = cur && cur.orders > 0 ? cur.booked / cur.orders : null
  const aovPrev = prev && prev.orders > 0 ? prev.booked / prev.orders : null
  const dCur = delivery.data?.current, dPrev = delivery.data?.previous
  const costPerDelivery = fees.data && fees.data.deliveredCount > 0 ? fees.data.total.amount / fees.data.deliveredCount : null
  const lag = forecast.data?.forecast?.method.medianLagDays ?? null

  const totals = sales.data?.totals
  const otherCarrierShare = totals && totals.orders > 0 ? totals.wijhaOrders / totals.orders : 0
  const lowTrust = stockSummary.data?.hasPieces && stockSummary.data.lowTrust

  // Realized counts Bosta deliveries only (other carriers are their own leak), so with no Bosta
  // account it is always 0 — show that it can't be measured rather than a green EGP 0.
  const realizedUnmeasured = noBosta && s.mode === 'realized'
  const vsPrev = t('analytics.kpi.vsPrevious', { days: s.days })
  const noPrevious = revenue.data && !revenue.data.previous && s.days > 92 ? t('analytics.kpi.noCompare') : undefined

  return (
    <div data-testid="analytics-summary" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.summary.title')} subtitle={t('analytics.pages.summary.subtitle')} showMode />
      <AnalyticsGrid>
        {otherCarrierShare >= OTHER_CARRIER_BANNER_SHARE && totals && (
          <Banner testId="other-carrier-banner">
            {t('analytics.otherCarrier.banner', { pct: fmt.pct(otherCarrierShare, 0), count: totals.wijhaOrders })}
          </Banner>
        )}

        <SectionTitle>{t('analytics.summary.cashTitle')}</SectionTitle>
        {pipeline.error && !pipeline.data
          ? <div className="col-span-full card p-[18px]"><CardError onRetry={pipeline.retry} /></div>
          : pipeline.data && connections.data
            ? <PipelineStrip p={pipeline.data} noBosta={noBosta} />
            : <div className="col-span-full card p-[18px]"><CardSkeleton lines={3} /></div>}

        <KpiRow>
          <Kpi testId="kpi-revenue" icon={s.mode === 'realized' ? Banknote : ShoppingBag} tone={realizedUnmeasured ? 'grey' : s.mode === 'realized' ? 'good' : 'blue'} hero={s.mode === 'realized' && !realizedUnmeasured}
            label={t(`analytics.kpi.netRevenue.${s.mode}`)} loading={!revenue.data && !revenue.error}
            value={revenue.error && !revenue.data ? '—' : realizedUnmeasured ? '—' : fmt.moneyK(revValue)}
            delta={realizedUnmeasured ? null : relDelta(fmt, revValue, revPrev)}
            foot={realizedUnmeasured ? t('analytics.kpi.realizedNeedsBosta') : revPrev != null ? vsPrev : noPrevious}
            chip={cur && cur.approximateLines > 0 ? <SourceChip title={t('analytics.chip.approximateTip')}>{t('analytics.chip.approximate')}</SourceChip> : undefined}
            to={analyticsLink('revenue', s.search)} />
          <Kpi testId="kpi-orders" icon={ShoppingBag} tone="blue" label={t('analytics.kpi.orders')} loading={!revenue.data && !revenue.error}
            value={fmt.num(cur?.orders)} delta={relDelta(fmt, cur?.orders, prev?.orders)} foot={prev ? vsPrev : noPrevious} />
          <Kpi testId="kpi-aov" icon={Tag} tone="blue" label={t('analytics.kpi.aov')} loading={!revenue.data && !revenue.error}
            value={fmt.money(aov)} delta={relDelta(fmt, aov, aovPrev)} foot={t('analytics.kpi.afterDiscounts')} />
          {!noBosta && (
            <>
              <Kpi testId="kpi-success" icon={Truck} tone="good" label={t('analytics.kpi.successRate')} loading={!delivery.data && !delivery.error}
                value={fmt.pct(dCur?.successRate)} delta={ptsDelta(fmt, dCur?.successRate, dPrev?.successRate)}
                foot={t('analytics.kpi.successFoot')} to={analyticsLink('delivery', s.search)} />
              <Kpi testId="kpi-cost-per-delivery" icon={Receipt} tone="grey" label={t('analytics.kpi.costPerDelivery')} loading={!fees.data && !fees.error}
                value={fmt.money(costPerDelivery)} foot={t('analytics.kpi.costPerDeliveryFoot')}
                chip={fees.data && fees.data.total.estimatedCount > 0 ? <SourceChip title={t('analytics.chip.estimatedTip')}>{t('analytics.chip.estimated')}</SourceChip> : undefined}
                to={analyticsLink('money', s.search)} />
              <Kpi testId="kpi-payout-lag" icon={Clock} tone="warn" label={t('analytics.kpi.payoutLag')} loading={!forecast.data && !forecast.error}
                value={lag == null ? '—' : t('analytics.kpi.days', { count: lag, value: fmt.num(lag, 1) })}
                foot={lag == null && forecast.data?.reason ? t(`analytics.summary.cash.reason.${forecast.data.reason}`) : t('analytics.kpi.payoutLagFoot')}
                to={analyticsLink('money', s.search)} />
            </>
          )}
        </KpiRow>

        <AnalyticsCard span="s5" title={t('analytics.summary.attention')} testId="card-attention">
          <QueryBlock q={alerts}>{d => <NeedsAttention alerts={d.alerts} search={s.search} />}</QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s7" title={t('analytics.summary.perDay')} desc={t('analytics.summary.perDayDesc')} testId="card-per-day">
          <QueryBlock q={revenue} lines={6} isEmpty={d => d.current.daily.length === 0 || d.current.orders === 0} empty={t('analytics.state.noOrders')}>
            {d => (
              <>
                <LineChart rtl={rtl} ariaLabel={t('analytics.summary.perDay')} formatY={fmt.axis}
                  labels={d.current.daily.map(x => fmt.day(x.date))}
                  series={[
                    { name: t('analytics.mode.booked'), color: CHART.booked, values: d.current.daily.map(x => x.booked), format: fmt.money },
                    ...(noBosta ? [] : [{ name: t('analytics.mode.realized'), color: CHART.realized, values: d.current.daily.map(x => x.realized), area: true, format: fmt.money }]),
                  ]} />
                <Note>{noBosta ? t('analytics.kpi.realizedNeedsBosta') : t('analytics.summary.perDayNote')}</Note>
              </>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s7" title={t('analytics.summary.topSkus')} testId="card-top-skus"
          desc={t(`analytics.summary.topSkusDesc.${s.mode}`)}
          right={analyticsLink('products', s.search) ? <TextLink to={analyticsLink('products', s.search)!}>{t('analytics.summary.allSkus')}</TextLink> : undefined}>
          {lowTrust && stockSummary.data && (
            <Banner tone="warn" testId="low-trust-banner">
              {t('analytics.stock.lowTrust', { pct: fmt.pct(stockSummary.data.trust.packedThroughTracedPct, 0) })}
            </Banner>
          )}
          <QueryBlock q={sales} isEmpty={() => top.length === 0} empty={t('analytics.state.noSales')}>
            {() => <TopSkus rows={top} daily={dailyMap} stock={stockMap} mode={s.mode} rtl={rtl} />}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s5" title={t('analytics.summary.cashIn')} desc={t('analytics.summary.cashInDesc')} testId="card-cash-in">
          {noBosta ? <CardEmpty>{t('analytics.noBosta.body')}</CardEmpty> : (
            <QueryBlock q={forecast}>{f => <ExpectedCash f={f} />}</QueryBlock>
          )}
        </AnalyticsCard>
      </AnalyticsGrid>
    </div>
  )
}
