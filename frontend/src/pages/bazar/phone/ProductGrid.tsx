import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search } from 'lucide-react'
import {
  matchesQuery, sizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize,
} from '../../../bazar/catalog'
import { formatMoney } from '../../../bazar/format'
import { cn } from '../../../components/ui'
import { NumberTag, ProductThumb } from './PickSheet'

/**
 * Bazar POS B7a — the computer's product grid (V2_SellQuick / V2_Sell): one tile per size with its number, colour,
 * size, bazar price and "N left" ("Sold out" faded). A tap sells one piece of it (quick) or adds it to the cart —
 * Traced picks which piece. Search by name, colour or #number; category chips.
 */
export function ProductGrid({ catalog, onPick }: {
  catalog: BazarCatalog | null
  onPick: (product: BazarCatalogProduct, size: BazarCatalogSize) => void
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<string | null>(null)
  const tiles = useMemo(() => (catalog?.products ?? [])
    .filter(p => matchesQuery(p, query) && (category === null || p.category === category))
    .flatMap(p => p.sizes.map(s => ({ p, s }))), [catalog, query, category])

  if (!catalog) return null
  return (
    <section className="flex flex-col gap-3" data-testid="bazar-product-grid" aria-label={t('bazar.pick.gridTitle')}>
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative flex-1 min-w-[220px]">
          <Search size={16} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" aria-hidden />
          <input value={query} onChange={e => setQuery(e.target.value)} autoComplete="off" spellCheck={false} dir="auto"
                 aria-label={t('bazar.pick.gridSearch')} placeholder={t('bazar.pick.gridSearch')}
                 className="input w-full h-10 ps-9" data-testid="grid-search" />
        </label>
        <button type="button" aria-pressed={category === null} onClick={() => setCategory(null)}
          className={cn('h-10 px-3 rounded-md border text-small font-semibold',
            category === null ? 'bg-[#0D1117] border-[#0D1117] text-white' : 'bg-surface border-line text-primary')}>
          {t('bazar.pick.allShort')}
        </button>
        {catalog.categories.map(c => (
          <button key={c.name} type="button" aria-pressed={category === c.name}
            onClick={() => setCategory(category === c.name ? null : c.name)}
            className={cn('h-10 px-3 rounded-md border text-small font-semibold',
              category === c.name ? 'bg-[#0D1117] border-[#0D1117] text-white' : 'bg-surface border-line text-primary')}>
            {c.name}
          </button>
        ))}
      </div>
      {tiles.length === 0 ? (
        <p className="text-body text-muted py-6 text-center">
          {query.trim() ? t('bazar.pick.noMatch', { query: query.trim() }) : t('bazar.pick.none')}
        </p>
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-3">
          {tiles.map(({ p, s }) => {
            const out = s.left === 0 || s.price == null
            return (
              <li key={s.variant_id}>
                <button type="button" disabled={out} onClick={() => onPick(p, s)} data-testid="grid-tile"
                  aria-label={t('bazar.pick.tileLabel', { name: `${p.title} · ${sizeName(p, s)}` })}
                  className={cn('w-full rounded-xl border border-line bg-surface overflow-hidden text-start flex flex-col',
                    out ? 'opacity-50 cursor-not-allowed' : 'hover:border-trace-blue')}>
                  <span className="relative block">
                    <ProductThumb product={p} className="w-full h-[74px] rounded-none text-h3" />
                    <span className="absolute top-2 end-2 rounded-md bg-white/90 text-[#0D1117] text-[11px] font-bold px-1.5 py-0.5">
                      {s.price == null ? t('bazar.pick.noPrice') : out ? t('bazar.pick.soldOut') : t('bazar.pick.left', { count: s.left })}
                    </span>
                    {p.number != null && <span className="absolute top-2 start-2"><NumberTag n={p.number} /></span>}
                  </span>
                  <span className="p-2.5 flex flex-col gap-0.5">
                    <span className="font-semibold text-primary truncate">{p.title}</span>
                    <span className="text-small text-muted truncate">{[p.colour, s.size].filter(Boolean).join(' · ') || '—'}</span>
                    <span className="font-bold tabular-nums text-primary mt-1">
                      {s.price == null ? '—' : formatMoney(s.price).replace(/^EGP /, '')}
                    </span>
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
      <p className="text-small text-muted">{t('bazar.pick.gridFootnote')}</p>
    </section>
  )
}
