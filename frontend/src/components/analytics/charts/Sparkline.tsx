import { CHART, mirrorX } from './chartUtils'

/** 72×22 sparkline of a daily series (latest point marked); RTL runs right to left. */
export function Sparkline({ values, color = CHART.blue, rtl = false, label }: {
  values: number[]
  color?: string
  rtl?: boolean
  label?: string
}) {
  const W = 72, H = 22, n = values.length
  if (n === 0) return <svg className="w-[72px] h-[22px] block" viewBox={`0 0 ${W} ${H}`} aria-hidden="true" />
  const mx = Math.max(...values), mn = Math.min(...values)
  const m = mirrorX(rtl, W)
  const pts = values.map((v, i) => [m((n <= 1 ? 0.5 : i / (n - 1)) * (W - 4) + 2), H - 3 - ((v - mn) / ((mx - mn) || 1)) * (H - 6)])
  const last = pts[n - 1]
  return (
    <svg className="w-[72px] h-[22px] block" viewBox={`0 0 ${W} ${H}`} role={label ? 'img' : undefined}
      aria-label={label} aria-hidden={label ? undefined : true}>
      <polyline points={pts.map(p => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ')} fill="none" stroke={color} strokeWidth={1.6} strokeLinejoin="round" />
      <circle cx={last[0]} cy={last[1]} r={2.4} fill={color} />
    </svg>
  )
}
