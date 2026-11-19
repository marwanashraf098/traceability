import { useEffect, useState, type ReactNode } from 'react'
import { cn } from '../../../components/ui'
import { toNumber } from '../../../bazar/format'

/**
 * Bazar R1 restyle — small bazar-only building blocks (design/bazar-v2). Square 6px tags (`rounded`), the date tile,
 * tabular numbers. Nothing here is shared with the rest of the app; ui.tsx is untouched.
 */

const LOCALE = (lang: string) => (lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB')

function utc(ymd: string): Date {
  return new Date(Date.UTC(+ymd.slice(0, 4), +ymd.slice(5, 7) - 1, +ymd.slice(8, 10)))
}

/** "NOV" / "نوفمبر" — a YYYY-MM-DD date's short month, no time-zone shift. */
export function monthShort(ymd: string, lang: string): string {
  // en-US for English: "Sep", never en-GB's "Sept" (the tile is 3 letters wide).
  return utc(ymd).toLocaleDateString(lang === 'ar' ? LOCALE(lang) : 'en-US', { month: 'short', timeZone: 'UTC' })
}

/** "Fri 20 Nov" — weekday + day + month. */
export function dayLabel(ymd: string, lang: string): string {
  return utc(ymd).toLocaleDateString(LOCALE(lang), { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
}

/** "Fri" — the short weekday. */
export function weekdayShort(ymd: string, lang: string): string {
  return utc(ymd).toLocaleDateString(LOCALE(lang), { weekday: 'short', timeZone: 'UTC' })
}

/** "Fri 20 – Sun 22 Nov" (or one day when start = end). */
export function rangeLabel(startsOn: string, endsOn: string, lang: string): string {
  return startsOn === endsOn ? dayLabel(startsOn, lang) : `${dayLabel(startsOn, lang)} – ${dayLabel(endsOn, lang)}`
}

/** "18,920" — whole pounds without decimals, otherwise two; Latin digits in both languages. */
export function amount(v: string | number | null | undefined): string {
  const n = toNumber(v)
  const whole = Math.abs(n % 1) < 0.005
  return n.toLocaleString('en-US', { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: whole ? 0 : 2 })
}

/** "62%" from a 0..1 share (null → ''). */
export function percent(v: string | number | null | undefined): string {
  if (v == null || v === '') return ''
  return `${Math.round(toNumber(v) * 100)}%`
}

/** Month + day tile. Dark variant for the hero, light for lists. */
export function DateTile({ ymd, lang, dark, size = 'md' }: { ymd: string; lang: string; dark?: boolean; size?: 'sm' | 'md' }) {
  return (
    <span aria-hidden className={cn('flex flex-col items-center justify-center shrink-0 rounded-xl',
      size === 'md' ? 'w-[52px] h-[56px]' : 'w-11 h-12',
      dark ? 'bg-white/[0.06] border border-grey-700' : 'bg-charcoal')}
      data-testid="bazar-date-tile">
      <span className={cn('text-[11px] leading-4 font-bold uppercase tracking-wide', dark ? 'text-[#93B4FF]' : 'text-trace-blue')}>
        {monthShort(ymd, lang)}
      </span>
      <span dir="ltr" className={cn('font-bold tabular-nums', size === 'md' ? 'text-[24px] leading-7' : 'text-[20px] leading-6',
        dark ? 'text-[#F2F4F7]' : 'text-primary')}>
        {ymd.slice(8, 10)}
      </span>
    </span>
  )
}

export type TagTone = 'live' | 'amber' | 'done' | 'neutral' | 'info'

const TAG_STYLE: Record<TagTone, string> = {
  live:    'bg-[rgba(34,197,94,0.14)] text-[#86EFAC]',
  amber:   'bg-[rgba(245,158,11,0.14)] text-[#FCD34D]',
  done:    'bg-[rgba(34,197,94,0.14)] text-[#86EFAC]',
  neutral: 'bg-white/[0.06] text-grey-100',
  info:    'bg-[rgba(37,99,235,0.18)] text-[#93B4FF]',
}

const TAG_STYLE_LIGHT: Record<TagTone, string> = {
  live:    'bg-success/10 text-success-text',
  amber:   'bg-warning/10 text-warning-text',
  done:    'bg-success/10 text-success-text',
  neutral: 'bg-charcoal text-neutral-text',
  info:    'bg-trace-blue/10 text-trace-blue',
}

/** Square tag (6px) — never a pill. `dark` = on the #0D1117 hero. */
export function Tag({ tone, dark, dot, children, testId }: {
  tone: TagTone; dark?: boolean; dot?: boolean; children: ReactNode; testId?: string
}) {
  return (
    <span data-testid={testId}
      className={cn('inline-flex items-center gap-2 px-2.5 py-1 rounded text-small font-semibold uppercase tracking-wide w-fit',
        dark ? TAG_STYLE[tone] : TAG_STYLE_LIGHT[tone])}>
      {dot && <span aria-hidden className={cn('w-2 h-2 rounded-sm',
        tone === 'live' || tone === 'done' ? 'bg-[#22C55E]' : tone === 'amber' ? 'bg-warning' : 'bg-current')} />}
      {children}
    </span>
  )
}

/** Small uppercase section label (dark hero or page). */
export function Eyebrow({ children, dark }: { children: ReactNode; dark?: boolean }) {
  return (
    <span className={cn('text-[11px] leading-4 font-bold uppercase tracking-[0.08em]', dark ? 'text-sidebar-text' : 'text-muted')}>
      {children}
    </span>
  )
}

/** Phone layout switch (< 640 px). Without matchMedia (tests, old browsers) it is the computer layout. */
export function useIsPhone(): boolean {
  const query = '(max-width: 639px)'
  const get = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches
  const [phone, setPhone] = useState(get)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia(query)
    const on = () => setPhone(mq.matches)
    on()
    mq.addEventListener?.('change', on)
    return () => mq.removeEventListener?.('change', on)
  }, [])
  return phone
}
