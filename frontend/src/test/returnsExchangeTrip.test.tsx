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

// 2026-11-12 — a Bosta exchange trip (type 30) card: parcel.exchange (ReturnSessionService.addParcelView);
// shapes match GET /returns/sessions/{id}. Fixture helpers copied from untrackedParcelCards.test.tsx.

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true, status,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}

const SESSION = 'aaaaaaaa-0000-0000-0000-00000000000a'

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



const exchangeParcel = (o: Record<string, unknown> = {}) => parcel({
  shipmentId: 'bbbbbbbb-0000-0000-0000-000000000030', awb: '4955034034', leg: 'forward', orderNumber: 'BRK-45979-EG',
  tracked: false,
  bosta: { itemsCount: 1, description: 'x1 Contrast Trim Knit Sweater - Burgundy - L - SKU: BRK-CTK-2627-BRG-L', descriptionAr: null },
  exchange: { originalOrderNumber: 'BRK-45979-EG', tracedRequest: false, inboundKnown: true,
    inboundProductTitle: 'Contrast Trim Knit Sweater', inboundVariantTitle: 'L', inboundSku: 'BRK-CTK-2627-BRG-L' },
  untrackedUnits: [{ orderItemId: 'oi-l', unitNo: 1, units: 1, productTitle: 'Contrast Trim Knit Sweater',
    variantTitle: 'L', sku: 'BRK-CTK-2627-BRG-L', intakeId: null, condition: null, viaPhone: false, markedBy: null,
    markedAt: null, markedInSession: null }],
  unitsIn: 0, canMarkReceived: false,
  ...o,
})

describe('Return session — Bosta exchange trip (type 30)', () => {
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

  test('x1 known inbound: Exchange label, the original order, what comes back, Bosta note, its unit row', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '4955034034', detail({ parcels: [exchangeParcel()] }))
    const card = await screen.findByTestId('parcel-card-4955034034')
    expect(card).toHaveTextContent('Exchange')
    expect(card).not.toHaveTextContent('Return to sender')
    expect(card).toHaveTextContent('BRK-45979-EG')
    expect(within(card).getByTestId('parcel-exchange-inbound')).toHaveTextContent('Contrast Trim Knit Sweater · L')
    expect(within(card).getByTestId('parcel-bosta-note')).toHaveTextContent('Contrast Trim Knit Sweater - Burgundy - L')
    expect(within(card).queryByTestId('parcel-exchange-unknown')).toBeNull()
    expect(card).not.toHaveTextContent('Denim Jeans')
  })

  test('x2 unknown inbound: the "scan any piece of the original order" hint, never the forward "nothing to scan" line', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '4955034034', detail({ parcels: [exchangeParcel({
      tracked: true, untrackedUnits: [],
      exchange: { originalOrderNumber: '#2201', tracedRequest: false, inboundKnown: false,
        inboundProductTitle: null, inboundVariantTitle: null, inboundSku: null },
      orderNumber: '#2201', bosta: { itemsCount: 1, description: 'x1 Knit Sweater', descriptionAr: null } })] }))
    const card = await screen.findByTestId('parcel-card-4955034034')
    expect(within(card).getByTestId('parcel-exchange-unknown')).toHaveTextContent('scan any piece of order')
    expect(within(card).getByTestId('parcel-exchange-unknown')).toHaveTextContent('#2201')
    expect(within(card).queryByTestId('parcel-nothing-to-scan')).toBeNull()
    expect(within(card).queryByTestId('parcel-exchange-inbound')).toBeNull()
  })

  test('x3 Arabic: the exchange label and hint are translated', async () => {
    await i18n.changeLanguage('ar')
    const user = await openSessionWith(detail(), true)
    await scanAwb(user, '4955034034', detail({ parcels: [exchangeParcel({
      exchange: { originalOrderNumber: null, tracedRequest: false, inboundKnown: false,
        inboundProductTitle: null, inboundVariantTitle: null, inboundSku: null }, untrackedUnits: [] })] }))
    const card = await screen.findByTestId('parcel-card-4955034034')
    expect(card).toHaveTextContent('استبدال')
    expect(within(card).getByTestId('parcel-exchange-unknown')).toHaveTextContent('غير مربوط بطلب')
  })
})
