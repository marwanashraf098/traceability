import { test, expect, describe, vi } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import OrderDetail from '../pages/OrderDetail'

// Display-only copy commit: the delivery-attempts-block succeeded label is now keyed on the
// shipment's OWN leg — return leg reads "Return completed" instead of the generic
// "Succeeded". Forward-leg "Succeeded", all "Failed" labels, and the created→"Label created"
// mapping (OrderStatusDeriver) are untouched — no position-inferred relabeling of `created`.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getOrder: vi.fn() }
})

const ORDER_ID = 'order-a428'

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
    trackingNumber: 'a428cde1',
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
    number: '#A428',
    customerName: 'Bob',
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
    derivedStatus: {
      primaryKey: 'status.label_created',
      tone: 'INFO',
      healthChips: [],
      historicalNote: null,
      conflictKey: null,
      notTraced: false,
    },
    ...overrides,
  }
}

describe('OrderDetail — return-leg attempt outcome copy', () => {
  // Fixture a428cde1: forward-leg shipment re-emitted 'created' after a prior with_courier
  // (RTO in progress on the sibling return leg) — the header must still read "Label created",
  // not some return-context relabel of `created` (that would require position-inferred state,
  // which this commit explicitly does not add).
  test('return-leg succeeded attempt reads "Return completed"; forward-leg "Succeeded" and header "Label created" untouched', async () => {
    const detail = makeOrderDetail({
      shipments: [
        makeShipment({
          id: 'fwd-1', trackingNumber: 'a428cde1', shipmentLeg: 'forward',
          internalState: 'created',
          attempts: [{
            attemptDate: '2026-08-01T09:00:00Z', type: 'delivery', succeeded: true,
            courierName: null, courierPhone: null, failureReason: null,
          }],
        }),
        makeShipment({
          id: 'ret-1', trackingNumber: 'a428cde1-r', shipmentLeg: 'return',
          internalState: 'returned', legStatus: { primaryKey: 'status.returned', tone: 'WARN' },
          attempts: [{
            attemptDate: '2026-08-02T09:00:00Z', type: 'return', succeeded: true,
            courierName: null, courierPhone: null, failureReason: null,
          }],
        }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A428')).toBeInTheDocument())

    // Return-leg succeeded attempt — new copy.
    expect(screen.getByText('Return completed')).toBeInTheDocument()

    // Forward-leg succeeded attempt — unchanged, still generic "Succeeded".
    expect(screen.getByText('Succeeded')).toBeInTheDocument()

    // Header still "Label created" — untouched even with a return leg present in the
    // same order and even though the forward shipment is 'created' again post-with_courier.
    expect(screen.getByText('Label created')).toBeInTheDocument()
  })

  test('failed attempts on either leg are untouched by the return-outcome relabel', async () => {
    const detail = makeOrderDetail({
      derivedStatus: {
        primaryKey: 'status.needs_attention', tone: 'WARN', healthChips: [],
        historicalNote: null, conflictKey: null, notTraced: false,
      },
      shipments: [
        makeShipment({
          id: 'ret-2', trackingNumber: 'a428cde1-r2', shipmentLeg: 'return',
          internalState: 'exception', legStatus: { primaryKey: 'status.needs_attention', tone: 'WARN' },
          attempts: [{
            attemptDate: '2026-08-03T09:00:00Z', type: 'return', succeeded: false,
            courierName: null, courierPhone: null, failureReason: 'Customer refused',
          }],
        }),
      ],
    })
    vi.mocked(api.getOrder).mockResolvedValue(detail)
    renderDetail()

    await waitFor(() => expect(screen.getByText('#A428')).toBeInTheDocument())

    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(screen.queryByText('Return completed')).toBeNull()
  })
})
