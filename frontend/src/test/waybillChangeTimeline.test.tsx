import { test, expect, vi, afterEach } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, TimelineItem } from '../api'

// Shopify waybill changes on the order timeline (S4): followed → "Waybill changed in Shopify" (old → new); not
// followed → a 'fail' row with its reason in plain EN / AR. Plus: every new key exists in both locales.

const REASON_KEYS = ['frozen', 'pickup_session', 'ambiguous', 'key_taken', 'old_delivery_live'] as const
const ALERT_TYPES = ['tracking_changed_after_custody', 'tracking_change_needs_review', 'tracking_changed_relabel',
  'superseded_waybill_moving'] as const

function flatten(obj: unknown, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') Object.assign(out, flatten(v, key))
    else out[key] = String(v)
  }
  return out
}

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function order(): OrderDetail {
  return {
    id: 'order-1', number: '#17243', customerName: 'Habiba Ali', customerPhone: '01211000010',
    address: null, paymentMethod: 'cod', codAmount: 990, status: 'new', onHold: false,
    holdReason: null, placedAt: '2026-10-30T10:42:00Z', createdAt: '2026-10-30T10:42:00Z',
    items: [], shipments: [], bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: false,
      fulfillmentKey: 'status.unfulfilled', fulfillmentTone: 'NEUTRAL',
    },
    physicallyWithCourier: false,
  } as OrderDetail
}

const TIMELINE: TimelineItem[] = [
  { occurredAt: '2026-10-30T10:42:00Z', eventKey: 'order_created', actorName: null, locationName: null, detail: null, kind: 'done' },
  { occurredAt: '2026-10-30T19:00:00Z', eventKey: 'awb_linked', actorName: null, locationName: null, detail: '853795364', kind: 'done' },
  { occurredAt: '2026-11-01T20:03:26Z', eventKey: 'waybill_changed_in_shopify', actorName: null, locationName: null,
    detail: '853795364 → 8384882226', kind: 'done' },
  { occurredAt: '2026-11-01T21:00:00Z', eventKey: 'waybill_change_not_followed', actorName: null, locationName: null,
    detail: '8384882226 → 9730639099', kind: 'fail', reasonKey: 'frozen' },
]

function renderDrawer(lang: 'en' | 'ar') {
  const i18n = i18next.createInstance()
  i18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en', resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', (url: string) =>
    url.includes('/timeline') ? jsonOk(TIMELINE) : url.includes('/orders/') ? jsonOk(order()) : jsonOk({}))
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={i18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>
  )
}

test('wc1 every new timeline / reason / alert key has a real string in en and ar', () => {
  for (const dict of [en, ar]) {
    const flat = flatten(dict)
    for (const path of ['orders.timeline.waybill_changed_in_shopify', 'orders.timeline.waybill_change_not_followed',
      ...REASON_KEYS.map((r) => `orders.timeline.reason.${r}`), ...ALERT_TYPES.map((t) => `orders.reason.${t}`)]) {
      expect(flat, `missing ${path}`).toHaveProperty(path)
      expect(flat[path].length).toBeGreaterThan(0)
      expect(flat[path]).not.toBe(path)
    }
  }
})

test('wc2 en: the change and the refusal render with the numbers and the plain reason, no raw key', async () => {
  renderDrawer('en')
  expect(await screen.findByText('Waybill changed in Shopify')).toBeInTheDocument()
  expect(screen.getByText('853795364 → 8384882226')).toBeInTheDocument()
  expect(screen.getByText('Waybill change in Shopify not followed')).toBeInTheDocument()
  expect(screen.getByTestId('timeline-reason')).toHaveTextContent(
    'The parcel had already left the warehouse — Traced kept the old waybill')
  for (const raw of ['waybill_changed_in_shopify', 'waybill_change_not_followed', 'frozen']) {
    expect(screen.queryByText(raw)).toBeNull()
  }
})

test('wc3 ar / RTL: same rows in Arabic; the numbers keep their order (ltr)', async () => {
  renderDrawer('ar')
  expect(await screen.findByText('تغيّرت البوليصة في شوبيفاي')).toBeInTheDocument()
  expect(screen.getByText('لم يتبع Traced تغيير البوليصة في شوبيفاي')).toBeInTheDocument()
  expect(screen.getByTestId('timeline-reason')).toHaveTextContent('كان الطرد قد خرج من المخزن')
  expect(screen.getByText('853795364 → 8384882226')).toHaveAttribute('dir', 'ltr')
  // Other details keep the page direction (only waybill changes are forced ltr).
  expect(screen.getByText('853795364')).not.toHaveAttribute('dir')
})
