import type {
  CustomerFeeSettings, ExchangePricing, FeeKind, FeeMode, FeeRule, FeeRuleInput, OtherCouriersReturnsSetting,
  PortalSettings, PortalSettingsInput, PortalVariantRow,
} from '../../../api'
import { PortalFont, portalFontOrDefault } from '../../../portal/fonts'
import { PriceMode, priceModeOf } from './policySummary'

/**
 * Settings → Returns portal, redesign Pass B — ONE "Save changes" for the whole tab.
 *
 * Pure: the draft and saved shapes, which fields are edited, the ordered list of calls a save makes, how a run stops
 * at the first failure, and how the calls that succeeded become the new saved state. No React, no fetch —
 * `usePortalSettingsDraft` wires it to the endpoints.
 *
 * Order (decided in review): other couriers → portal settings → exchange pricing → exchange fee → return fee →
 * store's-fault reasons → products (only the rows that changed, one call each). A step runs only when its fields
 * changed. A manager's plan has no owner-only call (other couriers, every fee call); with fees hidden for the store
 * there is no fee or pricing call. Logo upload / remove and area fees are NOT here — they save the moment they're used.
 */

export const FEE_KINDS: FeeKind[] = ['exchange', 'refund']

// ── shapes ──────────────────────────────────────────────────────────────────────

export interface SavedState {
  portal: PortalSettings
  /** null: the store doesn't see the other-couriers switch. */
  otherCouriers: OtherCouriersReturnsSetting | null
  /** null: not loaded / failed; `visible:false`: fees hidden for this store. */
  fees: CustomerFeeSettings | null
}

export interface PortalDraft {
  form: PortalSettingsInput
  windowText: string
  bookingOn: boolean
  exchangesOn: boolean
  font: PortalFont
  refundMethods: string[]
  requirePhotos: boolean
}

export interface PricingDraft {
  anyProduct: boolean
  freeVariantSwaps: boolean
  /** "Same-product swaps can refund the customer" (V223). */
  sameProductSwapRefunds: boolean
  priceMode: PriceMode
  /** The cap field's text (kept while another mode is chosen). */
  capText: string
}

export interface RuleDraft {
  enabled: boolean
  mode: FeeMode
  amount: string
  percent: string
  min: string
  max: string
}

export interface Draft {
  portal: PortalDraft
  otherCouriers: boolean
  pricing: PricingDraft
  rules: Record<FeeKind, RuleDraft>
  waiver: boolean
  reasons: string[]
  /** Products whose "can't be returned" flag is being changed: variant id → the row and the new value. */
  variants: Record<string, { row: PortalVariantRow; value: boolean }>
}

// ── numbers ─────────────────────────────────────────────────────────────────────

/** '' (or anything that isn't a number) → null; else the number (the backend validates it). */
export function num(s: string | null | undefined): number | null {
  const v = (s ?? '').trim()
  if (v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** A stored amount as the field's text; '' for none. Tolerates a number sent as a string. */
export function str(n: number | string | null | undefined): string {
  if (n == null) return ''
  const v = typeof n === 'number' ? n : Number(n)
  return Number.isFinite(v) ? String(v) : ''
}

function numOrNull(n: number | string | null | undefined): number | null {
  return n == null ? null : num(String(n))
}

export function modeOf(m: unknown): FeeMode { return m === 'percent' ? 'percent' : 'fixed' }

// ── refund methods ──────────────────────────────────────────────────────────────

export const PORTAL_REFUND_METHODS = ['bank_transfer', 'instapay', 'wallet', 'cash'] as const

export function orderedMethods(v: string[]): string[] {
  return PORTAL_REFUND_METHODS.filter(m => v.includes(m))
}

export function sameMethods(a: string[], b: string[]): boolean {
  return orderedMethods(a).join() === orderedMethods(b).join()
}

// ── from saved ──────────────────────────────────────────────────────────────────

export function toInput(s: PortalSettings): PortalSettingsInput {
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

export function portalDraftOf(s: PortalSettings): PortalDraft {
  return {
    form: toInput(s),
    windowText: String(s.returnWindowDays),
    bookingOn: !!s.portalPickupBooking,
    exchangesOn: !!s.exchangesEnabled,
    font: portalFontOrDefault(s.font),
    refundMethods: s.refundMethods ?? [],
    requirePhotos: !!s.requirePhotos,
  }
}

export function pricingDraftOf(p: ExchangePricing): PricingDraft {
  return {
    anyProduct: !!p.anyProduct,
    freeVariantSwaps: !!p.freeVariantSwaps,
    sameProductSwapRefunds: !!p.sameProductSwapRefunds,
    priceMode: priceModeOf({ sameOrLowerOnly: !!p.sameOrLowerOnly, maxDifference: numOrNull(p.maxDifference) }),
    capText: str(p.maxDifference),
  }
}

export function ruleDraftOf(r: FeeRule | null): RuleDraft {
  return {
    enabled: r?.enabled ?? false,
    mode: modeOf(r?.mode),
    amount: str(r?.amount),
    percent: str(r?.percent),
    min: str(r?.minAmount),
    max: str(r?.maxAmount),
  }
}

/** The merged waiver as stored: on only when every existing rule waives (no rule → on, the old default). */
export function savedWaiver(rules: Record<FeeKind, FeeRule | null> | undefined): boolean {
  const existing = FEE_KINDS.map(k => rules?.[k]).filter((r): r is FeeRule => !!r)
  return existing.every(r => r.waiveMerchantFault)
}

/** Some existing rules waive and some don't — shown off, with a note; a save makes them match. */
export function waiverMixed(rules: Record<FeeKind, FeeRule | null> | undefined): boolean {
  const existing = FEE_KINDS.map(k => rules?.[k]).filter((r): r is FeeRule => !!r)
  return existing.some(r => r.waiveMerchantFault) && existing.some(r => !r.waiveMerchantFault)
}

const NO_PRICING: ExchangePricing = { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: false }

/** The fee parts of a draft, from the stored fee settings (or none). */
export function feesDraftOf(fees: CustomerFeeSettings | null): Pick<Draft, 'pricing' | 'rules' | 'waiver' | 'reasons'> {
  return {
    pricing: pricingDraftOf(fees?.exchangePricing ?? NO_PRICING),
    rules: { exchange: ruleDraftOf(fees?.rules?.exchange ?? null), refund: ruleDraftOf(fees?.rules?.refund ?? null) },
    waiver: savedWaiver(fees?.rules),
    reasons: fees?.merchantFaultReasons ?? [],
  }
}

export function draftOf(saved: SavedState): Draft {
  return {
    portal: portalDraftOf(saved.portal),
    otherCouriers: !!saved.otherCouriers?.enabled,
    ...feesDraftOf(saved.fees),
    variants: {},
  }
}

// ── writes ──────────────────────────────────────────────────────────────────────

/** lower → (true, null), cap → (false, value), nolimit → (false, null). */
export function priceWrite(mode: PriceMode, capText: string): { sameOrLowerOnly: boolean; maxDifference: number | null } {
  if (mode === 'lower') return { sameOrLowerOnly: true, maxDifference: null }
  if (mode === 'cap') return { sameOrLowerOnly: false, maxDifference: num(capText) }
  return { sameOrLowerOnly: false, maxDifference: null }
}

/** The PUT body. {@code sameProductSwapRefunds} is sent only when it differs from {@code saved} (absent = kept). */
export function pricingBody(p: PricingDraft, saved?: ExchangePricing): ExchangePricing {
  const body: ExchangePricing = { anyProduct: p.anyProduct, ...priceWrite(p.priceMode, p.capText), freeVariantSwaps: p.freeVariantSwaps }
  if (saved && p.sameProductSwapRefunds !== !!saved.sameProductSwapRefunds) body.sameProductSwapRefunds = p.sameProductSwapRefunds
  return body
}

export function ruleBody(d: RuleDraft, waive: boolean): FeeRuleInput {
  return {
    enabled: d.enabled, mode: d.mode, waiveMerchantFault: waive,
    amount: d.mode === 'fixed' ? num(d.amount) : null,
    percent: d.mode === 'percent' ? num(d.percent) : null,
    minAmount: d.mode === 'percent' ? num(d.min) : null,
    maxAmount: d.mode === 'percent' ? num(d.max) : null,
  }
}

/** What PUT /rules/{kind} accepts (CustomerFeeSettingsService.shape, zeroAllowed false) — checked even when the fee is off. */
export function ruleAmountValid(d: RuleDraft): boolean {
  const whole = (n: number | null, zero: boolean) => n != null && Number.isInteger(n) && (zero ? n >= 0 : n > 0) && n <= 30000
  if (d.mode === 'fixed') return whole(num(d.amount), false)
  const p = num(d.percent)
  if (p == null || p <= 0 || p > 100 || Math.round(p * 100) !== p * 100) return false
  const min = num(d.min), max = num(d.max)
  if ((d.min.trim() !== '' && !whole(min, true)) || (d.max.trim() !== '' && !whole(max, true))) return false
  return !(min != null && max != null && min > max)
}

/** The body PUT /tenant/portal-settings gets — exactly as the tab sent it before Pass B. */
export function portalBody(saved: PortalSettings, d: PortalDraft): PortalSettingsInput {
  const form = d.form
  return {
    ...form,
    slug: form.slug?.trim() ? form.slug.trim() : null,
    returnWindowDays: Number(d.windowText),
    logoUrl: form.logoUrl?.trim() ? form.logoUrl.trim() : null,
    brandColor: form.brandColor?.trim() ? form.brandColor.trim() : null,
    policyText: form.policyText?.trim() ? form.policyText : null,
    ...(d.bookingOn !== !!saved.portalPickupBooking ? { pickupBooking: d.bookingOn } : {}),
    ...(d.exchangesOn !== !!saved.exchangesEnabled ? { exchangesEnabled: d.exchangesOn } : {}),
    ...(d.font !== portalFontOrDefault(saved.font) ? { font: d.font } : {}),
    ...(!sameMethods(d.refundMethods, saved.refundMethods ?? []) ? { refundMethods: orderedMethods(d.refundMethods) } : {}),
    ...(d.requirePhotos !== !!saved.requirePhotos ? { requirePhotos: d.requirePhotos } : {}),
  }
}

// ── what's edited ───────────────────────────────────────────────────────────────

export type PortalField = 'slug' | 'enabled' | 'autoApprove' | 'returnWindowDays' | 'logoUrl' | 'brandColor' | 'policyText'
  | 'returnLocationId' | 'pickupBooking' | 'exchangesEnabled' | 'font' | 'refundMethods' | 'requirePhotos'

export type PricingKey = 'pricing.swapTo' | 'pricing.freeSwaps' | 'pricing.sameProductRefunds' | 'pricing.priceMode'

export type EditedKey = `portal.${PortalField}` | 'otherCouriers' | PricingKey
  | 'rule.exchange' | 'rule.refund' | 'waiver' | 'reasons' | `variant.${string}`

const blank = (v: string | null | undefined) => (v && v.trim() ? v.trim() : null)

export function portalEdited(saved: PortalSettings, d: PortalDraft): PortalField[] {
  const f = d.form
  const out: PortalField[] = []
  if ((blank(f.slug)?.toLowerCase() ?? null) !== (blank(saved.slug)?.toLowerCase() ?? null)) out.push('slug')
  if (f.enabled !== saved.enabled) out.push('enabled')
  if (f.autoApprove !== saved.autoApprove) out.push('autoApprove')
  if (Number(d.windowText) !== saved.returnWindowDays) out.push('returnWindowDays')
  if (blank(f.logoUrl) !== blank(saved.logoUrl)) out.push('logoUrl')
  if (blank(f.brandColor) !== blank(saved.brandColor)) out.push('brandColor')
  if (blank(f.policyText) !== blank(saved.policyText)) out.push('policyText')
  if ((f.returnLocationId ?? null) !== (saved.returnLocationId ?? null)) out.push('returnLocationId')
  if (d.bookingOn !== !!saved.portalPickupBooking) out.push('pickupBooking')
  if (d.exchangesOn !== !!saved.exchangesEnabled) out.push('exchangesEnabled')
  if (d.font !== portalFontOrDefault(saved.font)) out.push('font')
  if (!sameMethods(d.refundMethods, saved.refundMethods ?? [])) out.push('refundMethods')
  if (d.requirePhotos !== !!saved.requirePhotos) out.push('requirePhotos')
  return out
}

export function ruleEdited(saved: FeeRule | null, d: RuleDraft): boolean {
  if (!saved) return d.enabled || d.amount.trim() !== '' || d.percent.trim() !== ''
  const b = ruleBody(d, saved.waiveMerchantFault)
  return b.enabled !== saved.enabled || b.mode !== modeOf(saved.mode)
    || b.amount !== numOrNull(saved.amount) || b.percent !== numOrNull(saved.percent)
    || b.minAmount !== numOrNull(saved.minAmount) || b.maxAmount !== numOrNull(saved.maxAmount)
}

export function pricingEdited(saved: ExchangePricing, d: PricingDraft): PricingKey[] {
  const s = pricingDraftOf(saved)
  const out: PricingKey[] = []
  if (d.anyProduct !== s.anyProduct) out.push('pricing.swapTo')
  if (d.freeVariantSwaps !== s.freeVariantSwaps) out.push('pricing.freeSwaps')
  if (d.sameProductSwapRefunds !== s.sameProductSwapRefunds) out.push('pricing.sameProductRefunds')
  // Compared as modes, not as the written pair: a stored conflicting state (lower AND a cap) reads "lower" and is not
  // edited until something is changed — saving then writes (true, null).
  if (d.priceMode !== s.priceMode || (d.priceMode === 'cap' && num(d.capText) !== numOrNull(saved.maxDifference))) {
    out.push('pricing.priceMode')
  }
  return out
}

/** The rules the merged waiver is written into: stored or being created now, with an amount PUT accepts. */
export function waiverTakers(fees: CustomerFeeSettings, d: Draft): FeeKind[] {
  return FEE_KINDS.filter(k => (!!fees.rules[k] || ruleEdited(fees.rules[k], d.rules[k])) && ruleAmountValid(d.rules[k]))
}

function sameReasons(a: string[], b: string[]) {
  return a.length === b.length && a.every(r => b.includes(r))
}

export function feesShown(saved: SavedState): boolean {
  return !!saved.fees?.visible
}

/** Every edited field, in screen order. Its length is the bar's "N unsaved changes". */
export function editedKeys(saved: SavedState, d: Draft): EditedKey[] {
  const out: EditedKey[] = portalEdited(saved.portal, d.portal).map(f => `portal.${f}` as EditedKey)
  if (saved.otherCouriers && d.otherCouriers !== !!saved.otherCouriers.enabled) out.unshift('otherCouriers')
  const fees = saved.fees
  if (fees?.visible) {
    out.push(...pricingEdited(fees.exchangePricing, d.pricing))
    for (const k of FEE_KINDS) if (ruleEdited(fees.rules[k], d.rules[k])) out.push(`rule.${k}`)
    if (d.waiver !== savedWaiver(fees.rules) && waiverTakers(fees, d).length) out.push('waiver')
    if (!sameReasons(d.reasons, fees.merchantFaultReasons)) out.push('reasons')
  }
  for (const [id, v] of Object.entries(d.variants)) if (v.value !== v.row.nonReturnable) out.push(`variant.${id}`)
  return out
}

// ── the plan ────────────────────────────────────────────────────────────────────

export type Section = 'otherCouriers' | 'portal' | 'pricing' | 'ruleExchange' | 'ruleRefund' | 'reasons' | 'variants'

export type SaveStep =
  | { section: 'otherCouriers'; kind: 'otherCouriers'; enabled: boolean }
  | { section: 'portal'; kind: 'portal'; body: PortalSettingsInput }
  | { section: 'pricing'; kind: 'pricing'; body: ExchangePricing }
  | { section: 'ruleExchange' | 'ruleRefund'; kind: 'rule'; fee: FeeKind; body: FeeRuleInput }
  | { section: 'reasons'; kind: 'reasons'; reasons: string[] }
  | { section: 'variants'; kind: 'variant'; row: PortalVariantRow; value: boolean }

/** A problem the browser can see before sending anything; it blocks the whole save. */
export type ClientError = { field: 'cap'; code: 'CAP_REQUIRED' }

export interface SavePlan { steps: SaveStep[]; errors: ClientError[] }

export function buildSavePlan(saved: SavedState, d: Draft, role: 'owner' | 'manager', reasonOrder: readonly string[]): SavePlan {
  const owner = role === 'owner'
  const fees = owner && saved.fees?.visible ? saved.fees : null
  const steps: SaveStep[] = []
  const errors: ClientError[] = []

  if (owner && saved.otherCouriers && d.otherCouriers !== !!saved.otherCouriers.enabled) {
    steps.push({ section: 'otherCouriers', kind: 'otherCouriers', enabled: d.otherCouriers })
  }
  if (portalEdited(saved.portal, d.portal).length) {
    steps.push({ section: 'portal', kind: 'portal', body: portalBody(saved.portal, d.portal) })
  }
  if (fees) {
    if (pricingEdited(fees.exchangePricing, d.pricing).length) {
      if (d.pricing.priceMode === 'cap' && d.pricing.capText.trim() === '') errors.push({ field: 'cap', code: 'CAP_REQUIRED' })
      steps.push({ section: 'pricing', kind: 'pricing', body: pricingBody(d.pricing, fees.exchangePricing) })
    }
    const takers = waiverTakers(fees, d)
    for (const k of FEE_KINDS) {
      const s = fees.rules[k]
      const r = d.rules[k]
      const edited = ruleEdited(s, r)
      // The merged waiver goes only into rules that exist (stored, or being created now) and whose amount PUT accepts.
      const takesWaiver = takers.includes(k)
      const waiverDiffers = takesWaiver && (!s || s.waiveMerchantFault !== d.waiver)
      if (!edited && !waiverDiffers) continue
      const waive = takesWaiver ? d.waiver : (s?.waiveMerchantFault ?? d.waiver)
      steps.push({ section: k === 'exchange' ? 'ruleExchange' : 'ruleRefund', kind: 'rule', fee: k, body: ruleBody(r, waive) })
    }
    if (!sameReasons(d.reasons, fees.merchantFaultReasons)) {
      steps.push({ section: 'reasons', kind: 'reasons', reasons: reasonOrder.filter(r => d.reasons.includes(r)) })
    }
  }
  for (const v of Object.values(d.variants)) {
    if (v.value !== v.row.nonReturnable) steps.push({ section: 'variants', kind: 'variant', row: v.row, value: v.value })
  }
  return { steps, errors }
}

// ── running it ──────────────────────────────────────────────────────────────────

export interface StepDone { step: SaveStep; result: unknown }
export interface PlanRun { done: StepDone[]; failed: { step: SaveStep; error: unknown } | null }

/** Runs the steps in order and stops at the first failure. */
export async function runSavePlan(steps: SaveStep[], exec: (s: SaveStep) => Promise<unknown>): Promise<PlanRun> {
  const done: StepDone[] = []
  for (const step of steps) {
    try {
      done.push({ step, result: await exec(step) })
    } catch (error) {
      return { done, failed: { step, error } }
    }
  }
  return { done, failed: null }
}

/**
 * The steps that succeeded become saved, and their part of the draft is reset to what the server answered; the failed
 * step and everything after it keep their edits (so they stay "Edited").
 */
export function applyDone(saved: SavedState, draft: Draft, done: StepDone[]): { saved: SavedState; draft: Draft } {
  let s = saved
  let d = draft
  for (const { step, result } of done) {
    switch (step.kind) {
      case 'otherCouriers': {
        const r = result as OtherCouriersReturnsSetting
        s = { ...s, otherCouriers: r }
        d = { ...d, otherCouriers: !!r.enabled }
        break
      }
      case 'portal': {
        const r = result as PortalSettings
        s = { ...s, portal: { ...r, logo: r.logo !== undefined ? r.logo : s.portal.logo } }
        d = { ...d, portal: portalDraftOf(r) }
        break
      }
      case 'pricing': {
        const r = result as CustomerFeeSettings
        s = { ...s, fees: r }
        d = { ...d, pricing: pricingDraftOf(r.exchangePricing) }
        break
      }
      case 'rule': {
        const r = result as CustomerFeeSettings
        s = { ...s, fees: r }
        d = { ...d, rules: { ...d.rules, [step.fee]: ruleDraftOf(r.rules[step.fee]) } }
        break
      }
      case 'reasons': {
        const r = result as CustomerFeeSettings
        s = { ...s, fees: r }
        d = { ...d, reasons: r.merchantFaultReasons }
        break
      }
      case 'variant': {
        const variants = { ...d.variants }
        delete variants[step.row.id]
        d = { ...d, variants }
        break
      }
    }
  }
  return { saved: s, draft: d }
}
