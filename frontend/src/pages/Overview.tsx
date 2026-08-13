import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { LucideIcon } from 'lucide-react'
import {
  PackageCheck, Undo2, AlertTriangle, PackageX, ChevronDown, X,
  Plug, Inbox, Users as UsersIcon, CheckCircle2, Circle,
} from 'lucide-react'
import {
  request,
  getStatusTotals, getValuation, getOrdersFunnel, getThroughput,
  getRecentActivity, getReturnsPendingTotal, getExceptionsCount,
  getOnboardingStatus, dismissOnboarding,
  type StatusTotals, type Valuation, type FunnelCounts, type ThroughputDay,
  type ActivityItem, type OnboardingStatus, type OnboardingStep,
} from '../api'
import {
  EmptyState, MiniStat, Progress, SectionHeader, useMe,
  Skeleton, SeverityBadge, Spinner, StatCard,
} from '../components/ui'

// ── DS token hex values — SVG presentation attrs can't use Tailwind classes ────

const SUCCESS  = '#16A34A'
const INFO     = '#0EA5E9'
const CRITICAL = '#DC2626'
const ELEVATED = '#161B22'

// ── Generic per-zone fetch hook ─────────────────────────────────────────────
// Every zone on this page fetches independently — one slow/broken zone never
// blocks another (mockup: "Each zone loads independently"). A shared source
// (e.g. status-totals) is fetched ONCE here and passed to every zone that
// reads it — never re-fetched per widget.

function useZoneFetch<T>(fetchFn: () => Promise<T>): { data: T | null; loading: boolean; error: boolean } {
  const [data, setData]       = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(false)
    fetchFn()
      .then(d => { if (!cancelled) setData(d) })
      .catch(() => { if (!cancelled) setError(true) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { data, loading, error }
}

// ── Minimal inline error fallback — never the full-bleed critical Alert ────

function ZoneError() {
  const { t } = useTranslation()
  return <p className="text-small text-critical py-2">{t('common.error')}</p>
}

// ── Relative time (activity feed) ───────────────────────────────────────────

function relativeTime(iso: string, t: TFunction): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 60) return t('overview.time.justNow')
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return t('overview.time.minutesAgo', { count: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('overview.time.hoursAgo', { count: hours })
  const days = Math.floor(hours / 24)
  return t('overview.time.daysAgo', { count: days })
}

// ── Donut — available / reserved / damaged+lost ─────────────────────────────

function Donut({ available, reserved, damagedLost }: { available: number; reserved: number; damagedLost: number }) {
  const { t } = useTranslation()
  const total = available + reserved + damagedLost
  const R = 40, C = 2 * Math.PI * R

  if (total === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-2" data-testid="donut-empty">
        <svg width="72" height="72" viewBox="0 0 100 100">
          <circle cx="50" cy="50" r={R} fill="none" stroke={ELEVATED} strokeWidth="12" />
          <text x="50" y="55" textAnchor="middle" fontSize="16" fontWeight="700" fill="#828B99" fontFamily="monospace">0</text>
        </svg>
        <span className="text-caption text-muted">{t('overview.atAGlance.empty')}</span>
      </div>
    )
  }

  const aLen = (available / total) * C
  const rLen = (reserved / total) * C
  const dLen = (damagedLost / total) * C

  return (
    <div className="flex items-center gap-4" data-testid="donut">
      <svg width="88" height="88" viewBox="0 0 100 100" className="flex-shrink-0">
        <circle cx="50" cy="50" r={R} fill="none" stroke={ELEVATED} strokeWidth="12" />
        <circle cx="50" cy="50" r={R} fill="none" stroke={SUCCESS} strokeWidth="12"
                strokeDasharray={`${aLen} ${C}`} transform="rotate(-90 50 50)" />
        <circle cx="50" cy="50" r={R} fill="none" stroke={INFO} strokeWidth="12"
                strokeDasharray={`${rLen} ${C}`} strokeDashoffset={-aLen} transform="rotate(-90 50 50)" />
        <circle cx="50" cy="50" r={R} fill="none" stroke={CRITICAL} strokeWidth="12"
                strokeDasharray={`${dLen} ${C}`} strokeDashoffset={-(aLen + rLen)} transform="rotate(-90 50 50)" />
        <text x="50" y="47" textAnchor="middle" fontSize="15" fontWeight="700" fill="#F2F4F7" fontFamily="monospace">
          {total >= 1000 ? `${(total / 1000).toFixed(1)}K` : total}
        </text>
        <text x="50" y="61" textAnchor="middle" fontSize="8" fill="#828B99">{t('overview.atAGlance.pieces')}</text>
      </svg>
      <div className="flex flex-col gap-1.5 text-caption">
        <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-success" />{t('catalog.statuses.available')} <span className="text-muted font-mono">{available.toLocaleString()}</span></span>
        <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-info" />{t('catalog.statuses.reserved')} <span className="text-muted font-mono">{reserved.toLocaleString()}</span></span>
        <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-critical" />{t('overview.atAGlance.damagedLost')} <span className="text-muted font-mono">{damagedLost.toLocaleString()}</span></span>
      </div>
    </div>
  )
}

// ── Fulfillment funnel — today ──────────────────────────────────────────────

const FUNNEL_ROWS: { key: keyof FunnelCounts; label: string; fill: string }[] = [
  { key: 'newCount',  label: 'new',       fill: 'bg-neutral-text' },
  { key: 'picking',   label: 'picking',   fill: 'bg-info' },
  { key: 'packed',    label: 'packed',    fill: 'bg-info' },
  { key: 'courier',   label: 'courier',   fill: 'bg-info' },
  { key: 'delivered', label: 'delivered', fill: 'bg-success' },
]

function FunnelBars({ counts }: { counts: FunnelCounts }) {
  const { t } = useTranslation()
  const values = FUNNEL_ROWS.map(r => counts[r.key])
  const max = Math.max(...values, 1)
  const allZero = values.every(v => v === 0)

  if (allZero) {
    return (
      <div className="flex flex-col gap-1.5" data-testid="funnel-empty">
        {FUNNEL_ROWS.slice(0, 2).map(r => (
          <div key={r.key} className="flex items-center gap-1.5">
            <span className="w-14 text-caption text-muted">{t(`overview.funnel.${r.label}`)}</span>
            <div className="flex-1 h-[11px] bg-elevated rounded" />
          </div>
        ))}
        <span className="text-caption text-muted text-center">{t('overview.funnel.empty')}</span>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-1.5" data-testid="funnel">
      {FUNNEL_ROWS.map(r => {
        const value = counts[r.key]
        return (
          <div key={r.key} className="flex items-center gap-1.5">
            <span className="w-14 text-caption text-muted">{t(`overview.funnel.${r.label}`)}</span>
            <div className="flex-1 h-[11px] bg-elevated rounded overflow-hidden">
              <div className={`h-full ${r.fill}`} style={{ width: `${(value / max) * 100}%` }} />
            </div>
            <span className="w-6 text-caption font-mono text-end">{value}</span>
          </div>
        )
      })}
    </div>
  )
}

// ── Exceptions by severity ───────────────────────────────────────────────────

function SeverityBar({ critical, warning }: { critical: number; warning: number }) {
  const { t } = useTranslation()
  const total = critical + warning

  if (total === 0) {
    return (
      <div className="flex flex-col gap-2" data-testid="severity-empty">
        <div className="h-3.5 rounded-full bg-success" />
        <span className="text-caption text-success-text text-center">{t('overview.severity.empty')}</span>
      </div>
    )
  }

  const criticalPct = (critical / total) * 100

  return (
    <div className="flex flex-col gap-2" data-testid="severity">
      <div className="flex h-3.5 rounded-full overflow-hidden w-full">
        <div className="bg-critical" style={{ width: `${criticalPct}%` }} />
        <div className="bg-warning" style={{ width: `${100 - criticalPct}%` }} />
      </div>
      <div className="flex gap-3.5 text-caption">
        <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-critical" />{t('overview.severity.critical')} <span className="text-muted font-mono">{critical}</span></span>
        <span className="flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-warning" />{t('overview.severity.warning')} <span className="text-muted font-mono">{warning}</span></span>
      </div>
      <Link to="/exceptions" className="text-caption text-trace-blue hover:text-trace-blue-hover transition-colors">
        {t('overview.severity.viewInExceptions')}
      </Link>
    </div>
  )
}

// ── Throughput — 30d received vs shipped ────────────────────────────────────

function ThroughputChart({ data }: { data: ThroughputDay[] }) {
  const { t } = useTranslation()
  const hasData = data.some(d => d.received > 0 || d.shipped > 0)

  if (!hasData) {
    return (
      <div className="h-[70px] flex items-center justify-center text-caption text-muted" data-testid="throughput-empty">
        {t('overview.throughput.empty')}
      </div>
    )
  }

  const W = 320, H = 70
  const max = Math.max(...data.map(d => Math.max(d.received, d.shipped)), 1)
  const len = data.length
  const xOf = (i: number) => (len > 1 ? (i / (len - 1)) * W : W / 2)
  const yOf = (v: number) => H - (v / max) * H

  const line = (key: 'received' | 'shipped') =>
    data.map((d, i) => `${i === 0 ? 'M' : 'L'} ${xOf(i).toFixed(1)} ${yOf(d[key]).toFixed(1)}`).join(' ')

  return (
    <div data-testid="throughput">
      <svg width="100%" height={H} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
        <polyline points={data.map((d, i) => `${xOf(i).toFixed(1)},${yOf(d.received).toFixed(1)}`).join(' ')}
                  fill="none" stroke="#2563EB" strokeWidth="2" />
        <path d={line('shipped')} fill="none" stroke={SUCCESS} strokeWidth="2" />
      </svg>
      <div className="flex justify-between text-[9px] text-muted font-mono mt-1">
        <span>{data[0]?.date?.slice(5)}</span>
        <span>{data[data.length - 1]?.date?.slice(5)}</span>
      </div>
      <div className="flex gap-3 text-caption text-muted mt-1.5">
        <span className="flex items-center gap-1"><span className="w-3.5 h-0.5 bg-trace-blue" />{t('overview.throughput.received')}</span>
        <span className="flex items-center gap-1"><span className="w-3.5 h-0.5 bg-success" />{t('overview.throughput.shipped')}</span>
      </div>
    </div>
  )
}

// ── Recent activity ──────────────────────────────────────────────────────────

function ActivityFeed({ items }: { items: ActivityItem[] }) {
  const { t } = useTranslation()

  if (items.length === 0) {
    return <p className="text-caption text-muted py-2" data-testid="activity-empty">{t('overview.activity.empty')}</p>
  }

  return (
    <div className="flex flex-col gap-2" data-testid="activity">
      {items.map(item => (
        <div key={item.refId} className="flex flex-col gap-0.5">
          <span className="text-small text-primary">
            {item.type === 'receiving'
              ? t('overview.activity.receiving', {
                  count: item.pieceCount ?? 0,
                  location: item.locationName ?? t('common.na'),
                  actor: item.actorName ?? t('common.na'),
                })
              : t('overview.activity.stockTake', {
                  ref: item.refCode ?? item.refId.slice(0, 8),
                  actor: item.actorName ?? t('common.na'),
                })}
          </span>
          <span className="text-caption text-muted">{relativeTime(item.occurredAt, t)}</span>
        </div>
      ))}
    </div>
  )
}

// ── Onboarding card — condensed, dismissible ────────────────────────────────

const ONBOARDING_CHIPS = ['connect_shopify', 'connect_bosta', 'initial_import', 'first_receiving'] as const

function OnboardingCard({ status, onDismissed }: { status: OnboardingStatus; onDismissed: () => void }) {
  const { t } = useTranslation()
  const [dismissing, setDismissing] = useState(false)

  const steps = ONBOARDING_CHIPS
    .map(key => status.steps.find(s => s.key === key))
    .filter((s): s is OnboardingStep => !!s)
  const doneCount = steps.filter(s => s.status === 'done').length
  const pct = steps.length > 0 ? (doneCount / steps.length) * 100 : 0

  async function handleDismiss() {
    setDismissing(true)
    try { await dismissOnboarding() } catch { /* optimistic hide regardless */ }
    onDismissed()
  }

  return (
    <div className="card border-trace-blue p-4 flex items-center gap-4" data-testid="onboarding-card">
      <div className="flex-1 flex flex-col gap-2 min-w-0">
        <div className="flex items-center justify-between">
          <span className="text-small font-bold text-primary">{t('overview.onboardingCard.title')}</span>
          <button
            type="button"
            onClick={handleDismiss}
            disabled={dismissing}
            aria-label={t('overview.onboardingCard.dismiss')}
            className="text-muted hover:text-primary transition-colors"
          >
            <X size={14} strokeWidth={2} />
          </button>
        </div>
        <Progress value={pct} />
      </div>
      <div className="flex flex-wrap gap-3.5 text-caption flex-shrink-0">
        {steps.map(step => (
          <Link
            key={step.key}
            to={step.status === 'done' ? '#' : '/connections'}
            className={step.status === 'done'
              ? 'flex items-center gap-1.5 text-muted pointer-events-none'
              : 'flex items-center gap-1.5 text-trace-blue hover:text-trace-blue-hover transition-colors'}
          >
            {step.status === 'done'
              ? <CheckCircle2 size={13} strokeWidth={2} className="text-success-text" />
              : <Circle size={13} strokeWidth={2} />}
            {t(`overview.onboardingCard.chip.${step.key}`)}
          </Link>
        ))}
      </div>
    </div>
  )
}

// ── Fresh-tenant card — replaces the whole zone stack ───────────────────────

function FreshTenantCard() {
  const { t } = useTranslation()
  const items = [
    { icon: Plug,      label: t('overview.freshTenant.connectShopify'), to: '/connections' },
    { icon: Inbox,     label: t('overview.freshTenant.receiveFirst'),   to: '/receiving' },
    { icon: UsersIcon, label: t('overview.freshTenant.inviteTeam'),     to: '/users' },
  ]
  return (
    <div className="card p-5 flex flex-col gap-3" data-testid="fresh-tenant-card">
      <p className="text-small text-muted">{t('overview.freshTenant.message')}</p>
      {items.map(item => (
        <Link
          key={item.to}
          to={item.to}
          className="flex items-center gap-2.5 bg-elevated border border-line rounded-lg p-3 hover:bg-white/5 transition-colors"
        >
          <item.icon size={16} strokeWidth={1.75} className="text-trace-blue" />
          <span className="flex-1 text-small text-primary">{item.label}</span>
        </Link>
      ))}
    </div>
  )
}

// ── Needs-attention tile shells (independent per-source loading) ───────────

function AttentionTile({
  loading, error, value, label, tone, icon: Icon, to,
}: {
  loading: boolean
  error: boolean
  value: number | string
  label: React.ReactNode
  tone: 'neutral' | 'warning' | 'critical'
  icon: LucideIcon
  to: string
}) {
  if (loading) return <Skeleton className="h-[92px] rounded-2xl" />
  if (error) return <div className="card p-3.5"><ZoneError /></div>
  return (
    <Link to={to} className="block rounded-2xl hover:opacity-90 transition-opacity">
      <MiniStat icon={Icon} value={value} label={label} tone={tone} />
    </Link>
  )
}

// ── Root ──────────────────────────────────────────────────────────────────────

interface ExceptionItem {
  type: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
}

function greetingKey(): 'morning' | 'afternoon' | 'evening' {
  const h = new Date().getHours()
  if (h < 12) return 'morning'
  if (h < 18) return 'afternoon'
  return 'evening'
}

export default function Overview() {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const me   = useMe()

  const statusTotals   = useZoneFetch<StatusTotals>(getStatusTotals)
  const valuation      = useZoneFetch<Valuation>(getValuation)
  const fulfillQueue   = useZoneFetch<number>(() => request<unknown[]>('/fulfill/queue').then(r => r.length))
  const returnsPending = useZoneFetch<number>(() => getReturnsPendingTotal().then(r => r.total))
  const exceptionsCount = useZoneFetch<{ count: number; critical: number; warning: number }>(getExceptionsCount)
  const funnel     = useZoneFetch<FunnelCounts>(getOrdersFunnel)
  const throughput = useZoneFetch<ThroughputDay[]>(getThroughput)
  const activity   = useZoneFetch<ActivityItem[]>(() => getRecentActivity(5))
  const onboarding = useZoneFetch<OnboardingStatus>(getOnboardingStatus)

  const [exceptions, setExceptions]   = useState<ExceptionItem[]>([])
  const [excLoading, setExcLoading]   = useState(true)
  const [excError, setExcError]       = useState(false)

  const [onboardingHidden, setOnboardingHidden] = useState(false)

  useEffect(() => {
    request<{ items: ExceptionItem[] }>('/exceptions?size=5')
      .then(r => setExceptions(r.items))
      .catch(() => setExcError(true))
      .finally(() => setExcLoading(false))
  }, [])

  const counts = statusTotals.data?.statusCounts
  const available   = counts?.available ?? 0
  const reserved    = counts?.reserved ?? 0
  const damaged     = counts?.damaged ?? 0
  const lost        = counts?.lost ?? 0
  const totalPieces = available + reserved + damaged + lost

  const showOnboarding = !onboardingHidden && !onboarding.loading && !onboarding.error &&
    onboarding.data && !onboarding.data.dismissed && !onboarding.data.allDone

  const isFreshTenant = !statusTotals.loading && !statusTotals.error && totalPieces === 0 &&
    !onboarding.loading && !onboarding.error && onboarding.data && !onboarding.data.allDone

  return (
    <div className="max-w-6xl mx-auto space-y-6">

      {/* ── Header ── */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h1 className="text-h1 text-primary">
            {me ? t(`overview.greeting.${greetingKey()}`, { name: me.name }) : t('overview.title')}
          </h1>
          <p className="text-small text-muted mt-0.5">{t('overview.tagline')}</p>
        </div>
        <button
          type="button"
          data-testid="today-control"
          className="flex items-center gap-1.5 bg-elevated border border-line rounded-lg px-3 py-1.5 text-small text-primary"
        >
          <span>{t('overview.today')}</span>
          <ChevronDown size={12} strokeWidth={2} className="text-muted" />
        </button>
      </div>

      {isFreshTenant ? (
        <FreshTenantCard />
      ) : (
        <>
          {/* ── Onboarding card ── */}
          {showOnboarding && onboarding.data && (
            <OnboardingCard status={onboarding.data} onDismissed={() => setOnboardingHidden(true)} />
          )}

          {/* ── Needs attention ── */}
          <div>
            <SectionHeader title={t('overview.needsAttention.title')} />
            <div data-testid="needs-attention" className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              <AttentionTile
                loading={fulfillQueue.loading} error={fulfillQueue.error}
                value={fulfillQueue.data ?? 0} icon={PackageCheck} tone="neutral" to="/fulfill"
                label={t('overview.needsAttention.pendingPicks')}
              />
              <AttentionTile
                loading={returnsPending.loading} error={returnsPending.error}
                value={returnsPending.data ?? 0} icon={Undo2} tone="neutral" to="/returns"
                label={t('overview.needsAttention.awaitingInspection')}
              />
              <AttentionTile
                loading={exceptionsCount.loading} error={exceptionsCount.error}
                value={exceptionsCount.data?.count ?? 0} icon={AlertTriangle} tone="critical" to="/exceptions"
                label={
                  <>
                    {t('overview.needsAttention.openExceptions')}
                    {exceptionsCount.data && (
                      <> — {t('overview.needsAttention.openExceptionsBreakdown', {
                        critical: exceptionsCount.data.critical, warning: exceptionsCount.data.warning,
                      })}</>
                    )}
                  </>
                }
              />
              <AttentionTile
                loading={valuation.loading} error={valuation.error}
                value={valuation.data?.lowStockCount ?? 0} icon={PackageX} tone="warning" to="/catalog"
                label={t('overview.needsAttention.lowStock')}
              />
            </div>
          </div>

          {/* ── Inventory health ── */}
          <div>
            <SectionHeader title={t('overview.inventoryHealth.title')} />
            <div data-testid="inventory-health" className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2.5">
              {statusTotals.loading ? (
                Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-24 rounded-2xl" />)
              ) : statusTotals.error ? (
                <div className="col-span-full"><ZoneError /></div>
              ) : (
                <>
                  <StatCard label={t('overview.inventoryHealth.totalPieces')} value={totalPieces} />
                  <Link to="/inventory?status=available" className="block rounded-2xl hover:opacity-90 transition-opacity">
                    <StatCard label={t('catalog.statuses.available')} value={available} />
                  </Link>
                  <Link to="/inventory?status=reserved" className="block rounded-2xl hover:opacity-90 transition-opacity">
                    <StatCard label={t('catalog.statuses.reserved')} value={reserved} />
                  </Link>
                  <Link to="/inventory?status=damaged" className="block rounded-2xl hover:opacity-90 transition-opacity">
                    <StatCard label={t('catalog.statuses.damaged')} value={damaged} />
                  </Link>
                  <Link to="/inventory?status=lost" className="block rounded-2xl hover:opacity-90 transition-opacity">
                    <StatCard label={t('catalog.statuses.lost')} value={lost} />
                  </Link>
                </>
              )}
              {valuation.loading ? (
                <Skeleton className="h-24 rounded-2xl" />
              ) : valuation.error ? (
                <ZoneError />
              ) : valuation.data && valuation.data.variantsCosted === 0 ? (
                <div className="card p-5 flex flex-col gap-2" data-testid="inventory-value-not-set-up">
                  <p className="text-small text-muted uppercase tracking-wider">{t('overview.inventoryHealth.value')}</p>
                  <p className="text-caption text-muted">{t('overview.inventoryHealth.valueNotSetUp')}</p>
                </div>
              ) : valuation.data && (
                <div className="card p-5 flex flex-col gap-2 border-trace-blue shadow-ring-accent" data-testid="inventory-value">
                  <p className="text-small text-muted uppercase tracking-wider">{t('overview.inventoryHealth.value')}</p>
                  <p className="text-h2 font-mono text-trace-blue">EGP {valuation.data.inventoryValue.toLocaleString()}</p>
                  {valuation.data.variantsCosted < valuation.data.variantsTotal && (
                    <p className="text-caption text-muted">
                      {t('overview.inventoryHealth.valueCaveat', {
                        costed: valuation.data.variantsCosted, total: valuation.data.variantsTotal,
                      })}
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* ── At a glance ── */}
          <div>
            <SectionHeader title={t('overview.atAGlance.title')} />
            <div data-testid="at-a-glance" className="grid grid-cols-1 lg:grid-cols-3 gap-3.5">
              <div className="card p-4">
                {statusTotals.loading ? <Skeleton className="h-24 rounded-xl" /> : statusTotals.error ? <ZoneError /> : (
                  <Donut available={available} reserved={reserved} damagedLost={damaged + lost} />
                )}
              </div>
              <div className="card p-4 flex flex-col gap-2">
                <p className="text-caption font-bold text-muted uppercase">{t('overview.funnel.title')}</p>
                {funnel.loading ? <Skeleton className="h-24 rounded-xl" /> : funnel.error ? <ZoneError /> : funnel.data && (
                  <FunnelBars counts={funnel.data} />
                )}
              </div>
              <div className="card p-4 flex flex-col gap-2">
                <p className="text-caption font-bold text-muted uppercase">{t('overview.severity.title')}</p>
                {exceptionsCount.loading ? <Skeleton className="h-24 rounded-xl" /> : exceptionsCount.error ? <ZoneError /> : exceptionsCount.data && (
                  <SeverityBar critical={exceptionsCount.data.critical} warning={exceptionsCount.data.warning} />
                )}
              </div>
            </div>
          </div>

          {/* ── Throughput + Activity ── */}
          <div className="grid grid-cols-1 lg:grid-cols-[1.3fr_1fr] gap-3.5">
            <div className="card p-4 flex flex-col gap-2.5">
              <p className="text-caption font-bold text-muted uppercase">{t('overview.throughput.title')}</p>
              {throughput.loading ? <Skeleton className="h-24 rounded-xl" /> : throughput.error ? <ZoneError /> : throughput.data && (
                <ThroughputChart data={throughput.data} />
              )}
            </div>
            <div className="card p-4 flex flex-col gap-2">
              <p className="text-caption font-bold text-muted uppercase">{t('overview.activity.title')}</p>
              {activity.loading ? <Skeleton className="h-24 rounded-xl" /> : activity.error ? <ZoneError /> : activity.data && (
                <ActivityFeed items={activity.data} />
              )}
            </div>
          </div>

          {/* ── Recent exceptions ── */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <p className="text-caption font-bold text-muted uppercase">{t('overview.recentExceptions')}</p>
              <Link to="/exceptions" className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
                {t('overview.viewAll')} →
              </Link>
            </div>
            <div className="card p-5">
              {excLoading ? (
                <div className="flex justify-center py-8"><Spinner /></div>
              ) : excError ? (
                <ZoneError />
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
        </>
      )}
    </div>
  )
}
