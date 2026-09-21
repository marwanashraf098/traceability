import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { ExchangeSummary, RefundLeg } from '../api'

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

const REFUND: RefundLeg = {
  id: 'ship-refund-1',
  tracking_number: 'RFD-TN-001',
  internal_state: 'with_courier',
  order_id: 'order-refund-1',
  order_number: '#RFD-1001',
  customer_name: 'Nour Adel',
  customer_phone: '01098765432',
  leg_status: { primaryKey: 'status.in_transit', tone: 'INFO' },
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

let attachCalls: Array<{ id: string; body: unknown }> = []
let bareReturnCalls: string[] = []
let dismissCalls: string[] = []
let exchangesOverride: ExchangeSummary[] | null = null

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()

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
    return fakeResponse([REFUND])
  }
  return fakeResponse({})
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
  exchangesOverride = null
  mockFetch = vi.fn(backendFetch)
  stubFetchWithShellDefaults(mockFetch)
})

describe('Exchanges & Refunds — list', () => {
  test('renders both feeds merged, one row shape', async () => {
    renderScreen()
    await screen.findByText('Maya Mostafa')
    expect(screen.getByText('Omar Said')).toBeInTheDocument()
    expect(screen.getByText('Lina Fathy')).toBeInTheDocument()
    expect(screen.getByText('Nour Adel')).toBeInTheDocument()
    expect(screen.getByText('910000001')).toBeInTheDocument()
    expect(screen.getByText('RFD-TN-001')).toBeInTheDocument()
  })

  test('a refund row shows the real courier badge; an exchange row shows a status pill, never a fake courier stepper', async () => {
    renderScreen()
    await screen.findByText('Nour Adel')

    const refundRow = screen.getByText('Nour Adel').closest('tr')!
    // LegStatusBadge renders the courier-derived label for the refund.
    expect(within(refundRow).getByText(/in transit/i)).toBeInTheDocument()

    const exchangeRow = screen.getByText('Maya Mostafa').closest('tr')!
    // Exchange status vocabulary pill — "Matched" — never a courier word like
    // "in transit"/"delivered"/"out for delivery" (HONESTY CONSTRAINT 2: no
    // fabricated inbound-leg courier progress for exchanges).
    expect(within(exchangeRow).getByText(/matched/i)).toBeInTheDocument()
    expect(within(exchangeRow).queryByText(/in transit/i)).not.toBeInTheDocument()
    expect(within(exchangeRow).queryByText(/delivered/i)).not.toBeInTheDocument()
  })

  test('filter tabs filter correctly — refunds tab hides exchange rows, exchanges tab hides refund rows', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Nour Adel')

    await user.click(screen.getByRole('button', { name: /^Refunds/i }))
    expect(screen.getByText('Nour Adel')).toBeInTheDocument()
    expect(screen.queryByText('Maya Mostafa')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Exchanges/i }))
    expect(screen.getByText('Maya Mostafa')).toBeInTheDocument()
    expect(screen.queryByText('Nour Adel')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^In transit/i }))
    expect(screen.getByText('Nour Adel')).toBeInTheDocument()
    expect(screen.queryByText('Maya Mostafa')).not.toBeInTheDocument()
    expect(screen.queryByText('Omar Said')).not.toBeInTheDocument()
  })

  test('labelling: an exchange row renders matched_order_id as the mapped order, never outbound_order_id', async () => {
    renderScreen()
    await screen.findByText('Maya Mostafa')
    const exchangeRow = screen.getByText('Maya Mostafa').closest('tr')!
    // matched_order_id = 'order-original-1' → short id "ORDERORI" (first 8 chars,
    // dashes stripped, uppercased) — see ExchangesRefunds.tsx's shortId().
    expect(within(exchangeRow).getByText('ORDERORI')).toBeInTheDocument()
  })
})

describe('Exchanges & Refunds — drawer', () => {
  test('needs_confirmation exchange → open drawer → candidates load → Confirm calls POST /attach with the chosen order → row reflects matched', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Omar Said')

    await user.click(screen.getByText('Omar Said'))
    await screen.findByTestId('candidate-picker')
    await screen.findByTestId('candidate-piece-a')

    // After confirming, the drawer's own refetch flips this exchange to 'matched' —
    // simulate that server-side state change so the list reload reflects it.
    exchangesOverride = [
      MATCHED_EXCHANGE,
      { ...NEEDS_CONFIRMATION_EXCHANGE, status: 'matched', matched_order_id: 'order-candidate-a', match_method: 'manual' },
      UNMATCHED_EXCHANGE,
    ]

    await user.click(within(screen.getByTestId('candidate-piece-a')).getByRole('button', { name: /confirm match/i }))

    await waitFor(() => {
      expect(attachCalls).toEqual([{ id: 'exc-needs-confirmation', body: { orderId: 'order-candidate-a' } }])
    })

    // Drawer refetches its own detail + tells the list to reload — the row for
    // this exchange should now read "Matched" instead of "Needs confirmation".
    await waitFor(() => {
      const row = screen.getByText('Omar Said').closest('tr')!
      expect(within(row).getByText(/matched/i)).toBeInTheDocument()
    })
  })

  test('unmatched exchange → bare-return calls the bare-return route', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Lina Fathy')

    await user.click(screen.getByText('Lina Fathy'))
    await screen.findByTestId('unmatched-actions')
    await user.click(screen.getByTestId('bare-return-button'))

    await waitFor(() => expect(bareReturnCalls).toEqual(['exc-unmatched']))
  })

  test('unmatched exchange → dismiss calls the dismiss route', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Lina Fathy')

    await user.click(screen.getByText('Lina Fathy'))
    await screen.findByTestId('unmatched-actions')
    await user.click(screen.getByTestId('dismiss-button'))

    await waitFor(() => expect(dismissCalls).toEqual(['exc-unmatched']))
  })

  test('refund drawer shows the original order + lifecycle, and links to Returns instead of reimplementing disposition', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('Nour Adel')

    await user.click(screen.getByText('Nour Adel'))
    const body = await screen.findByTestId('refund-drawer-body')
    expect(within(body).getByText('#RFD-1001')).toBeInTheDocument()
    expect(within(body).getByText(/in transit/i)).toBeInTheDocument()
    expect(within(body).getByRole('link', { name: /open in returns/i })).toHaveAttribute('href', '/returns')
  })
})
