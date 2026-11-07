import { describe, expect, test } from 'vitest'
import type { CustomerFeeSettings, FeeRule, PortalSettings, PortalVariantRow } from '../api'
import {
  Draft, SavedState, applyDone, buildSavePlan, draftOf, editedKeys, priceWrite, pricingDraftOf, runSavePlan,
  savedWaiver, waiverMixed,
} from '../pages/settings/portal/savePlan'
import { REASON_CODES } from '../pages/settings/portal/usePortalSettingsDraft'

// Returns portal redesign Pass B — savePlan.ts, the pure core of the tab's one "Save changes".
//   order     — other couriers → portal settings → exchange pricing → exchange fee → return fee → reasons → products
//   only-what-changed — a step runs only when its fields changed; products send only the changed rows
//   manager   — no owner-only call (other couriers, every fee call)
//   fees off  — no fee or pricing call
//   price mode — lower / cap / nolimit both ways, the stored conflicting state reads "lower", empty cap blocks, 0 valid
//   waiver    — all on / mixed / no rules; written only into rules that exist with a valid amount
//   run       — stops at the first failure; the steps before it become saved, the rest stay edited

const PORTAL: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: 'loc-1',
  returnLocationName: 'Maadi', bostaConnected: true, exchangesEnabled: false, font: 'cairo', refundMethods: [],
  requirePhotos: false,
}

const rule = (kind: 'exchange' | 'refund', extra: Partial<FeeRule> = {}): FeeRule => ({
  kind, enabled: true, mode: 'fixed', amount: 50, percent: null, minAmount: null, maxAmount: null,
  waiveMerchantFault: true, updatedAt: '2026-11-06T10:00:00Z', ...extra,
})

const FEES = (extra: Partial<CustomerFeeSettings> = {}): CustomerFeeSettings => ({
  visible: true,
  rules: { exchange: rule('exchange'), refund: rule('refund') },
  merchantFaultReasons: ['damaged', 'wrong_item'],
  overrides: [],
  exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
  ...extra,
})

const SAVED = (extra: Partial<SavedState> = {}): SavedState => ({
  portal: PORTAL, otherCouriers: { available: true, enabled: false, since: null }, fees: FEES(), ...extra,
})

const row = (id: string, nonReturnable: boolean): PortalVariantRow =>
  ({ id, productTitle: 'Linen Shirt', variantTitle: id, sku: id, nonReturnable })

function plan(saved: SavedState, edit: (d: Draft) => Draft, role: 'owner' | 'manager' = 'owner') {
  return buildSavePlan(saved, edit(draftOf(saved)), role, REASON_CODES)
}

/** Every editable thing changed at once. */
function everything(d: Draft): Draft {
  return {
    ...d,
    otherCouriers: true,
    portal: { ...d.portal, windowText: '14' },
    pricing: { ...d.pricing, anyProduct: true },
    rules: { exchange: { ...d.rules.exchange, amount: '60' }, refund: { ...d.rules.refund, amount: '70' } },
    reasons: ['wrong_size'],
    variants: { v1: { row: row('v1', false), value: true }, v2: { row: row('v2', true), value: false } },
  }
}

describe('order and only what changed', () => {
  test('nothing edited → no step', () => {
    const s = SAVED()
    expect(plan(s, d => d)).toEqual({ steps: [], errors: [] })
    expect(editedKeys(s, draftOf(s))).toEqual([])
  })

  test('everything edited → the agreed order', () => {
    const p = plan(SAVED(), everything)
    expect(p.steps.map(s => s.section)).toEqual(
      ['otherCouriers', 'portal', 'pricing', 'ruleExchange', 'ruleRefund', 'reasons', 'variants', 'variants'])
    expect(p.steps[0]).toMatchObject({ kind: 'otherCouriers', enabled: true })
    expect(p.steps[1]).toMatchObject({ kind: 'portal', body: { returnWindowDays: 14 } })
    expect(p.steps[5]).toEqual({ section: 'reasons', kind: 'reasons', reasons: ['wrong_size'] })
  })

  test('one field → one step; the portal body is the same shape the tab always sent', () => {
    const p = plan(SAVED(), d => ({ ...d, portal: { ...d.portal, form: { ...d.portal.form, autoApprove: true } } }))
    expect(p.steps).toEqual([{ section: 'portal', kind: 'portal', body: {
      slug: 'nourstudio', enabled: true, autoApprove: true, returnWindowDays: 30, logoUrl: null, brandColor: null,
      policyText: null, returnLocationId: 'loc-1',
    } }])
  })

  test('products: only the rows whose value differs from saved', () => {
    const p = plan(SAVED({ fees: null }), d => ({ ...d, variants: {
      v1: { row: row('v1', false), value: true },
      v2: { row: row('v2', true), value: true },   // back to its saved value → nothing to send
    } }))
    expect(p.steps).toEqual([{ section: 'variants', kind: 'variant', row: row('v1', false), value: true }])
  })

  test('reasons are sent in the portal order', () => {
    const p = plan(SAVED(), d => ({ ...d, reasons: ['wrong_item', 'other', 'wrong_size'] }))
    expect(p.steps).toEqual([{ section: 'reasons', kind: 'reasons', reasons: ['wrong_size', 'wrong_item', 'other'] }])
  })
})

describe('who and what is planned', () => {
  test('a manager\'s plan has no owner-only call', () => {
    const p = plan(SAVED(), everything, 'manager')
    expect(p.steps.map(s => s.kind)).toEqual(['portal', 'variant', 'variant'])
  })

  test('fees hidden for the store (visible:false) → no fee or pricing call', () => {
    const p = plan(SAVED({ fees: FEES({ visible: false }) }), everything)
    expect(p.steps.map(s => s.kind)).toEqual(['otherCouriers', 'portal', 'variant', 'variant'])
  })

  test('fees not loaded → no fee or pricing call', () => {
    expect(plan(SAVED({ fees: null }), everything).steps.map(s => s.kind)).toEqual(['otherCouriers', 'portal', 'variant', 'variant'])
  })

  test('the other-couriers switch not shown → never planned', () => {
    expect(plan(SAVED({ otherCouriers: null }), d => ({ ...d, otherCouriers: true })).steps).toEqual([])
  })
})

describe('price mode', () => {
  const pricing = (sameOrLowerOnly: boolean, maxDifference: number | null) =>
    SAVED({ fees: FEES({ exchangePricing: { anyProduct: false, sameOrLowerOnly, maxDifference, freeVariantSwaps: true } }) })

  test('read: lower / cap / nolimit, and the conflicting stored state reads lower', () => {
    expect(pricingDraftOf({ anyProduct: false, sameOrLowerOnly: true, maxDifference: null, freeVariantSwaps: true }).priceMode).toBe('lower')
    expect(pricingDraftOf({ anyProduct: false, sameOrLowerOnly: false, maxDifference: 200, freeVariantSwaps: true }).priceMode).toBe('cap')
    expect(pricingDraftOf({ anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true }).priceMode).toBe('nolimit')
    expect(pricingDraftOf({ anyProduct: false, sameOrLowerOnly: true, maxDifference: 300, freeVariantSwaps: true }).priceMode).toBe('lower')
  })

  test('write: lower → (true, null), cap → (false, value), nolimit → (false, null)', () => {
    expect(priceWrite('lower', '300')).toEqual({ sameOrLowerOnly: true, maxDifference: null })
    expect(priceWrite('cap', '150')).toEqual({ sameOrLowerOnly: false, maxDifference: 150 })
    expect(priceWrite('cap', '0')).toEqual({ sameOrLowerOnly: false, maxDifference: 0 })
    expect(priceWrite('nolimit', '150')).toEqual({ sameOrLowerOnly: false, maxDifference: null })
  })

  test('each mode change is planned with the written pair', () => {
    for (const [from, to, cap, body] of [
      [pricing(false, null), 'lower', '', { sameOrLowerOnly: true, maxDifference: null }],
      [pricing(false, null), 'cap', '250', { sameOrLowerOnly: false, maxDifference: 250 }],
      [pricing(true, null), 'nolimit', '', { sameOrLowerOnly: false, maxDifference: null }],
      [pricing(false, 100), 'lower', '100', { sameOrLowerOnly: true, maxDifference: null }],
      [pricing(true, null), 'cap', '0', { sameOrLowerOnly: false, maxDifference: 0 }],
    ] as const) {
      const p = plan(from, d => ({ ...d, pricing: { ...d.pricing, priceMode: to, capText: cap } }))
      expect(p.errors).toEqual([])
      expect(p.steps).toEqual([{ section: 'pricing', kind: 'pricing',
        body: { anyProduct: false, freeVariantSwaps: true, ...body } }])
    }
  })

  test('the conflicting stored state is not edited on load; any pricing change writes (true, null)', () => {
    const s = pricing(true, 300)
    expect(editedKeys(s, draftOf(s))).toEqual([])
    expect(plan(s, d => d).steps).toEqual([])
    const p = plan(s, d => ({ ...d, pricing: { ...d.pricing, anyProduct: true } }))
    expect(p.steps).toEqual([{ section: 'pricing', kind: 'pricing',
      body: { anyProduct: true, sameOrLowerOnly: true, maxDifference: null, freeVariantSwaps: true } }])
  })

  test('an empty cap is a client-side error that blocks Save; a changed cap amount is planned', () => {
    const p = plan(pricing(false, null), d => ({ ...d, pricing: { ...d.pricing, priceMode: 'cap', capText: '  ' } }))
    expect(p.errors).toEqual([{ field: 'cap', code: 'CAP_REQUIRED' }])
    const q = plan(pricing(false, 100), d => ({ ...d, pricing: { ...d.pricing, capText: '120' } }))
    expect(q.steps).toEqual([{ section: 'pricing', kind: 'pricing',
      body: { anyProduct: false, sameOrLowerOnly: false, maxDifference: 120, freeVariantSwaps: true } }])
  })
})

describe('merged waiver', () => {
  const withRules = (exchange: FeeRule | null, refund: FeeRule | null) => SAVED({ fees: FEES({ rules: { exchange, refund } }) })

  test('all on → on; mixed → off with the mixed note; no rules → on', () => {
    expect(savedWaiver({ exchange: rule('exchange'), refund: rule('refund') })).toBe(true)
    expect(savedWaiver({ exchange: rule('exchange'), refund: rule('refund', { waiveMerchantFault: false }) })).toBe(false)
    expect(waiverMixed({ exchange: rule('exchange'), refund: rule('refund', { waiveMerchantFault: false }) })).toBe(true)
    expect(waiverMixed({ exchange: rule('exchange', { waiveMerchantFault: false }), refund: null })).toBe(false)
    expect(savedWaiver({ exchange: null, refund: null })).toBe(true)
  })

  test('all on, switched off → both rules get waiveMerchantFault:false, nothing else changes', () => {
    const p = plan(withRules(rule('exchange'), rule('refund')), d => ({ ...d, waiver: false }))
    expect(p.steps.map(s => s.kind === 'rule' && [s.fee, s.body.waiveMerchantFault, s.body.amount])).toEqual(
      [['exchange', false, 50], ['refund', false, 50]])
  })

  test('mixed: untouched it is not an edit; a save of anything else makes the rules match (off)', () => {
    const s = withRules(rule('exchange'), rule('refund', { waiveMerchantFault: false }))
    expect(editedKeys(s, draftOf(s))).toEqual([])
    const p = plan(s, d => ({ ...d, portal: { ...d.portal, windowText: '14' } }))
    expect(p.steps.map(x => x.section)).toEqual(['portal', 'ruleExchange'])
    expect(p.steps[1]).toMatchObject({ fee: 'exchange', body: { waiveMerchantFault: false } })
  })

  test('mixed, switched on → only the rule that differs is written', () => {
    const p = plan(withRules(rule('exchange'), rule('refund', { waiveMerchantFault: false })), d => ({ ...d, waiver: true }))
    expect(p.steps).toEqual([{ section: 'ruleRefund', kind: 'rule', fee: 'refund', body: expect.objectContaining({ waiveMerchantFault: true }) }])
  })

  test('no rules: toggling it is not a change on its own; a rule created now takes it', () => {
    const s = withRules(null, null)
    expect(editedKeys(s, { ...draftOf(s), waiver: false })).toEqual([])
    expect(plan(s, d => ({ ...d, waiver: false })).steps).toEqual([])
    const p = plan(s, d => ({ ...d, waiver: false, rules: { ...d.rules, refund: { ...d.rules.refund, enabled: true, amount: '40' } } }))
    expect(p.steps).toEqual([{ section: 'ruleRefund', kind: 'rule', fee: 'refund', body: {
      enabled: true, mode: 'fixed', waiveMerchantFault: false, amount: 40, percent: null, minAmount: null, maxAmount: null } }])
  })

  test('written only into rules with a valid amount (PUT validates it even when the fee is off)', () => {
    // A stored rule whose amount is being cleared in the draft is not a waiver taker; it is sent only because it changed.
    const s = withRules(rule('exchange'), rule('refund'))
    const p = plan(s, d => ({ ...d, waiver: false, rules: { ...d.rules, exchange: { ...d.rules.exchange, enabled: false, amount: '' } } }))
    expect(p.steps.map(x => x.kind === 'rule' && [x.fee, x.body.waiveMerchantFault])).toEqual([['exchange', true], ['refund', false]])
  })
})

describe('running the plan', () => {
  test('stops at the first failure: step 1 saved, the failed section and the rest stay edited', async () => {
    const s = SAVED()
    const d = { ...everything(draftOf(s)), otherCouriers: true }
    const p = buildSavePlan(s, d, 'owner', REASON_CODES)
    const sent: string[] = []
    const run = await runSavePlan(p.steps, async step => {
      sent.push(step.section)
      if (step.kind === 'otherCouriers') return { available: true, enabled: true, since: '2026-11-07T10:00:00Z' }
      throw new Error('400: Bad Request')
    })
    expect(sent).toEqual(['otherCouriers', 'portal'])
    expect(run.done.map(x => x.step.section)).toEqual(['otherCouriers'])
    expect(run.failed?.step.section).toBe('portal')

    const next = applyDone(s, d, run.done)
    expect(next.saved.otherCouriers?.enabled).toBe(true)
    const left = editedKeys(next.saved, next.draft)
    expect(left).not.toContain('otherCouriers')
    expect(left).toEqual(expect.arrayContaining(['portal.returnWindowDays', 'pricing.swapTo', 'rule.exchange', 'rule.refund',
      'reasons', 'variant.v1', 'variant.v2']))
  })

  test('full success: nothing is left edited', async () => {
    const s = SAVED({ otherCouriers: null, fees: FEES() })
    const d = { ...draftOf(s), portal: { ...draftOf(s).portal, windowText: '14' }, reasons: ['other'],
      variants: { v1: { row: row('v1', false), value: true } } }
    const p = buildSavePlan(s, d, 'owner', REASON_CODES)
    const run = await runSavePlan(p.steps, async step => {
      if (step.kind === 'portal') return { ...PORTAL, returnWindowDays: 14 }
      if (step.kind === 'reasons') return FEES({ merchantFaultReasons: ['other'] })
      return null   // PUT /variants/{id}/non-returnable answers 204, no body
    })
    expect(run.failed).toBeNull()
    const next = applyDone(s, d, run.done)
    expect(editedKeys(next.saved, next.draft)).toEqual([])
  })
})
