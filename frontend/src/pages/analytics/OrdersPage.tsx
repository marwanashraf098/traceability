import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Download } from 'lucide-react'
import { getAnalyticsOrders, ordersExportPath, type OrderRow, type OrderSortKey } from '../../analyticsApi'
import { getAccessToken } from '../../auth'
import { useAnalyticsQuery } from '../../analytics/useAnalyticsQuery'
import { useAnalyticsState } from '../../analytics/period'
import { useFmt } from '../../analytics/format'
import { cn, DataTable, type DataTableSort } from '../../components/ui'
import { AnalyticsCard, AnalyticsGrid, Note, Pill, QueryBlock, SourceChip } from '../../components/analytics/ui'
import { AnalyticsPageHeader } from '../../components/analytics/PageHeader'
import { OtherCarrierBanner, useGroupLabel } from './shared'
import { FIN_PILL } from './drawers'

const PAGE_SIZE = 50
/** Table column → the API's sort key (the API sorts across every page). */
const SORT_KEY: Record<string, OrderSortKey> = { date: 'placedAt', total: 'total', fees: 'bostaFees', net: 'netToYou', fin: 'status' }
const COLUMN_OF: Record<string, string> = Object.fromEntries(Object.entries(SORT_KEY).map(([c, k]) => [k, c]))
/** Chip order; "other carrier" shows only when some order is in it. */
const STATUSES = ['paid', 'awaiting_payout', 'expected', 'overdue', 'lost', 'refunded', 'other_carrier'] as const

type Row = OrderRow & { id: string }

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value)
  useEffect(() => { const h = setTimeout(() => setV(value), ms); return () => clearTimeout(h) }, [value, ms])
  return v
}

export default function OrdersPage() {
  const { t, i18n } = useTranslation()
  const fmt = useFmt()
  const s = useAnalyticsState()
  const label = useGroupLabel()
  const [sp, setSp] = useSearchParams()
  const status = sp.get('status') ?? ''
  const page = Math.max(0, Number(sp.get('page') ?? '0') || 0)
  const [q, setQ] = useState(sp.get('q') ?? '')
  const debouncedQ = useDebounced(q.trim(), 300)
  const sortKey = sp.get('sort') as OrderSortKey | null
  const apiSort = sortKey && COLUMN_OF[sortKey] ? sortKey : undefined
  const dir: 'asc' | 'desc' = sp.get('dir') === 'asc' ? 'asc' : 'desc'
  const sort: DataTableSort | undefined = apiSort ? { key: COLUMN_OF[apiSort], dir } : undefined
  const [exporting, setExporting] = useState<'idle' | 'busy' | 'error' | 'truncated'>('idle')

  const setParam = (k: string, v: string | null, resetPage = true) => setSp(prev => {
    const n = new URLSearchParams(prev)
    if (v) n.set(k, v); else n.delete(k)
    if (resetPage) n.delete('page')
    return n
  }, { replace: true })

  useEffect(() => {
    if ((sp.get('q') ?? '') !== debouncedQ) setParam('q', debouncedQ || null)
  }, [debouncedQ]) // eslint-disable-line react-hooks/exhaustive-deps

  const filters = { ...s.params, status: status || undefined, q: sp.get('q') || undefined, sort: apiSort, dir }
  const key = JSON.stringify({ ...filters, page })
  const orders = useAnalyticsQuery(`orders:${key}`, sig => getAnalyticsOrders({ ...filters, page, size: PAGE_SIZE }, sig))

  const rows = useMemo(() => (orders.data?.orders ?? []).map(o => ({ ...o, id: o.orderId }) as Row), [orders.data])

  function onSort(column: string) {
    const key = SORT_KEY[column]
    if (!key) return
    const nextDir = sort?.key === column && sort.dir === 'desc' ? 'asc' : 'desc'
    setSp(prev => {
      const n = new URLSearchParams(prev)
      n.set('sort', key); n.set('dir', nextDir); n.delete('page')
      return n
    }, { replace: true })
  }

  async function exportCsv() {
    setExporting('busy')
    try {
      const token = getAccessToken()
      const res = await fetch(`/api/v1${ordersExportPath(filters)}`, {
        credentials: 'include', headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!res.ok) throw new Error(String(res.status))
      const blob = await res.blob()
      const name = /filename="([^"]+)"/.exec(res.headers.get('content-disposition') ?? '')?.[1] ?? 'orders.csv'
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = name
      document.body.appendChild(a); a.click(); a.remove()
      URL.revokeObjectURL(url)
      setExporting(res.headers.get('x-export-truncated') === 'true' ? 'truncated' : 'idle')
    } catch {
      setExporting('error')
    }
  }

  const counts = orders.data?.counts ?? {}
  const allCount = STATUSES.reduce((a, k) => a + (counts[k] ?? 0), 0)
  const total = orders.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div data-testid="analytics-orders" className="max-w-[1400px]">
      <AnalyticsPageHeader title={t('analytics.pages.orders.title')} subtitle={t('analytics.pages.orders.subtitle')}
        right={
          <button type="button" onClick={exportCsv} disabled={exporting === 'busy'} data-testid="export-csv"
            className="h-[34px] px-3 rounded-lg border border-grey-100 bg-surface text-[13px] font-medium inline-flex items-center gap-1.5 hover:bg-elevated disabled:opacity-50">
            <Download size={14} /> {t('analytics.orders.export')}
          </button>
        } />
      <AnalyticsGrid>
        <OtherCarrierBanner params={s.params} />
        {exporting === 'truncated' && <div className="col-span-full"><Note>{t('analytics.orders.exportTruncated')}</Note></div>}
        {exporting === 'error' && <div className="col-span-full" role="alert"><Note>{t('analytics.orders.exportError')}</Note></div>}
        <AnalyticsCard span="s12" title={t('analytics.orders.title')} desc={t('analytics.orders.desc')} testId="card-orders">
          <div className="flex flex-wrap gap-2 items-center">
            <div role="group" aria-label={t('analytics.orders.statusFilter')} className="flex flex-wrap gap-2">
              {[{ k: '', n: allCount }, ...STATUSES.filter(k => k !== 'other_carrier' || (counts[k] ?? 0) > 0).map(k => ({ k, n: counts[k] ?? 0 }))].map(({ k, n }) => (
                <button key={k || 'all'} type="button" aria-pressed={status === k} onClick={() => setParam('status', k || null)} data-testid={`chip-${k || 'all'}`}
                  className={cn('h-7 px-[11px] rounded-full border text-[12.5px] font-medium inline-flex items-center gap-1.5',
                    status === k ? 'bg-primary text-white border-primary' : 'border-grey-100 text-neutral-text')}>
                  {k ? t(`analytics.orders.fin.${k}`) : t('analytics.orders.all')}
                  <b className={cn('text-[11.5px]', status === k ? 'opacity-70' : 'text-muted')}>{orders.data ? fmt.num(n) : '·'}</b>
                </button>
              ))}
            </div>
            <input type="search" value={q} onChange={e => setQ(e.target.value)} aria-label={t('analytics.orders.searchLabel')}
              placeholder={t('analytics.orders.search')} className="input h-8 text-small w-[260px] max-w-full ms-auto" data-testid="orders-search" />
          </div>
          <QueryBlock q={orders} lines={8}>
            {() => (
              <>
                <div className="-mx-[18px] px-[18px] [&_td]:whitespace-nowrap [&_td]:px-2.5 [&_th]:px-2.5" data-testid="orders-table">
                  <DataTable<Row>
                    rows={rows}
                    emptyMessage={t('analytics.orders.none')}
                    sort={sort}
                    onSort={onSort}
                    columns={[
                      { key: 'name', header: t('analytics.orders.cols.order'), mono: true, render: r => (
                        <Link to={`/orders?order=${encodeURIComponent(r.orderId)}`} className="text-trace-blue hover:underline" data-testid="order-link">{r.name}</Link>
                      ) },
                      { key: 'date', header: t('analytics.orders.cols.date'), sortable: true, render: r => fmt.day(r.placedAt) },
                      { key: 'customer', header: t('analytics.orders.cols.customer'), render: r => r.customer ?? '—' },
                      { key: 'gov', header: t('analytics.orders.cols.governorate'), render: r => r.governorate ? label('governorate', r.governorate.key, r.governorate.label, r.governorate.labelAr) : '—' },
                      { key: 'items', header: t('analytics.orders.cols.items'), align: 'end', render: r => fmt.num(r.items) },
                      { key: 'total', header: t('analytics.orders.cols.total'), align: 'end', sortable: true, render: r => fmt.money(r.total) },
                      { key: 'pay', header: t('analytics.orders.cols.payment'), render: r => r.paymentGroup ? label('payment', r.paymentGroup, r.paymentGroup) : '—' },
                      { key: 'delivery', header: t('analytics.orders.cols.delivery'), render: r => t(`analytics.orders.delivery.${r.deliveryStatus.label}`, { defaultValue: r.deliveryStatus.label }) },
                      { key: 'fin', header: t('analytics.orders.cols.financial'), sortable: true, render: r => <Pill kind={FIN_PILL[r.financialStatus] ?? 'neutral'}>{t(`analytics.orders.fin.${r.financialStatus}`)}</Pill> },
                      {
                        key: 'fees', header: t('analytics.orders.cols.fees'), align: 'end', sortable: true, render: r => (
                          <span className="inline-flex items-center gap-1.5 justify-end">{fmt.money(r.bostaFees)}{r.feesEstimated && <SourceChip title={t('analytics.chip.estimatedTip')}>{t('analytics.chip.estimated')}</SourceChip>}</span>
                        ),
                      },
                      {
                        key: 'net', header: t('analytics.orders.cols.net'), align: 'end', sortable: true, render: r => r.netToYou == null
                          ? <span className="text-muted">{r.refundAmountUnknown ? t('analytics.orders.refundUnknown') : t('analytics.orders.pending')}</span>
                          : r.netToYou < 0 ? <Pill kind="crit">{fmt.money(r.netToYou)}</Pill> : fmt.money(r.netToYou),
                      },
                    ]}
                  />
                </div>
                {total > PAGE_SIZE && (
                  <div className="flex items-center gap-3 justify-end text-[12.5px] text-muted" data-testid="orders-pager">
                    <span>{t('analytics.orders.pageOf', { page: fmt.num(page + 1), pages: fmt.num(pages), total: fmt.num(total) })}</span>
                    <button type="button" disabled={page === 0} onClick={() => setParam('page', page - 1 > 0 ? String(page - 1) : null, false)}
                      className="h-7 px-2.5 rounded-md border border-grey-100 disabled:opacity-40">{i18n.language === 'ar' ? '→' : '←'} {t('analytics.orders.prev')}</button>
                    <button type="button" disabled={page + 1 >= pages} onClick={() => setParam('page', String(page + 1), false)}
                      className="h-7 px-2.5 rounded-md border border-grey-100 disabled:opacity-40">{t('analytics.orders.next')} {i18n.language === 'ar' ? '←' : '→'}</button>
                  </div>
                )}
                <Note>{t('analytics.orders.legend')}</Note>
              </>
            )}
          </QueryBlock>
        </AnalyticsCard>
      </AnalyticsGrid>
    </div>
  )
}
