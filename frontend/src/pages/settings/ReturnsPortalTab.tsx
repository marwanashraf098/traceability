import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search } from 'lucide-react'
import {
  getPortalSettings, getPortalVariants, savePortalSettings, setVariantNonReturnable,
  PortalSettings, PortalSettingsError, PortalSettingsInput, PortalVariantRow,
} from '../../api'
import { Button, Input, Skeleton, Toggle, cn, useToast } from '../../components/ui'
import { writeToClipboard } from './connections/CopyRow'

export const PORTAL_HOST = 'returns.tracedtech.com'
export const LOGO_PREFIX = 'https://cdn.shopify.com/'
export const POLICY_MAX = 2000
const VARIANTS_PAGE = 25

/** Same rule as the backend (and V103's CHECK): a Shopify Files CDN link. */
export function isValidLogoUrl(url: string): boolean {
  if (!url.startsWith(LOGO_PREFIX)) return false
  try {
    new URL(url)
    return true
  } catch {
    return false
  }
}

const HEX = /^#[0-9A-Fa-f]{6}$/

type FieldKey = 'slug' | 'returnWindowDays' | 'logoUrl' | 'brandColor' | 'policyText'

/**
 * Returns portal Step 4e-A (M4) — the merchant's portal settings, owner and manager (the
 * API allows both; SettingsPage never shows this tab to a worker).
 *
 * "Save changes" sends the whole form in one PUT (the backend applies it as one UPDATE).
 * The non-returnable switches are separate: each saves immediately through
 * PUT /variants/{id}/non-returnable, as in the mockup (they are not part of the form).
 */
export default function ReturnsPortalTab() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [saved, setSaved] = useState<PortalSettings | null>(null)
  const [form, setForm] = useState<PortalSettingsInput | null>(null)
  const [windowText, setWindowText] = useState('')
  const [loadError, setLoadError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [errors, setErrors] = useState<Partial<Record<FieldKey, string>>>({})
  const [copied, setCopied] = useState(false)

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      const s = await getPortalSettings()
      setSaved(s)
      setForm(toInput(s))
      setWindowText(String(s.returnWindowDays))
    } catch {
      setLoadError(true)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const dirty = useMemo(() => {
    if (!saved || !form) return false
    const a = toInput(saved)
    return JSON.stringify(normalize(a)) !== JSON.stringify(normalize({ ...form, returnWindowDays: Number(windowText) }))
  }, [saved, form, windowText])

  if (loadError) {
    return (
      <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="portal-load-error">
        <p className="text-body font-semibold text-primary">{t('settings.portal.loadError')}</p>
        <button className="btn-outline" onClick={load}>{t('exchangesRefunds.retry')}</button>
      </div>
    )
  }

  if (!form || !saved) {
    return (
      <div className="card p-6 space-y-4">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    )
  }

  function update<K extends keyof PortalSettingsInput>(key: K, value: PortalSettingsInput[K]) {
    setForm(f => (f ? { ...f, [key]: value } : f))
    if (key in errors) setErrors(e => ({ ...e, [key]: undefined }))
  }

  const slug = (form.slug ?? '').trim().toLowerCase()
  const portalUrl = `https://${PORTAL_HOST}/${slug}`

  async function copyLink() {
    try {
      await writeToClipboard(portalUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* the link is visible — the user can copy it by hand */ }
  }

  async function save() {
    if (!form) return
    setSaving(true)
    setErrors({})
    try {
      const body: PortalSettingsInput = {
        ...form,
        slug: form.slug?.trim() ? form.slug.trim() : null,
        returnWindowDays: Number(windowText),
        logoUrl: form.logoUrl?.trim() ? form.logoUrl.trim() : null,
        brandColor: form.brandColor?.trim() ? form.brandColor.trim() : null,
        policyText: form.policyText?.trim() ? form.policyText : null,
      }
      const s = await savePortalSettings(body)
      setSaved(s)
      setForm(toInput(s))
      setWindowText(String(s.returnWindowDays))
      toast({ tone: 'success', message: t('settings.portal.saved') })
    } catch (e) {
      if (e instanceof PortalSettingsError && e.field && isFieldKey(e.field)) {
        setErrors({ [e.field]: t(`settings.portal.errors.${e.code ?? 'generic'}`, { defaultValue: t('settings.portal.errors.generic') }) })
      } else if (e instanceof PortalSettingsError && e.status === 400) {
        toast({ tone: 'error', message: t('settings.portal.errors.generic') })
      } else {
        toast({ tone: 'error', message: t('settings.portal.saveFailed') })
      }
    } finally {
      setSaving(false)
    }
  }

  const logo = form.logoUrl?.trim() ?? ''
  const color = form.brandColor?.trim() ?? ''
  const policyLength = (form.policyText ?? '').length

  return (
    <div className="space-y-4 max-w-3xl" data-testid="returns-portal-settings">
      <section className="card divide-y divide-line">
        <SwitchRow
          title={t('settings.portal.enabled.title')}
          description={t('settings.portal.enabled.description')}
          checked={form.enabled}
          onChange={v => update('enabled', v)}
        />

        <div className="p-6 space-y-2">
          <label htmlFor="portal-slug" className="text-body font-medium text-primary block">
            {t('settings.portal.link.label')}
          </label>
          <div className="flex flex-wrap items-start gap-2">
            <div
              dir="ltr"
              className={cn(
                'flex flex-1 min-w-[240px] items-stretch rounded-xl border bg-surface overflow-hidden',
                errors.slug ? 'border-critical' : 'border-line focus-within:border-trace-blue'
              )}
            >
              <span className="flex items-center px-3 text-body text-muted bg-elevated border-e border-line whitespace-nowrap">
                {PORTAL_HOST}/
              </span>
              <input
                id="portal-slug"
                type="text"
                autoComplete="off"
                spellCheck={false}
                aria-invalid={!!errors.slug}
                aria-describedby="portal-slug-help"
                className="flex-1 min-w-0 px-3 py-2 bg-transparent text-body text-primary outline-none font-mono"
                value={form.slug ?? ''}
                onChange={e => update('slug', e.target.value)}
              />
            </div>
            <Button variant="outline" onClick={copyLink} disabled={!slug}>
              {copied ? t('settings.portal.link.copied') : t('settings.portal.link.copy')}
            </Button>
          </div>
          {errors.slug && <p className="text-small text-critical" role="alert" data-testid="error-slug">{errors.slug}</p>}
          {slug && (
            <p className="text-small text-primary font-mono break-all" dir="ltr" data-testid="portal-url">{portalUrl}</p>
          )}
          <p id="portal-slug-help" className="text-small text-muted">{t('settings.portal.link.help')}</p>
        </div>

        <div className="p-6 space-y-2">
          <label htmlFor="portal-window" className="text-body font-medium text-primary block">
            {t('settings.portal.window.label')}
          </label>
          <div className="flex items-center gap-3">
            <div className="w-24">
              <Input
                id="portal-window"
                type="number"
                min={1}
                max={90}
                inputMode="numeric"
                invalid={!!errors.returnWindowDays}
                aria-invalid={!!errors.returnWindowDays}
                value={windowText}
                onChange={e => { setWindowText(e.target.value); setErrors(er => ({ ...er, returnWindowDays: undefined })) }}
              />
            </div>
            <span className="text-body text-muted">{t('settings.portal.window.suffix')}</span>
          </div>
          {errors.returnWindowDays && (
            <p className="text-small text-critical" role="alert" data-testid="error-returnWindowDays">{errors.returnWindowDays}</p>
          )}
        </div>

        <SwitchRow
          title={t('settings.portal.autoApprove.title')}
          description={t(saved.pickupBooking ? 'settings.portal.autoApprove.descriptionBooking' : 'settings.portal.autoApprove.description')}
          checked={form.autoApprove}
          onChange={v => update('autoApprove', v)}
        />

        <NonReturnableList />
      </section>

      <section className="card p-6 space-y-5" aria-labelledby="portal-branding-title">
        <div>
          <h2 id="portal-branding-title" className="text-h3 text-primary">{t('settings.portal.branding.title')}</h2>
          <p className="text-small text-muted mt-1">{t('settings.portal.branding.description')}</p>
        </div>

        <div className="space-y-2">
          <label htmlFor="portal-logo" className="text-body font-medium text-primary block">
            {t('settings.portal.branding.logoLabel')}
          </label>
          <div className="flex items-center gap-3">
            <div className="flex-1" dir="ltr">
              <Input
                id="portal-logo"
                type="url"
                placeholder={`${LOGO_PREFIX}…`}
                invalid={!!errors.logoUrl}
                aria-invalid={!!errors.logoUrl}
                value={form.logoUrl ?? ''}
                onChange={e => update('logoUrl', e.target.value)}
              />
            </div>
            {isValidLogoUrl(logo) && (
              <img
                src={logo}
                alt={t('settings.portal.branding.logoPreview')}
                data-testid="logo-preview"
                className="h-10 w-auto max-w-[120px] object-contain rounded-lg border border-line bg-surface p-1"
              />
            )}
          </div>
          {errors.logoUrl && <p className="text-small text-critical" role="alert" data-testid="error-logoUrl">{errors.logoUrl}</p>}
          <p className="text-small text-muted">{t('settings.portal.branding.logoHelp')}</p>
        </div>

        <div className="space-y-2">
          <label htmlFor="portal-color" className="text-body font-medium text-primary block">
            {t('settings.portal.branding.colorLabel')}
          </label>
          <div className="flex items-center gap-3">
            <input
              type="color"
              aria-label={t('settings.portal.branding.colorPicker')}
              className="h-10 w-12 rounded-lg border border-line bg-surface p-1 cursor-pointer"
              value={HEX.test(color) ? color.toLowerCase() : '#000000'}
              onChange={e => update('brandColor', e.target.value.toUpperCase())}
            />
            <div className="w-36">
              <Input
                id="portal-color"
                type="text"
                placeholder="#1A2B3C"
                dir="ltr"
                className="font-mono"
                maxLength={7}
                invalid={!!errors.brandColor}
                aria-invalid={!!errors.brandColor}
                value={form.brandColor ?? ''}
                onChange={e => update('brandColor', e.target.value)}
              />
            </div>
          </div>
          {errors.brandColor && <p className="text-small text-critical" role="alert" data-testid="error-brandColor">{errors.brandColor}</p>}
        </div>

        <div className="space-y-2">
          <label htmlFor="portal-policy" className="text-body font-medium text-primary block">
            {t('settings.portal.branding.policyLabel')}
          </label>
          <textarea
            id="portal-policy"
            rows={5}
            dir="auto"
            maxLength={POLICY_MAX}
            aria-invalid={!!errors.policyText}
            aria-describedby="portal-policy-count"
            className={cn('input resize-y w-full', errors.policyText && 'border-critical')}
            value={form.policyText ?? ''}
            onChange={e => update('policyText', e.target.value)}
          />
          <div className="flex items-center justify-between gap-3">
            <span className="text-small text-critical" role={errors.policyText ? 'alert' : undefined} data-testid="error-policyText">
              {errors.policyText ?? ''}
            </span>
            <span id="portal-policy-count" className="text-small text-muted tabular-nums" dir="ltr">
              {policyLength} / {POLICY_MAX}
            </span>
          </div>
        </div>
      </section>

      <div className="flex justify-end">
        <Button onClick={save} loading={saving} disabled={!dirty || saving}>
          {t('settings.portal.save')}
        </Button>
      </div>
    </div>
  )
}

function SwitchRow({
  title, description, checked, onChange,
}: { title: string; description: string; checked: boolean; onChange: (v: boolean) => void }) {
  const { t } = useTranslation()
  return (
    <div className="p-6 flex items-start gap-4">
      <div className="flex-1 min-w-0">
        <p className="text-body font-medium text-primary">{title}</p>
        <p className="text-small text-muted mt-0.5">{description}</p>
      </div>
      <span className={cn('text-small font-medium mt-0.5', checked ? 'text-primary' : 'text-muted')} aria-hidden="true">
        {checked ? t('settings.portal.on') : t('settings.portal.off')}
      </span>
      <Toggle checked={checked} onChange={onChange} ariaLabel={title} />
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
    <div className="p-6 space-y-3">
      <div>
        <p className="text-body font-medium text-primary">{t('settings.portal.products.title')}</p>
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
        <ul className="rounded-xl border border-line divide-y divide-line" data-testid="variant-list">
          {items.map(v => {
            const name = [v.productTitle, v.variantTitle].filter(Boolean).join(', ')
            return (
              <li key={v.id} className="flex items-center gap-3 px-4 py-3" data-testid="variant-row">
                <div className="flex-1 min-w-0">
                  <p className="text-body text-primary truncate">
                    {v.productTitle}
                    {(v.variantTitle || v.sku) && (
                      <span className="text-muted">
                        {v.variantTitle && <> · {v.variantTitle}</>}
                        {v.sku && <> · <bdi className="font-mono text-small" dir="ltr">{v.sku}</bdi></>}
                      </span>
                    )}
                  </p>
                </div>
                <span className={cn('text-small', v.nonReturnable ? 'text-warning-text font-medium' : 'text-muted')}>
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

function toInput(s: PortalSettings): PortalSettingsInput {
  return {
    slug: s.slug,
    enabled: s.enabled,
    autoApprove: s.autoApprove,
    returnWindowDays: s.returnWindowDays,
    logoUrl: s.logoUrl,
    brandColor: s.brandColor,
    policyText: s.policyText,
  }
}

function normalize(i: PortalSettingsInput) {
  const blank = (v: string | null) => (v && v.trim() ? v.trim() : null)
  return {
    slug: blank(i.slug)?.toLowerCase() ?? null,
    enabled: i.enabled,
    autoApprove: i.autoApprove,
    returnWindowDays: i.returnWindowDays,
    logoUrl: blank(i.logoUrl),
    brandColor: blank(i.brandColor),
    policyText: blank(i.policyText),
  }
}

function isFieldKey(f: string): f is FieldKey {
  return ['slug', 'returnWindowDays', 'logoUrl', 'brandColor', 'policyText'].includes(f)
}
