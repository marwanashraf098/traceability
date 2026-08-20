import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { X, ExternalLink, CheckCircle2, AlertTriangle, Truck, Package, Clock } from 'lucide-react'
import { getOrder, getOrderTimeline, OrderDetail, TimelineItem, DerivedTone } from '../api'
import { ShipmentCard } from '../pages/OrderDetail'
import { Button, EmptyState, OrderStatus, ProductThumb, Skeleton, cn } from './ui'

// ── Relative time — same pattern as Overview.tsx/StockTake.tsx/Transfers.tsx (each
// page owns a small local formatter); reuses the existing generic overview.time.*
// wording rather than adding a 4th duplicate namespace for the same three words. ──
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

// ── Current-state card presentation ─────────────────────────────────────────────

const STATE_ICON: Record<DerivedTone, typeof CheckCircle2> = {
  SUCCESS: CheckCircle2,
  WARN: AlertTriangle,
  DANGER: AlertTriangle,
  INFO: Truck,
  NEUTRAL: Package,
}

const STATE_ICON_STYLE: Record<DerivedTone, string> = {
  SUCCESS: 'bg-success/[0.14] text-success-text',
  WARN: 'bg-warning/[0.14] text-warning-text',
  DANGER: 'bg-critical/[0.14] text-critical-text',
  INFO: 'bg-info/[0.14] text-info-text',
  NEUTRAL: 'bg-muted/[0.14] text-neutral-text',
}

type DrawerTab = 'overview' | 'items' | 'shipment' | 'notes'

export default function OrderDrawer({
  orderId,
  onClose,
}: {
  orderId: string | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [tab, setTab] = useState<DrawerTab>('overview')
  const open = orderId != null

  // Separate state from `order` — this fetch runs independently (own loading/error),
  // so a timeline failure never blanks or hangs the rest of the drawer, and the order
  // header/items render as soon as getOrder() resolves without waiting on this.
  const [timeline, setTimeline]               = useState<TimelineItem[] | null>(null)
  const [timelineLoading, setTimelineLoading] = useState(false)
  const [timelineError, setTimelineError]     = useState(false)

  useEffect(() => {
    if (!orderId) return
    setLoading(true)
    setOrder(null)
    setTab('overview')
    getOrder(orderId)
      .then(setOrder)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [orderId])

  useEffect(() => {
    if (!orderId) return
    setTimelineLoading(true)
    setTimeline(null)
    setTimelineError(false)
    getOrderTimeline(orderId)
      .then(setTimeline)
      .catch(() => setTimelineError(true))
      .finally(() => setTimelineLoading(false))
  }, [orderId])

  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const forward = order?.shipments.find(s => s.shipmentLeg === 'forward') ?? null
  const stateIcoTone = order?.derivedStatus.tone ?? 'NEUTRAL'
  const StateIcon = STATE_ICON[stateIcoTone]
  const stateSub = order
    ? (order.derivedStatus.primaryKey === 'status.delivery_failed' && forward?.lastFailureReason)
      || (order.derivedStatus.historicalNote && t(order.derivedStatus.historicalNote.key, { count: order.derivedStatus.historicalNote.count }))
      || null
    : null

  return (
    <>
      <div
        className={cn(
          'fixed inset-0 bg-black/45 z-overlay transition-opacity duration-200',
          open ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        )}
        onClick={onClose}
      />
      <aside
        aria-hidden={!open}
        className={cn(
          'fixed top-0 end-0 h-screen w-[440px] max-w-[92vw] bg-surface border-s border-line z-modal',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {!order || loading ? (
          <div className="p-5 space-y-4">
            <Skeleton className="h-8 w-40 rounded-lg" />
            <Skeleton className="h-24 rounded-2xl" />
            <Skeleton className="h-40 rounded-2xl" />
          </div>
        ) : (
          <>
            {/* Header */}
            <div className="p-5 pb-0 relative">
              <button
                onClick={onClose}
                aria-label={t('orders.drawer.close')}
                className="absolute top-4 end-4 w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors"
              >
                <X size={18} strokeWidth={2} />
              </button>
              <div className="flex items-center gap-3 pe-10">
                <h2 className="text-h3 text-primary font-mono">{order.number}</h2>
                <OrderStatus derived={order.derivedStatus} />
              </div>
              <div className="mt-3">
                <p className="text-body font-semibold text-primary">{order.customerName ?? t('common.na')}</p>
                {order.customerPhone && <p className="text-small text-muted">{order.customerPhone}</p>}
              </div>
              <div className="flex items-center justify-between mt-4">
                <span className="text-h3 text-primary">
                  {order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : t('common.na')}
                </span>
                {order.shopifyOrderUrl && (
                  <a
                    href={order.shopifyOrderUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="btn rounded-lg bg-elevated border border-line text-primary hover:bg-charcoal px-3 py-1.5 text-small gap-1.5 inline-flex items-center"
                  >
                    {t('orders.drawer.viewInShopify')}
                    <ExternalLink size={13} strokeWidth={2} />
                  </a>
                )}
              </div>
              <div className="mt-4">
                <div className="flex gap-6 border-b border-line">
                  {([
                    ['overview', t('orders.drawer.tabs.overview')],
                    ['items', t('orders.drawer.tabs.items', { count: order.items.length })],
                    ['shipment', t('orders.drawer.tabs.shipment')],
                    ['notes', t('orders.drawer.tabs.notes')],
                  ] as [DrawerTab, string][]).map(([key, label]) => (
                    <button
                      key={key}
                      type="button"
                      onClick={() => setTab(key)}
                      className={cn(
                        'pb-3 text-body transition-colors border-b-2 -mb-px',
                        tab === key ? 'text-primary border-trace-blue' : 'text-muted border-transparent hover:text-primary'
                      )}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Body */}
            <div className="flex-1 overflow-y-auto p-5">
              {tab === 'overview' && (
                <div className="space-y-6">
                  <div>
                    <p className="text-caption font-bold text-muted uppercase tracking-wider mb-3">
                      {t('orders.drawer.currentState')}
                    </p>
                    <div className="flex items-center gap-3.5 bg-elevated border border-line rounded-2xl p-4">
                      <span className={cn('w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0', STATE_ICON_STYLE[stateIcoTone])}>
                        <StateIcon size={20} strokeWidth={1.9} />
                      </span>
                      <div>
                        <p className="text-body font-semibold text-primary">
                          {t(order.derivedStatus.primaryKey, { defaultValue: order.derivedStatus.primaryKey.replace(/^status\./, '').replace(/_/g, ' ') })}
                        </p>
                        {stateSub && <p className="text-small text-muted">{stateSub}</p>}
                      </div>
                    </div>
                    <div className="flex gap-2.5 mt-3.5">
                      <a
                        href={order.customerPhone ? `tel:${order.customerPhone}` : undefined}
                        className={cn(
                          'flex-1 btn justify-center rounded-xl border border-line bg-transparent text-primary hover:bg-elevated px-4 py-2 text-body',
                          !order.customerPhone && 'opacity-40 pointer-events-none'
                        )}
                      >
                        {t('orders.drawer.contactCustomer')}
                      </a>
                      <Button
                        variant="primary"
                        className="flex-1 justify-center"
                        disabled={!forward}
                        onClick={() => setTab('shipment')}
                      >
                        {t('orders.drawer.viewShipment')}
                      </Button>
                    </div>
                  </div>

                  <div>
                    <p className="text-caption font-bold text-muted uppercase tracking-wider mb-3">
                      {t('orderDetail.items')}
                    </p>
                    <ul>
                      {order.items.slice(0, 2).map(item => (
                        <li key={item.id} className="flex items-center gap-3 py-2.5 border-b border-line">
                          <ProductThumb src={null} alt={item.productTitle} size={40} />
                          <div className="min-w-0 flex-1">
                            <p className="text-small font-semibold text-primary truncate">{item.productTitle}</p>
                            {item.sku && <p className="text-caption text-muted font-mono">{item.sku}</p>}
                          </div>
                          <span className="text-small text-muted flex-shrink-0">×{item.quantity}</span>
                        </li>
                      ))}
                    </ul>
                    {order.items.length > 2 && (
                      <button
                        onClick={() => setTab('items')}
                        className="text-small text-trace-blue hover:text-trace-blue-hover pt-3"
                      >
                        {t('orders.drawer.viewAllItems')}
                      </button>
                    )}
                  </div>

                  <div>
                    <p className="text-caption font-bold text-muted uppercase tracking-wider mb-3">
                      {t('orders.drawer.traceTimeline')}
                    </p>
                    {timelineLoading ? (
                      <div className="space-y-2">
                        <Skeleton className="h-12 rounded-xl" />
                        <Skeleton className="h-12 rounded-xl" />
                        <Skeleton className="h-12 rounded-xl" />
                      </div>
                    ) : timelineError ? (
                      <div className="flex items-center gap-3 bg-elevated border border-line rounded-2xl p-4 text-critical">
                        <AlertTriangle size={18} strokeWidth={1.9} className="flex-shrink-0" />
                        <p className="text-small">{t('orders.drawer.timelineError')}</p>
                      </div>
                    ) : !timeline || timeline.length === 0 ? (
                      <div className="flex items-center gap-3 bg-elevated border border-line rounded-2xl p-4 text-muted">
                        <Clock size={18} strokeWidth={1.9} className="flex-shrink-0" />
                        <p className="text-small">{t('orders.drawer.timelineEmpty')}</p>
                      </div>
                    ) : (
                      <DrawerTimeline items={timeline} />
                    )}
                  </div>
                </div>
              )}

              {tab === 'items' && (
                <ul>
                  {order.items.map(item => (
                    <li key={item.id} className="flex items-center gap-3 py-2.5 border-b border-line">
                      <ProductThumb src={null} alt={item.productTitle} size={40} />
                      <div className="min-w-0 flex-1">
                        <p className="text-small font-semibold text-primary truncate">{item.productTitle}</p>
                        <p className="text-caption text-muted">{item.variantTitle}</p>
                        {item.sku && <p className="text-caption text-muted font-mono">{item.sku}</p>}
                      </div>
                      <span className="text-small text-muted flex-shrink-0">×{item.quantity}</span>
                    </li>
                  ))}
                </ul>
              )}

              {tab === 'shipment' && (
                order.shipments.length === 0
                  ? <EmptyState message={t('common.na')} />
                  : <div className="space-y-3">
                      {order.shipments.map(s => <ShipmentCard key={s.id} shipment={s} />)}
                    </div>
              )}

              {tab === 'notes' && (
                <EmptyState message={t('orders.drawer.notesEmpty')} />
              )}
            </div>

            {/* Footer — omitted entirely when the timeline has no events (loading,
                errored, or genuinely empty): there's no real "last updated" fact to
                show yet, and a blank/broken timestamp is worse than no footer. */}
            {timeline && timeline.length > 0 && (
              <div className="px-5 py-3 border-t border-line flex-shrink-0">
                <p className="text-caption text-muted">
                  {t('orders.drawer.lastUpdated', { time: relativeTime(timeline[timeline.length - 1].occurredAt, t) })}
                </p>
              </div>
            )}
          </>
        )}
      </aside>
    </>
  )
}

// ── Timeline rail — same border-s/-start-1.5 logical-property pattern as
// OrderDetail.tsx's DeliveryTimeline, so it mirrors correctly in RTL. Pure
// consumption: eventKey/kind/actorName/detail render straight from the backend
// payload — nothing here recomputes which row is "current"; that's the backend's
// kind field, already final by the time it reaches this component. ────────────────
function DrawerTimeline({ items }: { items: TimelineItem[] }) {
  const { t } = useTranslation()
  return (
    <ol className="relative border-s border-line ms-2 space-y-4">
      {items.map((item, i) => {
        const label = item.eventKey === 'picked_up'
          ? t('orders.timeline.picked_up', { name: item.actorName ?? t('orders.timeline.system') })
          : t(`orders.timeline.${item.eventKey}`)
        return (
          <li key={i} className="ps-4">
            <span
              className={cn(
                'absolute -start-1.5 mt-0.5 h-3 w-3 rounded-full border-2 border-base',
                item.kind === 'fail' ? 'bg-critical' : item.kind === 'now' ? 'bg-trace-blue' : 'bg-line'
              )}
            />
            <p className="text-caption text-muted">{new Date(item.occurredAt).toLocaleString()}</p>
            <p className={cn('text-small font-medium', item.kind === 'fail' ? 'text-danger' : 'text-primary')}>
              {label}
            </p>
            {item.detail && <p className="text-caption text-muted">{item.detail}</p>}
          </li>
        )
      })}
    </ol>
  )
}
