import { useState } from 'react'
import { CHART, mirrorX, niceMax } from './chartUtils'

export interface WaterfallStep {
  key: string
  label: string
  /** Positive for a total, negative for a deduction. */
  value: number
  total?: boolean
  hero?: boolean
  color: string
  note?: string
}

/**
 * Revenue waterfall (the mockup's waterfall): a starting total, deductions floating from the
 * running total, and a final total (the hero). RTL runs the steps right to left; text unflipped.
 */
export function Waterfall({ steps, format, formatAxis, rtl = false, ariaLabel }: {
  steps: WaterfallStep[]
  format: (v: number) => string
  formatAxis: (v: number) => string
  rtl?: boolean
  ariaLabel: string
}) {
  const [hover, setHover] = useState<number | null>(null)
  const W = 640, H = 250, L = 8, R = 8, T = 26, B = 40, n = steps.length
  let run = 0
  const bars = steps.map(s => {
    if (s.total) { run = s.value; return { ...s, from: 0, to: s.value } }
    const b = { ...s, from: run + s.value, to: run }
    run += s.value
    return b
  })
  const max = niceMax(Math.max(1, ...bars.map(b => Math.max(b.from, b.to))))
  const slot = (W - L - R) / Math.max(1, n), bw = Math.min(56, slot * 0.62)
  const y = (v: number) => T + (H - T - B) * (1 - Math.max(0, v) / max)
  const m = mirrorX(rtl, W)
  const cxOf = (i: number) => m(L + slot * i + slot / 2)

  return (
    <div className="relative">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-auto block overflow-visible" role="img" aria-label={ariaLabel}>
        <line x1={L} x2={W - R} y1={y(0)} y2={y(0)} stroke={CHART.base} />
        {bars.map((b, i) => {
          const cx = cxOf(i)
          const top = y(Math.max(b.from, b.to))
          const h = Math.max(2, Math.abs(y(b.from) - y(b.to)))
          const words = b.label.split(' ')
          const half = Math.ceil(words.length / 2)
          const l1 = words.slice(0, half).join(' '), l2 = words.slice(half).join(' ')
          const next = i < n - 1 ? cxOf(i + 1) : null
          const yy = y(b.total ? b.to : b.from)
          const heroText = b.hero ? { fill: '#15803d', fontWeight: 700 } : {}
          return (
            <g key={b.key}>
              {b.hero && <rect x={cx - slot / 2 + 4} y={T - 22} width={slot - 8} height={H - T - B + 22 + 36} rx={8} fill="#e8f6ed" />}
              <rect x={cx - bw / 2} y={top} width={bw} height={h} rx={3} fill={b.color} />
              {next != null && (
                <line x1={rtl ? cx - bw / 2 : cx + bw / 2} x2={rtl ? next + bw / 2 : next - bw / 2} y1={yy} y2={yy} stroke={CHART.base} />
              )}
              <text x={cx} y={top - 7} textAnchor="middle" fontSize={b.hero ? 13 : 11} fontWeight={b.hero ? 700 : 600}
                fill={b.hero ? '#15803d' : '#4B5563'}>{b.total ? '' : '−'}{formatAxis(Math.abs(b.value))}</text>
              <text x={cx} y={H - B + 16} textAnchor="middle" fontSize={11} fill={CHART.label} {...heroText}>{l1}</text>
              <text x={cx} y={H - B + 30} textAnchor="middle" fontSize={11} fill={CHART.label} {...heroText}>{l2}</text>
              <rect x={cx - slot / 2} y={T - 20} width={slot} height={H - T - B + 20} fill="transparent"
                onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} data-testid={`wf-${b.key}`} />
            </g>
          )
        })}
      </svg>
      {hover != null && (
        <div role="tooltip" className="absolute top-0 pointer-events-none bg-primary text-white text-[12px] leading-snug px-2.5 py-1.5 rounded-md shadow-e3 whitespace-pre-line max-w-[260px]"
          style={{ [rtl ? 'right' : 'left']: `${Math.min(70, (((rtl ? W - cxOf(hover) : cxOf(hover)) - slot / 2) / W) * 100)}%` }}>
          {`${steps[hover].label}\n${steps[hover].total ? '' : '−'}${format(Math.abs(steps[hover].value))}${steps[hover].note ? `\n${steps[hover].note}` : ''}`}
        </div>
      )}
    </div>
  )
}
