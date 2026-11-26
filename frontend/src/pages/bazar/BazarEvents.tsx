import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowRight, Check, ChevronRight, Plus, UserSearch } from 'lucide-react'
import {
  BazarError, currentUserId, getBazarEvent, getBazarReport, getBazarToday, listBazarDays, listBazarEvents, listBazarPrices,
  orderedMethods, type BazarDay, type BazarDays, type BazarEventDetail, type BazarEventRow, type BazarPriceRow,
  type BazarReport, type BazarStatus, type BazarTodaySummary,
} from '../../bazar/api'
import { eventDay, toNumber } from '../../bazar/format'
import { TableSkeleton, cn, type BadgeTone } from '../../components/ui'
import { cairoToday, dayCount, daysFrom, pickOverview, type OverviewState } from './r1/overviewState'
import { DateTile, Eyebrow, Tag, amount, percent, rangeLabel, weekdayShort, type TagTone } from './r1/parts'
import { BazarSetupReminderCard } from './setup/LowerThese'
import { FinishEventLink, needsFinish } from './FinishEvent'

export const STATUS_TONE: Record<BazarStatus, BadgeTone> = { draft: 'warning', live: 'success', ended: 'neutral' }

const TAG_FOR_STATUS: Record<BazarStatus, TagTone> = { draft: 'amber', live: 'live', ended: 'neutral' }

type T = (k: string, o?: Record<string, unknown>) => string

/** The event's own page: an ended event opens its report (B5). */
export function eventHref(r: Pick<BazarEventRow, 'id' | 'status'>): string {
  return r.status === 'ended' ? `/bazar/${r.id}/report` : `/bazar/${r.id}`
}

/**
 * An optional read for the hero (Bazar R1, decision 2): undefined while loading, null when it failed. A failed or
 * slow read never blocks the page — the hero just shows the list-row fields it already has.
 */
function useOptional<V>(key: string, load: () => Promise<V>, valid: (v: unknown) => boolean): V | null | undefined {
  const [state, setState] = useState<{ key: string; value: V | null } | null>(null)
  useEffect(() => {
    let cancelled = false
    // An answer of the wrong shape counts as a failed read: the hero falls back to the list-row fields.
    load().then(v => { if (!cancelled) setState({ key, value: valid(v) ? v : null }) },
                () => { if (!cancelled) setState({ key, value: null }) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state && state.key === key ? state.value : undefined
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const validDetail = (v: unknown) => isObj(v) && isObj(v.today) && isObj(v.counts)
const validDays = (v: unknown) => isObj(v) && Array.isArray(v.days)
const validToday = (v: unknown) => isObj(v) && Array.isArray(v.per_seller)
const validPrices = (v: unknown) => Array.isArray(v)
const validReport = (v: unknown) => isObj(v) && isObj(v.money_not_received) && 'revenue' in v

/** Bazar POS — /bazar: the overview (R1 restyle). Owner / manager, in-shell. */
export default function BazarEvents() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [rows, setRows] = useState<BazarEventRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listBazarEvents()
      .then(setRows)
      .catch(e => {
        if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
        setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed'))
      })
  }, [navigate, t, i18n.language])

  const state = rows ? pickOverview(rows) : null

  return (
    <div className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-events" data-state={state?.kind}>
      <div className="flex items-end justify-between gap-4 flex-wrap">
        <div className="flex flex-col gap-1 min-w-0">
          <h1 className="text-[28px] leading-9 sm:text-[32px] sm:leading-10 font-bold tracking-[-0.02em] text-primary">{t('bazar.title')}</h1>
          <p className="hidden sm:block text-body text-muted">{t('bazar.r1.overview.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
        <Link to="/bazar/customers" data-testid="bazar-customer-details-link"
          className="inline-flex items-center gap-1.5 h-[42px] px-4 rounded-[10px] border border-line text-primary hover:bg-elevated font-semibold text-body">
          <UserSearch size={16} aria-hidden />
          <span>{t('bazar.customerData.link')}</span>
        </Link>
        <Link to="/bazar/new" aria-label={t('bazar.r1.overview.newBazar')}
          className="inline-flex items-center gap-1.5 h-[42px] px-4 sm:px-[18px] rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover text-white font-semibold text-body">
          <Plus size={16} aria-hidden />
          <span className="hidden sm:inline">{t('bazar.r1.overview.newBazar')}</span>
          <span className="sm:hidden">{t('bazar.r1.overview.newShort')}</span>
        </Link>
        </div>
      </div>

      {/* B9b — "Lower these" reminder until Done (only while the guided setup is on). */}
      <BazarSetupReminderCard />
      {error && <p role="alert" className="text-body text-critical">{error}</p>}
      {!rows && !error && <TableSkeleton rows={3} cols={4} />}
      {state && <Overview state={state} />}
    </div>
  )
}

function Overview({ state }: { state: OverviewState }) {
  if (state.kind === 'empty') return <FirstTime />
  return (
    <>
      {state.kind === 'live' && <LiveHero ev={state.hero} />}
      {state.kind === 'live' && state.alsoLive.length > 0 && <AlsoLive rows={state.alsoLive} />}
      {state.kind === 'upcoming' && <NextHero ev={state.hero} lastEnded={state.lastEnded} />}
      {state.kind === 'past' && <AfterHero ev={state.hero} first={state.past.length === 1} />}
      {state.kind !== 'past' && state.upcoming.length > 0 && <ComingUp rows={state.upcoming} />}
      {state.past.length > 0 && <PastList rows={state.past} />}
    </>
  )
}

// ── Shared hero pieces ───────────────────────────────────────────────────────

function HeroShell({ children, testId }: { children: ReactNode; testId: string }) {
  return (
    <section data-testid={testId} className="bg-grey-900 text-[#F2F4F7] rounded-[20px] p-4 sm:p-6 flex flex-wrap gap-6">
      {children}
    </section>
  )
}

function HeroMeta({ ev }: { ev: BazarEventRow }) {
  const { i18n } = useTranslation()
  return (
    <span className="text-[13px] leading-5 text-sidebar-text">
      {ev.location_name} · <span className="tabular-nums">{rangeLabel(ev.starts_on, ev.ends_on, i18n.language)}</span>
    </span>
  )
}

function HeroName({ ev }: { ev: BazarEventRow }) {
  return (
    <h2 className="m-0 text-[22px] leading-7 sm:text-[30px] sm:leading-9 font-bold tracking-[-0.02em] min-w-0">
      <Link to={eventHref(ev)} className="text-inherit hover:underline underline-offset-4" data-testid="bazar-hero-name">{ev.name}</Link>
    </h2>
  )
}

/** The big money number: "18,920 EGP". */
function BigMoney({ label, value, testId }: { label: string; value: string | number; testId: string }) {
  return (
    <span className="flex flex-col">
      <span className="text-small text-sidebar-text">{label}</span>
      <span dir="ltr" data-testid={testId}
        className="text-[36px] leading-[42px] sm:text-[44px] sm:leading-[48px] font-bold tracking-[-0.03em] tabular-nums text-start">
        {amount(value)}<span className="text-[18px] font-semibold text-sidebar-text"> EGP</span>
      </span>
    </span>
  )
}

function Stat({ value, label, bad, testId }: { value: string; label: string; bad?: boolean; testId?: string }) {
  return (
    <span className="flex flex-col min-w-0" data-testid={testId}>
      <span dir="ltr" className={cn('text-[22px] leading-7 font-bold tabular-nums text-start', bad && 'text-[#FCA5A5]')}>{value}</span>
      <span className="text-small text-sidebar-text">{label}</span>
    </span>
  )
}

function StatRow({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-3 sm:grid-cols-4 gap-3 pt-3.5 border-t border-grey-700">{children}</div>
}

function Panel({ children, testId, className }: { children: ReactNode; testId?: string; className?: string }) {
  return (
    <div data-testid={testId}
      className={cn('bg-white/[0.04] border border-grey-700 rounded-[14px] p-3.5 flex flex-col gap-2.5', className)}>
      {children}
    </div>
  )
}

function HeroButton({ to, children, testId }: { to: string; children: ReactNode; testId?: string }) {
  return (
    <Link to={to} data-testid={testId}
      className="h-[46px] px-5 rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover text-white font-semibold flex items-center justify-center gap-2">
      {children}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />
    </Link>
  )
}

function MiniStat({ value, label, bad }: { value: string; label: string; bad?: boolean }) {
  return (
    <span className="flex flex-col min-w-0">
      <span dir="ltr" className={cn('text-body font-bold tabular-nums text-start', bad && 'text-[#FCA5A5]')}>{value}</span>
      <span className="text-[11px] leading-4 text-sidebar-text">{label}</span>
    </span>
  )
}

// ── Live ─────────────────────────────────────────────────────────────────────

function LiveHero({ ev }: { ev: BazarEventRow }) {
  const { t } = useTranslation()
  // Decision 2: the live hero's three reads, in parallel; each one is optional.
  const detail = useOptional<BazarEventDetail>(`detail:${ev.id}`, () => getBazarEvent(ev.id), validDetail)
  const days = useOptional<BazarDays>(`days:${ev.id}`, () => listBazarDays(ev.id), validDays)
  const today = useOptional<BazarTodaySummary>(`today:${ev.id}`, () => getBazarToday(ev.id), validToday)
  const { day, count } = eventDay(ev.starts_on, ev.ends_on, cairoToday())
  const inRange = day >= 1 && day <= count

  return (
    <HeroShell testId="bazar-hero-live">
      <div className="flex-[999_1_420px] min-w-0 flex flex-col gap-3.5">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <Tag tone="live" dark dot testId="bazar-hero-tag">
            {inRange ? t('bazar.r1.overview.liveNow', { day, count }) : t('bazar.r1.overview.liveNowPlain')}
          </Tag>
          <span className="hidden sm:inline"><HeroMeta ev={ev} /></span>
        </div>
        <HeroName ev={ev} />
        {detail && <BigMoney label={t('bazar.r1.overview.todaySoFar')} value={detail.today.total} testId="bazar-hero-today" />}
        {detail && (
          <StatRow>
            <Stat value={String(detail.today.sales)} label={t('bazar.r1.overview.salesToday', { count: detail.today.sales })}
                  testId="bazar-hero-sales-today" />
            <Stat value={String(detail.counts.at_bazar)} label={t('bazar.r1.overview.atBazar')} />
            {detail.totals && <Stat value={amount(detail.totals.total)} label={t('bazar.r1.overview.eventTotal')} />}
          </StatRow>
        )}
        <div className="sm:hidden"><HeroButton to={`/bazar/${ev.id}`}>{t('bazar.r1.overview.openEvent')}</HeroButton></div>
      </div>
      <div className="hidden sm:flex flex-[1_1_300px] min-w-0 flex-col gap-3">
        {days && days.days.length > 0 && <DaysPanel days={days} />}
        {today && <SoldTodayPanel today={today} />}
        <HeroButton to={`/bazar/${ev.id}`} testId="bazar-hero-open">{t('bazar.r1.overview.openEvent')}</HeroButton>
      </div>
    </HeroShell>
  )
}

function dayState(d: BazarDay, today: string): { key: string; bar: string; text: string } {
  if (d.status === 'closed_balanced') return { key: 'closed_balanced', bar: 'bg-[#22C55E]', text: 'text-[#86EFAC]' }
  if (d.status === 'closed_short') return { key: 'closed_short', bar: 'bg-warning', text: 'text-[#FCD34D]' }
  if (d.status === 'changed_after_close') return { key: 'changed_after_close', bar: 'bg-warning', text: 'text-[#FCD34D]' }
  if (d.is_today) return { key: 'todayOpen', bar: 'bg-trace-blue', text: 'text-[#93B4FF]' }
  if (d.business_day > today) return { key: 'notYet', bar: 'bg-grey-700', text: 'text-sidebar-text' }
  return { key: 'notClosed', bar: 'bg-warning', text: 'text-[#FCD34D]' }
}

function DaysPanel({ days }: { days: BazarDays }) {
  const { t, i18n } = useTranslation()
  return (
    <Panel testId="bazar-hero-days">
      <Eyebrow dark>{t('bazar.r1.overview.days')}</Eyebrow>
      <div className="grid gap-1.5" style={{ gridTemplateColumns: `repeat(${Math.min(days.days.length, 4)}, minmax(0, 1fr))` }}>
        {days.days.map(d => {
          const s = dayState(d, days.today)
          return (
            <div key={d.business_day} className="flex flex-col gap-1 min-w-0" data-testid={`hero-day-${d.business_day}`}>
              <div aria-hidden className={cn('h-1.5 rounded-sm', s.bar)} />
              <span className={cn('text-small truncate', d.is_today ? 'text-[#F2F4F7] font-semibold' : 'text-grey-100')}>
                {weekdayShort(d.business_day, i18n.language)}
                {s.key !== 'notYet' && <> · <span dir="ltr" className="tabular-nums">{amount(d.money.total)}</span></>}
              </span>
              <span className={cn('text-[11px] leading-4', s.text)}>{t(`bazar.r1.overview.day.${s.key}`)}</span>
            </div>
          )
        })}
      </div>
    </Panel>
  )
}

const AVATAR = ['bg-trace-blue', 'bg-[#7C3AED]', 'bg-info', 'bg-[#16A34A]', 'bg-warning']

/** "Sold today" — who has sold today (GET …/today per_seller); not who is selling right now. */
function SoldTodayPanel({ today }: { today: BazarTodaySummary }) {
  const { t } = useTranslation()
  const me = currentUserId()
  return (
    <Panel testId="bazar-hero-sold-today">
      <Eyebrow dark>{t('bazar.r1.overview.soldToday')}</Eyebrow>
      {today.per_seller.length === 0
        ? <span className="text-[13px] text-sidebar-text">{t('bazar.r1.overview.noSalesToday')}</span>
        : today.per_seller.map((s, i) => (
          <div key={s.seller_id} className="flex items-center gap-2.5">
            <span aria-hidden className={cn('w-7 h-7 rounded-lg text-white text-small font-bold flex items-center justify-center shrink-0',
              AVATAR[i % AVATAR.length])}>{(s.seller_name || '?').trim().charAt(0).toUpperCase()}</span>
            <span className="flex-1 min-w-0 truncate text-[13px]">
              {s.seller_name}{s.seller_id === me ? ` ${t('bazar.r1.overview.you')}` : ''}
            </span>
            <span className="text-[13px] text-grey-100 whitespace-nowrap">
              {t('bazar.r1.overview.sellerSales', { count: s.sale_count })} · <span dir="ltr" className="tabular-nums">{amount(s.total)}</span>
            </span>
          </div>
        ))}
    </Panel>
  )
}

function AlsoLive({ rows }: { rows: BazarEventRow[] }) {
  const { t, i18n } = useTranslation()
  return (
    <section className="flex flex-col gap-2.5" data-testid="bazar-also-live">
      <Eyebrow>{t('bazar.r1.overview.alsoLive')}</Eyebrow>
      <ul className="card p-0 divide-y divide-line overflow-hidden">
        {rows.map(r => (
          <li key={r.id} className="flex items-center gap-4 px-4 sm:px-[18px] py-3">
            <DateTile ymd={r.starts_on} lang={i18n.language} size="sm" />
            <span className="flex flex-col min-w-0 me-auto">
              <Link to={eventHref(r)} className="font-semibold text-primary hover:underline truncate">{r.name}</Link>
              <span className="text-small text-muted truncate">{r.location_name}</span>
            </span>
            <Link to={eventHref(r)} aria-label={`${t('bazar.r1.overview.open')} ${r.name}`}
              className="text-small font-semibold text-trace-blue whitespace-nowrap">
              {t('bazar.r1.overview.open')}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

// ── Next bazar (upcoming draft) ──────────────────────────────────────────────

/** "in 16 days" / "starts today" / "date passed · 3 days ago" — never a negative "in N days". */
export function whenText(t: T, startsOn: string, today: string): string {
  const n = daysFrom(today, startsOn)
  if (n > 0) return t('bazar.r1.overview.whenIn', { count: n })
  if (n === 0) return t('bazar.r1.overview.whenToday')
  return t('bazar.r1.overview.whenPassed', { count: -n })
}

interface Step { key: 'created' | 'prices' | 'sent' | 'started'; done: boolean; sub: string }

/** The four setup steps we can already compute (decision 3). Prices need …/prices + the detail; both optional. */
export function setupSteps(t: T, lang: string, ev: BazarEventRow,
                           detail: BazarEventDetail | null | undefined, prices: BazarPriceRow[] | null | undefined): Step[] {
  const methods = detail ? orderedMethods(detail.payment_methods).map(m => t(`bazar.method.${m}`)).join(', ') : ''
  const dates = rangeLabel(ev.starts_on, ev.ends_on, lang)
  const sent = ev.transfer_status !== 'preparing'
  let pricesStep: Step
  if (prices === undefined || detail === undefined) {
    pricesStep = { key: 'prices', done: false, sub: '' }
  } else if (prices === null || detail === null) {
    pricesStep = { key: 'prices', done: false, sub: t('bazar.r1.overview.sub.unknown') }
  } else if (prices.length === 0) {
    pricesStep = { key: 'prices', done: false, sub: t('bazar.r1.overview.sub.noPieces') }
  } else {
    const missing = prices.filter(p => p.price == null).length
    const custom = prices.filter(p => p.source === 'override').length
    const base = detail.discount_pct != null
      ? t('bazar.r1.overview.sub.rule', { pct: toNumber(detail.discount_pct) })
      : t('bazar.r1.overview.sub.shopify')
    pricesStep = missing > 0
      ? { key: 'prices', done: false, sub: t('bazar.r1.overview.sub.missing', { count: missing }) }
      : { key: 'prices', done: true, sub: custom > 0 ? `${base} · ${t('bazar.r1.overview.sub.custom', { count: custom })}` : base }
  }
  const pieces = detail?.counts.sent
  return [
    { key: 'created', done: true, sub: methods ? `${dates} · ${methods}` : dates },
    pricesStep,
    { key: 'sent', done: sent,
      sub: pieces == null ? '' : sent ? t('bazar.r1.overview.sub.sent', { count: pieces }) : t('bazar.r1.overview.sub.scanned', { count: pieces }) },
    { key: 'started', done: false, sub: t('bazar.r1.overview.sub.starts', { date: rangeLabel(ev.starts_on, ev.starts_on, lang) }) },
  ]
}

function NextHero({ ev, lastEnded }: { ev: BazarEventRow; lastEnded: BazarEventRow | null }) {
  const { t, i18n } = useTranslation()
  // Decision 2: the upcoming hero's two reads, for the checklist; both optional.
  const detail = useOptional<BazarEventDetail>(`detail:${ev.id}`, () => getBazarEvent(ev.id), validDetail)
  const prices = useOptional<BazarPriceRow[]>(`prices:${ev.id}`, () => listBazarPrices(ev.id), validPrices)
  const steps = setupSteps(t, i18n.language, ev, detail, prices)
  const done = steps.filter(s => s.done).length

  return (
    <HeroShell testId="bazar-hero-next">
      <div className="flex-[999_1_420px] min-w-0 flex flex-col gap-4">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <Tag tone="amber" dark dot testId="bazar-hero-tag">
            {t('bazar.r1.overview.nextBazar')} · {whenText(t, ev.starts_on, cairoToday())}
          </Tag>
          <span className="hidden sm:inline"><HeroMeta ev={ev} /></span>
        </div>
        <div className="flex items-center gap-4 min-w-0">
          <DateTile ymd={ev.starts_on} lang={i18n.language} dark />
          <HeroName ev={ev} />
        </div>
        <div className="flex flex-col gap-2.5" data-testid="bazar-checklist">
          <div className="flex items-center justify-between gap-3">
            <Eyebrow dark>{t('bazar.r1.overview.gettingReady')}</Eyebrow>
            <span dir="auto" className="text-small text-sidebar-text" data-testid="bazar-checklist-count">
              {t('bazar.r1.overview.doneOf', { done, total: steps.length })}
            </span>
          </div>
          <div aria-hidden className="grid grid-cols-4 gap-1.5">
            {steps.map(s => <div key={s.key} className={cn('h-1.5 rounded-sm', s.done ? 'bg-[#22C55E]' : 'bg-grey-700')} />)}
          </div>
          <ul className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {steps.map(s => (
              <li key={s.key} data-testid={`step-${s.key}`} data-done={s.done ? 'true' : 'false'}
                className="flex items-center gap-3 p-3 rounded-[10px] border border-grey-700 bg-white/[0.02] min-w-0">
                <span aria-hidden className={cn('w-[22px] h-[22px] rounded shrink-0 flex items-center justify-center',
                  s.done ? 'bg-[#22C55E] text-grey-900' : 'border border-grey-500')}>
                  {s.done && <Check size={14} strokeWidth={3} />}
                </span>
                <span className="flex flex-col min-w-0">
                  <span className={cn('text-body font-semibold', s.done && 'line-through text-sidebar-text')}>
                    {t(`bazar.r1.overview.step.${s.key}`)}
                    <span className="sr-only"> — {s.done ? t('bazar.r1.overview.stepDone') : t('bazar.r1.overview.stepTodo')}</span>
                  </span>
                  {s.sub && <span className="text-small text-sidebar-text truncate">{s.sub}</span>}
                </span>
              </li>
            ))}
          </ul>
        </div>
        <div className="sm:hidden"><HeroButton to={`/bazar/${ev.id}`}>{t('bazar.r1.overview.continueSetup')}</HeroButton></div>
      </div>
      <div className="hidden sm:flex flex-[1_1_300px] min-w-0 flex-col gap-3">
        {lastEnded && <LastBazarPanel ev={lastEnded} />}
        <HeroButton to={`/bazar/${ev.id}`} testId="bazar-hero-continue">{t('bazar.r1.overview.continueSetup')}</HeroButton>
      </div>
    </HeroShell>
  )
}

function LastBazarPanel({ ev }: { ev: BazarEventRow }) {
  const { t, i18n } = useTranslation()
  // Decision 2: one report read for the newest ended event; optional.
  const report = useOptional<BazarReport>(`report:${ev.id}`, () => getBazarReport(ev.id), validReport)
  const notReceived = report ? toNumber(report.money_not_received.total) : 0
  return (
    <Panel testId="bazar-hero-last">
      <div className="flex items-center justify-between gap-2">
        <Eyebrow dark>{t('bazar.r1.overview.lastBazar')}</Eyebrow>
        <span className="text-[11px] text-sidebar-text truncate">
          {ev.location_name} · {t('bazar.r1.overview.endedOn', { date: rangeLabel(ev.ends_on, ev.ends_on, i18n.language) })}
        </span>
      </div>
      {report && (
        <>
          <span dir="ltr" className="text-[30px] leading-9 font-bold tabular-nums text-start" data-testid="bazar-last-revenue">
            {amount(report.revenue)}<span className="text-body font-semibold text-sidebar-text"> EGP</span>
          </span>
          <div className="grid grid-cols-3 gap-2">
            {report.sell_through != null && <MiniStat value={percent(report.sell_through)} label={t('bazar.r1.overview.soldThrough')} />}
            {report.sales != null && <MiniStat value={String(report.sales)} label={t('bazar.r1.overview.salesLabel', { count: report.sales })} />}
            {notReceived !== 0 && <MiniStat value={amount(notReceived)} label={t('bazar.r1.overview.notReceived')} bad />}
          </div>
        </>
      )}
      {needsFinish(ev.status, ev.transfer_status) && <FinishEventLink eventId={ev.id} piecesOut={ev.pieces_out} dark />}
      <Link to={`/bazar/${ev.id}/report`} className="text-small font-semibold text-[#93B4FF] hover:underline w-fit inline-flex items-center gap-1">
        {t('bazar.r1.overview.seeReport')}<ArrowRight size={13} aria-hidden className="rtl:-scale-x-100" />
      </Link>
    </Panel>
  )
}

// ── After the last bazar (past only) ─────────────────────────────────────────

function AfterHero({ ev, first }: { ev: BazarEventRow; first: boolean }) {
  const { t } = useTranslation()
  const report = useOptional<BazarReport>(`report:${ev.id}`, () => getBazarReport(ev.id), validReport)
  const notReceived = report ? toNumber(report.money_not_received.total) : 0
  return (
    <HeroShell testId="bazar-hero-past">
      <div className="flex-[999_1_420px] min-w-0 flex flex-col gap-3.5">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <Tag tone="done" dark testId="bazar-hero-tag">
            <Check size={13} strokeWidth={3} aria-hidden />
            {first ? t('bazar.r1.overview.firstDone') : t('bazar.r1.overview.lastDone')}
          </Tag>
          <span className="hidden sm:inline"><HeroMeta ev={ev} /></span>
        </div>
        <HeroName ev={ev} />
        {report && <BigMoney label={t('bazar.r1.overview.youSold')} value={report.revenue} testId="bazar-hero-revenue" />}
        {report && (
          <StatRow>
            <Stat value={String(report.pieces_sold)} label={t('bazar.r1.overview.piecesSold')} />
            {report.sell_through != null && <Stat value={percent(report.sell_through)} label={t('bazar.r1.overview.soldThrough')} />}
            <Stat value={String(report.customers_saved)} label={t('bazar.r1.overview.customers')} />
            {notReceived !== 0 && <Stat value={amount(notReceived)} label={t('bazar.r1.overview.notReceived')} bad />}
          </StatRow>
        )}
        {needsFinish(ev.status, ev.transfer_status) && <FinishEventLink eventId={ev.id} piecesOut={ev.pieces_out} dark />}
        <Link to={`/bazar/${ev.id}/report`} className="text-body font-semibold text-[#93B4FF] hover:underline w-fit inline-flex items-center gap-1">
          {t('bazar.r1.overview.seeFullReport')}<ArrowRight size={14} aria-hidden className="rtl:-scale-x-100" />
        </Link>
      </div>
      <div className="flex-[1_1_300px] min-w-0">
        <Panel className="p-4 gap-3">
          <Eyebrow dark>{t('bazar.r1.overview.planEyebrow')}</Eyebrow>
          <span className="text-[18px] leading-6 font-bold">{t('bazar.r1.overview.planHeading')}</span>
          <span className="text-[13px] text-sidebar-text">{t('bazar.r1.overview.planBody')}</span>
          <Link to="/bazar/new" data-testid="bazar-plan-next"
            className="mt-1 h-[46px] px-5 rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover text-white font-semibold flex items-center justify-center gap-1.5">
            <Plus size={16} aria-hidden />{t('bazar.r1.overview.planButton')}
          </Link>
        </Panel>
      </div>
    </HeroShell>
  )
}

// ── First time ───────────────────────────────────────────────────────────────

function FirstTime() {
  const { t } = useTranslation()
  return (
    <section className="bg-grey-900 text-[#F2F4F7] rounded-[20px] p-5 sm:p-8 flex flex-wrap gap-6 sm:gap-8" data-testid="bazar-empty">
      <div className="flex-[999_1_380px] min-w-0 flex flex-col gap-3.5">
        <span className="text-small font-bold uppercase tracking-[0.06em] text-[#93B4FF]">{t('bazar.r1.empty.eyebrow')}</span>
        <h2 className="m-0 text-[26px] leading-8 sm:text-[32px] sm:leading-[38px] font-bold tracking-[-0.02em]">{t('bazar.r1.empty.title')}</h2>
        <p className="text-body-lg text-sidebar-text">{t('bazar.r1.empty.body')}</p>
        <Link to="/bazar/new" data-testid="bazar-plan-first"
          className="mt-1 w-fit h-[46px] px-5 rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover text-white font-semibold flex items-center gap-1.5">
          <Plus size={16} aria-hidden />{t('bazar.r1.empty.button')}
        </Link>
      </div>
      <ol className="flex-[1_1_360px] min-w-0 grid grid-cols-1 sm:grid-cols-2 gap-2.5">
        {[1, 2, 3, 4].map(n => (
          <li key={n} className="border border-grey-700 bg-white/[0.02] rounded-xl p-3.5 flex flex-col gap-1.5">
            <span aria-hidden dir="ltr" className="w-6 h-6 rounded bg-white/[0.06] text-[#93B4FF] text-small font-bold flex items-center justify-center">{n}</span>
            <span className="text-body font-semibold">{t(`bazar.r1.empty.s${n}t`)}</span>
            <span className="text-small text-sidebar-text">{t(`bazar.r1.empty.s${n}d`)}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}

// ── Lists ────────────────────────────────────────────────────────────────────

/** Other upcoming drafts — date, name, when; no checklist (its reads are only made for the hero, decision 2). */
function ComingUp({ rows }: { rows: BazarEventRow[] }) {
  const { t, i18n } = useTranslation()
  const today = cairoToday()
  return (
    <section className="flex flex-col gap-2.5" data-testid="bazar-coming-up">
      <Eyebrow>{t('bazar.r1.overview.comingUp')}</Eyebrow>
      {rows.map(r => (
        <div key={r.id} className="card p-4 sm:p-[18px] flex items-center gap-4 flex-wrap">
          <DateTile ymd={r.starts_on} lang={i18n.language} />
          <span className="flex flex-col gap-0.5 min-w-0 flex-1">
            <span className="flex items-center gap-2.5 flex-wrap">
              <Link to={eventHref(r)} className="text-body-lg font-semibold text-primary hover:underline">{r.name}</Link>
              <Tag tone="amber">{whenText(t, r.starts_on, today)}</Tag>
            </span>
            <span className="text-body text-muted">
              <span className="tabular-nums">{rangeLabel(r.starts_on, r.ends_on, i18n.language)}</span> · {r.location_name}
            </span>
          </span>
          <Link to={`/bazar/${r.id}`} aria-label={`${t('bazar.r1.overview.continueSetup')} — ${r.name}`}
            className="hidden sm:flex h-10 px-4 rounded-[10px] bg-grey-900 hover:bg-grey-800 text-white font-semibold text-body items-center">
            {t('bazar.r1.overview.continueSetup')}
          </Link>
        </div>
      ))}
    </section>
  )
}

function PastList({ rows }: { rows: BazarEventRow[] }) {
  const { t, i18n } = useTranslation()
  return (
    <section className="card p-0 overflow-hidden" data-testid="bazar-event-list">
      <h2 className="px-4 sm:px-[18px] py-4 border-b border-line text-body-lg font-semibold text-primary">
        {t('bazar.r1.overview.pastBazars')}
      </h2>
      <ul className="divide-y divide-line">
        {rows.map(r => (
          <li key={r.id} className="relative flex items-center gap-4 px-4 sm:px-[18px] py-3.5 hover:bg-charcoal/60 transition-colors">
            <DateTile ymd={r.starts_on} lang={i18n.language} />
            <span className="flex flex-col gap-0.5 min-w-0 me-auto">
              {/* The name is the row's link (B5: an ended event opens its report); it covers the whole row. */}
              <Link to={eventHref(r)} className="font-semibold text-primary truncate after:absolute after:inset-0">{r.name}</Link>
              <span className="text-small text-muted truncate">
                {t('bazar.r1.overview.dayCount', { count: dayCount(r.starts_on, r.ends_on) })} · {r.location_name}
              </span>
              {needsFinish(r.status, r.transfer_status) && <FinishEventLink eventId={r.id} piecesOut={r.pieces_out} />}
            </span>
            <span className="hidden sm:inline-flex"><Tag tone={TAG_FOR_STATUS[r.status]}>{t(`bazar.status.${r.status}`)}</Tag></span>
            <ChevronRight size={18} aria-hidden className="text-muted shrink-0 rtl:-scale-x-100" />
          </li>
        ))}
      </ul>
    </section>
  )
}
