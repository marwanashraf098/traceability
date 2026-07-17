import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listOrders, OrderPage, listShopifyStores, syncShopifyStore } from '../api'
import {
  Badge, Button, DataTable, type DataTableColumn,
  DeliveryBadge, EmptyState, TableSkeleton,
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
      if (stores.length === 0) { setSyncMsg('No store connected'); return }
      await Promise.all(stores.map(s => syncShopifyStore(s.id)))
      setSyncMsg('Synced')
      fetchOrders()
    } catch {
      setSyncMsg('Sync failed')
    } finally {
      setSyncing(false)
    }
  }, [fetchOrders])

  function applyFilter(fn: () => void) { fn(); setPage(0) }

  const total = data?.total ?? 0
  const from  = total === 0 ? 0 : page * PAGE_SIZE + 1
  const to    = Math.min((page + 1) * PAGE_SIZE, total)
  const rows  = data?.items ?? []

  // ── Status cell ───────────────────────────────────────────────────────────────
  function renderStatus(order: OrderItem) {
    return (
      <div className="flex flex-wrap items-start gap-1.5">
        {order.deliveryState ? (
          <DeliveryBadge state={order.deliveryState} exceptionReason={order.exceptionReason} />
        ) : order.bostaLinkStatus === 'not_created' ? (
          <Badge tone="critical" label={t('delivery.state.not_created')} />
        ) : (
          <Badge
            status={order.status}
            label={t(`orders.pipeline.${order.status}`, { defaultValue: order.status.replace(/_/g, ' ') })}
          />
        )}
        {order.onHold && (
          <Badge tone="critical" label={t('orderDetail.onHold')} />
        )}
        {order.failedDeliveryAttempts > 0 && (
          <Badge tone="critical" label={t('orderDetail.failedAttempts', { count: order.failedDeliveryAttempts })} />
        )}
        {(order.isDelayed || order.slaBreached) && (
          <Badge tone="warning" label={t('orderDetail.delayed')} />
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
        // Order number — mono, links to detail
        <Link
          to={`/orders/${row.id}`}
          className="text-trace-blue hover:text-trace-blue-hover font-medium transition-colors"
        >
          {row.number ?? t('common.na')}
        </Link>
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
      header: t('orders.columns.cod', { defaultValue: 'COD' }),
      render: row => row.codAmount != null
        ? <span className="text-warning">{row.codAmount.toLocaleString()} EGP</span>
        : <span className="text-muted">{t('common.na')}</span>,
    },
    {
      key: 'placedAt',
      header: t('orders.columns.placedAt', { defaultValue: 'Placed' }),
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
            {syncing ? 'Shopify' : '↻ Shopify'}
          </Button>
        </div>
      </div>

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

      {error && <p className="text-small text-danger">{error}</p>}

      {/* Table — loading/empty/data handled here so EmptyState can carry an action */}
      <div className="card overflow-hidden">
        {loading ? (
          <TableSkeleton rows={5} cols={columns.length} />
        ) : rows.length === 0 ? (
          <EmptyState
            message={t('orders.empty')}
            icon="📦"
            action={{ label: '↻ Sync from Shopify', onClick: handleSync }}
          />
        ) : (
          <DataTable columns={columns} rows={rows} />
        )}
      </div>

      {/* Pagination */}
      {!loading && total > 0 && (
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
