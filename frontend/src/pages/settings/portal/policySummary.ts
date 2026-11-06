import type { FeeKind, FeeMode } from '../../../api'

/**
 * Settings → Returns portal — "Your policy in plain words". A pure function from the settings on screen to a list of
 * sentences, each one a whole i18n template (settings.portal.summary.*) with its values — never fragments joined in
 * code, so Arabic reads as written. Fee and exchange-pricing sentences appear only while those settings are shown
 * (`fees` is null when traced.portal-fees.settings-enabled is off for the store: GET customer-fees → visible:false).
 */

export type PriceMode = 'nolimit' | 'cap' | 'lower'

/** The stored pair → one mode: "same or lower" wins over a cap (PortalAmounts.exchangeBlock checks it first). */
export function priceModeOf(p: { sameOrLowerOnly: boolean; maxDifference: number | null }): PriceMode {
  if (p.sameOrLowerOnly) return 'lower'
  return p.maxDifference != null ? 'cap' : 'nolimit'
}

export interface FeeRuleSummary {
  enabled: boolean
  mode: FeeMode
  amount: number | null
  percent: number | null
  minAmount: number | null
  maxAmount: number | null
  waiveMerchantFault: boolean
}

export interface PolicyInput {
  enabled: boolean
  /** The return window being edited; null while the field isn't a valid number. */
  windowDays: number | null
  /** The other-couriers switch; null when the store doesn't see it. */
  otherCouriers: boolean | null
  requirePhotos: boolean
  autoApprove: boolean
  pickupBooking: boolean
  exchanges: boolean
  refundMethods: string[]
  fees: null | {
    anyProduct: boolean
    freeVariantSwaps: boolean
    sameOrLowerOnly: boolean
    maxDifference: number | null
    rules: Record<FeeKind, FeeRuleSummary | null>
  }
}

export type PolicyLine =
  | { key: string; count?: number; amount?: number; methods?: string[]; fee?: FeeRuleSummary }

const METHOD_ORDER = ['bank_transfer', 'instapay', 'wallet', 'cash']

export function policySummary(s: PolicyInput): PolicyLine[] {
  const lines: PolicyLine[] = []
  if (!s.enabled) lines.push({ key: 'off' })

  if (s.windowDays != null) {
    lines.push({ key: s.otherCouriers ? 'windowOtherCouriers' : 'window', count: s.windowDays })
  }
  if (s.requirePhotos) lines.push({ key: 'photos' })

  lines.push({ key: (s.autoApprove ? 'auto' : 'manual') + (s.pickupBooking ? 'Booking' : '') })

  if (!s.exchanges) {
    lines.push({ key: 'exchangesOff' })
  } else if (!s.fees) {
    // Without exchange pricing a store's exchanges are another size or colour of the same product, at no cost.
    lines.push({ key: 'exchangeSame' })
  } else {
    const f = s.fees
    if (!f.anyProduct && f.freeVariantSwaps) {
      lines.push({ key: 'exchangeSameFree' })
    } else {
      lines.push({ key: f.anyProduct ? 'exchangeAny' : 'exchangeSame' })
      if (f.anyProduct && f.freeVariantSwaps) lines.push({ key: 'swapsFree' })
      const mode = priceModeOf(f)
      lines.push(mode === 'lower' ? { key: 'priceLower' }
        : mode === 'cap' ? { key: 'priceCap', amount: f.maxDifference ?? 0 }
          : { key: 'priceNoLimit' })
    }
  }

  const methods = METHOD_ORDER.filter(m => s.refundMethods.includes(m))
  lines.push(methods.length ? { key: 'refundMethods', methods } : { key: 'refundMethodsNone' })

  if (s.fees) {
    const on = (['exchange', 'refund'] as FeeKind[]).filter(k => s.fees!.rules[k]?.enabled)
    if (on.length === 0) lines.push({ key: 'noFees' })
    for (const k of on) {
      const rule = s.fees.rules[k]!
      const base = k === 'exchange' ? 'feeExchange' : 'feeRefund'
      lines.push({ key: base + (rule.waiveMerchantFault ? 'Waived' : ''), fee: rule })
    }
  }
  return lines
}

type T = (key: string, options?: Record<string, unknown>) => string

/**
 * The sentences in the current language. `describeFee` formats a rule as a value ("EGP 50", "10% (min EGP 20)");
 * the list of refund methods is joined by the browser's own list rules for the language ("a, b or c" / "أ أو ب").
 */
export function renderPolicyLines(lines: PolicyLine[], t: T, lang: string,
                                  describeFee: (f: FeeRuleSummary, t: T) => string): string[] {
  return lines.map(l => {
    const key = `settings.portal.summary.${l.key}`
    if (l.methods) {
      const names = l.methods.map(m => t(`settings.portal.summary.methodNames.${m}`))
      return t(key, { methods: joinOr(names, lang) })
    }
    if (l.fee) return t(key, { fee: describeFee(l.fee, t) })
    if (l.count != null) return t(key, { count: l.count })
    if (l.amount != null) return t(key, { amount: l.amount })
    return t(key)
  })
}

function joinOr(items: string[], lang: string): string {
  // Intl.ListFormat is ES2021 (every browser the app supports); the project's TS lib predates it.
  const LF = (Intl as unknown as { ListFormat?: new (l: string, o: object) => { format: (i: string[]) => string } }).ListFormat
  try {
    if (LF) return new LF(lang === 'ar' ? 'ar' : 'en', { style: 'long', type: 'disjunction' }).format(items)
    return items.join(', ')
  } catch {
    return items.join(', ')
  }
}
