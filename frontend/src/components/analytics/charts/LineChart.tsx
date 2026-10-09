import { useState } from 'react'
import { CHART, mirrorX, niceMax } from './chartUtils'

export interface LineSeries {
  name: string
  color: string
  values: number[]
  /** Shade the area under the line. */
  area?: boolean
  format?: (v: number) => string
}

/**
 * Line chart with up to a few series over the same labels (the mockup's lineChart). RTL mirrors
 * the time axis (latest day on the left, y labels on the right); text is never flipped. Hovering a
 * column shows every series' value for that label.
 */
export function LineChart({
  labels, series, formatY, rtl = false, height = 230, every, ariaLabel,
}: {
  labels: string[]
  series: LineSeries[]
  formatY: (v: number) => string
  rtl?: boolean
  height?: number
  /** Show every n-th x label (the last is always shown). */
  every?: number
  ariaLabel: string
}) {
  const [hover, setHover] = useState<number | null>(null)
  const W = 640, H = height, PAD_AXIS = 50, PAD_END = 14, T = 12, B = 26
  const n = labels.length
  const L = rtl ? PAD_END : PAD_AXIS, R = rtl ? PAD_AXIS : PAD_END
  const maxV = Math.max(0, ...series.flatMap(s => s.values))
  const max = niceMax(maxV * 1.04)
  const m = mirrorX(rtl, W)
  // x in left-to-right terms from the axis side, then mirrored for RTL.
  const xl = (i: number) => PAD_AXIS + (W - PAD_AXIS - PAD_END) * (n <= 1 ? 0.5 : i / (n - 1))
  const x = (i: number) => m(xl(i))
  const y = (v: number) => T + (H - T - B) * (1 - v / max)
  const step = every ?? Math.max(1, Math.ceil(n / 6))
  const bw = (W - L - R) / Math.max(1, n - 1)
  const axisX = rtl ? W - PAD_AXIS + 8 : PAD_AXIS - 8

  return (
    <div className="flex flex-col gap-2">
      {series.length > 1 && (
        <div className="flex flex-wrap gap-x-4 gap-y-1.5 text-[12.5px] text-neutral-text">
          {series.map(s => (
            <span key={s.name} className="inline-flex items-center gap-1.5">
              <i className="w-3.5 h-[3px] rounded-sm inline-block" style={{ background: s.color }} />{s.name}
            </span>
          ))}
        </div>
      )}
      <div className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-auto block overflow-visible" role="img" aria-label={ariaLabel}>
          <g>
            {[0, 1, 2, 3, 4].map(t => {
              const v = (max * t) / 4
              return (
                <g key={t}>
                  <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} stroke={CHART.grid} />
                  <text x={axisX} y={y(v) + 4} textAnchor={rtl ? 'start' : 'end'} fontSize={11} fill={CHART.label}>{formatY(v)}</text>
                </g>
              )
            })}
          </g>
          <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} stroke={CHART.base} />
          {series.map(s => {
            if (n === 0) return null
            const d = s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('')
            const last = s.values[n - 1]
            return (
              <g key={s.name}>
                {s.area && <path d={`${d}L${x(n - 1)},${y(0)}L${x(0)},${y(0)}Z`} fill={s.color} opacity={0.1} />}
                <path d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
                <circle cx={x(n - 1)} cy={y(last)} r={4.5} fill={s.color} stroke="#fff" strokeWidth={2} />
              </g>
            )
          })}
          {labels.map((l, i) => (i % step === 0 || i === n - 1) ? (
            <text key={i} x={x(i)} y={H - 6} textAnchor="middle" fontSize={11} fill={CHART.label}>{l}</text>
          ) : null)}
          {hover != null && <line x1={x(hover)} x2={x(hover)} y1={T} y2={H - B} stroke={CHART.base} strokeDasharray="3 3" />}
          {labels.map((_, i) => (
            <rect key={i} x={x(i) - bw / 2} y={T} width={bw} height={H - T - B} fill="transparent"
              onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
          ))}
        </svg>
        {hover != null && (
          <div role="tooltip" className="absolute top-0 pointer-events-none bg-primary text-white text-[12px] leading-snug px-2.5 py-1.5 rounded-md shadow-e3 whitespace-pre-line"
            style={{ [rtl ? 'right' : 'left']: `${Math.min(80, ((rtl ? W - x(hover) : x(hover)) / W) * 100)}%` }}>
            {labels[hover]}{series.map(s => `\n${s.name}: ${(s.format ?? formatY)(s.values[hover])}`).join('')}
          </div>
        )}
      </div>
    </div>
  )
}
