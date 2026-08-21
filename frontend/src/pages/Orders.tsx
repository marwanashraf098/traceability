import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronRight } from 'lucide-react'
import {
  listOrders, OrderPage, listShopifyStores, syncShopifyStore,
  getOrdersSummary, OrderSummaryCounts, getExceptionsCount,
} from '../api'
import {
  Alert, Badge, Button, DataTable, type DataTableColumn,
  EmptyState, LegStatusBadge, Tabs, TableSkeleton,
} from '../components/ui'
import OrderDrawer from '../components/OrderDrawer'

const PAGE_SIZE = 20

// Infer item type from the API's OrderPage shape
type OrderItem = NonNullable<OrderPage['items']>[number]

// Tabs. All/needsAttention aside, these filter on the SHIPMENT's own internal_state
// (deliveryState param) — NOT orders.status. Confirmed by grep: the app never writes
// orders.status to 'with_courier' or 'returned' (only ShipmentLinkService's
// 'awaiting_pickup' and FulfillService's self-pickup-only 'delivered'/'cancelled'
// writes exist) — the real courier-pipeline signal lives solely on the shipment row.
// Using orders.status here would make the tab's count (from /orders/summary, which
// DOES derive off shipment state) and its click-through list silently diverge —
// exactly the parity bug caught in review. 'needsAttention' still has no equivalent
// column at all (derived from exception fields, not a single value) — count-only.
const TAB_DEFS = [
  { key: 'all',            labelKey: 'orders.tabs.all',            filterDeliveryState: null as string | null, clickable: true },
  { key: 'needsAttention', labelKey: 'orders.tabs.needsAttention', filterDeliveryState: null as string | null, clickable: false },
  { key: 'inTransit',      labelKey: 'orders.tabs.inTransit',      filterDeliveryState: 'with_courier',        clickable: true },
  { key: 'delivered',      labelKey: 'orders.tabs.delivered',      filterDeliveryState: 'delivered',           clickable: true },
  { key: 'returns',        labelKey: 'orders.tabs.returns',        filterDeliveryState: 'returned',            clickable: true },
] as const

export default function Orders() {
  const { t } = useTranslation()
  const [data,     setData]     = useState<OrderPage | null>(null)
  const [deliveryStateFilter, setDeliveryStateFilter] = useState('')
  const [q,        setQ]        = useState('')
  const [tracking, setTracking] = useState('')
  const [page,     setPage]     = useState(0)
  const [loading,  setLoading]  = useState(false)
  const [error,    setError]    = useState('')
  const [syncing,  setSyncing]  = useState(false)
  const [syncMsg,  setSyncMsg]  = useState('')
  const [summary,  setSummary]  = useState<OrderSummaryCounts | null>(null)
  const [needsAttention, setNeedsAttention] = useState<number | null>(null)
  const [drawerOrderId, setDrawerOrderId]   = useState<string | null>(null)

  // Independent, non-blocking — a failure here must never affect the table below (no
  // shared error state), and there's simply no tile row while it's unset.
  useEffect(() => {
    getOrdersSummary().then(setSummary).catch(() => {})
    // Same source as the shell's Alerts bell (Layout's own /exceptions/count poll) — the
    // Needs-attention tab and subheader must never show a number that could contradict it.
    getExceptionsCount().then(c => setNeedsAttention(c.count)).catch(() => {})
  }, [])

  const fetchOrders = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const res = await listOrders({
        deliveryState: deliveryStateFilter || undefined,
        q:        q || undefined,
        tracking: tracking || undefined,
        page,
        size: PAGE_SIZE,
      })
      setData(res)
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [deliveryStateFilter, q, tracking, page, t])

  useEffect(() => { fetchOrders() }, [fetchOrders])

  const handleSync = useCallback(async () => {
    setSyncing(true)
    setSyncMsg('')
    try {
      const stores = await listShopifyStores()
      if (stores.length === 0) { setSyncMsg(t('orders.syncNoStore')); return }
      await Promise.all(stores.map(s => syncShopifyStore(s.id)))
      setSyncMsg(t('orders.syncSuccess'))
      fetchOrders()
    } catch {
      setSyncMsg(t('orders.syncError'))
    } finally {
      setSyncing(false)
    }
  }, [fetchOrders, t])

  function applyFilter(fn: () => void) { fn(); setPage(0) }

  const total = data?.total ?? 0
  const from  = total === 0 ? 0 : page * PAGE_SIZE + 1
  const to    = Math.min((page + 1) * PAGE_SIZE, total)
  const rows  = data?.items ?? []

  // ── Fulfillment / Delivery cells ─────────────────────────────────────────────
  // Two facets of the single OrderStatusDeriver output — no client-side re-derivation.
  // Fulfillment reads derivedStatus.fulfillmentKey/.fulfillmentTone (new additive deriver
  // fields); Delivery reads derivedStatus.primaryKey/.tone (the existing composite facet,
  // untouched). The only branch here is presentational, not a re-derivation: no shipment
  // linked (row.deliveryState null, the same signal the LATERAL join already leaves null)
  // renders the "Not shipped" placeholder instead of echoing the pipeline stage — approved
  // in Step 0 review. onHold is a separate order-level flag, rendered alongside Fulfillment.
  function renderFulfillment(order: OrderItem) {
    return (
      <div className="flex flex-wrap items-start gap-1.5">
        <LegStatusBadge legStatus={{ primaryKey: order.derivedStatus.fulfillmentKey, tone: order.derivedStatus.fulfillmentTone }} />
        {order.onHold && <Badge tone="critical" label={t('orderDetail.onHold')} />}
        {/* Quiet marker for fulfilled-outside-Traced orders — same derivedStatus.notTraced
            flag the (dormant) stepper suppression already reads. Neutral/muted tone
            deliberately — must not shout over the ~60 normal orders in the list. */}
        {order.derivedStatus.notTraced && <Badge tone="neutral" label={t('orders.badge.notTraced')} />}
      </div>
    )
  }

  function renderDelivery(order: OrderItem) {
    if (!order.deliveryState) {
      return <LegStatusBadge legStatus={{ primaryKey: 'orders.delivery.notShipped', tone: 'NEUTRAL' }} />
    }
    // Cell-level label override ONLY — status.label_created itself is untouched (the
    // drawer's Current-state pill and the Overview funnel both still read that shared key
    // and must keep saying "Label created", a state). This list cell is stating an event
    // ("an AWB now exists for this order"), so it reads differently on purpose.
    const labelOverride = order.derivedStatus.primaryKey === 'status.label_created'
      ? t('orders.delivery.awbCreated')
      : undefined
    return (
      <LegStatusBadge
        legStatus={{ primaryKey: order.derivedStatus.primaryKey, tone: order.derivedStatus.tone }}
        labelOverride={labelOverride}
      />
    )
  }

  // ── Column definitions ─────────────────────────────────────────────────────
  const columns: DataTableColumn<OrderItem>[] = [
    {
      key: 'number',
      header: t('orders.columns.number', { defaultValue: 'Order' }),
      mono: true,
      render: row => (
        // Order number — mono, opens the same drawer the row click does (the full
        // OrderDetail.tsx page is retired/unrouted). Exchanges stay inline with normal
        // orders (no filter, no row restyling, no separate section) — the badge is
        // the only differentiator, same component QueueView/PickScreen already use.
        // stopPropagation is defensive here (this button's own click already does
        // exactly what the row click does) — kept so a future divergence between the
        // two can't accidentally double-fire.
        <div className="flex items-center gap-1.5" onClick={e => e.stopPropagation()}>
          <button
            type="button"
            onClick={() => setDrawerOrderId(row.id)}
            className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
          >
            {row.number ?? t('common.na')}
          </button>
          {row.isExchange && <Badge tone="info" label={t('exchange.badge')} />}
        </div>
      ),
    },
    {
      key: 'customer',
      header: t('orders.columns.customer', { defaultValue: 'Customer' }),
      render: row => (
        <div>
          <div className="text-primary">{row.customerName ?? t('common.na')}</div>
          {row.customerPhone && (
            <div className="text-small text-muted mt-0.5">{row.customerPhone}</div>
          )}
        </div>
      ),
    },
    {
      key: 'fulfillment',
      header: t('orders.columns.fulfillment'),
      render: renderFulfillment,
    },
    {
      key: 'delivery',
      header: t('orders.columns.delivery'),
      render: renderDelivery,
    },
    {
      key: 'cod',
      header: t('orders.columns.cod', { defaultValue: 'Amount' }),
      align: 'end',
      render: row => row.codAmount != null
        ? <span className="font-mono text-primary">{row.codAmount.toLocaleString()} EGP</span>
        : <span className="text-muted">{t('common.na')}</span>,
    },
    {
      key: 'placedAt',
      header: t('orders.columns.placedAt', { defaultValue: 'Date' }),
      align: 'end',
      render: row => (
        <span className="text-small text-muted">
          {row.placedAt ? new Date(row.placedAt).toLocaleDateString() : t('common.na')}
        </span>
      ),
    },
    {
      // Visual hint only — NOT a separate click target. The whole row already opens
      // the drawer (DataTable's onRowClick); this chevron just signals that. Hidden
      // until row hover (DataTable's <tr> carries `group`), muted, and flips for RTL
      // via the same rtl:rotate-180 convention used in Fulfill.tsx.
      key: 'chevron',
      header: '',
      align: 'end',
      render: () => (
        <ChevronRight
          size={16}
          strokeWidth={2}
          className="text-muted opacity-0 group-hover:opacity-100 transition-opacity rtl:rotate-180"
        />
      ),
    },
  ]

  const activeTabKey = TAB_DEFS.find(d => d.filterDeliveryState === (deliveryStateFilter || null))?.key ?? 'all'
  function handleTabClick(key: string) {
    const def = TAB_DEFS.find(d => d.key === key)
    if (!def || !def.clickable) return
    applyFilter(() => setDeliveryStateFilter(def.filterDeliveryState ?? ''))
  }
  const tabCount = (key: typeof TAB_DEFS[number]['key']): number | undefined => {
    switch (key) {
      case 'all':            return summary?.total
      case 'needsAttention': return needsAttention ?? undefined
      case 'inTransit':      return summary?.withCourier
      case 'delivered':      return summary?.delivered
      case 'returns':        return summary?.returned
    }
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('orders.title')}</h1>
        <div className="flex items-center gap-3">
          {syncMsg && <span className="text-small text-muted">{syncMsg}</span>}
          <Button
            variant="secondary"
            size="sm"
            loading={syncing}
            onClick={handleSync}
          >
            {t('orders.sync')}
          </Button>
        </div>
      </div>

      {/* Subheader — total + needs-attention count, calm and independent of table state */}
      <p className="text-small text-muted -mt-2" data-testid="orders-subheader">
        {t('orders.totalCount', { count: summary?.total ?? 0 })}
        {!!needsAttention && (
          <>
            <span className="mx-2 text-muted/50">•</span>
            <span className="text-trace-blue font-medium">
              {t('orders.needAttentionSubheader', { count: needsAttention })}
            </span>
          </>
        )}
      </p>

      {/* Tabs — counts from /orders/summary (+ /exceptions/count for Needs attention);
          clicking filters on the shipment's own internal_state (see TAB_DEFS comment). */}
      <Tabs
        activeKey={activeTabKey}
        onChange={handleTabClick}
        tabs={TAB_DEFS.map(def => ({
          key: def.key,
          label: t(def.labelKey),
          count: tabCount(def.key),
          disabled: !def.clickable,
        }))}
      />

      {/* Filter bar — raw inputs keep .input class; Input component can't hold fixed width without wrapper */}
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="text"
          placeholder={t('orders.search')}
          value={q}
          onChange={e => applyFilter(() => setQ(e.target.value))}
          className="input w-64"
        />
        <input
          type="text"
          placeholder={t('orders.searchTracking')}
          value={tracking}
          onChange={e => applyFilter(() => setTracking(e.target.value))}
          className="input w-40"
        />
      </div>

      {/* Table — loading/empty/error/data handled here so EmptyState can carry an action */}
      <div className="card overflow-hidden">
        {error ? (
          <div className="p-4">
            <Alert tone="critical" title={error} />
          </div>
        ) : loading ? (
          <TableSkeleton rows={5} cols={columns.length} />
        ) : rows.length === 0 ? (
          <EmptyState
            message={t('orders.empty')}
            icon="📦"
            action={{ label: t('orders.sync'), onClick: handleSync }}
          />
        ) : (
          <DataTable columns={columns} rows={rows} onRowClick={row => setDrawerOrderId(row.id)} />
        )}
      </div>

      {/* Pagination */}
      {!loading && !error && total > 0 && (
        <div className="flex items-center justify-between text-small text-muted">
          <span>{t('orders.showing', { from, to, total })}</span>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
            >
              {t('orders.prev')}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={to >= total}
              onClick={() => setPage(p => p + 1)}
            >
              {t('orders.next')}
            </Button>
          </div>
        </div>
      )}

      {/* Slide-in overlay, not a route — Layout stays outside this view-switch. */}
      <OrderDrawer orderId={drawerOrderId} onClose={() => setDrawerOrderId(null)} />
    </div>
  )
}
