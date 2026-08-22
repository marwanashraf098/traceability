import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import {
  getInventoryVariantBreakdown, InventoryVariantBreakdown,
} from '../../api'
import { Badge, EmptyState, ProductThumb, Skeleton, Tooltip, cn } from '../../components/ui'

/**
 * Tab 1's variant drawer — GET /inventory/variants/{id}/breakdown, a separate fetch
 * from the stock list with its own loading/error state (never blocks the table).
 * Same slide-in shell as OrderDrawer.tsx (scrim + translate-x, ltr:/rtl: mirrored).
 */
export default function VariantDrawer({
  variant,
  onClose,
}: {
  variant: { id: string; title: string; sku: string | null; imageUrl: string | null } | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const open = variant != null

  const [data, setData]       = useState<InventoryVariantBreakdown | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError]     = useState(false)

  useEffect(() => {
    if (!variant) { setData(null); return }
    setLoading(true)
    setError(false)
    setData(null)
    getInventoryVariantBreakdown(variant.id)
      .then(setData)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [variant?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <div
        className={cn(
          'fixed inset-0 bg-black/45 z-overlay transition-opacity duration-200',
          open ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        )}
        onClick={onClose}
      />
      <aside
        aria-hidden={!open}
        className={cn(
          'fixed top-0 end-0 h-screen w-[440px] max-w-[92vw] bg-surface border-s border-line z-modal',
          'flex flex-col shadow-e4 transition-transform duration-200',
          open ? 'translate-x-0' : 'ltr:translate-x-full rtl:-translate-x-full'
        )}
      >
        {!variant ? null : (
          <>
            {/* Header */}
            <div className="p-5 pb-4 border-b border-line relative flex items-center gap-3">
              <ProductThumb src={variant.imageUrl} alt={variant.title} size={38} />
              <div className="min-w-0">
                <div className="text-body-lg font-semibold text-primary truncate">{variant.title}</div>
                {variant.sku && <div className="text-small font-mono text-muted">{variant.sku}</div>}
              </div>
              <button
                onClick={onClose}
                aria-label={t('inventory.drawer.close')}
                className="absolute top-4 end-4 w-8 h-8 rounded-lg flex items-center justify-center text-muted hover:text-primary hover:bg-elevated transition-colors"
              >
                <X size={18} strokeWidth={2} />
              </button>
            </div>

            {/* Body — own loading/error, never blocks the table behind it */}
            <div className="flex-1 overflow-y-auto p-5">
              {loading && (
                <div className="space-y-4">
                  <Skeleton className="h-6 w-40 rounded-lg" />
                  <Skeleton className="h-32 rounded-2xl" />
                  <Skeleton className="h-6 w-40 rounded-lg" />
                  <Skeleton className="h-24 rounded-2xl" />
                </div>
              )}
              {!loading && error && (
                <EmptyState icon="⚠" message={t('inventory.drawer.error')} />
              )}
              {!loading && !error && data && (
                <>
                  <section className="mb-7">
                    <div className="flex items-center gap-1.5 mb-2.5">
                      <h3 className="text-caption font-semibold text-muted uppercase tracking-wider">
                        {t('inventory.drawer.matrixTitle')}
                      </h3>
                      <Tooltip content={t('inventory.drawer.committedTenantWide')}>
                        <span className="text-caption text-muted cursor-help">
                          {t('inventory.drawer.committedTotal', { count: data.committed })}
                        </span>
                      </Tooltip>
                    </div>
                    <div className="overflow-x-auto rounded-xl border border-line">
                      <table className="w-full text-body">
                        <thead>
                          <tr className="bg-elevated">
                            <th className="text-start px-3 py-2 text-caption font-medium text-muted">
                              {t('inventory.drawer.colLocation')}
                            </th>
                            <th className="text-end px-3 py-2 text-caption font-medium text-muted">
                              {t('inventory.drawer.colAvailable')}
                            </th>
                            <th className="text-end px-3 py-2 text-caption font-medium text-muted">
                              {t('inventory.drawer.colOnHand')}
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.locations.map(loc => (
                            <tr key={loc.locationId} className="border-t border-line">
                              <td className="px-3 py-2 text-primary">{loc.locationName}</td>
                              <td className="px-3 py-2 text-end text-muted">{loc.available.toLocaleString()}</td>
                              <td className="px-3 py-2 text-end text-muted">{loc.onHand.toLocaleString()}</td>
                            </tr>
                          ))}
                          <tr className="border-t border-line font-semibold">
                            <td className="px-3 py-2 text-primary">{t('inventory.drawer.total')}</td>
                            <td className="px-3 py-2 text-end text-primary">{data.available.toLocaleString()}</td>
                            <td className="px-3 py-2 text-end text-primary">{data.onHand.toLocaleString()}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </section>

                  <section>
                    <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-2.5">
                      {t('inventory.drawer.recentMovements')}
                    </h3>
                    {data.recentMovements.length === 0 ? (
                      <p className="text-small text-muted">{t('inventory.drawer.noMovements')}</p>
                    ) : (
                      <div className="divide-y divide-line">
                        {data.recentMovements.map(m => (
                          <div key={m.id} className="flex items-center gap-2.5 py-2.5 text-small">
                            <Badge
                              tone={m.status === 'failed' ? 'critical' : m.status === 'applied' ? 'success' : 'warning'}
                              label={t(`shopifyInventory.trigger.${m.triggerType}`, { defaultValue: m.triggerType })}
                            />
                            <span className="text-muted truncate flex-1 min-w-0">
                              {m.locationName} · {new Date(m.createdAt).toLocaleDateString()}
                            </span>
                            {m.delta != null && (
                              <span className={cn('font-semibold flex-none', m.delta < 0 ? 'text-critical' : 'text-success')}>
                                {m.delta > 0 ? `+${m.delta}` : m.delta}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </section>
                </>
              )}
            </div>
          </>
        )}
      </aside>
    </>
  )
}
