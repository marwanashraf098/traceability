import { test, expect, describe, beforeEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen } from './renderWithProviders'
import ExchangeRequestView from '../pages/exchangesRefunds/ExchangeRequestView'
import { reasonLine } from '../pages/ExchangesRefunds'
import type { ReturnCase, ReturnRequestDetail } from '../api'
import i18n from '../i18n'

/**
 * 2026-11-09 — Bosta wording on requests another courier carries:
 *  - the Returns list reason line of a request booked with the merchant's own courier (booking 'manual') said
 *    "Book the pickup in Bosta" (awaiting_items) and "Bosta <tracking>" (on_the_way);
 *  - a sold-out exchange's refund fallback said "Bosta collects the …" on a non-Bosta order.
 * Bosta requests keep their wording.
 */

const BOSTA_WORD = /Bosta|بوسطة|بوستا/

describe('Returns list reason line', () => {
  const manual = (nextStep: string, carrierName: string | null, extra: Record<string, unknown> = {}) => ({
    caseType: 'A', kind: 'refund', nextStep, reference: 'RR-1',
    reason: { bookingStatus: 'manual', carrierName, ...extra },
  }) as unknown as ReturnCase

  test.each([
    ['en', 'Aramex', 'Waiting for the items from Aramex'],
    ['en', null, 'Waiting for the items from the courier'],
    ['ar', 'Aramex', 'بانتظار القطع من Aramex'],
    ['ar', null, 'بانتظار القطع من شركة الشحن'],
  ] as const)('awaiting_items, manual, %s, %s → %s', (lng, carrier, text) => {
    const line = reasonLine(i18n.getFixedT(lng), manual('awaiting_items', carrier))
    expect(line).toBe(text)
    expect(line).not.toMatch(BOSTA_WORD)
  })

  test.each([
    ['en', 'Aramex', 'Aramex 7001'],
    ['en', null, 'the courier 7001'],
    ['ar', null, 'شركة الشحن 7001'],
  ] as const)('on_the_way, manual, %s, %s → %s', (lng, carrier, text) => {
    expect(reasonLine(i18n.getFixedT(lng), manual('on_the_way', carrier, { trackingNumber: '7001' }))).toBe(text)
  })

  test('a Bosta request keeps "Book the pickup in Bosta" and "Bosta <tracking>"', () => {
    const t = i18n.getFixedT('en')
    const bosta = (nextStep: string, extra: Record<string, unknown> = {}) => ({
      caseType: 'A', kind: 'refund', nextStep, reference: 'RR-1', reason: { bookingStatus: null, ...extra },
    }) as unknown as ReturnCase
    expect(reasonLine(t, bosta('awaiting_items'))).toBe('Book the pickup in Bosta')
    expect(reasonLine(t, bosta('on_the_way', { trackingNumber: '5100000001' }))).toBe('Bosta 5100000001')
  })
})

describe('sold-out exchange — refund fallback', () => {
  function detail(otherCourier: ReturnRequestDetail['otherCourier'] | undefined): ReturnRequestDetail {
    return {
      id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
      customerPhone: null, type: 'exchange', status: 'requested', email: null, note: null,
      createdAt: '2026-11-01T10:00:00Z', deliveredAt: '2026-10-28T10:00:00Z',
      pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
      pickupDistrictName: null, pickupDistrictNameAr: null,
      decidedAt: null, decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
      refundFallbackOk: true,
      items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-m', productTitle: 'Linen Shirt',
        variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true,
        replacementVariantId: 'v-l', replacementVariantTitle: 'White / L', replacementAvailable: 0, replacementInStock: false }],
      ...(otherCourier ? { otherCourier } : {}),
    } as unknown as ReturnRequestDetail
  }
  const other = (forwardCarrierName: string | null) =>
    ({ forwardCarrierName, carrierName: null, pickup: { name: null, phone: null, addressLines: [] } }) as unknown as ReturnRequestDetail['otherCourier']

  beforeEach(async () => { await i18n.changeLanguage('en') })

  test.each([
    ['en', 'Aramex', 'Aramex collects the White / M'],
    ['en', null, 'the courier collects the White / M'],
    ['ar', 'Aramex', 'تستلم Aramex White / M'],
    ['ar', null, 'تستلم شركة الشحن White / M'],
  ] as const)('another courier\'s order, %s, %s → names it, never Bosta', async (lng, carrier, text) => {
    await i18n.changeLanguage(lng)
    renderWithProviders(<I18nextProvider i18n={i18n}>
      <ExchangeRequestView detail={detail(other(carrier))} onReload={async () => {}} onReject={() => {}} />
    </I18nextProvider>)
    expect(document.body.textContent).toContain(text)
    expect(document.body.textContent).not.toMatch(BOSTA_WORD)
  })

  test('a Bosta order keeps "Bosta collects"', () => {
    renderWithProviders(<ExchangeRequestView detail={detail(undefined)} onReload={async () => {}} onReject={() => {}} />)
    expect(screen.getByText(/Bosta collects the White \/ M/)).toBeTruthy()
  })
})
