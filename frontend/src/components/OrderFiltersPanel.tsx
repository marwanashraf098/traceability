import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Filter, X } from 'lucide-react'
import { getOrderCounts, type OrderListFilters, type OrderTab, type OrderTabCounts } from '../api'
import { Button, FilterChip, cn } from './ui'

/** The Orders list's filters — every value as it sits in the URL ('' / absent = All; days absent = 30). */
export interface OrderFilterValues {
  traced: '' | 'traced' | 'not_traced'
  returns: '' | 'pending' | 'inspected'
  courier: '' | 'bosta' | 'other'
  payment: '' | 'cod' | 'prepaid'
  days: '7' | '30' | '90' | 'all'
}

export const DEFAULT_FILTERS: OrderFilterValues = { traced: '', returns: '', courier: '', payment: '', days: '30' }

type GroupKey = keyof OrderFilterValues

/** Chip options per group, in display order. The value '' is "All". */
const GROUPS: Array<{ key: GroupKey; options: string[]; hint?: boolean }> = [
  { key: 'traced',  options: ['', 'traced', 'not_traced'], hint: true },
  { key: 'returns', options: ['', 'pending', 'inspected'], hint: true },
  { key: 'courier', options: ['', 'bosta', 'other'] },
  { key: 'payment', options: ['', 'cod', 'prepaid'] },
  { key: 'days',    options: ['7', '30', '90', 'all'] },
]

/** The API's filter shape (days always sent — the server ignores it while searching / on Needs attention). */
export function toApiFilters(f: OrderFilterValues): OrderListFilters {
  return {
    traced:  f.traced  || undefined,
    returns: f.returns || undefined,
    courier: f.courier || undefined,
    payment: f.payment || undefined,
    days:    f.days,
  }
}

/** Filters that differ from the default, in display order. */
export function activeFilterKeys(f: OrderFilterValues): GroupKey[] {
  return GROUPS.map(g => g.key).filter(k => f[k] !== DEFAULT_FILTERS[k])
}

function optionLabelKey(group: GroupKey, value: string): string {
  if (group === 'days') return `orders.filters.options.d${value}`
  return value === '' ? 'orders.filters.options.all' : `orders.filters.options.${value}`
}

// The count under the pending selection for the active tab — Needs attention has none without the alert check.
const COUNT_KEY: Record<OrderTab, keyof OrderTabCounts | null> = {
  all: 'all', attention: null, toShip: 'toShip', inTransit: 'inTransit',
  delivered: 'delivered', returned: 'returned', cancelled: 'cancelled',
}

const PREVIEW_DEBOUNCE_MS = 150

/**
 * "Filters" button + its active-filter tags + the panel (a popover on desktop, a full-width bottom sheet on phones).
 * The panel edits a pending copy; "Show N orders" applies it (N from /orders/counts?attention=false).
 */
export default function OrderFiltersPanel({ value, onApply, tab, daysApplied }: {
  value: OrderFilterValues
  onApply: (next: OrderFilterValues) => void
  tab: OrderTab
  /** false while searching or on Needs attention — the date group then shows as not applied. */
  daysApplied: boolean
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState<OrderFilterValues>(value)
  const [preview, setPreview] = useState<OrderTabCounts | null>(null)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const active = activeFilterKeys(value)

  function openPanel() { setPending(value); setPreview(null); setOpen(true) }

  // Preview count for the pending selection — the cheap counts call (no alert check); newest wins.
  useEffect(() => {
    if (!open) return
    const ctrl = new AbortController()
    const timer = setTimeout(() => {
      getOrderCounts(toApiFilters(pending), { attention: false, signal: ctrl.signal })
        .then(c => { if (!ctrl.signal.aborted) setPreview(c) })
        .catch(() => {})
    }, PREVIEW_DEBOUNCE_MS)
    return () => { clearTimeout(timer); ctrl.abort() }
  }, [open, pending])

  // Escape closes; on desktop a click outside the popover closes it (phones have the scrim).
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => { document.removeEventListener('keydown', onKey); document.removeEventListener('mousedown', onDown) }
  }, [open])

  const countKey = COUNT_KEY[tab]
  const n = countKey && preview ? (preview[countKey] as number) : null

  function apply() { onApply(pending); setOpen(false) }

  return (
    <>
      <div ref={rootRef} className="relative">
        <button
          type="button"
          data-testid="orders-filters-button"
          aria-expanded={open}
          aria-haspopup="dialog"
          onClick={() => (open ? setOpen(false) : openPanel())}
          className={cn(
            'btn rounded-xl transition-colors h-[37px] px-3 text-small border inline-flex items-center gap-2',
            active.length > 0
              ? 'bg-trace-blue/10 border-trace-blue/40 text-trace-blue'
              : 'bg-transparent border-line text-primary hover:bg-elevated hover:border-grey-300'
          )}
        >
          <Filter size={16} strokeWidth={2} />
          {t('orders.filters.button')}
          {active.length > 0 && (
            <span data-testid="orders-filters-count"
                  aria-label={t('orders.filters.activeCount', { count: active.length })}
                  className="text-caption font-semibold rounded-full bg-trace-blue text-white min-w-[20px] h-5 px-1.5 inline-flex items-center justify-center">
              {active.length}
            </span>
          )}
        </button>

        {open && (
          <>
            {/* Phones: a scrim behind the bottom sheet. */}
            <div className="min-[900px]:hidden fixed inset-0 bg-black/45 z-overlay" onClick={() => setOpen(false)}
                 data-testid="orders-filters-scrim" />
            <div
              role="dialog"
              aria-label={t('orders.filters.title')}
              data-testid="orders-filters-panel"
              className={cn(
                'bg-surface border border-line shadow-e4 flex flex-col z-dropdown',
                // phone: full-width bottom sheet
                'max-[899px]:fixed max-[899px]:inset-x-0 max-[899px]:bottom-0 max-[899px]:max-h-[85vh] max-[899px]:rounded-t-2xl',
                // desktop: popover under the button
                'min-[900px]:absolute min-[900px]:top-full min-[900px]:mt-2 min-[900px]:start-0 min-[900px]:w-[380px]',
                'min-[900px]:max-h-[70vh] min-[900px]:rounded-2xl',
              )}
            >
              <div className="flex items-center justify-between px-5 py-3.5 border-b border-line">
                <h2 className="text-h3 text-primary">{t('orders.filters.title')}</h2>
                <div className="flex items-center gap-1">
                  <Button variant="tertiary" size="sm" onClick={() => setPending(DEFAULT_FILTERS)}>
                    {t('orders.filters.clearAll')}
                  </Button>
                  <button type="button" onClick={() => setOpen(false)} aria-label={t('orders.filters.close')}
                          className="text-muted hover:text-primary transition-colors p-1">
                    <X size={18} strokeWidth={2} />
                  </button>
                </div>
              </div>

              <div className="overflow-y-auto px-5 py-4 space-y-5">
                {GROUPS.map(g => {
                  const notApplied = g.key === 'days' && !daysApplied
                  return (
                    <div key={g.key} data-testid={`orders-filter-group-${g.key}`}>
                      <div className="flex items-baseline justify-between gap-2 mb-2">
                        <p className="text-small font-medium text-primary">{t(`orders.filters.groups.${g.key}`)}</p>
                        {notApplied && (
                          <span className="text-caption text-muted" data-testid="orders-filter-days-not-applied">
                            {t('orders.filters.notApplied')}
                          </span>
                        )}
                      </div>
                      <div role="group" aria-label={t(`orders.filters.groups.${g.key}`)}
                           className={cn('flex flex-wrap gap-1.5', notApplied && 'opacity-60')}>
                        {g.options.map(opt => {
                          const on = pending[g.key] === opt
                          return (
                            <button
                              key={opt || 'all'}
                              type="button"
                              aria-pressed={on}
                              data-testid={`orders-filter-${g.key}-${opt || 'all'}`}
                              onClick={() => setPending(p => ({ ...p, [g.key]: opt }))}
                              className={cn(
                                'px-3 py-1.5 rounded-full border text-small transition-colors whitespace-nowrap',
                                on ? 'bg-brand/[0.14] border-trace-blue/40 text-trace-blue font-medium'
                                   : 'border-line text-muted hover:text-primary hover:bg-elevated'
                              )}
                            >
                              {t(optionLabelKey(g.key, opt))}
                            </button>
                          )
                        })}
                      </div>
                      {g.hint && <p className="text-caption text-muted mt-2">{t(`orders.filters.hints.${g.key}`)}</p>}
                    </div>
                  )
                })}
              </div>

              <div className="border-t border-line px-5 py-3.5">
                <Button className="w-full justify-center" onClick={apply}>
                  {n != null ? t('orders.filters.show', { count: n }) : t('orders.filters.showOrders')}
                </Button>
              </div>
            </div>
          </>
        )}
      </div>

      {active.map(k => {
        const label = t('orders.filters.tag', {
          group: t(`orders.filters.groups.${k}`), value: t(optionLabelKey(k, value[k])),
        })
        return (
          <span key={k} data-testid={`orders-filter-tag-${k}`}>
            <FilterChip label={label} removeLabel={t('orders.filters.removeTag', { label })}
                        onRemove={() => onApply({ ...value, [k]: DEFAULT_FILTERS[k] })} />
          </span>
        )
      })}
    </>
  )
}
