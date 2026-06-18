import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listOrders, request } from '../api'
import { SeverityBadge, Spinner } from '../components/ui'

// ── Simple SVG sparkline ──────────────────────────────────────────────────────

function Sparkline({ color = '#6366FF' }: { color?: string }) {
  // Placeholder data — replace with real time-series when endpoint exists
  const data = [12, 19, 15, 28, 22, 35, 30, 42, 38, 52, 47, 61]
  const max = Math.max(...data)
  const W = 200, H = 48
  const pts = data.map((v, i) => [
    (i / (data.length - 1)) * W,
    H - (v / max) * H * 0.85,
  ])
  const line  = pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'} ${x} ${y}`).join(' ')
  const fill  = `${line} L ${W} ${H} L 0 ${H} Z`

  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="w-full h-12">
      <defs>
        <linearGradient id={`grad-${color.slice(1)}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.25" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={fill} fill={`url(#grad-${color.slice(1)})`} />
      <path d={line} fill="none" stroke={color} strokeWidth="1.5" />
    </svg>
  )
}

// ── StatCard with sparkline ───────────────────────────────────────────────────

function DashStatCard({
  label,
  value,
  loading,
  color,
  delta,
}: {
  label: string
  value: number
  loading: boolean
  color?: string
  delta?: { value: number; label: string }
}) {
  return (
    <div className="card p-5 flex flex-col gap-3 overflow-hidden">
      <p className="text-caption text-muted uppercase tracking-wider">{label}</p>
      <div className="flex items-end justify-between gap-2">
        <div>
          {loading ? (
            <div className="h-10 w-16 bg-elevated rounded animate-pulse" />
          ) : (
            <p className="text-display font-light text-primary">{value.toLocaleString()}</p>
          )}
          {delta && !loading && (
            <p className={`text-small font-medium mt-1 flex items-center gap-1 ${delta.value >= 0 ? 'text-success' : 'text-danger'}`}>
              {delta.value >= 0 ? '↑' : '↓'} {Math.abs(delta.value)} {delta.label}
            </p>
          )}
        </div>
      </div>
      <Sparkline color={color ?? '#6366FF'} />
    </div>
  )
}

// ── Recent exceptions snippet ─────────────────────────────────────────────────

interface ExceptionItem {
  type: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
}

// ── Root ──────────────────────────────────────────────────────────────────────

export default function Overview() {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [stats, setStats] = useState({
    total: 0, delivered: 0, withCourier: 0, returning: 0,
  })
  const [statsLoading, setStatsLoading] = useState(true)
  const [exceptions, setExceptions]     = useState<ExceptionItem[]>([])
  const [excLoading, setExcLoading]     = useState(true)

  useEffect(() => {
    async function load() {
      try {
        const [all, delivered, withCourier, returning] = await Promise.all([
          listOrders({ size: 1 }),
          listOrders({ status: 'delivered',   size: 1 }),
          listOrders({ status: 'with_courier', size: 1 }),
          listOrders({ status: 'returning',   size: 1 }),
        ])
        setStats({
          total:       all.total,
          delivered:   delivered.total,
          withCourier: withCourier.total,
          returning:   returning.total,
        })
      } finally {
        setStatsLoading(false)
      }
    }
    load()
  }, [])

  useEffect(() => {
    request<{ items: ExceptionItem[] }>('/exceptions?size=5')
      .then(r => setExceptions(r.items))
      .catch(() => {})
      .finally(() => setExcLoading(false))
  }, [])

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('overview.title')}</h1>
        <p className="text-small text-muted mt-0.5">nothing moves untraced</p>
      </div>

      {/* ── Stat cards ── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <DashStatCard
          label={t('overview.totalOrders')}
          value={stats.total}
          loading={statsLoading}
          color="#6366FF"
        />
        <DashStatCard
          label={t('overview.delivered')}
          value={stats.delivered}
          loading={statsLoading}
          color="#22C55E"
        />
        <DashStatCard
          label={t('overview.inTransit')}
          value={stats.withCourier}
          loading={statsLoading}
          color="#22D3EE"
        />
        <DashStatCard
          label={t('overview.returns')}
          value={stats.returning}
          loading={statsLoading}
          color="#F59E0B"
        />
      </div>

      {/* ── Recent exceptions ── */}
      <div className="card p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-h3 text-primary">{t('overview.recentExceptions')}</h2>
          <Link to="/exceptions" className="text-small text-brand hover:text-brand-hover transition-colors">
            {t('overview.viewAll')} →
          </Link>
        </div>

        {excLoading ? (
          <div className="flex justify-center py-8"><Spinner /></div>
        ) : exceptions.length === 0 ? (
          <div className="flex flex-col items-center py-8 gap-2 text-muted">
            <span className="text-2xl opacity-40">✓</span>
            <p className="text-body">{t('overview.noExceptions')}</p>
          </div>
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
                  className="text-small text-brand hover:text-brand-hover whitespace-nowrap shrink-0 transition-colors"
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
