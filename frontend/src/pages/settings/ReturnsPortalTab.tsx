import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CustomerFeeSettings, PortalSettings, PortalSettingsError, PortalSettingsInput, OtherCouriersReturnsSetting,
  getOtherCouriersReturns, getPortalSettings, savePortalSettings,
} from '../../api'
import { useCapabilities } from '../../capabilities'
import { Button, Skeleton, useToast } from '../../components/ui'
import { writeToClipboard } from './connections/CopyRow'
import { useUploadedLogoUrl } from './PortalBranding'
import { PortalFont, portalFontOrDefault } from '../../portal/fonts'
import { FeeDraft, useCustomerFeeSettings } from './CustomerFeesSection'
import PortalHeaderCard, { PORTAL_HOST } from './portal/PortalHeaderCard'
import PolicySummaryCard from './portal/PolicySummaryCard'
import JumpLinks from './portal/JumpLinks'
import EligibilitySection from './portal/EligibilitySection'
import ApprovalSection from './portal/ApprovalSection'
import ExchangesSection from './portal/ExchangesSection'
import RefundsFeesSection, { orderedMethods, sameMethods } from './portal/RefundsFeesSection'
import BrandingSection from './portal/BrandingSection'
import { PolicyInput } from './portal/policySummary'

export { PORTAL_HOST } from './portal/PortalHeaderCard'
export { LOGO_PREFIX, POLICY_MAX, isValidLogoUrl } from './portal/BrandingSection'

type FieldKey = 'slug' | 'returnWindowDays' | 'logoUrl' | 'brandColor' | 'policyText' | 'returnLocationId' | 'pickupBooking'
  | 'exchangesEnabled' | 'font' | 'refundMethods'

/**
 * Returns portal Step 4e-A (M4) — the merchant's portal settings, owner and manager (the
 * API allows both; SettingsPage never shows this tab to a worker).
 *
 * "Save changes" sends the whole form in one PUT (the backend applies it as one UPDATE).
 * The non-returnable switches are separate: each saves immediately through
 * PUT /variants/{id}/non-returnable, as in the mockup (they are not part of the form).
 *
 * Step 4c-2: "Returns go back to" — the tenant's Bosta pickup locations, fetched live. With nothing
 * saved yet, Bosta's default location is preselected (so Save becomes available to store it).
 *
 * Step 4c-3: "Book Bosta pickups when I approve" (tenants.portal_pickup_booking). It can only be
 * switched ON once Bosta is connected and a return location is SAVED (the backend enforces the
 * same); the reason is shown under it. Sent in the PUT only when it changed.
 *
 * Redesign Pass A (design/returns-portal-settings.html) — appearance only. The tab is now a header (Live / Off, Open
 * portal, the switch and the link), "Your policy in plain words", jump links and five sections: Eligibility, Approval
 * & pickup, Exchanges, Refunds & fees, Branding & policy (components in ./portal). What saves when is UNCHANGED: this
 * form with "Save changes"; the logo, the non-returnable switches, the other-couriers switch and area fees the moment
 * they're used ("Saves right away"); exchange pricing, each fee and the store's-fault reasons with their own Save.
 * The customer-fee settings are loaded once here for both places they show and for the summary.
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
  const [bookingOn, setBookingOn] = useState(false)
  const [exchangesOn, setExchangesOn] = useState(false)
  // P1: the font saves with "Save changes" (sent only when it changed); the logo saves on its own.
  const [font, setFont] = useState<PortalFont>(portalFontOrDefault(undefined))
  // P2: the portal's refund methods (sent only when changed — absent leaves them as they are).
  const [refundMethods, setRefundMethods] = useState<string[]>([])
  // P3: "Require photos" (sent only when changed).
  const [requirePhotos, setRequirePhotos] = useState(false)
  const uploadedLogoUrl = useUploadedLogoUrl(saved?.logo?.version)
  // G4a's "Accept returns for orders shipped with other couriers" (its own endpoint) — G4b fix: with it on,
  // "Allow exchanges" no longer needs Bosta. Loaded only for a store that ships with other couriers.
  const { genericCourier } = useCapabilities()
  const [otherCouriers, setOtherCouriers] = useState<OtherCouriersReturnsSetting | null>(null)
  useEffect(() => {
    if (!genericCourier) return
    let cancelled = false
    getOtherCouriersReturns().then(s => { if (!cancelled) setOtherCouriers(s) }).catch(() => {})
    return () => { cancelled = true }
  }, [genericCourier])
  const otherCouriersShown = !!otherCouriers && (otherCouriers.available || otherCouriers.enabled)
  const otherCouriersOn = !!otherCouriers?.enabled
  /** Bosta orders can be exchanged: pickup booking on with Bosta connected (saved state). */
  const bostaExchangePath = !!saved?.portalPickupBooking && !!saved?.bostaConnected
  // Returns portal fees: loaded once for the Exchanges and Refunds & fees sections and the summary. What the fee
  // editors show (saved or not) feeds the summary through feeDraft; their saves update `fees`.
  const [fees, setFeesState] = useState<CustomerFeeSettings | null>(null)
  const [loadedFees] = useCustomerFeeSettings()
  useEffect(() => { if (loadedFees) setFeesState(loadedFees) }, [loadedFees])
  const [feeDraft, setFeeDraft] = useState<FeeDraft>({})
  const setFees = useCallback((s: CustomerFeeSettings) => setFeesState(s), [])
  const onFeeDraft = useCallback((d: FeeDraft) => setFeeDraft(prev => ({
    pricing: d.pricing ?? prev.pricing,
    rules: d.rules ? { ...prev.rules, ...d.rules } : prev.rules,
  })), [])
  // Stable: ReturnLocationRow preselects Bosta's default through it from an effect.
  const onLocation = useCallback((id: string | null) => {
    setForm(f => (f ? { ...f, returnLocationId: id } : f))
    setErrors(e => (e.returnLocationId ? { ...e, returnLocationId: undefined } : e))
  }, [])

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      const s = await getPortalSettings()
      setSaved(s)
      setForm(toInput(s))
      setWindowText(String(s.returnWindowDays))
      setBookingOn(!!s.portalPickupBooking)
      setExchangesOn(!!s.exchangesEnabled)
      setFont(portalFontOrDefault(s.font))
      setRefundMethods(s.refundMethods ?? [])
      setRequirePhotos(!!s.requirePhotos)
    } catch {
      setLoadError(true)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const dirty = useMemo(() => {
    if (!saved || !form) return false
    const a = toInput(saved)
    return JSON.stringify(normalize(a)) !== JSON.stringify(normalize({ ...form, returnWindowDays: Number(windowText) }))
      || bookingOn !== !!saved.portalPickupBooking
      || exchangesOn !== !!saved.exchangesEnabled
      || font !== portalFontOrDefault(saved.font)
      || !sameMethods(refundMethods, saved.refundMethods ?? [])
      || requirePhotos !== !!saved.requirePhotos
  }, [saved, form, windowText, bookingOn, exchangesOn, font, refundMethods, requirePhotos])

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
        ...(bookingOn !== !!saved?.portalPickupBooking ? { pickupBooking: bookingOn } : {}),
        ...(exchangesOn !== !!saved?.exchangesEnabled ? { exchangesEnabled: exchangesOn } : {}),
        ...(font !== portalFontOrDefault(saved?.font) ? { font } : {}),
        ...(!sameMethods(refundMethods, saved?.refundMethods ?? []) ? { refundMethods: orderedMethods(refundMethods) } : {}),
        ...(requirePhotos !== !!saved?.requirePhotos ? { requirePhotos } : {}),
      }
      const s = await savePortalSettings(body)
      setSaved(s)
      setForm(toInput(s))
      setWindowText(String(s.returnWindowDays))
      setBookingOn(!!s.portalPickupBooking)
      setExchangesOn(!!s.exchangesEnabled)
      setFont(portalFontOrDefault(s.font))
      setRefundMethods(s.refundMethods ?? [])
      setRequirePhotos(!!s.requirePhotos)
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

  const windowNum = Number(windowText)
  const windowDays = windowText.trim() !== '' && Number.isInteger(windowNum) && windowNum >= 1 && windowNum <= 90 ? windowNum : null

  const summaryInput: PolicyInput = {
    enabled: form.enabled,
    windowDays,
    otherCouriers: otherCouriersShown ? otherCouriersOn : null,
    requirePhotos,
    autoApprove: form.autoApprove,
    pickupBooking: bookingOn,
    exchanges: exchangesOn,
    refundMethods,
    fees: fees?.visible ? {
      ...(feeDraft.pricing ?? fees.exchangePricing),
      rules: {
        exchange: feeDraft.rules?.exchange ?? fees.rules.exchange,
        refund: feeDraft.rules?.refund ?? fees.rules.refund,
      },
    } : null,
  }

  return (
    <div className="space-y-5 max-w-5xl" data-testid="returns-portal-settings">
      <PortalHeaderCard
        savedLive={saved.enabled && !!saved.slug}
        savedSlug={saved.slug}
        enabled={form.enabled}
        onEnabled={v => update('enabled', v)}
        slug={form.slug ?? ''}
        onSlug={v => update('slug', v)}
        slugError={errors.slug}
        copied={copied}
        onCopy={copyLink}
      />

      <PolicySummaryCard input={summaryInput} />
      <JumpLinks />

      <EligibilitySection
        windowText={windowText}
        onWindow={v => { setWindowText(v); setErrors(er => ({ ...er, returnWindowDays: undefined })) }}
        windowError={errors.returnWindowDays}
        otherCouriers={otherCouriers && otherCouriersShown ? otherCouriers : null}
        onOtherCouriers={setOtherCouriers}
        offKeepsExchanges={!!saved.exchangesEnabled && !bostaExchangePath}
        requirePhotos={requirePhotos}
        onRequirePhotos={setRequirePhotos}
      />

      <ApprovalSection
        autoApprove={form.autoApprove}
        onAutoApprove={v => update('autoApprove', v)}
        savedPickupBooking={!!saved.pickupBooking}
        bookingOn={bookingOn}
        bookingDisabled={!bookingOn && !(saved.bostaConnected && saved.returnLocationId)}
        bookingNote={!bookingOn && !saved.bostaConnected
          ? t('settings.portal.booking.needsBosta')
          : !bookingOn && !saved.returnLocationId ? t('settings.portal.booking.needsLocation') : undefined}
        bookingError={errors.pickupBooking}
        onBooking={v => {
          setBookingOn(v)
          // Exchanges are booked through pickup booking — switching it off switches them off,
          // unless they're on for other couriers' orders too (G4b fix; the backend agrees).
          if (!v && !otherCouriersOn) setExchangesOn(false)
          setErrors(e => ({ ...e, pickupBooking: undefined }))
        }}
        savedLocationName={saved.returnLocationName}
        locationId={form.returnLocationId}
        onLocation={onLocation}
        locationError={errors.returnLocationId}
      />

      {/* Step 5c — "Allow exchanges": only with pickup booking on, Bosta connected and a return location —
          or (G4b fix) with returns for other couriers' orders on. */}
      <ExchangesSection
        exchangesOn={exchangesOn}
        onExchanges={v => { setExchangesOn(v); setErrors(e => ({ ...e, exchangesEnabled: undefined })) }}
        description={t(otherCouriersOn ? 'settings.portal.exchanges.descriptionOtherCouriers' : 'settings.portal.exchanges.description')}
        disabled={!exchangesOn && !otherCouriersOn && !(bookingOn && saved.bostaConnected && saved.returnLocationId)}
        note={exchangesOn
          ? (otherCouriersShown && !otherCouriersOn && !bostaExchangePath ? t('settings.portal.exchanges.noOrdersEligible') : undefined)
          : otherCouriersOn ? undefined
          : !saved.bostaConnected ? t(otherCouriersShown ? 'settings.portal.exchanges.needsBostaOrOtherCouriers' : 'settings.portal.exchanges.needsBosta')
            : !saved.returnLocationId ? t('settings.portal.exchanges.needsLocation')
              : !bookingOn ? t('settings.portal.exchanges.needsBooking') : undefined}
        error={errors.exchangesEnabled}
        fees={fees}
        onFees={setFees}
        onFeeDraft={onFeeDraft}
      />

      <RefundsFeesSection
        refundMethods={refundMethods}
        onRefundMethods={setRefundMethods}
        fees={fees}
        onFees={setFees}
        onFeeDraft={onFeeDraft}
      />

      <BrandingSection
        saved={saved}
        uploadedLogoUrl={uploadedLogoUrl}
        onLogoSaved={s => setSaved(prev => (prev ? { ...prev, logo: s.logo ?? null } : s))}
        logoUrl={form.logoUrl ?? ''}
        onLogoUrl={v => update('logoUrl', v)}
        font={font}
        onFont={f => { setFont(f); setErrors(e => ({ ...e, font: undefined })) }}
        brandColor={form.brandColor ?? ''}
        onBrandColor={v => update('brandColor', v)}
        policyText={form.policyText ?? ''}
        onPolicyText={v => update('policyText', v)}
        windowDays={windowDays}
        errors={errors}
      />

      <div className="flex flex-col items-end gap-1.5">
        <Button onClick={save} loading={saving} disabled={!dirty || saving}>
          {t('settings.portal.save')}
        </Button>
        <p className="text-small text-muted text-end">{t('settings.portal.branding.saveNote')}</p>
      </div>
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
    returnLocationId: s.returnLocationId,
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
    returnLocationId: i.returnLocationId ?? null,
  }
}

function isFieldKey(f: string): f is FieldKey {
  return ['slug', 'returnWindowDays', 'logoUrl', 'brandColor', 'policyText', 'returnLocationId', 'pickupBooking',
    'exchangesEnabled', 'font', 'refundMethods'].includes(f)
}

