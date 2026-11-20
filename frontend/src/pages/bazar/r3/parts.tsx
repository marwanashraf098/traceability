import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, Check, ChevronDown } from 'lucide-react'
import { cn } from '../../../components/ui'
import { DateTile } from '../r1/parts'

/**
 * Bazar R3 restyle — bazar-only pieces for the event page, Get ready, and the computer Close day / End event pages
 * (design/bazar-v2 V2_Event, V2_Setup, V2_CloseDay, V2_EndEvent). Builds on r1/parts.tsx and r2/parts.tsx.
 */

/**
 * An optional read: undefined while loading, null when it failed or answered in the wrong shape. A failed read never
 * breaks the page — the card that needs it just hides. {@code key} changes → read again.
 */
export function useOptional<V>(key: string | null, load: () => Promise<V>, valid: (v: unknown) => boolean): V | null | undefined {
  const [state, setState] = useState<{ key: string; value: V | null } | null>(null)
  useEffect(() => {
    if (!key) return
    let cancelled = false
    load().then(v => { if (!cancelled) setState({ key, value: valid(v) ? v : null }) },
                () => { if (!cancelled) setState({ key, value: null }) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  // While the next read is in flight the last answer stays on screen (no flicker on refresh).
  return state ? state.value : undefined
}

export const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** The event header: date tile, back link, name, status tag, meta line, actions on the end side. */
export function EventHeader({ startsOn, lang, back, name, tag, meta, actions }: {
  startsOn: string; lang: string; back: { to: string; label: string }; name: string; tag: ReactNode; meta: ReactNode; actions?: ReactNode
}) {
  return (
    <div className="flex items-end justify-between gap-4 flex-wrap" data-testid="bazar-event-header">
      <div className="flex items-center gap-4 min-w-0">
        <span className="hidden sm:block"><DateTileDark ymd={startsOn} lang={lang} /></span>
        <div className="flex flex-col gap-1 min-w-0">
          <Link to={back.to} className="text-small text-muted hover:text-primary self-start inline-flex items-center gap-1">
            <ArrowLeft size={14} aria-hidden className="rtl:-scale-x-100" />{back.label}
          </Link>
          <div className="flex items-center gap-3 flex-wrap">
            <h1 className="text-[24px] leading-8 sm:text-[30px] sm:leading-9 font-bold tracking-[-0.02em] text-primary">{name}</h1>
            {tag}
          </div>
          <span className="text-body text-muted">{meta}</span>
        </div>
      </div>
      {actions && <div className="flex items-center gap-2.5 flex-wrap">{actions}</div>}
    </div>
  )
}

/** The dark (#0D1117) date tile of the event header. */
function DateTileDark({ ymd, lang }: { ymd: string; lang: string }) {
  return (
    <span className="block rounded-xl bg-grey-900 [&_[data-testid=bazar-date-tile]]:bg-transparent [&_[data-testid=bazar-date-tile]]:border-0 [&_[data-testid=bazar-date-tile]]:w-[58px] [&_[data-testid=bazar-date-tile]]:h-[60px]">
      <DateTile ymd={ymd} lang={lang} dark />
    </span>
  )
}

const btnBase = 'h-11 px-[18px] rounded-[10px] font-semibold text-body inline-flex items-center justify-center gap-2 whitespace-nowrap disabled:opacity-60'
export const btnSecondary = cn(btnBase, 'border border-line bg-surface text-primary hover:bg-charcoal')
export const btnPrimary = cn(btnBase, 'bg-trace-blue hover:bg-trace-blue-hover text-white')

/** "More ▾" — a small menu of actions (role=menu). Closes on Escape or a click outside. */
export function MoreMenu({ label, items }: { label: string; items: { key: string; label: string; onSelect: () => void; danger?: boolean }[] }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const id = useId()
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey) }
  }, [open])
  return (
    <div className="relative" ref={box}>
      <button type="button" className={btnSecondary} aria-haspopup="menu" aria-expanded={open} aria-controls={id}
              onClick={() => setOpen(v => !v)} data-testid="bazar-more">
        {label}<ChevronDown size={16} aria-hidden />
      </button>
      {open && (
        <div role="menu" id={id} className="absolute end-0 top-full mt-1.5 z-20 min-w-[200px] rounded-xl border border-line bg-surface shadow-e4 p-1.5 flex flex-col">
          {items.map(i => (
            <button key={i.key} type="button" role="menuitem" onClick={() => { setOpen(false); i.onSelect() }}
              className={cn('text-start px-3 py-2.5 rounded-lg text-body font-medium hover:bg-charcoal',
                i.danger ? 'text-critical-text' : 'text-primary')}>
              {i.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export interface TabDef { key: string; label: string; count?: number }

/** Underlined tabs (role=tablist); scrolls sideways on a phone. */
export function TabBar({ tabs, current, onChange, label }: { tabs: TabDef[]; current: string; onChange: (k: string) => void; label: string }) {
  return (
    <div role="tablist" aria-label={label} className="flex gap-5 overflow-x-auto border-b border-line -mx-1 px-1" data-testid="bazar-tabs">
      {tabs.map(tab => (
        <button key={tab.key} type="button" role="tab" id={`bazar-tab-${tab.key}`} aria-selected={tab.key === current}
          aria-controls={`bazar-panel-${tab.key}`} onClick={() => onChange(tab.key)}
          className={cn('shrink-0 py-2.5 -mb-px border-b-2 text-body font-semibold whitespace-nowrap',
            tab.key === current ? 'border-trace-blue text-primary' : 'border-transparent text-muted hover:text-primary')}>
          {tab.label}{tab.count != null && <span className="font-medium text-muted"> · <span dir="ltr">{tab.count}</span></span>}
        </button>
      ))}
    </div>
  )
}

export interface StepDef { title: string; sub: string; state: 'done' | 'current' | 'todo' }

/** The four-step strip (Get ready, End event): a bar each, ticked when done, blue when current. */
export function StepsStrip({ steps, testId }: { steps: StepDef[]; testId?: string }) {
  return (
    <ol className="grid grid-cols-2 sm:grid-cols-4 gap-3" data-testid={testId}>
      {steps.map((s, i) => (
        <li key={i} aria-current={s.state === 'current' ? 'step' : undefined} className="flex flex-col gap-1 min-w-0" data-state={s.state}>
          <span aria-hidden className={cn('h-1 rounded-sm',
            s.state === 'done' ? 'bg-[#16A34A]' : s.state === 'current' ? 'bg-trace-blue' : 'bg-line')} />
          <span className={cn('flex items-center gap-1.5 text-body font-semibold mt-1.5 min-w-0',
            s.state === 'todo' ? 'text-muted' : 'text-primary')}>
            {s.state === 'done'
              ? <span aria-hidden className="w-4 h-4 rounded bg-[#16A34A] text-white flex items-center justify-center shrink-0"><Check size={11} strokeWidth={3} /></span>
              : <span dir="ltr" className={cn('shrink-0', s.state === 'current' ? 'text-trace-blue' : 'text-muted')}>{i + 1}</span>}
            <span className="truncate">{s.title}</span>
          </span>
          <span className="text-small text-muted truncate">{s.sub}</span>
        </li>
      ))}
    </ol>
  )
}

/** A white card with a title row (title, optional end slot). */
export function Card({ title, end, children, testId, className }: {
  title?: ReactNode; end?: ReactNode; children: ReactNode; testId?: string; className?: string
}) {
  return (
    <section className={cn('card p-4 sm:p-5 flex flex-col gap-3 min-w-0', className)} data-testid={testId}>
      {(title || end) && (
        <div className="flex items-center justify-between gap-3 flex-wrap">
          {title && <h2 className="text-body-lg font-semibold text-primary">{title}</h2>}
          {end}
        </div>
      )}
      {children}
    </section>
  )
}

/** "Selling stopped · Sun 22 Nov, 22:09" style local time in Cairo. */
export function cairoDateTime(iso: string, lang: string): string {
  return new Date(iso).toLocaleString(lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB',
    { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Africa/Cairo' })
}

/** Minutes since {@code iso} ("last sale 2 min ago"). */
export function minutesAgo(iso: string, now = Date.now()): number {
  return Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000))
}

export function useLang() {
  return useTranslation().i18n.language
}
