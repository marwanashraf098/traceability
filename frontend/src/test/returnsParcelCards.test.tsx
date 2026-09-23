import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import Returns from '../pages/Returns'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import * as api from '../api'
import i18n from '../i18n'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

// ── Fixtures — shapes match GET /returns/sessions/{id} (ReturnSessionService.getSession) ──

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true, status,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}

function noContent() {
  return Promise.resolve({
    ok: true, status: 204,
    headers: { get: (k: string) => (k === 'content-length' ? '0' : null) },
    json: async () => null,
  })
}

const SESSION = 'aaaaaaaa-0000-0000-0000-00000000000a'

function item(overrides: Record<string, unknown> = {}) {
  return {
    id: 'item-1', piece_id: 'piece-1', barcode: 'PC-piece-1', short_code: 'P000112',
    status: 'return_pending_inspection', variant_title: 'Black · XL', product_title: 'Flipped Pants',
    sku: '1001-Black-XL', disposition: 'pending', unexpected: false, scan_source: 'barcode',
    damage_reason: null, scanned_at: '2026-09-23T16:00:00Z', disposition_at: null,
    ...overrides,
  }
}

function expected(id: string, awb: string) {
  return { id, barcode: `PC-${id}`, status: 'delivered', variant_title: 'White · M', product_title: 'Linen Shirt', sku: '2004-White-M', awb }
}

function parcel(overrides: Record<string, unknown> = {}) {
  return {
    shipmentId: 'bbbbbbbb-0000-0000-0000-000000000001', awb: '8987563471', leg: 'return',
    orderNumber: '#1047', customerShortName: 'Mariam S.', returnedAt: '2026-09-20T10:00:00Z',
    bosta: { itemsCount: 2, description: 'Flipped Pants in Black - XL x 1 (1001-Black-XL)', descriptionAr: null },
    tracked: true, intakeOutcome: null, markedBy: null, markedAt: null, markedInThisSession: false,
    expectedPieces: [], scannedItems: [], counts: { expected: 0, scanned: 0 }, complete: false,
    ...overrides,
  }
}

function detail(overrides: Record<string, unknown> = {}) {
  return {
    id: SESSION, status: 'open', opened_by: 'user-1', opened_at: new Date().toISOString(),
    closed_by: null, closed_at: null, note: null,
    items: [], expectedPieces: [], courierReturns: [], parcels: [], otherItems: [], lastScan: null,
    ...overrides,
  }
}

const trackedParcel = () => parcel({
  expectedPieces: [expected('piece-2', '8987563471')],
  scannedItems: [item()],
  counts: { expected: 2, scanned: 1 },
})

const untrackedParcel = (o: Record<string, unknown> = {}) => parcel({
  shipmentId: 'bbbbbbbb-0000-0000-0000-000000000002', awb: '6136538746', orderNumber: '#0988',
  customerShortName: 'Omar K.', tracked: false,
  bosta: { itemsCount: 1, description: 'Flipped Pants in Black - XL x 1 (1001-Black-XL)', descriptionAr: null },
  ...o,
})

// ── Harness ───────────────────────────────────────────────────────────────────

let mockFetch: ReturnType<typeof vi.fn>

async function openSessionWith(first: ReturnType<typeof detail>, arabic = false) {
  const user = userEvent.setup()
  mockFetch
    .mockReturnValueOnce(jsonOk({ sessionId: SESSION }, 201))
    .mockReturnValueOnce(jsonOk(first))
  if (arabic) {
    render(
      <StationProvider><MemoryRouter><I18nextProvider i18n={i18n}>
        <ToastProvider><Returns /></ToastProvider>
      </I18nextProvider></MemoryRouter></StationProvider>,
    )
  } else {
    renderWithProviders(<Returns />)
  }
  await user.click(await screen.findByTestId('open-session-button'))
  await waitFor(() => screen.getByTestId('open-session-screen'))
  return user
}

async function scanAwb(user: ReturnType<typeof userEvent.setup>, awb: string, after: ReturnType<typeof detail>) {
  mockFetch
    .mockReturnValueOnce(jsonOk({ scanType: 'awb', awb, expectedPieces: [] }))
    .mockReturnValueOnce(jsonOk(after))
  await user.type(screen.getByTestId('scan-input'), `${awb}{Enter}`)
}

function postCalls(suffix: string) {
  return mockFetch.mock.calls.filter(([u, o]) => String(u).endsWith(suffix) && (o as RequestInit)?.method === 'POST')
}

describe('Return session — parcel cards (Step 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    stubFetchWithShellDefaults((url: string, opts?: RequestInit) =>
      String(url).includes('/returns/awaiting-scan') ? jsonOk({ count: 0, items: [] }) : mockFetch(url, opts))
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(async () => {
    vi.unstubAllGlobals()
    await i18n.changeLanguage('en')
  })

  test('pc1 tracked courier return: AWB scan removes the empty state and shows the parcel card with items', async () => {
    const user = await openSessionWith(detail())
    expect(screen.getByText('Ready to scan')).toBeInTheDocument()

    await scanAwb(user, '8987563471', detail({
      items: [item()], parcels: [trackedParcel()],
      lastScan: { kind: 'awb', code: '8987563471', label: null, at: '2026-09-23T16:01:00Z' },
    }))

    const card = await screen.findByTestId('parcel-card-8987563471')
    expect(screen.queryByText('Ready to scan')).not.toBeInTheDocument()
    expect(card).toHaveTextContent('Courier return')
    expect(card).toHaveTextContent('AWB 8987563471')
    expect(card).toHaveTextContent('#1047')
    expect(card).toHaveTextContent('Mariam S.')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('1 of 2 items scanned')
    expect(within(card).getByTestId('parcel-bosta-note')).toHaveTextContent('2 items — Flipped Pants in Black - XL x 1 (1001-Black-XL)')
    expect(within(card).getByTestId('item-piece-1')).toBeInTheDocument()          // scanned, existing controls
    expect(within(card).getByTestId('expected-piece-2')).toBeInTheDocument()      // awaiting scan, reprint
    expect(screen.getByTestId('scan-feedback')).toHaveTextContent('Parcel label recognised · AWB 8987563471')
  })

  test('pc2 untracked courier return: notice + Mark parcel received / Leave it for now', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '6136538746', detail({ parcels: [untrackedParcel()] }))

    const card = await screen.findByTestId('parcel-card-6136538746')
    expect(screen.queryByText('Ready to scan')).not.toBeInTheDocument()
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('Not tracked by Traced')
    expect(within(card).getByTestId('parcel-untracked')).toHaveTextContent("Traced didn't track this order")
    expect(within(card).getByTestId('parcel-bosta-note')).toHaveTextContent('1 item — Flipped Pants')
    expect(within(card).getByTestId('parcel-mark-received')).toHaveTextContent('Mark parcel received')

    await user.click(within(card).getByTestId('parcel-leave'))
    expect(await screen.findByTestId('parcel-collapsed-6136538746')).toHaveTextContent('not tracked by Traced')
    expect(screen.queryByTestId('parcel-card-6136538746')).not.toBeInTheDocument()
  })

  test('pc3 forward AWB with no eligible pieces: card says nothing is waiting, no mark-received', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '9730600001', detail({
      parcels: [parcel({ awb: '9730600001', leg: 'forward', bosta: null, tracked: true })],
    }))
    const card = await screen.findByTestId('parcel-card-9730600001')
    expect(card).toHaveTextContent('Return to sender')
    expect(within(card).getByTestId('parcel-nothing-to-scan'))
      .toHaveTextContent('No items from this shipment are waiting to be scanned.')
    expect(within(card).queryByTestId('parcel-mark-received')).not.toBeInTheDocument()
    expect(within(card).queryByTestId('parcel-bosta-note')).not.toBeInTheDocument()
  })

  test('pc4 Mark received → "Received · not tracked" with Undo; Undo → back to the untracked notice', async () => {
    const user = await openSessionWith(detail({ parcels: [untrackedParcel()] }))
    const card = await screen.findByTestId('parcel-card-6136538746')

    mockFetch
      .mockReturnValueOnce(noContent())
      .mockReturnValueOnce(jsonOk(detail({
        parcels: [untrackedParcel({
          intakeOutcome: 'received_untracked', markedBy: 'Operator', markedAt: '2026-09-23T16:02:00Z',
          markedInThisSession: true, complete: true,
        })],
        lastScan: { kind: 'marked_received', code: '6136538746', label: null, at: '2026-09-23T16:02:00Z' },
      })))
    await user.click(within(card).getByTestId('parcel-mark-received'))

    const received = await screen.findByTestId('parcel-received-untracked')
    expect(postCalls(`/parcels/bbbbbbbb-0000-0000-0000-000000000002/mark-received`)).toHaveLength(1)
    expect(screen.getByTestId('parcel-pill')).toHaveTextContent('Received · not tracked')
    expect(received).toHaveTextContent('Marked received by Operator')
    expect(received).toHaveTextContent("Stock wasn't changed.")
    expect(screen.queryByTestId('parcel-bosta-note')).not.toBeInTheDocument()
    expect(screen.getByTestId('scan-feedback')).toHaveTextContent('Parcel marked received · AWB 6136538746')

    mockFetch
      .mockReturnValueOnce(noContent())
      .mockReturnValueOnce(jsonOk(detail({ parcels: [untrackedParcel()] })))
    await user.click(screen.getByTestId('parcel-undo'))
    expect(await screen.findByTestId('parcel-untracked')).toBeInTheDocument()
    expect(postCalls(`/parcels/bbbbbbbb-0000-0000-0000-000000000002/undo-mark-received`)).toHaveLength(1)
  })

  test('pc5 complete parcel collapses to one line; newest incomplete stays expanded; expand on click', async () => {
    const done = parcel({
      shipmentId: 'bbbbbbbb-0000-0000-0000-000000000009', awb: '2493716277', orderNumber: '#1052',
      scannedItems: [item({ id: 'item-9', piece_id: 'piece-9', disposition: 'restocked' })],
      counts: { expected: 1, scanned: 1 }, complete: true,
    })
    const user = await openSessionWith(detail({ items: [item(), item({ id: 'item-9', piece_id: 'piece-9', disposition: 'restocked' })],
      parcels: [trackedParcel(), done] }))

    expect(await screen.findByTestId('parcel-card-8987563471')).toBeInTheDocument()
    const collapsed = screen.getByTestId('parcel-collapsed-2493716277')
    // Order numbers are wrapped in Unicode isolates (U+2068/U+2069) for RTL — strip for the text check.
    expect(collapsed.textContent!.replace(/[\u2068\u2069]/g, '')).toContain('Order #1052 · 1 item · restocked')
    expect(collapsed).toHaveTextContent('All items in')

    await user.click(collapsed)
    const opened = await screen.findByTestId('parcel-card-2493716277')
    expect(within(opened).getByTestId('parcel-pill')).toHaveTextContent('All 1 item in')
  })

  test('pc6 disposition controls inside the card call the same endpoint as before', async () => {
    const user = await openSessionWith(detail({ items: [item()], parcels: [trackedParcel()] }))
    const card = await screen.findByTestId('parcel-card-8987563471')
    mockFetch
      .mockReturnValueOnce(jsonOk(item({ disposition: 'restocked' })))
      .mockReturnValueOnce(jsonOk(detail({ items: [item({ disposition: 'restocked' })], parcels: [trackedParcel()] })))
    await user.click(within(within(card).getByTestId('item-piece-1')).getByText('Restock'))
    await waitFor(() => expect(postCalls(`/returns/sessions/${SESSION}/items/piece-1/disposition`)).toHaveLength(1))
    const body = JSON.parse((postCalls(`/items/piece-1/disposition`)[0][1] as RequestInit).body as string)
    expect(body).toEqual({ disposition: 'restock', reason: null, locationId: null })
  })

  test('pc7 footer summary + item-scanned feedback strip, singular/plural EN', async () => {
    await openSessionWith(detail({
      items: [item({ disposition: 'restocked' })],
      parcels: [parcel({ scannedItems: [item({ disposition: 'restocked' })], counts: { expected: 1, scanned: 1 }, complete: true })],
      lastScan: { kind: 'piece', code: 'P000112', label: 'Flipped Pants', at: '2026-09-23T16:00:00Z' },
    }))
    expect(await screen.findByTestId('session-footer-summary'))
      .toHaveTextContent('1 parcel · 1 item scanned · nothing left to decide')
    expect(screen.getByTestId('scan-feedback')).toHaveTextContent('Item scanned · P000112 Flipped Pants')
  })

  test('pc8 footer says what blocks closing when an item still needs a decision', async () => {
    await openSessionWith(detail({ items: [item()], parcels: [trackedParcel()] }))
    expect(await screen.findByTestId('session-footer-summary'))
      .toHaveTextContent('Choose restock or damaged for every scanned item before closing.')
  })

  test('pc9 plurals: "1 item" / "2 items" in EN, Arabic renders', async () => {
    expect(i18n.t('returns.openSession.bostaSays', { lng: 'en', count: 1, description: 'x' })).toBe('1 item — x')
    expect(i18n.t('returns.openSession.bostaSays', { lng: 'en', count: 2, description: 'x' })).toBe('2 items — x')
    expect(i18n.t('returns.landing.awaitingScanTitle', { lng: 'en', count: 1 })).toBe('1 courier return waiting to be scanned')
    expect(i18n.t('returns.landing.awaitingScanTitle', { lng: 'en', count: 3 })).toBe('3 courier returns waiting to be scanned')
    expect(i18n.t('returns.openSession.footer.parcels', { lng: 'en', count: 2 })).toBe('2 parcels')
    expect(i18n.t('returns.openSession.bostaSays', { lng: 'ar', count: 1, description: 'x' })).toBe('قطعة واحدة — x')
    expect(i18n.t('returns.openSession.bostaSays', { lng: 'ar', count: 3, description: 'x' })).toBe('3 قطع — x')

    await openSessionWith(detail({ parcels: [untrackedParcel()] }), true)
    await i18n.changeLanguage('ar')
    const card = await screen.findByTestId('parcel-card-6136538746')
    expect(card).toHaveTextContent('مرتجع من بوستا')
    expect(within(card).getByTestId('parcel-mark-received')).toHaveTextContent('تسجيل الشحنة كمستلمة')
    expect(card.querySelector('bdi')).not.toBeNull()
  })
})

describe('Exchanges & Refunds — received_untracked', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    stubFetchWithShellDefaults(mockFetch)
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => { vi.unstubAllGlobals() })

  test('er1 counted under Received (not Needs action), pill + "Waiting to be added in Receiving"', async () => {
    const refund = (id: string, state: string, awaiting = false) => ({
      id, tracking_number: id === 'r1' ? '6136538746' : '2493716277', internal_state: 'returned',
      order_id: 'o-' + id, order_number: '#0988', customer_name: 'Omar K.', customer_phone: null,
      created_at: '2026-09-20T10:00:00Z', inspection_state: state, awaiting_receiving: awaiting,
      leg_status: { primaryKey: 'returned', tone: 'success' },
    })
    mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/exchanges')) return jsonOk([])
      if (String(url).includes('/refunds')) return jsonOk([refund('r1', 'received_untracked', true), refund('r2', 'resolved')])
      return jsonOk({})
    })
    const user = userEvent.setup()
    renderWithProviders(<ExchangesRefunds />)
    const badges = await screen.findAllByTestId('refund-inspection-badge')
    expect(badges[0]).toHaveTextContent('Received · not tracked')
    expect(within(badges[0]).getByTestId('refund-awaiting-receiving')).toHaveTextContent('Waiting to be added in Receiving')

    await user.click(screen.getByRole('button', { name: /^Needs action/i }))
    expect(screen.queryAllByTestId('refund-inspection-badge')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: /^Received/i }))
    expect(screen.getAllByTestId('refund-inspection-badge')).toHaveLength(2)
  })
})
