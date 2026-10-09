import type { ReactNode } from 'react'
import { CHART } from './chartUtils'

export interface HBarRow {
  key: string
  label: ReactNode
  value: number
  /** The value as shown at the row's end. */
  display: ReactNode
  /** Extra content after the value (a rate pill). */
  extra?: ReactNode
  color?: string
  title?: string
}

/**
 * Horizontal bars (the mockup's hbars). Bars grow from the start edge, so in RTL they grow from
 * the right — done with logical CSS, the label text is never flipped.
 */
export function HBars({ rows, color = CHART.blue, max }: { rows: HBarRow[]; color?: string; max?: number }) {
  const top = max ?? Math.max(0, ...rows.map(r => r.value))
  return (
    <div className="flex flex-col gap-[9px]">
      {rows.map(r => (
        <div key={r.key} title={r.title} className="grid grid-cols-[minmax(80px,32%)_minmax(0,1fr)_auto] gap-2.5 items-center text-[13px]">
          <span className="min-w-0 truncate text-neutral-text">{r.label}</span>
          <span className="h-3 relative" aria-hidden="true">
            <i className="absolute inset-y-0 start-0 rounded-e min-w-[2px]"
              style={{ width: `${top > 0 ? Math.max(0.5, (r.value / top) * 100) : 0}%`, background: r.color ?? color }} />
          </span>
          <span className="text-end whitespace-nowrap tabular-nums flex gap-2 items-center justify-end">{r.display}{r.extra}</span>
        </div>
      ))}
    </div>
  )
}
