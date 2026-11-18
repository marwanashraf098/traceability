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

// Bazar POS B4 — the close-the-day form (mockup page 1, step 8), shared by the phone screen and the desktop event
// page: cash in the drawer, card machine settlement, InstaPay tick, kept for tomorrow (+ the opening float on the
// bazar's first close), a live preview from the server (GET close-preview — the one calculation), the red
// "X EGP not received" box with its split, money over shown as over, take-home cash, then confirm. Sent like a sale:
// the same request id on every retry, "closed" only once the server confirms.

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

export function CloseDayForm({ eventId, day, methods, onClosed, compact }: {
  eventId: string
  day: BazarDay
  methods: PaymentMethod[]
  onClosed: (day: BazarDay) => void
  /** Desktop: inputs side by side. */
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

  function confirm() {
    if (!ready) return
    const n = note.trim() || null
    cmd.run((requestId, timeoutMs) => closeBazarDayOnce(eventId, day.business_day, entry, n, requestId, timeoutMs))
  }

  const field = cn('input w-full font-semibold tabular-nums', compact ? 'h-12 text-body-lg' : 'h-14 text-h4')

  return (
    <div className="flex flex-col gap-3.5" data-testid="close-day-form">
      {editFloat ? (
        <label className="flex flex-col gap-1">
          <span className="text-small font-semibold text-primary">{t('bazar.close.float')}</span>
          <input id={`${ids}-float`} className={field} inputMode="decimal" dir="ltr" value={floatStr}
                 onChange={e => setFloatStr(e.target.value)} disabled={cmd.busy} data-testid="close-float"
                 aria-invalid={Number.isNaN(flt ?? 0) || undefined} />
          <span className="text-small text-muted">{t('bazar.close.floatHint')}</span>
        </label>
      ) : (
        <div className="flex items-center justify-between gap-2 rounded-xl bg-surface-muted px-3 py-2 text-small" data-testid="close-float-line">
          <span className="text-primary">
            {t('bazar.close.floatFrom', { amount: formatMoney(toNumber(floatDefault.amount)) })}
            <span className="text-muted"> · {t(floatDefault.source === 'this_day_close' ? 'bazar.close.floatFromDay' : 'bazar.close.floatFromPrevious')}</span>
          </span>
          <button type="button" className="min-h-[44px] px-2 font-semibold text-trace-blue" disabled={cmd.busy}
                  onClick={() => setEditFloat(true)}>{t('bazar.close.floatChange')}</button>
        </div>
      )}

      <div className={cn('grid gap-3.5', compact && cardOn ? 'sm:grid-cols-2' : 'grid-cols-1')}>
        <label className="flex flex-col gap-1">
          <span className="text-small font-semibold text-primary">{t('bazar.close.cash')}</span>
          <input className={field} inputMode="decimal" dir="ltr" value={cashStr} autoFocus={!compact}
                 onChange={e => setCashStr(e.target.value)} disabled={cmd.busy} data-testid="close-cash"
                 aria-invalid={Number.isNaN(cash ?? 0) || undefined} />
          <span className="text-small text-muted">{t('bazar.close.cashHint')}</span>
        </label>
        {cardOn && (
          <label className="flex flex-col gap-1">
            <span className="text-small font-semibold text-primary">{t('bazar.close.card')}</span>
            <input className={field} inputMode="decimal" dir="ltr" value={cardStr}
                   onChange={e => setCardStr(e.target.value)} disabled={cmd.busy} data-testid="close-card"
                   aria-invalid={Number.isNaN(card ?? 0) || undefined} />
            <span className="text-small text-muted">{t('bazar.close.cardHint')}</span>
          </label>
        )}
      </div>

      {instaOn && (
        <label className="flex items-start gap-3 rounded-xl border border-line bg-surface px-3 py-3 cursor-pointer">
          <input type="checkbox" className="mt-1 w-5 h-5 accent-trace-blue" checked={insta} disabled={cmd.busy}
                 onChange={e => setInsta(e.target.checked)} data-testid="close-instapay" />
          <span className="flex flex-col gap-0.5">
            <span className="font-semibold text-primary">
              {t('bazar.close.instapay')}
              {preview && <span className="text-muted font-normal"> · {formatMoney(methodRow(preview.result, 'instapay')?.expected ?? 0)}</span>}
            </span>
            <span className="text-small text-muted">{t('bazar.close.instapayHint')}</span>
          </span>
        </label>
      )}

      <label className="flex flex-col gap-1">
        <span className="text-small font-semibold text-primary">{t('bazar.close.kept')}</span>
        <input className={cn(field, 'h-12 text-body-lg')} inputMode="decimal" dir="ltr"
               value={keptStr ?? (flt != null && !Number.isNaN(flt) ? String(flt) : '')}
               onChange={e => setKeptStr(e.target.value)} disabled={cmd.busy} data-testid="close-kept"
               aria-invalid={keptTooHigh || undefined} />
        <span className={cn('text-small', keptTooHigh ? 'text-critical-text font-semibold' : 'text-muted')}>
          {keptTooHigh ? t('bazar.close.keptTooHigh') : t('bazar.close.keptHint')}
        </span>
      </label>

      <CloseResultView result={preview?.result ?? null} loading={previewing && !preview} />

      <label className="flex flex-col gap-1">
        <span className="text-small font-semibold text-primary">{t('bazar.close.note')}</span>
        <input className="input h-11" value={note} maxLength={500} onChange={e => setNote(e.target.value)}
               placeholder={t('bazar.close.notePlaceholder')} disabled={cmd.busy} />
      </label>

      {floatMissing && <p className="text-small text-muted">{t('bazar.close.needFloat')}</p>}
      <CommandStatus state={cmd.state} />
      <button type="button" onClick={confirm} disabled={!ready || cmd.busy} data-testid="close-confirm"
        className="btn w-full h-[52px] rounded-xl text-body-lg font-semibold flex items-center justify-center gap-2 bg-trace-blue hover:bg-trace-blue-hover text-white disabled:opacity-60">
        {cmd.busy && <Loader2 size={16} className="animate-spin" aria-hidden />}
        {cmd.busy ? t('bazar.close.closing') : again ? t('bazar.close.confirmAgain') : t('bazar.close.confirm')}
      </button>
    </div>
  )
}

function methodRow(r: BazarCloseResult, m: PaymentMethod) {
  return r.methods.find(x => x.method === m)
}

/** Per method expected vs entered, the red "X EGP not received" box with its split, over, take-home. */
export function CloseResultView({ result, loading }: { result: BazarCloseResult | null; loading?: boolean }) {
  const { t } = useTranslation()
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
  return (
    <section className="flex flex-col gap-2.5" data-testid="close-result">
      <div className="rounded-xl border border-line bg-surface divide-y divide-line">
        <div className="grid grid-cols-[1fr_auto_auto] gap-3 px-3 py-2 text-small text-muted">
          <span />
          <span className="text-end">{t('bazar.close.expected')}</span>
          <span className="text-end min-w-[96px]">{t('bazar.close.difference')}</span>
        </div>
        {result.methods.map(row => (
          <div key={row.method} className="grid grid-cols-[1fr_auto_auto] gap-3 px-3 py-2.5 items-baseline" data-testid={`close-row-${row.method}`}>
            <span className="font-semibold text-primary">{t(`bazar.method.${row.method}`)}</span>
            <span className="text-end tabular-nums text-muted" dir="ltr">{plain(row.expected)}</span>
            <span className={cn('text-end tabular-nums font-semibold min-w-[96px]',
              row.state === 'even' ? 'text-success-text' : row.state === 'over' ? 'text-warning-text' : 'text-critical-text')}>
              {row.state === 'even' ? t('bazar.close.state.even')
                : t(`bazar.close.state.${row.state}`, { amount: plain(Math.abs(toNumber(row.difference))) })}
            </span>
          </div>
        ))}
      </div>

      {notReceived > 0 ? (
        <section role="alert" className="rounded-2xl bg-critical/10 border border-critical/40 px-4 py-3 flex flex-col gap-1" data-testid="close-not-received">
          <span className="text-h4 font-bold text-critical-text tabular-nums">
            {t('bazar.close.notReceivedTitle', { amount: formatMoney(notReceived) })}
          </span>
          {split.map(k => (
            <span key={k} className="flex justify-between gap-3 text-small text-primary">
              <span>{t(`bazar.close.split.${k}`)}</span>
              <span className="tabular-nums font-semibold" dir="ltr">{plain(result.not_received[k])}</span>
            </span>
          ))}
        </section>
      ) : (
        <section className="rounded-2xl bg-success/10 border border-success/30 px-4 py-3 flex items-center gap-2 text-success-text font-semibold"
                 data-testid="close-all-received">
          <Check size={18} aria-hidden />{t('bazar.close.allReceived')}
        </section>
      )}
      {over > 0 && (
        <section className="rounded-2xl bg-warning/10 border border-warning/30 px-4 py-3 flex flex-col gap-0.5" data-testid="close-over">
          <span className="font-bold text-warning-text tabular-nums">{t('bazar.close.overTitle', { amount: formatMoney(over) })}</span>
          <span className="text-small text-primary">{t('bazar.close.overBody')}</span>
        </section>
      )}
      <section className="rounded-2xl bg-surface-muted px-4 py-3 flex flex-col gap-0.5" data-testid="close-take-home">
        <span className="text-body-lg font-bold text-primary tabular-nums">
          {t('bazar.close.takeHome', { amount: formatMoney(result.take_home) })}
        </span>
        <span className="text-small text-muted">{t('bazar.close.takeHomeKept', { amount: formatMoney(result.cash_kept) })}</span>
      </section>
    </section>
  )
}

/** "Closed · balanced" / "Changed after close — close again" … — the day's status badge. */
export function DayStatusBadge({ day }: { day: BazarDay }) {
  const { t } = useTranslation()
  const tone = day.status === 'open' ? 'bg-surface-muted text-muted'
    : day.status === 'closed_balanced' ? 'bg-success/15 text-success-text'
    : day.status === 'closed_short' ? 'bg-critical/10 text-critical-text'
    : 'bg-warning/15 text-warning-text'
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-small font-semibold', tone)}
          data-testid={`day-status-${day.business_day}`}>
      {t(`bazar.close.status.${day.status}`, {
        amount: formatMoney(toNumber(day.close?.result.not_received_total ?? 0)) })}
    </span>
  )
}
