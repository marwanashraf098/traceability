import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getInventorySummary, getOrderDailyCounts, DayCount, InventorySummary, request } from '../api'
import { Alert, Badge, EmptyState, Skeleton, SeverityBadge, Spinner, StatCard } from '../components/ui'

// ── Section header ────────────────────────────────────────────────────────────

function SectionHeader({ title, note, badge }: { title: string; note: string; badge?: string }) {
  return (
    <div className="flex items-center gap-3 mb-3">
      <div>
        <h2 className="text-h3 text-primary inline-flex items-center gap-2">
          {title}
          {badge && <Badge tone="info" label={badge} />}
        </h2>
        <p className="text-caption text-muted mt-0.5">{note}</p>
      </div>
    </div>
  )
}

// ── Exception snippet ─────────────────────────────────────────────────────────

interface ExceptionItem {
  type: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
}

// ── Orders chart ─────────────────────────────────────────────────────────────

// DS token hex values — SVG presentation attrs can't use Tailwind classes
const CHART_LINE  = '#2563EB' // trace-blue
const CHART_GRID  = '#262C36' // line token
const CHART_TEXT  = '#828B99' // muted token

function OrdersChart({ data, loading }: { data: DayCount[]; loading: boolean }) {
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

// ── Root ──────────────────────────────────────────────────────────────────────

export default function Overview() {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [summary,     setSummary]     = useState<InventorySummary | null>(null)
  const [invLoading,  setInvLoading]  = useState(true)
  const [invError,    setInvError]    = useState('')

  const [exceptions,  setExceptions]  = useState<ExceptionItem[]>([])
  const [excLoading,  setExcLoading]  = useState(true)

  const [chartData,    setChartData]    = useState<DayCount[]>([])
  const [chartLoading, setChartLoading] = useState(true)

  useEffect(() => {
    getOrderDailyCounts()
      .then(d => setChartData(Array.isArray(d) ? d : []))
      .catch(() => setChartData([]))
      .finally(() => setChartLoading(false))
  }, [])

  useEffect(() => {
    getInventorySummary()
      .then(s => setSummary(s))
      .catch(() => setInvError(t('common.error')))
      .finally(() => setInvLoading(false))
  }, [t])

  useEffect(() => {
    request<{ items: ExceptionItem[] }>('/exceptions?size=5')
      .then(r => setExceptions(r.items))
      .catch(() => {})
      .finally(() => setExcLoading(false))
  }, [])

  const groupA = summary?.groupA ?? []
  const groupB = summary?.groupB ?? []

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('overview.title')}</h1>
        <p className="text-small text-muted mt-0.5">nothing moves untraced</p>
      </div>

      {/* ── Inventory error ── */}
      {invError && (
        <div data-testid="inventory-error">
          <Alert tone="critical" title={invError} />
        </div>
      )}

      {/* ── Group A: live inventory ── */}
      {!invError && (
        <div>
          <SectionHeader
            title={t('inventory.live')}
            note={t('inventory.liveNote')}
          />
          <div data-testid="group-a" className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
            {invLoading
              ? Array.from({ length: 6 }).map((_, i) => (
                  <Skeleton key={i} className="h-28 rounded-2xl" />
                ))
              : groupA.map(({ status, count }) => {
                  const dest = `/inventory?status=${status}`
                  return (
                    <Link
                      key={status}
                      to={dest}
                      data-testid={`tile-${status}`}
                      className="block rounded-2xl hover:opacity-90 transition-opacity"
                    >
                      <StatCard
                        label={t(`catalog.statuses.${status}`, status)}
                        value={count}
                      />
                    </Link>
                  )
                })}
          </div>
        </div>
      )}

      {/* ── Group B: last 30 days ── */}
      {!invError && (
        <div>
          <SectionHeader
            title={t('inventory.window30d')}
            note={t('inventory.window30dNote')}
            badge="30d"
          />
          <div data-testid="group-b" className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            {invLoading
              ? Array.from({ length: 3 }).map((_, i) => (
                  <Skeleton key={i} className="h-28 rounded-2xl" />
                ))
              : groupB.map(({ status, count }) => {
                  const dest = `/inventory?status=${status}&within30d=true`
                  return (
                    <Link
                      key={status}
                      to={dest}
                      data-testid={`tile-${status}`}
                      className="block rounded-2xl hover:opacity-90 transition-opacity"
                    >
                      <StatCard
                        label={t(`catalog.statuses.${status}`, status)}
                        value={count}
                      />
                    </Link>
                  )
                })}
          </div>
        </div>
      )}

      {/* ── Orders over time chart ── */}
      <div className="card p-5">
        <h2 className="text-h3 text-primary mb-1">{t('overview.chart.title')}</h2>
        <p className="text-caption text-muted mb-4">{t('overview.chart.subtitle')}</p>
        <OrdersChart data={chartData} loading={chartLoading} />
      </div>

      {/* ── Recent exceptions ── */}
      <div className="card p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-h3 text-primary">{t('overview.recentExceptions')}</h2>
          <Link
            to="/exceptions"
            className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors"
          >
            {t('overview.viewAll')} →
          </Link>
        </div>

        {excLoading ? (
          <div className="flex justify-center py-8"><Spinner /></div>
        ) : exceptions.length === 0 ? (
          <EmptyState icon="✓" message={t('overview.noExceptions')} />
        ) : (
          <div className="divide-y divide-line">
            {exceptions.map((exc, i) => (
              <div key={i} className="flex items-start justify-between gap-4 py-3">
                <div className="flex items-start gap-3 min-w-0">
                  <SeverityBadge severity={exc.severity} />
                  <p className="text-body text-primary truncate">
                    {isAr ? exc.descriptionAr : exc.descriptionEn}
                  </p>
                </div>
                <Link
                  to={exc.actionUrl}
                  className="text-small text-trace-blue hover:text-trace-blue-hover whitespace-nowrap shrink-0 transition-colors"
                >
                  View →
                </Link>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
