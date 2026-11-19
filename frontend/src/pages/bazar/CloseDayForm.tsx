import { useEffect, useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Loader2 } from 'lucide-react'
import {
  closeBazarDayOnce, previewBazarClose,
  type BazarCloseEntry, type BazarClosePreview, type BazarCloseResult, type BazarDay, type PaymentMethod,
} from '../../bazar/api'
import { formatMoney, shortDate, toNumber } from '../../bazar/format'
import { useRetryingCommand } from '../../bazar/useRetryingCommand'
import { CommandStatus } from './phone/ReturnParts'
import { cn } from '../../components/ui'
import { amount } from './r1/parts'
import { Chip, StepCard, type ChipTone } from './r2/parts'

// Bazar POS B4 — the close-the-day form, shared by the phone screen and the desktop event page: cash in the drawer,
// card machine settlement, InstaPay tick, then the drawer (opening float on the bazar's first close, kept for
// tomorrow, take-home), a live preview from the server (GET close-preview — the one calculation), the red
// "X EGP not received" box with its split, money over shown as over, then confirm. Sent like a sale: the same request
// id on every retry, "closed" only once the server confirms.
// R2 restyle (design/bazar-v2 V2_CloseDay): Cash first (the cursor starts there), Card, InstaPay, the dark difference
// card, then the Drawer block. Same state, same request, same test ids.

/** "1,234.5" / Arabic-Indic digits → 1234.5; empty → null; anything else → NaN. */
export function parseAmount(raw: string): number | null {
  const s = raw.replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660)).replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 0x06F0))
    .replace(/[,\s٬]/g, '').replace('٫', '.')
  if (s === '') return null
  return /^\d+(\.\d{1,2})?$/.test(s) ? Number(s) : NaN
}

const plain = (v: string | number) => formatMoney(v).replace(/^EGP /, '')

export function dayTitle(t: (k: string, o?: Record<string, unknown>) => string, day: BazarDay, lang: string) {
  return day.is_today ? t('bazar.close.todayLabel', { date: shortDate(day.business_day, lang) })
                      : t('bazar.close.dayLabel', { n: day.day_number, date: shortDate(day.business_day, lang) })
}

function methodRow(r: BazarCloseResult, m: PaymentMethod) {
  return r.methods.find(x => x.method === m)
}

const STATE_TONE: Record<string, ChipTone> = { even: 'good', short: 'warn', over: 'warn', not_received: 'bad' }

/** "Matches" / "Short 40" / "Over 10" for one method row of the preview. */
function StateChip({ result, method, testId }: { result: BazarCloseResult | null; method: PaymentMethod; testId?: string }) {
  const { t } = useTranslation()
  const row = result ? methodRow(result, method) : undefined
  if (!row) return null
  return (
    <Chip tone={STATE_TONE[row.state] ?? 'neutral'} testId={testId}>
      {row.state === 'even' && <Check size={13} strokeWidth={3} aria-hidden />}
      {row.state === 'even' ? t('bazar.close.state.even')
        : t(`bazar.close.state.${row.state}`, { amount: plain(Math.abs(toNumber(row.difference))) })}
    </Chip>
  )
}

/** The grey "Should be" box beside an input. */
function ShouldBe({ value, extra }: { value: string | number | null | undefined; extra?: string }) {
  const { t } = useTranslation()
  return (
    <div className="bg-charcoal border border-line rounded-[10px] px-3.5 py-2.5 flex flex-col justify-center min-w-0">
      <span className="text-small text-muted">{t('bazar.r2.close.shouldBe')}</span>
      <span dir="ltr" className="text-h4 font-bold text-primary tabular-nums text-start">{value == null ? '—' : amount(value)}</span>
      {extra && <span className="text-small text-muted">{extra}</span>}
    </div>
  )
}

export function CloseDayForm({ eventId, day, methods, onClosed, compact }: {
  eventId: string
  day: BazarDay
  methods: PaymentMethod[]
  onClosed: (day: BazarDay) => void
  /** Desktop (the event page's modal): a slightly smaller field. */
  compact?: boolean
}) {
  const { t } = useTranslation()
  const ids = useId()
  const floatDefault = day.opening_float_default
  const askFloat = floatDefault.source === 'none'
  const [editFloat, setEditFloat] = useState(askFloat)
  const [floatStr, setFloatStr] = useState(askFloat ? '' : String(toNumber(floatDefault.amount)))
  const [cashStr, setCashStr] = useState('')
  const [cardStr, setCardStr] = useState('')
  const [insta, setInsta] = useState(true)
  const [keptStr, setKeptStr] = useState<string | null>(null)   // null = follow the opening float
  const [note, setNote] = useState('')
  const [preview, setPreview] = useState<BazarClosePreview | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const cmd = useRetryingCommand<BazarDay>()

  const cardOn = methods.includes('card')
  const instaOn = methods.includes('instapay')
  const flt = parseAmount(floatStr)
  const cash = parseAmount(cashStr)
  const card = cardOn ? parseAmount(cardStr) : 0
  const kept = keptStr == null ? flt : parseAmount(keptStr)
  const bad = [flt, cash, card, kept].some(v => v != null && Number.isNaN(v))

  const entry: BazarCloseEntry = useMemo(() => ({
    cash: cash != null && !Number.isNaN(cash) ? cash : null,
    card: card != null && !Number.isNaN(card) ? card : null,
    instapay: instaOn ? insta : true,
    kept: kept != null && !Number.isNaN(kept) ? kept : null,
    openingFloat: editFloat && flt != null && !Number.isNaN(flt) ? flt : null,
  }), [cash, card, insta, instaOn, kept, editFloat, flt])

  // The preview follows what's typed (debounced); the last answer stays on screen while the next one loads.
  useEffect(() => {
    if (bad) return
    let cancelled = false
    setPreviewing(true)
    const timer = window.setTimeout(() => {
      previewBazarClose(eventId, day.business_day, entry)
        .then(p => { if (!cancelled) setPreview(p) })
        .catch(() => {})
        .finally(() => { if (!cancelled) setPreviewing(false) })
    }, 300)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [eventId, day.business_day, entry, bad])

  useEffect(() => {
    if (cmd.state.kind === 'done') onClosed(cmd.state.result)
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  const keptTooHigh = preview?.problems.includes('kept_more_than_counted') ?? false
  const floatMissing = editFloat && flt == null
  const ready = !bad && cash != null && (!cardOn || card != null) && !floatMissing && !keptTooHigh && !!preview && !previewing
  const again = day.close != null
  const result = preview?.result ?? null
  const floatInResult = result ? toNumber(result.opening_float) : 0

  function confirm() {
    if (!ready) return
    const n = note.trim() || null
    cmd.run((requestId, timeoutMs) => closeBazarDayOnce(eventId, day.business_day, entry, n, requestId, timeoutMs))
  }

  const field = cn('input w-full font-bold tabular-nums border-[1.5px] focus:border-trace-blue', compact ? 'h-12 text-h4' : 'h-14 text-h3')
  const inputLabel = 'text-small font-semibold text-primary'

  return (
    <div className="flex flex-col gap-3" data-testid="close-day-form">
      {/* 1 · Cash — first, and the cursor starts here (R2 decision 2). */}
      <StepCard n={1} title={t('bazar.close.cash')} sub={t('bazar.close.cashHint')}
        end={cash != null && !Number.isNaN(cash) ? <StateChip result={result} method="cash" testId="close-chip-cash" /> : undefined}
        testId="close-section-cash">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <ShouldBe value={result ? methodRow(result, 'cash')?.expected : null}
            extra={floatInResult > 0 ? t('bazar.r2.close.includesFloat', { amount: amount(floatInResult) }) : undefined} />
          <label className="flex flex-col gap-1">
            <span className={inputLabel}>{t('bazar.r2.close.countedCash')}</span>
            <input className={field} inputMode="decimal" dir="ltr" value={cashStr} autoFocus
                   onChange={e => setCashStr(e.target.value)} disabled={cmd.busy} data-testid="close-cash"
                   aria-invalid={Number.isNaN(cash ?? 0) || undefined} />
          </label>
        </div>
      </StepCard>

      {cardOn && (
        <StepCard n={2} title={t('bazar.r2.close.cardTitle')} sub={t('bazar.close.cardHint')}
          end={card != null && !Number.isNaN(card) ? <StateChip result={result} method="card" testId="close-chip-card" /> : undefined}
          testId="close-section-card">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <ShouldBe value={result ? methodRow(result, 'card')?.expected : null} />
            <label className="flex flex-col gap-1">
              <span className={inputLabel}>{t('bazar.close.card')}</span>
              <input className={field} inputMode="decimal" dir="ltr" value={cardStr}
                     onChange={e => setCardStr(e.target.value)} disabled={cmd.busy} data-testid="close-card"
                     aria-invalid={Number.isNaN(card ?? 0) || undefined} />
            </label>
          </div>
        </StepCard>
      )}

      {instaOn && (
        <StepCard n={cardOn ? 3 : 2} title={t('bazar.r2.close.instapayTitle')} sub={t('bazar.close.instapayHint')}
          end={<StateChip result={result} method="instapay" testId="close-chip-instapay" />} testId="close-section-instapay">
          <label className="relative flex items-center gap-3 rounded-[10px] border border-line bg-charcoal px-3.5 py-3 cursor-pointer">
            <input type="checkbox" className="w-5 h-5 rounded accent-trace-blue shrink-0" checked={insta} disabled={cmd.busy}
                   onChange={e => setInsta(e.target.checked)} data-testid="close-instapay" />
            <span className="font-semibold text-primary flex-1">{t('bazar.close.instapay')}</span>
            {result && (
              <span dir="ltr" className="font-bold tabular-nums text-primary">{amount(methodRow(result, 'instapay')?.expected ?? 0)}</span>
            )}
          </label>
        </StepCard>
      )}

      <CloseResultView result={result} loading={previewing && !preview} day={day} hideTakeHome />

      {/* The drawer — the opening float (first close only) and what stays for tomorrow. */}
      <section className="card p-4 flex flex-col gap-3" data-testid="close-drawer">
        <span className="text-body-lg font-semibold text-primary">{t('bazar.r2.close.drawer')}</span>
        {editFloat ? (
          <label className="flex flex-col gap-1">
            <span className={inputLabel}>{t('bazar.close.float')}</span>
            <input id={`${ids}-float`} className={cn(field, 'h-12 text-body-lg')} inputMode="decimal" dir="ltr" value={floatStr}
                   onChange={e => setFloatStr(e.target.value)} disabled={cmd.busy} data-testid="close-float"
                   aria-invalid={Number.isNaN(flt ?? 0) || undefined} />
            <span className="text-small text-muted">{t('bazar.close.floatHint')}</span>
          </label>
        ) : (
          <div className="flex items-center justify-between gap-2 rounded-[10px] bg-charcoal px-3 py-2 text-small" data-testid="close-float-line">
            <span className="text-primary">
              {t('bazar.close.floatFrom', { amount: formatMoney(toNumber(floatDefault.amount)) })}
              <span className="text-muted"> · {t(floatDefault.source === 'this_day_close' ? 'bazar.close.floatFromDay' : 'bazar.close.floatFromPrevious')}</span>
            </span>
            <button type="button" className="min-h-[44px] px-2 font-semibold text-trace-blue" disabled={cmd.busy}
                    onClick={() => setEditFloat(true)}>{t('bazar.close.floatChange')}</button>
          </div>
        )}
        <label className="flex flex-col gap-1">
          <span className={inputLabel}>{t('bazar.close.kept')}</span>
          <input className={cn(field, 'h-12 text-body-lg')} inputMode="decimal" dir="ltr"
                 value={keptStr ?? (flt != null && !Number.isNaN(flt) ? String(flt) : '')}
                 onChange={e => setKeptStr(e.target.value)} disabled={cmd.busy} data-testid="close-kept"
                 aria-invalid={keptTooHigh || undefined} />
          <span className={cn('text-small', keptTooHigh ? 'text-critical-text font-semibold' : 'text-muted')}>
            {keptTooHigh ? t('bazar.close.keptTooHigh') : t('bazar.close.keptHint')}
          </span>
        </label>
        {result && <TakeHome result={result} />}
      </section>

      <label className="flex flex-col gap-1">
        <span className={inputLabel}>{t('bazar.close.note')}</span>
        <textarea className="input min-h-[72px] py-2" value={note} maxLength={500} onChange={e => setNote(e.target.value)}
                  placeholder={t('bazar.close.notePlaceholder')} disabled={cmd.busy} />
      </label>

      {floatMissing && <p className="text-small text-muted">{t('bazar.close.needFloat')}</p>}
      <CommandStatus state={cmd.state} />
      <button type="button" onClick={confirm} disabled={!ready || cmd.busy} data-testid="close-confirm"
        className="btn w-full h-[52px] rounded-xl text-body-lg font-semibold flex items-center justify-center gap-2 bg-trace-blue hover:bg-trace-blue-hover text-white disabled:opacity-60">
        {cmd.busy && <Loader2 size={16} className="animate-spin" aria-hidden />}
        {cmd.busy ? t('bazar.close.closing') : again ? t('bazar.close.confirmAgain') : t('bazar.close.confirm')}
      </button>
      <p className="text-small text-muted -mt-1">{t('bazar.r2.close.sellingGoesOn')}</p>
    </div>
  )
}

function TakeHome({ result }: { result: BazarCloseResult }) {
  const { t } = useTranslation()
  return (
    <section className="rounded-[10px] bg-charcoal px-3.5 py-2.5 flex flex-col gap-0.5" data-testid="close-take-home">
      <span className="text-body-lg font-bold text-primary tabular-nums">
        {t('bazar.close.takeHome', { amount: formatMoney(result.take_home) })}
      </span>
      <span className="text-small text-muted">{t('bazar.close.takeHomeKept', { amount: formatMoney(result.cash_kept) })}</span>
    </section>
  )
}

/**
 * The dark difference card: the day's sales / refunds counts, per method expected vs entered, the total difference,
 * the "X EGP not received" box with its split, money over, and (unless the drawer block shows it) take-home.
 */
export function CloseResultView({ result, loading, day, hideTakeHome }: {
  result: BazarCloseResult | null; loading?: boolean; day?: BazarDay; hideTakeHome?: boolean
}) {
  const { t, i18n } = useTranslation()
  if (!result) {
    return loading ? (
      <p className="flex items-center gap-2 text-small text-muted" role="status">
        <Loader2 size={14} className="animate-spin" aria-hidden />{t('bazar.close.calculating')}
      </p>
    ) : null
  }
  const notReceived = toNumber(result.not_received_total)
  const over = toNumber(result.over_total)
  const split = (['cash', 'card', 'instapay'] as const).filter(k => toNumber(result.not_received[k]) > 0)
  const expected = result.methods.reduce((s, r) => s + toNumber(r.expected), 0)
  const entered = result.methods.reduce((s, r) => s + toNumber(r.entered), 0)
  const diff = result.methods.reduce((s, r) => s + toNumber(r.difference), 0)
  const SPLIT_DOT = { cash: 'bg-warning', card: 'bg-[#22C55E]', instapay: 'bg-critical' } as const
  return (
    <section className="flex flex-col gap-3" data-testid="close-result">
      <div className="bg-grey-900 text-[#F2F4F7] rounded-2xl p-4 flex flex-col gap-2.5" data-testid="close-summary">
        {day && (
          <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-sidebar-text">
            {dayTitle(t, day, i18n.language)}
          </span>
        )}
        {day && (
          <div className="flex justify-between gap-3 text-body text-grey-100">
            <span>{t('bazar.r2.close.salesCount', { count: day.money.sales })}</span>
            <span>{t('bazar.r2.close.refundsCount', { count: day.money.returns ?? 0 })}</span>
          </div>
        )}
        <div className="flex flex-col divide-y divide-grey-700">
          {result.methods.map(row => (
            <div key={row.method} className="grid grid-cols-[1fr_auto_auto] gap-3 py-2 items-baseline" data-testid={`close-row-${row.method}`}>
              <span className="font-semibold">{t(`bazar.method.${row.method}`)}</span>
              <span className="text-end tabular-nums text-sidebar-text" dir="ltr">{plain(row.expected)}</span>
              <span className={cn('text-end tabular-nums font-semibold min-w-[96px]',
                row.state === 'even' ? 'text-[#86EFAC]' : row.state === 'over' ? 'text-[#FCD34D]' : 'text-[#FCA5A5]')}>
                {row.state === 'even' ? t('bazar.close.state.even')
                  : t(`bazar.close.state.${row.state}`, { amount: plain(Math.abs(toNumber(row.difference))) })}
              </span>
            </div>
          ))}
        </div>
        <div className="flex justify-between gap-3 text-body text-grey-100 pt-1 border-t border-grey-700">
          <span>{t('bazar.r2.close.shouldHave')}</span><span dir="ltr" className="tabular-nums font-semibold">{amount(expected)}</span>
        </div>
        <div className="flex justify-between gap-3 text-body text-grey-100">
          <span>{t('bazar.r2.close.youCounted')}</span><span dir="ltr" className="tabular-nums font-semibold">{amount(entered)}</span>
        </div>
        <div className="flex items-baseline justify-between gap-3 pt-2 border-t border-grey-700" data-testid="close-difference">
          <span className="font-semibold">{t('bazar.r2.close.difference')}</span>
          <span dir="ltr" className={cn('text-[28px] leading-8 font-bold tabular-nums',
            diff < 0 ? 'text-[#FCA5A5]' : diff > 0 ? 'text-[#FCD34D]' : 'text-[#86EFAC]')}>
            {diff < 0 ? '− ' : diff > 0 ? '+ ' : ''}{amount(Math.abs(diff))}
          </span>
        </div>
      </div>

      {notReceived > 0 ? (
        <section role="alert" className="rounded-xl bg-critical/10 border border-critical/40 px-4 py-3 flex flex-col gap-1" data-testid="close-not-received">
          <span className="text-h4 font-bold text-critical-text tabular-nums">
            {t('bazar.close.notReceivedTitle', { amount: formatMoney(notReceived) })}
          </span>
          {split.map(k => (
            <span key={k} className="flex items-center justify-between gap-3 text-small text-primary">
              <span>{t(`bazar.close.split.${k}`)}</span>
              <span className="tabular-nums font-semibold" dir="ltr">{plain(result.not_received[k])}</span>
            </span>
          ))}
          <span aria-hidden className="flex gap-3 flex-wrap pt-0.5">
            {split.map(k => <span key={k} className={cn('w-2 h-2 rounded-sm', SPLIT_DOT[k])} />)}
          </span>
        </section>
      ) : (
        <section className="rounded-xl bg-success/10 border border-success/30 px-4 py-3 flex items-center gap-2 text-success-text font-semibold"
                 data-testid="close-all-received">
          <Check size={18} aria-hidden />{t('bazar.close.allReceived')}
        </section>
      )}
      {over > 0 && (
        <section className="rounded-xl bg-warning/10 border border-warning/30 px-4 py-3 flex flex-col gap-0.5" data-testid="close-over">
          <span className="font-bold text-warning-text tabular-nums">{t('bazar.close.overTitle', { amount: formatMoney(over) })}</span>
          <span className="text-small text-primary">{t('bazar.close.overBody')}</span>
        </section>
      )}
      {!hideTakeHome && <TakeHome result={result} />}
    </section>
  )
}

/** "Closed · balanced" / "Changed after close — close again" … — the day's status tag (square, 6px). */
export function DayStatusBadge({ day }: { day: BazarDay }) {
  const { t } = useTranslation()
  const tone = day.status === 'open' ? 'bg-charcoal text-neutral-text'
    : day.status === 'closed_balanced' ? 'bg-success/15 text-success-text'
    : day.status === 'closed_short' ? 'bg-critical/10 text-critical-text'
    : 'bg-warning/15 text-warning-text'
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-small font-semibold', tone)}
          data-testid={`day-status-${day.business_day}`}>
      {t(`bazar.close.status.${day.status}`, {
        amount: formatMoney(toNumber(day.close?.result.not_received_total ?? 0)) })}
    </span>
  )
}
