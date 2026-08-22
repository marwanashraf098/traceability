import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getInventoryMovements, InventoryMovementRow } from '../../api'
import { Badge, EmptyState, Spinner, cn } from '../../components/ui'

const PAGE_SIZE = 50

export default function LedgerTab() {
  const { t } = useTranslation()

  const [rows, setRows] = useState<InventoryMovementRow[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState(false)

  useEffect(() => {
    getInventoryMovements({ size: PAGE_SIZE })
      .then(page => { setRows(page.items); setCursor(page.nextCursor) })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  async function loadMore() {
    if (!cursor) return
    setLoadingMore(true)
    try {
      const page = await getInventoryMovements({ cursor, size: PAGE_SIZE })
      setRows(prev => [...prev, ...page.items])
      setCursor(page.nextCursor)
    } catch {
      // Leave what's already loaded — a failed "load more" isn't fatal.
    } finally {
      setLoadingMore(false)
    }
  }

  if (loading) {
    return <div className="py-16 flex items-center justify-center"><Spinner /></div>
  }
  if (error) {
    return <EmptyState icon="⚠" message={t('common.error')} />
  }
  if (rows.length === 0) {
    return <div className="card overflow-hidden"><EmptyState icon="📒" message={t('inventory.ledger.empty')} /></div>
  }

  return (
    <div className="card overflow-hidden">
      <div className="divide-y divide-line">
        {rows.map(row => (
          <div key={`${row.source}-${row.id}`} className="flex items-center gap-3 px-4 py-3">
            <span className="text-caption text-muted w-[110px] flex-none">
              {new Date(row.createdAt).toLocaleString()}
            </span>

            {row.source === 'adjustment' ? (
              <Badge
                tone={row.triggerType === 'damage_move' ? 'warning' : 'neutral'}
                label={t(`shopifyInventory.trigger.${row.triggerType}`, { defaultValue: row.triggerType ?? '' })}
              />
            ) : (
              <Badge tone="neutral" label={t('inventory.ledger.stockTakeSource')} />
            )}

            <span className="text-body min-w-0 flex-1 truncate">
              {row.source === 'adjustment' ? (
                <>
                  {row.productTitle} — {row.variantTitle}
                  {row.sku && <span className="text-muted font-mono text-small"> · {row.sku}</span>}
                  {row.locationName && <span className="text-muted"> · {row.locationName}</span>}
                </>
              ) : (
                // Session-grain — no single variant to name (per-variant payload
                // decomposition is a documented backend fast-follow).
                <span className="text-muted">
                  {t('inventory.ledger.stockTakeSession')}
                  {row.locationName && ` · ${row.locationName}`}
                </span>
              )}
            </span>

            <span className={cn(
              'font-semibold text-body-lg w-[56px] text-end flex-none',
              (row.delta ?? 0) < 0 ? 'text-critical' : (row.delta ?? 0) > 0 ? 'text-success' : 'text-muted'
            )}>
              {row.delta == null ? '—' : row.delta > 0 ? `+${row.delta}` : row.delta}
            </span>

            <span className={cn(
              'text-caption w-[90px] text-end flex-none',
              row.syncStatus === 'synced' ? 'text-success' : row.syncStatus === 'pending' ? 'text-warning' : 'text-critical'
            )}>
              {t(`inventory.ledger.sync.${row.syncStatus}`)}
            </span>
          </div>
        ))}
      </div>

      {cursor && (
        <div className="p-3 border-t border-line flex justify-center">
          <button
            type="button"
            onClick={loadMore}
            disabled={loadingMore}
            className="btn-outline text-small px-4 py-1.5"
          >
            {loadingMore ? <Spinner size={14} /> : t('inventory.ledger.loadMore')}
          </button>
        </div>
      )}
    </div>
  )
}
