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

// Issue 1 (V154, design/returns-parcel-states 3 / 3c / 6 / 8) — untracked parcel items, one row
// per unit. Shapes match ReturnSessionService.addParcelView (untrackedUnits / unitsIn /
// canMarkReceived / returnedToSender / bosta.state) and the endpoints
// POST /returns/sessions/{sid}/parcels/{shipmentId}/units/arrived {orderItemId, unitNo, condition}
// and …/units/arrived/undo {orderItemId, unitNo}.

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
const SHIPMENT = 'bbbbbbbb-0000-0000-0000-000000000009'

function unit(orderItemId: string, unitNo: number, units: number, o: Record<string, unknown> = {}) {
  return {
    orderItemId, unitNo, units, productTitle: orderItemId === 'oi-pants' ? 'Flipped Pants' : 'Linen Shirt',
    variantTitle: orderItemId === 'oi-pants' ? 'Black · XL' : 'White · M', sku: null,
    intakeId: null, condition: null, viaPhone: false, markedInSession: null,
    ...o,
  }
}

const marked = (condition: 'sellable' | 'damaged', o: Record<string, unknown> = {}) =>
  ({ intakeId: `in-${condition}`, condition, markedInSession: SESSION, ...o })

function parcel(o: Record<string, unknown> = {}) {
  return {
    shipmentId: SHIPMENT, awb: '6136538746', leg: 'return', orderNumber: '#0988', customerShortName: 'Omar K.',
    returnedAt: '2026-09-20T10:00:00Z',
    bosta: { itemsCount: 3, description: 'Flipped Pants in Black - XL x 2 (1001-Black-XL), Linen Shirt x 1', descriptionAr: null },
    tracked: false, intakeOutcome: null, markedBy: null, markedAt: null, markedInThisSession: false,
    expectedPieces: [], scannedItems: [], counts: { expected: 0, scanned: 0 }, complete: false,
    requestItems: [], returnedToSender: false, canMarkReceived: true, unitsIn: 0,
    untrackedUnits: [unit('oi-pants', 1, 2), unit('oi-pants', 2, 2), unit('oi-shirt', 1, 1)],
    ...o,
  }
}

function detail(o: Record<string, unknown> = {}) {
  return {
    id: SESSION, status: 'open', opened_by: 'user-1', opened_at: new Date().toISOString(),
    closed_by: null, closed_at: null, note: null,
    items: [], expectedPieces: [], courierReturns: [], parcels: [], otherItems: [], lastScan: null,
    ...o,
  }
}

let mockFetch: ReturnType<typeof vi.fn>

async function openSessionWith(first: ReturnType<typeof detail>, arabic = false) {
  const user = userEvent.setup()
  mockFetch
    .mockReturnValueOnce(jsonOk({ sessionId: SESSION }, 201))
    .mockReturnValueOnce(jsonOk(first))
  if (arabic) {
    await i18n.changeLanguage('ar')
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

function postBodies(suffix: string) {
  return mockFetch.mock.calls
    .filter(([u, o]) => String(u).endsWith(suffix) && (o as RequestInit)?.method === 'POST')
    .map(([, o]) => JSON.parse(String((o as RequestInit).body)))
}

describe('Return session — untracked parcel items, one row per unit (Issue 1)', () => {
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

  test('n1 untracked parcel, no request: one row per unit with both buttons, "0 of 3 in", the guidance and the whole-parcel fallback', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '6136538746', detail({ parcels: [parcel()] }))
    const card = await screen.findByTestId('parcel-card-6136538746')
    const rows = within(card).getAllByTestId(/^untracked-unit-oi-/)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Flipped Pants')
    expect(rows[0]).toHaveTextContent('Black · XL · Unit 1 of 2 · Not tracked')
    expect(rows[1]).toHaveTextContent('Unit 2 of 2')
    for (const r of rows) {
      expect(within(r).getByTestId('untracked-unit-sellable')).toHaveTextContent('Arrived · sellable')
      expect(within(r).getByTestId('untracked-unit-damaged')).toHaveTextContent('Arrived · damaged')
    }
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('0 of 3 in')
    expect(within(card).getByTestId('parcel-units-info')).toHaveTextContent("Items you don't mark are treated as not returned")
    expect(within(card).getByTestId('parcel-units-fallback')).toHaveTextContent("Contents don't match the order?")
    // the old untracked card (Mark parcel received as the only action) is gone for these parcels
    expect(within(card).queryByTestId('parcel-untracked')).toBeNull()
  })

  test('n2 Arrived · sellable posts the unit endpoint; the outcome + Undo replace the buttons; Undo posts the undo', async () => {
    const user = await openSessionWith(detail())
    await scanAwb(user, '6136538746', detail({ parcels: [parcel()] }))
    const after = detail({ parcels: [parcel({ unitsIn: 1, complete: true, intakeOutcome: 'untracked_units_arrived',
      canMarkReceived: false,
      untrackedUnits: [unit('oi-pants', 1, 2, marked('sellable')), unit('oi-pants', 2, 2), unit('oi-shirt', 1, 1)] })] })
    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(after))
    await user.click(within(await screen.findByTestId('untracked-unit-oi-pants-1')).getByTestId('untracked-unit-sellable'))

    await waitFor(() => expect(postBodies(`/parcels/${SHIPMENT}/units/arrived`)).toHaveLength(1))
    expect(postBodies(`/parcels/${SHIPMENT}/units/arrived`)[0]).toEqual({ orderItemId: 'oi-pants', unitNo: 1, condition: 'sellable' })
    const row = await screen.findByTestId('untracked-unit-oi-pants-1')
    await waitFor(() => expect(within(row).getByTestId('untracked-unit-outcome')).toHaveTextContent('Arrived · to receive'))
    expect(within(row).queryByTestId('untracked-unit-sellable')).toBeNull()
    expect(screen.getByTestId('parcel-pill')).toHaveTextContent('1 of 3 in')
    expect(screen.queryByTestId('parcel-units-fallback')).toBeNull()

    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [parcel()] })))
    await user.click(within(row).getByTestId('untracked-unit-undo'))
    await waitFor(() => expect(postBodies(`/parcels/${SHIPMENT}/units/arrived/undo`)).toHaveLength(1))
    expect(postBodies(`/parcels/${SHIPMENT}/units/arrived/undo`)[0]).toEqual({ orderItemId: 'oi-pants', unitNo: 1 })
    await waitFor(() => expect(within(screen.getByTestId('untracked-unit-oi-pants-1')).getByTestId('untracked-unit-sellable')).toBeInTheDocument())
  })

  test('n3 partial return is handled ("2 of 3 in", neutral, no guidance); all units in → "All 3 in"; via phone tag on a phone-scanned parcel', async () => {
    const partial = parcel({ unitsIn: 2, complete: true, intakeOutcome: 'untracked_units_arrived', canMarkReceived: false,
      untrackedUnits: [unit('oi-pants', 1, 2, marked('sellable', { viaPhone: true })), unit('oi-pants', 2, 2, marked('damaged', { intakeId: 'in-d' })),
        unit('oi-shirt', 1, 1)] })
    const user = await openSessionWith(detail())
    await scanAwb(user, '6136538746', detail({ parcels: [partial] }))
    const card = await screen.findByTestId('parcel-card-6136538746')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('2 of 3 in')
    expect(within(within(card).getByTestId('untracked-unit-oi-pants-1')).getByText('via phone')).toBeInTheDocument()
    expect(within(within(card).getByTestId('untracked-unit-oi-pants-2')).getByTestId('untracked-unit-outcome')).toHaveTextContent('Arrived · damaged')
    // the unmarked unit can still be marked while the session is open
    expect(within(within(card).getByTestId('untracked-unit-oi-shirt-1')).getByTestId('untracked-unit-sellable')).toBeInTheDocument()
    // unmarked units never block closing (the worker's close button is role-gated, not blocked)
    expect(screen.queryByTestId('close-blocked-callout')).toBeNull()

    const allIn = parcel({ unitsIn: 3, complete: true, intakeOutcome: 'untracked_units_arrived', canMarkReceived: false,
      untrackedUnits: [unit('oi-pants', 1, 2, marked('sellable')), unit('oi-pants', 2, 2, marked('damaged', { intakeId: 'in-d' })),
        unit('oi-shirt', 1, 1, marked('sellable', { intakeId: 'in-s2' }))] })
    await scanAwb(user, '6136538746', detail({ parcels: [allIn] }))
    await waitFor(() => expect(screen.getByTestId('parcel-pill')).toHaveTextContent('All 3 in'))
    expect(screen.queryByTestId('parcel-units-info')).toBeNull()
  })

  test('n4 returned-to-sender forward leg: "Return to sender", Bosta status + note, unit rows, whole-parcel fallback posts mark-received', async () => {
    const rts = parcel({ shipmentId: SHIPMENT, awb: '445040939', leg: 'forward', orderNumber: '#2212099474', returnedToSender: true,
      bosta: { itemsCount: 1, description: 'The Bikinis - XS/S / Pink & Red x 1 (SBPINK-2)', descriptionAr: null,
        state: 'Returned to business', rtoSince: '2026-10-05T10:00:00Z' },
      untrackedUnits: [unit('oi-shirt', 1, 1, { productTitle: 'The Bikinis', variantTitle: 'XS/S · Pink & Red' })] })
    const user = await openSessionWith(detail())
    await scanAwb(user, '445040939', detail({ parcels: [rts] }))
    const card = await screen.findByTestId('parcel-card-445040939')
    expect(card).toHaveTextContent('Return to sender')
    expect(within(card).getByTestId('parcel-bosta-status')).toHaveTextContent('Returned to business')
    expect(within(card).getByTestId('parcel-bosta-status')).toHaveTextContent('returned to origin since')
    expect(within(card).getByTestId('parcel-bosta-note')).toHaveTextContent('SBPINK-2')
    expect(within(card).getByTestId('parcel-units-info')).toHaveTextContent('Returned to sender — never delivered')
    expect(within(card).getAllByTestId(/^untracked-unit-oi-/)).toHaveLength(1)

    mockFetch.mockReturnValueOnce(noContent()).mockReturnValueOnce(jsonOk(detail({ parcels: [rts] })))
    await user.click(within(card).getByTestId('parcel-mark-received'))
    await waitFor(() => expect(mockFetch.mock.calls.some(([u, o]) =>
      String(u).endsWith(`/parcels/${SHIPMENT}/mark-received`) && (o as RequestInit)?.method === 'POST')).toBe(true))
  })

  test('n5 Arabic (RTL): unit rows, pill and guidance in Arabic', async () => {
    const partial = parcel({ unitsIn: 1, complete: true, intakeOutcome: 'untracked_units_arrived', canMarkReceived: false,
      untrackedUnits: [unit('oi-pants', 1, 2, marked('sellable')), unit('oi-pants', 2, 2), unit('oi-shirt', 1, 1)] })
    const user = await openSessionWith(detail(), true)
    await scanAwb(user, '6136538746', detail({ parcels: [partial] }))
    const card = await screen.findByTestId('parcel-card-6136538746')
    expect(within(card).getByTestId('parcel-pill')).toHaveTextContent('وصلت 1 من 3')
    expect(within(card).getByTestId('untracked-unit-oi-pants-2')).toHaveTextContent('القطعة 2 من 2')
    expect(within(within(card).getByTestId('untracked-unit-oi-pants-2')).getByTestId('untracked-unit-sellable'))
      .toHaveTextContent('وصل · صالح للبيع')
    expect(within(card).getByTestId('parcel-units-info')).toHaveTextContent('القطع التي لا تسجّلها تُعتبر لم تَعُد')
  })
})
