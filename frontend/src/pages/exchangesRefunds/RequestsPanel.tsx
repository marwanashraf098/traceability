import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getReturnRequests, ReturnRequestRow } from '../../api'
import { Badge, Button, DataTable, DataTableColumn } from '../../components/ui'
import { reasonLabel, requestStatusTone, sentLabel, shortCustomerName } from './requestFormat'
import ReturnRequestDrawer from './ReturnRequestDrawer'

export const REQUESTS_PAGE_SIZE = 25

/**
 * Returns portal Step 4e-A (M1) — the "Requests" tab of Exchanges & Refunds. Owner and
 * manager only (the parent never mounts it for a worker; the API answers 403 anyway).
 *
 * Its own paginated fetch of GET /return-requests (created_at DESC, id DESC) — separate
 * from the exchange/refund feeds, which it never touches. onDecided lets the parent refresh
 * the "{n} new" badge after an approve/reject.
 */
export default function RequestsPanel({ onDecided }: { onDecided: () => void }) {
  const { t, i18n } = useTranslation()
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<ReturnRequestRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = useCallback(async (p: number) => {
    setLoading(true)
    setError(false)
    try {
      const res = await getReturnRequests(p, REQUESTS_PAGE_SIZE)
      setRows(res.items)
      setTotal(res.total)
    } catch {
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load(page) }, [load, page])

  const refresh = useCallback(() => {
    load(page)
    onDecided()
  }, [load, page, onDecided])

  const columns: DataTableColumn<ReturnRequestRow>[] = [
    {
      key: 'reference', header: t('exchangesRefunds.requests.columns.reference'), mono: true,
      render: row => <bdi>{row.reference}</bdi>,
    },
    {
      key: 'customer', header: t('exchangesRefunds.requests.columns.customer'),
      render: row => <bdi className="text-primary">{shortCustomerName(row.customerName)}</bdi>,
    },
    {
      key: 'order', header: t('exchangesRefunds.requests.columns.order'),
      render: row => <bdi className="text-primary">{row.orderNumber}</bdi>,
    },
    {
      key: 'items', header: t('exchangesRefunds.requests.columns.items'),
      render: row => row.itemCount.toLocaleString(i18n.language),
    },
    {
      key: 'reasons', header: t('exchangesRefunds.requests.columns.reasons'),
      render: row => (
        <span className="text-muted">
          {row.reasonCodes.map(code => reasonLabel(t, code)).join(t('exchangesRefunds.requests.reasonSeparator'))}
        </span>
      ),
    },
    {
      key: 'sent', header: t('exchangesRefunds.requests.columns.sent'),
      render: row => <span className="text-muted">{sentLabel(t, i18n.language, row.createdAt)}</span>,
    },
    {
      key: 'status', header: t('exchangesRefunds.requests.columns.status'),
      render: row => (
        <Badge tone={requestStatusTone(row.status)} label={t(`exchangesRefunds.requests.status.${row.status}`)} />
      ),
    },
  ]

  const from = total === 0 ? 0 : page * REQUESTS_PAGE_SIZE + 1
  const to = Math.min(total, (page + 1) * REQUESTS_PAGE_SIZE)
  const lastPage = Math.max(0, Math.ceil(total / REQUESTS_PAGE_SIZE) - 1)

  return (
    <>
      {error ? (
        <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="requests-load-error">
          <p className="text-body font-semibold text-primary">{t('exchangesRefunds.errorTitle')}</p>
          <button className="btn-outline" onClick={() => load(page)}>{t('exchangesRefunds.retry')}</button>
        </div>
      ) : (
        <div className="card overflow-hidden" data-testid="requests-panel">
          <DataTable
            columns={columns}
            rows={rows}
            loading={loading}
            emptyMessage={t('exchangesRefunds.requests.empty')}
            onRowClick={row => setSelectedId(row.id)}
          />
          {!loading && total > REQUESTS_PAGE_SIZE && (
            <div className="flex items-center justify-between gap-3 px-4 py-3 border-t border-line">
              <span className="text-small text-muted">
                {t('exchangesRefunds.requests.pageRange', { from, to, total })}
              </span>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                  {t('exchangesRefunds.requests.prev')}
                </Button>
                <Button variant="outline" size="sm" disabled={page >= lastPage} onClick={() => setPage(p => p + 1)}>
                  {t('exchangesRefunds.requests.next')}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      <ReturnRequestDrawer
        requestId={selectedId}
        onClose={() => setSelectedId(null)}
        onChanged={refresh}
      />
    </>
  )
}
