import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import StockTakeReview from '../pages/StockTakeReview'

// Stock-take review (2026-10-27): no per-row Found it / Mark lost. "Found one? Scan it" opens a
// full-screen overlay on the counting screen's own POST /scan (keyboard + phone, phone target only while
// open); "Back to counting"; the finalize confirm lists every write-off; a finalized session is read-only
// lists + a Lookup pointer; error banners never show a bare status. Real api.ts over a stubbed fetch.
//
//   rs1 open session: scan + back-to-counting buttons; uncounted rows read-only; Back to counting → scan screen
//   rs2 overlay: keyboard scan → POST /scan (no relayEventId), "Counted", the review reloads and the piece leaves
//       "Still not counted"; Done unmounts it and reloads
//   rs3 overlay outcomes: already counted / not in this count / the server's refusal text
//   rs4 phone: the review registers no target; the overlay registers "Stock take · <name>" while open; a phone
//       scan sends its relayEventId and gets the overlay's line; after Done → "No scanning screen open"
//   rs5 finalize confirm lists each piece (code, product · variant, Shopify effect); > 10 → grouped by variant
//   rs6 finalized: counted / written off / found lists + "Open Lookup"; no scan button, no row actions
//   rs7 Apply damaged still works; a body-less 404 shows readable words, never "404"; a server message is shown as-is

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let relay: RelayHandlers | null = null
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_d: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})
const empty = (status: number) => Promise.resolve({
  ok: false, status, statusText: '', headers: { get: () => null }, json: async () => { throw new Error('no body') },
})

const CONNECTED = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari', pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }

const SESSION = { sessionId: 'st-1', status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: true,
  openedBy: 'u1', openedByName: 'Owner', openedAt: '2026-10-27T07:00:00Z', finalizedBy: null, finalizedByName: null,
  finalizedAt: null, note: 'Shelf A', shopifySync: null }

type Row = Record<string, unknown>
const row = (pieceId: string, shortCode: string, over: Row = {}): Row => ({ pieceId, shortCode, variantId: 'v1',
  variantTitle: 'Blue / M', sku: 'TEE-BM', productTitle: 'Tee', liveStatus: 'available', ...over })
const buckets = (over: Record<string, Row[]> = {}) => ({ on_shelf_counted: [], on_shelf_uncounted: [], committed_to_orders: [],
  with_courier_or_delivered: [], returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [], ...over })
const plan = (over: Row = {}) => ({ scans: 3, expectedFree: 5, scannedFree: 3, coveragePercent: 60, writeOffs: 0,
  writeOffPieces: [], damageCorrections: 0, founds: 0, foundIncrements: 0, driftSkipped: 0, alreadyWrittenOff: 0,
  shopifyDecrement: 0, byVariant: [], requiresTypedConfirmation: false, minCoveragePercent: 80, maxWriteOffPercent: 20,
  blockedReason: null, ...over })

let calls: Array<{ method: string; url: string; body?: unknown }>
let routes: (url: string, method: string, body?: Row) => unknown | undefined
let uncounted: Row[]

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  relay = null
  calls = []
  uncounted = [row('p-miss', 'P000147'), row('p-miss2', 'P000151')]
  routes = (url, method, body) => {
    if (url.endsWith('/stock-takes/sessions/st-1')) return SESSION
    if (url.endsWith('/stock-takes/sessions/st-1/reconciliation')) return { sessionId: 'st-1', status: 'open',
      completeCount: true, coveragePercent: 60, buckets: buckets({ on_shelf_uncounted: uncounted }), variantRollup: [],
      scanCount: 3, phoneScanCount: 0, finalizePlan: plan() }
    if (url.endsWith('/stock-takes/sessions/st-1/scan') && method === 'POST') {
      const code = body!.barcode as string
      if (code === 'PC-p-miss') uncounted = uncounted.filter(r => r.pieceId !== 'p-miss')
      return { sessionId: 'st-1', barcode: code, pieceId: 'x', alreadyScanned: code === 'PC-again',
        classification: code === 'PC-other' ? 'out_of_scope' : 'match', scanDevice: body!.relayEventId ? 'phone' : 'hardware' }
    }
    return undefined
  }
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(String(opts.body)) : undefined
    calls.push({ method, url, body })
    if (url.includes('/station/pairings/current') && method === 'GET') return json(CONNECTED)
    const hit = routes(url, method, body)
    if (hit instanceof Promise) return hit
    return json(hit ?? {})
  }))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

const outcomes = () => calls.filter(c => c.method === 'POST' && c.url.includes('/station/relay-events/'))
  .map(c => [c.url.split('/relay-events/')[1].split('/')[0], (c.body as Row).result, (c.body as Row).message])
const targets = () => calls.filter(c => c.method === 'PUT' && c.url.includes('/station/pairings/current/target'))
  .map(c => (c.body as Row).label)
const scans = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/stock-takes/sessions/st-1/scan')).map(c => c.body)
const recCalls = () => calls.filter(c => c.url.endsWith('/reconciliation')).length
const phoneScan = (id: string, code: string) => act(() => relay!.onScan({ id, seq: 1, code, createdAt: '' }))

function renderReview() {
  return renderWithProviders(
    <PhoneScanProvider>
      <Routes>
        <Route path="/stock-take/:id/review" element={<StockTakeReview />} />
        <Route path="/stock-take/:id/scan" element={<div data-testid="counting-screen" />} />
      </Routes>
      <PhoneControl />
    </PhoneScanProvider>,
    { initialEntries: ['/stock-take/st-1/review'] })
}

function keyboardScan(code: string) {
  const input = within(screen.getByTestId('review-scan-overlay')).getByRole('textbox') as HTMLInputElement
  input.value = code
  fireEvent.keyDown(input, { key: 'Enter' })
}

describe('stock-take review — scan instead of Found it / Mark lost', () => {
  test('rs1 — open session: scan + back buttons; uncounted rows are read-only; Back to counting → the counting screen', async () => {
    const user = userEvent.setup()
    renderReview()
    expect(await screen.findByRole('button', { name: 'Found one? Scan it' })).toBeInTheDocument()
    expect(screen.getByTestId('uncounted-hint')).toBeInTheDocument()
    const missRow = screen.getByTestId('row-p-miss')
    expect(missRow).toHaveTextContent('P000147')
    expect(within(missRow).queryByRole('button')).toBeNull()
    expect(within(missRow).queryByRole('checkbox')).toBeNull()
    expect(screen.queryByText(/Found it|Mark lost/)).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Back to counting' }))
    expect(await screen.findByTestId('counting-screen')).toBeInTheDocument()
  })

  test('rs2 — overlay: a keyboard scan counts the piece through POST /scan, the review reloads; Done unmounts it', async () => {
    const user = userEvent.setup()
    renderReview()
    await user.click(await screen.findByRole('button', { name: 'Found one? Scan it' }))
    const overlay = screen.getByTestId('review-scan-overlay')
    expect(overlay.parentElement).toBe(document.body)   // full-screen, outside the shell
    expect(within(overlay).getByTestId('review-scan-uncounted')).toHaveTextContent('P000147')
    const before = recCalls()

    keyboardScan('PC-p-miss')
    await waitFor(() => expect(scans()).toEqual([{ barcode: 'PC-p-miss', condition: 'good' }]))
    expect(await within(overlay).findByText('Counted', { selector: 'span' })).toBeInTheDocument()
    await waitFor(() => expect(recCalls()).toBeGreaterThan(before))
    await waitFor(() => expect(within(overlay).getByTestId('review-scan-uncounted')).not.toHaveTextContent('P000147'))
    expect(within(overlay).getByTestId('review-scan-uncounted')).toHaveTextContent('P000151')

    const afterScan = recCalls()
    await user.click(within(overlay).getByRole('button', { name: 'Done' }))
    expect(screen.queryByTestId('review-scan-overlay')).toBeNull()
    await waitFor(() => expect(recCalls()).toBeGreaterThan(afterScan))
  })

  test('rs3 — overlay outcomes: already counted, not in this count, the server\'s refusal text', async () => {
    const user = userEvent.setup()
    const base = routes
    routes = (url, method, body) => {
      if (url.endsWith('/scan') && method === 'POST' && body!.barcode === 'U-1') {
        return json({ code: 'PIECE_UNTAGGED', message_en: 'This piece has no label — count it per product.', message_ar: 'x' }, 409)
      }
      return base(url, method, body)
    }
    renderReview()
    await user.click(await screen.findByRole('button', { name: 'Found one? Scan it' }))
    const overlay = screen.getByTestId('review-scan-overlay')

    keyboardScan('PC-again')
    expect(await within(overlay).findByText('Already counted')).toBeInTheDocument()
    keyboardScan('PC-other')
    expect(await within(overlay).findByText('Not in this count — recorded as an unexpected find')).toBeInTheDocument()
    keyboardScan('U-1')
    expect(await within(overlay).findByText('This piece has no label — count it per product.')).toBeInTheDocument()
    expect(within(overlay).getAllByTestId('review-scan-feedback')).toHaveLength(3)
  })

  test('rs4 — phone: a target only while the overlay is open; the scan carries its relayEventId', async () => {
    const user = userEvent.setup()
    renderReview()
    await screen.findByRole('button', { name: 'Found one? Scan it' })
    await waitFor(() => expect(relay).not.toBeNull())
    phoneScan('ev-0', 'PC-p-miss')
    await waitFor(() => expect(outcomes()).toEqual([['ev-0', 'rejected', 'No scanning screen open on the tablet']]))
    expect(targets()).not.toContain('Stock take · Shelf A')

    await user.click(screen.getByRole('button', { name: 'Found one? Scan it' }))
    await waitFor(() => expect(targets()).toContain('Stock take · Shelf A'))
    phoneScan('ev-1', 'PC-p-miss')
    await waitFor(() => expect(outcomes()[1]).toEqual(['ev-1', 'accepted', 'Counted']))
    expect(scans()).toEqual([{ barcode: 'PC-p-miss', condition: 'good', relayEventId: 'ev-1' }])
    expect(await screen.findByTestId('via-phone')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    phoneScan('ev-2', 'PC-p-miss2')
    await waitFor(() => expect(outcomes()[2]).toEqual(['ev-2', 'rejected', 'No scanning screen open on the tablet']))
    expect(scans()).toHaveLength(1)
  })

  const wo = (i: number, over: Row = {}) => ({ pieceId: 'w' + i, shortCode: 'P0002' + String(i).padStart(2, '0'),
    productTitle: 'Tee', variantTitle: 'Blue / M', variantId: 'v1', origin: 'available', shopifyEffect: -1, ...over })

  test('rs5 — the finalize confirm lists each piece with its Shopify effect; more than 10 → grouped', async () => {
    const user = userEvent.setup()
    let pieces: Row[] = [wo(1), wo(2, { origin: 'damaged', shopifyEffect: 0, variantTitle: 'Red / L', variantId: 'v2' })]
    const base = routes
    routes = (url, method, body) => {
      if (url.endsWith('/reconciliation')) return { sessionId: 'st-1', status: 'open', completeCount: true, coveragePercent: 60,
        buckets: buckets(), variantRollup: [], finalizePlan: plan({ writeOffs: pieces.length, writeOffPieces: pieces,
          shopifyDecrement: pieces.filter(p => p.shopifyEffect === -1).length }) }
      return base(url, method, body)
    }
    const { unmount } = renderReview()
    await user.click(await screen.findByRole('button', { name: 'Finalize' }))
    const list = await screen.findByTestId('finalize-pieces')
    expect(list).toHaveTextContent('These 2 piece(s) will be written off as lost:')
    const lines = within(list).getAllByTestId('finalize-piece')
    expect(lines[0]).toHaveTextContent('P000201')
    expect(lines[0]).toHaveTextContent('Tee · Blue / M')
    expect(lines[0]).toHaveTextContent('Shopify −1')
    expect(lines[1]).toHaveTextContent('Traced only')
    // Above the totals.
    expect(list.compareDocumentPosition(screen.getByTestId('finalize-summary')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    unmount()

    pieces = Array.from({ length: 12 }, (_, i) => wo(i + 1, i >= 10 ? { variantId: 'v2', variantTitle: 'Red / L' } : {}))
    renderReview()
    await user.click(await screen.findByRole('button', { name: 'Finalize' }))
    const grouped = await screen.findByTestId('finalize-pieces')
    expect(within(grouped).queryAllByTestId('finalize-piece')).toHaveLength(0)
    const groups = within(grouped).getAllByTestId('finalize-piece-group')
    expect(groups).toHaveLength(2)
    expect(groups[0]).toHaveTextContent('Tee · Blue / M (10)')
    expect(groups[0]).toHaveTextContent('Shopify −10')
    expect(groups[0]).toHaveTextContent('P000201')
    expect(groups[1]).toHaveTextContent('(2)')
  })

  test('rs6 — finalized: read-only lists and a Lookup pointer; no scan button, no row actions', async () => {
    routes = url => {
      if (url.endsWith('/stock-takes/sessions/st-1')) return { ...SESSION, status: 'finalized', finalizedBy: 'u1',
        finalizedByName: 'Owner', finalizedAt: '2026-10-27T09:00:00Z' }
      if (url.endsWith('/reconciliation')) return { sessionId: 'st-1', status: 'finalized', completeCount: true,
        coveragePercent: 80, variantRollup: [], buckets: buckets({
          on_shelf_counted: [row('c1', 'P000001')],
          previously_written_off: [row('lost1', 'P000147', { liveStatus: 'lost', writtenOffHere: true }),
            row('old', 'P000099', { liveStatus: 'lost', writtenOffHere: false })],
          unexpected_finds: [row('f1', 'P000050', { foundHere: true })] }) }
      return undefined
    }
    renderReview()
    expect(await screen.findByTestId('finalized-counted')).toHaveTextContent('Counted in this count (1)')
    expect(screen.getByTestId('finalized-written-off')).toHaveTextContent('Written off by this count (1)')
    expect(screen.getByTestId('finalized-written-off')).toHaveTextContent('P000147')
    expect(screen.getByTestId('finalized-written-off')).not.toHaveTextContent('P000099')
    expect(screen.getByTestId('finalized-found')).toHaveTextContent('P000050')
    expect(within(screen.getByTestId('finalized-lookup-hint')).getByRole('link', { name: /Open Lookup/ }))
      .toHaveAttribute('href', '/lookup')
    expect(screen.queryByRole('button', { name: 'Found one? Scan it' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Back to counting' })).toBeNull()
    expect(screen.queryByText(/Apply — mark damaged|Found it|Mark lost/)).toBeNull()
  })

  test('rs7 — Apply damaged still resolves; a body-less 404 reads as words, a server message as-is', async () => {
    const user = userEvent.setup()
    uncounted = [row('p-dmg', 'P000300', { flag: 'condition_mismatch' })]
    let answer: () => unknown = () => empty(404)
    const base = routes
    routes = (url, method, body) => {
      if (url.endsWith('/resolve') && method === 'POST') return answer()
      return base(url, method, body)
    }
    renderReview()
    await user.click(await screen.findByText('Apply — mark damaged'))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/resolve'))?.body)
      .toEqual([{ pieceId: 'p-dmg', action: 'mark_damaged' }]))
    expect(await screen.findByText('This count wasn\'t found — reload the page.')).toBeInTheDocument()
    expect(screen.queryByText(/404/)).toBeNull()

    answer = () => json({ code: 'UNTAGGED_COUNTED_PER_PRODUCT', message_en: 'Untagged pieces are counted per product.',
      message_ar: 'x' }, 409)
    await user.click(screen.getByText('Apply — mark damaged'))
    expect(await screen.findByText('Untagged pieces are counted per product.')).toBeInTheDocument()

    answer = () => json([{ pieceId: 'p-dmg', action: 'mark_damaged', result: 'damaged' }])
    const before = recCalls()
    await user.click(screen.getByText('Apply — mark damaged'))
    await waitFor(() => expect(recCalls()).toBeGreaterThan(before))
  })
})
