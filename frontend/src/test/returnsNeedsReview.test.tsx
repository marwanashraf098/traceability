import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { within } from '@testing-library/react'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import * as apiModule from '../api'
import Returns from '../pages/Returns'

// V246 (Q2) — an unexpected (illegal-state) row no longer blocks Close: the close summary says it went to a manager,
// and the return_needs_review alert's link (/returns?session=…&piece=…) opens that closed session's summary with the
// piece highlighted, where a manager links it, marks it "Not the real piece" or resolves it ("Nothing to do").

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

const reviewItem = (over: Obj = {}): Obj => ({ id: 'it-9', piece_id: 'piece-9', barcode: 'P006819', status: 'available',
  variant_title: 'L / Black', product_title: 'Eagle Tee', sku: 'ET-L', disposition: 'needs_review', unexpected: true,
  damage_reason: null, via_phone: false, order_number: null, scanned_at: '2026-11-10T11:02:00Z', reviewResolved: false,
  linkCandidates: [{ shipmentId: 'sh-1', awb: '5605067774', orderItemId: 'line-1', unitNo: 1, units: 1,
    orderNumber: 'BRK-47010-EG', productTitle: 'Eagle Tee', variantTitle: 'L / Black', sku: 'ET-L', intakeId: 'in-1',
    condition: 'sellable' }], ...over })

let calls: Array<{ method: string; url: string; body?: Obj }>
let openDetail: Obj
let closedItems: Obj[]

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(String(opts.body)) : undefined
  calls.push({ method, url, body })
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1') && method === 'GET') return json(openDetail)
  if (url.endsWith('/returns/sessions/rs-1/close')) return json({ sessionId: 'rs-1', pieceCount: 2, restockedCount: 1,
    damagedCount: 0, mismatchCount: 0, linkedCount: 0, needsReviewCount: 1, shipmentCount: 0, closedAt: '2026-11-10T13:17:54Z' })
  if (url.endsWith('/returns/sessions/rs-9') && method === 'GET') return json({ id: 'rs-9', status: 'closed', opened_by: 'u',
    opened_at: '2026-11-10T11:00:00Z', closed_by: 'u', closed_at: '2026-11-10T13:17:54Z', note: null, items: closedItems,
    expectedPieces: [], parcels: [], otherItems: closedItems, lastScan: null })
  return json({})
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  vi.mocked(apiModule.getRoleFromToken).mockReturnValue('owner')
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  calls = []
  closedItems = [reviewItem()]
  openDetail = { id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-11-10T11:00:00Z', closed_by: null,
    closed_at: null, note: null, items: [reviewItem({ id: 'it-1', piece_id: 'piece-1', disposition: 'pending', linkCandidates: undefined })],
    expectedPieces: [], parcels: [], lastScan: null }
  window.history.pushState({}, '', '/returns')
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals(); window.history.pushState({}, '', '/') })

const posts = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix))

describe('close with an illegal row', () => {
  test('Close goes through and the summary counts the item sent to a manager', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await screen.findByTestId('item-piece-1')
    await user.click(screen.getByTestId('close-session-button'))
    const summary = await screen.findByTestId('close-summary')
    expect(within(summary).getByTestId('summary-needs-review')).toHaveTextContent('1')
    expect(within(summary).getByTestId('summary-needs-review')).toHaveTextContent('Needs review')
    expect(within(summary).getByTestId('summary-needs-review-note')).toHaveTextContent('1 unexpected item went to a manager for review.')
    expect(within(summary).queryByTestId('summary-linked')).toBeNull()
  })
})

describe('the alert deep link: a needs-review item on the closed session', () => {
  async function openDeepLink() {
    window.history.pushState({}, '', '/returns?session=rs-9&piece=piece-9')
    renderWithProviders(<Returns />)
    return screen.findByTestId('item-piece-9')
  }

  test('opens that session\'s summary with the piece highlighted, needs review, with the manager\'s actions', async () => {
    const row = await openDeepLink()
    expect(screen.getByTestId('session-summary-screen')).toBeInTheDocument()
    expect(row.className).toContain('ring-2')
    expect(row).toHaveTextContent('Needs review')
    expect(row).toHaveTextContent('Wasn\'t expected back and the session closed without a decision.')
    expect(within(row).getByTestId('link-unit')).toBeInTheDocument()
    expect(within(row).getByTestId('review-not-real')).toBeInTheDocument()
    expect(within(row).getByTestId('review-nothing-to-do')).toBeInTheDocument()
  })

  test('link from the summary posts link-unit for that session (no condition: the unit is marked)', async () => {
    const user = userEvent.setup()
    const row = await openDeepLink()
    await user.click(within(row).getByTestId('link-unit'))
    await waitFor(() => expect(posts('/returns/sessions/rs-9/items/piece-9/link-unit')).toHaveLength(1))
    expect(posts('/returns/sessions/rs-9/items/piece-9/link-unit')[0].body)
      .toEqual({ shipmentId: 'sh-1', orderItemId: 'line-1', unitNo: 1, condition: null })
  })

  test('"Not the real piece" asks first (a link is possible), then posts mismatch', async () => {
    const user = userEvent.setup()
    const row = await openDeepLink()
    await user.click(within(row).getByTestId('review-not-real'))
    expect(within(row).getByTestId('mismatch-confirm')).toHaveTextContent('Mark as not the real piece anyway?')
    expect(posts('/disposition')).toHaveLength(0)
    await user.click(within(row).getByTestId('mismatch-confirm-yes'))
    await waitFor(() => expect(posts('/returns/sessions/rs-9/items/piece-9/disposition')).toHaveLength(1))
    expect(posts('/returns/sessions/rs-9/items/piece-9/disposition')[0].body).toMatchObject({ disposition: 'mismatch' })
  })

  test('"Nothing to do" resolves the item\'s alert', async () => {
    const user = userEvent.setup()
    const row = await openDeepLink()
    await user.click(within(row).getByTestId('review-nothing-to-do'))
    await waitFor(() => expect(posts('/exceptions/resolve')).toHaveLength(1))
    expect(posts('/exceptions/resolve')[0].body).toMatchObject({ exceptionType: 'return_needs_review',
      subjectKey: 'return_needs_review:item:it-9' })
  })

  test('a resolved item reads as resolved and offers nothing', async () => {
    closedItems = [reviewItem({ reviewResolved: true })]
    const row = await openDeepLink()
    expect(row).toHaveTextContent('Resolved — nothing to do')
    expect(within(row).queryByTestId('review-nothing-to-do')).toBeNull()
  })

  test('a worker sees the item but no manager actions', async () => {
    vi.mocked(apiModule.getRoleFromToken).mockReturnValue('worker')
    const row = await openDeepLink()
    expect(row).toHaveTextContent('Needs review')
    expect(within(row).queryByTestId('review-not-real')).toBeNull()
    expect(within(row).queryByTestId('link-unit')).toBeNull()
  })
})
