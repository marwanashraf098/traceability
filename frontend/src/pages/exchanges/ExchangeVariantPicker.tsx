import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search, X } from 'lucide-react'
import { getCatalog, CatalogProduct, CatalogVariant } from '../../api'
import { Alert, Button, Input, Modal, ProductStatusBadge, ProductThumb, Skeleton, Toggle, cn } from '../../components/ui'

/**
 * FR-EXCHANGE Phase 2 — single-variant picker. Reuses the SAME data source
 * (getCatalog) and visual building blocks (ProductThumb, Modal, card-grid look)
 * as Receiving's ProductSelectionGrid, but the interaction is select-and-close,
 * not quantity entry — Receiving's grid/modal is shaped for "add N units of M
 * variants to a session," which is the wrong shape for "pick exactly one variant
 * per exchange leg." Not a reimplementation of the catalog API or design system,
 * only of the modal row's click behavior.
 */
const PAGE_SIZE = 48

interface Props {
  onSelect: (variant: CatalogVariant, product: CatalogProduct) => void
  onClose: () => void
}

export default function ExchangeVariantPicker({ onSelect, onClose }: Props) {
  const { t } = useTranslation()
  // Server-side search + status filter + keyset "load more" — the picker never loads the
  // whole catalog. Active products only by default (the replacement a customer can be sent);
  // the toggle widens it to every status, and the badge still marks draft / archived.
  const [catalog, setCatalog]       = useState<CatalogProduct[] | null>(null)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadError, setLoadError]   = useState<string | null>(null)
  const [query, setQuery]           = useState('')
  const [qDebounced, setQDebounced] = useState('')
  const [showAllStatuses, setShowAllStatuses] = useState(false)
  const [openProductId, setOpenProductId] = useState<string | null>(null)

  useEffect(() => {
    const id = setTimeout(() => setQDebounced(query.trim()), 300)
    return () => clearTimeout(id)
  }, [query])

  const status = showAllStatuses ? undefined : ['active']
  const requestRef = useRef(0)
  useEffect(() => {
    const id = ++requestRef.current
    getCatalog({ q: qDebounced || undefined, status: showAllStatuses ? undefined : ['active'], limit: PAGE_SIZE })
      .then(r => {
        if (id !== requestRef.current) return
        setCatalog(r.products)
        setNextCursor(r.nextCursor ?? null)
      })
      .catch(() => { if (id === requestRef.current) setLoadError(t('common.error')) })
  }, [qDebounced, showAllStatuses, t])

  async function loadMore() {
    if (!nextCursor) return
    const id = requestRef.current
    setLoadingMore(true)
    try {
      const r = await getCatalog({ q: qDebounced || undefined, status, cursor: nextCursor, limit: PAGE_SIZE })
      if (id !== requestRef.current) return
      setCatalog(prev => [...(prev ?? []), ...r.products])
      setNextCursor(r.nextCursor ?? null)
    } catch {
      setLoadError(t('common.error'))
    } finally {
      setLoadingMore(false)
    }
  }

  const filteredProducts = catalog ?? []
  const openProduct = openProductId ? (catalog?.find(p => p.id === openProductId) ?? null) : null

  // The per-product variant list (below) is a SECOND `fixed` overlay (the shared
  // Modal component) opened on top of this one. It must be a TOP-LEVEL sibling of
  // this overlay's own `fixed` wrapper, not nested inside it — both wrappers carry
  // the same z-overlay/z-modal classes, and nesting them puts this overlay's inner
  // z-modal content (800) in direct stacking competition with the inner Modal's
  // OWN z-overlay backdrop (700) within the SAME parent stacking context, so this
  // overlay's grid paints over the entire nested Modal, hiding it completely.
  // Confirmed empirically: the inner Modal was present in the DOM (visibility:
  // visible, opacity: 1) but invisible on screen until this was split into two
  // independent top-level `fixed` siblings.
  return (
    <>
    <div
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-overlay p-4"
      onClick={onClose}
      data-testid="exchange-variant-picker"
    >
      <div
        className="bg-surface rounded-2xl border border-line shadow-e4 w-full max-w-3xl animate-fadeIn z-modal p-5 space-y-4"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h3 className="text-h3 text-primary">{t('exchange.picker.title')}</h3>
          <button
            type="button"
            onClick={onClose}
            className="text-muted hover:text-primary transition-colors p-0.5"
            aria-label={t('exchange.picker.close')}
          >
            <X size={18} strokeWidth={2} />
          </button>
        </div>

        <div className="flex gap-2.5 items-center flex-wrap">
          <div className="flex-1 min-w-[200px]">
            <Input
              iconStart={Search}
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={t('receiving.grid.searchPlaceholder')}
            />
          </div>
          <label className="flex items-center gap-2 flex-shrink-0" data-testid="exchange-picker-show-all">
            <span className="text-small text-muted whitespace-nowrap">{t('exchange.picker.showAllStatuses')}</span>
            <Toggle size="sm" checked={showAllStatuses} onChange={setShowAllStatuses} />
          </label>
        </div>

        {loadError ? (
          <Alert tone="critical" title={loadError} />
        ) : catalog === null ? (
          <div className="grid grid-cols-3 sm:grid-cols-4 gap-2.5 max-h-[420px] overflow-y-auto p-0.5">
            {Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-24 rounded-xl" />)}
          </div>
        ) : filteredProducts.length === 0 ? (
          <p className="text-small text-muted text-center py-8">
            {qDebounced === '' && showAllStatuses ? t('receiving.grid.emptyCatalog') : t('receiving.grid.noResults')}
          </p>
        ) : (
          <>
          <div className="grid grid-cols-3 sm:grid-cols-4 gap-2.5 max-h-[420px] overflow-y-auto p-0.5">
            {filteredProducts.map(product => (
              <button
                key={product.id}
                type="button"
                onClick={() => setOpenProductId(product.id)}
                className="rounded-xl overflow-hidden border border-line bg-surface text-start transition-colors hover:border-trace-blue"
                data-testid={`exchange-picker-product-${product.id}`}
              >
                <div className="relative h-16 sm:h-20">
                  <ProductThumb
                    src={product.imageUrl}
                    alt={product.title}
                    fill
                    rounded="none"
                    cdnWidth={240}
                    objectFit="contain"
                    placeholderLabel={t('receiving.grid.noPhoto')}
                    className="border-0 border-b border-line"
                  />
                </div>
                <div className="px-2 py-1.5">
                  <div className="text-caption font-semibold text-primary truncate">{product.title}</div>
                  <div className="text-[10px] font-mono text-muted">
                    {t('receiving.grid.variantMeta', { count: product.variants.length })}
                  </div>
                  <ProductStatusBadge status={product.status} className="mt-0.5" />
                </div>
              </button>
            ))}
          </div>
          {nextCursor && (
            <div className="flex justify-center" data-testid="exchange-picker-load-more">
              <Button variant="secondary" size="sm" loading={loadingMore} onClick={() => void loadMore()}>
                {t('exchange.picker.loadMore')}
              </Button>
            </div>
          )}
          </>
        )}
      </div>
    </div>

    {openProduct && (
      <Modal title={openProduct.title} onClose={() => setOpenProductId(null)}>
        <div className="space-y-0.5 max-h-[50vh] overflow-y-auto" data-testid="exchange-picker-variant-list">
          {openProduct.variants.map(v => (
            <button
              key={v.id}
              type="button"
              onClick={() => onSelect(v, openProduct)}
              className={cn(
                'w-full flex items-center gap-2.5 py-2 px-1.5 rounded-lg text-start transition-colors',
                'hover:bg-elevated hover:border-trace-blue border border-transparent'
              )}
              data-testid={`exchange-picker-variant-${v.id}`}
            >
              <span className="text-body flex-1 truncate text-primary">{v.title}</span>
              {v.sku && <span className="font-mono text-caption text-muted flex-shrink-0">{v.sku}</span>}
            </button>
          ))}
        </div>
      </Modal>
    )}
    </>
  )
}
