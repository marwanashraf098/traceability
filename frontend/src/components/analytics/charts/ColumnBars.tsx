import { CHART } from './chartUtils'

export interface Column {
  key: string
  label: string
  value: number
  display: string
  color?: string
  title?: string
}

/**
 * Vertical bars with the value on top and the label below (the mockup's "cols": stock age, size
 * curve). A flex row, so in RTL the first column sits on the right with the page direction.
 */
export function ColumnBars({ columns, color = CHART.blue, testId }: { columns: Column[]; color?: string; testId?: string }) {
  const max = Math.max(0, ...columns.map(c => c.value))
  return (
    <div data-testid={testId}>
      <div className="flex items-end gap-3.5 h-[170px] pt-[18px] border-b border-grey-100">
        {columns.map(c => (
          <div key={c.key} title={c.title} className="flex-1 min-w-0 h-full flex flex-col items-center justify-end gap-1">
            <b className="text-[12px] font-semibold text-neutral-text tabular-nums">{c.display}</b>
            <i className="block w-[min(46px,100%)] rounded-t" style={{ height: `${max > 0 ? Math.max(1, (c.value / max) * 100) : 0}%`, background: c.color ?? color }} />
          </div>
        ))}
      </div>
      <div className="flex gap-3.5 text-[12px] text-muted mt-1.5">
        {columns.map(c => <span key={c.key} className="flex-1 min-w-0 text-center truncate">{c.label}</span>)}
      </div>
    </div>
  )
}
