import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { ExchangeSummary, RefundLeg, ReturnCase } from '../api'

// ExchangeRefundDrawer — a dashboard exchange (B) or a courier return no request holds (C), opened
// from a row of the Returns & exchanges list (Step 2). Moved here from the deleted
// exchangesRefunds.test.tsx (the old merged list): every drawer assertion is kept; the two that
// looked at the old list (the "Matched" row after attach, the Auto-matched row marker) are replaced
// by "the list refreshes" / dropped (the new list has no auto-matched marker; the drawer's banner stays).

// ── Fixtures ──────────────────────────────────────────────────────────────────

const MATCHED_EXCHANGE: ExchangeSummary = {
  id: 'exc-matched',
  tracking_number: '910000001',
  status: 'matched',
  matched_order_id: 'order-original-1',
  match_method: 'phone',
  matched_at: '2026-08-01T10:00:00Z',
  outbound_description: 'Yellow hat',
  inbound_description: 'Red hat',
  inbound_description_ar: null,
  cod: 0,
  goods_value: 600,
  outbound_items_count: 1,
  inbound_items_count: 1,
  customer_name: 'Maya Mostafa',
  customer_phone: '01001234567',
}

const NEEDS_CONFIRMATION_EXCHANGE: ExchangeSummary = {
  ...MATCHED_EXCHANGE,
  id: 'exc-needs-confirmation',
  tracking_number: '910000002',
  status: 'needs_confirmation',
  matched_order_id: null,
  match_method: null,
  matched_at: null,
  customer_name: 'Omar Said',
  customer_phone: '01055554444',
}

const UNMATCHED_EXCHANGE: ExchangeSummary = {
  ...MATCHED_EXCHANGE,
  id: 'exc-unmatched',
  tracking_number: '910000003',
  status: 'unmatched',
  matched_order_id: null,
  match_method: null,
  matched_at: null,
  customer_name: 'Lina Fathy',
  customer_phone: '01066667777',
}

// Build task ("outbound exchange variant: exact-match auto-commit + ranked recs"),
// Part B/C — auto-committed via tryAutoMap(), never a human map() call.
const AUTO_MATCHED_EXCHANGE: ExchangeSummary = {
  ...MATCHED_EXCHANGE,
  id: 'exc-auto-matched',
  tracking_number: '910000004',
  status: 'mapped',
  matched_order_id: null,
  match_method: null,
  matched_at: null,
  auto_matched: true,
  outbound_description: 'XS/S pink & white bandana',
  customer_name: 'Yara Adly',
  customer_phone: '01033332222',
}

const REFUND: RefundLeg = {
  id: 'ship-refund-1',
  tracking_number: 'RFD-TN-001',
  internal_state: 'with_courier',
  order_id: 'order-refund-1',
  order_number: '#RFD-1001',
  customer_name: 'Nour Adel',
  customer_phone: '01098765432',
  leg_status: { primaryKey: 'status.in_transit', tone: 'INFO' },
  inspection_state: 'in_transit',
}

// Step 4-close Part 2 — a returned-and-not-yet-inspected refund leg.
const NEEDS_INSPECTION_REFUND: RefundLeg = {
  id: 'ship-refund-2',
  tracking_number: 'RFD-TN-002',
  internal_state: 'returned',
  order_id: 'order-refund-2',
  order_number: '#RFD-1002',
  customer_name: 'Sara Kamal',
  customer_phone: '01077778888',
  leg_status: { primaryKey: 'status.returned', tone: 'WARN' },
  inspection_state: 'needs_inspection',
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

const CATALOG = {
  products: [
    {
      id: 'product-bandanas', title: 'The Bandanas', status: 'active', imageUrl: null,
      variants: [
        {
          id: 'variant-ml', title: 'M/L / Pink & White', sku: 'BAND-ML-PW', price: null,
          pieceCounts: {
            available: 0, reserved: 0, packed: 0, awaiting_pickup: 0, with_courier: 0,
            delivered: 0, return_in_transit: 0, return_pending_inspection: 0, damaged: 0,
            lost: 0, destroyed: 0, out_on_transfer: 0, sold: 0, total: 0,
          },
          committed: 0, available: 0,
        },
      ],
    },
  ],
}

let attachCalls: Array<{ id: string; body: unknown }> = []
let bareReturnCalls: string[] = []
let dismissCalls: string[] = []
let overrideVariantCalls: Array<{ id: string; body: unknown }> = []
let exchangesOverride: ExchangeSummary[] | null = null
let refundsOverride: RefundLeg[] | null = null

let listFetches = 0

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()

  if (url.includes('/returns-exchanges/counts')) {
    return fakeResponse({ stages: { all: 0, to_do: 0, in_progress: 0, done: 0 },
      tiles: { toApprove: 0, replacementToChoose: 0, toLinkOrder: 0, refundToRecord: 0, bookingProblem: 0 } })
  }
  if (url.includes('/returns-exchanges?')) {
    listFetches++
    const ex = exchangesOverride ?? [MATCHED_EXCHANGE, NEEDS_CONFIRMATION_EXCHANGE, UNMATCHED_EXCHANGE]
    return fakeResponse({ items: [...ex.map(caseOf), ...(refundsOverride ?? [REFUND]).map(caseOfLeg)], nextCursor: null })
  }

  if (url.includes('/api/v1/exchanges/') && url.includes('/candidates')) {
    return fakeResponse([
      { pieceId: 'piece-a', orderId: 'order-candidate-a', variantTitle: 'Red', productTitle: 'Bucket Hat' },
      { pieceId: 'piece-b', orderId: 'order-candidate-b', variantTitle: 'Blue', productTitle: 'Bucket Hat' },
    ])
  }
  if (url.includes('/api/v1/exchanges/') && url.endsWith('/attach') && method === 'POST') {
    const id = url.split('/exchanges/')[1].split('/attach')[0]
    const body = JSON.parse(opts.body as string)
    attachCalls.push({ id, body })
    return fakeResponse({ exchangeId: id, matchedOrderId: body.orderId, status: 'matched' })
  }
  if (url.includes('/api/v1/exchanges/') && url.endsWith('/bare-return') && method === 'POST') {
    bareReturnCalls.push(url.split('/exchanges/')[1].split('/bare-return')[0])
    return fakeResponse({})
  }
  if (url.includes('/api/v1/exchanges/') && url.endsWith('/dismiss') && method === 'POST') {
    dismissCalls.push(url.split('/exchanges/')[1].split('/dismiss')[0])
    return fakeResponse({})
  }
  if (url.includes('/api/v1/exchanges/') && url.endsWith('/outbound-variant') && method === 'POST') {
    const id = url.split('/exchanges/')[1].split('/outbound-variant')[0]
    const body = JSON.parse(opts.body as string)
    overrideVariantCalls.push({ id, body })
    return fakeResponse({ exchangeId: id, orderId: 'order-auto-1', variantId: body.variantId })
  }
  if (url.includes('/api/v1/catalog') && method === 'GET') {
    return fakeResponse(CATALOG)
  }
  if (url.includes('/api/v1/exchanges/') && method === 'GET') {
    const id = url.split('/exchanges/')[1]
    const all = exchangesOverride ?? [MATCHED_EXCHANGE, NEEDS_CONFIRMATION_EXCHANGE, UNMATCHED_EXCHANGE]
    const found = all.find(e => e.id === id) ?? MATCHED_EXCHANGE
    return fakeResponse(found)
  }
  if (url.includes('/api/v1/exchanges') && method === 'GET') {
    return fakeResponse(exchangesOverride ?? [MATCHED_EXCHANGE, NEEDS_CONFIRMATION_EXCHANGE, UNMATCHED_EXCHANGE])
  }
  if (url.includes('/api/v1/refunds') && method === 'GET') {
    return fakeResponse(refundsOverride ?? [REFUND])
  }
  return fakeResponse({})
}


/** The Returns & exchanges rows for the fixtures above (B = exchange, C = courier return). */
function caseOf(e: ExchangeSummary): ReturnCase {
  return {
    caseType: 'B', id: e.id, kind: 'exchange', reference: e.tracking_number, source: 'bosta',
    customerName: e.customer_name, orderNumber: null, stage: 'to_do', nextStep: 'link_order', tone: 'action',
    overdueDays: null, status: e.status,
    reason: { bookingStatus: null, bookingError: null, itemsCount: 1, arrivedCount: 0, awaitingCount: 0, candidateCount: 0,
      candidateReferences: null, trackingNumber: e.tracking_number, refundTotal: null, currency: null, closeReason: null,
      inspectionState: null },
    alerts: [], itemsSummary: 'Red hat → Yellow hat', itemsSummaryAr: 'Red hat ← Yellow hat', notScanned: false,
    updatedAt: new Date().toISOString(), legStatus: null,
    target: { requestId: null, exchangeId: e.id, shipmentId: null },
  }
}

function caseOfLeg(r: RefundLeg): ReturnCase {
  return {
    ...caseOf(MATCHED_EXCHANGE), caseType: 'C', id: r.id, kind: 'refund', reference: r.tracking_number,
    customerName: r.customer_name, orderNumber: r.order_number, stage: 'in_progress', nextStep: 'on_the_way', tone: 'moving',
    status: r.internal_state, legStatus: r.leg_status,
    reason: { ...caseOf(MATCHED_EXCHANGE).reason, inspectionState: r.inspection_state },
    target: { requestId: null, exchangeId: null, shipmentId: r.id },
  }
}

let mockFetch: ReturnType<typeof vi.fn>

function renderScreen() {
  return renderWithProviders(<Layout><ExchangesRefunds /></Layout>)
}

beforeEach(() => {
  vi.clearAllMocks()
  attachCalls = []
  bareReturnCalls = []
  dismissCalls = []
  overrideVariantCalls = []
  exchangesOverride = null
  refundsOverride = null
  listFetches = 0
  mockFetch = vi.fn(backendFetch)
  stubFetchWithShellDefaults(mockFetch)
})

describe('Exchange / courier-return drawer (opened from the Returns & exchanges list)', () => {
  test('needs_confirmation exchange → open drawer → candidates load → Confirm calls POST /attach with the chosen order → the list refreshes', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Omar S.')

    await user.click(screen.getByText('Omar S.'))
    await screen.findByTestId('candidate-picker')
    await screen.findByTestId('candidate-piece-a')
    const before = listFetches

    await user.click(within(screen.getByTestId('candidate-piece-a')).getByRole('button', { name: /confirm match/i }))

    await waitFor(() => {
      expect(attachCalls).toEqual([{ id: 'exc-needs-confirmation', body: { orderId: 'order-candidate-a' } }])
    })
    // Step 2: the drawer tells the page to reload — the Returns & exchanges list is fetched again.
    await waitFor(() => expect(listFetches).toBeGreaterThan(before))
  })

  test('unmatched exchange → bare-return calls the bare-return route', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Lina F.')

    await user.click(screen.getByText('Lina F.'))
    await screen.findByTestId('unmatched-actions')
    await user.click(screen.getByTestId('bare-return-button'))

    await waitFor(() => expect(bareReturnCalls).toEqual(['exc-unmatched']))
  })

  test('unmatched exchange → dismiss calls the dismiss route', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Lina F.')

    await user.click(screen.getByText('Lina F.'))
    await screen.findByTestId('unmatched-actions')
    await user.click(screen.getByTestId('dismiss-button'))

    await waitFor(() => expect(dismissCalls).toEqual(['exc-unmatched']))
  })

  test('refund drawer shows the original order + lifecycle, and links to Scan returns instead of reimplementing disposition', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Nour A.')

    await user.click(screen.getByText('Nour A.'))
    const body = await screen.findByTestId('refund-drawer-body')
    expect(within(body).getByText('#RFD-1001')).toBeInTheDocument()
    // Both the lifecycle (legStatus) badge and the inspection facet read "In transit" for this
    // fixture — two separate, additive sections, not a duplicate render of the same thing.
    expect(within(body).getAllByText(/in transit/i)).toHaveLength(2)
    expect(within(body).getByRole('link', { name: /open in scan returns/i })).toHaveAttribute('href', '/returns')
  })

  test('auto-matched exchange: the drawer shows the review banner and Change variant overrides the auto-pick', async () => {
    const user = userEvent.setup()
    exchangesOverride = [MATCHED_EXCHANGE, AUTO_MATCHED_EXCHANGE]
    renderScreen()
    await screen.findByText('Yara A.')

    await user.click(screen.getByText('Yara A.'))
    const banner = await screen.findByTestId('exchange-auto-matched-banner')
    expect(within(banner).getByText(/auto-matched/i)).toBeInTheDocument()

    await user.click(screen.getByTestId('change-variant-button'))
    await screen.findByTestId('exchange-variant-picker')
    await user.click(screen.getByTestId('exchange-picker-product-product-bandanas'))
    await screen.findByTestId('exchange-picker-variant-list')
    await user.click(screen.getByTestId('exchange-picker-variant-variant-ml'))

    await waitFor(() => {
      expect(overrideVariantCalls).toEqual([{ id: 'exc-auto-matched', body: { variantId: 'variant-ml' } }])
    })
  })
})
