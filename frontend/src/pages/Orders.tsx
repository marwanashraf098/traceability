import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listOrders, OrderPage, listShopifyStores, syncShopifyStore } from '../api'
import { DeliveryBadge, EmptyState, Spinner } from '../components/ui'

const ORDER_STATUSES = [
  'new', 'confirmed', 'ready_to_pick', 'picking', 'packed',
  'awaiting_pickup', 'with_courier', 'delivered',
  'returning', 'returned', 'lost', 'cancelled',
]

const STATUS_STYLE: Record<string, string> = {
  new:             'bg-muted/10 text-muted border-muted/20',
  confirmed:       'bg-accent/10 text-accent border-accent/20',
  ready_to_pick:   'bg-warning/10 text-warning border-warning/20',
  picking:         'bg-warning/10 text-warning border-warning/20',
  packed:          'bg-brand/10 text-brand border-brand/20',
  awaiting_pickup: 'bg-brand/10 text-brand border-brand/20',
  with_courier:    'bg-cyan/10 text-cyan border-cyan/20',
  delivered:       'bg-success/10 text-success border-success/20',
  returning:       'bg-warning/10 text-warning border-warning/20',
  returned:        'bg-muted/10 text-muted border-muted/20',
  lost:            'bg-danger/10 text-danger border-danger/20',
  cancelled:       'bg-muted/10 text-muted border-muted/20',
}

const PAGE_SIZE = 20

export default function Orders() {
  const { t } = useTranslation()
  const [data, setData]         = useState<OrderPage | null>(null)
  const [status, setStatus]     = useState('')
  const [q, setQ]               = useState('')
  const [tracking, setTracking] = useState('')
  const [page, setPage]         = useState(0)
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState('')
  const [syncing, setSyncing]   = useState(false)
  const [syncMsg, setSyncMsg]   = useState('')

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

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('orders.title')}</h1>
        <div className="flex items-center gap-3">
          {syncMsg && <span className="text-small text-muted">{syncMsg}</span>}
          <button
            onClick={handleSync}
            disabled={syncing}
            className="btn-outline btn text-small gap-2"
          >
            {syncing ? <Spinner size={14} /> : '↻'} Shopify
          </button>
        </div>
      </div>

      {/* Filter bar */}
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

      {/* Table */}
      <div className="card overflow-hidden">
        <table className="min-w-full">
          <thead>
            <tr className="border-b border-line">
              {(['number','customer','status','cod','placedAt','tracking'] as const).map(col => (
                <th key={col} className="tbl-header">{t(`orders.columns.${col}`, { defaultValue: col })}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={6} className="px-4 py-12 text-center">
                  <Spinner />
                </td>
              </tr>
            ) : data?.items.length === 0 ? (
              <tr>
                <td colSpan={6}>
                  <EmptyState message={t('orders.empty')} icon="📦" />
                </td>
              </tr>
            ) : (
              data?.items.map(order => (
                <tr key={order.id} className="tbl-row">
                  <td className="tbl-cell font-medium">
                    <Link to={`/orders/${order.id}`} className="text-brand hover:text-brand-hover transition-colors">
                      {order.number ?? t('common.na')}
                    </Link>
                  </td>
                  <td className="tbl-cell">
                    <div className="text-primary">{order.customerName ?? t('common.na')}</div>
                    {order.customerPhone && (
                      <div className="text-small text-muted mt-0.5">{order.customerPhone}</div>
                    )}
                  </td>
                  <td className="tbl-cell">
                    <div className="flex flex-wrap items-start gap-1.5">
                      {order.deliveryState ? (
                        <DeliveryBadge
                          state={order.deliveryState}
                          exceptionReason={order.exceptionReason}
                        />
                      ) : order.bostaLinkStatus === 'not_created' ? (
                        <span className="badge border bg-danger/10 text-danger border-danger/20">
                          {t('delivery.state.not_created')}
                        </span>
                      ) : (
                        <span className={`badge border ${STATUS_STYLE[order.status] ?? 'bg-muted/10 text-muted border-muted/20'}`}>
                          {t(`orders.pipeline.${order.status}`, { defaultValue: order.status.replace(/_/g, ' ') })}
                        </span>
                      )}
                      {order.onHold && (
                        <span className="badge border bg-danger/10 text-danger border-danger/20 font-bold">
                          {t('orderDetail.onHold')}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="tbl-cell text-warning">
                    {order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : <span className="text-muted">{t('common.na')}</span>}
                  </td>
                  <td className="tbl-cell text-muted text-small">
                    {order.placedAt ? new Date(order.placedAt).toLocaleDateString() : t('common.na')}
                  </td>
                  <td className="tbl-cell font-mono text-caption text-muted">
                    {order.trackingNumber ?? t('common.na')}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {!loading && total > 0 && (
        <div className="flex items-center justify-between text-small text-muted">
          <span>{t('orders.showing', { from, to, total })}</span>
          <div className="flex gap-2">
            <button
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
              className="btn-outline btn text-small disabled:opacity-30"
            >
              {t('orders.prev')}
            </button>
            <button
              disabled={to >= total}
              onClick={() => setPage(p => p + 1)}
              className="btn-outline btn text-small disabled:opacity-30"
            >
              {t('orders.next')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
