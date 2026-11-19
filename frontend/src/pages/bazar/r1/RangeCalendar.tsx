import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '../../../components/ui'

/**
 * Bazar R1 — the New bazar date-range calendar (bazar-only). Same values as the old two date inputs (startsOn /
 * endsOn, YYYY-MM-DD): past days stay selectable (no behaviour change), end is never before start.
 * Week starts on Saturday in both languages. Keyboard: arrows move a day / a week, Home / End the week's ends,
 * PageUp / PageDown a month; Enter or Space picks the first day, then the last.
 */

const pad = (n: number) => String(n).padStart(2, '0')
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`
const parse = (s: string) => ({ y: +s.slice(0, 4), m: +s.slice(5, 7) - 1, d: +s.slice(8, 10) })

function addDays(s: string, n: number): string {
  const { y, m, d } = parse(s)
  const dt = new Date(Date.UTC(y, m, d + n))
  return ymd(dt.getUTCFullYear(), dt.getUTCMonth(), dt.getUTCDate())
}

function addMonths(s: string, n: number): string {
  const { y, m, d } = parse(s)
  const last = new Date(Date.UTC(y, m + n + 1, 0)).getUTCDate()
  const dt = new Date(Date.UTC(y, m + n, Math.min(d, last)))
  return ymd(dt.getUTCFullYear(), dt.getUTCMonth(), dt.getUTCDate())
}

/** 0 = Saturday … 6 = Friday. */
function satIndex(s: string): number {
  const { y, m, d } = parse(s)
  return (new Date(Date.UTC(y, m, d)).getUTCDay() + 1) % 7
}

const LOCALE = (lang: string) => (lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB')

export function fullDate(s: string, lang: string): string {
  const { y, m, d } = parse(s)
  return new Date(Date.UTC(y, m, d)).toLocaleDateString(LOCALE(lang),
    { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
}

export default function RangeCalendar({ start, end, today, onChange }: {
  start: string; end: string; today: string; onChange: (start: string, end: string) => void
}) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [focus, setFocus] = useState(start)
  const [phase, setPhase] = useState<'start' | 'end'>('start')
  const [view, setView] = useState(start.slice(0, 7))
  const [announce, setAnnounce] = useState('')
  const grid = useRef<HTMLTableElement>(null)
  const moved = useRef(false)

  // Keep the focused day's month on screen.
  useEffect(() => { if (focus.slice(0, 7) !== view) setView(focus.slice(0, 7)) }, [focus, view])
  // Move DOM focus with the roving day only after a keyboard move (never steal focus on render).
  useEffect(() => {
    if (!moved.current) return
    const btn = grid.current?.querySelector<HTMLButtonElement>(`button[data-day="${focus}"]`)
    if (!btn) return            // the month is still switching — try again on the next render
    moved.current = false
    btn.focus()
  }, [focus, view])

  const { y, m } = parse(`${view}-01`)
  const first = ymd(y, m, 1)
  const daysIn = new Date(Date.UTC(y, m + 1, 0)).getUTCDate()
  const lead = satIndex(first)
  const cells: (string | null)[] = [...Array(lead).fill(null), ...Array.from({ length: daysIn }, (_, i) => ymd(y, m, i + 1))]
  while (cells.length % 7) cells.push(null)
  const weeks: (string | null)[][] = []
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7))
  // Saturday-first weekday headers: 2022-01-01 was a Saturday.
  const heads = Array.from({ length: 7 }, (_, i) => {
    const s = addDays('2022-01-01', i)
    const { y: yy, m: mm, d: dd } = parse(s)
    const date = new Date(Date.UTC(yy, mm, dd))
    return {
      short: date.toLocaleDateString(LOCALE(lang), { weekday: 'narrow', timeZone: 'UTC' }),
      long: date.toLocaleDateString(LOCALE(lang), { weekday: 'long', timeZone: 'UTC' }),
    }
  })
  const monthTitle = new Date(Date.UTC(y, m, 1)).toLocaleDateString(LOCALE(lang), { month: 'long', year: 'numeric', timeZone: 'UTC' })

  function pick(day: string) {
    setFocus(day)
    if (phase === 'start' || day < start) {
      onChange(day, day)
      setPhase('end')
      setAnnounce(t('bazar.r1.calendar.pickedStart', { date: fullDate(day, lang) }))
    } else {
      onChange(start, day)
      setPhase('start')
      setAnnounce(t('bazar.r1.calendar.pickedEnd', { start: fullDate(start, lang), end: fullDate(day, lang) }))
    }
  }

  function onKey(e: KeyboardEvent<HTMLTableElement>) {
    const rtl = (e.currentTarget.closest('[dir]') as HTMLElement | null)?.dir === 'rtl' || document.documentElement.dir === 'rtl'
    let next: string | null = null
    switch (e.key) {
      case 'ArrowRight': next = addDays(focus, rtl ? -1 : 1); break
      case 'ArrowLeft':  next = addDays(focus, rtl ? 1 : -1); break
      case 'ArrowDown':  next = addDays(focus, 7); break
      case 'ArrowUp':    next = addDays(focus, -7); break
      case 'Home':       next = addDays(focus, -satIndex(focus)); break
      case 'End':        next = addDays(focus, 6 - satIndex(focus)); break
      case 'PageDown':   next = addMonths(focus, 1); break
      case 'PageUp':     next = addMonths(focus, -1); break
      case 'Enter':
      case ' ':          e.preventDefault(); pick(focus); return
      default: return
    }
    e.preventDefault()
    moved.current = true
    setFocus(next)
  }

  function shiftMonth(n: number) {
    const next = addMonths(`${view}-01`, n)
    setView(next.slice(0, 7))
    setFocus(next)
  }

  return (
    <div className="relative border border-line rounded-xl p-3 flex flex-col gap-2 min-w-0" data-testid="bazar-calendar">
      <div className="flex items-center justify-between gap-2">
        <button type="button" onClick={() => shiftMonth(-1)} aria-label={t('bazar.r1.calendar.prev')}
          className="w-[30px] h-[30px] rounded-lg border border-line flex items-center justify-center hover:bg-charcoal">
          <ChevronLeft size={16} aria-hidden className="rtl:-scale-x-100" />
        </button>
        <span className="text-body font-semibold text-primary" aria-live="polite" id="bazar-cal-title">{monthTitle}</span>
        <button type="button" onClick={() => shiftMonth(1)} aria-label={t('bazar.r1.calendar.next')}
          className="w-[30px] h-[30px] rounded-lg border border-line flex items-center justify-center hover:bg-charcoal">
          <ChevronRight size={16} aria-hidden className="rtl:-scale-x-100" />
        </button>
      </div>
      <table ref={grid} role="grid" aria-labelledby="bazar-cal-title" onKeyDown={onKey} className="w-full border-collapse">
        <thead>
          <tr>
            {heads.map(h => (
              <th key={h.long} scope="col" abbr={h.long} className="text-small font-medium text-muted h-7">{h.short}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((w, i) => (
            <tr key={i}>
              {w.map((day, j) => {
                if (!day) return <td key={j} role="gridcell" />
                const selected = day >= start && day <= end
                const edge = day === start || day === end
                return (
                  <td key={day} role="gridcell" aria-selected={selected} className="p-0.5 text-center">
                    <button type="button" data-day={day} tabIndex={day === focus ? 0 : -1}
                      aria-label={fullDate(day, lang)} aria-current={day === today ? 'date' : undefined}
                      onClick={() => pick(day)} onFocus={() => setFocus(day)}
                      className={cn('w-full h-9 rounded-lg text-body tabular-nums transition-colors',
                        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-trace-blue',
                        edge ? 'bg-trace-blue text-white font-bold'
                          : selected ? 'bg-trace-blue/10 text-trace-blue font-semibold'
                          : day < today ? 'text-muted hover:bg-charcoal' : 'text-primary hover:bg-charcoal',
                        day === today && !edge && 'underline underline-offset-4 font-bold')}>
                      <span dir="ltr">{+day.slice(8, 10)}</span>
                    </button>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="sr-only" aria-live="polite">{announce}</p>
    </div>
  )
}
