import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search } from 'lucide-react'
import {
  OtherCouriersReturnsSetting, PortalVariantRow, TransferCommandError, getPortalVariants, getRoleFromToken,
  setOtherCouriersReturns, setVariantNonReturnable,
} from '../../../api'
import { Button, Input, Skeleton, Toggle, cn, useToast } from '../../../components/ui'
import { SavesRightAway, SectionCard, SwitchRow } from './ui'

const VARIANTS_PAGE = 25

/**
 * Eligibility — the return window and "Require photos" (saved with "Save changes"), the other-couriers switch and the
 * products that can't be returned (each saves the moment it's used, as before; marked "Saves right away").
 */
export default function EligibilitySection({
  windowText, onWindow, windowError, otherCouriers, onOtherCouriers, offKeepsExchanges, requirePhotos, onRequirePhotos,
}: {
  windowText: string
  onWindow: (v: string) => void
  windowError?: string
  /** Shown only to a store that ships with other couriers (or has it on); null otherwise. */
  otherCouriers: OtherCouriersReturnsSetting | null
  onOtherCouriers: (s: OtherCouriersReturnsSetting) => void
  offKeepsExchanges: boolean
  requirePhotos: boolean
  onRequirePhotos: (v: boolean) => void
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-eligibility" title={t('settings.portal.sections.eligibility.title')}
      description={t('settings.portal.sections.eligibility.description')}>
      <div className="px-6 py-5 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <label htmlFor="portal-window" className="text-body font-semibold text-primary block">
            {t('settings.portal.window.label')}
          </label>
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
        <OtherCouriersRow setting={otherCouriers} onSaved={onOtherCouriers} offKeepsExchanges={offKeepsExchanges} />
      )}

      <SwitchRow
        title={t('settings.portal.requirePhotos.title')}
        description={t('settings.portal.requirePhotos.description')}
        checked={requirePhotos}
        onChange={onRequirePhotos}
      />

      <NonReturnableList />
    </SectionCard>
  )
}

/**
 * Non-Bosta couriers G4a — "Accept returns for orders shipped with other couriers": shown only to a store that
 * ships with other couriers (or has it switched on). Saved at once on its own endpoint (owner only, audit-logged),
 * not with "Save changes"; a manager sees it read-only.
 */
function OtherCouriersRow({ setting, onSaved, offKeepsExchanges }: {
  setting: OtherCouriersReturnsSetting
  onSaved: (s: OtherCouriersReturnsSetting) => void
  /** G4b fix: exchanges are on and Bosta can't take them — say that switching this off leaves them on. */
  offKeepsExchanges: boolean
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const owner = getRoleFromToken() === 'owner'

  async function change(v: boolean) {
    setSaving(true)
    setError(undefined)
    try {
      const s = await setOtherCouriersReturns(v)
      onSaved(s)
      toast({ tone: 'success', message: t(v ? 'settings.portal.otherCouriers.savedOn' : 'settings.portal.otherCouriers.savedOff') })
    } catch (e) {
      setError(e instanceof TransferCommandError ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('settings.portal.otherCouriers.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-testid="other-couriers-row">
      <SwitchRow
        title={t('settings.portal.otherCouriers.title')}
        description={t('settings.portal.otherCouriers.description')}
        extra={owner ? <SavesRightAway /> : undefined}
        checked={setting.enabled}
        disabled={saving || !owner || (!setting.enabled && !setting.available)}
        note={!owner ? t('settings.portal.otherCouriers.ownerOnly')
          : setting.enabled && offKeepsExchanges ? t('settings.portal.otherCouriers.offKeepsExchanges') : undefined}
        noteTestId="other-couriers-note"
        error={error}
        errorTestId="error-otherCouriers"
        onChange={change}
      />
    </div>
  )
}

/** Search + per-variant switch; each switch saves immediately. */
function NonReturnableList() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<PortalVariantRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [pending, setPending] = useState<Set<string>>(new Set())

  // Debounce typing into the query that is actually fetched.
  useEffect(() => {
    const id = setTimeout(() => setQuery(search), 250)
    return () => clearTimeout(id)
  }, [search])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(false)
    getPortalVariants(query, 0, VARIANTS_PAGE)
      .then(res => { if (!cancelled) { setItems(res.items); setTotal(res.total) } })
      .catch(() => { if (!cancelled) setError(true) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [query])

  async function loadMore() {
    try {
      const res = await getPortalVariants(query, Math.floor(items.length / VARIANTS_PAGE), VARIANTS_PAGE)
      setItems(prev => [...prev, ...res.items.filter(v => !prev.some(p => p.id === v.id))])
      setTotal(res.total)
    } catch {
      toast({ tone: 'error', message: t('settings.portal.products.loadFailed') })
    }
  }

  async function toggle(v: PortalVariantRow, value: boolean) {
    setPending(p => new Set(p).add(v.id))
    try {
      await setVariantNonReturnable(v.id, value)
      setItems(prev => prev.map(x => (x.id === v.id ? { ...x, nonReturnable: value } : x)))
    } catch {
      toast({ tone: 'error', message: t('settings.portal.products.saveFailed') })
    } finally {
      setPending(p => { const n = new Set(p); n.delete(v.id); return n })
    }
  }

  return (
    <div className="px-6 py-5 space-y-3">
      <div>
        <p className="text-body font-semibold text-primary">{t('settings.portal.products.title')}<SavesRightAway /></p>
        <p className="text-small text-muted mt-0.5">{t('settings.portal.products.description')}</p>
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
        <ul className="rounded-xl border border-line divide-y divide-line max-h-[360px] overflow-y-auto" data-testid="variant-list">
          {items.map(v => {
            const name = [v.productTitle, v.variantTitle].filter(Boolean).join(', ')
            return (
              <li key={v.id} className="flex items-center gap-3 px-4 py-2.5" data-testid="variant-row">
                <div className="flex-1 min-w-0">
                  <p className="text-body text-primary truncate">
                    <span className="font-medium">{v.productTitle}</span>
                    {(v.variantTitle || v.sku) && (
                      <span className="text-muted">
                        {v.variantTitle && <> · {v.variantTitle}</>}
                        {v.sku && <> · <bdi className="font-mono text-small" dir="ltr">{v.sku}</bdi></>}
                      </span>
                    )}
                  </p>
                </div>
                <span className={cn('text-small hidden sm:inline', v.nonReturnable ? 'text-warning-text font-medium' : 'text-muted')}>
                  {v.nonReturnable ? t('settings.portal.products.nonReturnable') : t('settings.portal.products.returnable')}
                </span>
                <Toggle
                  checked={v.nonReturnable}
                  disabled={pending.has(v.id)}
                  onChange={value => toggle(v, value)}
                  ariaLabel={t('settings.portal.products.switchLabel', { name })}
                />
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
