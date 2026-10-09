import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Truck, Repeat, Receipt, AlertTriangle, Banknote } from 'lucide-react'
import {
  getFees, getPayouts, getProfitSummary, getRevenueSummary, getStuck, type Fees, type StuckShipment, type NotPaidShipment,
} from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { cn } from '../../components/ui'
import {
  AnalyticsCard, AnalyticsGrid, CardEmpty, Kpi, KpiRow, Note, Pill, QueryBlock, SourceChip,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { HBars } from '../../components/analytics/charts/HBars'
import { CHART } from '../../components/analytics/charts/chartUtils'
import { ConnectBostaCard, CostNote, OtherCarrierBanner, useInventorySync, useMerchant } from './shared'
import { useDrawerParams } from './drawers'

type StuckKind = 'stuck_with_bosta' | 'never_picked_up' | 'delivered_not_paid'
const STUCK_KINDS: StuckKind[] = ['stuck_with_bosta', 'never_picked_up', 'delivered_not_paid']

/** Fees Bosta charges on top of the four leg kinds (anything in the total the legs don't explain). */
function otherFees(f: Fees) {
  return Math.max(0, f.total.amount - f.shipping.amount - f.failed.amount - f.exchange.amount - f.returned.amount)
}

function DrillLink({ onClick, children }: { onClick: () => void; children: React.ReactNode }) {
  return <button type="button" onClick={onClick} className="text-trace-blue font-semibold hover:underline">{children}</button>
}

function StuckCard({ on }: { on: boolean }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const [sp, setSp] = useSearchParams()
  const stuck = useAnalyticsQuery(on ? 'money-stuck' : null, sig => getStuck(sig))
  const kindParam = sp.get('kind') as StuckKind | null
  const kind: StuckKind = kindParam && STUCK_KINDS.includes(kindParam) ? kindParam : 'stuck_with_bosta'
  const setKind = (k: StuckKind) => setSp(prev => { const n = new URLSearchParams(prev); n.set('view', 'stuck'); n.set('kind', k); return n }, { replace: true })
  return (
    <AnalyticsCard span="s12" title={t('analytics.money.stuck.title')} desc={t('analytics.money.stuck.desc')} testId="card-stuck">
      <QueryBlock q={stuck}>
        {d => {
          const lists: Record<StuckKind, Array<StuckShipment | NotPaidShipment>> = {
            stuck_with_bosta: d.stuckWithBosta, never_picked_up: d.neverPickedUp, delivered_not_paid: d.deliveredNotPaid,
          }
          const amount = (r: StuckShipment | NotPaidShipment) => ('cod' in r ? r.cod : r.deposited) ?? 0
          const atRisk = [...d.stuckWithBosta, ...d.neverPickedUp].reduce((a, r) => a + amount(r), 0)
          const notPaid = d.deliveredNotPaid.reduce((a, r) => a + amount(r), 0)
          const all = [...d.stuckWithBosta, ...d.neverPickedUp, ...d.deliveredNotPaid]
          const rows = lists[kind]
          return (
            <>
              <div className="flex flex-wrap items-end gap-x-7 gap-y-2.5">
                <span className="font-mono text-[34px] font-semibold tracking-[-0.03em] leading-none text-critical-text" data-testid="stuck-at-risk">{fmt.money(atRisk + notPaid)}</span>
                <div className="flex flex-col gap-0.5"><span className="text-[12px] text-muted">{t('analytics.money.stuck.shipments')}</span><span className="text-[17px] font-semibold">{fmt.num(all.length)}</span></div>
                <div className="flex flex-col gap-0.5"><span className="text-[12px] text-muted">{t('analytics.money.stuck.oldest')}</span><span className="text-[17px] font-semibold">{all.length ? t('analytics.kpi.days', { count: Math.max(...all.map(r => r.days)), value: fmt.num(Math.max(...all.map(r => r.days))) }) : '—'}</span></div>
                <div className="flex flex-col gap-0.5"><span className="text-[12px] text-muted">{t('analytics.money.stuck.notPaid')}</span><span className="text-[17px] font-semibold text-warning-text">{fmt.money(notPaid)}</span></div>
              </div>
              <div role="group" aria-label={t('analytics.money.stuck.title')} className="flex flex-wrap gap-2">
                {STUCK_KINDS.map(k => (
                  <button key={k} type="button" aria-pressed={k === kind} onClick={() => setKind(k)} data-testid={`stuck-tab-${k}`}
                    className={cn('h-7 px-[11px] rounded-full border text-[12.5px] font-medium inline-flex items-center gap-1.5',
                      k === kind ? 'bg-primary text-white border-primary' : 'border-grey-100 text-neutral-text')}>
                    {t(`analytics.money.stuck.kind.${k}`)} <b className={cn('text-[11.5px]', k === kind ? 'opacity-70' : 'text-muted')}>{lists[k].length}</b>
                  </button>
                ))}
              </div>
              {rows.length === 0 ? <CardEmpty>{t('analytics.money.stuck.none')}</CardEmpty> : (
                <div className="overflow-x-auto -mx-[18px] px-[18px]">
                  <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="stuck-table">
                    <thead>
                      <tr className="border-b border-line">
                        <th className="tbl-header text-start ps-0">{t('analytics.money.stuck.cols.tracking')}</th>
                        <th className="tbl-header text-start">{t('analytics.money.stuck.cols.order')}</th>
                        <th className="tbl-header text-start">{kind === 'delivered_not_paid' ? t('analytics.money.stuck.cols.depositedOn') : t('analytics.money.stuck.cols.lastStatus')}</th>
                        <th className="tbl-header text-end">{t('analytics.money.stuck.cols.days')}</th>
                        <th className="tbl-header text-end pe-0">{kind === 'delivered_not_paid' ? t('analytics.money.stuck.cols.collected') : t('analytics.money.stuck.cols.cod')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map(r => (
                        <tr key={r.trackingNumber} className="border-b border-line last:border-0">
                          <td className="py-[9px] ps-0 pe-2.5 font-mono text-[12px]">{r.trackingNumber}</td>
                          <td className="py-[9px] px-2.5">{r.orderNumber ?? '—'}</td>
                          <td className="py-[9px] px-2.5">{'depositedOn' in r ? fmt.day(r.depositedOn) : r.lastStatus ? t(`analytics.orders.delivery.${r.lastStatus}`, { defaultValue: r.lastStatus }) : '—'}</td>
                          <td className="py-[9px] px-2.5 text-end"><Pill kind={r.days >= 10 ? 'crit' : 'warn'}>{t('analytics.kpi.days', { count: r.days, value: fmt.num(r.days) })}</Pill></td>
                          <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums">{fmt.money(amount(r))}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )
        }}
      </QueryBlock>
    </AnalyticsCard>
  )
}

export default function MoneyPage() {
  const { t } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const merchant = useMerchant()
  const drawers = useDrawerParams()
  const pk = JSON.stringify(s.params)
  const ck = JSON.stringify({ ...s.params, c: s.compare })
  const on = merchant.ready && !merchant.noBosta

  const fees = useAnalyticsQuery(on ? `fees:${pk}` : null, sig => getFees(s.params, sig))
  const payouts = useAnalyticsQuery(on ? `payouts:${pk}` : null, sig => getPayouts(s.params, sig))
  const revenue = useAnalyticsQuery(on ? `revenue-summary:${ck}` : null, sig => getRevenueSummary({ ...s.params, compare: s.compare }, sig))
  const profit = useAnalyticsQuery(on ? `profit-summary:${pk}` : null, sig => getProfitSummary(s.params, sig))
  const sync = useInventorySync()

  const f = fees.data
  const loading = !f && !fees.error
  const est = (n: number | undefined) => (n ? <SourceChip title={t('analytics.chip.estimatedTip')}>{t('analytics.chip.estimated')}</SourceChip> : undefined)
  const realized = revenue.data?.current.realized
  const costed = (profit.data?.coverage.variantsCosted ?? 0) > 0

  return (
    <div data-testid="analytics-money" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.money.title')} subtitle={t('analytics.pages.money.subtitle')} />
      <AnalyticsGrid>
        {merchant.noBosta ? <ConnectBostaCard /> : (
          <>
            <OtherCarrierBanner params={s.params} />
            <KpiRow>
              <Kpi testId="kpi-shipping" icon={Truck} tone="grey" label={t('analytics.money.kpi.shipping')} loading={loading}
                value={fmt.money(f?.shipping.amount)} chip={est(f?.shipping.estimatedCount)}
                foot={f ? t('analytics.drill.shipments', { count: f.shipping.legs, value: fmt.num(f.shipping.legs) }) : undefined} />
              <Kpi testId="kpi-extra" icon={Repeat} tone="bad" label={t('analytics.money.kpi.extra')} loading={loading}
                value={fmt.money(f ? f.failed.amount + f.exchange.amount + f.returned.amount : null)}
                foot={<>{t('analytics.money.kpi.extraFoot')} · <DrillLink onClick={() => drawers.openDrill('extra')}>{t('analytics.money.drill')}</DrillLink></>} />
              <Kpi testId="kpi-other" icon={Receipt} tone="grey" label={t('analytics.money.kpi.other')} loading={loading}
                value={fmt.money(f ? otherFees(f) : null)} foot={t('analytics.money.kpi.otherFoot')} />
              <Kpi testId="kpi-per-success" icon={Receipt} tone="grey" label={t('analytics.money.kpi.perSuccess')} loading={loading}
                value={fmt.money(f?.costPerSuccessfulDelivery)}
                foot={f ? t('analytics.money.kpi.perSuccessFoot', { count: f.deliveredCount, delivered: fmt.num(f.deliveredCount) }) : undefined} />
              <Kpi testId="kpi-per-failed" icon={AlertTriangle} tone="bad" label={t('analytics.money.kpi.perFailed')} loading={loading}
                value={fmt.money(f?.costPerUnsuccessfulDelivery)}
                foot={f ? <>{t('analytics.money.kpi.perFailedFoot', { count: f.refusedCount, failed: fmt.num(f.refusedCount), total: fmt.money(f.failed.amount) })} · <DrillLink onClick={() => drawers.openDrill('failed')}>{t('analytics.money.drill')}</DrillLink></> : undefined} />
              <Kpi testId="kpi-contribution" icon={Banknote} tone={costed ? 'good' : 'grey'} label={t('analytics.money.kpi.contribution')} loading={!profit.data && !profit.error}
                value={costed ? fmt.moneyK(profit.data?.contributionProfit) : '—'}
                foot={costed
                  ? <>{t('analytics.money.kpi.contributionFoot', { pct: fmt.pct(profit.data?.contributionMargin, 0) })} <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="contribution-cost-note" /></>
                  : <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="contribution-cost-note" />} />
            </KpiRow>

            <AnalyticsCard span="s5" title={t('analytics.money.where')} desc={t('analytics.money.whereDesc')} testId="card-where">
              <QueryBlock q={fees} isEmpty={d => d.total.amount === 0} empty={t('analytics.money.noFees')}>
                {d => (
                  <>
                    <HBars rows={[
                      { key: 'shipping', label: t('analytics.money.legs.shipping'), value: d.shipping.amount, display: fmt.money(d.shipping.amount), color: CHART.grey },
                      { key: 'failed', label: t('analytics.drill.type.failed'), value: d.failed.amount, display: fmt.money(d.failed.amount), color: CHART.red },
                      { key: 'exchange', label: t('analytics.drill.type.exchange'), value: d.exchange.amount, display: fmt.money(d.exchange.amount), color: CHART.red },
                      { key: 'return', label: t('analytics.drill.type.return'), value: d.returned.amount, display: fmt.money(d.returned.amount), color: CHART.red },
                      ...(otherFees(d) > 0 ? [{ key: 'other', label: t('analytics.money.kpi.other'), value: otherFees(d), display: fmt.money(otherFees(d)), color: CHART.grey }] : []),
                    ].map(r => ({ ...r, title: t('analytics.money.shareOfTotal', { pct: fmt.pct(d.total.amount ? r.value / d.total.amount : null) }) }))} />
                    <div className="flex flex-wrap gap-x-3.5 gap-y-1.5 text-[12px] text-neutral-text">
                      <span className="inline-flex items-center gap-1.5"><i className="w-2.5 h-2.5 rounded-[3px] inline-block" style={{ background: CHART.red }} />{t('analytics.money.legendLost')}</span>
                      <span className="inline-flex items-center gap-1.5"><i className="w-2.5 h-2.5 rounded-[3px] inline-block" style={{ background: CHART.grey }} />{t('analytics.money.legendNormal')}</span>
                    </div>
                    <Note>{t('analytics.money.total', { value: fmt.money(d.total.amount) })}{realized ? ` ${t('analytics.money.ofRealized', { pct: fmt.pct(d.total.amount / realized) })}` : ''}</Note>
                    {d.settledComponents.settledLegs > 0 && (
                      <Note>{t('analytics.money.components', {
                        legs: fmt.num(d.settledComponents.settledLegs), shipping: fmt.money(d.settledComponents.shippingFees),
                        collection: fmt.money(d.settledComponents.collectionFees), vat: fmt.money(d.settledComponents.vat),
                      })}</Note>
                    )}
                  </>
                )}
              </QueryBlock>
            </AnalyticsCard>

            <AnalyticsCard span="s7" title={t('analytics.money.payouts.title')} desc={t('analytics.money.payouts.desc')} testId="card-payouts">
              <QueryBlock q={payouts} isEmpty={d => d.payouts.length === 0} empty={t('analytics.money.payouts.none')}>
                {d => (
                  <>
                    <div className="overflow-x-auto -mx-[18px] px-[18px]">
                      <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="payouts-table">
                        <thead>
                          <tr className="border-b border-line">
                            <th className="tbl-header text-start ps-0">{t('analytics.money.payouts.date')}</th>
                            <th className="tbl-header text-start">{t('analytics.money.payouts.reference')}</th>
                            <th className="tbl-header text-end">{t('analytics.money.payouts.orders')}</th>
                            <th className="tbl-header text-end">{t('analytics.money.payouts.amount')}</th>
                            <th className="tbl-header text-end">{t('analytics.money.payouts.batch')}</th>
                            <th className="tbl-header text-end pe-0">{t('analytics.money.payouts.status')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {d.payouts.map(p => {
                            const same = p.bostaBatchTotal != null && Math.abs(p.bostaBatchTotal - p.trackedDeposited) < 0.5
                            return (
                              <tr key={p.transactionId} className="border-b border-line last:border-0">
                                <td className="py-[9px] ps-0 pe-2.5">{fmt.day(p.date)}</td>
                                <td className="py-[9px] px-2.5 font-mono text-[12px]">{p.transactionId}</td>
                                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(p.trackedShipments)}</td>
                                <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.money(p.trackedDeposited)}</td>
                                <td className="py-[9px] px-2.5 text-end tabular-nums">{p.bostaBatchTotal == null ? '—' : fmt.money(p.bostaBatchTotal)}</td>
                                <td className="py-[9px] ps-2.5 pe-0 text-end">
                                  {p.bostaBatchTotal == null
                                    ? <span className="text-muted">{t('analytics.money.payouts.noBatch')}</span>
                                    : same
                                      ? <Pill kind="good">{t('analytics.money.payouts.allTraced')}</Pill>
                                      : <span title={t('analytics.money.payouts.differsTip')}><Pill kind="neutral">{t('analytics.money.payouts.differs', { value: fmt.money(Math.abs(p.bostaBatchTotal - p.trackedDeposited)) })}</Pill></span>}
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                    <Note>{t('analytics.money.payouts.note')}</Note>
                  </>
                )}
              </QueryBlock>
            </AnalyticsCard>

            <StuckCard on={on} />

            <AnalyticsCard span="s12" v2 title={t('analytics.money.anomalies')} desc={t('analytics.money.anomaliesDesc')} testId="card-anomalies">
              <CardEmpty>{t('analytics.money.anomaliesLater')}</CardEmpty>
            </AnalyticsCard>
          </>
        )}
      </AnalyticsGrid>
    </div>
  )
}
