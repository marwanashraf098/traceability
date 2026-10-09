import { type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { LucideIcon } from 'lucide-react'
import { AlertCircle, Info, RefreshCw } from 'lucide-react'
import { cn, Skeleton } from '../ui'
import type { QueryState } from '../../analytics/useAnalyticsQuery'

// Analytics building blocks, styled after design/Traced_Analytics_dc.html inside the app's
// tokens. Every card owns its loading / error / empty state — one failing endpoint never blanks
// the page.

// ── Card ─────────────────────────────────────────────────────────────────────

export type Span = 's12' | 's8' | 's7' | 's6' | 's5' | 's4' | 's3'

const SPAN: Record<Span, string> = {
  s12: 'lg:col-span-12',
  s8: 'lg:col-span-8',
  s7: 'lg:col-span-7',
  s6: 'lg:col-span-6',
  s5: 'lg:col-span-5',
  s4: 'lg:col-span-4',
  s3: 'lg:col-span-3',
}

export function AnalyticsGrid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">{children}</div>
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <div className="col-span-full flex items-baseline gap-2.5 mt-2.5">
      <h2 className="text-[13px] font-semibold uppercase tracking-[0.08em] text-muted">{children}</h2>
      <span className="flex-1 h-px bg-line" />
    </div>
  )
}

export function AnalyticsCard({
  title, desc, right, v2 = false, span = 's6', children, testId,
}: {
  title: ReactNode
  desc?: ReactNode
  right?: ReactNode
  v2?: boolean
  span?: Span
  children: ReactNode
  testId?: string
}) {
  return (
    <article data-testid={testId} className={cn('card p-[18px] min-w-0 flex flex-col gap-3.5 col-span-full', SPAN[span])}>
      <div className="flex items-start gap-2.5 flex-wrap">
        <div className="flex-1 min-w-0">
          <h3 className="text-[12.5px] font-bold tracking-[0.05em] uppercase text-primary">{title}</h3>
          {desc && <p className="text-[12.5px] text-muted mt-0.5">{desc}</p>}
        </div>
        {v2 && <V2Chip />}
        {right}
      </div>
      {children}
    </article>
  )
}

// ── States ───────────────────────────────────────────────────────────────────

export function CardSkeleton({ lines = 4 }: { lines?: number }) {
  return (
    <div className="flex flex-col gap-2.5" aria-busy="true">
      {Array.from({ length: lines }).map((_, i) => <Skeleton key={i} className={cn('h-4', i % 2 ? 'w-3/4' : 'w-full')} />)}
    </div>
  )
}

export function CardError({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation()
  return (
    <div role="alert" className="flex items-center gap-3 rounded-lg bg-critical/5 border border-critical/20 px-3 py-2.5">
      <AlertCircle size={16} className="text-critical flex-shrink-0" />
      <span className="flex-1 text-small text-primary">{t('analytics.state.error')}</span>
      <button type="button" onClick={onRetry} className="inline-flex items-center gap-1.5 text-small font-semibold text-trace-blue hover:underline">
        <RefreshCw size={13} /> {t('analytics.state.retry')}
      </button>
    </div>
  )
}

export function CardEmpty({ children }: { children: ReactNode }) {
  return <p className="text-small text-muted py-6 text-center">{children}</p>
}

/**
 * The per-card state switch: skeleton while the first answer loads, a retry row on error, the
 * empty text when `isEmpty(data)`, else the content.
 */
export function QueryBlock<T>({
  q, children, isEmpty, empty, lines,
}: {
  q: QueryState<T>
  children: (data: T) => ReactNode
  isEmpty?: (data: T) => boolean
  empty?: ReactNode
  lines?: number
}) {
  if (q.error && q.data === undefined) return <CardError onRetry={q.retry} />
  if (q.data === undefined) return <CardSkeleton lines={lines} />
  if (isEmpty?.(q.data)) return <CardEmpty>{empty}</CardEmpty>
  return <>{children(q.data)}</>
}

// ── KPI tile ─────────────────────────────────────────────────────────────────

export type Tone = 'good' | 'bad' | 'warn' | 'blue' | 'grey'

const TONE_ICON: Record<Tone, string> = {
  good: 'bg-success/10 text-success-text',
  bad: 'bg-critical/10 text-critical-text',
  warn: 'bg-warning/10 text-warning-text',
  blue: 'bg-trace-blue/10 text-trace-blue',
  grey: 'bg-elevated text-muted',
}
const TONE_VALUE: Record<Tone, string> = {
  good: 'text-success-text',
  bad: 'text-critical-text',
  warn: 'text-warning-text',
  blue: 'text-primary',
  grey: 'text-primary',
}

export type DeltaDir = 'up' | 'down' | 'flat'

export interface DeltaInfo {
  text: string
  dir: DeltaDir
  /** Whether "up" is good (revenue) or bad (cost). Colours follow the meaning, arrows the number. */
  goodWhenUp?: boolean
}

export function Delta({ d }: { d: DeltaInfo }) {
  const good = d.dir === 'flat' ? null : (d.dir === 'up') === (d.goodWhenUp ?? true)
  return (
    <span className={cn('font-semibold text-[12px]', good == null ? 'text-muted' : good ? 'text-success-text' : 'text-critical-text')}>
      <span aria-hidden="true">{d.dir === 'up' ? '▲' : d.dir === 'down' ? '▼' : '–'}</span> {d.text}
    </span>
  )
}

export function Kpi({
  label, value, foot, delta, tone = 'grey', icon: Icon, to, hero = false, chip, testId, loading = false,
}: {
  label: ReactNode
  value: ReactNode
  foot?: ReactNode
  delta?: DeltaInfo | null
  tone?: Tone
  icon: LucideIcon
  to?: string
  hero?: boolean
  chip?: ReactNode
  testId?: string
  loading?: boolean
}) {
  const body = (
    <>
      <div className="flex items-start gap-2 text-[12.5px] text-muted leading-tight">
        <span className={cn('w-[26px] h-[26px] rounded-[7px] inline-grid place-items-center flex-none', TONE_ICON[tone])} aria-hidden="true">
          <Icon size={15} strokeWidth={1.8} />
        </span>
        {/* Labels wrap rather than truncate: six tiles in a row leave ~150 px each. */}
        <span className="min-w-0 self-center">{label}</span>
      </div>
      {loading ? <Skeleton className="h-7 w-28 mt-1" /> : (
        <div className={cn('font-mono text-[26px] font-semibold tracking-[-0.02em] leading-tight break-words', TONE_VALUE[tone])}>{value}</div>
      )}
      <div className="text-[12px] text-muted flex gap-1.5 items-center flex-wrap min-h-[16px]">
        {delta && <Delta d={delta} />}
        {foot}
        {chip}
      </div>
    </>
  )
  const cls = cn('card px-4 py-3.5 flex flex-col gap-1 min-w-0', hero && tone === 'good' && 'ring-[1.5px] ring-success')
  return to
    ? <Link to={to} data-testid={testId} className={cn(cls, 'hover:ring-1 hover:ring-trace-blue transition-shadow')}>{body}</Link>
    : <div data-testid={testId} className={cls}>{body}</div>
}

export function KpiRow({ children }: { children: ReactNode }) {
  return <div className="col-span-full grid gap-4 grid-cols-[repeat(auto-fit,minmax(150px,1fr))]">{children}</div>
}

// ── Pills, chips, banners ────────────────────────────────────────────────────

export type PillKind = 'good' | 'warn' | 'serious' | 'crit' | 'info' | 'neutral'

const PILL: Record<PillKind, string> = {
  good: 'bg-success/10 text-success-text',
  warn: 'bg-warning/15 text-warning-text',
  serious: 'bg-[#fbe9e1] text-[#a3401b]',
  crit: 'bg-critical/10 text-critical-text',
  info: 'bg-trace-blue/10 text-trace-blue-hover',
  neutral: 'bg-elevated text-neutral-text',
}
const PILL_ICON: Record<PillKind, string> = { good: '✓', warn: '!', serious: '↺', crit: '✕', info: '◷', neutral: '→' }

export function Pill({ kind, children }: { kind: PillKind; children: ReactNode }) {
  return (
    <span className={cn('inline-flex items-center gap-[5px] h-[22px] px-2 rounded-full text-[12px] font-semibold whitespace-nowrap', PILL[kind])}>
      <i className="not-italic text-[11px]" aria-hidden="true">{PILL_ICON[kind]}</i>{children}
    </span>
  )
}

/** Delivery success pill: red under 68%, amber under 78% (the mockup's thresholds). */
export function RatePill({ rate, text }: { rate: number | null; text: string }) {
  if (rate == null) return <Pill kind="neutral">{text}</Pill>
  const pct = rate * 100
  return <Pill kind={pct < 68 ? 'crit' : pct < 78 ? 'warn' : 'good'}>{text}</Pill>
}

export function V2Chip() {
  const { t } = useTranslation()
  return <span className="inline-flex items-center h-5 px-[7px] rounded-[5px] text-[11px] font-semibold tracking-[0.04em] uppercase whitespace-nowrap bg-[#eeecfa] text-[#6b5fc7]">{t('analytics.chip.v2')}</span>
}

/** A grey tag naming where a figure came from, or how sure it is. */
export function SourceChip({ children, title }: { children: ReactNode; title?: string }) {
  return <span title={title} className="inline-flex items-center h-5 px-[7px] rounded-[5px] text-[11px] font-medium whitespace-nowrap bg-elevated text-muted">{children}</span>
}

export function Banner({ children, tone = 'info', action, testId }: { children: ReactNode; tone?: 'info' | 'warn'; action?: ReactNode; testId?: string }) {
  return (
    <div data-testid={testId} role="note" className={cn(
      'col-span-full flex gap-2.5 items-start px-3.5 py-2.5 rounded-xl text-[13px]',
      tone === 'warn' ? 'bg-warning/10 border border-warning/30 text-primary' : 'bg-surface border border-dashed border-grey-100 text-neutral-text'
    )}>
      <Info size={16} className={cn('flex-shrink-0 mt-0.5', tone === 'warn' ? 'text-warning-text' : 'text-muted')} />
      <div className="flex-1 min-w-0">{children}</div>
      {action}
    </div>
  )
}

export function Note({ children }: { children: ReactNode }) {
  return <p className="text-[12px] text-muted">{children}</p>
}

export function TextLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link to={to} className="text-[12.5px] font-semibold text-trace-blue hover:underline whitespace-nowrap">{children}</Link>
}
