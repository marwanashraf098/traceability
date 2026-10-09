import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Box, Star, AlertTriangle } from 'lucide-react'
import {
  getAnalyticsSettings, getProductExtras, getProfitSkus, getRestock, getSalesVariants, getStockVariants, saveAnalyticsSettings,
  type SkuProfit, type VariantSales, type VariantStock, type AbcRow,
} from '../../analyticsApi'
import { clearAnalyticsCache, useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { cn, DataTable, type DataTableSort } from '../../components/ui'
import {
  AnalyticsCard, AnalyticsGrid, Banner, Kpi, KpiRow, Note, Pill, QueryBlock, SourceChip,
} from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { ColumnBars } from '../../components/analytics/charts/ColumnBars'
import { CostNote, useInventorySync } from './shared'
import { useDrawerParams } from './drawers'

type Row = {
  id: string; sale: VariantSales; prof?: SkuProfit; stock?: VariantStock; abc?: AbcRow
}

/** Rate cells turn amber / red past these (the mockup's thresholds). */
const RATE = { returns: [0.08, 0.15], failed: [0.15, 0.2] } as const

function RateCell({ r, kind }: { r: number | null | undefined; kind: keyof typeof RATE }) {
  const fmt = useFmt()
  if (r == null) return <span className="text-muted">—</span>
  const [warn, crit] = RATE[kind]
  return r >= crit ? <Pill kind="crit">{fmt.pct(r)}</Pill> : r >= warn ? <Pill kind="warn">{fmt.pct(r)}</Pill> : <>{fmt.pct(r)}</>
}

function RestockSettings({ lead, cover }: { lead: number; cover: number }) {
  const { t } = useTranslation()
  const [l, setL] = useState(String(lead))
  const [c, setC] = useState(String(cover))
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const valid = /^\d+$/.test(l) && /^\d+$/.test(c) && Number(l) >= 1 && Number(l) <= 365 && Number(c) >= 1 && Number(c) <= 365
  async function save() {
    setState('saving')
    try {
      await saveAnalyticsSettings({ supplierLeadDays: Number(l), coverDays: Number(c) })
      clearAnalyticsCache() // restock suggestions depend on these
      setState('saved')
    } catch { setState('error') }
  }
  return (
    <form className="flex flex-wrap items-end gap-2.5" onSubmit={e => { e.preventDefault(); if (valid) save() }} data-testid="restock-settings">
      <label className="flex flex-col gap-1 text-[12px] text-muted">{t('analytics.products.restock.lead')}
        <input className="input py-1 text-small w-24" inputMode="numeric" value={l} onChange={e => { setL(e.target.value); setState('idle') }} />
      </label>
      <label className="flex flex-col gap-1 text-[12px] text-muted">{t('analytics.products.restock.cover')}
        <input className="input py-1 text-small w-24" inputMode="numeric" value={c} onChange={e => { setC(e.target.value); setState('idle') }} />
      </label>
      <button type="submit" disabled={!valid || state === 'saving'} className="h-[32px] px-3 rounded-lg bg-primary text-white text-small disabled:opacity-40">{t('analytics.products.restock.save')}</button>
      {state === 'saved' && <span className="text-[12px] text-success-text" role="status">{t('analytics.products.restock.saved')}</span>}
      {state === 'error' && <span className="text-[12px] text-critical-text" role="alert">{t('analytics.products.restock.error')}</span>}
      {!valid && <span className="text-[12px] text-critical-text w-full">{t('analytics.products.restock.invalid')}</span>}
    </form>
  )
}

export default function ProductsPage() {
  const { t } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const drawers = useDrawerParams()
  const sync = useInventorySync()
  const pk = JSON.stringify(s.params)
  const sales = useAnalyticsQuery(`sales-variants:${pk}`, sig => getSalesVariants(s.params, sig))
  const profit = useAnalyticsQuery(`profit-skus:500:${pk}`, sig => getProfitSkus({ ...s.params, limit: 500 }, sig))
  const stock = useAnalyticsQuery('stock-variants:500', sig => getStockVariants({ limit: 500 }, sig))
  const extras = useAnalyticsQuery(`product-extras:${JSON.stringify({ ...s.params, c: false })}`, sig => getProductExtras(s.params, sig))
  const restock = useAnalyticsQuery('stock-restock', sig => getRestock(sig))
  const settings = useAnalyticsQuery('analytics-settings', sig => getAnalyticsSettings(sig))
  const [sort, setSort] = useState<DataTableSort>({ key: 'revenue', dir: 'desc' })

  const hasPieces = stock.data?.hasPieces ?? false
  const costed = (profit.data?.coverage.variantsCosted ?? 0) > 0
  const rows = useMemo<Row[]>(() => {
    const prof = new Map((profit.data?.skus ?? []).map(p => [p.variantId, p]))
    const st = new Map((stock.data?.hasPieces ? stock.data.variants : []).map(v => [v.variantId, v]))
    const abc = new Map((extras.data?.current.abc ?? []).map(a => [a.variantId, a]))
    const list = (sales.data?.variants ?? []).map(v => ({ id: v.variantId, sale: v, prof: prof.get(v.variantId), stock: st.get(v.variantId), abc: abc.get(v.variantId) }))
    const val = (r: Row): number | string | null => {
      switch (sort.key) {
        case 'sku': return `${r.sale.productTitle} ${r.sale.variantTitle}`
        case 'units': return r.sale.soldUnits
        case 'revenue': return s.mode === 'realized' ? r.sale.netRevenue : r.sale.grossRevenue
        case 'net': return r.prof?.trueNet ?? null
        case 'st': return r.stock?.sellThrough ?? null
        case 'vel': return r.stock?.velocityPerDay ?? null
        case 'days': return r.stock?.daysOfCover ?? null
        case 'age': return r.stock?.avgPieceAgeDays ?? null
        case 'ret': return r.sale.returnRate
        case 'exch': return r.stock?.exchangedUnits90 ?? null
        case 'fail': return r.sale.refusalRate
        case 'abc': return r.abc?.abcClass ?? null
        default: return null
      }
    }
    const dir = sort.dir === 'asc' ? 1 : -1
    return list.sort((a, b) => {
      const A = val(a), B = val(b)
      if (A == null && B == null) return a.id.localeCompare(b.id)
      if (A == null) return 1
      if (B == null) return -1
      return (A < B ? -1 : A > B ? 1 : 0) * dir || a.id.localeCompare(b.id)
    })
  }, [sales.data, profit.data, stock.data, extras.data, sort, s.mode])

  const costedSkus = (profit.data?.skus ?? []).filter(p => p.costed && p.trueNet != null)
  const best = costedSkus.length ? costedSkus.reduce((a, b) => (b.trueNet! > a.trueNet! ? b : a)) : null
  const losing = costedSkus.filter(p => p.trueNet! < 0)
  const noSaleIn60 = stock.data?.hasPieces ? stock.data.variants.filter(v => v.deadStock).length : null
  const source = stock.data?.stockSource

  return (
    <div data-testid="analytics-products" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.products.title')} subtitle={t('analytics.pages.products.subtitle')} showMode />
      <AnalyticsGrid>
        {stock.data?.hasPieces && stock.data.trustLevel === 'low' && (
          <Banner tone="warn" testId="low-trust-banner">{t('analytics.stock.lowTrustShort')}</Banner>
        )}
        <KpiRow>
          <Kpi testId="kpi-active" icon={Box} tone="blue" label={t('analytics.products.kpi.active')} loading={!sales.data}
            value={fmt.num(sales.data?.variants.length)}
            foot={noSaleIn60 != null ? t('analytics.products.kpi.activeFoot', { count: noSaleIn60, dead: fmt.num(noSaleIn60) }) : t('analytics.products.kpi.soldInPeriod')} />
          <Kpi testId="kpi-units" icon={Box} tone="blue" label={t('analytics.products.kpi.units')} loading={!sales.data}
            value={fmt.num(sales.data?.totals.soldUnits)} foot={t('analytics.products.kpi.unitsFoot', { net: fmt.num(sales.data?.totals.netSoldUnits) })} />
          <Kpi testId="kpi-best" icon={Star} tone={best ? 'good' : 'grey'} label={t('analytics.products.kpi.best')} loading={!profit.data}
            value={best ? <span className="text-[17px] leading-snug">{best.productTitle} · {best.variantTitle}</span> : '—'}
            foot={best ? t('analytics.products.kpi.bestFoot', { value: fmt.money(best.trueNet) }) : <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="best-cost-note" />} />
          <Kpi testId="kpi-losing" icon={AlertTriangle} tone={losing.length ? 'bad' : 'grey'} label={t('analytics.products.kpi.losing')} loading={!profit.data}
            value={costed ? fmt.num(losing.length) : '—'}
            foot={costed ? t('analytics.products.kpi.losingFoot') : <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="losing-cost-note" />} />
        </KpiRow>

        <AnalyticsCard span="s12" title={t('analytics.products.all')} desc={t('analytics.products.allDesc')} testId="card-all-skus"
          right={source === 'shopify' && hasPieces ? <SourceChip>{t('analytics.products.stockFrom', { source: t('analytics.stock.sourceShopify') })}</SourceChip> : undefined}>
          <QueryBlock q={sales} lines={8} isEmpty={d => d.variants.length === 0} empty={t('analytics.state.noSales')}>
            {() => (
              <>
                <div className="-mx-[18px] px-[18px] [&_td]:whitespace-nowrap [&_td]:px-2.5 [&_th]:px-2.5" data-testid="sku-table">
                  <DataTable<Row>
                    rows={rows}
                    sort={sort}
                    onSort={k => setSort(prev => ({ key: k, dir: prev.key === k && prev.dir === 'desc' ? 'asc' : 'desc' }))}
                    onRowClick={r => drawers.openSku(r.id)}
                    columns={[
                      { key: 'sku', header: t('analytics.summary.top.sku'), sortable: true, render: r => (
                        <div><div className="font-medium text-primary">{r.sale.productTitle} <span className="text-muted">· {r.sale.variantTitle}</span></div>{r.sale.sku && <div className="text-[12px] text-muted font-mono">{r.sale.sku}</div>}</div>
                      ) },
                      { key: 'units', header: t('analytics.products.cols.units'), sortable: true, align: 'end', render: r => fmt.num(r.sale.soldUnits) },
                      { key: 'revenue', header: t(`analytics.mode.${s.mode}`), sortable: true, align: 'end', render: r => fmt.money(s.mode === 'realized' ? r.sale.netRevenue : r.sale.grossRevenue) },
                      { key: 'net', header: t('analytics.sku.trueNet'), sortable: true, align: 'end', render: r => r.prof?.trueNet == null
                        ? <span className="text-muted" title={t('analytics.cost.missing')}>—</span>
                        : r.prof.trueNet < 0 ? <Pill kind="crit">{fmt.money(r.prof.trueNet)}</Pill> : <span className="text-success-text font-semibold">{fmt.money(r.prof.trueNet)}</span> },
                      ...(hasPieces ? [
                        { key: 'st', header: t('analytics.products.cols.sellThrough'), sortable: true, align: 'end' as const, render: (r: Row) => fmt.pct(r.stock?.sellThrough, 0) },
                        { key: 'vel', header: t('analytics.products.cols.perDay'), sortable: true, align: 'end' as const, render: (r: Row) => fmt.num(r.stock?.velocityPerDay, 1) },
                        { key: 'days', header: t('analytics.products.cols.stockLeft'), sortable: true, align: 'end' as const, render: (r: Row) => {
                          const d = r.stock?.daysOfCover
                          if (d == null) return r.stock ? t('analytics.summary.top.unitsLeft', { count: r.stock.stockUsed }) : '—'
                          const txt = t('analytics.summary.top.days', { count: Math.round(d) })
                          return d < 7 ? <Pill kind="warn">{txt}</Pill> : d > 120 ? <Pill kind="neutral">{txt}</Pill> : txt
                        } },
                        { key: 'age', header: t('analytics.products.cols.age'), sortable: true, align: 'end' as const, render: (r: Row) => r.stock?.avgPieceAgeDays == null ? '—' : t('analytics.products.days', { value: fmt.num(r.stock.avgPieceAgeDays, 0) }) },
                      ] : []),
                      { key: 'ret', header: t('analytics.products.cols.returns'), sortable: true, align: 'end', render: r => <RateCell r={r.sale.returnRate} kind="returns" /> },
                      ...(hasPieces ? [{ key: 'exch', header: t('analytics.products.cols.exchanges'), sortable: true, align: 'end' as const, render: (r: Row) => fmt.num(r.stock?.exchangedUnits90) }] : []),
                      { key: 'fail', header: t('analytics.products.cols.failed'), sortable: true, align: 'end', render: r => <RateCell r={r.sale.refusalRate} kind="failed" /> },
                      { key: 'abc', header: t('analytics.products.cols.abc'), sortable: true, align: 'center', render: r => r.abc
                        ? <span className={cn('inline-grid place-items-center w-[22px] h-[22px] rounded-md font-bold text-[12px]', r.abc.abcClass === 'A' ? 'bg-trace-blue/10 text-trace-blue' : 'bg-elevated text-neutral-text')}>{r.abc.abcClass}</span>
                        : <span className="text-muted">—</span> },
                    ]}
                  />
                </div>
                <Note>{t('analytics.products.allNote')}</Note>
                {stock.data && !hasPieces && <Note>{t('analytics.products.noPiecesColumns')}</Note>}
                {!costed && <CostNote coverage={profit.data?.coverage} sync={sync.data} testId="table-cost-note" />}
              </>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.products.sizeCurve')} desc={t('analytics.products.sizeCurveDesc')} testId="card-size-curve">
          <QueryBlock q={extras} isEmpty={d => d.current.sizeCurve.sizes.length === 0} empty={t('analytics.products.noSizes')}>
            {d => {
              const sc = d.current.sizeCurve
              return (
                <>
                  <ColumnBars testId="size-curve" columns={sc.sizes.map(z => ({
                    key: z.size, label: z.size, value: z.soldUnits, display: fmt.num(z.soldUnits),
                    title: t('analytics.products.sizeTip', { units: fmt.num(z.soldUnits), returns: fmt.pct(z.returnRate), exchanged: fmt.num(z.exchangedUnits) }),
                  }))} />
                  {(sc.unparseableUnits > 0 || sc.noSizeUnits > 0) && (
                    <Note>{t('analytics.products.sizeOther', { unparsed: fmt.num(sc.unparseableUnits), noSize: fmt.num(sc.noSizeUnits) })}</Note>
                  )}
                </>
              )
            }}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.products.restock.title')} testId="card-restock"
          desc={restock.data ? t('analytics.products.restock.desc', { cover: restock.data.coverDays, lead: restock.data.supplierLeadDays }) : undefined}
          right={restock.data?.stockSource === 'shopify' ? <SourceChip>{t('analytics.stock.sourceShopify')}</SourceChip> : undefined}>
          {settings.data && <RestockSettings key={`${settings.data.supplierLeadDays}-${settings.data.coverDays}`} lead={settings.data.supplierLeadDays} cover={settings.data.coverDays} />}
          <QueryBlock q={restock} isEmpty={d => !d.hasPieces || d.items.length === 0} empty={restock.data && !restock.data.hasPieces ? t('analytics.stock.noPieces') : t('analytics.products.restock.none')}>
            {d => (
              <table className="w-full text-[13px] [&_td]:whitespace-nowrap" data-testid="restock-table">
                <thead>
                  <tr className="border-b border-line">
                    <th className="tbl-header text-start ps-0">{t('analytics.summary.top.sku')}</th>
                    <th className="tbl-header text-end">{t('analytics.products.restock.onHand')}</th>
                    <th className="tbl-header text-end">{t('analytics.products.cols.perDay')}</th>
                    <th className="tbl-header text-end pe-0">{t('analytics.products.restock.reorder')}</th>
                  </tr>
                </thead>
                <tbody>
                  {d.items.map(r => (
                    <tr key={r.variantId} className="border-b border-line last:border-0 cursor-pointer hover:bg-trace-blue/5" onClick={() => drawers.openSku(r.variantId)}>
                      <td className="py-[9px] ps-0 pe-2.5"><div className="font-medium">{r.productTitle}</div><div className="text-[12px] text-muted">{r.variantTitle}</div></td>
                      <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.onHand)}{r.comingBack > 0 && <span className="text-muted"> +{fmt.num(r.comingBack)}</span>}</td>
                      <td className="py-[9px] px-2.5 text-end tabular-nums">{fmt.num(r.velocityPerDay, 1)}</td>
                      <td className="py-[9px] ps-2.5 pe-0 text-end"><b>{fmt.num(r.suggestedUnits)}</b>{r.costAtUnitCost != null && <div className="text-[12px] text-muted">{fmt.money(r.costAtUnitCost)}</div>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </QueryBlock>
        </AnalyticsCard>

        <AnalyticsCard span="s6" title={t('analytics.products.together')} desc={t('analytics.products.togetherDesc')} testId="card-together">
          <QueryBlock q={extras} isEmpty={d => d.current.boughtTogether.length === 0} empty={t('analytics.products.togetherNone')}>
            {d => (
              <div className="flex flex-col">
                {d.current.boughtTogether.map(p => (
                  <div key={`${p.variantA}-${p.variantB}`} className="flex justify-between items-center gap-3 py-[9px] border-b border-line last:border-0 text-[13px]">
                    <div className="min-w-0"><div className="font-medium truncate">{p.titleA} + {p.titleB}</div>
                      <div className="text-[12px] text-muted">{t('analytics.products.togetherOrders', { count: p.orders, value: fmt.num(p.orders) })}</div></div>
                    <Pill kind="neutral">{t('analytics.products.bundleIdea')}</Pill>
                  </div>
                ))}
              </div>
            )}
          </QueryBlock>
        </AnalyticsCard>

      </AnalyticsGrid>
    </div>
  )
}
