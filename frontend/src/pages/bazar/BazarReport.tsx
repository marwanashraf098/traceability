import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { OnlineStockTag } from './OnlineStock'
import { ArrowLeft, Download } from 'lucide-react'
import { BazarError, downloadBazarCustomers, getBazarReport, type BazarReport } from '../../bazar/api'
import { toNumber, transferCode } from '../../bazar/format'
import { Button, Spinner, cn, type BadgeTone } from '../../components/ui'
import { STATUS_TONE } from './BazarEvents'
import { dayCount } from './r1/overviewState'
import { amount, rangeLabel, weekdayShort } from './r1/parts'
import { Chip, Legend, Money, StackBar, money, type ChipTone } from './r2/parts'

// Bazar POS B5 — the event report (mockup page 1, step 10): /bazar/:id/report, in-shell (computer and phone). Read-
// only, owner / manager. Built from GET /report only (bazar tables + the linked transfer).
// R2 restyle (design/bazar-v2 V2_Report / V2_ReportPhone): the dark hero, money by day, what sold, the hours chart,
// where the pieces ended up, sellers and customers — only what the report already returns.

const pctText = (v: string | number | null | undefined) => (v == null ? '—' : `${Math.round(toNumber(v) * 100)}%`)

const TONE_CHIP: Record<BadgeTone, ChipTone> = { success: 'good', warning: 'warn', critical: 'bad', neutral: 'neutral', info: 'info' }
const DAY_CHIP: Record<string, ChipTone> = { open: 'neutral', closed_balanced: 'good', closed_short: 'warn', changed_after_close: 'warn' }
const METHOD_COLOR: Record<string, string> = { cash: 'bg-[#16A34A]', instapay: 'bg-trace-blue', card: 'bg-warning' }

export default function BazarReportPage() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [r, setR] = useState<BazarReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getBazarReport(id).then(setR).catch(e => {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.report.loadFailed'))
    })
  }, [id, navigate, t, i18n.language])

  if (error && !r) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!r || !id) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>
  return <ReportView id={id} r={r} />
}

function ReportView({ id, r }: { id: string; r: BazarReport }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState(false)
  const nr = r.money_not_received
  const maxDay = Math.max(1, ...r.sales_by_day.map(d => Math.abs(toNumber(d.revenue))))
  const maxHour = Math.max(1, ...r.busiest_hours.map(h => h.sales))
  const peak = r.busiest_hours.reduce<{ hour: number; sales: number } | null>((b, h) => (!b || h.sales > b.sales ? h : b), null)
  const moneyIn = Math.max(0, toNumber(r.money_in))
  const tr = r.transfer
  const transferClosed = tr.status === 'closed'

  async function exportCsv() {
    setExporting(true)
    setExportError(false)
    try { await downloadBazarCustomers(id, `bazar-customers-${r.event.starts_on}.csv`) }
    catch { setExportError(true) }
    finally { setExporting(false) }
  }

  return (
    <div className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-report">
      <div className="flex flex-col gap-1">
        <Link to="/bazar" className="text-small text-muted hover:text-primary self-start inline-flex items-center gap-1">
          <ArrowLeft size={14} aria-hidden className="rtl:-scale-x-100" />{t('bazar.title')}
        </Link>
        <div className="flex items-center gap-3 flex-wrap">
          <h1 className="text-[26px] leading-8 sm:text-[30px] sm:leading-9 font-bold tracking-[-0.02em] text-primary">{t('bazar.report.title')}</h1>
          <Chip tone={TONE_CHIP[STATUS_TONE[r.event.status]]} testId="report-status">
            {t(`bazar.status.${r.event.status}`)}{transferClosed ? ` · ${t('bazar.r2.report.transferClosed')}` : ''}
          </Chip>
          <OnlineStockTag mode={r.event.online_stock} />
        </div>
        <span className="text-body text-muted">
          {r.event.location_name} · <span>{rangeLabel(r.event.starts_on, r.event.ends_on, lang)}</span>
        </span>
      </div>

      <ReportKpis r={r} />

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <Card title={t('bazar.r2.report.byDay')} testId="report-by-day">
          {r.sales_by_day.length === 0 ? <Empty /> : (
            <div className="flex items-end justify-around gap-3 pt-2">
              {r.sales_by_day.map(d => (
                <div key={d.business_day} className="flex flex-col items-center gap-1.5 min-w-0 flex-1 max-w-[110px]"
                     data-testid={`report-day-${d.business_day}`}>
                  <span dir="ltr" className="text-body font-bold text-primary tabular-nums">{amount(d.revenue)}</span>
                  <div className="w-full h-36 flex items-end" aria-hidden>
                    <div className="w-full rounded-t-md bg-trace-blue"
                         style={{ height: `${Math.max(4, (Math.abs(toNumber(d.revenue)) / maxDay) * 100)}%` }} />
                  </div>
                  <span className="text-small text-primary" dir="auto">{weekdayShort(d.business_day, lang)} {+d.business_day.slice(8, 10)}</span>
                  <Chip tone={DAY_CHIP[d.status] ?? 'neutral'}>{t(`bazar.r2.close.chip.${d.status}`)}</Chip>
                </div>
              ))}
            </div>
          )}
          <div className="flex flex-col gap-2 pt-2 border-t border-line" data-testid="report-payment-split">
            <StackBar total={moneyIn} parts={r.payment_split.map(m => ({
              key: m.method, n: Math.max(0, toNumber(m.amount)), color: METHOD_COLOR[m.method] ?? 'bg-grey-300' }))} />
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              {r.payment_split.map(m => (
                <Legend key={m.method} color={METHOD_COLOR[m.method] ?? 'bg-grey-300'} label={t(`bazar.method.${m.method}`)}
                        value={`${amount(m.amount)} · ${pctText(m.share)}`} />
              ))}
            </div>
            <span className="text-small text-muted">{t('bazar.r2.report.splitNote')}</span>
          </div>
        </Card>

        <Card title={t('bazar.r2.report.whatSold')} sub={t('bazar.r2.report.whatSoldSub')} testId="report-top-products">
          {r.top_products.length === 0 ? <Empty /> : r.top_products.map(p => (
            <SoldRow key={p.product_title} label={p.product_title} sold={p.sold} sent={p.sent} revenue={p.revenue} />
          ))}
        </Card>
      </div>

      <Card title={t('bazar.report.hours')} testId="report-hours"
        sub={peak ? t('bazar.r2.report.busiest', { from: hh(peak.hour), to: hh(peak.hour + 1), count: peak.sales }) : t('bazar.report.hoursSub')}>
        {r.busiest_hours.length === 0 ? <Empty /> : (
          <div className="flex items-end gap-1.5 sm:gap-3 h-44 pt-4 border-b border-line" dir="ltr">
            {r.busiest_hours.map(h => {
              const top = peak != null && h.hour === peak.hour
              return (
                <div key={h.hour} className="flex-1 h-full flex flex-col items-center justify-end gap-1 min-w-0">
                  <span className={cn('text-[11px] font-semibold tabular-nums', top ? 'text-trace-blue' : 'text-muted')}>{h.sales}</span>
                  <div className={cn('w-full rounded-t-md', top ? 'bg-trace-blue' : 'bg-[#CBD5E1]')}
                       style={{ height: `${(h.sales / maxHour) * 100}%` }} />
                </div>
              )
            })}
          </div>
        )}
        {r.busiest_hours.length > 0 && (
          <div className="flex gap-1.5 sm:gap-3 -mt-1" dir="ltr" aria-hidden>
            {r.busiest_hours.map(h => <span key={h.hour} className="flex-1 text-center text-[11px] text-muted tabular-nums">{hh(h.hour)}</span>)}
          </div>
        )}
        <span className="text-small text-muted">{t('bazar.report.hoursSub')}</span>
      </Card>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1fr)]">
        <Card title={t('bazar.r2.report.ended', { count: tr.sent })} testId="report-transfer">
          <StackBar className="h-3" total={tr.sent} parts={[
            { key: 'sold', n: tr.sold, color: 'bg-[#16A34A]' },
            { key: 'back', n: tr.back, color: 'bg-trace-blue' },
            { key: 'damaged', n: tr.damaged, color: 'bg-warning' },
            { key: 'lost', n: tr.lost, color: 'bg-critical' },
            { key: 'out', n: tr.outstanding, color: 'bg-grey-300' },
          ]} />
          <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
            <Outcome color="bg-[#16A34A]" label={t('bazar.end.count.sold')} n={tr.sold} />
            <Outcome color="bg-trace-blue" label={t('bazar.r2.report.backInStock')} n={tr.back} />
            <Outcome color="bg-warning" label={t('bazar.end.count.damaged')} n={tr.damaged} />
            <Outcome color="bg-critical" label={t('bazar.end.count.lost')} n={tr.lost} />
            <Outcome color="bg-grey-300" label={t('bazar.end.count.out')} n={tr.outstanding} />
          </div>
          <p className="text-small text-muted tabular-nums" dir="auto">
            {t('bazar.end.equation', { sent: tr.sent, sold: tr.sold, back: tr.back, damaged: tr.damaged, lost: tr.lost })}
          </p>
          {tr.outstanding > 0 && <p className="text-small text-warning-text">{t('bazar.end.stillOut', { count: tr.outstanding })}</p>}
          <div className="flex items-center justify-between gap-2 pt-1 border-t border-line">
            <span className="font-mono text-small text-muted" dir="ltr">{transferCode(tr.transfer_id)}</span>
            <Link to={`/transfers/${tr.transfer_id}`} className="text-small font-semibold text-trace-blue">{t('bazar.live.openTransfer')}</Link>
          </div>
        </Card>

        <Card title={t('bazar.r2.report.sellers')} testId="report-per-seller">
          {r.per_seller.length === 0 ? <Empty /> : r.per_seller.map(s => (
            <div key={s.seller_name} className="flex items-center gap-3">
              <span aria-hidden className="w-8 h-8 rounded-lg bg-trace-blue/10 text-trace-blue text-small font-bold flex items-center justify-center shrink-0">
                {(s.seller_name || '?').trim().charAt(0).toUpperCase()}
              </span>
              <span className="flex flex-col min-w-0 flex-1">
                <span className="font-semibold text-primary truncate">{s.seller_name}</span>
                <span className="text-small text-muted">{t('bazar.phone.pieces', { count: s.pieces })}</span>
              </span>
              <Money value={s.total} className="font-bold text-primary" />
            </div>
          ))}
        </Card>

        <Card title={t('bazar.report.customers')} testId="report-customers">
          <p className="flex items-baseline gap-2">
            <span dir="ltr" className="text-[30px] leading-9 font-bold text-primary tabular-nums">{r.customers_saved}</span>
            <span className="text-body text-muted">{t('bazar.report.customersSaved', { count: r.customers_saved })}</span>
          </p>
          <p className="text-small text-muted">
            {t('bazar.report.returns', { count: r.returns })} · {t('bazar.report.exchanges', { count: r.exchanges })}
          </p>
          <p className="text-small text-muted">{t('bazar.report.exportNote')}</p>
          <Button variant="secondary" iconStart={Download} onClick={exportCsv} loading={exporting}
                  disabled={r.customers_saved === 0} className="self-start">{t('bazar.report.exportCsv')}</Button>
          {exportError && <p role="alert" className="text-small text-critical">{t('bazar.report.exportFailed')}</p>}
        </Card>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t('bazar.report.topSizes')} testId="report-top-sizes">
          {r.top_sizes.length === 0 ? <Empty /> : r.top_sizes.map(p => (
            <SoldRow key={p.product_title + p.variant_title} label={`${p.product_title} · ${p.variant_title}`}
                     sold={p.sold} sent={p.sent} revenue={p.revenue} />
          ))}
        </Card>
        <Card title={t('bazar.report.notReceived')} sub={t('bazar.report.notReceivedSub')} testId="report-not-received">
          <Line label={t('bazar.report.cashShort')} value={money(nr.cash_short)} />
          <Line label={t('bazar.report.cardShort')} value={money(nr.card_short)} />
          <Line label={t('bazar.report.instapay')} value={money(nr.instapay_not_received)} />
          {toNumber(nr.cash_over) > 0 && <Line label={t('bazar.report.cashOver')} value={money(nr.cash_over)} />}
          {toNumber(nr.card_over) > 0 && <Line label={t('bazar.report.cardOver')} value={money(nr.card_over)} />}
          <div className={cn('flex justify-between gap-2 pt-2 border-t border-line font-bold',
            toNumber(nr.total) > 0 ? 'text-critical-text' : 'text-primary')}>
            <span>{t('bazar.report.total')}</span><span className="tabular-nums" dir="ltr">{money(nr.total)}</span>
          </div>
          {nr.days_not_closed > 0 && (
            <p className="text-small text-warning-text">{t('bazar.report.daysNotClosed', { count: nr.days_not_closed })}</p>
          )}
        </Card>
      </div>
    </div>
  )
}

const hh = (h: number) => `${String(h % 24).padStart(2, '0')}:00`

/**
 * The dark hero: revenue (sales minus returns) and four tiles — sell-through, pieces sold, average sale, per day. Also
 * the phone's end-of-event summary.
 */
export function ReportKpis({ r }: { r: BazarReport }) {
  const { t } = useTranslation()
  const days = Math.max(1, r.sales_by_day.length || dayCount(r.event.starts_on, r.event.ends_on))
  const notReceived = toNumber(r.money_not_received.total)
  const tiles: { k: string; label: string; value: ReactNode; sub: string }[] = [
    { k: 'through', label: t('bazar.report.sellThrough'), value: pctText(r.sell_through),
      sub: t('bazar.r2.report.ofPieces', { sold: r.pieces_sold, sent: r.pieces_sent }) },
    { k: 'sold', label: t('bazar.report.piecesSold'), value: String(r.pieces_sold), sub: t('bazar.report.ofSent', { count: r.pieces_sent }) },
    { k: 'avg', label: t('bazar.report.averageSale'), value: r.average_sale == null ? '—' : money(r.average_sale),
      sub: r.sales == null ? '' : t('bazar.report.salesCount', { count: r.sales }) },
    { k: 'day', label: t('bazar.r2.report.perDay'), value: amount(Math.round(toNumber(r.revenue) / days)),
      sub: t('bazar.r2.report.avgOfDays', { count: days }) },
  ]
  return (
    <section className="bg-grey-900 text-[#F2F4F7] rounded-[20px] p-4 sm:p-6 flex flex-wrap gap-5 items-center" data-testid="report-kpis">
      <div className="flex-[1_1_260px] min-w-0 flex flex-col gap-1" data-testid="kpi-revenue">
        <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-sidebar-text">
          {r.event.name} · {t('bazar.r1.overview.dayCount', { count: days })}
        </span>
        <span className="sr-only">{t('bazar.report.revenue')}</span>
        <Money value={r.revenue} unitClass="text-[18px] text-sidebar-text"
               className="text-[40px] leading-[46px] sm:text-[56px] sm:leading-[60px] font-bold tracking-[-0.03em] text-start" />
        <span className="text-small text-sidebar-text">
          {t('bazar.report.revenueSub')}
          {toNumber(r.discounts) > 0 && <> · {t('bazar.r2.report.discounts')} <Money value={r.discounts ?? 0} unitClass="text-sidebar-text" /></>}
          {notReceived > 0 && <> · <span className="text-[#FCA5A5]">{t('bazar.r1.overview.notReceived')} <Money value={notReceived} unitClass="text-[#FCA5A5]" /></span></>}
        </span>
      </div>
      <div className="flex-[2_1_560px] min-w-0 grid grid-cols-2 sm:grid-cols-4 gap-2.5">
        {tiles.map(x => (
          <div key={x.k} className="bg-white/[0.06] rounded-xl px-3.5 py-3 flex flex-col gap-0.5 min-w-0" data-testid={`kpi-${x.k}`}>
            <span className="text-small text-sidebar-text">{x.label}</span>
            <span dir="ltr" className="text-[20px] leading-7 font-bold tabular-nums text-start whitespace-nowrap">{x.value}</span>
            <span className="text-[11px] text-sidebar-text">{x.sub}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

function Card({ title, sub, children, testId }: { title: string; sub?: string; children: ReactNode; testId?: string }) {
  return (
    <section className="card p-4 sm:p-5 flex flex-col gap-3 min-w-0" data-testid={testId}>
      <div className="flex flex-col">
        <h2 className="text-body-lg font-semibold text-primary">{title}</h2>
        {sub && <span className="text-small text-muted">{sub}</span>}
      </div>
      {children}
    </section>
  )
}

/** A product (or size) row: name, a sold-of-taken bar, "8/10", the money. */
function SoldRow({ label, sold, sent, revenue }: { label: string; sold: number; sent: number; revenue: string | number }) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_auto] sm:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_auto_auto] items-center gap-x-3 gap-y-1">
      <span className="font-semibold text-primary truncate">{label}</span>
      <div aria-hidden className="hidden sm:block h-1.5 rounded-sm bg-charcoal overflow-hidden">
        <div className="h-full bg-grey-900" style={{ width: `${sent ? Math.min(100, (sold / sent) * 100) : 0}%` }} />
      </div>
      <span dir="ltr" className="hidden sm:inline text-small text-muted tabular-nums">{sold}/{sent}</span>
      <span className="flex items-center gap-2 justify-end">
        <span dir="ltr" className="sm:hidden text-small text-muted tabular-nums">{sold}/{sent}</span>
        <span dir="ltr" className="font-bold text-primary tabular-nums">{amount(revenue)}</span>
      </span>
    </div>
  )
}

function Outcome({ color, label, n }: { color: string; label: string; n: number }) {
  return (
    <span className="flex flex-col gap-0.5 min-w-0">
      <Legend color={color} label={label} />
      <span dir="ltr" className="text-h4 font-bold text-primary tabular-nums text-start">{n}</span>
    </span>
  )
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2 text-body">
      <span className="text-primary">{label}</span>
      <span className="tabular-nums text-primary" dir="ltr">{value}</span>
    </div>
  )
}

function Empty() {
  const { t } = useTranslation()
  return <p className="text-body text-muted">{t('bazar.report.noSales')}</p>
}
