import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listPieces, PiecePage } from '../api'
import { Spinner } from '../components/ui'

export default function Inventory() {
  const { t } = useTranslation()
  const [params] = useSearchParams()

  const status    = params.get('status') ?? 'available'
  const within30d = params.get('within30d') === 'true'

  const [data,    setData]    = useState<PiecePage | null>(null)
  const [page,    setPage]    = useState(0)
  const [loading, setLoading] = useState(true)
  const [error,   setError]   = useState('')

  useEffect(() => {
    setLoading(true)
    setError('')
    listPieces({ status, within30d, page, size: 20 })
      .then(d => setData(d))
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [status, within30d, page, t])

  const statusLabel = t(`catalog.statuses.${status}`, status)
  const title = within30d
    ? t('inventory.drillTitle30d', { status: statusLabel })
    : t('inventory.drillTitle',    { status: statusLabel })

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const from  = total === 0 ? 0 : page * 20 + 1
  const to    = Math.min((page + 1) * 20, total)

  return (
    <div className="max-w-5xl mx-auto space-y-5">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Link to="/overview" className="text-small text-brand hover:text-brand-hover transition-colors">
          {t('inventory.back')}
        </Link>
      </div>
      <div>
        <h1 className="text-h1 text-primary">{title}</h1>
        {within30d && (
          <p className="text-small text-muted mt-1">{t('inventory.window30dNote')}</p>
        )}
      </div>

      {/* Error */}
      {error && (
        <div
          role="alert"
          className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2"
        >
          {error}
        </div>
      )}

      {/* Table */}
      {!error && (
        <div className="card overflow-hidden">
          <table className="w-full text-small">
            <thead className="bg-elevated border-b border-line">
              <tr>
                <th className="text-start px-4 py-3 font-medium text-muted">{t('inventory.col.barcode')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted">{t('inventory.col.variant')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted hidden sm:table-cell">{t('inventory.col.sku')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted hidden md:table-cell">{t('inventory.col.location')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted hidden lg:table-cell">{t('inventory.col.lastEvent')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {loading ? (
                <tr>
                  <td colSpan={5} className="text-center py-12">
                    <Spinner />
                  </td>
                </tr>
              ) : items.length === 0 ? (
                <tr>
                  <td colSpan={5} className="text-center py-12 text-muted">
                    {t('inventory.empty')}
                  </td>
                </tr>
              ) : (
                items.map(piece => (
                  <tr key={piece.id} className="hover:bg-elevated/50 transition-colors">
                    <td className="px-4 py-3 font-mono text-caption text-muted">
                      {piece.barcode}
                    </td>
                    <td className="px-4 py-3">
                      <p className="font-medium text-primary">{piece.variantTitle}</p>
                      <p className="text-caption text-muted">{piece.productTitle}</p>
                    </td>
                    <td className="px-4 py-3 text-muted hidden sm:table-cell">
                      {piece.sku ?? t('common.na')}
                    </td>
                    <td className="px-4 py-3 text-muted hidden md:table-cell">
                      {piece.locationName ?? t('common.na')}
                    </td>
                    <td className="px-4 py-3 text-muted hidden lg:table-cell">
                      {piece.lastEventAt
                        ? new Date(piece.lastEventAt).toLocaleDateString()
                        : t('common.na')}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>

          {/* Pagination */}
          {!loading && total > 0 && (
            <div className="flex items-center justify-between px-4 py-3 border-t border-line bg-elevated/50">
              <p className="text-caption text-muted">
                {t('inventory.showing', { from, to, total })}
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="btn btn-outline text-small py-1 px-3 disabled:opacity-40"
                >
                  {t('inventory.prev')}
                </button>
                <button
                  onClick={() => setPage(p => p + 1)}
                  disabled={to >= total}
                  className="btn btn-outline text-small py-1 px-3 disabled:opacity-40"
                >
                  {t('inventory.next')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
