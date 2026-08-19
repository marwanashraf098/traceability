import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listOrders, OrderPage, listShopifyStores, syncShopifyStore, getOrdersSummary, OrderSummaryCounts } from '../api'
import {
  Alert, Badge, Button, DataTable, type DataTableColumn,
  EmptyState, OrderStatus, StatCard, TableSkeleton,
} from '../components/ui'

const ORDER_STATUSES = [
  'new', 'confirmed', 'ready_to_pick', 'picking', 'packed',
  'awaiting_pickup', 'with_courier', 'delivered',
  'returning', 'returned', 'lost', 'cancelled',
]

const PAGE_SIZE = 20

// Infer item type from the API's OrderPage shape
type OrderItem = NonNullable<OrderPage['items']>[number]

export default function Orders() {
  const { t } = useTranslation()
  const [data,     setData]     = useState<OrderPage | null>(null)
  const [status,   setStatus]   = useState('')
  const [q,        setQ]        = useState('')
  const [tracking, setTracking] = useState('')
  const [page,     setPage]     = useState(0)
  const [loading,  setLoading]  = useState(false)
  const [error,    setError]    = useState('')
  const [syncing,  setSyncing]  = useState(false)
  const [syncMsg,  setSyncMsg]  = useState('')
  const [summary,  setSummary]  = useState<OrderSummaryCounts | null>(null)

  // Independent, non-blocking — a failure here must never affect the table below (no
  // shared error state), and there's simply no tile row while it's unset.
  useEffect(() => {
    getOrdersSummary().then(setSummary).catch(() => {})
  }, [])

  const fetchOrders = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const res = await listOrders({
        status:   status || undefined,
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
  }, [status, q, tracking, page, t])

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

  // ── Status cell ───────────────────────────────────────────────────────────────
  // FR-7/FR-11: single derived headline — no independent pipeline/delivery-state
  // reads here. onHold is a separate order-level flag, not part of the shipment
  // derivation, so it's still rendered alongside it.
  function renderStatus(order: OrderItem) {
    return (
      <div className="flex flex-wrap items-start gap-1.5">
        <OrderStatus derived={order.derivedStatus} />
        {order.onHold && (
          <Badge tone="critical" label={t('orderDetail.onHold')} />
        )}
      </div>
    )
  }

  // ── Column definitions ─────────────────────────────────────────────────────
  const columns: DataTableColumn<OrderItem>[] = [
    {
      key: 'number',
      header: t('orders.columns.number', { defaultValue: 'Order' }),
      mono: true,
      render: row => (
        // Order number — mono, links to detail. Exchanges stay inline with normal
        // orders (no filter, no row restyling, no separate section) — the badge is
        // the only differentiator, same component QueueView/PickScreen already use.
        <div className="flex items-center gap-1.5">
          <Link
            to={`/orders/${row.id}`}
            className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
          >
            {row.number ?? t('common.na')}
          </Link>
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
      key: 'status',
      header: t('orders.columns.status', { defaultValue: 'Status' }),
      render: renderStatus,
    },
    {
      key: 'cod',
      header: t('orders.columns.cod', { defaultValue: 'Amount' }),
      render: row => row.codAmount != null
        ? <span className="font-mono text-primary">{row.codAmount.toLocaleString()} EGP</span>
        : <span className="text-muted">{t('common.na')}</span>,
    },
    {
      key: 'placedAt',
      header: t('orders.columns.placedAt', { defaultValue: 'Date' }),
      render: row => (
        <span className="text-small text-muted">
          {row.placedAt ? new Date(row.placedAt).toLocaleDateString() : t('common.na')}
        </span>
      ),
    },
    {
      key: 'tracking',
      header: t('orders.columns.tracking', { defaultValue: 'Tracking' }),
      mono: true,
      render: row => (
        // Tracking number — mono per spec
        <span className="text-caption text-muted">{row.trackingNumber ?? t('common.na')}</span>
      ),
    },
  ]

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

      {/* Summary tile row — calm, independent of the table's own loading/error state */}
      {summary && (
        <div className="grid grid-cols-5 gap-3" data-testid="orders-summary">
          <StatCard label={t('orders.summary.total')}      value={summary.total} />
          <StatCard label={t('orders.summary.processing')} value={summary.processing} />
          <StatCard label={t('orders.pipeline.with_courier')} value={summary.withCourier} />
          <StatCard label={t('orders.pipeline.delivered')} value={summary.delivered} />
          <StatCard label={t('orders.pipeline.returned')}  value={summary.returned} />
        </div>
      )}

      {/* Filter bar — raw inputs keep .input class; Input component can't hold fixed width without wrapper */}
      <div className="flex flex-wrap gap-2">
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
        <select
          value={status}
          onChange={e => applyFilter(() => setStatus(e.target.value))}
          className="input w-auto"
        >
          <option value="">{t('orders.filterStatus')}</option>
          {ORDER_STATUSES.map(s => (
            <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
          ))}
        </select>
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
          <DataTable columns={columns} rows={rows} />
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
    </div>
  )
}
