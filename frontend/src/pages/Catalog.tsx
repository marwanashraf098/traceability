import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getCatalog, CatalogProduct } from '../api'
import {
  Alert, Badge, DataTable, type DataTableColumn,
  EmptyState, TableSkeleton,
} from '../components/ui'

// Piece statuses present in the catalog — used to build the stock breakdown column
const STATUS_KEYS = [
  'available', 'reserved', 'packed', 'awaiting_pickup',
  'with_courier', 'delivered', 'return_in_transit',
  'return_pending_inspection', 'damaged', 'lost', 'destroyed',
] as const

export default function Catalog() {
  const { t } = useTranslation()
  const [products, setProducts] = useState<CatalogProduct[]>([])
  const [loading,  setLoading]  = useState(true)
  const [error,    setError]    = useState('')

  useEffect(() => {
    getCatalog()
      .then(r => setProducts(r.products))
      .catch(() => setError(t('common.error')))
      .finally(() => setLoading(false))
  }, [t])

  // Flatten products → one row per variant, product info denormalized
  const rows = products.flatMap(p =>
    p.variants.map(v => ({
      id:            v.id,
      productTitle:  p.title,
      productStatus: p.status,
      variantTitle:  v.title,
      sku:           v.sku,
      price:         v.price,
      pieceCounts:   v.pieceCounts,
    }))
  )
  type Row = typeof rows[number]

  const columns: DataTableColumn<Row>[] = [
    {
      key: 'product',
      header: t('catalog.columns.product', { defaultValue: 'Product' }),
      render: row => (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-body font-medium text-primary">{row.productTitle}</span>
          {/* Product status (ACTIVE/DRAFT/ARCHIVED) not in STATUS_TONE → neutral tone */}
          <Badge tone="neutral" label={row.productStatus} />
        </div>
      ),
    },
    {
      key: 'variant',
      header: t('catalog.columns.variant', { defaultValue: 'Variant / SKU' }),
      render: row => (
        <div>
          <span className="text-body text-primary">{row.variantTitle}</span>
          {/* SKU — font-mono per spec */}
          {row.sku && (
            <div className="font-mono text-small text-muted mt-0.5">{row.sku}</div>
          )}
          {row.price != null && (
            <div className="text-small text-muted mt-0.5">{row.price.toLocaleString()} EGP</div>
          )}
        </div>
      ),
    },
    {
      key: 'total',
      header: t('catalog.columns.total', { defaultValue: 'Total' }),
      align: 'end',
      render: row => (
        <>
          <span className="text-body font-semibold text-primary">{row.pieceCounts.total}</span>
          <span className="text-small text-muted ms-1">{t('catalog.pieces')}</span>
        </>
      ),
    },
    {
      key: 'stock',
      header: t('catalog.columns.stock', { defaultValue: 'Stock breakdown' }),
      render: row => {
        const counts  = row.pieceCounts
        const nonZero = STATUS_KEYS.filter(s => (counts[s] ?? 0) > 0)
        if (nonZero.length === 0) return <span className="text-small text-muted/50">—</span>
        return (
          <div className="flex flex-wrap gap-1.5">
            {nonZero.map(s => (
              <Badge key={s} status={s} label={`${counts[s]} ${t(`catalog.statuses.${s}`)}`} />
            ))}
          </div>
        )
      },
    },
  ]

  return (
    <div className="space-y-4">
      <h1 className="text-h1 text-primary">{t('catalog.title')}</h1>

      {error && (
        <div data-testid="catalog-error">
          <Alert tone="critical" title={error} />
        </div>
      )}

      {/* Loading/empty/data handled here — keeps EmptyState action prop accessible */}
      <div className="card overflow-hidden">
        {loading ? (
          <TableSkeleton rows={5} cols={columns.length} />
        ) : rows.length === 0 ? (
          <EmptyState message={t('catalog.empty')} icon="📦" />
        ) : (
          <DataTable columns={columns} rows={rows} />
        )}
      </div>
    </div>
  )
}
