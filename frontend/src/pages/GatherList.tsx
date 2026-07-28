import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getGatherList, GatherListResponse } from '../api'
import { Button, EmptyState, TableSkeleton } from '../components/ui'

export default function GatherList() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const [data, setData] = useState<GatherListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const resp = await getGatherList()
      setData(resp)
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { load() }, [load])

  const rows = data?.rows ?? []

  return (
    <div className="p-6 max-w-4xl mx-auto" data-testid="gather-list">
      {/* Print-only rules: A6 handheld pick sheet, light theme (the app itself is
          dark-only — index.css sets html/body to #0D1117/#F2F4F7 with no light
          variant), hide interactive chrome. Table columns hidden below the sm/md
          breakpoint would otherwise stay hidden on an A6-width print page (~105mm,
          well under the sm: 640px breakpoint) — print:table-cell forces them back. */}
      <style>{`
        @media print {
          @page { size: A6; margin: 6mm; }

          .gather-no-print { display: none !important; }

          html, body {
            background: #ffffff !important;
            color: #111111 !important;
          }

          .text-primary { color: #111111 !important; }
          .text-muted   { color: #444444 !important; }

          .card {
            background: #ffffff !important;
            border-color: #cccccc !important;
            box-shadow: none !important;
          }
          .bg-elevated { background: #f0f0f0 !important; }

          table, th, td, tr { border-color: #cccccc !important; }

          table { width: 100% !important; font-size: 10px; }
          th, td { padding: 3px 5px !important; }
        }
      `}</style>

      <div className="flex items-center justify-between mb-2 gather-no-print">
        <h1 className="text-h1 text-primary">{t('fulfill.gather.title')}</h1>
        <div className="flex gap-2">
          <Button variant="tertiary" size="sm" onClick={load}>
            {t('fulfill.gather.refresh')}
          </Button>
          <Button variant="tertiary" size="sm" onClick={() => window.print()}>
            {t('fulfill.gather.print')}
          </Button>
          <Button variant="secondary" size="sm" onClick={() => navigate('/fulfill')}>
            {t('fulfill.gather.back')}
          </Button>
        </div>
      </div>

      <h1 className="hidden print:block text-h1 text-primary mb-2">
        {t('fulfill.gather.title')}
      </h1>

      {data && (
        <p className="text-small text-muted mb-6">
          {t('fulfill.gather.subtitle', { count: data.orderCount })}
          {' · '}
          {t('fulfill.gather.generatedAt', { time: new Date(data.generatedAt).toLocaleTimeString() })}
        </p>
      )}

      {error && (
        <div
          role="alert"
          className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2 mb-4 gather-no-print"
        >
          {error}
        </div>
      )}

      {loading ? (
        <TableSkeleton rows={5} cols={4} />
      ) : rows.length === 0 ? (
        <EmptyState message={t('fulfill.gather.empty')} icon="📦" />
      ) : (
        <div className="card overflow-hidden">
          <table className="w-full text-small">
            <thead className="bg-elevated border-b border-line">
              <tr>
                <th className="text-start px-4 py-3 font-medium text-muted">{t('fulfill.gather.colItem')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted hidden sm:table-cell print:table-cell">{t('fulfill.gather.colSku')}</th>
                <th className="text-end px-4 py-3 font-medium text-muted">{t('fulfill.gather.colNeeded')}</th>
                <th className="text-end px-4 py-3 font-medium text-muted">{t('fulfill.gather.colAvailable')}</th>
                <th className="text-start px-4 py-3 font-medium text-muted hidden md:table-cell print:table-cell">{t('fulfill.gather.colOrders')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map(row => (
                <tr
                  key={row.variantId}
                  className={row.shortage ? 'bg-danger/10' : ''}
                >
                  <td className="px-4 py-3">
                    <p className="font-medium text-primary">{row.displayName}</p>
                    {row.shortage && (
                      <p className="text-caption text-danger">
                        {t('fulfill.gather.shortageNote', {
                          shortfall: row.needed - row.availableCount,
                          needed: row.needed,
                          available: row.availableCount,
                        })}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted hidden sm:table-cell print:table-cell">
                    {row.sku ?? t('common.na')}
                  </td>
                  <td className="px-4 py-3 text-end font-mono">{row.needed}</td>
                  <td className={`px-4 py-3 text-end font-mono ${row.shortage ? 'text-danger font-semibold' : ''}`}>
                    {row.availableCount}
                  </td>
                  <td className="px-4 py-3 text-muted hidden md:table-cell print:table-cell">
                    {row.orderNumbers.join(', ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
