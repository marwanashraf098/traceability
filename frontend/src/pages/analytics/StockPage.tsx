import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Box, Banknote, Clock, Repeat } from 'lucide-react'
import { getProductExtras, getStockSummary, getStockVariants, type VariantStock } from '../../analyticsApi'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import {
  AnalyticsCard, AnalyticsGrid, Banner, CardEmpty, Kpi, KpiRow, Note, Pill, QueryBlock, SourceChip,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { ColumnBars } from '../../components/analytics/charts/ColumnBars'
import { HBars } from '../../components/analytics/charts/HBars'
import { CHART } from '../../components/analytics/charts/chartUtils'
import { useDrawerParams } from './drawers'

const AGE_COLOR: Record<string, string> = { '0-30': CHART.realized, '31-60': CHART.amber, '61-90': CHART.amber, '90+': CHART.red }
/** Most exchanged / returned: only SKUs with enough sales for a rate to mean something. */
const MIN_SOLD_FOR_RETURNS = 20

function SkuCell({ v }: { v: VariantStock }) {
  return <><div className="font-medium text-primary">{v.productTitle}</div><div className="text-[12px] text-muted">{v.variantTitle}</div></>
}

export default function StockPage() {
  const { t } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const drawers = useDrawerParams()
  const pk = JSON.stringify(s.params)
  const summary = useAnalyticsQuery(`stock-summary:${pk}`, sig => getStockSummary(s.params, sig))
  const all = useAnalyticsQuery('stock-variants:500', sig => getStockVariants({ limit: 500 }, sig))
  const low = useAnalyticsQuery('stock-variants:running_low', sig => getStockVariants({ filter: 'running_low', sort: 'daysOfCover', limit: 50 }, sig))
  const dead = useAnalyticsQuery('stock-variants:dead_stock', sig => getStockVariants({ filter: 'dead_stock', sort: 'cash', limit: 50 }, sig))
  const extras = useAnalyticsQuery(`product-extras:${JSON.stringify({ ...s.params, c: false })}`, sig => getProductExtras(s.params, sig))

  const sum = summary.data
  const shopify = all.data?.stockSource === 'shopify'
  const srcChip = shopify ? <SourceChip>{t('analytics.stock.sourceShopify')}</SourceChip> : undefined

  if (sum && !sum.hasPieces) {
    return (
      <div data-testid="analytics-stock" className="max-w-[1400px]">
        <AnalyticsPageHeader title={t('analytics.pages.stock.title')} subtitle={t('analytics.pages.stock.subtitle')} />
        <AnalyticsGrid>
          <div className="col-span-full card p-[18px] flex flex-col gap-1.5" data-testid="no-pieces">
            <span className="text-[11.5px] uppercase tracking-[0.07em] font-semibold text-muted">{t('analytics.stock.noPiecesTitle')}</span>
            <span className="text-[13px] text-neutral-text">{t('analytics.stock.noPieces')}</span>
            <Link to="/receiving" className="text-[12.5px] font-semibold text-trace-blue hover:underline self-start">{t('analytics.stock.toReceiving')}</Link>
          </div>
        </AnalyticsGrid>
      </div>
    )
  }

  return (
    <div data-testid="analytics-stock" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.stock.title')} subtitle={t('analytics.pages.stock.subtitle')} />
      <AnalyticsGrid>
        {sum?.lowTrust && (
          <Banner tone="warn" testId="low-trust-banner">{t('analytics.stock.lowTrust', { pct: fmt.pct(sum.trust.packedThroughTracedPct, 0) })}</Banner>
        )}
        <KpiRow>
          <Kpi testId="kpi-pieces" icon={Box} tone="blue" label={t('analytics.stock.kpi.pieces')} loading={!sum}
            value={fmt.num(sum?.inWarehouse)} foot={sum ? t('analytics.stock.kpi.piecesFoot', { count: sum.variantsInStock, skus: fmt.num(sum.variantsInStock) }) : undefined} />
          <Kpi testId="kpi-value-price" icon={Banknote} tone="blue" label={t('analytics.stock.kpi.valuePrice')} loading={!sum}
            value={fmt.moneyK(sum?.valueAtPrice)} foot={t('analytics.stock.kpi.valuePriceFoot')} />
          <Kpi testId="kpi-value-cost" icon={Banknote} tone={sum?.valueAtCost != null ? 'warn' : 'grey'} label={t('analytics.stock.kpi.valueCost')} loading={!sum}
            value={sum?.valueAtCost == null ? '—' : fmt.moneyK(sum.valueAtCost)}
            foot={sum ? (sum.costedVariants === 0
              ? t('analytics.cost.missing')
              : t('analytics.stock.kpi.costCoverage', { costed: fmt.num(sum.costedVariants), total: fmt.num(sum.variantsInStock) })) : undefined} />
          <Kpi testId="kpi-age" icon={Clock} tone="warn" label={t('analytics.stock.kpi.age')} loading={!sum}
            value={sum?.avgDaysInStock == null ? '—' : t('analytics.kpi.days', { count: Math.round(sum.avgDaysInStock), value: fmt.num(sum.avgDaysInStock, 0) })}
            foot={t('analytics.stock.kpi.ageFoot')} />
          <Kpi testId="kpi-moved" icon={Repeat} tone={sum && sum.piecesMovedFourPlus > 0 ? 'bad' : 'grey'} label={t('analytics.stock.kpi.moved')} loading={!sum}
            value={fmt.num(sum?.piecesMovedFourPlus)}
            foot={sum && sum.piecesMovedFourPlus > 0
              ? <button type="button" onClick={drawers.openPieces} className="text-trace-blue font-semibold hover:underline">{t('analytics.stock.kpi.seePieces')}</button>
              : t('analytics.stock.kpi.movedFoot')} />
        </KpiRow>

        <AnalyticsCard span="s6" title={t('analytics.stock.ageTitle')} desc={t('analytics.stock.ageDesc')} testId="card-age">
          <QueryBlock q={summary} isEmpty={d => d.ageBuckets.every(b => b.pieces === 0)} empty={t('analytics.stock.empty')}>
            {d => {
              const old = d.ageBuckets.find(b => b.key === '90+')
              return (
                <>
                  <ColumnBars testId="age-buckets" columns={d.ageBuckets.map(b => ({
                    key: b.key, label: t(`analytics.stock.age.${b.key}`, { defaultValue: b.key }), value: b.pieces, display: fmt.num(b.pieces),
                    color: AGE_COLOR[b.key] ?? CHART.blue, title: t('analytics.stock.ageTip', { pieces: fmt.num(b.pieces), value: fmt.money(b.valueAtPrice) }),
                  }))} />
                  <div className="flex flex-wrap gap-x-3.5 gap-y-1.5 text-[12px] text-neutral-text">
                    {(['fresh', 'slowing', 'stuck'] as const).map((k, i) => (
                      <span key={k} className="inline-flex items-center gap-1.5"><i className="w-2.5 h-2.5 rounded-[3px] inline-block" style={{ background: [CHART.realized, CHART.amber, CHART.red][i] }} />{t(`analytics.stock.ageLegend.${k}`)}</span>
                    ))}
                  </div>
                  {old && old.pieces > 0 && <Note>{t('analytics.stock.ageNote', { count: old.pieces, pieces: fmt.num(old.pieces), value: fmt.money(old.valueAtPrice) })}</Note>}
                </>
              )
            }}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.stock.low')} desc={t('analytics.stock.lowDesc')} testId="card-low" right={srcChip}>
          <QueryBlock q={low} isEmpty={d => d.variants.length === 0} empty={t('analytics.stock.lowNone')}>
            {d => (
              <div className="flex flex-col">
                {d.variants.map(v => (
                  <button key={v.variantId} type="button" onClick={() => drawers.openSku(v.variantId)} data-testid="low-row"
                    className="flex justify-between items-center gap-3 py-[9px] border-b border-line last:border-0 text-[13px] text-start hover:bg-trace-blue/5">
                    <div className="min-w-0"><div className="font-medium truncate">{v.productTitle} · {v.variantTitle}</div>
                      <div className="text-[12px] text-muted">{t('analytics.stock.lowMeta', { left: fmt.num(v.stockUsed), pace: fmt.num(v.velocityPerDay, 1) })}</div></div>
                    <Pill kind={(v.daysOfCover ?? 0) < 3 ? 'crit' : 'warn'}>{t('analytics.stock.daysLeft', { value: fmt.num(v.daysOfCover, 1) })}</Pill>
                  </button>
                ))}
              </div>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.stock.best')} desc={t('analytics.stock.bestDesc')} testId="card-best" right={srcChip}>
          <QueryBlock q={all} isEmpty={d => d.variants.filter(v => v.soldUnits30 > 0).length === 0} empty={t('analytics.state.noSales')}>
            {d => (
              <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="best-table">
                <thead>
                  <tr className="border-b border-line">
                    <th className="tbl-header text-start ps-0">{t('analytics.summary.top.sku')}</th>
                    <th className="tbl-header text-end">{t('analytics.stock.cols.sold')}</th>
                    <th className="tbl-header text-end">{t('analytics.stock.cols.inStock')}</th>
                    <th className="tbl-header text-end">{t('analytics.stock.cols.daysLeft')}</th>
                    <th className="tbl-header text-end pe-0">{t('analytics.products.cols.age')}</th>
                  </tr>
                </thead>
                <tbody>
                  {[...d.variants].filter(v => v.soldUnits30 > 0).sort((a, b) => b.soldUnits30 - a.soldUnits30 || a.variantId.localeCompare(b.variantId)).slice(0, 6).map(v => (
                    <tr key={v.variantId} className="border-b border-line last:border-0 cursor-pointer hover:bg-trace-blue/5" onClick={() => drawers.openSku(v.variantId)}>
                      <td className="py-[9px] ps-0 pe-2.5"><SkuCell v={v} /></td>
                      <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(v.soldUnits30)}</td>
                      <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(v.stockUsed)}</td>
                      <td className="py-[9px] px-2.5 text-end">{v.daysOfCover == null ? '—' : v.daysOfCover < 7
                        ? <Pill kind="warn">{t('analytics.summary.top.days', { count: Math.round(v.daysOfCover) })}</Pill>
                        : t('analytics.summary.top.days', { count: Math.round(v.daysOfCover) })}</td>
                      <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums">{v.avgPieceAgeDays == null ? '—' : t('analytics.products.days', { value: fmt.num(v.avgPieceAgeDays, 0) })}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.stock.returned')} desc={t('analytics.stock.returnedDesc', { min: MIN_SOLD_FOR_RETURNS })} testId="card-returned">
          <QueryBlock q={all}>
            {d => {
              const rows = d.variants.filter(v => v.soldUnits30 >= MIN_SOLD_FOR_RETURNS && v.returnedUnits90 + v.exchangedUnits90 > 0)
                .sort((a, b) => (b.returnedUnits90 + b.exchangedUnits90) - (a.returnedUnits90 + a.exchangedUnits90)).slice(0, 6)
              if (rows.length === 0) return <CardEmpty>{t('analytics.sku.noReturns')}</CardEmpty>
              return <HBars color={CHART.red} rows={rows.map(v => ({
                key: v.variantId, label: `${v.productTitle} · ${v.variantTitle}`, value: v.returnedUnits90 + v.exchangedUnits90,
                display: t('analytics.stock.returnedUnits', { returns: fmt.num(v.returnedUnits90), exchanges: fmt.num(v.exchangedUnits90) }),
                title: v.topReturnReason ? t('analytics.sku.topReason', { reason: v.topReturnReason }) : undefined,
                extra: v.topReturnReason ? <span className="text-[12px] text-muted max-w-[140px] truncate">{v.topReturnReason}</span> : undefined,
              }))} />
            }}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.stock.dead')} desc={t('analytics.stock.deadDesc')} testId="card-dead">
          <QueryBlock q={dead} isEmpty={d => d.variants.length === 0} empty={t('analytics.stock.deadNone')}>
            {d => (
              <>
                <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="dead-table">
                  <thead>
                    <tr className="border-b border-line">
                      <th className="tbl-header text-start ps-0">{t('analytics.summary.top.sku')}</th>
                      <th className="tbl-header text-end">{t('analytics.products.restock.onHand')}</th>
                      <th className="tbl-header text-end">{t('analytics.stock.cols.lastSale')}</th>
                      <th className="tbl-header text-end pe-0">{t('analytics.stock.cols.cash')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.variants.map(v => (
                      <tr key={v.variantId} className="border-b border-line last:border-0 cursor-pointer hover:bg-trace-blue/5" onClick={() => drawers.openSku(v.variantId)}>
                        <td className="py-[9px] ps-0 pe-2.5"><SkuCell v={v} /></td>
                        <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(v.stockUsed)}</td>
                        <td className="py-[9px] px-2.5 text-end">{v.lastSoldAt ? fmt.day(v.lastSoldAt) : t('analytics.stock.never')}</td>
                        <td className="py-[9px] ps-2.5 pe-0 text-end tabular-nums">
                          {fmt.money(v.stockValue)} <span className="text-[11px] text-muted">{v.valueAtCost ? t('analytics.stock.atCost') : t('analytics.stock.atPrice')}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Note>{t('analytics.stock.deadNote')}</Note>
              </>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.stock.bySize')} desc={t('analytics.stock.bySizeDesc')} testId="card-by-size">
          <QueryBlock q={extras} isEmpty={d => d.current.sizeCurve.sizes.every(z => z.returnRate == null)} empty={t('analytics.sku.noReturns')}>
            {d => {
              const sizes = d.current.sizeCurve.sizes.filter(z => z.returnRate != null)
              const avg = sizes.reduce((a, z) => a + (z.returnRate ?? 0), 0) / Math.max(1, sizes.length)
              return <HBars rows={sizes.map(z => ({
                key: z.size, label: z.size, value: z.returnRate ?? 0, display: fmt.pct(z.returnRate),
                color: (z.returnRate ?? 0) > avg * 1.5 ? CHART.red : CHART.grey,
                title: t('analytics.stock.sizeTip', { returned: fmt.num(z.returnedUnits), delivered: fmt.num(z.deliveredUnits) }),
              }))} />
            }}
          </QueryBlock>
        </AnalyticsCard>
      </AnalyticsGrid>
    </div>
  )
}
