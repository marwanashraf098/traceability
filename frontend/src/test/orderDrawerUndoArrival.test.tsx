import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, within, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, UntrackedArrival } from '../api'

// Bug 2 (approved 2026-11-16, BRK-47431-EG) — the order drawer lists the untracked units marked Arrived in Scan
// returns under their line, with an owner / manager "Undo arrival" (confirm first) for one whose session closed;
// "received" / "session open" say why it can't be undone; a worker (403) sees nothing of it.

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

afterEach(() => { vi.unstubAllGlobals() })

const ORDER = {
  id: 'order-47431', number: 'BRK-47431-EG', customerName: 'Mona Said', customerPhone: '01022334455',
  address: null, paymentMethod: 'cod', codAmount: 2400, status: 'new', onHold: false,
  holdReason: null, placedAt: '2026-11-08T13:51:58Z', createdAt: '2026-11-08T13:52:05Z',
  items: [
    { id: 'oi-crew', productTitle: 'Boxy Oversized Crewneck – Burgundy', variantTitle: 'M', sku: 'BRK-RGC-2627-BRG-M',
      quantity: 1, imageUrl: null, allocatedPieces: [] },
    { id: 'oi-raw', productTitle: 'Raw Denim Relaxed Fit Jeans', variantTitle: '30', sku: 'BROEK-Raw-Denim-RFD-1',
      quantity: 1, imageUrl: null, allocatedPieces: [] },
    { id: 'oi-gray', productTitle: 'Gray Relaxed Fit Denim Jeans', variantTitle: '30', sku: 'BROEK-Gray-RFD-1',
      quantity: 1, imageUrl: null, allocatedPieces: [] },
  ],
  shipments: [],
  bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
  derivedStatus: {
    primaryKey: 'status.delivered', tone: 'SUCCESS', healthChips: [], historicalNote: null,
    conflictKey: null, notTraced: false, packedConfirmed: true,
    fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
  },
  physicallyWithCourier: false,
} as unknown as OrderDetail

const RAW: UntrackedArrival = { intakeId: '7671830b-7aa3-4cd4-a6c1-d466e7e15d6b', orderItemId: 'oi-raw', unitNo: 1,
  condition: 'damaged', awb: '7139234160', markedBy: 'Mona', markedAt: '2026-11-14T16:44:14Z', undoBlocked: null }
const CREW: UntrackedArrival = { intakeId: '54cae80e-eea5-49cc-aa10-32ce80f7e701', orderItemId: 'oi-crew', unitNo: 1,
  condition: 'sellable', awb: '7139234160', markedBy: 'Mona', markedAt: '2026-11-14T16:44:20Z', undoBlocked: 'received' }

let calls: { url: string; method: string }[] = []

async function openItems(arrivals: UntrackedArrival[] | 'forbidden', lang: 'en' | 'ar' = 'en') {
  calls = []
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  let live = arrivals
  vi.stubGlobal('fetch', vi.fn((url: string, opts?: RequestInit) => {
    calls.push({ url, method: opts?.method ?? 'GET' })
    if (url.includes('/undo')) {
      if (live !== 'forbidden') live = live.filter(a => !url.includes(a.intakeId))
      return json(null, 204)
    }
    if (url.includes('/untracked-arrivals')) return live === 'forbidden' ? json({}, 403) : json(live)
    if (url.includes('/notes') || url.includes('/timeline')) return json([])
    if (url.includes('/orders/')) return json(ORDER)
    return json({})
  }))
  render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-47431" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: lang === 'en' ? 'Items (3)' : 'المنتجات (3)' }))
  return user
}

test('da1 R2: the mistaken damaged mark → Undo arrival → confirm → POST undo, the row is gone', async () => {
  const user = await openItems([RAW, CREW])
  const row = await screen.findByTestId(`drawer-arrival-${RAW.intakeId}`)
  expect(row).toHaveTextContent('Unit 1 came back · damaged · 7139234160')
  await user.click(within(row).getByTestId('drawer-undo-arrival'))
  const dialog = await screen.findByTestId('drawer-undo-arrival-dialog')
  expect(dialog).toHaveTextContent("didn't actually come back in parcel 7139234160")
  expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)
  await user.click(within(dialog).getByRole('button', { name: 'Undo arrival' }))
  await waitFor(() => expect(calls.filter(c => c.method === 'POST').map(c => c.url))
    .toEqual([expect.stringContaining(`/orders/order-47431/untracked-arrivals/${RAW.intakeId}/undo`)]))
  await waitFor(() => expect(screen.queryByTestId(`drawer-arrival-${RAW.intakeId}`)).toBeNull())
  expect(screen.getByTestId(`drawer-arrival-${CREW.intakeId}`)).toBeInTheDocument()
})

test('da2 received / session open: no button, the reason instead', async () => {
  await openItems([CREW, { ...RAW, undoBlocked: 'session_open' }])
  const crew = await screen.findByTestId(`drawer-arrival-${CREW.intakeId}`)
  expect(within(crew).queryByTestId('drawer-undo-arrival')).toBeNull()
  expect(crew).toHaveTextContent('Already received into stock')
  expect(screen.getByTestId(`drawer-arrival-${RAW.intakeId}`)).toHaveTextContent('Undo it in Scan returns (session still open)')
})

test('da3 a worker (403): nothing of it is shown', async () => {
  await openItems('forbidden')
  await screen.findByText('Raw Denim Relaxed Fit Jeans')
  expect(screen.queryByTestId('drawer-undo-arrival')).toBeNull()
  expect(screen.queryByTestId(`drawer-arrival-${RAW.intakeId}`)).toBeNull()
})

test('da4 Arabic', async () => {
  const user = await openItems([RAW], 'ar')
  const row = await screen.findByTestId(`drawer-arrival-${RAW.intakeId}`)
  expect(row).toHaveTextContent('القطعة 1 رجعت · تالفة')
  await user.click(within(row).getByTestId('drawer-undo-arrival'))
  expect(await screen.findByTestId('drawer-undo-arrival-dialog')).toHaveTextContent('لم ترجع القطعة فعلاً في الطرد')
})
