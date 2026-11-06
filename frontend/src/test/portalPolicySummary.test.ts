import { describe, expect, test } from 'vitest'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { describeFee } from '../pages/settings/CustomerFeesSection'
import {
  FeeRuleSummary, PolicyInput, policySummary, priceModeOf, renderPolicyLines,
} from '../pages/settings/portal/policySummary'

// Returns portal redesign Pass A — "Your policy in plain words" (pages/settings/portal/policySummary.ts).
//   flag     — fees null (traced.portal-fees.settings-enabled off for the store): no fee or exchange-pricing sentence
//   modes    — each price mode, including the stored conflicting state (same-or-lower AND a cap → "lower")
//   exchange — another size / any product × free swaps; off
//   approval — manual / auto × booking
//   EN + AR  — whole sentences from one template each, Arabic plural forms for the window, the methods list joined
//              by the language's own "or"

function i18n(lang: 'en' | 'ar') {
  const i = i18next.createInstance()
  i.init({ lng: lang, fallbackLng: 'en', initImmediate: false, resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false } })
  return i
}

const rule = (r: Partial<FeeRuleSummary> = {}): FeeRuleSummary => ({
  enabled: true, mode: 'fixed', amount: 50, percent: null, minAmount: null, maxAmount: null, waiveMerchantFault: true, ...r,
})

const BASE: PolicyInput = {
  enabled: true, windowDays: 14, otherCouriers: null, requirePhotos: false, autoApprove: false, pickupBooking: true,
  exchanges: true, refundMethods: ['instapay', 'bank_transfer'], fees: null,
}

const FEES = (f: Partial<NonNullable<PolicyInput['fees']>> = {}): NonNullable<PolicyInput['fees']> => ({
  anyProduct: false, freeVariantSwaps: true, sameOrLowerOnly: false, maxDifference: null,
  rules: { exchange: null, refund: null }, ...f,
})

const keys = (s: PolicyInput) => policySummary(s).map(l => l.key)

function sentences(s: PolicyInput, lang: 'en' | 'ar') {
  const i = i18n(lang)
  return renderPolicyLines(policySummary(s), i.t.bind(i), lang, describeFee)
}

describe('policySummary — keys', () => {
  test('flag off: no fee or pricing sentence, whatever the rest', () => {
    for (const exchanges of [true, false]) {
      const k = keys({ ...BASE, exchanges, fees: null })
      expect(k.filter(x => /^(fee|noFees|price|swaps|exchangeAny|exchangeSameFree)/.test(x))).toEqual([])
    }
    expect(keys({ ...BASE, fees: null })).toEqual(['window', 'manualBooking', 'exchangeSame', 'refundMethods'])
  })

  test('flag on: the fee sentences appear; no rule on → "no fees"', () => {
    expect(keys({ ...BASE, fees: FEES() })).toContain('noFees')
    const k = keys({ ...BASE, fees: FEES({ rules: { exchange: rule(), refund: rule({ waiveMerchantFault: false }) } }) })
    expect(k).toContain('feeExchangeWaived')
    expect(k).toContain('feeRefund')
    expect(k).not.toContain('noFees')
    // A rule that is off says nothing.
    expect(keys({ ...BASE, fees: FEES({ rules: { exchange: rule({ enabled: false }), refund: null } }) })).toContain('noFees')
  })

  test.each([
    ['nolimit', { sameOrLowerOnly: false, maxDifference: null }, 'priceNoLimit'],
    ['cap', { sameOrLowerOnly: false, maxDifference: 300 }, 'priceCap'],
    ['lower', { sameOrLowerOnly: true, maxDifference: null }, 'priceLower'],
    // The stored conflicting state behaves exactly like "lower" (PortalAmounts.exchangeBlock checks it first).
    ['conflicting → lower', { sameOrLowerOnly: true, maxDifference: 300 }, 'priceLower'],
  ] as const)('price mode %s', (_, stored, key) => {
    expect(priceModeOf(stored)).toBe(key === 'priceNoLimit' ? 'nolimit' : key === 'priceCap' ? 'cap' : 'lower')
    const k = keys({ ...BASE, fees: FEES({ anyProduct: true, ...stored }) })
    expect(k.filter(x => x.startsWith('price'))).toEqual([key])
  })

  test('the cap amount travels with the sentence', () => {
    const line = policySummary({ ...BASE, fees: FEES({ anyProduct: true, maxDifference: 300 }) }).find(l => l.key === 'priceCap')
    expect(line?.amount).toBe(300)
  })

  test('exchanges: same product + free swaps is one sentence (no price); any product says swaps are free', () => {
    expect(keys({ ...BASE, fees: FEES({ anyProduct: false, freeVariantSwaps: true }) }).filter(x => /exchange|price|swaps/.test(x)))
      .toEqual(['exchangeSameFree'])
    expect(keys({ ...BASE, fees: FEES({ anyProduct: false, freeVariantSwaps: false }) }).filter(x => /exchange|price|swaps/.test(x)))
      .toEqual(['exchangeSame', 'priceNoLimit'])
    expect(keys({ ...BASE, fees: FEES({ anyProduct: true, freeVariantSwaps: true }) }).filter(x => /exchange|price|swaps/.test(x)))
      .toEqual(['exchangeAny', 'swapsFree', 'priceNoLimit'])
    expect(keys({ ...BASE, fees: FEES({ anyProduct: true, freeVariantSwaps: false }) }).filter(x => /exchange|price|swaps/.test(x)))
      .toEqual(['exchangeAny', 'priceNoLimit'])
    expect(keys({ ...BASE, exchanges: false, fees: FEES({ anyProduct: true }) }).filter(x => /exchange|price|swaps/.test(x)))
      .toEqual(['exchangesOff'])
  })

  test('approval × booking', () => {
    expect(keys({ ...BASE, autoApprove: false, pickupBooking: false })).toContain('manual')
    expect(keys({ ...BASE, autoApprove: false, pickupBooking: true })).toContain('manualBooking')
    expect(keys({ ...BASE, autoApprove: true, pickupBooking: false })).toContain('auto')
    expect(keys({ ...BASE, autoApprove: true, pickupBooking: true })).toContain('autoBooking')
  })

  test('off first; window with other couriers; photos; no window while the field is invalid; no methods', () => {
    expect(keys({ ...BASE, enabled: false })[0]).toBe('off')
    expect(keys({ ...BASE, otherCouriers: true })).toContain('windowOtherCouriers')
    expect(keys({ ...BASE, otherCouriers: false })).toContain('window')
    expect(keys({ ...BASE, requirePhotos: true })).toContain('photos')
    expect(keys({ ...BASE, windowDays: null }).some(k => k.startsWith('window'))).toBe(false)
    expect(keys({ ...BASE, refundMethods: [] })).toContain('refundMethodsNone')
  })
})

describe('policySummary — sentences', () => {
  test('EN', () => {
    const s = sentences({ ...BASE, requirePhotos: true, fees: FEES({ anyProduct: true, maxDifference: 300,
      rules: { exchange: rule({ amount: 50 }), refund: rule({ mode: 'percent', amount: null, percent: 10, minAmount: 20, waiveMerchantFault: false }) } }) }, 'en')
    expect(s).toEqual([
      'Customers can start a return within 14 days of delivery.',
      'Customers add 1–3 photos of each item.',
      'You review every request, and Traced books a Bosta pickup when you approve.',
      'Customers can exchange for any product in stock.',
      'Size and colour swaps of the same product are free.',
      'When the new item costs more, they pay the difference on delivery — up to 300 EGP.',
      'Customers choose how to get their money back: bank transfer or InstaPay.',
      'An exchange fee of EGP 50 applies, except when it\'s the store\'s fault.',
      'A return fee of 10% (min EGP 20) is taken off the refund.',
    ])
    expect(sentences({ ...BASE, windowDays: 1 }, 'en')[0]).toBe('Customers can start a return within 1 day of delivery.')
  })

  test('AR: whole sentences, Arabic plural forms for the window, the methods joined by "أو"', () => {
    const s = sentences({ ...BASE, autoApprove: true, otherCouriers: true, fees: FEES({ sameOrLowerOnly: true, freeVariantSwaps: false }) }, 'ar')
    expect(s).toEqual([
      'يمكن للعملاء بدء الإرجاع خلال 14 يومًا من التوصيل، بما في ذلك الطلبات المشحونة مع شركات شحن أخرى.',
      'تتم الموافقة على الطلبات تلقائيًا، ويحجز Traced استلامًا مع بوسطة فورًا.',
      'يمكن للعملاء الاستبدال بمقاس أو لون آخر من نفس المنتج.',
      'يمكن للعميل اختيار منتج بنفس السعر أو أقل فقط.',
      'يختار العملاء طريقة استرداد أموالهم: تحويل بنكي أو إنستاباي.',
      'لا يدفع العملاء أي رسوم.',
    ])
    const window = (n: number) => sentences({ ...BASE, windowDays: n }, 'ar')[0]
    expect(window(1)).toBe('يمكن للعملاء بدء الإرجاع خلال يوم واحد من التوصيل.')
    expect(window(2)).toBe('يمكن للعملاء بدء الإرجاع خلال يومين من التوصيل.')
    expect(window(7)).toBe('يمكن للعملاء بدء الإرجاع خلال 7 أيام من التوصيل.')
    expect(window(30)).toBe('يمكن للعملاء بدء الإرجاع خلال 30 يومًا من التوصيل.')
    expect(window(100)).toBe('يمكن للعملاء بدء الإرجاع خلال 100 يوم من التوصيل.')
  })

  test('every key has a template in both languages (no raw key on screen)', () => {
    const all: PolicyInput[] = [
      { ...BASE, enabled: false, otherCouriers: true, requirePhotos: true, autoApprove: true, refundMethods: [] },
      { ...BASE, exchanges: false, fees: FEES() },
      { ...BASE, fees: FEES({ anyProduct: true, maxDifference: 5, rules: { exchange: rule({ waiveMerchantFault: false }), refund: rule() } }) },
      { ...BASE, fees: FEES({ freeVariantSwaps: false, sameOrLowerOnly: true }) },
    ]
    for (const lang of ['en', 'ar'] as const) {
      for (const input of all) {
        for (const s of sentences(input, lang)) expect(s, `${lang}: ${s}`).not.toMatch(/settings\.portal|{{/)
      }
    }
  })
})
