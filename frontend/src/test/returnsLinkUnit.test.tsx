import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { within } from '@testing-library/react'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken, } from '../auth'
import * as apiModule from '../api'
import Returns from '../pages/Returns'
import en from '../locales/en.json'
import ar from '../locales/ar.json'

// V246 — Scan returns: "this label is that unit" (BRK-47010-EG). An unexpected label of the same variant as an
// untracked line on a scanned parcel offers the link FIRST; "Not the real piece" asks first while a link is possible;
// a linked label merges into its unit's row (with Undo and the case-B note); only a legal pending row blocks Close.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : k.toLowerCase() === 'content-length' ? '10' : null) },
  json: async () => structuredClone(data),
})

type Obj = Record<string, unknown>

const UNIT = { orderItemId: 'line-1', unitNo: 1, units: 1, productTitle: 'Eagle Tee', variantTitle: 'L / Black', sku: 'ET-L',
  intakeId: null as string | null, condition: null as string | null, viaPhone: false, markedBy: null, markedAt: null,
  markedInSession: null as string | null }

const candidate = (over: Obj = {}): Obj => ({ shipmentId: 'sh-1', awb: '5605067774', orderItemId: 'line-1', unitNo: 1, units: 1,
  orderNumber: 'BRK-47010-EG', productTitle: 'Eagle Tee', variantTitle: 'L / Black', sku: 'ET-L', intakeId: null,
  condition: null, ...over })

const unexpectedItem = (over: Obj = {}): Obj => ({ id: 'it-1', piece_id: 'piece-1', barcode: 'P006819', status: 'available',
  variant_title: 'L / Black', product_title: 'Eagle Tee', sku: 'ET-L', disposition: 'pending', unexpected: true,
  damage_reason: null, via_phone: false, order_number: null, scanned_at: '2026-11-10T11:02:00Z',
  linkCandidates: [candidate()], ...over })

const parcel = (units: Obj[]): Obj => ({ shipmentId: 'sh-1', awb: '5605067774', leg: 'forward', orderNumber: 'BRK-47010-EG',
  requestReference: null, customerShortName: 'A.', returnedAt: null, bosta: null, tracked: false, intakeOutcome: null,
  markedBy: null, markedAt: null, markedInThisSession: false, expectedPieces: [], scannedItems: [], returnedToSender: true,
  untrackedUnits: units, unitsIn: units.filter(u => u.intakeId != null).length, canMarkReceived: false,
  counts: { expected: 0, scanned: 0 }, complete: false, itemsRequestId: null, itemsRequestReference: null, requestItems: [] })

let detail: Obj
let calls: Array<{ method: string; url: string; body?: Obj }>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(String(opts.body)) : undefined
  calls.push({ method, url, body })
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1') && method === 'GET') return json(detail)
  if (url.endsWith('/link-unit') || url.endsWith('/link-unit/undo') || url.endsWith('/disposition')) return json({})
  return json({})
}

function sessionDetail(items: Obj[], parcels: Obj[], extra: Obj = {}): Obj {
  return { id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-11-10T11:00:00Z', closed_by: null, closed_at: null,
    note: null, items, expectedPieces: [], parcels, otherItems: items, lastScan: null, phoneScanCount: 0, ...extra }
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  vi.mocked(apiModule.getRoleFromToken).mockReturnValue('owner')
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  calls = []
  detail = sessionDetail([unexpectedItem()], [parcel([UNIT])])
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

async function openSession() {
  const user = userEvent.setup()
  renderWithProviders(<Returns />)
  await user.click(await screen.findByTestId('open-session-button'))
  await screen.findByTestId('scan-input')
  return user
}

const posts = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix))

describe('the unexpected row with a link candidate', () => {
  test('offers the link first — before "Not the real piece" — naming the untracked line', async () => {
    await openSession()
    const row = await screen.findByTestId('item-piece-1')
    const link = within(row).getByTestId('link-piece-1')
    expect(link).toHaveTextContent('Eagle Tee / L / Black')
    expect(link).toHaveTextContent('order BRK-47010-EG')
    const linkBtn = within(row).getByTestId('link-unit-sellable')
    const notReal = within(row).getByText('Not the real piece')
    expect(linkBtn.compareDocumentPosition(notReal) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('a unit not marked yet: "This is that item · sellable" posts the unit with its condition', async () => {
    const user = await openSession()
    await user.click(await screen.findByTestId('link-unit-sellable'))
    await waitFor(() => expect(posts('/items/piece-1/link-unit')).toHaveLength(1))
    expect(posts('/items/piece-1/link-unit')[0].body).toEqual({ shipmentId: 'sh-1', orderItemId: 'line-1', unitNo: 1, condition: 'sellable' })
  })

  test('a unit already marked Arrived: one "This is that item", no condition sent', async () => {
    detail = sessionDetail([unexpectedItem({ linkCandidates: [candidate({ intakeId: 'in-1', condition: 'sellable' })] })],
      [parcel([{ ...UNIT, intakeId: 'in-1', condition: 'sellable', markedInSession: 'rs-1' }])])
    const user = await openSession()
    expect(await screen.findByTestId('link-piece-1')).toHaveTextContent('marked sellable')
    expect(screen.queryByTestId('link-unit-sellable')).toBeNull()
    await user.click(screen.getByTestId('link-unit'))
    await waitFor(() => expect(posts('/items/piece-1/link-unit')).toHaveLength(1))
    expect(posts('/items/piece-1/link-unit')[0].body).toMatchObject({ condition: null })
  })

  test('"Not the real piece" asks first while a link is possible; Cancel sends nothing, Mark anyway sends mismatch', async () => {
    const user = await openSession()
    await user.click(await screen.findByText('Not the real piece'))
    const confirm = await screen.findByTestId('mismatch-confirm')
    expect(confirm).toHaveTextContent('This label may be the unit for Eagle Tee / L / Black · order BRK-47010-EG · unit 1 of 1. Mark as not the real piece anyway?')
    await user.click(screen.getByTestId('mismatch-confirm-cancel'))
    expect(screen.queryByTestId('mismatch-confirm')).toBeNull()
    expect(posts('/disposition')).toHaveLength(0)

    await user.click(screen.getByText('Not the real piece'))
    await user.click(await screen.findByTestId('mismatch-confirm-yes'))
    await waitFor(() => expect(posts('/items/piece-1/disposition')).toHaveLength(1))
    expect(posts('/items/piece-1/disposition')[0].body).toMatchObject({ disposition: 'mismatch' })
  })

  test('no candidate: "Not the real piece" is immediate, as before', async () => {
    detail = sessionDetail([unexpectedItem({ linkCandidates: undefined })], [])
    const user = await openSession()
    await user.click(await screen.findByText('Not the real piece'))
    await waitFor(() => expect(posts('/items/piece-1/disposition')).toHaveLength(1))
    expect(screen.queryByTestId('mismatch-confirm')).toBeNull()
  })

  test('a worker can link (the action is not manager-only); "Not the real piece" stays a manager\'s', async () => {
    vi.mocked(apiModule.getRoleFromToken).mockReturnValue('worker')
    await openSession()
    expect(await screen.findByTestId('link-unit-sellable')).toBeInTheDocument()
    expect(screen.queryByText('Not the real piece')).toBeNull()
  })
})

describe('the merged linked row', () => {
  const linked = () => sessionDetail(
    [unexpectedItem({ disposition: 'linked', linkCandidates: undefined })],
    [parcel([{ ...UNIT, intakeId: 'in-1', condition: 'sellable', markedInSession: 'rs-1' }])],
    { unitLinks: [{ linkId: 'lk-1', pieceId: 'piece-1', pieceCode: 'P006819', intakeId: 'in-1', shipmentId: 'sh-1',
      orderItemId: 'line-1', unitNo: 1, condition: 'sellable', shopifyMayBeLow: false, canUndo: true }] })

  test('the label shows on its unit\'s row (in stock), not as a row of its own; Undo link posts the undo', async () => {
    detail = linked()
    const user = await openSession()
    const unitRow = await screen.findByTestId('untracked-unit-line-1-1')
    expect(within(unitRow).getByTestId('untracked-unit-linked')).toHaveTextContent('Label P006819 is this item')
    expect(within(unitRow).getByTestId('untracked-unit-outcome')).toHaveTextContent('Arrived · in stock')
    expect(screen.queryByTestId('item-piece-1')).toBeNull()
    expect(within(unitRow).queryByTestId('untracked-unit-undo')).toBeNull()
    expect(screen.queryByTestId('shopify-may-be-low')).toBeNull()
    await user.click(within(unitRow).getByTestId('untracked-unit-undo-link'))
    await waitFor(() => expect(posts('/items/piece-1/link-unit/undo')).toHaveLength(1))
  })

  test('case B: "Shopify may be 1 low at Main" on the linked row — a note, nothing blocked', async () => {
    detail = linked()
    ;(detail.unitLinks as Obj[])[0].shopifyMayBeLow = true
    await openSession()
    expect(await screen.findByTestId('shopify-may-be-low')).toHaveTextContent('Shopify may be 1 low at Main')
    expect(screen.getByTestId('close-session-button')).not.toBeDisabled()
  })

  test('a damaged link has no Undo here', async () => {
    detail = linked()
    ;(detail.unitLinks as Obj[])[0].condition = 'damaged'
    ;(detail.unitLinks as Obj[])[0].canUndo = false
    await openSession()
    await screen.findByTestId('untracked-unit-linked')
    expect(screen.queryByTestId('untracked-unit-undo-link')).toBeNull()
  })
})

describe('Close', () => {
  test('an illegal pending row no longer blocks Close — it says it goes to a manager', async () => {
    await openSession()
    await screen.findByTestId('item-piece-1')
    expect(screen.getByTestId('close-session-button')).not.toBeDisabled()
    expect(screen.queryByTestId('close-blocked-callout')).toBeNull()
    expect(screen.getByTestId('close-review-note')).toHaveTextContent('1 unexpected item will go to a manager for review when you close.')
  })

  test('a legal pending row (return_pending_inspection) still blocks Close', async () => {
    detail = sessionDetail([unexpectedItem(), unexpectedItem({ id: 'it-2', piece_id: 'piece-2', barcode: 'P2',
      status: 'return_pending_inspection', unexpected: false, linkCandidates: undefined })], [parcel([UNIT])])
    await openSession()
    await screen.findByTestId('item-piece-2')
    expect(screen.getByTestId('close-session-button')).toBeDisabled()
    expect(screen.getByTestId('close-blocked-callout')).toHaveTextContent('P2')
    expect(screen.getByTestId('close-blocked-callout')).not.toHaveTextContent('P006819')
  })
})

describe('strings', () => {
  test('every returns.link string exists in Arabic and is translated', () => {
    const enLink = (en as { returns: { link: Record<string, string> } }).returns.link
    const arLink = (ar as { returns: { link: Record<string, string> } }).returns.link
    for (const [k, v] of Object.entries(enLink)) {
      expect(arLink[k], k).toBeTruthy()
      expect(arLink[k], k).not.toBe(v)
    }
  })
})
