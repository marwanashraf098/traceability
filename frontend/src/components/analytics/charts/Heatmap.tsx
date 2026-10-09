import type { HeatCell } from '../../../analyticsApi'

const SCALE = ['#eef4fc', '#cde2fb', '#9ec5f4', '#5598e7', '#256abf', '#184f95']
/** ISO weekdays, Saturday first (the Egyptian week), as in the mockup. */
export const WEEK_ORDER = [6, 7, 1, 2, 3, 4, 5]

function shade(v: number, max: number): string {
  const r = max > 0 ? v / max : 0
  return r < 0.12 ? SCALE[0] : r < 0.3 ? SCALE[1] : r < 0.5 ? SCALE[2] : r < 0.7 ? SCALE[3] : r < 0.88 ? SCALE[4] : SCALE[5]
}

/**
 * Weekday × hour heatmap of average orders. A CSS grid, so in RTL the hours run right to left
 * with the page direction; day names come from the caller (localized).
 */
export function Heatmap({ cells, dayLabel, cellTitle, fewer, more }: {
  cells: HeatCell[]
  dayLabel: (isoWeekday: number) => string
  cellTitle: (isoWeekday: number, hour: number, avg: number | null) => string
  fewer: string
  more: string
}) {
  const at = new Map(cells.map(c => [`${c.weekday}:${c.hour}`, c]))
  const max = Math.max(0, ...cells.map(c => c.avgOrders ?? 0))
  const hours = Array.from({ length: 24 }, (_, h) => h)
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto -mx-[18px] px-[18px]">
        <div className="grid gap-[2px] text-[10.5px] text-muted min-w-[520px]" style={{ gridTemplateColumns: '34px repeat(24, minmax(14px, 1fr))' }} data-testid="heatmap">
          <span />
          {hours.map(h => <span key={h} className="text-center">{h % 3 === 0 ? h : ''}</span>)}
          {WEEK_ORDER.map(d => [
            <span key={`l${d}`} className="flex items-center">{dayLabel(d)}</span>,
            ...hours.map(h => {
              const c = at.get(`${d}:${h}`)
              const avg = c?.avgOrders ?? null
              return <i key={`${d}:${h}`} title={cellTitle(d, h, avg)} className="block rounded-sm aspect-[1.4]" style={{ background: shade(avg ?? 0, max) }} />
            }),
          ])}
        </div>
      </div>
      <div className="flex items-center gap-1.5 text-[11.5px] text-muted">
        {fewer}{SCALE.map(c => <i key={c} className="inline-block w-[18px] h-2.5 rounded-sm" style={{ background: c }} />)}{more}
      </div>
    </div>
  )
}
