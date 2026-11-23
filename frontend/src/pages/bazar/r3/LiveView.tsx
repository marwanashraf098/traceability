import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { OnlineStockTag } from '../OnlineStock'
import { ArrowRight, ChevronDown, ChevronRight, ScanLine, Smartphone } from 'lucide-react'
import {
  currentUserId, getBazarLeftovers, getBazarReport, getBazarToday, isReceipt, listBazarDays, listBazarSales, orderedMethods,
  type BazarDays, type BazarEventDetail, type BazarLeftovers, type BazarReport, type BazarSaleRow, type BazarTodaySummary,
} from '../../../bazar/api'
import { eventDay, pieceTitle, saleTime, toNumber, transferCode } from '../../../bazar/format'
import { Spinner, cn } from '../../../components/ui'
import { QrCode } from '../../../phone/PhonePairModal'
import { SaleBadge } from '../phone/SaleSheets'
import { DayStatusBadge, dayTitle } from '../CloseDayForm'
import { LeftoverCounts } from '../Leftovers'
import { defaultCloseDay } from '../closeDayDefault'
import { Eyebrow, Tag, amount, dayLabel, percent, rangeLabel, weekdayShort } from '../r1/parts'
import { Chip, Money, StackBar } from '../r2/parts'
import { PricesCard, usePriceData } from './PricesCard'
import { SellersCard } from './SellersCard'
import { Card, EventHeader, MoreMenu, TabBar, btnPrimary, btnSecondary, isObj, minutesAgo, useOptional, type TabDef } from './parts'

// Bazar POS B6 — a live (or ended) event, restyled in R3 (design/bazar-v2 V2_Event / V2_EventPhone): the header with
// Close today / More ▾ / Start selling, and tabs — Today, Sales, Days & cash, Pieces, Prices, Sellers. Same reads as
// before plus two approved ones: GET …/today (sold today per seller, on the 30 s loop) and GET …/report (selling best
// and the whole-bazar hours chart, on open and when Today is opened again). A read that fails hides its card only.
// Close the day and End event are their own pages now (/bazar/:id/close-day, /bazar/:id/end-event) — no modals.

const METHOD_COLOR: Record<string, string> = { cash: 'bg-[#16A34A]', instapay: 'bg-trace-blue', card: 'bg-warning' }
type Tab = 'today' | 'sales' | 'days' | 'pieces' | 'prices' | 'sellers'

const validToday = (v: unknown) => isObj(v) && Array.isArray(v.per_seller)
const validReport = (v: unknown) => isObj(v) && Array.isArray(v.top_products) && Array.isArray(v.busiest_hours)

export function LiveView({ ev, reload }: { ev: BazarEventDetail; reload: () => Promise<void> }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const live = ev.status === 'live'
  const tabs: TabDef[] = [
    { key: 'today', label: live ? t('bazar.r3.tabs.today') : t('bazar.r3.tabs.summary') },
    { key: 'sales', label: t('bazar.r3.tabs.sales'), count: ev.totals?.sales ?? ev.today.sales },
    { key: 'days', label: t('bazar.r3.tabs.days') },
    { key: 'pieces', label: t('bazar.r3.tabs.pieces'), count: ev.counts.sent },
    ...(live ? [{ key: 'prices', label: t('bazar.r3.tabs.prices') }, { key: 'sellers', label: t('bazar.r3.tabs.sellers') }] : []),
  ]
  const asked = params.get('tab') as Tab | null
  const tab: Tab = asked && tabs.some(x => x.key === asked) ? asked : 'today'
  const setTab = (k: string) => setParams(p => { const n = new URLSearchParams(p); n.set('tab', k); return n }, { replace: true })

  const [sales, setSales] = useState<BazarSaleRow[] | null>(null)
  const [days, setDays] = useState<BazarDays | null>(null)
  const [today, setToday] = useState<BazarTodaySummary | null | undefined>(undefined)
  const [left, setLeft] = useState<BazarLeftovers | null>(null)

  const loadDays = useCallback(() => listBazarDays(ev.id).then(setDays).catch(() => {}), [ev.id])
  // U1a: the button opens the oldest day still needing a close (closeDayDefault) — its date shown when it isn't today.
  const closeTarget = days ? defaultCloseDay(days.days) : undefined
  const closeDayLabel = closeTarget && !closeTarget.is_today
    ? t('bazar.u1a.closeDayOn', { date: dayLabel(closeTarget.business_day, lang) })
    : t('bazar.u1a.closeDay')
  const loadToday = useCallback(() => {
    if (!live) return
    getBazarToday(ev.id).then(v => setToday(validToday(v) ? v : null)).catch(() => setToday(null))
  }, [ev.id, live])
  // B5: once ended, the leftovers coming back on the linked transfer.
  useEffect(() => {
    if (ev.status === 'ended') getBazarLeftovers(ev.id).then(setLeft).catch(() => {})
  }, [ev.id, ev.status])
  useEffect(() => {
    const loadSales = () => listBazarSales(ev.id, 10).then(p => setSales(p.items)).catch(() => setSales([]))
    loadSales()
    loadDays()
    loadToday()
    if (!live) return
    const iv = window.setInterval(() => { reload(); loadSales(); loadDays(); loadToday() }, 30_000)
    return () => window.clearInterval(iv)
  }, [ev.id, live, reload, loadDays, loadToday])

  // The report (selling best + hours): on open, and again each time Today is opened — never on the 30 s loop.
  const [reportRound, setReportRound] = useState(0)
  const lastTab = useRef<Tab>(tab)
  useEffect(() => {
    if (tab === 'today' && lastTab.current !== 'today') setReportRound(n => n + 1)
    lastTab.current = tab
  }, [tab])
  const report = useOptional<BazarReport>(`${ev.id}:${reportRound}`, () => getBazarReport(ev.id), validReport)

  const { day, count } = eventDay(ev.starts_on, ev.ends_on, ev.today.business_day)
  const inRange = day >= 1 && day <= count

  return (
    <div className="flex flex-col gap-5" data-testid="bazar-live">
      <EventHeader startsOn={ev.starts_on} lang={lang} name={ev.name}
        back={{ to: '/bazar', label: t('bazar.title') }}
        tag={<>
          {live
            ? <Tag tone="live" dot testId="bazar-event-tag">{inRange ? t('bazar.r1.overview.liveNow', { day, count }) : t('bazar.r1.overview.liveNowPlain')}</Tag>
            : <Tag tone="neutral" testId="bazar-event-tag">{t('bazar.status.ended')}</Tag>}
          <OnlineStockTag mode={ev.online_stock} />
        </>}
        meta={<>{ev.location_name} · <span>{rangeLabel(ev.starts_on, ev.ends_on, lang)}</span> · <span dir="ltr" className="font-mono">{transferCode(ev.transfer_id)}</span></>}
        actions={live ? (
          <>
            <button type="button" className={btnSecondary} data-testid="desktop-close-day" disabled={!days?.days.length}
                    onClick={() => navigate(`/bazar/${ev.id}/close-day`)}>{closeDayLabel}</button>
            <MoreMenu label={t('bazar.r3.header.more')} items={[
              { key: 'prices', label: t('bazar.prices.changePrices'), onSelect: () => setTab('prices') },
              { key: 'sellers', label: t('bazar.r3.header.inviteSellers'), onSelect: () => setTab('sellers') },
              { key: 'end', label: t('bazar.phone.endEvent'), onSelect: () => navigate(`/bazar/${ev.id}/end-event`), danger: true },
            ]} />
            <Link to={`/bazar/${ev.id}/sell`} className={cn(btnPrimary, 'hidden sm:inline-flex')} data-testid="bazar-start-selling">
              <ScanLine size={18} aria-hidden />{t('bazar.r3.header.startSelling')}
            </Link>
          </>
        ) : (
          <>
            {left && left.transfer_status !== 'closed' && (
              <Link to={`/bazar/${ev.id}/end-event`} className={btnSecondary}>{t('bazar.r3.pieces.bringBack')}</Link>
            )}
            <Link to={`/bazar/${ev.id}/report`} className={btnPrimary} data-testid="view-report">{t('bazar.end.viewReport')}</Link>
          </>
        )} />

      <TabBar tabs={tabs} current={tab} onChange={setTab} label={t('bazar.r3.tabs.label')} />

      <div role="tabpanel" id={`bazar-panel-${tab}`} aria-labelledby={`bazar-tab-${tab}`} className="flex flex-col gap-5">
        {tab === 'today' && <TodayPanel ev={ev} sales={sales} days={days} today={today} report={report} onTab={setTab} />}
        {tab === 'sales' && <SalesPanel ev={ev} />}
        {tab === 'days' && <DaysCard ev={ev} days={days} />}
        {tab === 'pieces' && <PiecesPanel ev={ev} left={left} />}
        {tab === 'prices' && live && <PricesPanel ev={ev} />}
        {tab === 'sellers' && live && (
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] items-start">
            <SellersCard eventId={ev.id} />
            <PhoneCard ev={ev} />
          </div>
        )}
      </div>
      {/* The phone's bottom bar (V2_EventPhone): Return · Start selling. */}
      {live && (
        <div className="sm:hidden sticky bottom-3 z-10 grid grid-cols-[auto_1fr] gap-2.5">
          <Link to={`/bazar/${ev.id}/return`} className={cn(btnSecondary, 'h-[52px]')}>{t('bazar.r3.today.return')}</Link>
          <Link to={`/bazar/${ev.id}/sell`} className={cn(btnPrimary, 'h-[52px]')}><ScanLine size={18} aria-hidden />{t('bazar.r3.header.startSelling')}</Link>
        </div>
      )}
    </div>
  )
}

// ── Today ────────────────────────────────────────────────────────────────────

function TodayPanel({ ev, sales, days, today, report, onTab }: {
  ev: BazarEventDetail; sales: BazarSaleRow[] | null; days: BazarDays | null
  today: BazarTodaySummary | null | undefined; report: BazarReport | null | undefined; onTab: (k: string) => void
}) {
  const live = ev.status === 'live'
  return (
    <>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.85fr)_minmax(0,1fr)] items-stretch">
        <DarkToday ev={ev} sales={sales} days={days} report={report} />
        <PiecesCard ev={ev} />
      </div>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.85fr)_minmax(0,1fr)] items-start">
        <LatestSales ev={ev} sales={sales} onAll={() => onTab('sales')} />
        <div className="flex flex-col gap-5 min-w-0">
          {report && report.top_products.length > 0 && <SellingBest report={report} />}
          {live && today && <SoldToday today={today} onInvite={() => onTab('sellers')} />}
          {live && <PhoneCard ev={ev} />}
        </div>
      </div>
    </>
  )
}

function DarkToday({ ev, sales, days, report }: {
  ev: BazarEventDetail; sales: BazarSaleRow[] | null; days: BazarDays | null; report: BazarReport | null | undefined
}) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const live = ev.status === 'live'
  const block = live ? ev.today : (ev.totals ?? ev.today)
  const total = toNumber(block.total)
  const salesN = block.sales
  const methods = orderedMethods(ev.payment_methods)
  const byMethod = methods.map(m => ({ m, v: toNumber(block.by_method[m]) }))
  const moneyIn = byMethod.reduce((s, x) => s + Math.max(0, x.v), 0)
  const yesterday = live && days ? [...days.days].reverse().find(d => d.business_day < ev.today.business_day) : undefined
  const last = sales?.[0]
  const hours = report?.busiest_hours ?? []
  const maxHour = Math.max(1, ...hours.map(h => h.sales))
  const peak = hours.reduce<{ hour: number; sales: number } | null>((b, h) => (!b || h.sales > b.sales ? h : b), null)

  return (
    <section className="bg-grey-900 text-[#F2F4F7] rounded-[20px] p-4 sm:p-6 flex flex-wrap gap-6 min-w-0" data-testid="kpi-today">
      <div className="flex-[1_1_260px] min-w-0 flex flex-col gap-3">
        <Eyebrow dark>{live ? `${t('bazar.r3.today.today')} · ${dayLabel(ev.today.business_day, lang)}` : t('bazar.r3.today.wholeBazar')}</Eyebrow>
        <Money value={total} unitClass="text-[18px] text-sidebar-text" testId="bazar-today-money"
               className="text-[40px] leading-[46px] sm:text-[52px] sm:leading-[56px] font-bold tracking-[-0.03em] text-start" />
        <span className="text-body text-grey-100">
          {t('bazar.live.salesCount', { count: salesN })}
          {salesN > 0 && <> · {t('bazar.r3.today.avg')} <span dir="ltr" className="tabular-nums">{amount(Math.round(total / salesN))} EGP</span></>}
          {live && block.returns ? <> · {t('bazar.phone.returnsCount', { count: block.returns })}</> : null}
        </span>
        <StackBar total={moneyIn} parts={byMethod.map(x => ({ key: x.m, n: Math.max(0, x.v), color: METHOD_COLOR[x.m] ?? 'bg-grey-300' }))} />
        <div className="flex gap-5 flex-wrap" data-testid="kpi-methods">
          {byMethod.map(x => (
            <span key={x.m} className="flex flex-col">
              <span className="inline-flex items-center gap-1.5 text-small text-sidebar-text">
                <span aria-hidden className={cn('w-2.5 h-2.5 rounded-sm', METHOD_COLOR[x.m])} />{t(`bazar.method.${x.m}`)}
              </span>
              <span dir="ltr" className="text-body-lg font-bold tabular-nums text-start">{amount(x.v)}</span>
            </span>
          ))}
        </div>
      </div>
      <div className="flex-[1_1_260px] min-w-0 flex flex-col gap-3">
        {hours.length > 0 && (
          <div className="flex flex-col gap-2" data-testid="bazar-hours">
            <div className="flex items-center justify-between gap-2">
              <Eyebrow dark>{t('bazar.r3.today.hours')}</Eyebrow>
              {live && last && <span className="text-[11px] text-sidebar-text">{t('bazar.r3.today.lastSale', { count: minutesAgo(last.sold_at) })}</span>}
            </div>
            <div className="flex items-end gap-1.5 h-24" dir="ltr">
              {hours.map(h => (
                <div key={h.hour} className="flex-1 h-full flex items-end min-w-0">
                  <div className={cn('w-full rounded-t-sm', peak && h.hour === peak.hour ? 'bg-trace-blue' : 'bg-grey-500')}
                       style={{ height: `${Math.max(6, (h.sales / maxHour) * 100)}%` }} />
                </div>
              ))}
            </div>
            <div className="flex gap-1.5" dir="ltr" aria-hidden>
              {hours.map(h => <span key={h.hour} className="flex-1 text-center text-[11px] text-sidebar-text tabular-nums">{String(h.hour % 24).padStart(2, '0')}</span>)}
            </div>
          </div>
        )}
        {hours.length === 0 && live && last && (
          <span className="text-small text-sidebar-text">{t('bazar.r3.today.lastSale', { count: minutesAgo(last.sold_at) })}</span>
        )}
        <div className="flex flex-col gap-2 items-start mt-auto">
          {live && ev.totals && (
            <span className="px-2.5 py-1 rounded bg-white/[0.08] text-small text-grey-100" data-testid="kpi-event-total">
              {t('bazar.r3.today.wholeBazar')} <b dir="ltr" className="text-[#F2F4F7] tabular-nums">{amount(ev.totals.total)}</b>
            </span>
          )}
          {yesterday && (
            <span className="px-2.5 py-1 rounded bg-white/[0.08] text-small text-grey-100" data-testid="bazar-yesterday">
              {t('bazar.r3.today.yesterday')} <b dir="ltr" className="text-[#F2F4F7] tabular-nums">{amount(yesterday.money.total)}</b>
              {' · '}{t(`bazar.r2.close.chip.${yesterday.status}`)}
            </span>
          )}
        </div>
      </div>
    </section>
  )
}

function PiecesCard({ ev }: { ev: BazarEventDetail }) {
  const { t } = useTranslation()
  const sent = ev.counts.sent || 0
  const returned = ev.totals?.returns ?? 0
  return (
    <Card testId="bazar-pieces" title={t('bazar.r3.pieces.title')}
      end={<Link to={`/transfers/${ev.transfer_id}`} className="text-small font-semibold text-trace-blue">{t('bazar.live.openTransfer')}</Link>}>
      <p className="flex items-baseline gap-2 flex-wrap">
        <span dir="ltr" className="text-[32px] leading-9 font-bold text-primary tabular-nums">{percent(sent ? ev.counts.sold / sent : 0)}</span>
        <span className="text-body text-muted">{t('bazar.r3.pieces.soldThrough', { sold: ev.counts.sold, sent })}</span>
      </p>
      <StackBar className="h-3" total={sent} parts={[
        { key: 'sold', n: ev.counts.sold, color: 'bg-[#16A34A]' },
        { key: 'at', n: ev.counts.at_bazar, color: 'bg-trace-blue' },
      ]} />
      <dl className="flex flex-col gap-2 text-body">
        <Row color="bg-[#16A34A]" label={t('bazar.live.sold')} value={ev.counts.sold} />
        <Row color="bg-trace-blue" label={t('bazar.live.atBazar')} value={ev.counts.at_bazar} />
        <Row color="bg-warning" label={t('bazar.r3.pieces.returned')} value={returned} />
      </dl>
    </Card>
  )
}

function Row({ color, label, value }: { color: string; label: string; value: number }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="inline-flex items-center gap-2 text-primary"><span aria-hidden className={cn('w-2.5 h-2.5 rounded-sm', color)} />{label}</dt>
      <dd dir="ltr" className="font-bold text-primary tabular-nums">{value}</dd>
    </div>
  )
}

function LatestSales({ ev, sales, onAll }: { ev: BazarEventDetail; sales: BazarSaleRow[] | null; onAll: () => void }) {
  const { t } = useTranslation()
  const live = ev.status === 'live'
  const total = ev.totals?.sales ?? ev.today.sales
  return (
    <Card testId="bazar-live-sales" title={t('bazar.r3.today.latest')}
      end={<span className="flex items-center gap-4">
        {live && <Link to={`/bazar/${ev.id}/return`} className="text-small font-semibold text-trace-blue">{t('bazar.r3.today.returnExchange')}</Link>}
        <button type="button" onClick={onAll} className="text-small font-semibold text-trace-blue inline-flex items-center gap-1">
          {t('bazar.r3.today.all', { count: total })}<ArrowRight size={13} aria-hidden className="rtl:-scale-x-100" />
        </button>
      </span>}>
      {!sales ? <Spinner /> : sales.length === 0
        ? <p className="text-body text-muted">{t('bazar.sell.noSales')}</p>
        : <SalesTable ev={ev} rows={sales.slice(0, 6)} />}
    </Card>
  )
}

/** The sales table (Today's latest and the Sales tab): time, piece, seller, paid, EGP; carts open to their lines. */
function SalesTable({ ev, rows }: { ev: BazarEventDetail; rows: BazarSaleRow[] }) {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState<Set<string>>(new Set())
  const today = ev.today.business_day
  const when = (s: BazarSaleRow) => (
    <>
      {s.business_day !== today && <span className="block text-[11px] text-muted">{weekdayShort(s.business_day, i18n.language)}</span>}
      <span dir="ltr">{saleTime(s.sold_at)}</span>
    </>
  )
  const paid = (s: BazarSaleRow) => s.status === 'undone'
    ? <Chip tone="neutral">{t('bazar.sell.undoneShort')}</Chip>
    : s.method ? <Chip tone="neutral">{t(`bazar.method.${s.method}`)}</Chip> : null
  return (
    <div className="overflow-x-auto -mx-1 px-1">
      <table className="w-full">
        <thead>
          <tr className="text-[11px] font-bold uppercase tracking-[0.06em] text-muted">
            <th className="tbl-cell text-start">{t('bazar.r3.sales.time')}</th>
            <th className="tbl-cell text-start">{t('bazar.r3.sales.piece')}</th>
            <th className="tbl-cell text-start hidden sm:table-cell">{t('bazar.r3.sales.seller')}</th>
            <th className="tbl-cell text-start hidden sm:table-cell">{t('bazar.r3.sales.paid')}</th>
            <th className="tbl-cell text-end">EGP</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(s => isReceipt(s) ? (
            <Fragment key={s.id}>
              <tr className="tbl-row" data-testid="sales-receipt-row">
                <td className="tbl-cell text-small text-muted tabular-nums align-top">{when(s)}</td>
                <td className={cn('tbl-cell text-primary', s.status !== 'active' && 'line-through text-muted')}>
                  <button type="button" aria-expanded={open.has(s.id)}
                    onClick={() => setOpen(prev => { const n = new Set(prev); if (n.has(s.id)) n.delete(s.id); else n.add(s.id); return n })}
                    className="inline-flex items-center gap-1.5 font-semibold hover:underline text-start">
                    {open.has(s.id) ? <ChevronDown size={14} aria-hidden /> : <ChevronRight size={14} className="rtl:rotate-180" aria-hidden />}
                    {t('bazar.cart.row', { count: s.line_count })}
                    <span className="sr-only">{open.has(s.id) ? t('bazar.cart.hideLines') : t('bazar.cart.showLines')}</span>
                  </button>
                  <Chip tone="neutral" className="ms-2 align-middle">{t('bazar.r3.sales.cart')}</Chip>
                  {toNumber(s.discount) > 0 && (
                    <span className="block text-small text-muted">{t('bazar.cart.discountLine', { amount: `${amount(s.discount)} EGP` })}</span>
                  )}
                  <span className="sm:hidden block text-small text-muted">{s.seller_name}{s.method ? ` · ${t(`bazar.method.${s.method}`)}` : ''}</span>
                </td>
                <td className="tbl-cell text-small text-muted hidden sm:table-cell">{s.seller_name}</td>
                <td className="tbl-cell hidden sm:table-cell">{paid(s)}</td>
                <td className={cn('tbl-cell text-end font-bold tabular-nums', s.status !== 'active' && 'line-through text-muted')} dir="ltr">
                  {amount(s.total)}
                </td>
              </tr>
              {open.has(s.id) && s.lines.map(l => (
                <tr key={l.id} className="tbl-row bg-charcoal/40" data-testid="sales-receipt-line">
                  <td className="tbl-cell font-mono text-small text-muted" dir="ltr">{l.piece_code}</td>
                  <td className={cn('tbl-cell text-small text-primary ps-8', l.status !== 'active' && 'line-through text-muted')}>
                    {pieceTitle(l)} {l.status !== 'active' && <Badge sale={l} />}
                  </td>
                  <td className="tbl-cell hidden sm:table-cell" />
                  <td className="tbl-cell hidden sm:table-cell" />
                  <td className={cn('tbl-cell text-end text-small tabular-nums', l.status !== 'active' && 'line-through text-muted')} dir="ltr">
                    {amount(l.net_price ?? l.price)}
                  </td>
                </tr>
              ))}
            </Fragment>
          ) : (
            <tr key={s.id} className="tbl-row">
              <td className="tbl-cell text-small text-muted tabular-nums align-top">{when(s)}</td>
              <td className={cn('tbl-cell', s.status !== 'active' && 'text-muted')}>
                <span className="flex items-center gap-2 flex-wrap">
                  <span className={cn('font-semibold text-primary', s.status === 'undone' && 'line-through text-muted')}>{s.product_title}</span>
                  {(s.status !== 'active' || s.exchange_of_sale_number) && <Badge sale={s} />}
                </span>
                {s.variant_title && <span className="block text-small text-muted">{s.variant_title}</span>}
                <span className="sm:hidden block text-small text-muted">{s.seller_name}{s.method ? ` · ${t(`bazar.method.${s.method}`)}` : ''}</span>
              </td>
              <td className="tbl-cell text-small text-muted hidden sm:table-cell">{s.seller_name}</td>
              <td className="tbl-cell hidden sm:table-cell">{paid(s)}</td>
              <td className={cn('tbl-cell text-end font-bold tabular-nums', s.status !== 'active' && 'line-through text-muted')} dir="ltr">
                {amount(s.price)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SellingBest({ report }: { report: BazarReport }) {
  const { t } = useTranslation()
  return (
    <Card testId="bazar-selling-best" title={t('bazar.r3.today.sellingBest')}
      end={<span className="text-small text-muted">{t('bazar.r3.today.wholeBazarLower')}</span>}>
      <ul className="flex flex-col gap-3">
        {report.top_products.slice(0, 5).map(p => (
          <li key={p.product_title} className="flex flex-col gap-1">
            <span className="flex justify-between gap-3">
              <span className="font-semibold text-primary truncate">{p.product_title}</span>
              <span dir="ltr" className="font-semibold text-primary tabular-nums">{amount(p.revenue)}</span>
            </span>
            <span aria-hidden className="h-1.5 rounded-sm bg-charcoal overflow-hidden">
              <span className="block h-full bg-grey-900" style={{ width: `${p.sent ? Math.min(100, (p.sold / p.sent) * 100) : 0}%` }} />
            </span>
            <span className="text-small text-muted">{t('bazar.r3.today.soldOf', { sold: p.sold, sent: p.sent })}</span>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function SoldToday({ today, onInvite }: { today: BazarTodaySummary; onInvite: () => void }) {
  const { t } = useTranslation()
  const me = currentUserId()
  return (
    <Card testId="bazar-sold-today" title={t('bazar.r1.overview.soldToday')}
      end={<button type="button" onClick={onInvite} className="text-small font-semibold text-trace-blue">{t('bazar.r3.today.invite')}</button>}>
      {today.per_seller.length === 0 ? <p className="text-body text-muted">{t('bazar.r1.overview.noSalesToday')}</p> : (
        <ul className="flex flex-col gap-3">
          {today.per_seller.map(s => (
            <li key={s.seller_id} className="flex items-center gap-3">
              <span aria-hidden className="w-8 h-8 rounded-lg bg-trace-blue/10 text-trace-blue text-small font-bold flex items-center justify-center shrink-0">
                {(s.seller_name || '?').trim().charAt(0).toUpperCase()}
              </span>
              <span className="flex flex-col min-w-0 flex-1">
                <span className="font-semibold text-primary truncate">{s.seller_name}{s.seller_id === me ? ` ${t('bazar.r1.overview.you')}` : ''}</span>
                <span className="text-small text-muted">
                  {t('bazar.r1.overview.sellerSales', { count: s.sale_count })} · <span dir="ltr" className="tabular-nums">{amount(s.total)}</span>
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function PhoneCard({ ev }: { ev: BazarEventDetail }) {
  const { t } = useTranslation()
  const sellUrl = `${window.location.origin}/bazar/${ev.id}/sell`
  return (
    <aside className="card p-4 flex items-center gap-3.5 min-w-0" data-testid="bazar-open-on-phone">
      <div className="rounded-lg border border-line p-1.5 bg-white shrink-0"><QrCode value={sellUrl} size={72} /></div>
      <div className="flex flex-col gap-1 min-w-0">
        <span className="font-semibold text-primary inline-flex items-center gap-1.5"><Smartphone size={16} aria-hidden />{t('bazar.live.phoneTitle')}</span>
        <span className="text-small text-muted">{t('bazar.live.phoneBody')}</span>
      </div>
    </aside>
  )
}

/** The sale's own status tag (returned / undone / exchange) — the existing SaleBadge, squared (6px). */
function Badge({ sale }: { sale: Parameters<typeof SaleBadge>[0]['sale'] }) {
  return <span className="inline-flex [&>.badge]:rounded [&>.badge]:py-0.5 [&>.badge]:px-2">{<SaleBadge sale={sale} />}</span>
}

// ── Other tabs ───────────────────────────────────────────────────────────────

/** Every sale, newest first, a page at a time (the existing sales list). */
function SalesPanel({ ev }: { ev: BazarEventDetail }) {
  const { t } = useTranslation()
  const [rows, setRows] = useState<BazarSaleRow[] | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const load = useCallback((after: string | null) => {
    setLoading(true)
    listBazarSales(ev.id, 50, after)
      .then(p => { setRows(cur => (after ? [...(cur ?? []), ...p.items] : p.items)); setCursor(p.next_cursor); setFailed(false) })
      .catch(() => setFailed(true))
      .finally(() => setLoading(false))
  }, [ev.id])
  useEffect(() => { load(null) }, [load])
  return (
    <Card testId="bazar-sales-tab" title={t('bazar.r3.tabs.sales')}>
      {failed && !rows ? <p role="alert" className="text-body text-critical">{t('bazar.loadFailed')}</p>
        : !rows ? <Spinner />
        : rows.length === 0 ? <p className="text-body text-muted">{t('bazar.sell.noSales')}</p>
        : <SalesTable ev={ev} rows={rows} />}
      {cursor && (
        <button type="button" className={cn(btnSecondary, 'self-center')} onClick={() => load(cursor)} disabled={loading}
                data-testid="bazar-sales-more">{t('bazar.r3.sales.more')}</button>
      )}
    </Card>
  )
}

/** B4 — every day of the event with its money and close status; closing a day opens the Close day page. */
function DaysCard({ ev, days }: { ev: BazarEventDetail; days: BazarDays | null }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  return (
    <Card testId="bazar-days" title={t('bazar.close.days')}>
      {!days ? <Spinner /> : days.days.length === 0 ? (
        <p className="text-body text-muted">{t('bazar.close.noDays')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line">
          {[...days.days].reverse().map(d => (
            <li key={d.business_day} className="py-3 flex items-center gap-4 flex-wrap" data-testid={`day-row-${d.business_day}`}>
              <span className="flex flex-col min-w-0 flex-[1_1_160px]">
                <span className="font-semibold text-primary">{dayTitle(t, d, i18n.language)}</span>
                <span className="text-small text-muted">
                  {orderedMethods(days.payment_methods).map(m => `${t(`bazar.method.${m}`)} ${amount(toNumber(d.money.by_method[m]))}`).join(' · ')}
                </span>
              </span>
              <Money value={d.money.total} className="font-bold text-primary" />
              <DayStatusBadge day={d} />
              <button type="button" className="text-small font-semibold text-trace-blue min-h-[36px] px-1 whitespace-nowrap ms-auto"
                      onClick={() => navigate(`/bazar/${ev.id}/close-day?day=${d.business_day}`)}>
                {d.close ? t('bazar.close.closeAgain') : t('bazar.close.closeThisDay')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function PiecesPanel({ ev, left }: { ev: BazarEventDetail; left: BazarLeftovers | null }) {
  const { t } = useTranslation()
  return (
    <div className="grid gap-5 lg:grid-cols-2 items-start">
      <PiecesCard ev={ev} />
      {left && (
        <Card testId="bazar-leftovers" title={t('bazar.end.leftoversTitle')}
          end={<Link to={`/bazar/${ev.id}/end-event`} className="text-small font-semibold text-trace-blue inline-flex items-center gap-1">
            {left.transfer_status === 'closed' ? t('bazar.r3.pieces.openEnd') : t('bazar.r3.pieces.bringBack')}
            <ArrowRight size={13} aria-hidden className="rtl:-scale-x-100" /></Link>}>
          <LeftoverCounts l={left} />
        </Card>
      )}
    </div>
  )
}

function PricesPanel({ ev }: { ev: BazarEventDetail }) {
  const data = usePriceData(ev)
  return <PricesCard ev={ev} data={data} live />
}

