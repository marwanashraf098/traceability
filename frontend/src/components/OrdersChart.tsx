import { useTranslation } from 'react-i18next'
import type { DayCount } from '../api'
import { Spinner } from './ui'

// DS token hex values — SVG presentation attrs can't use Tailwind classes
const CHART_LINE = '#2563EB' // trace-blue
const CHART_GRID = '#262C36' // line token
const CHART_TEXT = '#828B99' // muted token

/**
 * Daily order-volume line chart — no longer rendered on Overview (superseded there
 * by the Overview dashboard rebuild's per-zone widgets), kept here as a reusable,
 * still-correct component over GET /orders/daily-counts (also unchanged/untouched).
 */
export function OrdersChart({ data, loading }: { data: DayCount[]; loading: boolean }) {
  const { t } = useTranslation()

  if (loading) return (
    <div className="h-44 flex items-center justify-center" data-testid="orders-chart-loading">
      <Spinner />
    </div>
  )

  const hasData = data.some(d => d.count > 0)
  if (!hasData) return (
    <div className="h-44 flex items-center justify-center text-muted text-small"
         data-testid="orders-chart-empty">
      {t('overview.chart.noOrders')}
    </div>
  )

  const W = 480, H = 144, padL = 28, padB = 22, padT = 8, padR = 8
  const cW = W - padL - padR
  const cH = H - padB - padT
  const max = Math.max(...data.map(d => d.count), 1)
  const len = data.length

  const xOf = (i: number) => padL + (len > 1 ? (i / (len - 1)) * cW : cW / 2)
  const yOf = (v: number) => padT + (1 - v / max) * cH

  const linePts = data.map((d, i) =>
    `${i === 0 ? 'M' : 'L'} ${xOf(i).toFixed(1)} ${yOf(d.count).toFixed(1)}`
  ).join(' ')
  const areaPts = `${linePts} L ${xOf(len - 1).toFixed(1)} ${(padT + cH).toFixed(1)} L ${xOf(0).toFixed(1)} ${(padT + cH).toFixed(1)} Z`

  const lblIdx = Array.from(new Set([0, Math.floor(len / 2), len - 1]))

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" style={{ height: 144 }}
         data-testid="orders-chart" role="img" aria-label={t('overview.chart.title')}>
      <defs>
        <linearGradient id="ocGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%"   stopColor={CHART_LINE} stopOpacity="0.22" />
          <stop offset="100%" stopColor={CHART_LINE} stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0.25, 0.5, 0.75, 1].map(f => (
        <line key={f}
              x1={padL} y1={(padT + (1 - f) * cH).toFixed(1)}
              x2={W - padR} y2={(padT + (1 - f) * cH).toFixed(1)}
              stroke={CHART_GRID} strokeWidth="1" />
      ))}
      <path d={areaPts} fill="url(#ocGrad)" />
      <path d={linePts} fill="none" stroke={CHART_LINE} strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" />
      {data.map((d, i) => (
        <circle key={d.date} cx={xOf(i).toFixed(1)} cy={yOf(d.count).toFixed(1)}
                r="3" fill={CHART_LINE} />
      ))}
      {lblIdx.map(i => (
        <text key={i} x={xOf(i).toFixed(1)} y={H - padB + 14}
              textAnchor="middle" fontSize="9" fill={CHART_TEXT}>
          {data[i]?.date?.slice(5)}
        </text>
      ))}
      <text x={padL - 4} y={padT + 4}        textAnchor="end" fontSize="9" fill={CHART_TEXT}>{max}</text>
      <text x={padL - 4} y={padT + cH + 4}   textAnchor="end" fontSize="9" fill={CHART_TEXT}>0</text>
    </svg>
  )
}
