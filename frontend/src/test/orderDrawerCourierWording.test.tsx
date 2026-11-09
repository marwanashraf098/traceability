import { test, expect, afterEach, vi, describe } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, ShipmentDetail, TimelineItem } from '../api'

/**
 * 2026-11-09 — the order drawer on a non-Bosta order:
 *  Bug A: the header badge showed the literal "With {{carrier}}" (OrderStatus never passed the carrier).
 *  Bug B: "contact Bosta" / "No Bosta waybill yet" / "Bosta AWB Linked" on orders another courier carries.
 * The order's courier block (newest forward shipment's provider; with none, the store's Bosta account) decides;
 * an order with no courier block keeps today's Bosta wording (orderDrawerNoWaybill / orderDrawerCancelHoldGate).
 */

afterEach(() => vi.unstubAllGlobals())

const BOSTA_WORD = /Bosta|بوسطة|بوستا/

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function genericForward(carrierName: string): ShipmentDetail {
  return {
    id: 'ship-g', trackingNumber: 'MYL-778899', provider: 'generic', internalState: 'with_courier',
    shipmentLeg: 'forward', numberOfAttempts: 0, failedDeliveryAttempts: 0, awbUrl: null,
    exceptionCode: null, exceptionReason: null, isDelayed: null, slaBreached: null,
    scheduledAt: null, courierName: null, courierPhone: null, lastFailureReason: null,
    attempts: [], deliveryHistory: [],
    legStatus: { primaryKey: 'status.with_carrier', tone: 'INFO' },
    carrierName,
  } as ShipmentDetail
}

function order(over: Partial<OrderDetail> & { primaryKey?: string }): OrderDetail {
  const { primaryKey = 'status.new', ...rest } = over
  return {
    id: 'order-1', number: '#2001', customerName: 'Mona', customerPhone: null,
    address: null, paymentMethod: 'cod', codAmount: null, status: 'confirmed', onHold: false,
    holdReason: null, placedAt: '2026-11-01T10:00:00Z', createdAt: '2026-11-01T10:00:05Z',
    items: [],
    shipments: [],
    bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey, tone: 'INFO', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: false,
      fulfillmentKey: 'status.packed', fulfillmentTone: 'INFO',
    },
    physicallyWithCourier: false,
    shippingBadge: null,
    ...rest,
  } as unknown as OrderDetail
}

function renderDrawer(o: OrderDetail, lang: 'en' | 'ar' = 'en', timeline: TimelineItem[] = []) {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/timeline')) return jsonOk(timeline)
    if (url.includes('/notes')) return jsonOk([])
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

describe.each(['en', 'ar'] as const)('%s', lang => {
  test('a generic order with its courier: the header and the card name the carrier — no {{ }} and no Bosta', async () => {
    renderDrawer(order({
      primaryKey: 'status.with_carrier', physicallyWithCourier: true,
      shipments: [genericForward('Mylerz')],
      courier: { kind: 'generic', name: 'Mylerz' },
    }), lang)
    const withMylerz = lang === 'en' ? 'With Mylerz' : 'مع Mylerz'
    expect(await screen.findAllByText(withMylerz)).toHaveLength(2) // header badge + Current state card
    expect(document.body.textContent).not.toContain('{{')
    expect(document.body.textContent).not.toMatch(BOSTA_WORD)
    expect(document.body.textContent).toContain(lang === 'en'
      ? 'Order has been handed to Mylerz — contact them to arrange cancellation.'
      : 'تم تسليم الطلب إلى Mylerz — يرجى التواصل معها لترتيب الإلغاء.')
  })

  test('a generic order without a carrier name: "the courier", delivered', async () => {
    renderDrawer(order({
      primaryKey: 'status.delivered_with_carrier', status: 'delivered',
      shipments: [genericForward('')],
      courier: { kind: 'generic', name: null },
    }), lang)
    const label = lang === 'en' ? 'Delivered · the courier' : 'تم التوصيل · شركة الشحن'
    expect(await screen.findAllByText(label)).toHaveLength(2)
    expect(document.body.textContent).not.toContain('{{')
    expect(document.body.textContent).not.toMatch(BOSTA_WORD)
  })

  test('no shipment on a store without Bosta (courier kind null): "No waybill yet"', async () => {
    renderDrawer(order({ courier: { kind: null, name: null } }), lang)
    expect(await screen.findByTestId('drawer-no-waybill')).toHaveTextContent(
      lang === 'en' ? 'No waybill yet' : 'لا توجد بوليصة بعد')
    expect(document.body.textContent).not.toMatch(BOSTA_WORD)
  })

  test('no shipment on a Bosta store (courier kind bosta): today\'s Bosta wording', async () => {
    renderDrawer(order({ courier: { kind: 'bosta', name: null } }), lang)
    expect(await screen.findByTestId('drawer-no-waybill')).toHaveTextContent(
      lang === 'en' ? 'No Bosta waybill yet' : 'لا توجد بوليصة بوستا بعد')
  })

  test('a Bosta order with its courier: "contact Bosta" stays', async () => {
    renderDrawer(order({ physicallyWithCourier: true, courier: { kind: 'bosta', name: null } }), lang)
    expect(await screen.findByText(lang === 'en'
      ? /contact Bosta to arrange cancellation/
      : /يرجى التواصل مع Bosta لترتيب الإلغاء/)).toBeTruthy()
  })

  test('timeline: a non-Bosta waybill reads "Waybill Linked"', async () => {
    renderDrawer(order({ shipments: [genericForward('Mylerz')], courier: { kind: 'generic', name: 'Mylerz' } }), lang, [
      { occurredAt: '2026-11-01T11:00:00Z', eventKey: 'waybill_linked', actorName: null, locationName: null,
        detail: 'MYL-778899', kind: 'done' },
      { occurredAt: '2026-11-02T11:00:00Z', eventKey: 'return_waybill_linked', actorName: null, locationName: null,
        detail: 'MYL-R1', kind: 'done' },
    ])
    expect(await screen.findByText(lang === 'en' ? 'Waybill Linked' : 'تم ربط بوليصة الشحن')).toBeTruthy()
    expect(screen.getByText(lang === 'en' ? 'Return Waybill Linked' : 'تم ربط بوليصة الإرجاع')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(BOSTA_WORD)
  })
})
