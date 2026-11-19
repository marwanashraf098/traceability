import type { ReactNode } from 'react'
import { Check } from 'lucide-react'
import { cn } from '../../../components/ui'
import { amount } from '../r1/parts'

/**
 * Bazar R2 restyle — bazar-only pieces for Close day, End event and the report (design/bazar-v2). Builds on R1's
 * r1/parts.tsx (amount, DateTile, Tag, Eyebrow); nothing here is shared with the rest of the app.
 */

/** "62,780 EGP" — number first, unit after (R1 / R2 format); Latin digits. */
export function money(v: string | number | null | undefined): string {
  return `${amount(v)} EGP`
}

/** A money figure: the number LTR + tabular, the unit small and muted. */
export function Money({ value, unitClass, className, testId }: {
  value: string | number | null | undefined; unitClass?: string; className?: string; testId?: string
}) {
  return (
    <span dir="ltr" data-testid={testId} className={cn('tabular-nums whitespace-nowrap', className)}>
      {amount(value)}<span className={cn('font-semibold', unitClass ?? 'text-[0.55em] text-muted')}> EGP</span>
    </span>
  )
}

export type ChipTone = 'good' | 'warn' | 'bad' | 'neutral' | 'info'

const CHIP: Record<ChipTone, string> = {
  good:    'bg-success/10 text-success-text',
  warn:    'bg-warning/10 text-warning-text',
  bad:     'bg-critical/10 text-critical-text',
  neutral: 'bg-charcoal text-neutral-text',
  info:    'bg-trace-blue/10 text-trace-blue',
}

/** A square (6px) status chip in normal case — "Matches", "−40 short", "Damaged". */
export function Chip({ tone, children, testId, className }: { tone: ChipTone; children: ReactNode; testId?: string; className?: string }) {
  return (
    <span data-testid={testId} className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded text-small font-semibold w-fit whitespace-nowrap',
      CHIP[tone], className)}>
      {children}
    </span>
  )
}

/** A numbered (or ticked) section card: "1 · Cash in the drawer". */
export function StepCard({ n, done, title, sub, end, children, testId }: {
  n: number | string; done?: boolean; title: string; sub?: ReactNode; end?: ReactNode; children?: ReactNode; testId?: string
}) {
  return (
    <section className="card p-4 flex flex-col gap-3.5 min-w-0" data-testid={testId}>
      <div className="flex items-start gap-3">
        <span aria-hidden dir="ltr" className={cn('w-7 h-7 rounded shrink-0 text-small font-bold flex items-center justify-center',
          done ? 'bg-[#16A34A] text-white' : 'bg-grey-900 text-white')}>
          {done ? <Check size={15} strokeWidth={3} /> : n}
        </span>
        <span className="flex flex-col min-w-0 flex-1">
          <span className="text-body-lg font-semibold text-primary">{title}</span>
          {sub && <span className="text-small text-muted">{sub}</span>}
        </span>
        {end}
      </div>
      {children}
    </section>
  )
}

/** A segmented bar: each part's share of {@code total}. */
export function StackBar({ parts, total, className }: {
  parts: { key: string; n: number; color: string }[]; total: number; className?: string
}) {
  return (
    <div aria-hidden className={cn('flex h-2.5 rounded-sm overflow-hidden bg-charcoal gap-px', className)}>
      {parts.filter(p => p.n > 0).map(p => (
        <div key={p.key} className={p.color} style={{ width: total ? `${(p.n / total) * 100}%` : '0%' }} />
      ))}
    </div>
  )
}

/** A legend swatch + label (+ value). */
export function Legend({ color, label, value, testId }: { color: string; label: string; value?: ReactNode; testId?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-small text-muted" data-testid={testId}>
      <span aria-hidden className={cn('w-2.5 h-2.5 rounded-sm shrink-0', color)} />
      <span>{label}</span>
      {value != null && <span className="font-semibold text-primary tabular-nums" dir="ltr">{value}</span>}
    </span>
  )
}
