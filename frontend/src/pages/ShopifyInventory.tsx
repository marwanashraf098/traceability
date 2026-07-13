import { useState, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { getAccessToken } from '../auth'

function authHeaders(): Record<string, string> {
  const t = getAccessToken()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

interface Adjustment {
  id: number
  batch_id: string
  trigger_type: 'receiving_session' | 'return_inspection'
  trigger_id: string
  sku: string
  variant_title: string
  product_title: string
  location_name: string
  delta: number
  status: 'shadow' | 'pending' | 'applied' | 'failed'
  error: string | null
  created_at: string
  applied_at: string | null
}

const STATUS_BADGE: Record<string, string> = {
  shadow:  'badge-gray',
  pending: 'badge-yellow',
  applied: 'badge-green',
  failed:  'badge-red',
}

function StatusBadge({ status }: { status: string }) {
  const { t } = useTranslation()
  const cls = STATUS_BADGE[status] ?? 'badge-gray'
  return <span className={`badge ${cls}`}>{t(`shopifyInventory.status.${status}`, status)}</span>
}

function TriggerBadge({ type }: { type: string }) {
  const { t } = useTranslation()
  return (
    <span className="inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium bg-surface text-secondary border border-line">
      {t(`shopifyInventory.trigger.${type}`, type)}
    </span>
  )
}

export default function ShopifyInventory() {
  const { t } = useTranslation()

  const [rows, setRows]   = useState<Adjustment[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage]   = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError]   = useState<string | null>(null)

  const [filterStatus,  setFilterStatus]  = useState('')
  const [filterTrigger, setFilterTrigger] = useState('')
  const [filterFrom,    setFilterFrom]    = useState('')
  const [filterTo,      setFilterTo]      = useState('')

  const [exporting, setExporting] = useState(false)

  const SIZE = 50

  const load = useCallback(async (p = 0) => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({ page: String(p), size: String(SIZE) })
      if (filterStatus)  params.set('status',      filterStatus)
      if (filterTrigger) params.set('triggerType', filterTrigger)
      if (filterFrom)    params.set('from',        filterFrom)
      if (filterTo)      params.set('to',          filterTo)

      const res = await fetch(`/api/v1/shopify-inventory/adjustments?${params}`,
        { headers: authHeaders() })
      if (!res.ok) throw new Error(res.statusText)
      const data = await res.json()
      setRows(data.rows)
      setTotal(data.total)
      setPage(p)
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [filterStatus, filterTrigger, filterFrom, filterTo, t])

  useEffect(() => { load(0) }, [load])

  async function exportCsv() {
    setExporting(true)
    try {
      const params = new URLSearchParams()
      if (filterStatus)  params.set('status',      filterStatus)
      if (filterTrigger) params.set('triggerType', filterTrigger)
      if (filterFrom)    params.set('from',        filterFrom)
      if (filterTo)      params.set('to',          filterTo)

      const res = await fetch(`/api/v1/shopify-inventory/adjustments/export.csv?${params}`,
        { headers: authHeaders() })
      if (!res.ok) throw new Error(res.statusText)
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'shopify-inventory-adjustments.csv'
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      // silently ignore export errors
    } finally {
      setExporting(false)
    }
  }

  const totalPages = Math.ceil(total / SIZE)
  const from = page * SIZE + 1
  const to   = Math.min((page + 1) * SIZE, total)

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-primary">{t('shopifyInventory.title')}</h1>
          <p className="text-sm text-muted mt-0.5">{t('shopifyInventory.subtitle')}</p>
        </div>
        <button
          onClick={exportCsv}
          disabled={exporting || rows.length === 0}
          className="btn-secondary text-sm"
        >
          {exporting ? t('common.loading') : t('shopifyInventory.export')}
        </button>
      </div>

      {/* Filters */}
      <div className="card p-4 flex flex-wrap gap-3">
        <select
          value={filterStatus}
          onChange={e => setFilterStatus(e.target.value)}
          className="input text-sm py-1.5 w-36"
        >
          <option value="">{t('shopifyInventory.allStatuses')}</option>
          <option value="shadow">{t('shopifyInventory.status.shadow')}</option>
          <option value="pending">{t('shopifyInventory.status.pending')}</option>
          <option value="applied">{t('shopifyInventory.status.applied')}</option>
          <option value="failed">{t('shopifyInventory.status.failed')}</option>
        </select>

        <select
          value={filterTrigger}
          onChange={e => setFilterTrigger(e.target.value)}
          className="input text-sm py-1.5 w-44"
        >
          <option value="">{t('shopifyInventory.allTriggers')}</option>
          <option value="receiving_session">{t('shopifyInventory.trigger.receiving_session')}</option>
          <option value="return_inspection">{t('shopifyInventory.trigger.return_inspection')}</option>
        </select>

        <input
          type="date"
          value={filterFrom}
          onChange={e => setFilterFrom(e.target.value)}
          className="input text-sm py-1.5 w-38"
          placeholder={t('shopifyInventory.from')}
        />
        <input
          type="date"
          value={filterTo}
          onChange={e => setFilterTo(e.target.value)}
          className="input text-sm py-1.5 w-38"
          placeholder={t('shopifyInventory.to')}
        />

        <button onClick={() => load(0)} className="btn-primary text-sm py-1.5">
          {t('shopifyInventory.apply')}
        </button>
      </div>

      {/* Table */}
      <div className="card overflow-hidden">
        {loading && (
          <div className="p-6 text-center text-muted text-sm">{t('common.loading')}</div>
        )}
        {error && (
          <div className="p-4 text-danger text-sm">{error}</div>
        )}
        {!loading && !error && rows.length === 0 && (
          <div className="p-6 text-center text-muted text-sm">{t('shopifyInventory.empty')}</div>
        )}
        {!loading && !error && rows.length > 0 && (
          <table className="w-full text-sm">
            <thead className="bg-surface border-b border-line">
              <tr>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.product')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.location')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.trigger')}</th>
                <th className="text-end   px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.delta')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.status')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('shopifyInventory.col.createdAt')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map(row => (
                <tr key={row.id} className={row.status === 'failed' ? 'bg-danger/5' : ''}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-primary">{row.product_title}</div>
                    <div className="text-xs text-muted">{row.variant_title} · {row.sku}</div>
                    {row.status === 'failed' && row.error && (
                      <div className="text-xs text-danger mt-0.5 truncate max-w-xs" title={row.error}>
                        {row.error}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-secondary">{row.location_name}</td>
                  <td className="px-4 py-3">
                    <TriggerBadge type={row.trigger_type} />
                  </td>
                  <td className="px-4 py-3 text-end font-mono">
                    <span className="text-success">+{row.delta}</span>
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={row.status} />
                  </td>
                  <td className="px-4 py-3 text-secondary text-xs">
                    {new Date(row.created_at).toLocaleString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Pagination */}
      {total > SIZE && (
        <div className="flex items-center justify-between text-sm text-secondary">
          <span>{t('shopifyInventory.showing', { from, to, total })}</span>
          <div className="flex gap-2">
            <button
              disabled={page === 0}
              onClick={() => load(page - 1)}
              className="btn-secondary text-xs py-1 px-3 disabled:opacity-40"
            >{t('shopifyInventory.prev')}</button>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => load(page + 1)}
              className="btn-secondary text-xs py-1 px-3 disabled:opacity-40"
            >{t('shopifyInventory.next')}</button>
          </div>
        </div>
      )}
    </div>
  )
}
