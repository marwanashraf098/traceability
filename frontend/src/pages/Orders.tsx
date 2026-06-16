import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listOrders, OrderPage, listShopifyStores, syncShopifyStore } from '../api'

const ORDER_STATUSES = [
  'new', 'confirmed', 'ready_to_pick', 'picking', 'packed',
  'awaiting_pickup', 'with_courier', 'delivered',
  'returning', 'returned', 'lost', 'cancelled',
]

const STATUS_BADGE: Record<string, string> = {
  new:            'bg-gray-100 text-gray-700',
  confirmed:      'bg-blue-100 text-blue-700',
  ready_to_pick:  'bg-yellow-100 text-yellow-700',
  picking:        'bg-orange-100 text-orange-700',
  packed:         'bg-purple-100 text-purple-700',
  awaiting_pickup:'bg-indigo-100 text-indigo-700',
  with_courier:   'bg-sky-100 text-sky-700',
  delivered:      'bg-green-100 text-green-700',
  returning:      'bg-amber-100 text-amber-700',
  returned:       'bg-teal-100 text-teal-700',
  lost:           'bg-red-100 text-red-700',
  cancelled:      'bg-gray-200 text-gray-500',
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

  const fetch = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const res = await listOrders({ status: status || undefined, q: q || undefined, tracking: tracking || undefined, page, size: PAGE_SIZE })
      setData(res)
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [status, q, tracking, page, t])

  useEffect(() => { fetch() }, [fetch])

  const handleSync = useCallback(async () => {
    setSyncing(true)
    setSyncMsg('')
    try {
      const stores = await listShopifyStores()
      if (stores.length === 0) { setSyncMsg('No store connected'); return }
      await Promise.all(stores.map(s => syncShopifyStore(s.id)))
      setSyncMsg('Sync complete')
      fetch()
    } catch {
      setSyncMsg('Sync failed')
    } finally {
      setSyncing(false)
    }
  }, [])

  // Reset to page 0 when filters change
  function applyFilter(fn: () => void) {
    fn()
    setPage(0)
  }

  const total = data?.total ?? 0
  const from  = total === 0 ? 0 : page * PAGE_SIZE + 1
  const to    = Math.min((page + 1) * PAGE_SIZE, total)

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">{t('orders.title')}</h1>
        <div className="flex items-center gap-3">
          {syncMsg && <span className="text-sm text-gray-500">{syncMsg}</span>}
          <button
            onClick={handleSync}
            disabled={syncing}
            className="text-sm px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white rounded font-medium transition"
          >
            {syncing ? 'Syncing…' : '↻ Sync Shopify'}
          </button>
        </div>
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap gap-3 mb-4">
        <input
          type="text"
          placeholder={t('orders.search')}
          value={q}
          onChange={e => applyFilter(() => setQ(e.target.value))}
          className="border border-gray-300 rounded px-3 py-1.5 text-sm w-72 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <input
          type="text"
          placeholder={t('orders.searchTracking')}
          value={tracking}
          onChange={e => applyFilter(() => setTracking(e.target.value))}
          className="border border-gray-300 rounded px-3 py-1.5 text-sm w-44 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <select
          value={status}
          onChange={e => applyFilter(() => setStatus(e.target.value))}
          className="border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          <option value="">{t('orders.filterStatus')}</option>
          {ORDER_STATUSES.map(s => (
            <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
          ))}
        </select>
      </div>

      {error && <p className="text-red-600 text-sm mb-4">{error}</p>}

      {loading ? (
        <p className="text-gray-500 text-sm">{t('common.loading')}</p>
      ) : (
        <>
          <div className="bg-white shadow rounded-lg overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50">
                <tr>
                  {(['number','customer','status','cod','placedAt','tracking'] as const).map(col => (
                    <th key={col} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">
                      {t(`orders.columns.${col}`)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data?.items.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                      {t('orders.empty')}
                    </td>
                  </tr>
                )}
                {data?.items.map(order => (
                  <tr key={order.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-medium text-indigo-600">
                      <Link to={`/orders/${order.id}`}>{order.number ?? t('common.na')}</Link>
                    </td>
                    <td className="px-4 py-3">
                      <div>{order.customerName ?? t('common.na')}</div>
                      {order.customerPhone && (
                        <div className="text-xs text-gray-400 mt-0.5">{order.customerPhone}</div>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1">
                        <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_BADGE[order.status] ?? 'bg-gray-100 text-gray-600'}`}>
                          {order.status.replace(/_/g, ' ')}
                        </span>
                        {order.onHold && (
                          <span className="inline-flex px-1.5 py-0.5 rounded text-xs font-bold bg-red-100 text-red-700">HOLD</span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      {order.codAmount != null ? `${order.codAmount.toLocaleString()} EGP` : t('common.na')}
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">
                      {order.placedAt ? new Date(order.placedAt).toLocaleDateString() : t('common.na')}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-500">
                      {order.trackingNumber ?? t('common.na')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between mt-4 text-sm text-gray-500">
            <span>
              {total > 0
                ? t('orders.showing', { from, to, total })
                : t('orders.empty')}
            </span>
            <div className="flex gap-2">
              <button
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
                className="px-3 py-1.5 border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50 transition-colors"
              >
                {t('orders.prev')}
              </button>
              <button
                disabled={to >= total}
                onClick={() => setPage(p => p + 1)}
                className="px-3 py-1.5 border border-gray-300 rounded disabled:opacity-40 hover:bg-gray-50 transition-colors"
              >
                {t('orders.next')}
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
