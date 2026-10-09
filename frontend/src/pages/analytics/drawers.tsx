import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Banknote, Box, ShoppingBag, Repeat, AlertTriangle, Receipt } from 'lucide-react'
import {
  getDeliverySummary, getExtraFees, getFailureReasons, getFees, getPieceHistory, getProductExtras, getProfitSkus,
  getSalesVariants, getSalesVariantsDaily, getStockVariants, getTripPieces, getVariantOrders, getVariantPieces, type ExtraByAwb,
  type ExtraBySku, type PieceHistory,
} from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { Drawer } from '../../components/Drawer'
import { cn } from '../../components/ui'
import { CardEmpty, Kpi, Note, Pill, QueryBlock, SourceChip, type PillKind } from '../../components/analytics/ui'
import { LineChart } from '../../components/analytics/charts/LineChart'
import { HBars } from '../../components/analytics/charts/HBars'
import { CHART } from '../../components/analytics/charts/chartUtils'
import { CostNote, useGroupLabel, useInventorySync } from './shared'

// The two analytics drawers, opened from the URL so they're linkable and survive a reload:
//   ?sku=<variantId>                     the SKU drawer (any analytics page)
//   ?drill=extra|failed&view=sku|awb     the extra-shipping / unsuccessful-delivery drill-down

export function useDrawerParams() {
  const [sp, setSp] = useSearchParams()
  const set = useCallback((mut: (n: URLSearchParams) => void) => {
    setSp(prev => { const n = new URLSearchParams(prev); mut(n); return n }, { replace: false })
  }, [setSp])
  return {
    sku: sp.get('sku'),
    piece: sp.get('piece'),
    pieces: sp.get('pieces') != null,
    openPiece: (id: string) => set(n => { n.set('piece', id) }),
    closePiece: () => set(n => { n.delete('piece') }),
    /** One URL update: two separate ones would each start from the same URL and the second would win. */
    pieceToSku: (variantId: string) => set(n => { n.delete('piece'); n.set('sku', variantId) }),
    openPieces: () => set(n => { n.set('pieces', '4') }),
    closePieces: () => set(n => { n.delete('pieces') }),
    drill: sp.get('drill') as 'extra' | 'failed' | null,
    view: (sp.get('view') === 'awb' ? 'awb' : 'sku') as 'sku' | 'awb',
    openSku: (id: string) => set(n => { n.set('sku', id) }),
    closeSku: () => set(n => { n.delete('sku') }),
    openDrill: (kind: 'extra' | 'failed') => set(n => { n.set('drill', kind); n.delete('view') }),
    setView: (v: 'sku' | 'awb') => set(n => { if (v === 'awb') n.set('view', 'awb'); else n.delete('view') }),
    closeDrill: () => set(n => { n.delete('drill'); n.delete('view') }),
  }
}

export const FIN_PILL: Record<string, PillKind> = {
  paid: 'good', awaiting_payout: 'info', expected: 'neutral', overdue: 'warn', lost: 'crit', refunded: 'serious', other_carrier: 'neutral',
}

const TL_DOT: Record<string, string> = { out: 'bg-[#2a78d6]', back: 'bg-[#ec835a]', done: 'bg-success', in: 'bg-neutral-text' }

function Section({ title, desc, right, children, testId }: { title: string; desc?: string; right?: React.ReactNode; children: React.ReactNode; testId?: string }) {
  return (
    <section className="card p-[18px] flex flex-col gap-3" data-testid={testId}>
      <div className="flex items-start gap-2.5 flex-wrap">
        <div className="flex-1 min-w-0">
          <h3 className="text-[12.5px] font-bold tracking-[0.05em] uppercase text-primary">{title}</h3>
          {desc && <p className="text-[12.5px] text-muted mt-0.5">{desc}</p>}
        </div>
        {right}
      </div>
      {children}
    </section>
  )
}

// ── Piece timeline (SKU drawer + piece drawer) ─────────────────────────────

function PieceTimeline({ h }: { h: PieceHistory }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  return (
    <div className="flex flex-col">
      <div className="grid grid-cols-[18px_minmax(0,1fr)_auto] gap-2.5 pb-3 text-[13px]">
        <i className={cn('w-2.5 h-2.5 rounded-full mt-1 ms-1', TL_DOT.in)} />
        <div><div className="font-medium">{t('analytics.sku.received')}</div><div className="text-[12px] text-muted">{fmt.day(h.receivedAt)}</div></div>
        <span className="text-[12px] text-muted">{h.location ?? ''}</span>
      </div>
      {h.trips.map((tr, i) => {
        const kind = tr.outcome === 'delivered' ? 'done' : !tr.outcome || tr.outcome === 'in_transit' || tr.outcome === 'unknown' ? 'out' : 'back'
        return (
          <div key={i} className="grid grid-cols-[18px_minmax(0,1fr)_auto] gap-2.5 pb-3 text-[13px]" data-testid="trip">
            <i className={cn('w-2.5 h-2.5 rounded-full mt-1 ms-1', TL_DOT[kind])} />
            <div>
              <div className="font-medium">{t(`analytics.sku.trip.${tr.outcome ?? 'shipped'}`, { defaultValue: tr.outcome ?? '' })}{tr.orderNumber ? ` · ${tr.orderNumber}` : ''}</div>
              <div className="text-[12px] text-muted">{[tr.trackingNumber, fmt.day(tr.at), tr.cityName].filter(Boolean).join(' · ')}</div>
            </div>
            <span className="text-[12px] text-muted">{tr.fee != null ? `${fmt.money(tr.fee)}${tr.feeEstimated ? ` (${t('analytics.chip.estimated')})` : ''}` : ''}</span>
          </div>
        )
      })}
    </div>
  )
}

// ── SKU drawer ──────────────────────────────────────────────────────────────

function SkuDrawerBody({ variantId }: { variantId: string }) {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const rtl = i18n.language === 'ar'
  const pk = JSON.stringify(s.params)
  const sync = useInventorySync()
  const sales = useAnalyticsQuery(`sales-variants:${pk}`, sig => getSalesVariants(s.params, sig))
  const profit = useAnalyticsQuery(`profit-skus:500:${pk}`, sig => getProfitSkus({ ...s.params, limit: 500 }, sig))
  const stock = useAnalyticsQuery('stock-variants:500', sig => getStockVariants({ limit: 500 }, sig))
  const extras = useAnalyticsQuery(`product-extras:${JSON.stringify({ ...s.params, c: false })}`, sig => getProductExtras(s.params, sig))
  const daily = useAnalyticsQuery(`sales-daily:${variantId}:${pk}`, sig => getSalesVariantsDaily([variantId], s.params, sig))
  const orders = useAnalyticsQuery(`variant-orders:${variantId}`, sig => getVariantOrders(variantId, sig))
  const pieces = useAnalyticsQuery(`variant-pieces:${variantId}`, sig => getVariantPieces(variantId, { limit: 50 }, sig))
  const top = pieces.data?.pieces.length ? [...pieces.data.pieces].sort((a, b) => b.trips - a.trips)[0] : null
  const history = useAnalyticsQuery(top ? `piece-history:${top.pieceId}` : null, sig => getPieceHistory(top!.pieceId, sig))

  const sale = sales.data?.variants.find(v => v.variantId === variantId)
  const prof = profit.data?.skus.find(v => v.variantId === variantId)
  const st = stock.data?.hasPieces ? stock.data.variants.find(v => v.variantId === variantId) : undefined
  const abc = extras.data?.current.abc.find(v => v.variantId === variantId)
  const title = sale ? `${sale.productTitle} · ${sale.variantTitle}` : prof ? `${prof.productTitle} · ${prof.variantTitle}` : st ? `${st.productTitle} · ${st.variantTitle}` : null
  const sku = sale?.sku ?? prof?.sku ?? st?.sku
  const fourPlus = pieces.data?.pieces.filter(p => p.trips >= 4).length ?? 0

  return (
    <div className="flex flex-col gap-4" data-testid="sku-drawer">
      <div>
        {sku && <span className="font-mono text-[12px] text-muted">{sku}</span>}
        <p className="text-[18px] font-semibold text-primary" data-testid="sku-title">{title ?? (sales.data ? t('analytics.sku.noSales') : '…')}</p>
        <p className="text-[12.5px] text-muted flex gap-2 flex-wrap items-center">
          {prof?.unitCost != null && <span>{t('analytics.sku.cost', { value: fmt.money(prof.unitCost) })}</span>}
          {abc && <span>{t('analytics.sku.abc', { cls: abc.abcClass })}</span>}
        </p>
      </div>
      <div className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(140px,1fr))]">
        <Kpi testId="sku-units" icon={Box} tone="blue" label={t('analytics.sku.units')} value={fmt.num(sale?.soldUnits ?? 0)}
          foot={t('analytics.sku.perDay', { value: fmt.num((sale?.soldUnits ?? 0) / s.days, 1) })} loading={!sales.data} />
        <Kpi testId="sku-realized" icon={Banknote} tone="good" label={t('analytics.sku.realized')} value={fmt.moneyK(sale?.netRevenue ?? 0)}
          foot={t('analytics.sku.booked', { value: fmt.moneyK(sale?.grossRevenue ?? 0) })} loading={!sales.data} />
        <Kpi testId="sku-true-net" icon={Banknote} tone={prof?.trueNet != null && prof.trueNet < 0 ? 'bad' : prof?.costed ? 'good' : 'grey'}
          label={t('analytics.sku.trueNet')} loading={!profit.data}
          value={prof?.costed ? fmt.moneyK(prof.trueNet) : '—'}
          foot={prof?.costed ? t('analytics.sku.ofRealized', { pct: fmt.pct(prof.trueNetMargin, 0) }) : <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="sku-cost-note" />} />
        {stock.data?.hasPieces !== false && (
          <Kpi testId="sku-stock" icon={ShoppingBag} tone="blue" label={t('analytics.sku.stock')} loading={!stock.data}
            value={st ? fmt.num(st.stockUsed) : '—'}
            foot={st?.daysOfCover != null ? t('analytics.summary.top.days', { count: Math.round(st.daysOfCover) }) : undefined}
            chip={st?.stockSource === 'shopify' ? <SourceChip>{t('analytics.stock.sourceShopify')}</SourceChip> : undefined} />
        )}
        <Kpi testId="sku-returns" icon={Repeat} tone="bad" label={t('analytics.sku.returns')} loading={!sales.data}
          value={sale?.returnRate == null ? t('analytics.sku.noReturns') : fmt.pct(sale.returnRate)}
          foot={st?.topReturnReason ? t('analytics.sku.topReason', { reason: st.topReturnReason }) : undefined} />
        <Kpi testId="sku-failed" icon={AlertTriangle} tone="bad" label={t('analytics.sku.failed')} loading={!sales.data}
          value={fmt.pct(sale?.refusalRate)} foot={st?.avgPieceAgeDays != null ? t('analytics.sku.age', { value: fmt.num(st.avgPieceAgeDays, 0) }) : undefined} />
      </div>

      <Section title={t('analytics.sku.unitsPerDay')} testId="sku-daily">
        <QueryBlock q={daily} isEmpty={d => (d.variants[0]?.totalUnits ?? 0) === 0} empty={t('analytics.state.noSales')}>
          {d => <LineChart rtl={rtl} height={170} ariaLabel={t('analytics.sku.unitsPerDay')} formatY={v => fmt.num(v)}
            labels={d.days.map(x => fmt.day(x))}
            series={[{ name: t('analytics.sku.units'), color: CHART.blue, area: true, values: d.variants[0]?.units ?? [], format: v => fmt.num(v) }]} />}
        </QueryBlock>
      </Section>

      <Section title={t('analytics.sku.money')} testId="sku-money">
        {!prof ? <CardEmpty>{t('analytics.state.noSales')}</CardEmpty> : (
          <>
            <HBars max={Math.max(1, prof.realized)} rows={[
              { key: 'realized', label: t('analytics.sku.realized'), value: prof.realized, display: fmt.money(prof.realized), color: CHART.blue },
              ...(prof.costed ? [{ key: 'cogs', label: t('analytics.sku.cogs'), value: prof.cogs ?? 0, display: `−${fmt.money(prof.cogs)}`, color: CHART.grey }] : []),
              { key: 'ship', label: t('analytics.sku.shipping'), value: prof.shippingFees, display: `−${fmt.money(prof.shippingFees)}`, color: CHART.grey },
              { key: 'other', label: t('analytics.sku.otherFees'), value: prof.otherFees, display: `−${fmt.money(prof.otherFees)}`, color: CHART.red },
              prof.costed
                ? { key: 'net', label: t('analytics.sku.trueNet'), value: Math.max(0, prof.trueNet ?? 0), display: fmt.money(prof.trueNet), color: (prof.trueNet ?? 0) < 0 ? CHART.red : CHART.realized }
                : { key: 'net', label: t('analytics.sku.netBeforeCost'), value: Math.max(0, prof.netBeforeCost), display: fmt.money(prof.netBeforeCost), color: CHART.realized },
            ]} />
            {!prof.costed && <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="sku-money-cost-note" />}
          </>
        )}
      </Section>

      <Section title={top ? t('analytics.sku.pieceHistory', { piece: top.shortCode ?? top.barcode }) : t('analytics.sku.pieceHistoryNone')} testId="sku-piece"
        desc={top ? t('analytics.sku.pieceDesc', { count: top.trips, trips: fmt.num(top.trips), fourPlus: fmt.num(fourPlus) }) : undefined}>
        {!pieces.data ? <Note>…</Note> : !top ? <CardEmpty>{t('analytics.sku.noPieces')}</CardEmpty> : (
          <QueryBlock q={history}>{h => <PieceTimeline h={h} />}</QueryBlock>
        )}
      </Section>

      <Section title={t('analytics.sku.recentOrders')} testId="sku-orders">
        <QueryBlock q={orders} isEmpty={d => d.orders.length === 0} empty={t('analytics.state.noOrders')}>
          {d => (
            <table className="w-full text-[13px] [&_td]:whitespace-nowrap">
              <tbody>
                {d.orders.slice(0, 8).map(o => (
                  <tr key={o.orderId} className="border-b border-line last:border-0">
                    <td className="py-2 font-mono text-[12px]">{o.name}</td>
                    <td className="py-2 px-2.5">{fmt.day(o.placedAt)}</td>
                    <td className="py-2 px-2.5">{(i18n.language === 'ar' && o.governorate?.labelAr) || o.governorate?.label || '—'}</td>
                    <td className="py-2 text-end"><Pill kind={FIN_PILL[o.financialStatus] ?? 'neutral'}>{t(`analytics.orders.fin.${o.financialStatus}`)}</Pill></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </QueryBlock>
      </Section>
    </div>
  )
}

export function SkuDrawer() {
  const { t } = useTranslation()
  const d = useDrawerParams()
  return (
    <Drawer open={!!d.sku} onClose={d.closeSku} title={t('analytics.sku.title')} closeLabel={t('analytics.drawer.close')}>
      {d.sku && <SkuDrawerBody variantId={d.sku} />}
    </Drawer>
  )
}

// ── Drill-down ──────────────────────────────────────────────────────────────

const TYPE_PILL: Record<string, PillKind> = { failed: 'crit', exchange: 'warn', return: 'serious' }

function DrillBody({ kind, view }: { kind: 'extra' | 'failed'; view: 'sku' | 'awb' }) {
  const { t } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const d = useDrawerParams()
  const label = useGroupLabel()
  const pk = JSON.stringify(s.params)
  const ck = JSON.stringify({ ...s.params, c: s.compare })
  const fees = useAnalyticsQuery(`fees:${pk}`, sig => getFees(s.params, sig))
  const extra = useAnalyticsQuery(`extra-fees:${view}:${pk}`, sig => getExtraFees({ ...s.params, groupBy: view }, sig))
  const delivery = useAnalyticsQuery(kind === 'failed' ? `delivery-summary:${ck}` : null, sig => getDeliverySummary({ ...s.params, compare: s.compare }, sig))
  const reasons = useAnalyticsQuery(kind === 'failed' ? `failure-reasons:${ck}` : null, sig => getFailureReasons({ ...s.params, compare: s.compare }, sig))
  const f = fees.data
  const failedOnly = kind === 'failed'
  const avgFailed = f?.costPerUnsuccessfulDelivery ?? null

  const kpis = failedOnly ? (
    <>
      <Kpi testId="drill-cost-per-failed" icon={AlertTriangle} tone="bad" label={t('analytics.drill.costPerFailed')} value={fmt.money(avgFailed)} foot={t('analytics.drill.costPerFailedFoot')} loading={!f} />
      <Kpi testId="drill-failed-count" icon={AlertTriangle} tone="bad" label={t('analytics.drill.failedCount')} value={fmt.num(f?.failed.legs)} loading={!f} />
      <Kpi testId="drill-total" icon={Receipt} tone="bad" label={t('analytics.drill.totalCost')} value={fmt.money(f?.failed.amount)} loading={!f}
        chip={f && f.failed.estimatedCount > 0 ? <SourceChip>{t('analytics.chip.estimated')}</SourceChip> : undefined} />
      <Kpi testId="drill-lost-sales" icon={Banknote} tone="bad" label={t('analytics.drill.salesLost')} value={fmt.moneyK(delivery.data?.current.lostSalesValue)} loading={!delivery.data} />
    </>
  ) : (
    <>
      <Kpi testId="drill-total" icon={Receipt} tone="bad" label={t('analytics.drill.totalExtra')} loading={!f}
        value={fmt.money(f ? f.failed.amount + f.exchange.amount + f.returned.amount : null)}
        foot={f ? t('analytics.drill.shipments', { count: f.failed.legs + f.exchange.legs + f.returned.legs, value: fmt.num(f.failed.legs + f.exchange.legs + f.returned.legs) }) : undefined} />
      <Kpi testId="drill-failed-fees" icon={AlertTriangle} tone="bad" label={t('analytics.drill.failedFees')} value={fmt.money(f?.failed.amount)} foot={f ? t('analytics.drill.legs', { count: f.failed.legs, value: fmt.num(f.failed.legs) }) : undefined} loading={!f} />
      <Kpi testId="drill-exchange" icon={Repeat} tone="bad" label={t('analytics.drill.exchangeFees')} value={fmt.money(f?.exchange.amount)} foot={f ? t('analytics.drill.legs', { count: f.exchange.legs, value: fmt.num(f.exchange.legs) }) : undefined} loading={!f} />
      <Kpi testId="drill-returns" icon={Repeat} tone="bad" label={t('analytics.drill.returnFees')} value={fmt.money(f?.returned.amount)} foot={f ? t('analytics.drill.legs', { count: f.returned.legs, value: fmt.num(f.returned.legs) }) : undefined} loading={!f} />
    </>
  )

  const skuRows = (rows: ExtraBySku[]) => {
    const list = rows.filter(r => (failedOnly ? r.failed > 0 : r.failed + r.exchanges + r.returns > 0))
      .sort((a, b) => (failedOnly ? b.failed - a.failed : b.extraFees - a.extraFees))
    if (list.length === 0) return <CardEmpty>{t('analytics.drill.none')}</CardEmpty>
    return (
      <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="drill-sku-table">
        <thead>
          <tr className="border-b border-line">
            <th className="tbl-header text-start ps-0">{t('analytics.summary.top.sku')}</th>
            <th className="tbl-header text-end">{t('analytics.drill.cols.failed')}</th>
            {!failedOnly && <th className="tbl-header text-end">{t('analytics.drill.cols.exchanges')}</th>}
            {!failedOnly && <th className="tbl-header text-end">{t('analytics.drill.cols.returns')}</th>}
            <th className="tbl-header text-end pe-0">{t(failedOnly ? 'analytics.drill.cols.estCost' : 'analytics.drill.cols.extra')}</th>
          </tr>
        </thead>
        <tbody>
          {list.map(r => (
            <tr key={r.variantId} className="border-b border-line last:border-0 cursor-pointer hover:bg-trace-blue/5" onClick={() => d.openSku(r.variantId)} data-testid="drill-sku-row">
              <td className="py-[9px] ps-0 pe-2.5"><div className="font-medium">{r.productTitle} <span className="text-muted">· {r.variantTitle}</span></div>{r.sku && <div className="text-[12px] text-muted font-mono">{r.sku}</div>}</td>
              <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.failed)}</td>
              {!failedOnly && <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.exchanges)}</td>}
              {!failedOnly && <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.returns)}</td>}
              <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums">{failedOnly ? fmt.money(avgFailed == null ? null : r.failed * avgFailed) : fmt.money(r.extraFees)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    )
  }

  const awbRows = (rows: ExtraByAwb[]) => {
    const list = rows.filter(r => !failedOnly || r.type === 'failed')
    if (list.length === 0) return <CardEmpty>{t('analytics.drill.none')}</CardEmpty>
    return (
      <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="drill-awb-table">
        <thead>
          <tr className="border-b border-line">
            <th className="tbl-header text-start ps-0">{t('analytics.drill.cols.awb')}</th>
            <th className="tbl-header text-start">{t('analytics.drill.cols.order')}</th>
            <th className="tbl-header text-start">{t('analytics.drill.cols.items')}</th>
            <th className="tbl-header text-start">{t('analytics.drill.cols.governorate')}</th>
            {!failedOnly && <th className="tbl-header text-start">{t('analytics.drill.cols.type')}</th>}
            <th className="tbl-header text-start">{t('analytics.drill.cols.reason')}</th>
            <th className="tbl-header text-end">{t('analytics.drill.cols.fee')}</th>
            <th className="tbl-header text-start pe-0">{t('analytics.drill.cols.date')}</th>
          </tr>
        </thead>
        <tbody>
          {list.map(r => (
            <tr key={r.trackingNumber} className="border-b border-line last:border-0" data-testid="drill-awb-row">
              <td className="py-[9px] ps-0 pe-2.5 font-mono text-[12px]">{r.trackingNumber}</td>
              <td className="py-[9px] px-2.5">{r.orderNumber ?? '—'}</td>
              <td className="py-[9px] px-2.5 font-mono text-[12px]">{r.skus.join(', ') || '—'}</td>
              <td className="py-[9px] px-2.5">{r.city ?? '—'}</td>
              {!failedOnly && <td className="py-[9px] px-2.5"><Pill kind={TYPE_PILL[r.type] ?? 'neutral'}>{t(`analytics.drill.type.${r.type}`, { defaultValue: r.type })}</Pill></td>}
              <td className="py-[9px] px-2.5">{r.reason ? label('reason', r.reason, r.reason) : '—'}</td>
              <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.money(r.fee)}{r.estimated && <span className="ms-1.5"><SourceChip>{t('analytics.chip.estimated')}</SourceChip></span>}</td>
              <td className="py-[9px] ps-2.5 pe-0">{fmt.day(r.date)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid={`drill-${kind}`}>
      <div className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(150px,1fr))]">{kpis}</div>
      {!failedOnly && f && (
        <Section title={t('analytics.drill.byType')}>
          <HBars color={CHART.red} rows={[
            { key: 'failed', label: t('analytics.drill.type.failed'), value: f.failed.amount, display: fmt.money(f.failed.amount) },
            { key: 'exchange', label: t('analytics.drill.type.exchange'), value: f.exchange.amount, display: fmt.money(f.exchange.amount) },
            { key: 'returned', label: t('analytics.drill.type.return'), value: f.returned.amount, display: fmt.money(f.returned.amount) },
          ]} />
        </Section>
      )}
      {failedOnly && (
        <Section title={t('analytics.drill.byReason')} desc={t('analytics.drill.byReasonDesc')}>
          <QueryBlock q={reasons} isEmpty={r => r.current.reasons.length === 0} empty={t('analytics.delivery.noFailures')}>
            {r => <HBars color={CHART.red} rows={r.current.reasons.map(x => ({
              key: x.reason, label: label('reason', x.reason, x.reason), value: x.count,
              display: avgFailed == null ? fmt.num(x.count) : fmt.money(x.count * avgFailed),
              title: t('analytics.delivery.reasonTip', { count: fmt.num(x.count), total: fmt.num(r.current.withReason) }),
            }))} />}
          </QueryBlock>
        </Section>
      )}
      <Section title={view === 'sku' ? t('analytics.drill.bySku') : t('analytics.drill.byAwb')} desc={view === 'sku' ? t('analytics.drill.clickSku') : undefined}
        right={
          <div role="group" aria-label={t('analytics.drill.groupBy')} className="inline-flex gap-0.5 p-0.5 border border-grey-100 rounded-lg bg-surface">
            {(['sku', 'awb'] as const).map(v => (
              <button key={v} type="button" aria-pressed={view === v} onClick={() => d.setView(v)}
                className={cn('px-3 py-[5px] rounded-md text-[13px] font-medium', view === v ? 'bg-primary text-white' : 'text-neutral-text')}>
                {t(`analytics.drill.view.${v}`)}
              </button>
            ))}
          </div>
        }>
        <div className="overflow-x-auto -mx-[18px] px-[18px]">
          <QueryBlock q={extra}>
            {x => view === 'sku' ? skuRows(x.skus ?? []) : awbRows(x.shipments ?? [])}
          </QueryBlock>
        </div>
        {extra.data && extra.data.estimatedCount > 0 && <Note>{t('analytics.drill.estimatedNote', { count: extra.data.estimatedCount })}</Note>}
      </Section>
    </div>
  )
}

export function DrillDrawer() {
  const { t } = useTranslation()
  const d = useDrawerParams()
  const kind = d.drill === 'failed' || d.drill === 'extra' ? d.drill : null
  // The SKU drawer opens on top of the drill-down: the drill-down hides while it's open.
  const open = kind != null && !d.sku
  return (
    <Drawer open={open} onClose={d.closeDrill} wide closeLabel={t('analytics.drawer.close')}
      title={kind === 'failed' ? t('analytics.drill.failedTitle') : t('analytics.drill.extraTitle')}
      subtitle={kind === 'failed' ? t('analytics.drill.failedSub') : t('analytics.drill.extraSub')}>
      {kind && <DrillBody kind={kind} view={d.view} />}
    </Drawer>
  )
}

// ── Pieces moved 4+ times, and one piece ───────────────────────────────────

function PiecesBody() {
  const { t } = useTranslation()
  const fmt = useFmt()
  const d = useDrawerParams()
  const q = useAnalyticsQuery('trip-pieces:4', sig => getTripPieces({ minTrips: 4, limit: 100 }, sig))
  return (
    <div data-testid="pieces-drawer">
      <QueryBlock q={q} isEmpty={x => x.total === 0} empty={t('analytics.pieces.none')}>
        {x => (
          <>
            <p className="text-[12.5px] text-muted mb-3">{t('analytics.pieces.desc', { count: x.total, total: fmt.num(x.total), shown: fmt.num(x.pieces.length) })}</p>
            <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="pieces-table">
              <thead>
                <tr className="border-b border-line">
                  <th className="tbl-header text-start ps-0">{t('analytics.pieces.cols.piece')}</th>
                  <th className="tbl-header text-end">{t('analytics.pieces.cols.trips')}</th>
                  <th className="tbl-header text-start">{t('analytics.pieces.cols.now')}</th>
                  <th className="tbl-header text-start pe-0">{t('analytics.pieces.cols.last')}</th>
                </tr>
              </thead>
              <tbody>
                {x.pieces.map(p => (
                  <tr key={p.pieceId} className="border-b border-line last:border-0 cursor-pointer hover:bg-trace-blue/5" onClick={() => d.openPiece(p.pieceId)} data-testid="pieces-row">
                    <td className="py-[9px] ps-0 pe-2.5"><div className="font-medium">{p.productTitle} <span className="text-muted">· {p.variantTitle}</span></div><div className="text-[12px] text-muted font-mono">{p.shortCode ?? p.barcode}</div></td>
                    <td className="py-[9px] px-2.5 text-end"><Pill kind={p.trips >= 5 ? 'crit' : 'warn'}>{t('analytics.pieces.trips', { count: p.trips, value: fmt.num(p.trips) })}</Pill></td>
                    <td className="py-[9px] px-2.5">{t(`analytics.pieces.status.${p.status}`, { defaultValue: p.status })}{p.location ? ` · ${p.location}` : ''}</td>
                    <td className="py-[9px] ps-2.5 pe-0">{fmt.day(p.lastTripAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Note>{t('analytics.pieces.note')}</Note>
          </>
        )}
      </QueryBlock>
    </div>
  )
}

export function PiecesDrawer() {
  const { t } = useTranslation()
  const d = useDrawerParams()
  return (
    <Drawer open={d.pieces && !d.piece && !d.sku} onClose={d.closePieces} title={t('analytics.pieces.title')} closeLabel={t('analytics.drawer.close')}>
      {d.pieces && <PiecesBody />}
    </Drawer>
  )
}

function PieceBody({ id }: { id: string }) {
  const { t } = useTranslation()
  const d = useDrawerParams()
  const h = useAnalyticsQuery(`piece-history:${id}`, sig => getPieceHistory(id, sig))
  return (
    <div className="flex flex-col gap-3" data-testid="piece-drawer">
      <QueryBlock q={h}>
        {x => (
          <>
            <div>
              <span className="font-mono text-[12px] text-muted">{x.shortCode ?? x.barcode}</span>
              <p className="text-[18px] font-semibold text-primary">{x.productTitle} · {x.variantTitle}</p>
              <p className="text-[12.5px] text-muted">{t(`analytics.pieces.status.${x.status}`, { defaultValue: x.status })}{x.location ? ` · ${x.location}` : ''}</p>
            </div>
            <Section title={t('analytics.pieces.history')}><PieceTimeline h={x} /></Section>
            <button type="button" onClick={() => d.pieceToSku(x.variantId)} className="self-start text-[12.5px] font-semibold text-trace-blue hover:underline">{t('analytics.pieces.openSku')}</button>
          </>
        )}
      </QueryBlock>
    </div>
  )
}

export function PieceDrawer() {
  const { t } = useTranslation()
  const d = useDrawerParams()
  return (
    <Drawer open={!!d.piece && !d.sku} onClose={d.closePiece} title={t('analytics.pieces.pieceTitle')} closeLabel={t('analytics.drawer.close')}>
      {d.piece && <PieceBody id={d.piece} />}
    </Drawer>
  )
}

/** Used by tests and pages: the drawers the analytics route mounts once. */
export function AnalyticsDrawers() {
  return <><DrillDrawer /><PiecesDrawer /><PieceDrawer /><SkuDrawer /></>
}
