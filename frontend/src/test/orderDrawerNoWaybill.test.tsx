import { test, expect, afterEach, vi } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, ShipmentDetail } from '../api'

/**
 * Orders drawer: "View shipment" is disabled when the order has no forward (Bosta)
 * shipment — it must say why ("No Bosta waybill yet") instead of a silent dead button.
 * Reviewer case: #1301, draft-sourced, no Bosta shipment.
 */

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function forwardShipment(): ShipmentDetail {
  return {
    id: 'ship-1', trackingNumber: '2944282510', provider: 'bosta', internalState: 'created',
    shipmentLeg: 'forward', numberOfAttempts: 0, failedDeliveryAttempts: 0, awbUrl: null,
    exceptionCode: null, exceptionReason: null, isDelayed: null, slaBreached: null,
    scheduledAt: null, courierName: null, courierPhone: null, lastFailureReason: null,
    attempts: [], deliveryHistory: [],
    legStatus: { primaryKey: 'status.awaiting_courier', tone: 'INFO' },
  } as ShipmentDetail
}

function order(shipments: ShipmentDetail[]): OrderDetail {
  return {
    id: 'order-1', number: '#1301', customerName: null, customerPhone: null,
    address: null, paymentMethod: 'cod', codAmount: null, status: 'new', onHold: false,
    holdReason: null, placedAt: '2026-09-25T13:51:58Z', createdAt: '2026-09-25T13:52:05Z',
    items: [{ id: 'item-1', productTitle: 'Gift card', variantTitle: 'Default Title', sku: null, quantity: 1, imageUrl: null, allocatedPieces: [] }],
    shipments,
    bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: false,
      fulfillmentKey: 'status.new', fulfillmentTone: 'NEUTRAL',
    },
    physicallyWithCourier: false,
  } as unknown as OrderDetail
}

function renderDrawer(o: OrderDetail, lang: 'en' | 'ar' = 'en') {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/notes') || url.includes('/timeline')) return jsonOk([])
    if (url.includes('/orders/')) return jsonOk(o)
    return jsonOk({})
  }))
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>,
  )
}

test('no forward shipment → View shipment disabled with "No Bosta waybill yet" beneath it', async () => {
  renderDrawer(order([]))
  const btn = await screen.findByRole('button', { name: 'View shipment' })
  expect(btn).toBeDisabled()
  expect(screen.getByTestId('drawer-no-waybill')).toHaveTextContent('No Bosta waybill yet')
})

test('forward shipment present (positive control) → button enabled, no hint', async () => {
  renderDrawer(order([forwardShipment()]))
  const btn = await screen.findByRole('button', { name: 'View shipment' })
  expect(btn).toBeEnabled()
  expect(screen.queryByTestId('drawer-no-waybill')).toBeNull()
})

test('ar/RTL — hint resolves to Arabic', async () => {
  renderDrawer(order([]), 'ar')
  expect(await screen.findByTestId('drawer-no-waybill')).toHaveTextContent('لا توجد بوليصة بوستا بعد')
})
