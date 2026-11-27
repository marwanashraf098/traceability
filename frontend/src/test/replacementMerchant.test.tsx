import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import { ExchangePill } from '../pages/exchangesRefunds/ReturnRequestDrawer'
import i18n from '../i18n'
import type { CustomerFeeSettings, PortalSettings, ReturnCase } from '../api'
import { applyDone, buildSavePlan, draftOf, editedKeys, SavedState } from '../pages/settings/portal/savePlan'
import { REASON_CODES } from '../pages/settings/portal/usePortalSettingsDraft'

// Defect replacement (V246) — the merchant side.
//   list      — Returns & exchanges: a replacement's type tag reads "Replacement (same item)" (EN + AR); a swap "Exchange"
//   pill      — the request drawer's pill
//   settings  — "Charge fees on defect replacements": edited key, its own save step (owner only), saved state applied

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

function kase(id: string, over: Partial<ReturnCase> = {}): ReturnCase {
  return {
    caseType: 'A', id, kind: 'exchange', reference: `RR-${id.toUpperCase()}`, source: 'returns_page',
    customerName: 'Mariam Saleh', orderNumber: '#1047', stage: 'to_do', nextStep: 'approve', tone: 'action',
    overdueDays: null, status: 'requested',
    reason: {
      bookingStatus: null, bookingError: null, itemsCount: 1, arrivedCount: 0, awaitingCount: 1, candidateCount: 0,
      candidateReferences: null, trackingNumber: null, refundTotal: '0.00', currency: 'EGP', closeReason: null,
      inspectionState: null,
    },
    alerts: [], itemsSummary: 'Linen Shirt White / M', itemsSummaryAr: 'Linen Shirt White / M', notScanned: false,
    updatedAt: new Date().toISOString(), legStatus: null,
    target: { requestId: id, exchangeId: null, shipmentId: null },
    ...over,
  }
}

const CASES = [kase('r1', { exchangeKind: 'replacement' }), kase('s1')]

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  stubFetchWithShellDefaults(vi.fn((url: string) => {
    const u = new URL(url, 'http://x')
    if (u.pathname.endsWith('/returns-exchanges/counts')) {
      return fakeResponse({ stages: { all: 2, to_do: 2, in_progress: 0, done: 0 }, tiles: {} })
    }
    if (u.pathname.endsWith('/returns-exchanges')) return fakeResponse({ items: CASES, nextCursor: null })
    return fakeResponse({})
  }))
})

afterEach(async () => {
  await i18n.changeLanguage('en')
  document.documentElement.dir = 'ltr'
})

const rowOf = (ref: string) => screen.getByText(ref).closest('tr') as HTMLElement

describe('replacement labels', () => {
  test('list: the type tag says Replacement (same item); a swap stays Exchange', async () => {
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
    await screen.findAllByTestId('case-row')
    expect(within(rowOf('RR-R1')).getByTestId('type-tag')).toHaveTextContent('Replacement (same item)')
    expect(within(rowOf('RR-S1')).getByTestId('type-tag')).toHaveTextContent('Exchange')
    expect(within(rowOf('RR-S1')).getByTestId('type-tag')).not.toHaveTextContent('Replacement')
  })

  test('list in Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
    await screen.findAllByTestId('case-row')
    expect(within(rowOf('RR-R1')).getByTestId('type-tag')).toHaveTextContent('استبدال بنفس المنتج')
  })

  test('drawer pill', () => {
    renderWithProviders(<><ExchangePill replacement /><ExchangePill /></>)
    expect(screen.getByText('Replacement (same item)')).toBeInTheDocument()
    expect(screen.getByText('Exchange')).toBeInTheDocument()
  })
})

const PORTAL = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: 'loc-1',
  returnLocationName: 'Maadi', bostaConnected: true, exchangesEnabled: true, font: 'cairo', refundMethods: [],
  requirePhotos: false,
} as unknown as PortalSettings

const FEES = (replacementFees: boolean): CustomerFeeSettings => ({
  visible: true, rules: { exchange: null, refund: null }, merchantFaultReasons: ['damaged'], overrides: [],
  exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
  replacementFees,
})

const SAVED = (fees: CustomerFeeSettings | null): SavedState =>
  ({ portal: PORTAL, otherCouriers: null, fees } as unknown as SavedState)

describe('settings: charge fees on defect replacements', () => {
  test('edited → its own step (owner); applied from the answer', () => {
    const saved = SAVED(FEES(false))
    const d = { ...draftOf(saved), replacementFees: true }
    expect(editedKeys(saved, d)).toEqual(['replacementFees'])
    const p = buildSavePlan(saved, d, 'owner', REASON_CODES)
    expect(p.steps).toEqual([{ section: 'replacementFees', kind: 'replacementFees', charge: true }])
    const after = applyDone(saved, d, [{ step: p.steps[0], result: FEES(true) }])
    expect(after.saved.fees?.replacementFees).toBe(true)
    expect(editedKeys(after.saved, after.draft)).toEqual([])
  })

  test('unchanged, a manager, or fees hidden → no step', () => {
    const saved = SAVED(FEES(true))
    expect(buildSavePlan(saved, draftOf(saved), 'owner', REASON_CODES).steps).toEqual([])
    expect(buildSavePlan(saved, { ...draftOf(saved), replacementFees: false }, 'manager', REASON_CODES).steps).toEqual([])
    const hidden = SAVED({ ...FEES(false), visible: false })
    expect(buildSavePlan(hidden, { ...draftOf(hidden), replacementFees: true }, 'owner', REASON_CODES).steps).toEqual([])
  })

  test('an older backend without the key reads as off and is never edited', () => {
    const { replacementFees: _, ...old } = FEES(false)
    const saved = SAVED(old as CustomerFeeSettings)
    expect(draftOf(saved).replacementFees).toBe(false)
    expect(editedKeys(saved, draftOf(saved))).toEqual([])
  })
})
