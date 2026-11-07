import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, Search, X } from 'lucide-react'
import { OtherCouriersReturnsSetting, PortalVariantRow, getPortalVariants } from '../../../api'
import { Button, Input, Skeleton, cn } from '../../../components/ui'
import { EditedTag, SectionCard, SwitchRow } from './ui'
import type { Draft } from './savePlan'
import type { ExcludedList } from './usePortalSettingsDraft'

const SEARCH_PAGE = 25

export interface ExcludedProps {
  /** The saved excluded list (GET /variants?nonReturnable=true); null while loading. */
  excluded: ExcludedList | null
  excludedError: boolean
  onRetryExcluded: () => void
  onMoreExcluded: () => void
  /** Products being changed in the draft. */
  changes: Draft['variants']
  onChange: (row: PortalVariantRow, value: boolean) => void
}

/**
 * Eligibility — the return window, "Require photos", the other-couriers switch and the products that can't be returned.
 * Redesign Pass B: all of it goes into the tab's draft and is saved by "Save changes" (no click-to-save any more).
 */
export default function EligibilitySection({
  windowText, onWindow, windowError, windowEdited, otherCouriers, otherCouriersOn, onOtherCouriers, otherCouriersEdited,
  otherCouriersError, owner, offKeepsExchanges, requirePhotos, onRequirePhotos, requirePhotosEdited, products,
}: {
  windowText: string
  onWindow: (v: string) => void
  windowError?: string
  windowEdited: boolean
  /** Shown only to a store that ships with other couriers (or has it on); null otherwise. */
  otherCouriers: OtherCouriersReturnsSetting | null
  otherCouriersOn: boolean
  onOtherCouriers: (v: boolean) => void
  otherCouriersEdited: boolean
  otherCouriersError?: string
  owner: boolean
  offKeepsExchanges: boolean
  requirePhotos: boolean
  onRequirePhotos: (v: boolean) => void
  requirePhotosEdited: boolean
  products: ExcludedProps
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-eligibility" title={t('settings.portal.sections.eligibility.title')}
      description={t('settings.portal.sections.eligibility.description')}>
      <div className="px-6 py-5 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <div className="flex items-center">
            <label htmlFor="portal-window" className="text-body font-semibold text-primary block">
              {t('settings.portal.window.label')}
            </label>
            {windowEdited && <EditedTag />}
          </div>
          {windowError && (
            <p className="text-small text-critical mt-1" role="alert" data-testid="error-returnWindowDays">{windowError}</p>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="w-24">
            <Input
              id="portal-window"
              type="number"
              min={1}
              max={90}
              inputMode="numeric"
              invalid={!!windowError}
              aria-invalid={!!windowError}
              value={windowText}
              onChange={e => onWindow(e.target.value)}
            />
          </div>
          <span className="text-body text-muted">{t('settings.portal.window.suffix')}</span>
        </div>
      </div>

      {otherCouriers && (
        <div data-testid="other-couriers-row">
          <SwitchRow
            title={t('settings.portal.otherCouriers.title')}
            description={t('settings.portal.otherCouriers.description')}
            extra={otherCouriersEdited ? <EditedTag /> : undefined}
            checked={otherCouriersOn}
            disabled={!owner || (!otherCouriersOn && !otherCouriers.available)}
            note={!owner ? t('settings.portal.otherCouriers.ownerOnly')
              : otherCouriersOn && offKeepsExchanges ? t('settings.portal.otherCouriers.offKeepsExchanges') : undefined}
            noteTestId="other-couriers-note"
            error={otherCouriersError}
            errorTestId="error-otherCouriers"
            onChange={onOtherCouriers}
          />
        </div>
      )}

      <SwitchRow
        title={t('settings.portal.requirePhotos.title')}
        description={t('settings.portal.requirePhotos.description')}
        extra={requirePhotosEdited ? <EditedTag /> : undefined}
        checked={requirePhotos}
        onChange={onRequirePhotos}
      />

      <ExcludedProducts {...products} />
    </SectionCard>
  )
}

/**
 * "Products that can't be returned" — only the excluded products (GET /variants?nonReturnable=true), each with Remove,
 * and "Add products" to search the catalogue (the existing ?search=). Both go into the draft; Save sends only the
 * products that changed, one PUT each.
 */
function ExcludedProducts({ excluded, excludedError, onRetryExcluded, onMoreExcluded, changes, onChange }: ExcludedProps) {
  const { t } = useTranslation()
  const [adding, setAdding] = useState(false)
  const added = Object.values(changes).filter(c => c.value && !c.row.nonReturnable).map(c => c.row)
  const removed = new Set(Object.values(changes).filter(c => !c.value && c.row.nonReturnable).map(c => c.row.id))
  const savedItems = excluded?.items ?? []
  const rows = [...added.filter(a => !savedItems.some(v => v.id === a.id)), ...savedItems]
  const count = excluded ? excluded.total + added.length - removed.size : 0
  const edited = added.length + removed.size > 0
  const isExcluded = (v: PortalVariantRow) => (changes[v.id] ? changes[v.id].value : v.nonReturnable)

  return (
    <div className="px-6 py-5 space-y-3" data-testid="excluded-products">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-body font-semibold text-primary">{t('settings.portal.products.title')}{edited && <EditedTag />}</p>
          <p className="text-small text-muted mt-0.5">{t('settings.portal.products.description')}</p>
          {excluded && count > 0 && (
            <p className="text-small text-primary font-medium mt-1" data-testid="excluded-count">
              {t('settings.portal.products.count', { count })}
            </p>
          )}
        </div>
        {!adding && (
          <Button size="sm" variant="outline" iconStart={Plus} onClick={() => setAdding(true)}>
            {t('settings.portal.products.add')}
          </Button>
        )}
      </div>

      {adding && <AddProductsPanel isExcluded={isExcluded} onExclude={v => onChange(v, true)} onDone={() => setAdding(false)} />}

      {excludedError ? (
        <div className="space-y-2">
          <p className="text-small text-critical">{t('settings.portal.products.loadFailed')}</p>
          <Button size="sm" variant="outline" onClick={onRetryExcluded}>{t('settings.portal.returnLocation.retry')}</Button>
        </div>
      ) : !excluded ? (
        <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : rows.length === 0 ? (
        <p className="text-small text-muted rounded-xl border border-dashed border-line px-4 py-4 text-center" data-testid="excluded-empty">
          {t('settings.portal.products.emptyExcluded')}
        </p>
      ) : (
        <ul className="rounded-xl border border-line divide-y divide-line max-h-[360px] overflow-y-auto" data-testid="variant-list">
          {rows.map(v => {
            const gone = removed.has(v.id)
            const isNew = !v.nonReturnable
            const name = [v.productTitle, v.variantTitle].filter(Boolean).join(', ')
            return (
              <li key={v.id} className={cn('flex items-center gap-3 px-4 py-2.5', gone && 'bg-elevated/60')} data-testid="variant-row">
                <ProductName v={v} struck={gone} />
                {(gone || isNew) && <EditedTag className="shrink-0" />}
                {gone ? (
                  <RowButton onClick={() => onChange(v, true)} label={t('settings.portal.products.undoLabel', { name })}>
                    {t('settings.portal.products.undo')}
                  </RowButton>
                ) : (
                  <RowButton onClick={() => onChange(v, false)} label={t('settings.portal.products.removeLabel', { name })}>
                    <X className="w-4 h-4" aria-hidden="true" />{t('settings.portal.products.remove')}
                  </RowButton>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {excluded && !excludedError && excluded.items.length < excluded.total && (
        <Button variant="ghost" size="sm" onClick={onMoreExcluded}>
          {t('settings.portal.products.showMore', { count: excluded.total - excluded.items.length })}
        </Button>
      )}
    </div>
  )
}

/** A small row action whose accessible name names the product ("Remove Linen Shirt, White / M"). */
function RowButton({ children, label, onClick, outline }: { children: React.ReactNode; label: string; onClick: () => void; outline?: boolean }) {
  return (
    <button type="button" aria-label={label} onClick={onClick}
      className={cn('btn rounded-xl transition-colors px-3 py-1.5 text-small gap-1.5 shrink-0',
        outline ? 'bg-transparent border border-line text-primary hover:bg-elevated hover:border-grey-300'
          : 'text-muted hover:text-primary hover:bg-elevated bg-transparent')}>
      {children}
    </button>
  )
}

function ProductName({ v, struck }: { v: PortalVariantRow; struck?: boolean }) {
  return (
    <div className="flex-1 min-w-0">
      {/* Name on one line, variant · SKU under it — each isolated, so a Latin name in an Arabic page keeps its order. */}
      <p className={cn('text-body text-primary font-medium break-words', struck && 'line-through text-muted')}>
        <bdi>{v.productTitle}</bdi>
      </p>
      {(v.variantTitle || v.sku) && (
        <p className={cn('text-small text-muted break-words', struck && 'line-through')}>
          {v.variantTitle && <bdi>{v.variantTitle}</bdi>}
          {v.variantTitle && v.sku && ' · '}
          {v.sku && <bdi className="font-mono whitespace-nowrap" dir="ltr">{v.sku}</bdi>}
        </p>
      )}
    </div>
  )
}

/** Search the whole catalogue and exclude a product (into the draft). */
function AddProductsPanel({ isExcluded, onExclude, onDone }: {
  isExcluded: (v: PortalVariantRow) => boolean
  onExclude: (v: PortalVariantRow) => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<PortalVariantRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    const id = setTimeout(() => setQuery(search), 250)
    return () => clearTimeout(id)
  }, [search])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(false)
    getPortalVariants(query, 0, SEARCH_PAGE)
      .then(res => { if (!cancelled) { setItems(res.items); setTotal(res.total) } })
      .catch(() => { if (!cancelled) setError(true) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [query])

  async function loadMore() {
    try {
      const res = await getPortalVariants(query, Math.floor(items.length / SEARCH_PAGE), SEARCH_PAGE)
      setItems(prev => [...prev, ...res.items.filter(v => !prev.some(p => p.id === v.id))])
      setTotal(res.total)
    } catch {
      setError(true)
    }
  }

  return (
    <div className="rounded-xl border border-line bg-elevated/40 p-4 space-y-3" data-testid="add-products">
      <div className="flex items-center justify-between gap-3">
        <p className="text-body font-semibold text-primary">{t('settings.portal.products.addTitle')}</p>
        <Button size="sm" variant="outline" onClick={onDone}>{t('settings.portal.products.done')}</Button>
      </div>
      <label htmlFor="portal-product-search" className="sr-only">{t('settings.portal.products.searchLabel')}</label>
      <Input
        id="portal-product-search"
        type="search"
        iconStart={Search}
        placeholder={t('settings.portal.products.searchPlaceholder')}
        value={search}
        onChange={e => setSearch(e.target.value)}
      />
      {error ? (
        <p className="text-small text-critical">{t('settings.portal.products.loadFailed')}</p>
      ) : loading ? (
        <div className="space-y-2"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : items.length === 0 ? (
        <p className="text-small text-muted py-2">{t('settings.portal.products.empty')}</p>
      ) : (
        <ul className="rounded-xl border border-line bg-panel divide-y divide-line max-h-[300px] overflow-y-auto" data-testid="add-products-list">
          {items.map(v => {
            const name = [v.productTitle, v.variantTitle].filter(Boolean).join(', ')
            const already = isExcluded(v)
            return (
              <li key={v.id} className="flex items-center gap-3 px-4 py-2.5" data-testid="add-product-row">
                <ProductName v={v} />
                {already ? (
                  <span className="text-small text-warning-text font-medium shrink-0">{t('settings.portal.products.nonReturnable')}</span>
                ) : (
                  <RowButton outline onClick={() => onExclude(v)} label={t('settings.portal.products.excludeLabel', { name })}>
                    {t('settings.portal.products.exclude')}
                  </RowButton>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {!loading && !error && items.length < total && (
        <Button variant="ghost" size="sm" onClick={loadMore}>
          {t('settings.portal.products.showMore', { count: total - items.length })}
        </Button>
      )}
    </div>
  )
}
