import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
  AlertTriangle, PackageX, X,
  Plug, Inbox, Users as UsersIcon, CheckCircle2, Circle,
  Truck, Unlink, ShoppingBag, PackageSearch, PackageCheck,
  Repeat, Search,
} from 'lucide-react'
import {
  request,
  getStatusTotals, getValuation, getOrdersFunnel, getOnboardingStatus, dismissOnboarding,
  setOnboardingStep,
  getOverviewTrends, getOverviewTopSkus, getOrdersSummary, listOrders, getLateToPack,
  type StatusTotals, type Valuation, type FunnelCounts,
  type OnboardingStatus, type OnboardingStep,
  type MetricTrend, type TrendPoint, type TopSku, type OrderSummaryCounts, type OrderSummary,
  type LateToPack,
} from '../api'
import {
  EmptyState, Progress, useMe, useToast,
  Skeleton, Spinner, ProductThumb, DeliveryBadge, cn,
  SegmentedControl, Input,
} from '../components/ui'
import OrderDrawer from '../components/OrderDrawer'

// ── DS token hex values — SVG presentation attrs can't use Tailwind classes ────
// Mirrors tailwind.config.js exactly — never introduce a hex value that isn't
// already a named DS token there. NEUTRAL_TXT/ELEVATED were pinned to the old
// dark-theme values (sidebar text / dark elevated) and never repointed when
// the app converted to light (8e54bbb) — fixed here to the current tokens.

const SUCCESS     = '#16A34A'
const INFO        = '#0EA5E9'
const CRITICAL    = '#DC2626'
const WARNING     = '#F59E0B'
const TRACE_BLUE  = '#2563EB'
const NEUTRAL_TXT = '#4B5563' // current `neutral.text` (was the old dark sidebar-text #9CA6B2)
const MUTED       = '#5B6675' // current `muted` (was `grey.600` #2A333F) — grey.600 read as a
                               // near-black blemish next to green/blue/orange siblings, and the
                               // next step down (grey.500) sat too close in value to NEUTRAL_TXT
                               // to read as a separate ring segment (both verified by eye,
                               // headless render). `muted` is lighter than NEUTRAL_TXT, which
                               // also fits: "Other" is the least important bucket, and its
                               // quietest color now matches that.
const ELEVATED    = '#F2F4F7' // current `elevated` (was the old dark elevated #161B22)

// ── Generic per-zone fetch hook ─────────────────────────────────────────────
// Every zone on this page fetches independently — one slow/broken zone never
// blocks another. A shared source is fetched ONCE and passed to every widget
// that reads it — never re-fetched per widget.

function useZoneFetch<T>(fetchFn: () => Promise<T>): { data: T | null; loading: boolean; error: boolean; refetch: () => void } {
  const [data, setData]       = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(false)
  const [version, setVersion] = useState(0)

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
  }, [version])

  return { data, loading, error, refetch: () => setVersion(v => v + 1) }
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

// ── Stat cards with sparkline ───────────────────────────────────────────────
// Headline = trend.total, scoped to whatever [from,to] range the date-range
// picker has selected. Sparkline = trend.series, ALWAYS the fixed trailing
// 14-day shape regardless of the selected range — deliberately decoupled (see
// OverviewService's class doc) so Today/Yesterday don't collapse it to a
// single point. There is no "vs yesterday" comparison anymore: it doesn't have
// a coherent meaning against an arbitrary range total (the backend no longer
// returns yesterday/deltaPct at all).

const STAT_DEFS: {
  metric: MetricTrend['metric']
  labelKey: string
  color: string
  format?: (n: number) => string
}[] = [
  { metric: 'orders',     labelKey: 'nav.orders',                  color: INFO },
  { metric: 'delivered',  labelKey: 'orders.pipeline.delivered',   color: SUCCESS },
  { metric: 'returns',    labelKey: 'nav.returns',                 color: WARNING },
  { metric: 'exchanges',  labelKey: 'overview.stats.exchanges',    color: TRACE_BLUE },
  { metric: 'exceptions', labelKey: 'nav.exceptions',              color: CRITICAL },
]

function SparkStatCard({
  label, trend, color, format, emphasize = false,
}: {
  label: string
  trend: MetricTrend | undefined
  color: string
  format?: (n: number) => string
  /** Exceptions-only: true when its count > 0, to give the one metric that
      needs attention a visual weight the other 4 (equally-styled) cards
      don't carry — see Overview audit P2 "no KPI hierarchy". */
  emphasize?: boolean
}) {
  if (!trend) return <Skeleton className="h-[118px] rounded-2xl" />

  return (
    <div
      className={cn('card p-5 flex flex-col gap-1 animate-fadeIn motion-reduce:animate-none', emphasize && 'border-danger')}
      data-testid={`stat-${trend.metric}`}
    >
      <p className="text-small text-muted font-medium">{label}</p>
      <div className="flex items-end justify-between gap-2">
        <p className={cn('text-h2 font-mono', emphasize ? 'text-danger' : 'text-primary')}>
          {format ? format(trend.total) : trend.total.toLocaleString()}
        </p>
        {trend.series.length > 0 && <Sparkline series={trend.series} color={color} />}
      </div>
    </div>
  )
}

// ── Date-range picker (top stat cards only — Zone 1) ────────────────────────
// Presets computed in Cairo local calendar days, matching the server's own
// Cairo-pinned Clock — no date library, just Intl.DateTimeFormat('en-CA', ...)
// (which formats as YYYY-MM-DD) plus UTC-anchored day arithmetic on that
// Y-M-D triple, so it's immune to the runtime's own local timezone.

type DateRangePreset = 'today' | 'yesterday' | '7d' | '30d' | 'month' | 'custom'

function cairoDateStr(date: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo' }).format(date)
}

function cairoTodayStr(): string {
  return cairoDateStr(new Date())
}

function cairoOffsetStr(days: number): string {
  const [y, m, d] = cairoTodayStr().split('-').map(Number)
  const base = new Date(Date.UTC(y, m - 1, d))
  base.setUTCDate(base.getUTCDate() + days)
  return base.toISOString().slice(0, 10)
}

function cairoMonthStartStr(): string {
  const [y, m] = cairoTodayStr().split('-')
  return `${y}-${m}-01`
}

function rangeForPreset(preset: DateRangePreset, customFrom: string, customTo: string): { from: string; to: string } {
  switch (preset) {
    case 'today':     return { from: cairoTodayStr(), to: cairoTodayStr() }
    case 'yesterday': { const y = cairoOffsetStr(-1); return { from: y, to: y } }
    case '30d':       return { from: cairoOffsetStr(-29), to: cairoTodayStr() }
    case 'month':     return { from: cairoMonthStartStr(), to: cairoTodayStr() }
    case 'custom':    return { from: customFrom, to: customTo }
    case '7d':
    default:          return { from: cairoOffsetStr(-6), to: cairoTodayStr() }
  }
}

const DATE_RANGE_PRESETS: { value: DateRangePreset; labelKey: string }[] = [
  { value: 'today',     labelKey: 'overview.dateRange.today' },
  { value: 'yesterday', labelKey: 'overview.dateRange.yesterday' },
  { value: '7d',        labelKey: 'overview.dateRange.last7' },
  { value: '30d',       labelKey: 'overview.dateRange.last30' },
  { value: 'month',     labelKey: 'overview.dateRange.thisMonth' },
  { value: 'custom',    labelKey: 'overview.dateRange.custom' },
]

function DateRangePicker({
  preset, onPresetChange, customFrom, customTo, onCustomChange,
}: {
  preset: DateRangePreset
  onPresetChange: (p: DateRangePreset) => void
  customFrom: string
  customTo: string
  onCustomChange: (from: string, to: string) => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2 flex-wrap" data-testid="date-range-picker">
      <SegmentedControl
        options={DATE_RANGE_PRESETS.map(o => ({ value: o.value, label: t(o.labelKey) }))}
        value={preset}
        onChange={v => onPresetChange(v as DateRangePreset)}
      />
      {preset === 'custom' && (
        <div className="flex items-center gap-1.5" data-testid="date-range-custom">
          <div className="w-[152px]">
            <Input
              type="date"
              value={customFrom}
              max={customTo || undefined}
              aria-label={t('overview.dateRange.fromLabel')}
              onChange={e => onCustomChange(e.target.value, customTo)}
            />
          </div>
          <span className="text-muted text-small flex-shrink-0">→</span>
          <div className="w-[152px]">
            <Input
              type="date"
              value={customTo}
              min={customFrom || undefined}
              aria-label={t('overview.dateRange.toLabel')}
              onChange={e => onCustomChange(customFrom, e.target.value)}
            />
          </div>
        </div>
      )}
    </div>
  )
}

// ── Late-to-pack (live state — NOT scoped by the date-range picker) ────────
// overdue/over48 are absolute counts as of right now, not a period metric —
// caption says "as of now" so it never reads like it's scoped to the picker
// above it. overdue=0 is a genuinely calm state (over48 is then necessarily
// 0 too, since over48 orders are a subset of overdue ones).

function LateToPackCard({ data }: { data: LateToPack | null }) {
  const { t } = useTranslation()
  if (!data) return <Skeleton className="h-[118px] rounded-2xl" />
  const calm = data.overdue === 0

  return (
    <div className="card p-5 flex flex-col gap-1 animate-fadeIn motion-reduce:animate-none" data-testid="late-to-pack-card">
      <p className="text-small text-muted font-medium">{t('overview.lateToPack.title')}</p>
      <p className={cn('text-h2 font-mono', calm ? 'text-success' : 'text-critical')}>
        {data.overdue.toLocaleString()}
      </p>
      {calm ? (
        <p className="text-small text-muted mt-1">{t('overview.lateToPack.allCaughtUp')}</p>
      ) : (
        <>
          <p className="text-small text-muted mt-1">{t('overview.lateToPack.asOfNow')}</p>
          {data.over48 > 0 && (
            <p className="text-small font-semibold text-critical mt-0.5">
              {t('overview.lateToPack.over48', { count: data.over48 })}
            </p>
          )}
        </>
      )}
    </div>
  )
}

// ── Live-ops flow strip ──────────────────────────────────────────────────────
// Reuses /orders/funnel (today, 5 buckets) — the SAME source the old funnel
// bars used, just restyled as nodes+arrows. Deliberately 4 in-progress nodes,
// not the mockup's 6: no separate "Warehouse/in stock" pipeline stage exists
// (that's a different concept, total available inventory, not an order-flow
// stage), and "Out for delivery" isn't split from "In transit" — no data
// signal distinguishes them (Bosta's granular codes collapse into the
// 9-value shipment_internal_state enum before reaching the app), the exact
// same cut already made for the Orders-detail stepper restyle.
//
// Delivered is intentionally NOT a strip node (an order that's Delivered has
// left the "in progress" work the strip visualizes) — but counts.delivered
// still feeds the footer's Completed figure directly below, unaffected by
// this array.

type FlowTone = 'new' | 'picking' | 'packed' | 'courier'

const FLOW_NODES: { key: keyof FunnelCounts; labelKey: string; icon: typeof ShoppingBag; tone: FlowTone }[] = [
  { key: 'newCount', labelKey: 'overview.funnel.new',     icon: ShoppingBag,   tone: 'new' },
  { key: 'picking',  labelKey: 'overview.funnel.picking', icon: PackageSearch, tone: 'picking' },
  { key: 'packed',   labelKey: 'overview.funnel.packed',  icon: PackageCheck,  tone: 'packed' },
  { key: 'courier',  labelKey: 'overview.funnel.courier', icon: Truck,         tone: 'courier' },
]

// Each stage gets its own DS-token tile, same pattern the old Delivered-only
// styling used: tinted bg (token @ ~12% opacity) + border (token @ 40%) +
// icon stroke in the full/bright token color. Tailwind classes reference
// tailwind.config.js tokens directly — no hex consts needed here.
const FLOW_TONE_CLASSES: Record<FlowTone, string> = {
  new:     'bg-grey-300/[0.12] border-grey-300/40 text-neutral-text',
  picking: 'bg-info/[0.12] border-info/40 text-info-text',
  packed:  'bg-warning/[0.12] border-warning/40 text-warning-text',
  courier: 'bg-trace-blue/[0.12] border-trace-blue/40 text-trace-blue',
}

function FlowStrip({ counts }: { counts: FunnelCounts }) {
  const { t } = useTranslation()
  const values = FLOW_NODES.map(n => counts[n.key])
  const inProgress = counts.newCount + counts.picking + counts.packed + counts.courier
  const completed  = counts.delivered
  // NOT values.every(v => v === 0): values only spans the 4 in-progress nodes
  // now that Delivered isn't one of them. A day with nothing in progress but
  // orders delivered (inProgress=0, completed>0) must still show the strip +
  // footer's Completed count, not fall into the "No orders yet" empty state.
  const allZero = inProgress === 0 && completed === 0
  const pct = completed + inProgress > 0 ? (completed / (completed + inProgress)) * 100 : 0

  if (allZero) {
    return (
      <p className="text-caption text-muted text-center py-8 animate-fadeIn motion-reduce:animate-none" data-testid="flow-empty">
        {t('overview.funnel.empty')}
      </p>
    )
  }

  return (
    <div data-testid="flow-strip" className="animate-fadeIn motion-reduce:animate-none">
      <div className="flex items-start gap-1 mt-2">
        {FLOW_NODES.map((node, i) => {
          const isLast = i === FLOW_NODES.length - 1
          const Icon = node.icon
          return (
            <div key={node.key} className="flex items-start flex-1 min-w-0">
              <div className="flex flex-col items-center gap-2 flex-1 text-center min-w-0">
                <div className={cn(
                  'w-12 h-12 rounded-2xl border flex items-center justify-center flex-shrink-0',
                  FLOW_TONE_CLASSES[node.tone]
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
//
// Destination routing (diagnosis: most ExceptionService.enrich() actionUrls point
// at paths with no matching route — e.g. /orders/{id}, /shipments/{id} — so
// react-router's catch-all silently redirects them to /overview). Rather than
// touching enrich() (a separate, shared fix — Exceptions.tsx's own "Go →" button
// has the identical bug and needs the same decision), these 3 buckets are handled
// here, frontend-only, keyed by exception `type`:
//   1. Order-anchored types: open OrderDrawer by order_id, the SAME app-wide
//      destination Orders.tsx/Lookup.tsx/VariantDrawer.tsx already use — never
//      navigate to the dead actionUrl for these.
//   2. Types whose actionUrl already resolves to a real route: used as-is.
//   3. unmatched_delivery / guided_unpack: no real destination exists yet (no
//      unlinked-delivery reconciliation screen; Fulfill has no deep-link-to-order
//      entry point — both unbuilt backlog). Falls back to /exceptions?type=<type>
//      (a real, already-supported filter — see Exceptions.tsx's own typeFilter)
//      so the alert always lands somewhere useful, never on /overview.

interface AlertException {
  type: string
  descriptionEn: string
  descriptionAr: string
  actionUrl: string
  ageSeconds: number
  order_id?: string
}

type AlertDestination =
  | { kind: 'order'; orderId: string }
  | { kind: 'url'; url: string }

// The 12 types whose enrich() actionUrl is a dead /orders/{id} (or similar) but
// which always carry a real order_id — see the Overview diagnosis pass.
const ORDER_ANCHORED_ALERT_TYPES = new Set([
  'lost', 'blocked_customer', 'stuck_shipment', 'delivery_limbo', 'ndr_failed',
  'missing_awb', 'missing_provider_id', 'high_attempts', 'shopify_cancel_vs_inflight',
  'cancelled_live_shipment', 'cancelled_but_delivered', 'shopify_edit_conflict',
])

// No real destination exists for these yet — see the header comment above.
const EXCEPTIONS_FALLBACK_ALERT_TYPES = new Set(['unmatched_delivery', 'guided_unpack'])

function destinationFor(item: AlertException): AlertDestination {
  if (ORDER_ANCHORED_ALERT_TYPES.has(item.type) && item.order_id) {
    return { kind: 'order', orderId: item.order_id }
  }
  if (EXCEPTIONS_FALLBACK_ALERT_TYPES.has(item.type)) {
    return { kind: 'url', url: `/exceptions?type=${item.type}` }
  }
  return { kind: 'url', url: item.actionUrl }
}

interface AlertRow {
  key: string
  icon: typeof AlertTriangle
  tone: 'critical' | 'warning' | 'info'
  descriptionEn: string
  descriptionAr: string
  destination: AlertDestination
  ageSeconds: number
}

const ALERT_TONE_CLASSES: Record<AlertRow['tone'], string> = {
  critical: 'bg-critical/[0.13] text-critical-text',
  warning:  'bg-warning/[0.13] text-warning-text',
  info:     'bg-info/[0.13] text-info-text',
}

function AlertsPanel({
  ndrFailed, stuckShipment, unmatchedDelivery, lowStockCount, onSelectOrder,
}: {
  ndrFailed: AlertException[]
  stuckShipment: AlertException[]
  unmatchedDelivery: AlertException[]
  lowStockCount: number
  onSelectOrder: (orderId: string) => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const rows: AlertRow[] = []
  if (ndrFailed[0]) {
    rows.push({
      key: 'ndr_failed', icon: AlertTriangle, tone: 'critical',
      descriptionEn: ndrFailed[0].descriptionEn, descriptionAr: ndrFailed[0].descriptionAr,
      destination: destinationFor(ndrFailed[0]), ageSeconds: ndrFailed[0].ageSeconds,
    })
  }
  if (stuckShipment[0]) {
    rows.push({
      key: 'stuck_shipment', icon: Truck, tone: 'warning',
      descriptionEn: stuckShipment[0].descriptionEn, descriptionAr: stuckShipment[0].descriptionAr,
      destination: destinationFor(stuckShipment[0]), ageSeconds: stuckShipment[0].ageSeconds,
    })
  }
  if (lowStockCount > 0) {
    rows.push({
      key: 'low_stock', icon: PackageX, tone: 'warning',
      descriptionEn: t('overview.needsAttention.lowStock', { lng: 'en' }),
      descriptionAr: t('overview.needsAttention.lowStock', { lng: 'ar' }),
      destination: { kind: 'url', url: '/inventory?lowStockOnly=true' }, ageSeconds: 0,
    })
  }
  if (unmatchedDelivery[0]) {
    rows.push({
      key: 'unmatched_delivery', icon: Unlink, tone: 'info',
      descriptionEn: unmatchedDelivery[0].descriptionEn, descriptionAr: unmatchedDelivery[0].descriptionAr,
      destination: destinationFor(unmatchedDelivery[0]), ageSeconds: unmatchedDelivery[0].ageSeconds,
    })
  }

  if (rows.length === 0) {
    return <EmptyState icon="✓" message={t('overview.alerts.empty')} />
  }

  const linkClass = 'text-small text-primary [@media(hover:hover)_and_(pointer:fine)]:hover:text-trace-blue transition-colors block'

  return (
    <div className="flex flex-col animate-fadeIn motion-reduce:animate-none">
      {rows.map((row, i) => {
        const Icon = row.icon
        const destination = row.destination
        return (
          <div
            key={row.key}
            className={cn('flex items-start gap-3 py-3', i > 0 && 'border-t border-line')}
          >
            <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0', ALERT_TONE_CLASSES[row.tone])}>
              <Icon size={16} strokeWidth={1.75} />
            </div>
            <div className="flex-1 min-w-0">
              {destination.kind === 'order' ? (
                <button type="button" onClick={() => onSelectOrder(destination.orderId)} className={cn(linkClass, 'text-start w-full bg-transparent border-0 p-0')}>
                  {isAr ? row.descriptionAr : row.descriptionEn}
                </button>
              ) : (
                <Link to={destination.url} className={linkClass}>
                  {isAr ? row.descriptionAr : row.descriptionEn}
                </Link>
              )}
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
    <div className="flex flex-col animate-fadeIn motion-reduce:animate-none">
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
const DONUT_SIZE = 204 // rendered px — enlarged from the prior 120px card
const DONUT_STROKE = 15 // thicker ring, up from 12
const DONUT_GAP = 2.5 // subtracted from each slice's dasharray so segments don't touch

function OrdersDonut({ summary }: { summary: OrderSummaryCounts }) {
  const { t } = useTranslation()
  const other = Math.max(0, summary.total - summary.processing - summary.withCourier - summary.delivered - summary.returned)
  const segments = [
    { labelKey: 'orders.summary.processing',   value: summary.processing, color: NEUTRAL_TXT },
    { labelKey: 'orders.pipeline.with_courier', value: summary.withCourier, color: INFO },
    { labelKey: 'orders.pipeline.delivered',    value: summary.delivered,  color: SUCCESS },
    { labelKey: 'orders.pipeline.returned',     value: summary.returned,  color: WARNING },
    { labelKey: 'overview.donut.other',         value: other,             color: MUTED },
  ]

  if (summary.total === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-4">
        <svg width="88" height="88" viewBox="0 0 100 100" className="animate-fadeIn motion-reduce:animate-none">
          <circle cx="50" cy="50" r={DONUT_R} fill="none" stroke={ELEVATED} strokeWidth="12" />
        </svg>
        <span className="text-caption text-muted">{t('overview.donut.empty')}</span>
      </div>
    )
  }

  let offset = 0
  return (
    <div className="flex flex-col items-center gap-4">
      {/* Ring settles in once on first load (fadeIn is a mount-only CSS
          animation — it does not replay on data refetch since the same DOM
          node stays mounted; see useZoneFetch's stale-data-during-refetch
          behavior). Opacity chosen over an animated stroke-dashoffset "draw":
          each segment already has a distinct, hand-computed dashoffset for
          its pie-slice position, and animating that per-segment safely would
          need per-element keyframes — opacity is the equivalent settle with
          none of that risk. */}
      <svg
        width={DONUT_SIZE} height={DONUT_SIZE} viewBox="0 0 100 100"
        className="flex-shrink-0 animate-fadeIn motion-reduce:animate-none"
      >
        {segments.filter(s => s.value > 0).map(s => {
          const len = (s.value / summary.total) * DONUT_C
          const circle = (
            <circle key={s.labelKey} cx="50" cy="50" r={DONUT_R} fill="none" stroke={s.color} strokeWidth={DONUT_STROKE}
                    strokeLinecap="round"
                    strokeDasharray={`${Math.max(0, len - DONUT_GAP)} ${DONUT_C}`} strokeDashoffset={-offset}
                    transform="rotate(-90 50 50)" />
          )
          offset += len
          return circle
        })}
        <text x="50" y="46" textAnchor="middle" fontSize="19" fontWeight="700" fill="#111827" fontFamily="monospace">
          {summary.total >= 1000 ? `${(summary.total / 1000).toFixed(1)}K` : summary.total}
        </text>
        <text x="50" y="61" textAnchor="middle" fontSize="9" fill="#5B6675">{t('overview.donut.total')}</text>
      </svg>
      <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 w-full">
        {segments.map(s => (
          <span key={s.labelKey} className="flex items-center gap-1.5 text-caption min-w-0">
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
//
// Rows open OrderDrawer by o.id (already the React key) — the SAME app-wide
// order destination Orders.tsx/Lookup.tsx/VariantDrawer.tsx use, not a new
// route. A native <button> gives Enter/Space activation for free; the
// bg-black/[0.04] hover + rounded corners mirrors FreshTenantCard's row-item
// treatment elsewhere on this page.

function RecentOrdersList({ orders, onSelectOrder }: { orders: OrderSummary[]; onSelectOrder: (orderId: string) => void }) {
  const { t } = useTranslation()
  const shipped = orders.filter(o => o.trackingNumber).slice(0, 5)

  if (shipped.length === 0) {
    return <EmptyState icon="—" message={t('overview.recentOrders.empty')} />
  }

  return (
    <div className="flex flex-col animate-fadeIn motion-reduce:animate-none">
      {shipped.map((o, i) => (
        <button
          key={o.id}
          type="button"
          onClick={() => onSelectOrder(o.id)}
          className={cn(
            'flex items-center gap-3 py-2.5 -mx-2 px-2 rounded-lg text-start w-full bg-transparent border-0',
            '[@media(hover:hover)_and_(pointer:fine)]:hover:bg-black/[0.04] transition-colors',
            i > 0 && 'border-t border-line',
          )}
        >
          <span className="text-small font-semibold font-mono text-trace-blue truncate">{o.trackingNumber}</span>
          <span className="text-caption text-muted w-11 flex-shrink-0">{t('overview.recentOrders.carrier')}</span>
          <DeliveryBadge state={o.deliveryState} className="ms-auto flex-shrink-0" />
          {o.placedAt && (
            <span className="text-caption text-muted w-11 text-end flex-shrink-0">{relativeTime(o.placedAt, t)}</span>
          )}
        </button>
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
          className="flex items-center justify-center gap-2 bg-elevated border border-line rounded-lg px-4 py-3.5 text-small font-semibold text-primary
                     [@media(hover:hover)_and_(pointer:fine)]:hover:border-grey-600 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-black/[0.04]
                     transition-[background-color,border-color,transform] duration-100 ease-out [@media(hover:hover)_and_(pointer:fine)]:hover:duration-150
                     active:scale-[0.97] active:duration-100
                     motion-reduce:transition-none motion-reduce:active:scale-100"
        >
          <a.icon size={17} strokeWidth={1.75} className="text-trace-blue" />
          {t(a.labelKey)}
        </Link>
      ))}
    </div>
  )
}

// ── Onboarding card — condensed, dismissible ────────────────────────────────
//
// The 5 checklist steps, in a fixed display order — key strings are the ones
// OnboardingController.status() returns (connect_shopify/connect_bosta/location/
// test_label/first_receiving are unchanged; "location" is the new FR-1.2 step,
// "initial_import" was dropped). Each row shows the auto/manual-derived `done`
// state plus a manual-override checkbox — checking it calls setOnboardingStep()
// and reconciles from the next onboarding refetch (optimistic flip in between).

const ONBOARDING_STEP_ORDER = ['connect_shopify', 'connect_bosta', 'location', 'test_label', 'first_receiving'] as const

const STEP_DEST: Record<string, string> = {
  connect_shopify: '/settings?tab=connections',
  connect_bosta:   '/settings?tab=connections',
  location:        '/settings?tab=locations',
  test_label:      '/receiving',
  first_receiving: '/receiving',
}

function OnboardingCard({
  status,
  onDismissed,
  onRefetch,
}: {
  status: OnboardingStatus
  onDismissed: () => void
  onRefetch: () => void
}) {
  const { t } = useTranslation()
  const [dismissing, setDismissing] = useState(false)
  const [pendingToggle, setPendingToggle] = useState<Set<string>>(new Set())
  // Optimistic local override for the manual flag, keyed by step — flipped the instant
  // the checkbox is clicked, before the network round-trip. Cleared per-key once the
  // authoritative `status` prop (from the next onboarding refetch) actually agrees with
  // it — reconciling on the real server state, not just on the request settling, so a
  // failed write correctly snaps back instead of sticking.
  const [optimisticManual, setOptimisticManual] = useState<Record<string, boolean>>({})

  useEffect(() => {
    setOptimisticManual(prev => {
      if (Object.keys(prev).length === 0) return prev
      const next = { ...prev }
      let changed = false
      for (const s of status.steps) {
        if (s.key in next && next[s.key] === s.manual) { delete next[s.key]; changed = true }
      }
      return changed ? next : prev
    })
  }, [status.steps])

  const steps = ONBOARDING_STEP_ORDER
    .map(key => status.steps.find(s => s.key === key))
    .filter((s): s is OnboardingStep => !!s)
    .map(s => {
      const manual = s.key in optimisticManual ? optimisticManual[s.key] : s.manual
      return { ...s, manual, done: s.auto || manual }
    })
  const doneCount = steps.filter(s => s.done).length
  const pct = steps.length > 0 ? (doneCount / steps.length) * 100 : 0

  async function handleDismiss() {
    setDismissing(true)
    try { await dismissOnboarding() } catch { /* optimistic hide regardless */ }
    onDismissed()
  }

  async function handleToggle(key: OnboardingStep['key'], checked: boolean) {
    setOptimisticManual(prev => ({ ...prev, [key]: checked }))
    setPendingToggle(prev => new Set(prev).add(key))
    try {
      await setOnboardingStep(key, checked)
    } catch {
      // Write failed — drop the optimistic guess immediately (it would otherwise strand
      // forever: the reconciling effect only clears an entry once the server's value
      // matches it, which never happens for a value the server never accepted).
      setOptimisticManual(prev => { const next = { ...prev }; delete next[key]; return next })
    }
    onRefetch()
    setPendingToggle(prev => { const next = new Set(prev); next.delete(key); return next })
  }

  return (
    <div className="card border-trace-blue p-4 flex flex-col gap-3 animate-fadeIn motion-reduce:animate-none" data-testid="onboarding-card">
      <div className="flex items-center justify-between">
        <span className="text-small font-bold text-primary">{t('overview.onboardingCard.title')}</span>
        <button
          type="button"
          onClick={handleDismiss}
          disabled={dismissing}
          aria-label={t('overview.onboardingCard.dismiss')}
          className="text-muted [@media(hover:hover)_and_(pointer:fine)]:hover:text-primary transition-colors p-1.5 -m-1.5"
        >
          <X size={14} strokeWidth={2} />
        </button>
      </div>
      <Progress value={pct} />
      <div className="flex flex-col gap-1.5">
        {steps.map(step => (
          <div
            key={step.key}
            data-testid={`onboarding-step-${step.key}`}
            data-done={step.done}
            className="flex items-center gap-2.5 text-caption"
          >
            <input
              type="checkbox"
              checked={step.manual}
              disabled={pendingToggle.has(step.key)}
              onChange={e => handleToggle(step.key, e.target.checked)}
              aria-label={t('overview.onboardingCard.manualCheckbox', { step: t(`overview.onboardingCard.chip.${step.key}`) })}
              className="flex-shrink-0"
            />
            {step.done
              ? <CheckCircle2 size={13} strokeWidth={2} className="text-success-text flex-shrink-0" />
              : <Circle size={13} strokeWidth={2} className="text-muted flex-shrink-0" />}
            <span className={step.done ? 'flex-1 text-muted line-through' : 'flex-1 text-primary'}>
              {t(`overview.onboardingCard.chip.${step.key}`)}
            </span>
            {!step.done && (
              <Link
                to={STEP_DEST[step.key]}
                className="text-trace-blue [@media(hover:hover)_and_(pointer:fine)]:hover:text-trace-blue-hover transition-colors font-medium flex-shrink-0"
              >
                {t('overview.onboardingCard.go')}
              </Link>
            )}
          </div>
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
    <div className="card p-5 flex flex-col gap-3 animate-fadeIn motion-reduce:animate-none" data-testid="fresh-tenant-card">
      <p className="text-small text-muted">{t('overview.freshTenant.message')}</p>
      {items.map(item => (
        <Link
          key={item.to}
          to={item.to}
          className="flex items-center gap-2.5 bg-elevated border border-line rounded-lg p-3
                     [@media(hover:hover)_and_(pointer:fine)]:hover:bg-black/[0.04]
                     transition-[background-color,transform] duration-100 ease-out [@media(hover:hover)_and_(pointer:fine)]:hover:duration-150
                     active:scale-[0.97] active:duration-100
                     motion-reduce:transition-none motion-reduce:active:scale-100"
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

  // ── Date-range picker (Zone 1 — top stat cards only) ──────────────────────
  const [dateRangePreset, setDateRangePreset] = useState<DateRangePreset>('7d')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo]     = useState('')
  const { from, to } = rangeForPreset(dateRangePreset, customFrom, customTo)

  const trends   = useZoneFetch<MetricTrend[]>(() => getOverviewTrends(from, to))
  const funnel   = useZoneFetch<FunnelCounts>(getOrdersFunnel)
  const valuation = useZoneFetch<Valuation>(getValuation)
  const topSkus  = useZoneFetch<TopSku[]>(getOverviewTopSkus)
  const ordersSummary = useZoneFetch<OrderSummaryCounts>(getOrdersSummary)
  const recentOrders  = useZoneFetch<OrderSummary[]>(() => listOrders({ size: 20 }).then(r => r.items))
  const lateToPack     = useZoneFetch<LateToPack>(getLateToPack)

  const ndrFailed         = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('ndr_failed'))
  const stuckShipment     = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('stuck_shipment'))
  const unmatchedDelivery = useZoneFetch<AlertException[]>(() => fetchExceptionsByType('unmatched_delivery'))

  // Slide-in overlay, not a route — shared by Recent orders rows and order-anchored
  // alert rows, same OrderDrawer Orders.tsx/Lookup.tsx/VariantDrawer.tsx already use.
  const [drawerOrderId, setDrawerOrderId] = useState<string | null>(null)

  // Re-fetch trends whenever the selected range changes (skip the very first
  // render — useZoneFetch's own mount effect already fetched the initial
  // [from,to] once).
  const rangeMounted = useRef(false)
  useEffect(() => {
    if (!rangeMounted.current) { rangeMounted.current = true; return }
    trends.refetch()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to])

  function handleCustomRangeChange(nextFrom: string, nextTo: string) {
    setCustomFrom(nextFrom)
    setCustomTo(nextTo)
  }

  const [onboardingHidden, setOnboardingHidden] = useState(false)

  // Transient "you're all set up!" toast — fires only on an in-session false->true
  // transition (e.g. checking the last manual-override box), never on a fresh load
  // that's already allDone, and never writes to onboarding_dismissed_at. The
  // persistent onboarding-card condition below is untouched by this.
  const { toast } = useToast()
  const prevAllDoneRef = useRef<boolean | null>(null)
  useEffect(() => {
    if (onboarding.loading || onboarding.error || !onboarding.data) return
    const wasIncomplete = prevAllDoneRef.current === false
    if (wasIncomplete && onboarding.data.allDone) {
      toast({ tone: 'success', message: t('overview.onboardingCard.allSetToast') })
    }
    prevAllDoneRef.current = onboarding.data.allDone
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onboarding.data, onboarding.loading, onboarding.error])

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
        <DateRangePicker
          preset={dateRangePreset}
          onPresetChange={setDateRangePreset}
          customFrom={customFrom}
          customTo={customTo}
          onCustomChange={handleCustomRangeChange}
        />
      </div>

      {isFreshTenant ? (
        <FreshTenantCard />
      ) : (
        <>
          {/* ── Onboarding card ── */}
          {showOnboarding && onboarding.data && (
            <OnboardingCard
              status={onboarding.data}
              onDismissed={() => setOnboardingHidden(true)}
              onRefetch={onboarding.refetch}
            />
          )}

          {/* ── 5 sparkline stat cards ── */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3.5" data-testid="stat-cards">
            {trends.error ? (
              <div className="col-span-full"><ZoneError /></div>
            ) : (
              STAT_DEFS.map(def => {
                const trend = trendFor(def.metric)
                return (
                  <SparkStatCard
                    key={def.metric}
                    label={t(def.labelKey)}
                    trend={trend}
                    color={def.color}
                    format={def.format}
                    emphasize={def.metric === 'exceptions' && !!trend && trend.total > 0}
                  />
                )
              })
            )}
          </div>

          {/* ── Live operations + Late-to-pack + Alerts ── */}
          <div className="grid grid-cols-1 lg:grid-cols-[1fr_220px_380px] gap-3.5">
            <div className="card p-5">
              <h2 className="text-caption font-bold text-muted uppercase">{t('overview.flow.title')}</h2>
              <p className="text-caption text-muted">{t('overview.flow.subtitle')}</p>
              {funnel.loading ? <Skeleton className="h-32 rounded-xl mt-3" /> : funnel.error ? <ZoneError /> : funnel.data && (
                <FlowStrip counts={funnel.data} />
              )}
            </div>
            {lateToPack.error ? <ZoneError /> : <LateToPackCard data={lateToPack.data} />}
            <div className="card p-5" data-testid="alerts-panel">
              <div className="flex items-center justify-between mb-1">
                <h2 className="text-caption font-bold text-muted uppercase">{t('overview.alerts.title')}</h2>
                <Link to="/exceptions" className="text-caption text-trace-blue [@media(hover:hover)_and_(pointer:fine)]:hover:text-trace-blue-hover transition-colors">
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
                  onSelectOrder={setDrawerOrderId}
                />
              )}
            </div>
          </div>

          {/* ── Top SKUs + Orders by status + Recent shipments ── */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-3.5">
            <div className="card p-5" data-testid="top-skus">
              <h2 className="text-caption font-bold text-muted uppercase">{t('overview.topSkus.title')}</h2>
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
              <Link to="/orders" className="text-caption text-trace-blue [@media(hover:hover)_and_(pointer:fine)]:hover:text-trace-blue-hover transition-colors mt-3.5 inline-block">
                {t('overview.viewAll')} →
              </Link>
            </div>
            <div className="card p-5" data-testid="recent-orders">
              <div className="flex items-center justify-between mb-1">
                <h2 className="text-caption font-bold text-muted uppercase">{t('overview.recentOrders.title')}</h2>
                <Link to="/orders" className="text-caption text-trace-blue [@media(hover:hover)_and_(pointer:fine)]:hover:text-trace-blue-hover transition-colors">
                  {t('overview.viewAll')}
                </Link>
              </div>
              {recentOrders.loading ? <Skeleton className="h-40 rounded-xl mt-2" /> : recentOrders.error ? <ZoneError /> : recentOrders.data && (
                <RecentOrdersList orders={recentOrders.data} onSelectOrder={setDrawerOrderId} />
              )}
            </div>
          </div>

          {/* ── Quick actions ── */}
          <div className="card p-5" data-testid="quick-actions">
            <h2 className="text-caption font-bold text-muted uppercase mb-3">{t('overview.quickActions.title')}</h2>
            <QuickActions />
          </div>
        </>
      )}

      {/* Slide-in overlay, not a route — same pattern as Orders.tsx. */}
      <OrderDrawer orderId={drawerOrderId} onClose={() => setDrawerOrderId(null)} />
    </div>
  )
}
