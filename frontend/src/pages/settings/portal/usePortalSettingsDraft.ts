import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CustomerFeeSettings, OtherCouriersReturnsSetting, PortalSettings, PortalSettingsError, PortalVariantRow, TransferCommandError, getCustomerFees,
  getOtherCouriersReturns, getPortalSettings, getPortalVariants, getRoleFromToken, saveExchangePricing, saveFeeRule,
  saveMerchantFaultReasons, savePortalSettings, setOtherCouriersReturns, setVariantNonReturnable,
} from '../../../api'
import { useCapabilities } from '../../../capabilities'
import { useToast } from '../../../components/ui'
import {
  ClientError, Draft, EditedKey, SaveStep, SavedState, Section, applyDone, buildSavePlan, draftOf, editedKeys,
  feesDraftOf, runSavePlan,
} from './savePlan'

/** The portal's return reasons, in the portal's order (PortalService.REASON_CODES). */
export const REASON_CODES = ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'] as const

export const EXCLUDED_PAGE = 25

const PORTAL_FIELDS = ['slug', 'returnWindowDays', 'logoUrl', 'brandColor', 'policyText', 'returnLocationId', 'pickupBooking',
  'exchangesEnabled', 'font', 'refundMethods'] as const
export type PortalFieldError = typeof PORTAL_FIELDS[number]

export interface SaveFailure {
  section: Section
  message: string
  /** At least one step before it was saved. */
  partial: boolean
}

export interface ExcludedList { items: PortalVariantRow[]; total: number }

/**
 * Returns portal redesign Pass B — one draft and one saved snapshot for every field the tab edits, and the one save
 * (savePlan.ts). The logo and area fees are not drafted: they save the moment they're used and update the saved
 * snapshot only.
 */
export function usePortalSettingsDraft() {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const { genericCourier } = useCapabilities()
  const role: 'owner' | 'manager' = getRoleFromToken() === 'owner' ? 'owner' : 'manager'

  const [portal, setPortal] = useState<PortalSettings | null>(null)
  const [fees, setFees] = useState<CustomerFeeSettings | null>(null)
  const [otherCouriers, setOtherCouriers] = useState<OtherCouriersReturnsSetting | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [excluded, setExcluded] = useState<ExcludedList | null>(null)
  const [excludedError, setExcludedError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<SaveFailure | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<PortalFieldError, string>>>({})
  const [sectionErrors, setSectionErrors] = useState<Partial<Record<Section, string>>>({})
  const [clientErrors, setClientErrors] = useState<ClientError[]>([])

  const saved: SavedState | null = useMemo(
    () => (portal ? { portal, fees, otherCouriers } : null), [portal, fees, otherCouriers])

  const load = useCallback(async () => {
    setLoadError(false)
    try {
      setPortal(await getPortalSettings())
    } catch {
      setLoadError(true)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // The draft starts once the portal settings are in, with whatever else has arrived by then.
  useEffect(() => {
    if (saved && !draft) setDraft(draftOf(saved))
  }, [saved, draft])

  // Fees and the other-couriers switch arrive on their own; the first answer of each also fills its part of the
  // draft (later changes to saved — a save, an area fee — never overwrite the draft).
  useEffect(() => {
    let cancelled = false
    getCustomerFees().then(f => {
      if (cancelled) return
      setFees(f)
      setDraft(d => (d ? feesIntoDraft(d, f) : d))
    }).catch(() => {})
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!genericCourier) return
    let cancelled = false
    getOtherCouriersReturns().then(oc => {
      if (cancelled) return
      setOtherCouriers(oc.available || oc.enabled ? oc : null)
      setDraft(d => (d ? { ...d, otherCouriers: !!oc.enabled } : d))
    }).catch(() => {})
    return () => { cancelled = true }
  }, [genericCourier])

  const loadExcluded = useCallback(async () => {
    setExcludedError(false)
    try {
      const res = await getPortalVariants('', 0, EXCLUDED_PAGE, true)
      setExcluded({ items: res.items, total: res.total })
    } catch {
      setExcludedError(true)
    }
  }, [])
  useEffect(() => { loadExcluded() }, [loadExcluded])

  const loadMoreExcluded = useCallback(async () => {
    if (!excluded) return
    try {
      const res = await getPortalVariants('', Math.floor(excluded.items.length / EXCLUDED_PAGE), EXCLUDED_PAGE, true)
      setExcluded(prev => prev && {
        items: [...prev.items, ...res.items.filter(v => !prev.items.some(p => p.id === v.id))],
        total: res.total,
      })
    } catch {
      toast({ tone: 'error', message: t('settings.portal.products.loadFailed') })
    }
  }, [excluded, toast, t])

  const ready = !!saved && !!draft
  const edited: EditedKey[] = useMemo(() => (saved && draft ? editedKeys(saved, draft) : []), [saved, draft])
  const dirty = edited.length > 0

  const update = useCallback((fn: (d: Draft) => Draft) => {
    setDraft(d => (d ? fn(d) : d))
    setFailure(null)
    setClientErrors([])
  }, [])

  const clearFieldError = useCallback((f: PortalFieldError) => {
    setFieldErrors(e => (e[f] ? { ...e, [f]: undefined } : e))
  }, [])

  const discard = useCallback(() => {
    if (!saved) return
    setDraft(draftOf(saved))
    setFailure(null)
    setFieldErrors({})
    setSectionErrors({})
    setClientErrors([])
  }, [saved])

  function messageFor(step: SaveStep, e: unknown): { message: string; field?: PortalFieldError } {
    if (e instanceof TransferCommandError) return { message: i18n.language === 'ar' ? e.messageAr : e.messageEn }
    if (step.kind === 'portal' && e instanceof PortalSettingsError) {
      if (e.field && (PORTAL_FIELDS as readonly string[]).includes(e.field)) {
        return {
          field: e.field as PortalFieldError,
          message: t(`settings.portal.errors.${e.code ?? 'generic'}`, { defaultValue: t('settings.portal.errors.generic') }),
        }
      }
      return { message: t(e.status === 400 ? 'settings.portal.errors.generic' : 'settings.portal.saveFailed') }
    }
    switch (step.kind) {
      case 'variant': return { message: t('settings.portal.products.saveFailed') }
      case 'otherCouriers': return { message: t('settings.portal.otherCouriers.saveFailed') }
      case 'portal': return { message: t('settings.portal.saveFailed') }
      default: return { message: t('settings.portal.fees.saveFailed') }
    }
  }

  const save = useCallback(async () => {
    if (!saved || !draft || saving) return
    const plan = buildSavePlan(saved, draft, role, REASON_CODES)
    setClientErrors(plan.errors)
    if (plan.errors.length) return
    setSaving(true)
    setFailure(null)
    setFieldErrors({})
    setSectionErrors({})
    const run = await runSavePlan(plan.steps, exec)
    const next = applyDone(saved, draft, run.done)
    // A product's flag changed: the excluded list follows what was saved.
    const variantDone = run.done.filter(d => d.step.kind === 'variant').map(d => d.step as Extract<SaveStep, { kind: 'variant' }>)
    if (variantDone.length) {
      setExcluded(prev => {
        if (!prev) return prev
        let items = prev.items
        let total = prev.total
        for (const s of variantDone) {
          if (s.value && !items.some(v => v.id === s.row.id)) { items = [{ ...s.row, nonReturnable: true }, ...items]; total += 1 }
          if (!s.value && items.some(v => v.id === s.row.id)) { items = items.filter(v => v.id !== s.row.id); total -= 1 }
        }
        return { items, total }
      })
    }
    setPortal(next.saved.portal)
    setFees(next.saved.fees)
    setOtherCouriers(next.saved.otherCouriers)
    setDraft(next.draft)
    setSaving(false)
    if (run.failed) {
      const { message, field } = messageFor(run.failed.step, run.failed.error)
      if (field) setFieldErrors({ [field]: message })
      else setSectionErrors({ [run.failed.step.section]: message })
      setFailure({ section: run.failed.step.section, message, partial: run.done.length > 0 })
    } else {
      toast({ tone: 'success', message: t('settings.portal.saveBar.saved') })
    }
  }, [saved, draft, saving, role, toast, t])   // eslint-disable-line react-hooks/exhaustive-deps

  /** The logo saves on its own (upload / remove): only the saved snapshot's logo changes. */
  const onLogoSaved = useCallback((s: PortalSettings) => {
    setPortal(prev => (prev ? { ...prev, logo: s.logo ?? null } : prev))
  }, [])

  /** Area fees save on their own: the saved fees take the answer; the draft keeps its edits. */
  const onAreaFeesSaved = useCallback((f: CustomerFeeSettings) => {
    setFees(prev => ({ ...(prev ?? f), overrides: f.overrides }))
  }, [])

  return {
    role, saved: ready ? saved : null, draft: ready ? draft : null, loadError, load,
    excluded, excludedError, loadExcluded, loadMoreExcluded,
    edited, dirty, update, discard, save, saving, failure, fieldErrors, clearFieldError, sectionErrors, clientErrors,
    onLogoSaved, onAreaFeesSaved,
  }
}

function exec(step: SaveStep): Promise<unknown> {
  switch (step.kind) {
    case 'otherCouriers': return setOtherCouriersReturns(step.enabled)
    case 'portal': return savePortalSettings(step.body)
    case 'pricing': return saveExchangePricing(step.body)
    case 'rule': return saveFeeRule(step.fee, step.body)
    case 'reasons': return saveMerchantFaultReasons(step.reasons)
    case 'variant': return setVariantNonReturnable(step.row.id, step.value)
  }
}

function feesIntoDraft(d: Draft, fees: CustomerFeeSettings): Draft {
  return { ...d, ...feesDraftOf(fees) }
}
