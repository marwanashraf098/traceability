import { test, expect, afterEach, vi } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail } from '../api'

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

// Mirrors ApiExceptionHandler's bare-ResponseStatusException handler, which returns
// status only, no body — request() never gets a JSON message to read for this case.
function jsonConflict() {
  return Promise.resolve({
    ok: false,
    status: 409,
    statusText: 'Conflict',
    headers: { get: () => null },
    json: async () => ({}),
  })
}

function makeOrderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#2212094474', customerName: 'Habiba Ali', customerPhone: '01211000010',
    address: null, paymentMethod: 'cod', codAmount: 990, status: 'awaiting_pickup', onHold: false,
    holdReason: null, placedAt: '2026-08-11T10:42:00Z', createdAt: '2026-08-11T10:42:00Z',
    items: [{ id: 'item-1', productTitle: 'Bikini Pink', variantTitle: 'M', sku: 'SN-BIK-PNK-M', quantity: 1, imageUrl: null, allocatedPieces: [] }],
    shipments: [],
    bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: true,
      fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
    },
    physicallyWithCourier: false,
    ...overrides,
  }
}

// Stateful stub so a successful Cancel POST actually changes what the drawer's
// subsequent refetch (reload()) sees — proves the refetch happened, not just that a
// local flag flipped. cancelStatus lets a test simulate the 409 gate race (server
// still enforces it even though the button was clickable at click-time).
function stubFetch(initialOrder: OrderDetail, cancelStatus: 200 | 409 = 200) {
  let order = initialOrder
  const cancelCalls: string[] = []
  const fn = vi.fn((url: string, init?: RequestInit) => {
    if (url.includes('/cancel') && init?.method === 'POST') {
      cancelCalls.push(url)
      if (cancelStatus === 409) return jsonConflict()
      order = { ...order, status: 'cancelled' }
      return jsonOk({ status: 'cancelled', message: 'Order cancelled', remainingPacked: 0 })
    }
    if (url.includes('/notes')) return jsonOk([])
    if (url.includes('/timeline')) return jsonOk([])
    if (url.includes('/orders/')) return jsonOk(order)
    return jsonOk({})
  })
  return { fn, cancelCalls }
}

function renderDrawer(fetchImpl: ReturnType<typeof vi.fn>, lang: 'en' | 'ar' = 'en') {
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

// ── a: ungated, non-terminal — Cancel enabled, confirm fires cancelOrder, order refetches ──

test('a: physicallyWithCourier=false, non-terminal — Cancel enabled; confirming cancels and refetches', async () => {
  const { fn, cancelCalls } = stubFetch(makeOrderDetail())
  const user = userEvent.setup()
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  const cancelBtn = screen.getByRole('button', { name: 'Cancel order' })
  expect(cancelBtn).toBeEnabled()

  await user.click(cancelBtn)
  const bodyText = await screen.findByText("Are you sure you want to cancel this order? This can't be undone.")
  // Scope into the modal — its own confirm button shares the outer button's accessible
  // name ("Cancel order"), and the outer one stays mounted underneath (same pattern as
  // exceptionsResolve.test.tsx's Resolve-dialog collision).
  const modal = bodyText.closest('.bg-surface') as HTMLElement
  await user.click(within(modal).getByRole('button', { name: 'Cancel order' }))

  await waitFor(() => expect(cancelCalls).toEqual(['/api/v1/fulfill/order-1/cancel']))
  // Refetch happened and the drawer reflects the new (now terminal) status — the
  // Hold/Cancel row hides itself, same as it already does for any terminal order.
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument())
})

// ── b: gated state — Hold AND Cancel both disable; Unhold stays enabled ──────────────

// Positive control for the pair below: this is the "revert-to-confirm" companion —
// there is no backend-style toggle to flip in a frontend test, but hardcoding the
// disabled prop to true (or removing it) fails THIS test, while hardcoding it to
// false (or removing it) fails the gated test right after. Only reading
// order.physicallyWithCourier as-is passes both.
test('b1: physicallyWithCourier=false — Hold and Cancel are both enabled, no reason text', async () => {
  const { fn } = stubFetch(makeOrderDetail({ physicallyWithCourier: false }))
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  expect(screen.getByRole('button', { name: 'Hold order' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'Cancel order' })).toBeEnabled()
  expect(screen.queryByText(/handed to courier/i)).not.toBeInTheDocument()
})

test('b2: physicallyWithCourier=true, not on hold — Hold and Cancel both disabled, reason shown', async () => {
  const { fn } = stubFetch(makeOrderDetail({ physicallyWithCourier: true, onHold: false }))
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  expect(screen.getByRole('button', { name: 'Hold order' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Cancel order' })).toBeDisabled()
  expect(screen.getByText(
    'Order has been handed to courier — contact Bosta to arrange cancellation. ' +
    'Pieces will be released once the courier returns them.'
  )).toBeInTheDocument()
})

test('b3: physicallyWithCourier=true, on hold — Unhold stays enabled, Cancel disabled', async () => {
  const { fn } = stubFetch(makeOrderDetail({ physicallyWithCourier: true, onHold: true, holdReason: 'test' }))
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  expect(screen.getByRole('button', { name: 'Release hold' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'Cancel order' })).toBeDisabled()
  // Hold button itself isn't rendered while on_hold (Unhold replaces it) — nothing to
  // assert disabled about a button that isn't there.
  expect(screen.queryByRole('button', { name: 'Hold order' })).not.toBeInTheDocument()
})

// ── c: 409 from cancelOrder — server message shown, no crash ────────────────────────

test('c: 409 from cancelOrder shows the gated reason and does not crash the drawer', async () => {
  const { fn, cancelCalls } = stubFetch(makeOrderDetail(), 409)
  const user = userEvent.setup()
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  await user.click(screen.getByRole('button', { name: 'Cancel order' }))
  const bodyText = await screen.findByText("Are you sure you want to cancel this order? This can't be undone.")
  const modal = bodyText.closest('.bg-surface') as HTMLElement
  await user.click(within(modal).getByRole('button', { name: 'Cancel order' }))

  await waitFor(() => expect(cancelCalls).toEqual(['/api/v1/fulfill/order-1/cancel']))
  expect(await screen.findByText(
    'Order has been handed to courier — contact Bosta to arrange cancellation. ' +
    'Pieces will be released once the courier returns them.'
  )).toBeInTheDocument()
  // Drawer is still alive, not crashed — order header and dialog are both still there.
  expect(screen.getByText('#2212094474')).toBeInTheDocument()
})

// ── d: full flow in ar/RTL ───────────────────────────────────────────────────────────

test('d: full cancel flow renders and works in ar/RTL', async () => {
  const { fn, cancelCalls } = stubFetch(makeOrderDetail())
  const user = userEvent.setup()
  renderDrawer(fn, 'ar')

  await screen.findByText('#2212094474')
  const cancelBtn = screen.getByRole('button', { name: 'إلغاء الطلب' })
  expect(cancelBtn).toBeEnabled()

  await user.click(cancelBtn)
  const bodyText = await screen.findByText('هل تريد إلغاء هذا الطلب؟ لا يمكن التراجع عن هذا الإجراء.')
  const modal = bodyText.closest('.bg-surface') as HTMLElement
  await user.click(within(modal).getByRole('button', { name: 'إلغاء الطلب' }))

  await waitFor(() => expect(cancelCalls).toEqual(['/api/v1/fulfill/order-1/cancel']))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'إلغاء الطلب' })).not.toBeInTheDocument())
})

test('d2: ar/RTL gated state — Hold and Cancel disabled with Arabic reason text', async () => {
  const { fn } = stubFetch(makeOrderDetail({ physicallyWithCourier: true, onHold: false }))
  renderDrawer(fn, 'ar')

  await screen.findByText('#2212094474')
  expect(screen.getByRole('button', { name: 'تعليق الطلب' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'إلغاء الطلب' })).toBeDisabled()
  expect(screen.getByText(
    'تم تسليم الطلب للمندوب — يرجى التواصل مع Bosta لترتيب الإلغاء. سيتم تحرير القطع بعد استرجاعها من المندوب.'
  )).toBeInTheDocument()
})
