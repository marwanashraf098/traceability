import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getCatalog, CatalogProduct } from '../api'

const STATUS_KEYS = [
  'available', 'reserved', 'packed', 'awaiting_pickup',
  'with_courier', 'delivered', 'return_in_transit',
  'return_pending_inspection', 'damaged', 'lost', 'destroyed',
] as const

const STATUS_COLOR: Record<string, string> = {
  available:                  'bg-green-100 text-green-700',
  reserved:                   'bg-yellow-100 text-yellow-700',
  packed:                     'bg-purple-100 text-purple-700',
  awaiting_pickup:            'bg-indigo-100 text-indigo-700',
  with_courier:               'bg-sky-100 text-sky-700',
  delivered:                  'bg-green-200 text-green-800',
  return_in_transit:          'bg-amber-100 text-amber-700',
  return_pending_inspection:  'bg-orange-100 text-orange-700',
  damaged:                    'bg-red-100 text-red-600',
  lost:                       'bg-red-200 text-red-800',
  destroyed:                  'bg-gray-200 text-gray-600',
}

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

  if (loading) return <p className="text-gray-500 text-sm">{t('common.loading')}</p>
  if (error)   return <p className="text-red-600 text-sm">{error}</p>

  return (
    <div>
      <h1 className="text-xl font-semibold text-gray-900 mb-6">{t('catalog.title')}</h1>

      {products.length === 0 && (
        <p className="text-gray-400 text-sm">{t('catalog.empty')}</p>
      )}

      <div className="space-y-4">
        {products.map(product => (
          <div key={product.id} className="bg-white shadow rounded-lg overflow-hidden">
            <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
              <div>
                <span className="font-medium text-gray-900">{product.title}</span>
                <span className="ml-2 text-xs text-gray-400">{product.status}</span>
              </div>
              <span className="text-xs text-gray-500">
                {product.variants.length} variant{product.variants.length !== 1 ? 's' : ''}
              </span>
            </div>

            <div className="divide-y divide-gray-100">
              {product.variants.map(variant => {
                const counts = variant.pieceCounts
                const nonZero = STATUS_KEYS.filter(s => (counts[s] ?? 0) > 0)

                return (
                  <div key={variant.id} className="px-4 py-3">
                    <div className="flex items-start justify-between mb-2">
                      <div>
                        <span className="text-sm font-medium text-gray-800">{variant.title}</span>
                        {variant.sku && (
                          <span className="ml-2 text-xs font-mono text-gray-400">{variant.sku}</span>
                        )}
                      </div>
                      <div className="text-right">
                        <span className="text-sm font-semibold text-gray-900">{counts.total}</span>
                        <span className="text-xs text-gray-400 ml-1">{t('catalog.pieces')}</span>
                        {variant.price != null && (
                          <div className="text-xs text-gray-400">{variant.price.toLocaleString()} EGP</div>
                        )}
                      </div>
                    </div>

                    {/* Piece count badges — only statuses with > 0 pieces */}
                    {nonZero.length > 0 ? (
                      <div className="flex flex-wrap gap-1.5">
                        {nonZero.map(s => (
                          <span
                            key={s}
                            className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_COLOR[s] ?? 'bg-gray-100 text-gray-600'}`}
                          >
                            <span>{counts[s]}</span>
                            <span>{t(`catalog.statuses.${s}`)}</span>
                          </span>
                        ))}
                      </div>
                    ) : (
                      <p className="text-xs text-gray-300">No pieces</p>
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
