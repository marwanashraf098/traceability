import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'
import OrderDetail, { computeStepperSteps } from '../pages/OrderDetail'
import type {
  OrderPage, OrderSummary, OrderDetail as IOrderDetail, ShipmentDetail, DerivedOrderStatus,
  OrderSummaryCounts,
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
    packedConfirmed: false,
    fulfillmentKey: 'status.new',
    fulfillmentTone: 'NEUTRAL',
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
    isExchange: false,
    derivedStatus: makeDerivedStatus(),
    ...overrides,
  }
}

function makeOrderPage(items: OrderSummary[], overrides: Partial<OrderPage> = {}): OrderPage {
  return { items, page: 0, size: 20, total: items.length, ...overrides }
}

function makeOrderSummaryCounts(overrides: Partial<OrderSummaryCounts> = {}): OrderSummaryCounts {
  return { total: 3, processing: 1, withCourier: 1, delivered: 1, returned: 0, ...overrides }
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
    isExchange: false,
    derivedStatus: makeDerivedStatus(),
    shopifyOrderUrl: null,
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
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('table')).toBeInTheDocument())
  })

  test('empty state shows the sync CTA, not a bare table', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([]))
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(await screen.findByText('No orders found')).toBeInTheDocument()
    expect(document.querySelector('table')).toBeNull()
  })

  test('error state shows an Alert card, not a bare table', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonErr()
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    expect(await screen.findByText('Something went wrong')).toBeInTheDocument()
    expect(document.querySelector('table')).toBeNull()
  })

  test('loaded state renders rows with codAmount-only Amount cell — no payment sub-line', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
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

  test('clicking a tab re-fetches filtered by the shipment deliveryState, not orders.status', async () => {
    // NOT status=delivered — orders.status is never written to 'with_courier'/'returned' by
    // the app (confirmed by grep of FulfillService/ShipmentLinkService), and only reaches
    // 'delivered' for self-pickup handover. The real signal is the shipment's own
    // internal_state, joined via the LATERAL `s` alias list() already selects.
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Delivered/ }))

    await waitFor(() => {
      const calls = appFetch.mock.calls.map(c => c[0]).filter((u): u is string => typeof u === 'string' && u.includes('/orders?'))
      expect(calls.some(u => u.includes('deliveryState=delivered'))).toBe(true)
      expect(calls.some(u => u.includes('status=delivered'))).toBe(false)
    })
  })

  test('tab count and click-through list use the SAME deliveryState predicate (parity)', async () => {
    // Regression guard for the review finding: the tab's count (from /orders/summary)
    // and its click-through list (from /orders?deliveryState=...) must agree on what
    // "In transit" means. Simulates a backend that filters by internal_state and
    // returns exactly the count summary() derived — proves the two paths share one
    // definition (same param, same value), not two that can silently diverge.
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) {
        if (url.includes('deliveryState=with_courier')) {
          return jsonOk(makeOrderPage(
            [makeOrderSummary({ id: 'a' }), makeOrderSummary({ id: 'b' })],
            { total: 2 },
          ))
        }
        return jsonOk(makeOrderPage([makeOrderSummary()]))
      }
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts({ withCourier: 2 }))
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')

    const inTransitTab = screen.getByRole('button', { name: /In transit/ })
    expect(within(inTransitTab).getByText('2')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(inTransitTab)

    expect(await screen.findByText('Showing 1–2 of 2')).toBeInTheDocument()
  })

  test('the Needs attention tab is not clickable/filtering (no raw-status equivalent)', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')

    const user = userEvent.setup()
    appFetch.mockClear()
    await user.click(screen.getByRole('button', { name: /Needs attention/ }))

    // No new /orders? fetch fired — the click is a no-op, per the approved resolution.
    await new Promise(r => setTimeout(r, 50))
    expect(appFetch.mock.calls.some(c => typeof c[0] === 'string' && c[0].includes('/orders?'))).toBe(false)
  })

  test('pagination — Previous disabled on page 1, Next advances to page 2', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) {
        return jsonOk(makeOrderPage(
          Array.from({ length: 20 }, (_, i) => makeOrderSummary({ id: `order-${i}`, number: `#${1000 + i}` })),
          { total: 45 },
        ))
      }
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
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

  test('subheader shows the total; tabs show the buckets from GET /orders/summary', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      if (url.includes('/orders/summary')) {
        return jsonOk(makeOrderSummaryCounts({ total: 12, processing: 5, withCourier: 3, delivered: 3, returned: 1 }))
      }
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')

    expect(await screen.findByText('12 orders')).toBeInTheDocument()

    const allTab       = screen.getByRole('button', { name: /All/ })
    const inTransitTab = screen.getByRole('button', { name: /In transit/ })
    const deliveredTab = screen.getByRole('button', { name: /Delivered/ })
    const returnsTab   = screen.getByRole('button', { name: /Returns/ })

    expect(within(allTab).getByText('12')).toBeInTheDocument()
    expect(within(inTransitTab).getByText('3')).toBeInTheDocument()
    expect(within(deliveredTab).getByText('3')).toBeInTheDocument()
    expect(within(returnsTab).getByText('1')).toBeInTheDocument()
  })

  // TRIPWIRE — proves the calm-fail is real, not assumed. Verified by temporarily changing
  // Orders.tsx's `.catch(() => {})` on the summary fetch to `.catch(() => setError(t('common.error')))`
  // (sharing the table's own error state — the exact regression this test guards against) and
  // re-running: the test failed because the shared error state replaced the table with the
  // Alert card, so '#1001' never rendered. Then reverted.
  test('when GET /orders/summary rejects, no summary row renders and the table still loads normally', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) return jsonOk(makeOrderPage([makeOrderSummary()]))
      if (url.includes('/orders/summary')) return jsonErr()
      return jsonOk({})
    })
    renderOrdersList(appFetch)

    // Table renders normally, unaffected by the summary endpoint's failure.
    expect(await screen.findByText('#1001')).toBeInTheDocument()
    expect(document.querySelector('table')).toBeInTheDocument()

    // No broken/empty tile row — the summary section simply doesn't exist.
    expect(screen.queryByTestId('orders-summary')).toBeNull()

    // No shared error state with the table either — the list's own error Alert never appears.
    expect(screen.queryByText('Something went wrong')).toBeNull()
  })

  // FR-EXCHANGE Part 2 — display-only badge, no filter/restyle/section. Exchanges stay
  // inline with normal orders; the badge is the sole differentiator, reusing the exact
  // <Badge tone="info"> QueueView/PickScreen already render for is_self_pickup.
  test('a mapped exchange order shows the Exchange badge; a normal order does not', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) {
        return jsonOk(makeOrderPage([
          makeOrderSummary({ id: 'order-1', number: '#1001', isExchange: false }),
          makeOrderSummary({ id: 'order-2', number: '#EXC-2', isExchange: true }),
        ]))
      }
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')
    await screen.findByText('#EXC-2')

    const normalRow = screen.getByText('#1001').closest('tr')!
    const exchangeRow = screen.getByText('#EXC-2').closest('tr')!
    expect(within(normalRow).queryByText('Exchange')).toBeNull()
    expect(within(exchangeRow).getByText('Exchange')).toBeInTheDocument()
  })

  test('Delivery cell: no shipment shows "Not shipped"; a linked shipment shows the delivery facet', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders?')) {
        return jsonOk(makeOrderPage([
          makeOrderSummary({ id: 'order-1', number: '#1001', deliveryState: null }),
          makeOrderSummary({
            id: 'order-2', number: '#1002', deliveryState: 'with_courier',
            derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit', tone: 'INFO' }),
          }),
        ]))
      }
      if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
      return jsonOk({})
    })
    renderOrdersList(appFetch)
    await screen.findByText('#1001')
    await screen.findByText('#1002')

    const noShipmentRow  = screen.getByText('#1001').closest('tr')!
    const shipmentRow    = screen.getByText('#1002').closest('tr')!
    expect(within(noShipmentRow).getByText('Not shipped')).toBeInTheDocument()
    expect(within(noShipmentRow).queryByText('In transit')).toBeNull()
    expect(within(shipmentRow).getByText('In transit')).toBeInTheDocument()
    expect(within(shipmentRow).queryByText('Not shipped')).toBeNull()
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

  // FR-EXCHANGE Part 2 — same badge, next to the header title. Display-only.
  test('exchange order detail header shows the Exchange badge; a normal order does not', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) return jsonOk(makeOrderDetail({ isExchange: true }))
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')
    expect(screen.getByText('Exchange')).toBeInTheDocument()
  })

  test('normal order detail header shows no Exchange badge', async () => {
    const appFetch = vi.fn((url: string) => {
      if (url.includes('/orders/order-1')) return jsonOk(makeOrderDetail({ isExchange: false }))
      return jsonOk({})
    })
    renderOrderDetailPage(appFetch)
    await screen.findByText('#1001')
    expect(screen.queryByText('Exchange')).toBeNull()
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
    // Status-split fix: at internalState='created' with the fixture's default order.status
    // ('new'), a failed attempt this early does NOT prove packing happened (it can be a
    // failed PICKUP attempt — courier came before the order was ready) — Packed correctly
    // shows 'pending', not 'done'. This is a genuine behavior correction from the fix, not a
    // relaxed assertion: the step2 dot is still 'current' at the shipment's real rank, exactly
    // as before — only step1's honesty changed.
    const atCreated = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivery_failed' }),
      shipments: [makeShipment({ internalState: 'created', failedDeliveryAttempts: 1 })],
    })
    expect(computeStepperSteps(atCreated)!.map(s => s.state)).toEqual(['done', 'pending', 'current', 'pending', 'pending'])

    // At internalState='with_courier', the courier physically holding the parcel IS proof
    // packing happened, regardless of order.status — Packed correctly shows 'done'.
    const atWithCourier = makeOrderDetail({
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.delivery_failed' }),
      shipments: [makeShipment({ internalState: 'with_courier', failedDeliveryAttempts: 1 })],
    })
    expect(computeStepperSteps(atWithCourier)!.map(s => s.state)).toEqual(['done', 'done', 'done', 'current', 'pending'])
  })

  // ── Slice 1 (status-split fix): packedReached ───────────────────────────────
  // Each of these is a tripwire — prove it fails first by reverting packedReached to the
  // old `stepState(1)` (i.e. `1 < currentIndex`), then restore.

  test('SLICE1(a) — webhook-matched never-packed order: Packed not done, With-courier lit, grey line between', () => {
    // The core bug this fix targets: a shipment exists at label_created (rank 1) while
    // order.status is still pre-pack ('picking') — ShipmentLinkService.tryMatchDelivery()'s
    // guarded packed->awaiting_pickup flip is a documented no-op here since packing never
    // happened. No phantom "Packed" checkmark.
    const order = makeOrderDetail({
      status: 'picking',
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.label_created' }),
      shipments: [makeShipment({ internalState: 'created' })],
    })
    const steps = computeStepperSteps(order)!
    expect(steps.map(s => s.state)).toEqual(['done', 'pending', 'current', 'pending', 'pending'])
    expect(steps[1].key).toBe('packed')
    expect(steps[2].key).toBe('with_courier')
  })

  test('SLICE1(b) — packer-first order (order.status="packed"): Packed done, even with no shipment yet', () => {
    // The normal, gated linkByAwbScan() flow: order.status reaches 'packed' before any
    // shipment exists.
    const order = makeOrderDetail({
      status: 'packed',
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.packed' }),
      shipments: [],
    })
    expect(computeStepperSteps(order)!.map(s => s.state)).toEqual(['done', 'done', 'pending', 'pending', 'pending'])
  })

  test('SLICE1(c) — courier physically holding the parcel proves packing, even with order.status stuck pre-pack', () => {
    // order.status never reached 'packed' in Traced's own record (the guarded flip to
    // awaiting_pickup only fires once, at shipment-creation time — not on every later
    // shipment progression) — but the courier holding the parcel is unambiguous physical
    // proof packing happened.
    const order = makeOrderDetail({
      status: 'picking',
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.in_transit' }),
      shipments: [makeShipment({ internalState: 'with_courier' })],
    })
    expect(computeStepperSteps(order)!.map(s => s.state)).toEqual(['done', 'done', 'done', 'current', 'pending'])
  })

  test('SLICE1(d) — a shipment-cancelled order never reaches the frontend "Packed not done" branch at all, because it is fully suppressed', () => {
    // Any forward shipment reaching internal_state='cancelled' is a TERMINAL_STATES member,
    // so OrderStatusDeriver.derive() always produces primaryKey='status.cancelled' for it,
    // regardless of order.status — and 'status.cancelled' is already in STEPPER_SUPPRESS_KEYS.
    // So on the frontend, "cancelled is not proof of packing" is moot by construction: there is
    // no reachable non-suppressed primaryKey for a shipment-cancelled order to test packedReached
    // against. The exclusion is real and load-bearing on the BACKEND (funnel()/summary()'s
    // packedConfirmed, `terminal && !"cancelled".equals(shipmentInternalState)`), where it's
    // covered by OrderStatusListDetailParityTest.slice1_cancelledShipment_notProofOfPacking_
    // packedConfirmedFalse — asserting the frontend suppression here instead of an unreachable,
    // fabricated primaryKey/shipment-state combination.
    const order = makeOrderDetail({
      status: 'picking',
      derivedStatus: makeDerivedStatus({ primaryKey: 'status.cancelled' }),
      shipments: [makeShipment({ internalState: 'cancelled' })],
    })
    expect(computeStepperSteps(order)).toBeNull()
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
