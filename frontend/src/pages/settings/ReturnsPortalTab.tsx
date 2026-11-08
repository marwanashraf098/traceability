import { useCallback, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { PortalSettingsInput } from '../../api'
import { Skeleton } from '../../components/ui'
import { writeToClipboard } from './connections/CopyRow'
import { useUploadedLogoUrl } from './PortalBranding'
import type { FeesDraft, FeesEditorProps } from './CustomerFeesSection'
import ShopifyReturnsSyncCard from './ShopifyReturnsSyncCard'
import PortalHeaderCard, { PORTAL_HOST } from './portal/PortalHeaderCard'
import PolicySummaryCard from './portal/PolicySummaryCard'
import JumpLinks from './portal/JumpLinks'
import EligibilitySection from './portal/EligibilitySection'
import ApprovalSection from './portal/ApprovalSection'
import ExchangesSection from './portal/ExchangesSection'
import RefundsFeesSection from './portal/RefundsFeesSection'
import BrandingSection from './portal/BrandingSection'
import SaveBar from './portal/SaveBar'
import { PolicyInput } from './portal/policySummary'
import { Draft, EditedKey, PortalDraft, pricingBody, ruleBody } from './portal/savePlan'
import { PortalFieldError, usePortalSettingsDraft } from './portal/usePortalSettingsDraft'
import { useLeaveGuard } from './portal/useLeaveGuard'

export { PORTAL_HOST } from './portal/PortalHeaderCard'
export { LOGO_PREFIX, POLICY_MAX, isValidLogoUrl } from './portal/BrandingSection'

/**
 * Settings → Returns portal (owner and manager; SettingsPage never shows it to a worker).
 *
 * Redesign Pass B — ONE save for the whole tab. Every field is part of one draft (usePortalSettingsDraft); the sticky
 * bar "N unsaved changes · Discard · Save changes" appears while something is edited, and Save runs the ordered plan
 * (portal/savePlan.ts): other couriers → portal settings → exchange pricing → exchange fee → return fee → store's-fault
 * reasons → products, stopping at the first failure. Only the logo (upload / remove) and fees by area still save the
 * moment they're used ("Saves right away"). Leaving with unsaved changes asks first (useLeaveGuard).
 *
 * Booking / exchanges preconditions: Bosta connected (saved) and a return location — saved, or chosen in this draft
 * (the PUT carries both and the backend stores the location first). Everything else as before.
 */
export default function ReturnsPortalTab() {
  const { t } = useTranslation()
  const s = usePortalSettingsDraft()
  const [copied, setCopied] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const uploadedLogoUrl = useUploadedLogoUrl(s.saved?.portal.logo?.version)
  const edited = useMemo(() => new Set<EditedKey>(s.edited), [s.edited])
  const leaveDialog = useLeaveGuard(s.dirty, s.edited.length, root)

  const { update, clearFieldError } = s
  const setPortal = useCallback((patch: Partial<PortalDraft>) => update(d => ({ ...d, portal: { ...d.portal, ...patch } })), [update])
  const setForm = useCallback(<K extends keyof PortalSettingsInput>(key: K, value: PortalSettingsInput[K]) => {
    update(d => ({ ...d, portal: { ...d.portal, form: { ...d.portal.form, [key]: value } } }))
    if (isFieldError(key)) clearFieldError(key)
  }, [update, clearFieldError])
  const onLocation = useCallback((id: string) => setForm('returnLocationId', id), [setForm])
  const onFees = useCallback((fn: (d: FeesDraft) => FeesDraft) => update(d => ({ ...d, ...fn(d) })), [update])

  if (s.loadError) {
    return (
      <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="portal-load-error">
        <p className="text-body font-semibold text-primary">{t('settings.portal.loadError')}</p>
        <button className="btn-outline" onClick={s.load}>{t('exchangesRefunds.retry')}</button>
      </div>
    )
  }

  if (!s.saved || !s.draft) {
    return (
      <div className="card p-6 space-y-4">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    )
  }

  const saved = s.saved
  const draft: Draft = s.draft
  const portal = saved.portal
  const d = draft.portal
  const form = d.form
  const errors = s.fieldErrors
  const owner = s.role === 'owner'
  const has = (k: EditedKey) => edited.has(k)

  const slug = (form.slug ?? '').trim().toLowerCase()
  async function copyLink() {
    try {
      await writeToClipboard(`https://${PORTAL_HOST}/${slug}`)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* the link is visible — the user can copy it by hand */ }
  }

  const otherCouriersShown = !!saved.otherCouriers
  const otherCouriersOn = otherCouriersShown && draft.otherCouriers
  /** Bosta orders can be exchanged: pickup booking on with Bosta connected (saved state). */
  const bostaExchangePath = !!portal.portalPickupBooking && !!portal.bostaConnected
  /**
   * A return location — saved, or picked by the merchant in this draft. Bosta's default is never filled in by itself
   * (it is only suggested), so any location in the draft is one the merchant chose.
   */
  const hasLocation = !!form.returnLocationId

  const windowNum = Number(d.windowText)
  const windowDays = d.windowText.trim() !== '' && Number.isInteger(windowNum) && windowNum >= 1 && windowNum <= 90 ? windowNum : null

  const feesShown = !!saved.fees?.visible
  const feesProps: FeesEditorProps | null = feesShown ? {
    saved: saved.fees!, draft, onChange: onFees, owner, edited, errors: s.sectionErrors,
    capError: s.clientErrors.some(e => e.field === 'cap'), onAreaFeesSaved: s.onAreaFeesSaved,
  } : null

  const summaryInput: PolicyInput = {
    enabled: form.enabled,
    windowDays,
    otherCouriers: otherCouriersShown ? otherCouriersOn : null,
    requirePhotos: d.requirePhotos,
    autoApprove: form.autoApprove,
    pickupBooking: d.bookingOn,
    exchanges: d.exchangesOn,
    refundMethods: d.refundMethods,
    fees: feesShown ? {
      ...pricingBody(draft.pricing),
      rules: { exchange: ruleBody(draft.rules.exchange, draft.waiver), refund: ruleBody(draft.rules.refund, draft.waiver) },
    } : null,
  }

  return (
    <div ref={root} className="space-y-5 max-w-5xl" data-testid="returns-portal-settings">
      <PortalHeaderCard
        savedLive={portal.enabled && !!portal.slug}
        savedSlug={portal.slug}
        enabled={form.enabled}
        onEnabled={v => setForm('enabled', v)}
        slug={form.slug ?? ''}
        onSlug={v => setForm('slug', v)}
        slugError={errors.slug}
        copied={copied}
        onCopy={copyLink}
        edited={{ enabled: has('portal.enabled'), slug: has('portal.slug') }}
      />

      <PolicySummaryCard input={summaryInput} />
      <JumpLinks />

      <EligibilitySection
        windowText={d.windowText}
        onWindow={v => { setPortal({ windowText: v }); clearFieldError('returnWindowDays') }}
        windowError={errors.returnWindowDays}
        windowEdited={has('portal.returnWindowDays')}
        otherCouriers={saved.otherCouriers}
        otherCouriersOn={otherCouriersOn}
        onOtherCouriers={v => update(x => ({ ...x, otherCouriers: v }))}
        otherCouriersEdited={has('otherCouriers')}
        otherCouriersError={s.sectionErrors.otherCouriers}
        owner={owner}
        offKeepsExchanges={!!portal.exchangesEnabled && !bostaExchangePath}
        requirePhotos={d.requirePhotos}
        onRequirePhotos={v => setPortal({ requirePhotos: v })}
        requirePhotosEdited={has('portal.requirePhotos')}
        products={{
          excluded: s.excluded,
          excludedError: s.excludedError,
          onRetryExcluded: s.loadExcluded,
          onMoreExcluded: s.loadMoreExcluded,
          changes: draft.variants,
          onChange: (row, value) => update(x => {
            const variants = { ...x.variants }
            if (value === row.nonReturnable) delete variants[row.id]
            else variants[row.id] = { row, value }
            return { ...x, variants }
          }),
        }}
      />

      <ApprovalSection
        autoApprove={form.autoApprove}
        onAutoApprove={v => setForm('autoApprove', v)}
        savedPickupBooking={!!portal.pickupBooking}
        bookingOn={d.bookingOn}
        bookingDisabled={!d.bookingOn && !(portal.bostaConnected && hasLocation)}
        bookingNote={!d.bookingOn && !portal.bostaConnected
          ? t('settings.portal.booking.needsBosta')
          : !d.bookingOn && !hasLocation ? t('settings.portal.booking.needsLocation') : undefined}
        bookingError={errors.pickupBooking}
        onBooking={v => {
          // Exchanges are booked through pickup booking — switching it off switches them off,
          // unless they're on for other couriers' orders too (G4b fix; the backend agrees).
          setPortal(!v && !otherCouriersOn ? { bookingOn: v, exchangesOn: false } : { bookingOn: v })
          clearFieldError('pickupBooking')
        }}
        savedLocationName={portal.returnLocationName}
        locationId={form.returnLocationId}
        onLocation={onLocation}
        locationError={errors.returnLocationId}
        edited={{ autoApprove: has('portal.autoApprove'), booking: has('portal.pickupBooking'), location: has('portal.returnLocationId') }}
      />

      {/* Shopify-native returns S5a — "Create returns in Shopify" (rollout-listed tenants only; saves right away). */}
      <ShopifyReturnsSyncCard owner={owner} />

      {/* Step 5c — "Allow exchanges": only with pickup booking on, Bosta connected and a return location —
          or (G4b fix) with returns for other couriers' orders on. */}
      <ExchangesSection
        exchangesOn={d.exchangesOn}
        onExchanges={v => { setPortal({ exchangesOn: v }); clearFieldError('exchangesEnabled') }}
        description={t(otherCouriersOn ? 'settings.portal.exchanges.descriptionOtherCouriers' : 'settings.portal.exchanges.description')}
        disabled={!d.exchangesOn && !otherCouriersOn && !(d.bookingOn && portal.bostaConnected && hasLocation)}
        note={d.exchangesOn
          ? (otherCouriersShown && !otherCouriersOn && !bostaExchangePath ? t('settings.portal.exchanges.noOrdersEligible') : undefined)
          : otherCouriersOn ? undefined
          : !portal.bostaConnected ? t(otherCouriersShown ? 'settings.portal.exchanges.needsBostaOrOtherCouriers' : 'settings.portal.exchanges.needsBosta')
            : !hasLocation ? t('settings.portal.exchanges.needsLocation')
              : !d.bookingOn ? t('settings.portal.exchanges.needsBooking') : undefined}
        error={errors.exchangesEnabled}
        edited={has('portal.exchangesEnabled')}
        fees={feesProps}
      />

      <RefundsFeesSection
        refundMethods={d.refundMethods}
        onRefundMethods={v => { setPortal({ refundMethods: v }); clearFieldError('refundMethods') }}
        methodsEdited={has('portal.refundMethods')}
        fees={feesProps}
      />

      <BrandingSection
        saved={portal}
        uploadedLogoUrl={uploadedLogoUrl}
        onLogoSaved={s.onLogoSaved}
        logoUrl={form.logoUrl ?? ''}
        onLogoUrl={v => setForm('logoUrl', v)}
        font={d.font}
        onFont={f => { setPortal({ font: f }); clearFieldError('font') }}
        brandColor={form.brandColor ?? ''}
        onBrandColor={v => setForm('brandColor', v)}
        policyText={form.policyText ?? ''}
        onPolicyText={v => setForm('policyText', v)}
        windowDays={windowDays}
        errors={errors}
        edited={{ logoUrl: has('portal.logoUrl'), font: has('portal.font'), brandColor: has('portal.brandColor'),
          policyText: has('portal.policyText') }}
      />

      <SaveBar count={s.edited.length} saving={s.saving} failure={s.failure} blocked={s.clientErrors.length > 0}
        onDiscard={s.discard} onSave={s.save} />
      {leaveDialog}
    </div>
  )
}

function isFieldError(k: string): k is PortalFieldError {
  return ['slug', 'returnWindowDays', 'logoUrl', 'brandColor', 'policyText', 'returnLocationId', 'pickupBooking',
    'exchangesEnabled', 'font', 'refundMethods'].includes(k)
}
