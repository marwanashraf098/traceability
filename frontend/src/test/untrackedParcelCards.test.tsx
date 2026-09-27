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

// Step 6b — untracked request items on a parcel card: shapes match 6a's ReturnSessionService
// parcel view (itemsRequestId / itemsRequestReference / requestItems[{id, tracked, pieceId,
// shortCode, productTitle, variantTitle, itemStatus, arrivedCondition}]) and the 6a session
// endpoints POST /returns/sessions/{sid}/request-items/{itemId}/arrived {condition} and …/arrived/undo.

function requestItem(overrides: Record<string, unknown> = {}) {
  return {
    id: 'ri-1', tracked: false, pieceId: null, shortCode: null, orderItemId: 'oi-1', unitNo: 1,
    productTitle: 'Linen Shirt', variantTitle: 'White · M', itemStatus: 'awaiting', arrivedCondition: null,
    ...overrides,
  }
}

const requestParcel = (o: Record<string, unknown> = {}) => parcel({
  shipmentId: 'bbbbbbbb-0000-0000-0000-000000000003', awb: '7300000001', orderNumber: '#6501', tracked: false,
  requestReference: 'RR-6B0001', itemsRequestId: 'rr-1', itemsRequestReference: 'RR-6B0001',
  requestItems: [requestItem()], counts: { expected: 0, scanned: 0 },
  ...o,
})

describe('Return session — untracked request items (Step 6b)', () => {
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

  test('u1 request-linked parcel: untracked row with both buttons, the request reference, no whole-parcel mark-received', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '7300000001', detail({ parcels: [requestParcel()] }))
    const card = await screen.findByTestId('parcel-card-7300000001')
    expect(within(card).getByTestId('parcel-request')).toHaveTextContent('RR-6B0001')
    const row = within(card).getByTestId('untracked-item-ri-1')
    expect(row).toHaveTextContent('Linen Shirt')
    expect(row).toHaveTextContent('White · M · Not tracked')
    expect(within(row).getByTestId('untracked-arrived-sellable')).toHaveTextContent('Arrived · sellable')
    expect(within(row).getByTestId('untracked-arrived-damaged')).toHaveTextContent('Arrived · damaged')
    expect(within(card).queryByTestId('parcel-mark-received')).toBeNull()
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('0 of 1')
  })

  test('u2 Arrived · sellable posts the session endpoint, shows the outcome, then Undo posts the undo', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '7300000001', detail({ parcels: [requestParcel()] }))
    const arrived = detail({ parcels: [requestParcel({ complete: true, intakeOutcome: 'request_items_arrived',
      requestItems: [requestItem({ itemStatus: 'done', arrivedCondition: 'sellable' })] })] })
    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(arrived))
    await user.click(within(await screen.findByTestId('untracked-item-ri-1')).getByTestId('untracked-arrived-sellable'))

    await waitFor(() => expect(postCalls(`/returns/sessions/${SESSION}/request-items/ri-1/arrived`)).toHaveLength(1))
    expect(JSON.parse(postCalls(`/request-items/ri-1/arrived`)[0][1].body as string)).toEqual({ condition: 'sellable' })
    // Nothing left to receive: the card completes and collapses like a fully scanned one.
    await user.click(await screen.findByTestId('parcel-collapsed-7300000001'))
    const row = await screen.findByTestId('untracked-item-ri-1')
    await waitFor(() => expect(within(row).getByTestId('untracked-outcome')).toHaveTextContent('Arrived · to receive'))

    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [requestParcel()] })))
    await user.click(within(row).getByTestId('untracked-undo'))
    await waitFor(() => expect(postCalls(`/request-items/ri-1/arrived/undo`)).toHaveLength(1))
    await waitFor(() => expect(screen.getByTestId('untracked-arrived-sellable')).toBeInTheDocument())
  })

  test('u3 a mixed card (a tracked piece decided + an untracked item arrived damaged) is complete', async () => {
    const user = await openSessionWith(detail())
    const mixed = requestParcel({
      tracked: true, complete: true, intakeOutcome: 'request_items_arrived',
      scannedItems: [item({ disposition: 'restocked' })], counts: { expected: 1, scanned: 1 },
      requestItems: [requestItem({ id: 'ri-t', tracked: true, pieceId: 'piece-1', shortCode: 'P000112', itemStatus: 'done' }),
        requestItem({ itemStatus: 'done', arrivedCondition: 'damaged' })],
    })
    await scanAwb(user, '7300000001', detail({ items: [item({ disposition: 'restocked' })], parcels: [mixed] }))
    await user.click(await screen.findByTestId('parcel-collapsed-7300000001'))
    const card = await screen.findByTestId('parcel-card-7300000001')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('All 2 items in')
    expect(within(card).getByTestId('untracked-outcome')).toHaveTextContent('Arrived · damaged')
    expect(within(card).queryByTestId('untracked-item-ri-t')).toBeNull()   // tracked items render as today
  })

  test('u4 Arabic: the untracked row', async () => {
    await i18n.changeLanguage('ar')
    const user = await openSessionWith(detail(), true)
    await scanAwb(user, '7300000001', detail({ parcels: [requestParcel()] }))
    const row = await screen.findByTestId('untracked-item-ri-1')
    expect(row).toHaveTextContent('غير متتبَّع')
    expect(within(row).getByTestId('untracked-arrived-sellable')).toHaveTextContent('وصل · صالح للبيع')
    expect(within(row).getByTestId('untracked-arrived-damaged')).toHaveTextContent('وصل · تالف')
  })
})
