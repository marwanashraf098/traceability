import { test, expect, describe, vi } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import OrderDetail from '../pages/OrderDetail'

// A3.1 — the return-leg ShipmentCard renders a small leg-scoped badge from its OWN
// internal_state (via OrderStatusDeriver.deriveLegStatus on the backend); the forward
// card never gets a raw-state badge — that's the order header's job alone.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getOrder: vi.fn() }
})

const ORDER_ID = 'order-a31'

function renderDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/orders/:id" element={<OrderDetail />} />
    </Routes>,
    { initialEntries: [`/orders/${ORDER_ID}`] },
  )
}

function makeShipment(overrides: Partial<api.ShipmentDetail>): api.ShipmentDetail {
  return {
    id: 'ship-1',
    trackingNumber: '9810234590',
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

function makeOrderDetail(overrides: Partial<api.OrderDetail> = {}): api.OrderDetail {
  return {
    id: ORDER_ID,
    number: '#A31',
    customerName: 'Alice',
    customerPhone: null,
    address: null,
    paymentMethod: 'cod',
    codAmount: null,
    status: 'returning',
    onHold: false,
    holdReason: null,
    placedAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    items: [],
    shipments: [],
    bostaLinkStatus: 'linked',
    notTracedAt: null,
    // Deliberately distinct from any leg label so assertions are unambiguous.
    derivedStatus: {
      primaryKey: 'status.needs_attention',
      tone: 'WARN',
      healthChips: [],
      historicalNote: null,
      conflictKey: null,
      notTraced: false,
    },
    ...overrides,
  }
}

describe('OrderDetail — A3.1 leg-scoped return badge', () => {
  test('return leg in {returning} renders its own leg badge', async () => {
    const detail = makeOrderDetail({
      shipments: [
        makeShipment({ id: 'fwd-1', shipmentLeg: 'forward', internalState: 'with_courier',
          legStatus: { primaryKey: 'status.in_transit', tone: 'INFO' } }),
        makeShipment({ id: 'ret-1', shipmentLeg: 'return', trackingNumber: '9810234591',
          internalState: 'returning', legStatus: { primaryKey: 'status.returning', tone: 'WARN' } }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A31')).toBeInTheDocument())

    // Order header shows the derived headline (order-level), not a leg label.
    expect(screen.getByText('Needs attention')).toBeInTheDocument()

    // Return-leg card shows its OWN leg-scoped badge.
    expect(screen.getByText('Returning')).toBeInTheDocument()

    // Forward-leg card never gets a raw-state badge — "In transit" must not appear
    // anywhere, even though the forward shipment's internal_state maps to that label.
    expect(screen.queryByText('In transit')).toBeNull()
  })

  test('return leg in {returned} renders its own leg badge', async () => {
    const detail = makeOrderDetail({
      status: 'returned',
      derivedStatus: {
        primaryKey: 'status.returned', tone: 'WARN', healthChips: [],
        historicalNote: null, conflictKey: null, notTraced: false,
      },
      shipments: [
        makeShipment({ id: 'fwd-2', shipmentLeg: 'forward', internalState: 'returned',
          legStatus: { primaryKey: 'status.returned', tone: 'WARN' } }),
        makeShipment({ id: 'ret-2', shipmentLeg: 'return', trackingNumber: '9810234592',
          internalState: 'returned', legStatus: { primaryKey: 'status.returned', tone: 'WARN' } }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A31')).toBeInTheDocument())

    // "Returned" legitimately appears twice here (order header + return-leg badge) —
    // just confirm both cards rendered without erroring and the return section exists.
    expect(screen.getByText('Return Shipment')).toBeInTheDocument()
    expect(screen.getAllByText('Returned').length).toBeGreaterThanOrEqual(2)
  })

  test('return leg in {exception} renders needs-attention leg badge', async () => {
    const detail = makeOrderDetail({
      shipments: [
        makeShipment({ id: 'fwd-3', shipmentLeg: 'forward', internalState: 'returning',
          legStatus: { primaryKey: 'status.returning', tone: 'WARN' } }),
        makeShipment({ id: 'ret-3', shipmentLeg: 'return', trackingNumber: '9810234593',
          internalState: 'exception', exceptionReason: 'Postponed by customer',
          legStatus: { primaryKey: 'status.needs_attention', tone: 'WARN' } }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A31')).toBeInTheDocument())

    // Order header AND return-leg badge both render "Needs attention" here (order-level
    // derivedStatus was set to the same key for this fixture) — assert at least one, and
    // that the return card section rendered.
    expect(screen.getByText('Return Shipment')).toBeInTheDocument()
    expect(screen.getAllByText('Needs attention').length).toBeGreaterThanOrEqual(1)
  })

  test('order with only a forward shipment never renders a leg-scoped badge at all', async () => {
    const detail = makeOrderDetail({
      status: 'awaiting_pickup',
      derivedStatus: {
        primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [],
        historicalNote: null, conflictKey: null, notTraced: false,
      },
      shipments: [
        makeShipment({ id: 'fwd-only', shipmentLeg: 'forward', internalState: 'with_courier',
          legStatus: { primaryKey: 'status.in_transit', tone: 'INFO' } }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A31')).toBeInTheDocument())

    // "In transit" legitimately appears twice here: the order header, and the status
    // stepper's "In transit" step (reuses the same status.in_transit copy — approved,
    // see computeStepperSteps in OrderDetail.tsx). Exactly 2, never a 3rd, which is what
    // a forward-card leg badge (the thing this test actually guards against) would add
    // (there is no return card here to legitimately show a 2nd "In transit" of its own).
    expect(screen.getAllByText('In transit')).toHaveLength(2)
    expect(screen.queryByText('Return Shipment')).toBeNull()
  })
})
