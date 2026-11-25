import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronUp, Search } from 'lucide-react'
import {
  matchesQuery, sizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize,
} from '../../../bazar/catalog'
import { formatMoney, toNumber, variantName } from '../../../bazar/format'
import { useScanBurst } from '../../../bazar/useScanBurst'
import { cn } from '../../../components/ui'
import { Sheet } from './shared'

/**
 * Bazar POS B7a — "Pick a product" (V2_SellPickPhone): sell without a barcode. Search by name, colour or the price
 * sheet's number; category chips; a product row opens its sizes ("N left", sold out faded); the green button sells
 * one piece of that size (quick) or adds it to the cart — Traced picks which piece. Opened from the Sell screen's
 * button, or by "12" / "#12" in its scan field (that product opens). A HID scan typed into the search goes to the
 * Sell screen's own scan path ({@code onScanBurst}), never into the search.
 */
export function PickSheet({ catalog, startNumber, mode, onPick, onClose, onScanBurst }: {
  catalog: BazarCatalog | null
  /** "12" from the scan field: that product, open. */
  startNumber?: number | null
  /** quick: sell; cart: add; exchange (B7c): the new piece; find (B7c): whose sale is coming back — every size;
   *  send (B7d): what to send by count — `left` is what's at Main, a size without a bazar price can still go;
   *  back (B7d-2): what to bring back by count — `left` is the untagged pieces still out. */
  mode: 'quick' | 'cart' | 'exchange' | 'find' | 'send' | 'back'
  onPick: (product: BazarCatalogProduct, size: BazarCatalogSize) => void
  onClose: () => void
  onScanBurst?: (code: string) => void
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState(startNumber != null ? `#${startNumber}` : '')
  const [category, setCategory] = useState<string | null>(null)
  const startProduct = startNumber != null ? catalog?.products.find(p => p.number === startNumber) : undefined
  const [open, setOpen] = useState<string | null>(startProduct ? key(startProduct) : null)
  const find = mode === 'find'
  const back = mode === 'back'
  const send = mode === 'send' || back
  const leftKey = back ? 'bazar.bringBack.out' : 'bazar.count.atMain'
  const [picked, setPicked] = useState<string | null>(() =>
    startProduct ? firstAvailable(startProduct, find, send)?.variant_id ?? null : null)
  const bodyRef = useRef<HTMLDivElement>(null)
  useScanBurst(bodyRef, onScanBurst)

  const products = useMemo(() => (catalog?.products ?? [])
    .filter(p => matchesQuery(p, query) && (category === null || p.category === category)), [catalog, query, category])

  return (
    <Sheet title={find ? t('bazar.find.title') : back ? t('bazar.bringBack.pickTitle') : send ? t('bazar.count.pickTitle') : t('bazar.pick.title')} onClose={onClose}
           testId="bazar-pick-sheet">
      <div ref={bodyRef} className="flex flex-col gap-3">
        <label className="relative block">
          <Search size={18} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" aria-hidden />
          <input value={query} onChange={e => setQuery(e.target.value)} autoComplete="off" spellCheck={false}
                 aria-label={t('bazar.pick.search')} placeholder={t('bazar.pick.search')} dir="auto"
                 className="input w-full h-12 ps-10 text-body" data-testid="pick-search" />
        </label>
        {catalog && catalog.categories.length > 0 && (
          <div className="flex gap-2 overflow-x-auto -mx-4 px-4 pb-1" role="group" aria-label={t('bazar.pick.categories')}>
            <Chip active={category === null} onClick={() => setCategory(null)}>
              {t('bazar.pick.all', { count: catalog.products.length })}
            </Chip>
            {catalog.categories.map(c => (
              <Chip key={c.name} active={category === c.name} onClick={() => setCategory(category === c.name ? null : c.name)}>
                {c.name}
              </Chip>
            ))}
          </div>
        )}

        {!catalog ? (
          <p className="text-body text-muted py-6 text-center">{t('bazar.pick.loading')}</p>
        ) : products.length === 0 ? (
          <p className="text-body text-muted py-6 text-center" data-testid="pick-empty">
            {query.trim() ? t('bazar.pick.noMatch', { query: query.trim() }) : t('bazar.pick.none')}
          </p>
        ) : (
          <ul className="flex flex-col gap-2" data-testid="pick-products">
            {products.map(p => {
              const k = key(p)
              const isOpen = open === k
              const size = isOpen ? p.sizes.find(s => s.variant_id === picked) : undefined
              return (
                <li key={k} className={cn('rounded-xl border bg-surface', isOpen ? 'border-trace-blue' : 'border-line')}
                    data-testid="pick-product">
                  <button type="button" aria-expanded={isOpen}
                    onClick={() => { setOpen(isOpen ? null : k); setPicked(isOpen ? null : firstAvailable(p, find, send)?.variant_id ?? null) }}
                    className={cn('w-full flex items-center gap-3 p-3 text-start min-h-[64px]', p.left === 0 && !find && 'opacity-60')}>
                    <ProductThumb product={p} size={48} />
                    <span className="flex-1 min-w-0 flex flex-col">
                      <span className="flex items-center gap-1.5 min-w-0">
                        {p.number != null && <NumberTag n={p.number} />}
                        <span className="font-semibold text-primary truncate">{p.title}</span>
                      </span>
                      <span className="text-small text-muted truncate">
                        {[p.colour, find ? null : send ? t(leftKey, { count: p.left })
                          : p.left > 0 ? t('bazar.pick.left', { count: p.left }) : t('bazar.pick.soldOut')]
                          .filter(Boolean).join(' · ')}
                      </span>
                    </span>
                    <span className="font-bold tabular-nums text-primary shrink-0">{priceText(p)}</span>
                    {isOpen ? <ChevronUp size={16} className="text-muted shrink-0" aria-hidden />
                            : <ChevronDown size={16} className="text-muted shrink-0" aria-hidden />}
                  </button>
                  {isOpen && (
                    <div className="px-3 pb-3 flex flex-col gap-3">
                      <div className="grid grid-cols-4 gap-2" role="radiogroup" aria-label={t('bazar.pick.sizes')}>
                        {p.sizes.map(s => {
                          const out = !find && (s.left === 0 || (!send && s.price == null))
                          return (
                            <button key={s.variant_id} type="button" role="radio" aria-checked={picked === s.variant_id}
                              disabled={out} onClick={() => setPicked(s.variant_id)} data-testid="pick-size"
                              className={cn('min-h-[52px] rounded-lg border flex flex-col items-center justify-center px-1',
                                out ? 'border-dashed border-line text-muted opacity-50'
                                  : picked === s.variant_id ? 'bg-trace-blue border-trace-blue text-white'
                                    : 'border-line bg-surface text-primary')}>
                              <span className="font-bold leading-tight">{s.size ?? variantName(s.title) ?? t('bazar.pick.oneSize')}</span>
                              {!find && (
                                <span className="text-[11px] leading-tight">
                                  {send ? t(leftKey, { count: s.left })
                                    : s.price == null ? t('bazar.pick.noPrice') : out ? t('bazar.pick.soldOut') : t('bazar.pick.left', { count: s.left })}
                                </span>
                              )}
                            </button>
                          )
                        })}
                      </div>
                      <button type="button" disabled={!size} data-testid="pick-sell"
                        onClick={() => { if (size) onPick(p, size) }}
                        className="h-12 rounded-xl bg-[#16A34A] hover:bg-[#15803D] disabled:opacity-50 text-white font-bold">
                        {size
                          ? t(find ? 'bazar.find.findSize' : send ? 'bazar.count.choose' : mode === 'cart' ? 'bazar.pick.add'
                              : mode === 'exchange' ? 'bazar.pick.use' : 'bazar.pick.sell',
                              { name: sizeName(p, size), price: formatMoney(size.price ?? 0).replace(/^EGP /, '') })
                          : t('bazar.pick.pickSize')}
                      </button>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-small text-muted">
          {find ? t('bazar.find.footnote') : back ? t('bazar.bringBack.footnote') : send ? t('bazar.count.footnote') : t('bazar.pick.footnote')}
        </p>
      </div>
    </Sheet>
  )
}

function key(p: BazarCatalogProduct) {
  return `${p.product_id}|${p.colour ?? ''}`
}

function firstAvailable(p: BazarCatalogProduct, any = false, send = false) {
  const ok = any ? p.sizes : p.sizes.filter(s => s.left > 0 && (send || s.price != null))
  return ok.length === 1 ? ok[0] : undefined
}

/** "960", or "800–960" when sizes differ; "—" without a price. */
export function priceText(p: BazarCatalogProduct): string {
  if (p.price_min == null) return '—'
  const min = formatMoney(toNumber(p.price_min)).replace(/^EGP /, '')
  const max = formatMoney(toNumber(p.price_max ?? p.price_min)).replace(/^EGP /, '')
  return min === max ? min : `${min}–${max}`
}

export function NumberTag({ n }: { n: number }) {
  return (
    <span className="shrink-0 rounded-md bg-[#0D1117] text-white text-[11px] font-bold px-1.5 py-0.5 tabular-nums" dir="ltr"
          data-testid="pick-number">#{n}</span>
  )
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-pressed={active} onClick={onClick}
      className={cn('shrink-0 h-9 px-3 rounded-md border text-small font-semibold',
        active ? 'bg-[#0D1117] border-[#0D1117] text-white' : 'bg-surface border-line text-primary')}>
      {children}
    </button>
  )
}

/** The product's image, else a neutral tile with its first letter. */
export function ProductThumb({ product, size, className }: { product: BazarCatalogProduct; size?: number; className?: string }) {
  const [broken, setBroken] = useState(false)
  const style = size ? { width: size, height: size } : undefined
  if (product.image_url && !broken) {
    return <img src={product.image_url} alt="" style={style} onError={() => setBroken(true)}
                className={cn('rounded-lg object-cover bg-charcoal shrink-0', className)} />
  }
  return (
    <span style={style} aria-hidden
          className={cn('rounded-lg bg-charcoal text-muted font-bold flex items-center justify-center shrink-0', className)}>
      {product.title.trim().charAt(0).toUpperCase()}
    </span>
  )
}
