import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search } from 'lucide-react'
import { getCatalog, CatalogProduct, CatalogVariant } from '../../api'
import {
  Alert, Button, Checkbox, Input, Modal, ProductThumb, Skeleton, Toggle, cn,
} from '../../components/ui'
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
  const [modalProductId, setModalProductId]     = useState<string | null>(null)

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
  //    — never a new endpoint, per the approved HARD RULE. Unchanged from the
  //    prior grid-in-place build — the entry UI changed, this contract didn't.
  //
  // Legacy consolidation (a variant with >1 pre-existing line, from the old
  // type-ahead flow which could create duplicates): PUT the new total onto the
  // first line BEFORE deleting the rest, so there is never a moment where this
  // variant's lines sum to less than the intended quantity — no lost update.
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

  function removeProduct(product: CatalogProduct) {
    for (const v of product.variants) {
      const g = groupsRef.current.get(v.id)
      if (g && g.qty > 0) scheduleCommit(v.id, 0)
    }
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

  const selectedProducts = useMemo(() => {
    if (!catalog) return []
    return catalog
      .map(product => {
        const rows = product.variants
          .map(variant => ({ variant, qty: groups.get(variant.id)?.qty ?? 0 }))
          .filter(r => r.qty > 0)
        if (rows.length === 0) return null
        return { product, rows, subtotal: rows.reduce((s, r) => s + r.qty, 0) }
      })
      .filter((x): x is { product: CatalogProduct; rows: { variant: CatalogVariant; qty: number }[]; subtotal: number } => x !== null)
  }, [catalog, groups])

  const modalProduct = modalProductId ? (catalog?.find(p => p.id === modalProductId) ?? null) : null

  if (loadError) return <Alert tone="critical" title={loadError} />

  return (
    <div className="space-y-4" data-testid="receiving-grid">
      {gridError && <Alert tone="critical" title={gridError} />}

      {/* Zone 1 — browse/add grid */}
      <div className="card p-5 space-y-4">
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
          <div className="grid grid-cols-3 sm:grid-cols-4 lg:grid-cols-5 gap-2.5 max-h-[380px] overflow-y-auto p-0.5">
            {Array.from({ length: 10 }).map((_, i) => <Skeleton key={i} className="h-24 rounded-xl" />)}
          </div>
        ) : filteredProducts.length === 0 ? (
          <p className="text-small text-muted text-center py-8">
            {catalog.length === 0 ? t('receiving.grid.emptyCatalog') : t('receiving.grid.noResults')}
          </p>
        ) : (
          // Height-capped to ~3 card rows and internally scrollable — search/toggle
          // stay above (outside this div) since they filter the whole grid; the
          // selected-summary and Finalize below stay visible without page scroll.
          // A short (sub-cap) catalog naturally shrinks to fit — no forced min-height.
          <div className="grid grid-cols-3 sm:grid-cols-4 lg:grid-cols-5 gap-2.5 max-h-[380px] overflow-y-auto p-0.5">
            {filteredProducts.map(product => (
              <CompactProductCard
                key={product.id}
                product={product}
                groups={groups}
                onOpen={() => setModalProductId(product.id)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Zone 3 — persistent selected-products summary */}
      <div className="card p-5 space-y-3">
        <h2 className="text-caption text-muted uppercase tracking-widest">
          {t('receiving.grid.summaryTitle')}
        </h2>
        {selectedProducts.length === 0 ? (
          <p className="text-small text-muted py-4">{t('receiving.grid.summaryEmpty')}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-line">
                  <th className="tbl-header">{t('receiving.grid.summaryColProduct')}</th>
                  <th className="tbl-header">{t('receiving.grid.summaryColVariants')}</th>
                  <th className="tbl-header text-end">{t('receiving.grid.summaryColUnits')}</th>
                  <th className="tbl-header w-24" />
                </tr>
              </thead>
              <tbody>
                {selectedProducts.map(({ product, rows, subtotal }) => (
                  <tr key={product.id} className="tbl-row" data-testid={`summary-row-${product.id}`}>
                    <td className="tbl-cell">
                      <div className="flex items-center gap-2.5">
                        <ProductThumb src={product.imageUrl} alt={product.title} size={32} cdnWidth={64} />
                        <span className="font-medium text-primary">{product.title}</span>
                      </div>
                    </td>
                    <td className="tbl-cell text-small text-muted">
                      {rows.map(r => `${r.variant.title}: ${r.qty}`).join(' · ')}
                    </td>
                    <td className="tbl-cell text-end font-mono font-semibold text-primary">{subtotal}</td>
                    <td className="tbl-cell text-end">
                      <div className="flex gap-3 justify-end">
                        <button
                          type="button"
                          className="text-caption text-trace-blue hover:underline"
                          onClick={() => setModalProductId(product.id)}
                        >
                          {t('receiving.grid.edit')}
                        </button>
                        <button
                          type="button"
                          className="text-caption text-danger hover:text-danger/70 transition-colors"
                          onClick={() => removeProduct(product)}
                        >
                          {t('receiving.grid.remove')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Running total + Finalize */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <span className="text-small text-muted">
          {t('receiving.grid.total', {
            products: productCountWithQty, variants: totals.variants, units: totals.units,
          })}
        </span>
        <Button loading={finalizing} disabled={totals.units === 0} onClick={onFinalizeClick}>
          {t('receiving.finalize')}
        </Button>
      </div>

      {/* Zone 2 — variant entry modal (opens OVER the grid; the grid never expands/reflows) */}
      {modalProduct && (
        <VariantModal
          product={modalProduct}
          groups={groups}
          onCommitVariant={(variantId, qty) => scheduleCommit(variantId, qty)}
          onClose={() => setModalProductId(null)}
        />
      )}
    </div>
  )
}

// ── CompactProductCard ───────────────────────────────────────────────────────

function CompactProductCard({ product, groups, onOpen }: {
  product: CatalogProduct
  groups: Map<string, VariantGroup>
  onOpen: () => void
}) {
  const { t } = useTranslation()
  let totalUnits = 0, variantsWithQty = 0
  for (const v of product.variants) {
    const q = groups.get(v.id)?.qty ?? 0
    if (q > 0) { totalUnits += q; variantsWithQty++ }
  }
  const hasAnyQty = totalUnits > 0

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        'rounded-xl overflow-hidden border text-start transition-colors',
        hasAnyQty ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line bg-surface'
      )}
      data-testid={`product-card-${product.id}`}
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
        {hasAnyQty && (
          <span className="absolute top-1 end-1 bg-trace-blue/90 text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full">
            {t('receiving.grid.cardBadge', { variants: variantsWithQty, units: totalUnits })}
          </span>
        )}
      </div>
      <div className="px-2 py-1.5">
        <div className="text-caption font-semibold text-primary truncate">{product.title}</div>
        <div className="text-[10px] font-mono text-muted">
          {t('receiving.grid.variantMeta', { count: product.variants.length })}
        </div>
      </div>
    </button>
  )
}

// ── VariantModal ──────────────────────────────────────────────────────────

function VariantModal({ product, groups, onCommitVariant, onClose }: {
  product: CatalogProduct
  groups: Map<string, VariantGroup>
  onCommitVariant: (variantId: string, qty: number) => void
  onClose: () => void
}) {
  const { t } = useTranslation()

  function handleClose() {
    // Flush whatever field currently has focus before closing — a typed-but-
    // not-yet-blurred quantity must never be silently lost when the modal closes.
    (document.activeElement as HTMLElement | null)?.blur?.()
    onClose()
  }

  return (
    <Modal title={product.title} onClose={handleClose}>
      <div className="space-y-0.5 max-h-[50vh] overflow-y-auto" data-testid="variant-modal-body">
        {product.variants.map(v => (
          <VariantRow
            key={v.id}
            variant={v}
            committedQty={groups.get(v.id)?.qty ?? 0}
            onCommit={qty => onCommitVariant(v.id, qty)}
          />
        ))}
      </div>
      <div className="flex justify-end pt-4 mt-3 border-t border-line">
        <Button onClick={handleClose}>{t('receiving.grid.doneButton')}</Button>
      </div>
    </Modal>
  )
}

// ── VariantRow ────────────────────────────────────────────────────────────
// SAFETY: not a scan input — a plain quantity field. Check-first-then-quantity:
// the field stays disabled/muted until its checkbox is checked (required by
// spec), and unchecking an already-quantified row clears it (commits qty=0).

function VariantRow({ variant, committedQty, onCommit }: {
  variant: CatalogVariant
  committedQty: number
  onCommit: (qty: number) => void
}) {
  const [checked, setChecked]       = useState(committedQty > 0)
  const [localValue, setLocalValue] = useState<string | null>(null)
  const debounceTimer  = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRef        = useRef<HTMLInputElement>(null)
  const userToggledRef  = useRef(false)

  // Sync checked state if committedQty changes externally (e.g. after a commit
  // round-trip updates the prop) without clobbering an in-progress local edit.
  useEffect(() => {
    if (localValue === null) setChecked(committedQty > 0)
  }, [committedQty, localValue])

  // Focus the quantity field exactly once, only right after the user's own
  // click checked this row — never on mount, never on a props-driven sync.
  useEffect(() => {
    if (checked && userToggledRef.current) {
      userToggledRef.current = false
      inputRef.current?.focus()
    }
  }, [checked])

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

  function handleCheckToggle(next: boolean) {
    userToggledRef.current = next
    setChecked(next)
    if (!next) {
      if (debounceTimer.current) { clearTimeout(debounceTimer.current); debounceTimer.current = null }
      setLocalValue(null)
      if (committedQty > 0) onCommit(0)
    }
  }

  const displayValue = localValue ?? (committedQty > 0 ? String(committedQty) : '')
  const hasQty = parse(displayValue) > 0

  return (
    <div
      className="flex items-center gap-2.5 py-2 px-1.5 rounded-lg hover:bg-elevated transition-colors"
      data-testid={`variant-row-${variant.id}`}
    >
      <Checkbox checked={checked} onChange={handleCheckToggle} />
      <span className={cn('text-body flex-1 truncate', checked ? 'font-semibold text-primary' : 'text-primary')}>
        {variant.title}
      </span>
      {variant.sku && (
        <span className="font-mono text-caption text-muted flex-shrink-0">{variant.sku}</span>
      )}
      <input
        ref={inputRef}
        type="number"
        min={0}
        inputMode="numeric"
        placeholder="0"
        disabled={!checked}
        data-testid={`qty-input-${variant.id}`}
        value={displayValue}
        onChange={e => scheduleFromInput(e.target.value)}
        onBlur={flushNow}
        onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
        className={cn(
          'w-16 flex-shrink-0 text-center rounded-lg border bg-transparent px-2 py-1.5 text-caption font-mono transition-colors',
          !checked && 'border-line text-muted/50 cursor-not-allowed',
          checked && !hasQty && 'border-line text-muted',
          checked && hasQty && 'border-trace-blue bg-elevated text-primary'
        )}
      />
    </div>
  )
}

// exported for tests only — not part of the public component surface
export { groupLinesByVariant }
export type { VariantGroup }
