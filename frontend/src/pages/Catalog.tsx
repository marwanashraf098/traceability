import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getCatalog, CatalogProduct } from '../api'
import { Badge, EmptyState, Spinner } from '../components/ui'

const STATUS_KEYS = [
  'available', 'reserved', 'packed', 'awaiting_pickup',
  'with_courier', 'delivered', 'return_in_transit',
  'return_pending_inspection', 'damaged', 'lost', 'destroyed',
] as const

export default function Catalog() {
  const { t } = useTranslation()
  const [products, setProducts] = useState<CatalogProduct[]>([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState('')

  useEffect(() => {
    getCatalog()
      .then(r => setProducts(r.products))
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [t])

  if (loading) return <div className="flex justify-center pt-16"><Spinner size={28} /></div>
  if (error)   return <p className="text-small text-danger">{error}</p>

  return (
    <div className="space-y-4">
      <h1 className="text-h1 text-primary">{t('catalog.title')}</h1>

      {products.length === 0 && (
        <EmptyState message={t('catalog.empty')} icon="📦" />
      )}

      <div className="space-y-3">
        {products.map(product => (
          <div key={product.id} className="card overflow-hidden">
            {/* Product header */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-line bg-elevated">
              <div className="flex items-center gap-3">
                <span className="text-body font-semibold text-primary">{product.title}</span>
                <span className="badge border bg-muted/10 text-muted border-muted/20">
                  {product.status}
                </span>
              </div>
              <span className="text-small text-muted">
                {product.variants.length} {product.variants.length !== 1 ? 'variants' : 'variant'}
              </span>
            </div>

            {/* Variants */}
            <div className="divide-y divide-line">
              {product.variants.map(variant => {
                const counts = variant.pieceCounts
                const nonZero = STATUS_KEYS.filter(s => (counts[s] ?? 0) > 0)

                return (
                  <div key={variant.id} className="px-4 py-3">
                    <div className="flex items-start justify-between mb-2.5">
                      <div>
                        <span className="text-body font-medium text-primary">{variant.title}</span>
                        {variant.sku && (
                          <span className="ms-2 font-mono text-small text-muted">{variant.sku}</span>
                        )}
                      </div>
                      <div className="text-end">
                        <span className="text-body font-semibold text-primary">{counts.total}</span>
                        <span className="text-small text-muted ms-1">{t('catalog.pieces')}</span>
                        {variant.price != null && (
                          <div className="text-small text-muted">{variant.price.toLocaleString()} EGP</div>
                        )}
                      </div>
                    </div>

                    {nonZero.length > 0 ? (
                      <div className="flex flex-wrap gap-1.5">
                        {nonZero.map(s => (
                          <Badge key={s} status={s} label={`${counts[s]} ${t(`catalog.statuses.${s}`)}`} />
                        ))}
                      </div>
                    ) : (
                      <p className="text-small text-muted/50">—</p>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
