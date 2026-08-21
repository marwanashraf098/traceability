import { test, expect, describe, vi, afterEach } from 'vitest'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, TimelineItem } from '../api'

// ── Strong no-missing-key test (data-level, no rendering needed) ────────────────
// The backend eventKey set (OrderController.timeline()) is not exported as constants —
// enumerated here verbatim from the actual emit sites (grepped, not retyped from a
// naming convention): "order_created"/"picked_up"/"packed" (literals),
// isReturn ? "return_awb_linked" : "awb_linked" (Orders fix pass 2a — relabels the
// earliest real shipment_status_history row, or falls back to shipments.created_at if
// none exists yet), "delivery_attempt_failed", and
// (isReturn ? "return_shipment_state_" : "shipment_state_") + state for all 9
// shipment_internal_state enum values.
const BACKEND_EVENT_KEYS = [
  'order_created', 'picked_up', 'packed', 'awb_linked',
  'return_awb_linked', 'delivery_attempt_failed',
  'shipment_state_created', 'shipment_state_with_courier', 'shipment_state_delivered',
  'shipment_state_returning', 'shipment_state_returned', 'shipment_state_lost',
  'shipment_state_exception', 'shipment_state_terminated', 'shipment_state_cancelled',
  'return_shipment_state_created', 'return_shipment_state_with_courier',
  'return_shipment_state_delivered', 'return_shipment_state_returning',
  'return_shipment_state_returned', 'return_shipment_state_lost',
  'return_shipment_state_exception', 'return_shipment_state_terminated',
  'return_shipment_state_cancelled',
] as const

function flatten(obj: unknown, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') Object.assign(out, flatten(v, key))
    else out[key] = String(v)
  }
  return out
}

describe('orders.timeline.* — all 24 backend eventKeys resolve, no raw-key fallback', () => {
  test(`exactly ${BACKEND_EVENT_KEYS.length} keys enumerated`, () => {
    expect(BACKEND_EVENT_KEYS.length).toBe(24)
  })

  for (const locale of [{ name: 'en', dict: en }, { name: 'ar', dict: ar }]) {
    test(`every backend eventKey has a real ${locale.name} string under orders.timeline`, () => {
      const flat = flatten(locale.dict)
      for (const eventKey of BACKEND_EVENT_KEYS) {
        const path = `orders.timeline.${eventKey}`
        expect(flat, `missing key: ${path}`).toHaveProperty(path)
        const value = flat[path]
        expect(value, `key ${path} resolved to itself — raw-key fallback`).not.toBe(path)
        expect(value, `key ${path} resolved to the bare eventKey — raw-key fallback`).not.toBe(eventKey)
        expect(value.length, `key ${path} resolved to an empty string`).toBeGreaterThan(0)
      }
    })
  }

  test('orders.timeline.system fallback (null actor) exists in both locales', () => {
    expect(flatten(en)).toHaveProperty('orders.timeline.system')
    expect(flatten(ar)).toHaveProperty('orders.timeline.system')
  })
})

// ── Component-level render tests ─────────────────────────────────────────────────

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function jsonErr(status = 500) {
  return Promise.resolve({
    ok: false,
    status,
    statusText: 'Server Error',
    headers: { get: () => null },
    json: async () => ({}),
  })
}

function makeOrderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#2212094474', customerName: 'Habiba Ali', customerPhone: '01211000010',
    address: null, paymentMethod: 'cod', codAmount: 990, status: 'with_courier', onHold: false,
    holdReason: null, placedAt: '2026-08-11T10:42:00Z', createdAt: '2026-08-11T10:42:00Z',
    items: [{ id: 'item-1', productTitle: 'Bikini Pink', variantTitle: 'M', sku: 'SN-BIK-PNK-M', quantity: 1, imageUrl: null, allocatedPieces: [] }],
    shipments: [],
    bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: true,
      fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
    },
    ...overrides,
  }
}

// Covers all 4 shapes the review asked for: forward happy-path, RETURN-LEG row,
// non-attempt exception row, and picked_up with a real name — not just the mockup's
// forward-only states.
function makeMixedTimeline(): TimelineItem[] {
  return [
    { occurredAt: '2026-08-11T10:42:00Z', eventKey: 'order_created', actorName: null, locationName: null, detail: null, kind: 'done' },
    { occurredAt: '2026-08-11T11:00:00Z', eventKey: 'picked_up', actorName: 'Ahmed Mostafa', locationName: null, detail: null, kind: 'done' },
    { occurredAt: '2026-08-11T11:15:00Z', eventKey: 'packed', actorName: 'Ahmed Mostafa', locationName: null, detail: null, kind: 'done' },
    { occurredAt: '2026-08-11T14:00:00Z', eventKey: 'awb_linked', actorName: null, locationName: null, detail: '3920909349', kind: 'done' },
    { occurredAt: '2026-08-12T09:00:00Z', eventKey: 'shipment_state_exception', actorName: null, locationName: null, detail: 'Investigation opened', kind: 'fail' },
    { occurredAt: '2026-08-12T13:00:00Z', eventKey: 'shipment_state_with_courier', actorName: null, locationName: null, detail: null, kind: 'done' },
    { occurredAt: '2026-08-13T09:00:00Z', eventKey: 'return_shipment_state_returning', actorName: null, locationName: null, detail: null, kind: 'now' },
  ]
}

function stubFetch(order: OrderDetail, timeline: TimelineItem[] | 'error') {
  return (url: string) => {
    if (url.includes('/timeline')) return timeline === 'error' ? jsonErr() : jsonOk(timeline)
    if (url.includes('/orders/')) return jsonOk(order)
    return jsonOk({})
  }
}

function renderDrawer(fetchImpl: (url: string) => unknown, lang: 'en' | 'ar' = 'en') {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', fetchImpl)
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>
  )
}

test('drawer renders forward, return-leg, non-attempt-exception, and picked_up(name) rows — no raw key visible', async () => {
  renderDrawer(stubFetch(makeOrderDetail(), makeMixedTimeline()))

  expect(await screen.findByText('Order created')).toBeInTheDocument()
  expect(screen.getByText('Picked by Ahmed Mostafa')).toBeInTheDocument()
  expect(screen.getByText('Packed')).toBeInTheDocument()
  expect(screen.getByText('Bosta AWB Linked')).toBeInTheDocument()
  // Non-attempt exception (no forward NDR code) — distinct from "Delivery attempt failed".
  expect(screen.getByText('Delivery issue')).toBeInTheDocument()
  expect(screen.getByText('Investigation opened')).toBeInTheDocument()
  expect(screen.getByText('Out for delivery')).toBeInTheDocument()
  // Return-leg row.
  expect(screen.getByText('Returning')).toBeInTheDocument()

  // No raw eventKey string ever visible as rendered text.
  for (const key of BACKEND_EVENT_KEYS) {
    expect(screen.queryByText(key)).toBeNull()
  }
})

test('drawer renders the same rows correctly in ar/RTL, including picked_up interpolation', async () => {
  renderDrawer(stubFetch(makeOrderDetail(), makeMixedTimeline()), 'ar')

  expect(await screen.findByText('تم إنشاء الطلب')).toBeInTheDocument()
  expect(screen.getByText('تم الالتقاط بواسطة Ahmed Mostafa')).toBeInTheDocument()
  expect(screen.getByText('مشكلة في التوصيل')).toBeInTheDocument()
  expect(screen.getByText('جاري الإرجاع')).toBeInTheDocument()

  for (const key of BACKEND_EVENT_KEYS) {
    expect(screen.queryByText(key)).toBeNull()
  }
})

test('picked_up with a null actor falls back to the System label, not a blank/undefined', async () => {
  const timeline: TimelineItem[] = [
    { occurredAt: '2026-08-11T11:00:00Z', eventKey: 'picked_up', actorName: null, locationName: null, detail: null, kind: 'now' },
  ]
  renderDrawer(stubFetch(makeOrderDetail(), timeline))
  expect(await screen.findByText('Picked by System')).toBeInTheDocument()
})

test('timeline fetch failure shows its own error state WITHOUT blocking order header/items', async () => {
  renderDrawer(stubFetch(makeOrderDetail(), 'error'))

  // Order header renders immediately regardless of the timeline fetch's outcome.
  expect(await screen.findByText('#2212094474')).toBeInTheDocument()
  expect(screen.getByText('Habiba Ali')).toBeInTheDocument()
  expect(screen.getByText('Bikini Pink')).toBeInTheDocument()

  // Timeline section shows its own error, not a blank/hung skeleton.
  await waitFor(() => expect(screen.getByText("Couldn't load activity")).toBeInTheDocument())
})

test('genuinely empty timeline shows the honest empty state, not the retired "coming soon" copy', async () => {
  renderDrawer(stubFetch(makeOrderDetail(), []))
  expect(await screen.findByText('No activity recorded yet')).toBeInTheDocument()
  expect(screen.queryByText('Activity timeline coming soon')).toBeNull()
  // "Last updated" footer must be absent — no real last event to report.
  expect(screen.queryByText(/Last updated/)).toBeNull()
})

test('"Last updated" footer derives from the last timeline event, omitted while loading/empty', async () => {
  renderDrawer(stubFetch(makeOrderDetail(), makeMixedTimeline()))
  expect(await screen.findByText(/Last updated/)).toBeInTheDocument()
})
