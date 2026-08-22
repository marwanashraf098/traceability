import { Fragment, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { ChevronRight, Info, Search, AlertTriangle } from 'lucide-react'
import {
  getInventoryStock, listLocations, InventoryStockProduct, InventoryStockVariant,
  LocationRow, ShopifySyncStatus,
} from '../../api'
import { Badge, EmptyState, ProductThumb, Select, Spinner, Tooltip, cn } from '../../components/ui'
import VariantDrawer from './VariantDrawer'

const SYNC_TONE: Record<ShopifySyncStatus, 'success' | 'warning' | 'critical' | 'neutral'> = {
  synced:  'success',
  pending: 'warning',
  failed:  'critical',
  none:    'neutral',
}

// Product-level rollup of its variants' individual sync statuses — the mockup shows
// a "drift" concept the backend has no data for (no stored Shopify-reported level to
// diff against), so this is the honest replacement: worst-of-variants, same priority
// InventoryStockService itself uses (failed > pending > synced), plus 'none'.
function rollupSync(variants: InventoryStockVariant[]): ShopifySyncStatus {
  if (variants.some(v => v.shopifySync === 'failed'))  return 'failed'
  if (variants.some(v => v.shopifySync === 'pending')) return 'pending'
  if (variants.some(v => v.shopifySync === 'synced'))  return 'synced'
  return 'none'
}

function SyncChip({ status }: { status: ShopifySyncStatus }) {
  const { t } = useTranslation()
  return <Badge tone={SYNC_TONE[status]} label={t(`inventory.stock.sync.${status}`)} />
}

/** Absolute rotation angle — pointing "into" the row when collapsed (end-ward, so it
 *  mirrors naturally for RTL without composing two separate rotate utilities), pointing
 *  down when expanded regardless of direction. */
function chevronDeg(open: boolean, isRtl: boolean): number {
  if (open) return 90
  return isRtl ? 180 : 0
}

const PAGE_SIZE = 20

export default function StockTab() {
  const { t, i18n } = useTranslation()
  const isRtl = i18n.dir() === 'rtl'

  // ?lowStockOnly=true — the deep-link Overview's "needs attention" low-stock card
  // uses (see Overview.tsx). Read once at mount; not kept in sync with the URL after.
  const [searchParams] = useSearchParams()

  const [q, setQ] = useState('')
  const [qDebounced, setQDebounced] = useState('')
  const [locationId, setLocationId] = useState<string>('')
  const [lowStockOnly, setLowStockOnly] = useState(() => searchParams.get('lowStockOnly') === 'true')
  const [locations, setLocations] = useState<LocationRow[]>([])

  const [items, setItems] = useState<InventoryStockProduct[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState(false)

  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [drawerVariant, setDrawerVariant] = useState<
    { id: string; title: string; sku: string | null; imageUrl: string | null } | null
  >(null)

  useEffect(() => { listLocations().then(setLocations).catch(() => {}) }, [])

  // Debounce search input — same 300ms convention as other search fields in this app.
  useEffect(() => {
    const id = setTimeout(() => setQDebounced(q.trim()), 300)
    return () => clearTimeout(id)
  }, [q])

  const filterActive = qDebounced.length > 0 || lowStockOnly

  const load = useRef(async (_reset: boolean, _cursor?: string) => {})
  load.current = async (reset: boolean, cursor?: string) => {
    if (reset) { setLoading(true); setError(false) } else { setLoadingMore(true) }
    try {
      const page = await getInventoryStock({
        q: qDebounced || undefined,
        locationId: locationId || undefined,
        lowStockOnly,
        cursor,
        size: PAGE_SIZE,
      })
      setItems(prev => reset ? page.items : [...prev, ...page.items])
      setNextCursor(page.nextCursor)
      // Search/low-stock results are already server-filtered to matches — auto-expand
      // all of them; otherwise leave products collapsed by default.
      if (reset && filterActive) {
        setExpanded(new Set(page.items.map(p => p.id)))
      } else if (reset) {
        setExpanded(new Set())
      }
    } catch {
      if (reset) setError(true)
    } finally {
      setLoading(false)
      setLoadingMore(false)
    }
  }

  useEffect(() => { load.current(true) }, [qDebounced, locationId, lowStockOnly])

  function toggleProduct(id: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  const locationOptions = [
    { value: '', label: t('inventory.stock.allLocations') },
    ...locations.map(l => ({ value: l.id, label: l.name })),
  ]

  return (
    <div>
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-2.5 mb-4">
        <div className="relative flex-1 min-w-[200px]">
          <Search size={16} strokeWidth={2} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
          <input
            value={q}
            onChange={e => setQ(e.target.value)}
            placeholder={t('inventory.stock.searchPlaceholder')}
            className="input ps-9"
          />
        </div>
        <Select
          value={locationId}
          onChange={setLocationId}
          options={locationOptions}
          className="w-52"
        />
        <button
          type="button"
          onClick={() => setLowStockOnly(v => !v)}
          className={cn(
            'inline-flex items-center gap-2 h-[37px] px-3.5 rounded-xl border text-body transition-colors',
            lowStockOnly
              ? 'bg-brand/[0.14] border-trace-blue text-trace-blue'
              : 'bg-elevated border-line text-muted hover:text-primary'
          )}
        >
          <AlertTriangle size={15} strokeWidth={2} />
          {t('inventory.stock.lowStock')}
        </button>
      </div>

      {loading ? (
        <div className="card overflow-hidden py-16 flex items-center justify-center">
          <Spinner />
        </div>
      ) : error ? (
        <div className="card p-6">
          <EmptyState icon="⚠" message={t('common.error')} />
        </div>
      ) : items.length === 0 ? (
        <div className="card overflow-hidden">
          <EmptyState icon="📦" message={t('inventory.stock.empty')} />
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-line">
                  <th className="tbl-header text-start">{t('inventory.stock.col.product')}</th>
                  <th className="tbl-header text-end">{t('inventory.stock.col.onHand')}</th>
                  <th className="tbl-header text-end">
                    <span className="inline-flex items-center gap-1 justify-end">
                      {t('inventory.stock.col.committed')}
                      <Tooltip content={t('inventory.stock.committedTenantWideTooltip')}>
                        <Info size={13} strokeWidth={2} className="text-muted cursor-help" />
                      </Tooltip>
                    </span>
                  </th>
                  <th className="tbl-header text-end">{t('inventory.stock.col.available')}</th>
                  <th className="tbl-header text-center">{t('inventory.stock.col.shopify')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map(product => {
                  const isOpen = expanded.has(product.id)
                  return (
                    <Fragment key={product.id}>
                      <tr
                        className="tbl-row cursor-pointer hover:bg-white/[0.03]"
                        onClick={() => toggleProduct(product.id)}
                      >
                        <td className="tbl-cell">
                          <div className="flex items-center gap-2.5">
                            <ChevronRight
                              size={16}
                              strokeWidth={2}
                              className="text-muted transition-transform flex-none"
                              style={{ transform: `rotate(${chevronDeg(isOpen, isRtl)}deg)` }}
                            />
                            <ProductThumb src={product.imageUrl} alt={product.title} size={34} />
                            <div className="min-w-0">
                              <div className="text-primary font-medium truncate">{product.title}</div>
                              <div className="text-caption text-muted">
                                {t('inventory.stock.variantCount', { count: product.variants.length })}
                              </div>
                            </div>
                          </div>
                        </td>
                        <td className="tbl-cell text-end font-medium text-primary">{product.onHand.toLocaleString()}</td>
                        <td className="tbl-cell text-end text-primary">
                          {product.committed == null ? <span className="text-muted">—</span> : product.committed.toLocaleString()}
                        </td>
                        <td className="tbl-cell text-end font-medium text-primary">{product.available.toLocaleString()}</td>
                        <td className="tbl-cell text-center">
                          <SyncChip status={rollupSync(product.variants)} />
                        </td>
                      </tr>
                      {isOpen && product.variants.map(variant => (
                        <tr
                          key={variant.id}
                          className="bg-charcoal/40 cursor-pointer hover:bg-white/[0.03] border-b border-line"
                          onClick={() => setDrawerVariant({
                            id: variant.id, title: `${product.title} — ${variant.title}`,
                            sku: variant.sku, imageUrl: product.imageUrl,
                          })}
                        >
                          <td className="tbl-cell ps-[52px] text-muted">
                            {variant.title}
                            {variant.sku && <span className="text-caption font-mono ms-1.5">· {variant.sku}</span>}
                          </td>
                          <td className="tbl-cell text-end text-muted">{variant.onHand.toLocaleString()}</td>
                          <td className="tbl-cell text-end text-muted">
                            {variant.committed == null ? '—' : variant.committed.toLocaleString()}
                          </td>
                          <td className={cn('tbl-cell text-end', variant.available < 0 ? 'text-critical font-semibold' : 'text-muted')}>
                            {variant.available.toLocaleString()}
                          </td>
                          <td className="tbl-cell text-center">
                            <SyncChip status={variant.shopifySync} />
                          </td>
                        </tr>
                      ))}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>

          {nextCursor && (
            <div className="p-3 border-t border-line flex justify-center">
              <button
                type="button"
                onClick={() => load.current(false, nextCursor)}
                disabled={loadingMore}
                className="btn-outline text-small px-4 py-1.5"
              >
                {loadingMore ? <Spinner size={14} /> : t('inventory.stock.loadMore')}
              </button>
            </div>
          )}

          <div className="px-4 py-2.5 border-t border-line bg-elevated text-caption text-muted">
            {t('inventory.stock.footerHint')}
          </div>
        </div>
      )}

      <VariantDrawer variant={drawerVariant} onClose={() => setDrawerVariant(null)} />
    </div>
  )
}
