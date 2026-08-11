import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search, ChevronUp, Check } from 'lucide-react'
import { getCatalog, CatalogProduct, CatalogVariant } from '../../api'
import { Alert, Button, Input, ProductThumb, Skeleton, Toggle, cn } from '../../components/ui'
import { api, Line } from '../Receiving'

// ── Line-grouping (derived from session.lines — the same array the old
//    type-ahead flow already produced; this is a pure read-side transform,
//    no schema/contract change) ────────────────────────────────────────────

interface VariantGroup { qty: number; lineIds: string[] }

function groupLinesByVariant(lines: Line[]): Map<string, VariantGroup> {
  const map = new Map<string, VariantGroup>()
  for (const l of lines) {
    const g = map.get(l.variant_id) ?? { qty: 0, lineIds: [] }
    g.qty += l.quantity
    g.lineIds.push(l.id)
    map.set(l.variant_id, g)
  }
  return map
}

interface Props {
  sessionId: string
  lines: Line[]
  onRefresh: () => void
  onFinalizeClick: () => void
  finalizing: boolean
}

export default function ProductSelectionGrid({
  sessionId, lines, onRefresh, onFinalizeClick, finalizing,
}: Props) {
  const { t } = useTranslation()
  const [catalog, setCatalog]         = useState<CatalogProduct[] | null>(null)
  const [loadError, setLoadError]     = useState<string | null>(null)
  const [gridError, setGridError]     = useState<string | null>(null)
  const [query, setQuery]             = useState('')
  const [showSelectedOnly, setShowSelectedOnly] = useState(false)
  const [expanded, setExpanded]       = useState<Set<string>>(new Set())

  useEffect(() => {
    getCatalog()
      .then(r => setCatalog(r.products))
      .catch(() => setLoadError(t('common.error')))
  }, [t])

  const groups = useMemo(() => groupLinesByVariant(lines), [lines])
  // Always-fresh ref: commitQty must read the LATEST groups even when invoked
  // from a queued/recursive continuation whose closure predates the most
  // recent onRefresh() — see commitQty's doc comment below.
  const groupsRef = useRef(groups)
  groupsRef.current = groups

  // ── Reconciliation: commits one variant's new ABSOLUTE quantity through the
  //    existing line endpoints only (POST create / PUT update / DELETE remove)
  //    — never a new endpoint, per the approved HARD RULE. ────────────────────
  //
  // Legacy consolidation (a variant with >1 pre-existing line, from the old
  // type-ahead flow which could create duplicates): PUT the new total onto the
  // first line BEFORE deleting the rest, so there is never a moment where this
  // variant's lines sum to less than the intended quantity — no lost update.
  // Worst case on a partial failure (e.g. one DELETE fails) is a stray
  // duplicate line left behind, which self-heals on the next edit to this
  // variant, never a dropped quantity.
  const pendingRef  = useRef<Map<string, number>>(new Map())
  const inFlightRef = useRef<Set<string>>(new Set())

  async function commitQty(variantId: string, newQty: number) {
    const group   = groupsRef.current.get(variantId)
    const current = group?.qty ?? 0
    if (newQty === current) return

    try {
      if (!group || group.lineIds.length === 0) {
        if (newQty > 0) {
          await api(`/receiving/sessions/${sessionId}/lines`, {
            method: 'POST', body: JSON.stringify({ variantId, quantity: newQty }),
          })
        }
      } else if (group.lineIds.length === 1) {
        const lineId = group.lineIds[0]
        if (newQty > 0) {
          await api(`/receiving/sessions/${sessionId}/lines/${lineId}`, {
            method: 'PUT', body: JSON.stringify({ quantity: newQty }),
          })
        } else {
          await api(`/receiving/sessions/${sessionId}/lines/${lineId}`, { method: 'DELETE' })
        }
      } else {
        const [keep, ...extra] = group.lineIds
        if (newQty > 0) {
          await api(`/receiving/sessions/${sessionId}/lines/${keep}`, {
            method: 'PUT', body: JSON.stringify({ quantity: newQty }),
          })
          await Promise.all(extra.map(id =>
            api(`/receiving/sessions/${sessionId}/lines/${id}`, { method: 'DELETE' })))
        } else {
          await Promise.all(group.lineIds.map(id =>
            api(`/receiving/sessions/${sessionId}/lines/${id}`, { method: 'DELETE' })))
        }
      }
      setGridError(null)
      onRefresh()
    } catch (e: unknown) {
      setGridError((e as Error).message)
    }
  }

  // Per-variant queue: coalesces a rapid second edit that arrives while the
  // first is still in flight, instead of firing overlapping requests.
  function scheduleCommit(variantId: string, value: number) {
    pendingRef.current.set(variantId, value)
    if (inFlightRef.current.has(variantId)) return
    void runQueued(variantId)
  }

  async function runQueued(variantId: string) {
    const value = pendingRef.current.get(variantId)
    if (value === undefined) return
    pendingRef.current.delete(variantId)
    inFlightRef.current.add(variantId)
    await commitQty(variantId, value)
    inFlightRef.current.delete(variantId)
    if (pendingRef.current.has(variantId)) void runQueued(variantId)
  }

  function toggleExpanded(productId: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(productId)) next.delete(productId)
      else next.add(productId)
      return next
    })
  }

  const filteredProducts = useMemo(() => {
    if (!catalog) return []
    const q = query.trim().toLowerCase()
    return catalog.filter(p => {
      if (showSelectedOnly) {
        const hasQty = p.variants.some(v => (groups.get(v.id)?.qty ?? 0) > 0)
        if (!hasQty) return false
      }
      if (q.length === 0) return true
      if (p.title.toLowerCase().includes(q)) return true
      return p.variants.some(v => v.sku?.toLowerCase().includes(q))
    })
  }, [catalog, query, showSelectedOnly, groups])

  const totals = useMemo(() => {
    let variants = 0, units = 0
    for (const g of groups.values()) {
      if (g.qty > 0) { variants++; units += g.qty }
    }
    return { variants, units }
  }, [groups])

  const productCountWithQty = useMemo(() => {
    if (!catalog) return 0
    return catalog.filter(p => p.variants.some(v => (groups.get(v.id)?.qty ?? 0) > 0)).length
  }, [catalog, groups])

  if (loadError) return <Alert tone="critical" title={loadError} />

  return (
    <div className="card p-5 space-y-4" data-testid="receiving-grid">
      {gridError && <Alert tone="critical" title={gridError} />}

      <div className="flex gap-2.5 items-center flex-wrap">
        <div className="flex-1 min-w-[200px]">
          <Input
            iconStart={Search}
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t('receiving.grid.searchPlaceholder')}
          />
        </div>
        <label className="flex items-center gap-2 flex-shrink-0">
          <span className="text-small text-muted whitespace-nowrap">
            {t('receiving.grid.showSelectedOnly')}
          </span>
          <Toggle size="sm" checked={showSelectedOnly} onChange={setShowSelectedOnly} />
        </label>
      </div>

      {catalog === null ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
          {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="aspect-square rounded-xl" />)}
        </div>
      ) : filteredProducts.length === 0 ? (
        <p className="text-small text-muted text-center py-8">
          {catalog.length === 0 ? t('receiving.grid.emptyCatalog') : t('receiving.grid.noResults')}
        </p>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 max-h-[420px] overflow-y-auto p-0.5">
          {filteredProducts.map(product => (
            <ProductCard
              key={product.id}
              product={product}
              groups={groups}
              isExpanded={expanded.has(product.id)}
              onToggle={() => toggleExpanded(product.id)}
              onCommitVariant={(variantId, qty) => scheduleCommit(variantId, qty)}
            />
          ))}
        </div>
      )}

      <div className="flex items-center justify-between border-t border-line pt-3.5 flex-wrap gap-3">
        <span className="text-small text-muted">
          {t('receiving.grid.total', {
            products: productCountWithQty, variants: totals.variants, units: totals.units,
          })}
        </span>
        <Button loading={finalizing} disabled={totals.units === 0} onClick={onFinalizeClick}>
          {t('receiving.finalize')}
        </Button>
      </div>
    </div>
  )
}

// ── ProductCard ────────────────────────────────────────────────────────────

function ProductCard({
  product, groups, isExpanded, onToggle, onCommitVariant,
}: {
  product: CatalogProduct
  groups: Map<string, VariantGroup>
  isExpanded: boolean
  onToggle: () => void
  onCommitVariant: (variantId: string, qty: number) => void
}) {
  const { t } = useTranslation()
  let totalUnits = 0, variantsWithQty = 0
  for (const v of product.variants) {
    const q = groups.get(v.id)?.qty ?? 0
    if (q > 0) { totalUnits += q; variantsWithQty++ }
  }
  const hasAnyQty = totalUnits > 0

  if (isExpanded) {
    return (
      <div
        className={cn(
          'col-span-2 sm:col-span-3 rounded-xl overflow-hidden border',
          hasAnyQty ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line bg-panel'
        )}
        data-testid={`product-card-${product.id}`}
      >
        <button
          type="button"
          onClick={onToggle}
          className="w-full flex items-center gap-2.5 px-3 py-2.5 text-start"
        >
          <ProductThumb src={product.imageUrl} alt={product.title} size={36} />
          <span className="text-body font-semibold text-primary flex-1 truncate">{product.title}</span>
          {hasAnyQty && (
            <span className="badge bg-trace-blue/[0.16] text-trace-blue border-0 flex-shrink-0">
              {t('receiving.grid.summaryBadge', { variants: variantsWithQty, units: totalUnits })}
            </span>
          )}
          <ChevronUp size={14} strokeWidth={2} className="text-muted flex-shrink-0" />
        </button>
        <div>
          {product.variants.map(v => (
            <VariantRow
              key={v.id}
              variant={v}
              committedQty={groups.get(v.id)?.qty ?? 0}
              onCommit={qty => onCommitVariant(v.id, qty)}
            />
          ))}
        </div>
      </div>
    )
  }

  return (
    <button
      type="button"
      onClick={onToggle}
      className={cn(
        'rounded-xl overflow-hidden border text-start transition-colors',
        hasAnyQty ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line bg-surface'
      )}
      data-testid={`product-card-${product.id}`}
    >
      <div className="relative">
        <ProductThumb
          src={product.imageUrl}
          alt={product.title}
          fill
          rounded="none"
          placeholderLabel={t('receiving.grid.noPhoto')}
          className="border-0 border-b border-line"
        />
        {hasAnyQty && (
          <span className="absolute top-1.5 end-1.5 bg-trace-blue/90 text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full">
            {t('receiving.grid.cardBadge', { variants: variantsWithQty, units: totalUnits })}
          </span>
        )}
      </div>
      <div className="px-2.5 py-2">
        <div className="text-caption font-semibold text-primary truncate">{product.title}</div>
        <div className="text-[10px] font-mono text-muted">
          {t('receiving.grid.variantMeta', { count: product.variants.length })}
        </div>
      </div>
    </button>
  )
}

// ── VariantRow ────────────────────────────────────────────────────────────
// SAFETY: not a scan input — a plain quantity field, debounced-commit on
// change plus immediate flush on blur/Enter so a value is never silently lost.

function VariantRow({ variant, committedQty, onCommit }: {
  variant: CatalogVariant
  committedQty: number
  onCommit: (qty: number) => void
}) {
  const [localValue, setLocalValue] = useState<string | null>(null)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function parse(raw: string): number {
    const n = Math.floor(Number(raw))
    return Number.isFinite(n) && n > 0 ? n : 0
  }

  function scheduleFromInput(raw: string) {
    setLocalValue(raw)
    if (debounceTimer.current) clearTimeout(debounceTimer.current)
    debounceTimer.current = setTimeout(() => onCommit(parse(raw)), 500)
  }

  function flushNow() {
    if (debounceTimer.current) { clearTimeout(debounceTimer.current); debounceTimer.current = null }
    if (localValue !== null) onCommit(parse(localValue))
    setLocalValue(null)
  }

  const qty = localValue !== null ? parse(localValue) : committedQty
  const hasQty = qty > 0
  const displayValue = localValue ?? (committedQty > 0 ? String(committedQty) : '')

  return (
    <div
      className={cn(
        'flex items-center gap-2.5 py-2.5 px-3 border-t border-line',
        hasQty && 'bg-trace-blue/[0.08]'
      )}
      style={{ paddingInlineStart: 58 }}
    >
      <div
        className={cn(
          'w-[15px] h-[15px] rounded flex-shrink-0 flex items-center justify-center',
          hasQty ? 'bg-trace-blue' : 'border border-line'
        )}
      >
        {hasQty && <Check size={10} strokeWidth={3} className="text-white" />}
      </div>
      <span className={cn('text-body flex-1 truncate', hasQty ? 'font-semibold text-primary' : 'text-primary')}>
        {variant.title}
      </span>
      {variant.sku && (
        <span className="font-mono text-caption text-muted flex-shrink-0">{variant.sku}</span>
      )}
      <input
        type="number"
        min={0}
        inputMode="numeric"
        placeholder="0"
        data-testid={`qty-input-${variant.id}`}
        value={displayValue}
        onChange={e => scheduleFromInput(e.target.value)}
        onBlur={flushNow}
        onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
        className={cn(
          'w-14 flex-shrink-0 text-center rounded-lg border bg-transparent px-2 py-1.5 text-caption font-mono transition-colors',
          hasQty ? 'border-trace-blue bg-elevated text-primary' : 'border-line text-muted'
        )}
      />
    </div>
  )
}

// exported for tests only — not part of the public component surface
export { groupLinesByVariant }
export type { VariantGroup }
