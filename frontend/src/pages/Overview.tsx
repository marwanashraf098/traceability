import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
  AlertTriangle, PackageX, ChevronDown, X,
  Plug, Inbox, Users as UsersIcon, CheckCircle2, Circle,
  Truck, Unlink, ShoppingBag, PackageSearch, PackageCheck,
  Repeat, Search,
} from 'lucide-react'
import {
  request,
  getStatusTotals, getValuation, getOrdersFunnel, getOnboardingStatus, dismissOnboarding,
  getOverviewTrends, getOverviewTopSkus, getOrdersSummary, listOrders,
  type StatusTotals, type Valuation, type FunnelCounts,
  type OnboardingStatus, type OnboardingStep,
  type MetricTrend, type TrendPoint, type TopSku, type OrderSummaryCounts, type OrderSummary,
} from '../api'
import {
  EmptyState, Progress, useMe,
  Skeleton, Spinner, ProductThumb, DeliveryBadge, cn,
} from '../components/ui'

// ── DS token hex values — SVG presentation attrs can't use Tailwind classes ────
// Mirrors tailwind.config.js exactly — never introduce a hex value that isn't
// already a named DS token there.

const SUCCESS     = '#16A34A'
const INFO        = '#0EA5E9'
const CRITICAL    = '#DC2626'
const WARNING     = '#F59E0B'
const TRACE_BLUE  = '#2563EB'
const NEUTRAL_TXT = '#9CA6B2'
const GREY_600    = '#2A333F'
const ELEVATED    = '#161B22'

// ── Generic per-zone fetch hook ─────────────────────────────────────────────
// Every zone on this page fetches independently — one slow/broken zone never
// blocks another. A shared source is fetched ONCE and passed to every widget
// that reads it — never re-fetched per widget.

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

// ── Relative time ────────────────────────────────────────────────────────────

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

// ── Sparkline — same inline-SVG polyline+gradient-area pattern as the prior
// throughput chart (no charting library anywhere in this codebase). Stroke
// color is always one of the DS token hex consts above, never a raw mockup hex.

function Sparkline({ series, color }: { series: TrendPoint[]; color: string }) {
  if (series.length < 2) return null
  const W = 76, H = 32
  const values = series.map(p => p.count)
  const max = Math.max(...values, 1)
  const min = Math.min(0, ...values)
  const range = max - min || 1
  const xOf = (i: number) => (i / (series.length - 1)) * W
  const yOf = (v: number) => H - ((v - min) / range) * H
  const points = series.map((p, i) => `${xOf(i).toFixed(1)},${yOf(p.count).toFixed(1)}`).join(' ')
  const gid = `spark-${color.replace('#', '')}`

  return (
    <svg className="w-[76px] h-[32px] flex-shrink-0" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
      <defs>
        <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.28" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <polygon points={`${points} ${W},${H} 0,${H}`} fill={`url(#${gid})`} />
      <polyline points={points} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

// ── Stat cards with sparkline + "vs yesterday" delta ────────────────────────
// DELTA COLOR = improvement, not arrow direction. The arrow always shows the
// actual sign of deltaPct; goodDirection picks which sign is success-toned.
// deltaPct === null (yesterday=0, guarded server-side) renders "—", never a
// fabricated infinite/undefined percentage.

const STAT_DEFS: {
  metric: MetricTrend['metric']
  labelKey: string
  color: string
  goodDirection: 'up' | 'down'
}[] = [
  { metric: 'orders',     labelKey: 'nav.orders',              color: INFO,       goodDirection: 'up' },
  { metric: 'shipments',  labelKey: 'overview.stats.shipments', color: TRACE_BLUE, goodDirection: 'up' },
  { metric: 'delivered',  labelKey: 'orders.pipeline.delivered', color: SUCCESS,   goodDirection: 'up' },
  { metric: 'exceptions', labelKey: 'nav.exceptions',           color: CRITICAL,   goodDirection: 'down' },
  { metric: 'returns',    labelKey: 'nav.returns',              color: WARNING,    goodDirection: 'down' },
]

function SparkStatCard({
  label, trend, color, goodDirection,
}: {
  label: string
  trend: MetricTrend | undefined
  color: string
  goodDirection: 'up' | 'down'
}) {
  const { t } = useTranslation()
  if (!trend) return <Skeleton className="h-[118px] rounded-2xl" />

  const deltaPct = trend.deltaPct
  const up = (deltaPct ?? 0) >= 0
  const isGood = goodDirection === 'up' ? up : !up

  return (
    <div className="card p-5 flex flex-col gap-1" data-testid={`stat-${trend.metric}`}>
      <p className="text-small text-muted font-medium">{label}</p>
      <div className="flex items-end justify-between gap-2">
        <p className="text-h2 font-mono text-primary">{trend.today.toLocaleString()}</p>
        {trend.series.length > 0 && <Sparkline series={trend.series} color={color} />}
      </div>
      {deltaPct === null ? (
        <p className="text-small text-muted mt-1">
          — <span>{t('overview.stats.vsYesterday')}</span>
        </p>
      ) : (
        <p className={cn('text-small font-semibold flex items-center gap-1 mt-1', isGood ? 'text-success' : 'text-critical')}>
          <span>{up ? '↑' : '↓'}</span>
          <span>{Math.abs(deltaPct).toFixed(1)}%</span>
          <span className="text-muted font-normal">{t('overview.stats.vsYesterday')}</span>
        </p>
      )}
    </div>
  )
}

// ── Live-ops flow strip ──────────────────────────────────────────────────────
// Reuses /orders/funnel (today, 5 buckets) — the SAME source the old funnel
// bars used, just restyled as nodes+arrows. Deliberately 5 nodes, not the
// mockup's 6: no separate "Warehouse/in stock" pipeline stage exists (that's
// a different concept, total available inventory, not an order-flow stage),
// and "Out for delivery" isn't split from "In transit" — no data signal
// distinguishes them (Bosta's granular codes collapse into the 9-value
// shipment_internal_state enum before reaching the app), the exact same cut
// already made for the Orders-detail stepper restyle.

const FLOW_NODES: { key: keyof FunnelCounts; labelKey: string; icon: typeof ShoppingBag }[] = [
  { key: 'newCount',  labelKey: 'overview.funnel.new',      icon: ShoppingBag },
  { key: 'picking',   labelKey: 'overview.funnel.picking',  icon: PackageSearch },
  { key: 'packed',    labelKey: 'overview.funnel.packed',   icon: PackageCheck },
  { key: 'courier',   labelKey: 'overview.funnel.courier',  icon: Truck },
  { key: 'delivered', labelKey: 'overview.funnel.delivered', icon: CheckCircle2 },
]

function FlowStrip({ counts }: { counts: FunnelCounts }) {
  const { t } = useTranslation()
  const values = FLOW_NODES.map(n => counts[n.key])
  const allZero = values.every(v => v === 0)
  const inProgress = counts.newCount + counts.picking + counts.packed + counts.courier
  const completed  = counts.delivered
  const pct = completed + inProgress > 0 ? (completed / (completed + inProgress)) * 100 : 0

  if (allZero) {
    return <p className="text-caption text-muted text-center py-8" data-testid="flow-empty">{t('overview.funnel.empty')}</p>
  }

  return (
    <div data-testid="flow-strip">
      <div className="flex items-start gap-1 mt-2">
        {FLOW_NODES.map((node, i) => {
          const isLast = i === FLOW_NODES.length - 1
          const Icon = node.icon
          return (
            <div key={node.key} className="flex items-start flex-1 min-w-0">
              <div className="flex flex-col items-center gap-2 flex-1 text-center min-w-0">
                <div className={cn(
                  'w-12 h-12 rounded-2xl border flex items-center justify-center flex-shrink-0',
                  isLast ? 'bg-success/[0.12] border-success/40 text-success-text' : 'bg-elevated border-line text-muted'
                )}>
                  <Icon size={20} strokeWidth={1.75} />
                </div>
                <p className="text-h4 font-mono text-primary leading-none">{values[i]}</p>
                <p className="text-caption text-muted font-semibold truncate w-full">{t(node.labelKey)}</p>
              </div>
              {!isLast && <span className="text-muted mt-4 px-0.5 flex-shrink-0">→</span>}
            </div>
          )
        })}
      </div>
      <div className="flex items-center gap-3.5 mt-5">
        <span className="text-caption text-muted whitespace-nowrap">
          {t('overview.flow.inProgress')} <b className="text-primary font-mono">{inProgress}</b>
        </span>
        <Progress value={pct} className="flex-1" />
        <span className="text-caption text-muted whitespace-nowrap">
          {t('overview.flow.completed')} <b className="text-primary font-mono">{completed}</b>
        </span>
      </div>
    </div>
  )
}

// ── Alerts panel ─────────────────────────────────────────────────────────────
// 4 real signals, no fabricated ones: 3 ExceptionService detectors (ndr_failed,
// stuck_shipment, unmatched_delivery — filtered via the existing GET /exceptions
// ?type= param, same endpoint Exceptions.tsx already uses) plus /inventory/
// valuation's lowStockCount (already live on this page's predecessor — not an
// ExceptionService detector, but real, not invented). Descriptions come straight
// from the backend's existing bilingual descriptionEn/descriptionAr — never a
// new hardcoded English map. Degrades gracefully: an empty type is simply
// omitted, not shown as a blank/broken row.

interface AlertException {
  type: string
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
  ageSeconds: number
}

interface AlertRow {
  key: string
  icon: typeof AlertTriangle
  tone: 'critical' | 'warning' | 'info'
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
  ageSeconds: number
}

const ALERT_TONE_CLASSES: Record<AlertRow['tone'], string> = {
  critical: 'bg-critical/[0.13] text-critical-text',
  warning:  'bg-warning/[0.13] text-warning-text',
  info:     'bg-info/[0.13] text-info-text',
}

function AlertsPanel({
  ndrFailed, stuckShipment, unmatchedDelivery, lowStockCount,
}: {
  ndrFailed: AlertException[]
  stuckShipment: AlertException[]
  unmatchedDelivery: AlertException[]
  lowStockCount: number
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const rows: AlertRow[] = []
  if (ndrFailed[0]) {
    rows.push({
      key: 'ndr_failed', icon: AlertTriangle, tone: 'critical',
      descriptionEn: ndrFailed[0].descriptionEn, descriptionAr: ndrFailed[0].descriptionAr,
      actionUrl: ndrFailed[0].actionUrl, ageSeconds: ndrFailed[0].ageSeconds,
    })
  }
  if (stuckShipment[0]) {
    rows.push({
      key: 'stuck_shipment', icon: Truck, tone: 'warning',
      descriptionEn: stuckShipment[0].descriptionEn, descriptionAr: stuckShipment[0].descriptionAr,
      actionUrl: stuckShipment[0].actionUrl, ageSeconds: stuckShipment[0].ageSeconds,
    })
  }
  if (lowStockCount > 0) {
    rows.push({
      key: 'low_stock', icon: PackageX, tone: 'warning',
      descriptionEn: t('overview.needsAttention.lowStock', { lng: 'en' }),
      descriptionAr: t('overview.needsAttention.lowStock', { lng: 'ar' }),
      actionUrl: '/catalog', ageSeconds: 0,
    })
  }
  if (unmatchedDelivery[0]) {
    rows.push({
      key: 'unmatched_delivery', icon: Unlink, tone: 'info',
      descriptionEn: unmatchedDelivery[0].descriptionEn, descriptionAr: unmatchedDelivery[0].descriptionAr,
      actionUrl: unmatchedDelivery[0].actionUrl, ageSeconds: unmatchedDelivery[0].ageSeconds,
    })
  }

  if (rows.length === 0) {
    return <EmptyState icon="✓" message={t('overview.alerts.empty')} />
  }

  return (
    <div className="flex flex-col">
      {rows.map((row, i) => {
        const Icon = row.icon
        return (
          <div
            key={row.key}
            className={cn('flex items-start gap-3 py-3', i > 0 && 'border-t border-line')}
          >
            <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0', ALERT_TONE_CLASSES[row.tone])}>
              <Icon size={16} strokeWidth={1.75} />
            </div>
            <div className="flex-1 min-w-0">
              <Link to={row.actionUrl} className="text-small text-primary hover:text-trace-blue transition-colors block">
                {isAr ? row.descriptionAr : row.descriptionEn}
              </Link>
            </div>
            {row.ageSeconds > 0 && (
              <span className="text-caption text-muted whitespace-nowrap">
                {relativeTime(new Date(Date.now() - row.ageSeconds * 1000).toISOString(), t)}
              </span>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ── Top-selling SKUs ─────────────────────────────────────────────────────────
// Units ordered per SKU, rolling 30 Cairo-days, cancelled orders excluded —
// label says exactly that, never "by order volume" (that would be order count,
// a different metric this endpoint doesn't compute).

function TopSkusList({ skus }: { skus: TopSku[] }) {
  const { t } = useTranslation()
  if (skus.length === 0) {
    return <EmptyState icon="—" message={t('overview.topSkus.empty')} />
  }
  return (
    <div className="flex flex-col">
      {skus.map((sku, i) => (
        <div key={`${sku.sku ?? sku.title}-${i}`} className={cn('flex items-center gap-3 py-2.5', i > 0 && 'border-t border-line')}>
          <span className="w-4 text-caption text-muted font-semibold text-center flex-shrink-0">{i + 1}</span>
          <ProductThumb src={sku.imageUrl} alt={sku.title} size={34} />
          <div className="flex-1 min-w-0">
            <p className="text-small text-primary truncate">{sku.title}</p>
            {sku.sku && <p className="text-caption text-muted font-mono truncate">{sku.sku}</p>}
          </div>
          <span className="text-small font-bold font-mono text-primary flex-shrink-0">{sku.units.toLocaleString()}</span>
        </div>
      ))}
    </div>
  )
}

// ── Orders-by-status donut ───────────────────────────────────────────────────
// Sourced from /orders/summary (all-time, the SAME endpoint the Orders list's
// own summary row uses) — its 4 real buckets (processing/withCourier/delivered/
// returned) plus the endpoint's own documented "other" remainder (cancelled,
// lost, terminated, needs_attention, delivery_failed, self_pickup_pending,
// returning — intentionally uncounted by summary() itself). NOT the mockup's
// invented 6-slice breakdown (delayed / delivery-failed as separate slices) —
// no endpoint computes that split at this all-time granularity; inventing one
// here would be exactly the kind of new derivation a restyle must not add
// silently.

const DONUT_R = 40
const DONUT_C = 2 * Math.PI * DONUT_R

function OrdersDonut({ summary }: { summary: OrderSummaryCounts }) {
  const { t } = useTranslation()
  const other = Math.max(0, summary.total - summary.processing - summary.withCourier - summary.delivered - summary.returned)
  const segments = [
    { labelKey: 'orders.summary.processing',   value: summary.processing, color: NEUTRAL_TXT },
    { labelKey: 'orders.pipeline.with_courier', value: summary.withCourier, color: INFO },
    { labelKey: 'orders.pipeline.delivered',    value: summary.delivered,  color: SUCCESS },
    { labelKey: 'orders.pipeline.returned',     value: summary.returned,  color: WARNING },
    { labelKey: 'overview.donut.other',         value: other,             color: GREY_600 },
  ]

  if (summary.total === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-4">
        <svg width="88" height="88" viewBox="0 0 100 100">
          <circle cx="50" cy="50" r={DONUT_R} fill="none" stroke={ELEVATED} strokeWidth="12" />
        </svg>
        <span className="text-caption text-muted">{t('overview.donut.empty')}</span>
      </div>
    )
  }

  let offset = 0
  return (
    <div className="flex items-center gap-4">
      <svg width="120" height="120" viewBox="0 0 100 100" className="flex-shrink-0">
        {segments.filter(s => s.value > 0).map(s => {
          const len = (s.value / summary.total) * DONUT_C
          const circle = (
            <circle key={s.labelKey} cx="50" cy="50" r={DONUT_R} fill="none" stroke={s.color} strokeWidth="12"
                    strokeDasharray={`${len} ${DONUT_C}`} strokeDashoffset={-offset}
                    transform="rotate(-90 50 50)" />
          )
          offset += len
          return circle
        })}
        <text x="50" y="47" textAnchor="middle" fontSize="15" fontWeight="700" fill="#F2F4F7" fontFamily="monospace">
          {summary.total >= 1000 ? `${(summary.total / 1000).toFixed(1)}K` : summary.total}
        </text>
        <text x="50" y="61" textAnchor="middle" fontSize="8" fill="#828B99">{t('overview.donut.total')}</text>
      </svg>
      <div className="flex flex-col gap-1.5 flex-1 min-w-0">
        {segments.map(s => (
          <span key={s.labelKey} className="flex items-center gap-1.5 text-caption">
            <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: s.color }} />
            <span className="text-muted flex-1 truncate">{t(s.labelKey)}</span>
            <span className="text-primary font-mono font-semibold">{s.value.toLocaleString()}</span>
          </span>
        ))}
      </div>
    </div>
  )
}

// ── Recent orders (with a shipment) ─────────────────────────────────────────
// Derived from GET /orders (already-wired endpoint), filtered client-side to
// rows with a trackingNumber — no new backend endpoint for this frontend-only
// pass. Deliberately titled "Recent orders", not "Recent shipments": both the
// sort AND the "X ago" timestamp come from orders.placed_at (order placement
// time) — list()'s own ORDER BY o.placed_at DESC, confirmed by reading the SQL,
// not any shipment status-update/event time (GET /orders exposes no such field
// at all — no last_synced_at, no shipment_status_history timestamp). Labeling
// this "shipments" would imply shipment-event recency it doesn't have; "Recent
// orders" is what it actually, honestly is — recently-placed orders that
// already have a shipment attached. Status chip reuses DeliveryBadge (the SAME
// component Orders.tsx/OrderDetail.tsx already render shipment state with) —
// no new label map.

function RecentOrdersList({ orders }: { orders: OrderSummary[] }) {
  const { t } = useTranslation()
  const shipped = orders.filter(o => o.trackingNumber).slice(0, 5)

  if (shipped.length === 0) {
    return <EmptyState icon="—" message={t('overview.recentOrders.empty')} />
  }

  return (
    <div className="flex flex-col">
      {shipped.map((o, i) => (
        <div key={o.id} className={cn('flex items-center gap-3 py-2.5', i > 0 && 'border-t border-line')}>
          <span className="text-small font-semibold font-mono text-trace-blue truncate">{o.trackingNumber}</span>
          <span className="text-caption text-muted w-11 flex-shrink-0">{t('overview.recentOrders.carrier')}</span>
          <DeliveryBadge state={o.deliveryState} className="ms-auto flex-shrink-0" />
          {o.placedAt && (
            <span className="text-caption text-muted w-11 text-end flex-shrink-0">{relativeTime(o.placedAt, t)}</span>
          )}
        </div>
      ))}
    </div>
  )
}

// ── Quick actions — real routes only ─────────────────────────────────────────

const QUICK_ACTIONS: { labelKey: string; to: string; icon: typeof PackageCheck }[] = [
  { labelKey: 'overview.quickActions.fulfill',   to: '/fulfill',   icon: PackageCheck },
  { labelKey: 'overview.quickActions.receiving', to: '/receiving', icon: Inbox },
  { labelKey: 'overview.quickActions.transfers', to: '/transfers', icon: Repeat },
  { labelKey: 'overview.quickActions.lookup',    to: '/lookup',    icon: Search },
]

function QuickActions() {
  const { t } = useTranslation()
  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
      {QUICK_ACTIONS.map(a => (
        <Link
          key={a.to}
          to={a.to}
          className="flex items-center justify-center gap-2 bg-elevated border border-line rounded-lg px-4 py-3.5 text-small font-semibold text-primary hover:border-grey-600 hover:bg-white/5 transition-colors"
        >
          <a.icon size={17} strokeWidth={1.75} className="text-trace-blue" />
          {t(a.labelKey)}
        </Link>
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

// ── Root ──────────────────────────────────────────────────────────────────────

function greetingKey(): 'morning' | 'afternoon' | 'evening' {
  const h = new Date().getHours()
  if (h < 12) return 'morning'
  if (h < 18) return 'afternoon'
  return 'evening'
}

function fetchExceptionsByType(type: string, size = 1) {
  return request<{ items: AlertException[] }>(`/exceptions?type=${type}&size=${size}`).then(r => r.items)
}

export default function Overview() {
  const { t } = useTranslation()
  const me   = useMe()

  // Kept solely to preserve the existing fresh-tenant empty-state gate — its
  // visual tiles (the old "Inventory health" row) are gone, but the gating
  // behavior itself must not change (see PROGRESS.md's fresh-tenant/zero-value
  // gate-collision gotcha).
  const statusTotals = useZoneFetch<StatusTotals>(getStatusTotals)
  const onboarding   = useZoneFetch<OnboardingStatus>(getOnboardingStatus)

  const trends   = useZoneFetch<MetricTrend[]>(getOverviewTrends)
  const funnel   = useZoneFetch<FunnelCounts>(getOrdersFunnel)
  const valuation = useZoneFetch<Valuation>(getValuation)
  const topSkus  = useZoneFetch<TopSku[]>(getOverviewTopSkus)
  const ordersSummary = useZoneFetch<OrderSummaryCounts>(getOrdersSummary)
  const recentOrders  = useZoneFetch<OrderSummary[]>(() => listOrders({ size: 20 }).then(r => r.items))

  const ndrFailed         = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('ndr_failed'))
  const stuckShipment     = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('stuck_shipment'))
  const unmatchedDelivery = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('unmatched_delivery'))

  const [onboardingHidden, setOnboardingHidden] = useState(false)

  const counts = statusTotals.data?.statusCounts
  const totalPieces = (counts?.available ?? 0) + (counts?.reserved ?? 0) + (counts?.damaged ?? 0) + (counts?.lost ?? 0)

  const showOnboarding = !onboardingHidden && !onboarding.loading && !onboarding.error &&
    onboarding.data && !onboarding.data.dismissed && !onboarding.data.allDone

  const isFreshTenant = !statusTotals.loading && !statusTotals.error && totalPieces === 0 &&
    !onboarding.loading && !onboarding.error && onboarding.data && !onboarding.data.allDone

  const trendFor = (metric: MetricTrend['metric']) => trends.data?.find(t2 => t2.metric === metric)

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

          {/* ── 5 sparkline stat cards ── */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3.5" data-testid="stat-cards">
            {trends.error ? (
              <div className="col-span-full"><ZoneError /></div>
            ) : (
              STAT_DEFS.map(def => (
                <SparkStatCard
                  key={def.metric}
                  label={t(def.labelKey)}
                  trend={trendFor(def.metric)}
                  color={def.color}
                  goodDirection={def.goodDirection}
                />
              ))
            )}
          </div>

          {/* ── Live operations + Alerts ── */}
          <div className="grid grid-cols-1 lg:grid-cols-[1fr_380px] gap-3.5">
            <div className="card p-5">
              <p className="text-caption font-bold text-muted uppercase">{t('overview.flow.title')}</p>
              <p className="text-caption text-muted">{t('overview.flow.subtitle')}</p>
              {funnel.loading ? <Skeleton className="h-32 rounded-xl mt-3" /> : funnel.error ? <ZoneError /> : funnel.data && (
                <FlowStrip counts={funnel.data} />
              )}
            </div>
            <div className="card p-5" data-testid="alerts-panel">
              <div className="flex items-center justify-between mb-1">
                <p className="text-caption font-bold text-muted uppercase">{t('overview.alerts.title')}</p>
                <Link to="/exceptions" className="text-caption text-trace-blue hover:text-trace-blue-hover transition-colors">
                  {t('overview.viewAll')}
                </Link>
              </div>
              {(ndrFailed.loading || stuckShipment.loading || unmatchedDelivery.loading || valuation.loading) ? (
                <div className="flex justify-center py-8"><Spinner /></div>
              ) : (ndrFailed.error || stuckShipment.error || unmatchedDelivery.error || valuation.error) ? (
                <ZoneError />
              ) : (
                <AlertsPanel
                  ndrFailed={ndrFailed.data ?? []}
                  stuckShipment={stuckShipment.data ?? []}
                  unmatchedDelivery={unmatchedDelivery.data ?? []}
                  lowStockCount={valuation.data?.lowStockCount ?? 0}
                />
              )}
            </div>
          </div>

          {/* ── Top SKUs + Orders by status + Recent shipments ── */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-3.5">
            <div className="card p-5" data-testid="top-skus">
              <p className="text-caption font-bold text-muted uppercase">{t('overview.topSkus.title')}</p>
              <p className="text-caption text-muted">{t('overview.topSkus.subtitle')}</p>
              {topSkus.loading ? <Skeleton className="h-40 rounded-xl mt-2" /> : topSkus.error ? <ZoneError /> : topSkus.data && (
                <TopSkusList skus={topSkus.data} />
              )}
            </div>
            <div className="card p-5" data-testid="orders-donut">
              <p className="text-caption font-bold text-muted uppercase mb-1">{t('overview.donut.title')}</p>
              {ordersSummary.loading ? <Skeleton className="h-40 rounded-xl mt-2" /> : ordersSummary.error ? <ZoneError /> : ordersSummary.data && (
                <OrdersDonut summary={ordersSummary.data} />
              )}
              <Link to="/orders" className="text-caption text-trace-blue hover:text-trace-blue-hover transition-colors mt-3.5 inline-block">
                {t('overview.viewAll')} →
              </Link>
            </div>
            <div className="card p-5" data-testid="recent-orders">
              <div className="flex items-center justify-between mb-1">
                <p className="text-caption font-bold text-muted uppercase">{t('overview.recentOrders.title')}</p>
                <Link to="/orders" className="text-caption text-trace-blue hover:text-trace-blue-hover transition-colors">
                  {t('overview.viewAll')}
                </Link>
              </div>
              {recentOrders.loading ? <Skeleton className="h-40 rounded-xl mt-2" /> : recentOrders.error ? <ZoneError /> : recentOrders.data && (
                <RecentOrdersList orders={recentOrders.data} />
              )}
            </div>
          </div>

          {/* ── Quick actions ── */}
          <div className="card p-5" data-testid="quick-actions">
            <p className="text-caption font-bold text-muted uppercase mb-3">{t('overview.quickActions.title')}</p>
            <QuickActions />
          </div>
        </>
      )}
    </div>
  )
}
