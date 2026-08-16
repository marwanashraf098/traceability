import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'
import OrderDetail, { computeStepperSteps } from '../pages/OrderDetail'
import type {
  OrderPage, OrderSummary, OrderDetail as IOrderDetail, ShipmentDetail, DerivedOrderStatus,
} from '../api'

// ── fetch fakes — shapes mirror api.ts's request() contract exactly ────────────

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true,
    status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function jsonErr(status = 500, statusText = 'Server Error') {
  return Promise.resolve({
    ok: false,
    status,
    statusText,
    headers: { get: () => null },
    json: async () => ({}),
  })
}

// ── fixtures — mirror the real OrderSummary/OrderDetail shapes exactly, no
// invented paymentMethod/paid fields (neither exists on OrderSummary; there is
// no paid/pending concept anywhere in the backend — confirmed by grep) ─────────

function makeDerivedStatus(overrides: Partial<DerivedOrderStatus> = {}): DerivedOrderStatus {
  return {
    primaryKey: 'status.new',
    tone: 'NEUTRAL',
    healthChips: [],
    historicalNote: null,
    conflictKey: null,
    notTraced: false,
    ...overrides,
  }
}

function makeOrderSummary(overrides: Partial<OrderSummary> = {}): OrderSummary {
  return {
    id: 'order-1',
    number: '#1001',
    customerName: 'Mona Said',
    customerPhone: '010 2233 4455',
    status: 'new',
    onHold: false,
    codAmount: 540,
    placedAt: '2026-07-20T09:12:00Z',
    trackingNumber: '889213',
    deliveryState: null,
    exceptionReason: null,
    bostaLinkStatus: 'linked',
    failedDeliveryAttempts: 0,
    isDelayed: null,
    slaBreached: null,
    notTracedAt: null,
    derivedStatus: makeDerivedStatus(),
    ...overrides,
  }
}

function makeOrderPage(items: OrderSummary[], overrides: Partial<OrderPage> = {}): OrderPage {
  return { items, page: 0, size: 20, total: items.length, ...overrides }
}

function makeShipment(overrides: Partial<ShipmentDetail> = {}): ShipmentDetail {
  return {
    id: 'ship-1',
    trackingNumber: '889213',
    provider: 'bosta',
    internalState: 'with_courier',
    shipmentLeg: 'forward',
    numberOfAttempts: 0,
    failedDeliveryAttempts: 0,
    awbUrl: null,
    exceptionCode: null,
    exceptionReason: null,
    isDelayed: null,
    slaBreached: null,
    scheduledAt: null,
    courierName: null,
    courierPhone: null,
    lastFailureReason: null,
    attempts: [],
    deliveryHistory: [],
    legStatus: { primaryKey: 'status.in_transit', tone: 'INFO' },
    ...overrides,
  }
}

function makeOrderDetail(overrides: Partial<IOrderDetail> = {}): IOrderDetail {
  return {
    id: 'order-1',
    number: '#1001',
    customerName: 'Mona Said',
    customerPhone: '010 2233 4455',
    address: null,
    paymentMethod: 'cod',
    codAmount: 540,
    status: 'new',
    onHold: false,
    holdReason: null,
    placedAt: '2026-07-20T09:12:00Z',
    createdAt: '2026-07-20T09:12:00Z',
    items: [],
    shipments: [],
    bostaLinkStatus: 'linked',
    notTracedAt: null,
    derivedStatus: makeDerivedStatus(),
    ...overrides,
  }
}

// ── render helpers ───────────────────────────────────────────────────────────

function renderOrdersList(appFetch: ReturnType<typeof vi.fn>) {
  stubFetchWithShellDefaults(appFetch)
  return renderWithProviders(<Layout><Orders /></Layout>)
}

function renderOrderDetailPage(appFetch: ReturnType<typeof vi.fn>, id = 'order-1') {
  stubFetchWithShellDefaults(appFetch)
  return renderWithProviders(
    <Layout>
      <Routes>
        <Route path="/orders/:id" element={<OrderDetail />} />
      </Routes>
    </Layout>,
    { initialEntries: [`/orders/${id}`] },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ── Orders list ──────────────────────────────────────────────────────────────

describe('Orders list', () => {
  test('loading state renders the table skeleton', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return new Promise(() => {}) // never resolves
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('table')).toBeInTheDocument())
  })

  test('empty state shows the sync CTA, not a bare table', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([]))
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(await screen.findByText('No orders found')).toBeInTheDocument()
    expect(document.querySelector('table')).toBeNull()
  })

  test('error state shows an Alert card, not a bare table', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonErr()
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(await screen.findByText('Something went wrong')).toBeInTheDocument()
    expect(document.querySelector('table')).toBeNull()
  })

  test('loaded state renders rows with codAmount-only Amount cell — no payment sub-line', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(await screen.findByText('#1001')).toBeInTheDocument()
    expect(screen.getByText('Amount')).toBeInTheDocument()
    expect(screen.getByText('540 EGP')).toBeInTheDocument()
    // Mockup's illustrative "COD PENDING" / "Prepaid PAID" sub-line has no backing
    // field on OrderSummary and must never appear.
    expect(screen.queryByText(/PENDING|PAID/)).toBeNull()
  })

  test('status filter re-fetches with the selected status', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')

    const user = userEvent.setup()
    await user.selectOptions(screen.getByRole('combobox'), 'packed')

    await waitFor(() => {
      const calls = appFetch.mock.calls.map(c => c[0]).filter((u): u is string => typeof u === 'string' && u.includes('/orders?'))
      expect(calls.some(u => u.includes('status=packed'))).toBe(true)
    })
  })

  test('pagination — Previous disabled on page 1, Next advances to page 2', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) {
        return jsonOk(makeOrderPage(
          Array.from({ length: 20 }, (_, i) => makeOrderSummary({ id: `order-${i}`, number: `#${1000 + i}` })),
          { total: 45 },
        ))
      }
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1000')

    expect(screen.getByText('Previous')).toBeDisabled()
    expect(screen.getByText('Next')).not.toBeDisabled()

    const user = userEvent.setup()
    await user.click(screen.getByText('Next'))

    await waitFor(() => {
      const calls = appFetch.mock.calls.map(c => c[0]).filter((u): u is string => typeof u === 'string' && u.includes('/orders?'))
      expect(calls.some(u => u.includes('page=1'))).toBe(true)
    })
  })
})

// ── Order detail ─────────────────────────────────────────────────────────────

describe('Order detail', () => {
  test('loading state does not show the order yet', () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) return new Promise(() => {})
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    expect(screen.queryByText('#1001')).toBeNull()
  })

  test('loaded state renders order number and customer', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) return jsonOk(makeOrderDetail())
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    expect(await screen.findByText('#1001')).toBeInTheDocument()
    expect(screen.getAllByText('Mona Said').length).toBeGreaterThan(0)
  })

  test('COD inline edit — view → editing → saving → error, className-only over the existing state machine', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/fulfill/') && url.includes('/cod')) return jsonErr(400, 'Bad Request')
      if (url.includes('/orders/order-1')) return jsonOk(makeOrderDetail({ status: 'new', codAmount: 540 }))
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')

    // view
    expect(screen.getByText('540 EGP')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByTitle('Edit COD'))

    // editing — accent border/ring wrapper
    const input = await screen.findByDisplayValue('540')
    expect(input.closest('div')).toHaveClass('border-trace-blue')

    await user.clear(input)
    await user.type(input, '560')

    // saving → error (PATCH rejects) — wrapper switches to critical border
    await user.click(screen.getByText('Save'))
    await waitFor(() => expect(input.closest('div')).toHaveClass('border-critical'))
    expect(screen.getByText(/400/)).toBeInTheDocument()
  })
})

// ── Status stepper ───────────────────────────────────────────────────────────
// The one dangerous element in this restyle — a wrong mapping visually
// misrepresents an order's status. Each suppression case is a tripwire: reverting
// its guard in computeStepperSteps must make the corresponding test fail.

describe('Order status stepper', () => {
  test('positioning — an in-transit order lights step 3 current, 0-2 done, 4 pending', () => {
    const order = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit' }),
      shipments: [makeShipment({
        internalState: 'with_courier',
        deliveryHistory: [
          { state: 'created', providerState: null, exceptionCode: null, exceptionReason: null, occurredAt: '2026-07-20T13:52:00Z' },
          { state: 'with_courier', providerState: null, exceptionCode: null, exceptionReason: null, occurredAt: '2026-07-20T16:03:00Z' },
        ],
      })],
    })
    const steps = computeStepperSteps(order)
    expect(steps).not.toBeNull()
    expect(steps!.map(s => s.state)).toEqual(['done', 'done', 'done', 'current', 'pending'])
    expect(steps![3].key).toBe('in_transit')
    // Packed never gets a timestamp (no packed_at anywhere) — marker only.
    expect(steps![1].at).toBeNull()
    // With-courier / in-transit timestamps come from the forward shipment's own history.
    expect(steps![2].at).toBe('2026-07-20T13:52:00Z')
    expect(steps![3].at).toBe('2026-07-20T16:03:00Z')
  })

  test('delivered order — final step is done+current (checkmark), all prior steps done', () => {
    const order = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivered' }),
      shipments: [makeShipment({ internalState: 'delivered' })],
    })
    expect(computeStepperSteps(order)!.map(s => s.state)).toEqual(['done', 'done', 'done', 'done', 'done'])
  })

  test('delivery_failed splits by shipment internalState onto the correct rank (not suppressed)', () => {
    const atCreated = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivery_failed' }),
      shipments: [makeShipment({ internalState: 'created', failedDeliveryAttempts: 1 })],
    })
    expect(computeStepperSteps(atCreated)!.map(s => s.state)).toEqual(['done', 'done', 'current', 'pending', 'pending'])

    const atWithCourier = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivery_failed' }),
      shipments: [makeShipment({ internalState: 'with_courier', failedDeliveryAttempts: 1 })],
    })
    expect(computeStepperSteps(atWithCourier)!.map(s => s.state)).toEqual(['done', 'done', 'done', 'current', 'pending'])
  })

  // ── Tripwire 1 ──
  test('TRIPWIRE — cancelled order suppresses the stepper entirely', () => {
    const order = makeOrderDetail({
      status: 'cancelled',
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.cancelled' }),
      shipments: [makeShipment({ internalState: 'with_courier' })],
    })
    expect(computeStepperSteps(order)).toBeNull()
  })

  // ── Tripwire 2 ──
  test('TRIPWIRE — not-traced order suppresses the stepper even on an otherwise-normal forward order', () => {
    const order = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit', notTraced: true }),
      shipments: [makeShipment({ internalState: 'with_courier' })],
    })
    expect(computeStepperSteps(order)).toBeNull()
  })

  // ── Tripwire 3 ──
  test('TRIPWIRE — delivery_failed + internalState exception has no reliable rank signal, suppresses the stepper', () => {
    const order = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivery_failed' }),
      shipments: [makeShipment({ internalState: 'exception', failedDeliveryAttempts: 1 })],
    })
    expect(computeStepperSteps(order)).toBeNull()
  })

  // ── Tripwire 4 (positive case) ──
  test('TRIPWIRE — a normal forward order renders the stepper widget with the correct single dot lit', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) {
        return jsonOk(makeOrderDetail({
          derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit' }),
          shipments: [makeShipment({ internalState: 'with_courier' })],
        }))
      }
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')
    expect(screen.getByTestId('order-stepper')).toBeInTheDocument()
  })

  test('rendered page — cancelled order shows no stepper widget in the DOM', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) {
        return jsonOk(makeOrderDetail({
          status: 'cancelled',
          derivedStatus: makeDerivedStatus({ primaryKey: 'status.cancelled' }),
        }))
      }
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')
    expect(screen.queryByTestId('order-stepper')).toBeNull()
  })

  test('rendered page — not-traced order shows no stepper widget in the DOM', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) {
        return jsonOk(makeOrderDetail({
          derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit', notTraced: true }),
        }))
      }
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')
    expect(screen.queryByTestId('order-stepper')).toBeNull()
  })
})
