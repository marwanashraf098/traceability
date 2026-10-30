import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within, act } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { resetPutAwayWaitingCache } from '../pages/putaway/usePutAwayWaiting'
import type { PlaceRef, PutAwayScanResult, PutAwaySession, PutAwayWaiting } from '../api'
import PutAway from '../pages/putaway/PutAway'
import PutAwayScan from '../pages/putaway/PutAwayScan'
import Sidebar from '../components/Sidebar'
import WorkerHome from '../pages/WorkerHome'
import LookupPage from '../pages/Lookup'
import PlacesTab from '../pages/settings/PlacesTab'
import i18n from '../i18n'
import { MeProvider } from '../components/ui'

/**
 * Warehouse places S2 — Put away.
 *   pa1  waiting list: source counts, filter chips (refetch with ?source=), rows with came-from / waiting / suggestion,
 *        Start opens the session's scan loop
 *   pa2  no places for the tenant (404) → the page leaves
 *   pa3  my open session here → "Resume put away · N placed"; owner / manager: Open sessions (who, where, placed) and
 *        Close (confirm → POST close → list refreshed); a worker never sees or fetches the list
 *   ps4  a session closed elsewhere (manager / idle) → the scan says so, with the way back
 *   ps1  scan loop: exactly one auto-focusing input; place card + counts; every result type reads as it should
 *   ps2  20 fast scans typed in a row (userEvent): all 20 reach the server, in order, none dropped
 *   ps3  Finish → ONE summary view (what went where, still waiting)
 *   nv1  capability off: no Put away in the owner or worker sidebar, no WorkerHome tile, no summary request
 *   nv2  capability on: owner + worker sidebar show Put away with the waiting badge; WorkerHome tile → /put-away
 *   lk1  Lookup: place + path beside Location; Move for a worker (no Reprint): scan a sticker / pick / No place
 *   lk2  Lookup timeline phrases for put_away / place_moved / place_cleared / box_moved, EN and AR
 *   lk3  a scanned place sticker shows "That's a place sticker"
 *   st1  Settings › Places "N pieces have no place yet" links to Put away
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

let calls: { method: string; url: string; body?: Record<string, unknown> }[]
let routes: (url: string, method: string, body: Record<string, unknown> | undefined) => unknown

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  const r = routes(url, method, body)
  const reply = (x: unknown) => (x && typeof x === 'object' && 'status' in (x as object) && 'reply' in (x as object)
    ? json((x as { reply: unknown }).reply, (x as { status: number }).status) : json(x ?? {}))
  return r instanceof Promise ? r.then(reply) : reply(r)
}

function setup(places: boolean, role: 'owner' | 'manager' | 'worker' = 'owner') {
  calls = []
  resetCapabilitiesCache()
  resetPutAwayWaitingCache()
  setAccessToken(fakeJwt(role))
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Ahmed Ali', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: false, places } })
}

beforeEach(async () => { await i18n.changeLanguage('en') })
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

const shelf: PlaceRef = { id: 'b-s2', kind: 'shelf', code: 'Z1-R01-S2', trail: ['Z1', 'R01', 'S2'], parentCode: 'Z1-R01', parentKind: 'rack' }
const box: PlaceRef = { id: 'b-box', kind: 'box', code: 'BOX-07', trail: ['Z1', 'R01', 'S2', 'BOX-07'], parentCode: 'Z1-R01-S2', parentKind: 'shelf' }

const waiting = (over: Partial<PutAwayWaiting> = {}): PutAwayWaiting => ({
  locationId: 'loc-main', locationName: 'Main Warehouse', hasPlaces: true, total: 45,
  counts: { receiving: 32, returns: 9, transfers: 3, stock_take: 1, other: 0 }, source: 'all', truncated: false, mine: null,
  rows: [
    { pieceId: 'p1', code: 'P104233', variantId: 'v1', productTitle: 'Black T-shirt', variantTitle: 'M', sku: 'BT-M', status: 'available',
      source: 'receiving', sourceRef: null, waitingSince: new Date(Date.now() - 2 * 3600_000).toISOString(),
      suggestion: { place: shelf, reason: 'most_of_variant' } },
    { pieceId: 'p2', code: 'P103918', variantId: 'v2', productTitle: 'White Hoodie', variantTitle: 'L', sku: null, status: 'available',
      source: 'returns', sourceRef: '#16443', waitingSince: new Date(Date.now() - 26 * 3600_000).toISOString(),
      suggestion: { place: box, reason: 'last_place' } },
    { pieceId: 'p3', code: 'P101200', variantId: 'v3', productTitle: 'Linen Shirt', variantTitle: 'S', sku: null, status: 'damaged',
      source: 'stock_take', sourceRef: null, waitingSince: new Date(Date.now() - 3 * 86400_000).toISOString(), suggestion: null },
  ],
  ...over,
})

const session = (over: Partial<PutAwaySession> = {}): PutAwaySession => ({
  id: 'S1', locationId: 'loc-main', locationName: 'Main Warehouse', status: 'open', current: null,
  placed: 0, placesUsed: 0, stillWaiting: 45, createdAt: new Date().toISOString(), closedAt: null, closeReason: null, ...over,
})

function scanRoutes(onScan: (code: string) => PutAwayScanResult | Promise<PutAwayScanResult>) {
  return (url: string, method: string, body: Record<string, unknown> | undefined) => {
    if (url.endsWith('/put-away/sessions/S1') && method === 'GET') return session()
    if (url.endsWith('/put-away/sessions/S1/scan')) return onScan(String(body?.code))
    if (url.endsWith('/put-away/sessions/S1/close')) {
      return { session: session({ status: 'closed', placed: 3, placesUsed: 2, stillWaiting: 4 }),
               places: [{ place: box, pieces: 2 }, { place: shelf, pieces: 1 }] }
    }
    return {}
  }
}

function renderScan() {
  return renderWithProviders(
    <Routes>
      <Route path="/put-away/:id/scan" element={<PutAwayScan />} />
      <Route path="/put-away" element={<p>waiting list</p>} />
    </Routes>, { initialEntries: ['/put-away/S1/scan'] })
}

// ── waiting list ───────────────────────────────────────────────────────────────

describe('Put away — waiting list', () => {
  test('pa1 counts, filter chips, rows, Start opens the scan loop', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.endsWith('/put-away/summary')) return { waiting: 45, locations: [{ id: 'loc-main', name: 'Main Warehouse', main: true, waiting: 45 }] }
      if (url.includes('/put-away/waiting?source=returns')) return waiting({ source: 'returns', rows: [waiting().rows[1]] })
      if (url.includes('/put-away/waiting')) return waiting()
      if (url.endsWith('/put-away/sessions') && method === 'POST') return session()
      return {}
    }
    renderWithProviders(
      <Routes>
        <Route path="/put-away" element={<PutAway />} />
        <Route path="/put-away/:id/scan" element={<p>scan loop</p>} />
      </Routes>, { initialEntries: ['/put-away'] })

    await waitFor(() => expect(screen.getByTestId('put-away-total')).toHaveTextContent('45'))
    const counts = screen.getByTestId('put-away-counts')
    expect(counts).toHaveTextContent('32')
    expect(counts).toHaveTextContent('from Receiving')
    expect(counts).toHaveTextContent('9')
    expect(counts).toHaveTextContent('4')          // transfers + stock take + other

    expect(screen.getByTestId('put-away-filter-all')).toHaveTextContent('All · 45')
    expect(screen.getByTestId('put-away-filter-receiving')).toHaveTextContent('Receiving · 32')
    expect(screen.queryByTestId('put-away-filter-other')).toBeNull()     // 0 → no chip

    const hoodie = screen.getByTestId('put-away-row-P103918').closest('tr')!
    expect(hoodie).toHaveTextContent('White Hoodie')
    expect(hoodie).toHaveTextContent('Returns · #16443')
    expect(hoodie).toHaveTextContent('1 day')
    expect(hoodie).toHaveTextContent('BOX-07 (last place)')
    const tee = screen.getByTestId('put-away-row-P104233').closest('tr')!
    expect(tee).toHaveTextContent('2 h')
    expect(tee).toHaveTextContent('Z1-R01-S2')
    expect(screen.getByTestId('put-away-row-P101200').closest('tr')!).toHaveTextContent('No suggestion')

    await userEvent.click(screen.getByTestId('put-away-filter-returns'))
    await waitFor(() => expect(calls.some(c => c.url.includes('/put-away/waiting?source=returns'))).toBe(true))
    await waitFor(() => expect(screen.queryByTestId('put-away-row-P104233')).toBeNull())
    expect(screen.getByTestId('put-away-filter-returns')).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(screen.getByRole('button', { name: /Start put away/ }))
    expect(await screen.findByText('scan loop')).toBeInTheDocument()
    expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/put-away/sessions'))?.body).toEqual({ locationId: 'loc-main' })
  })

  test('pa2 no places for the tenant → the page leaves', async () => {
    setup(false)
    routes = url => (url.includes('/put-away/') ? { status: 404, reply: null } : {})
    renderWithProviders(
      <Routes>
        <Route path="/put-away" element={<PutAway />} />
        <Route path="/" element={<p>home</p>} />
      </Routes>, { initialEntries: ['/put-away'] })
    expect(await screen.findByText('home')).toBeInTheDocument()
  })
})

test('pa3 resume; open sessions for owner / manager, not for a worker', async () => {
  const twelveAgo = new Date(Date.now() - 3 * 3600_000).toISOString()
  let open = [{ id: 'S9', locationId: 'loc-main', locationName: 'Main Warehouse', openedBy: 'u9', openedByName: 'Mona',
    createdAt: twelveAgo, lastActivityAt: new Date(Date.now() - 2 * 3600_000).toISOString(), placed: 7 }]
  setup(true, 'owner')
  routes = (url, method) => {
    if (url.endsWith('/put-away/summary')) return { waiting: 45, locations: [] }
    if (url.includes('/put-away/waiting')) return waiting({ mine: { id: 'S1', placed: 4, createdAt: twelveAgo, lastActivityAt: twelveAgo } })
    if (url.endsWith('/put-away/sessions') && method === 'GET') return open
    if (url.endsWith('/put-away/sessions/S9/close')) { open = []; return { session: session({ id: 'S9', status: 'closed', closeReason: 'closed_by_manager' }), places: [] } }
    return {}
  }
  const { unmount } = renderWithProviders(<Routes><Route path="/put-away" element={<PutAway />} /></Routes>, { initialEntries: ['/put-away'] })
  expect(await screen.findByRole('button', { name: /Resume put away · 4 placed/ })).toBeInTheDocument()
  const row = await screen.findByTestId('put-away-open-S9')
  expect(row).toHaveTextContent('Mona · Main Warehouse')
  expect(row).toHaveTextContent('7 placed · started 3 h ago · last scan 2 h ago')
  await userEvent.click(within(row).getByRole('button', { name: 'Close' }))
  expect(await screen.findByText('Close this session?')).toBeInTheDocument()
  expect(screen.getByText("Mona's session ends now. The 7 pieces it placed stay where they are.")).toBeInTheDocument()
  const closeButtons = screen.getAllByRole('button', { name: 'Close' })
  await userEvent.click(closeButtons[closeButtons.length - 1])
  await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/put-away/sessions/S9/close'))).toBe(true))
  await waitFor(() => expect(screen.queryByTestId('put-away-open-sessions')).toBeNull())
  unmount()

  setup(true, 'worker')
  routes = (url) => (url.includes('/put-away/waiting') ? waiting() : url.endsWith('/put-away/summary') ? { waiting: 45, locations: [] } : open)
  renderWithProviders(<Routes><Route path="/put-away" element={<PutAway />} /></Routes>, { initialEntries: ['/put-away'] })
  expect(await screen.findByRole('button', { name: /Start put away/ })).toBeInTheDocument()
  await act(async () => { await new Promise(r => setTimeout(r, 20)) })
  expect(screen.queryByTestId('put-away-open-sessions')).toBeNull()
  expect(calls.some(c => c.method === 'GET' && c.url.endsWith('/put-away/sessions'))).toBe(false)
})

// ── scan loop ──────────────────────────────────────────────────────────────────

function result(outcome: PutAwayScanResult['outcome'], over: Partial<PutAwayScanResult> = {}): PutAwayScanResult {
  return {
    outcome, code: 'X', piece: null, place: null, fromPlace: null, locationName: null, status: null, viaPhone: false,
    session: session({ current: { place: shelf, putHereNow: 12, onThisPlace: 36 }, placed: 18, placesUsed: 3, stillWaiting: 27 }),
    ...over,
  }
}

const tee = { id: 'p1', code: 'P104233', productTitle: 'Black T-shirt', variantTitle: 'M' }

describe('Put away — scan loop', () => {
  test('ps1 one auto-focusing input; place card; every result type', async () => {
    setup(true, 'worker')
    const replies: Record<string, PutAwayScanResult> = {
      Q000107: result('PLACE_SET', { place: shelf, fromPlace: box }),
      P104233: result('PLACED', { piece: tee }),
      P103918: result('MOVED', { piece: { ...tee, code: 'P103918', productTitle: 'White Hoodie', variantTitle: 'L' }, fromPlace: box }),
      P104234: result('ALREADY_HERE', { piece: { ...tee, code: 'P104234' } }),
      Q000001: result('SCAN_SHELF_OR_BOX', { place: { ...shelf, kind: 'zone', code: 'Z1', trail: ['Z1'] } }),
      Q999999: result('UNKNOWN_STICKER'),
      Q000500: result('PLACE_OTHER_LOCATION', { place: { ...shelf, code: 'Z2-R01-S1' }, locationName: 'Second Store' }),
      Q000600: result('PLACE_ARCHIVED', { place: { ...box, code: 'BOX-99' } }),
      P200000: result('NOT_AT_THIS_LOCATION', { piece: { ...tee, code: 'P200000' }, locationName: 'Second Store' }),
      P300000: result('CANNOT_PLACE', { piece: { ...tee, code: 'P300000' }, status: 'reserved' }),
      P400000: result('UNTAGGED', { piece: { ...tee, code: null } }),
      P500000: result('SCAN_PLACE_FIRST', { piece: { ...tee, code: 'P500000' } }),
    }
    routes = scanRoutes(code => replies[code])
    renderScan()
    const input = await screen.findByPlaceholderText('Scan…')
    expect(screen.getByTestId('put-away-no-place')).toHaveTextContent('Scan a shelf or box sticker')
    expect(document.querySelectorAll('input')).toHaveLength(1)
    expect(document.activeElement).toBe(input)

    const expected: [string, string][] = [
      ['Q000107', 'Switched to shelf Z1-R01-S2'],
      ['P104233', 'P104233 · added to Z1-R01-S2'],
      ['P103918', 'P103918 · moved from BOX-07'],
      ['P104234', 'P104234 · already in Z1-R01-S2'],
      ['Q000001', 'Z1 is a zone — pieces go on a shelf or in a box'],
      ['Q999999', 'Unknown sticker'],
      ['Q000500', 'Z2-R01-S1 is in Second Store'],
      ['Q000600', 'BOX-99 is archived'],
      ['P200000', 'P200000 is at Second Store'],
      ['P300000', 'P300000 is Reserved'],
      ['P400000', 'This piece has no label yet'],
      ['P500000', 'Scan a shelf or box first'],
    ]
    for (const [code, text] of expected) {
      await userEvent.type(input, `${code}{Enter}`)
      await waitFor(() => expect(screen.getAllByTestId('put-away-scan-row')[0]).toHaveTextContent(text))
    }
    const rows = screen.getAllByTestId('put-away-scan-row')
    expect(rows.map(r => r.getAttribute('data-outcome')).slice(0, 3)).toEqual(['SCAN_PLACE_FIRST', 'UNTAGGED', 'CANNOT_PLACE'])
    expect(screen.getByTestId('put-away-current-code')).toHaveTextContent('Z1-R01-S2')
    expect(screen.getByTestId('put-away-current')).toHaveTextContent('Z1 › R01 › S2')
    expect(screen.getByTestId('put-away-here-now')).toHaveTextContent('12')
    expect(screen.getByTestId('put-away-current')).toHaveTextContent('36 on this shelf')
    expect(screen.getByTestId('put-away-placed')).toHaveTextContent('18')
    expect(screen.getByTestId('put-away-still-waiting')).toHaveTextContent('27')
    expect(document.querySelectorAll('input')).toHaveLength(1)
    expect(document.activeElement).toBe(input)
  })

  test('ps2 20 fast scans in a row: none dropped, in order', async () => {
    setup(true, 'worker')
    let placed = 0
    const seen: string[] = []
    routes = scanRoutes(code => new Promise(resolve => setTimeout(() => {
      seen.push(code)
      if (code.startsWith('Q')) { resolve(result('PLACE_SET', { place: shelf })); return }
      placed += 1
      resolve(result('PLACED', { piece: { ...tee, code },
        session: session({ current: { place: shelf, putHereNow: placed, onThisPlace: placed }, placed, placesUsed: 1, stillWaiting: 45 - placed }) }))
    }, 15)))
    renderScan()
    const input = await screen.findByPlaceholderText('Scan…')
    const codes = ['Q000107', ...Array.from({ length: 19 }, (_, i) => `P9000${String(i + 10).padStart(2, '0')}`)]
    await userEvent.type(input, codes.map(c => `${c}{Enter}`).join(''), { delay: null })

    await waitFor(() => expect(seen).toHaveLength(20), { timeout: 5000 })
    expect(seen).toEqual(codes)
    expect(calls.filter(c => c.url.endsWith('/scan')).map(c => c.body?.code)).toEqual(codes)
    await waitFor(() => expect(screen.getByTestId('put-away-placed')).toHaveTextContent('19'))
    expect(screen.queryByTestId('scan-queue-full')).toBeNull()
  })

  test('ps4 closed elsewhere → the scan says so', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.endsWith('/put-away/sessions/S1') && method === 'GET') return session()
      if (url.endsWith('/scan')) return { status: 409, reply: { code: 'SESSION_CLOSED', message_en: 'This put-away session is finished', message_ar: 'x' } }
      return {}
    }
    renderScan()
    const input = await screen.findByPlaceholderText('Scan…')
    await userEvent.type(input, 'P104233{Enter}')
    expect(await screen.findByTestId('put-away-closed-elsewhere')).toHaveTextContent('This session was closed')
    expect(screen.getAllByTestId('put-away-scan-row')[0]).toHaveTextContent('This put-away session is finished')
  })

  test('ps3 Finish → one summary view', async () => {
    setup(true)
    routes = scanRoutes(() => result('PLACE_SET', { place: shelf }))
    renderScan()
    await screen.findByPlaceholderText('Scan…')
    await userEvent.click(screen.getByRole('button', { name: 'Finish' }))
    const done = await screen.findByTestId('put-away-done')
    expect(screen.queryByTestId('put-away-scan')).toBeNull()
    expect(done).toHaveTextContent('3 pieces placed in 2 places.')
    expect(done).toHaveTextContent('BOX-07')
    expect(done).toHaveTextContent('BOX-07 on Z1-R01-S2')
    expect(done).toHaveTextContent('2 pieces')
    expect(done).toHaveTextContent('4 pieces here still have no place')
    await userEvent.click(within(done).getByRole('button', { name: 'Back to Put away' }))
    expect(await screen.findByText('waiting list')).toBeInTheDocument()
  })
})

// ── navigation ─────────────────────────────────────────────────────────────────

describe('Put away — navigation', () => {
  test('nv1 capability off: no sidebar item (owner, worker), no tile, no summary request', async () => {
    for (const role of ['owner', 'worker'] as const) {
      setup(false, role)
      routes = () => ({})
      const { unmount } = renderWithProviders(<Sidebar role={role} me={null} navOpen={false} onCloseNav={() => {}} />)
      await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(0))
      await act(async () => { await new Promise(r => setTimeout(r, 20)) })
      expect(screen.queryByRole('link', { name: /Put away/ })).toBeNull()
      unmount()
    }
    setup(false, 'worker')
    routes = () => ({})
    renderWithProviders(<MeProvider me={null}><WorkerHome /></MeProvider>)
    await act(async () => { await new Promise(r => setTimeout(r, 20)) })
    expect(screen.queryByTestId('worker-home-put-away')).toBeNull()
    expect(calls.some(c => c.url.includes('/put-away'))).toBe(false)
  })

  test('nv2 capability on: owner + worker sidebar with badge; WorkerHome tile → /put-away', async () => {
    for (const role of ['owner', 'worker'] as const) {
      setup(true, role)
      routes = url => (url.endsWith('/put-away/summary') ? { waiting: 45, locations: [] } : {})
      const { unmount } = renderWithProviders(<Sidebar role={role} me={null} navOpen={false} onCloseNav={() => {}} />)
      const link = await screen.findByRole('link', { name: /Put away/ })
      expect(link).toHaveAttribute('href', '/put-away')
      expect(await screen.findByTestId('nav-badge-put-away')).toHaveTextContent('45')
      unmount()
    }
    setup(true, 'worker')
    routes = url => (url.endsWith('/put-away/summary') ? { waiting: 5, locations: [] } : {})
    renderWithProviders(
      <Routes>
        <Route path="/" element={<MeProvider me={null}><WorkerHome /></MeProvider>} />
        <Route path="/put-away" element={<p>put away page</p>} />
      </Routes>)
    const tile = await screen.findByTestId('worker-home-put-away')
    expect(tile).toHaveTextContent('5 pieces waiting to be put away')
    expect(tile).toHaveTextContent('Start')
    await userEvent.click(tile)
    expect(await screen.findByText('put away page')).toBeInTheDocument()
  })
})

// ── Lookup ─────────────────────────────────────────────────────────────────────

const piece = (over: Record<string, unknown> = {}) => ({
  type: 'piece', id: '01PIECE', barcode: 'PC-01PIECE', shortCode: 'P800001', status: 'available', canReprint: true,
  condition: 'good', receivedAt: new Date().toISOString(),
  variant: { id: 'v1', title: 'M', sku: 'BT-M', productTitle: 'Black T-shirt' },
  currentLocation: { id: 'loc-main', name: 'Main Warehouse' }, currentOrder: null, currentShipment: null,
  receivingSession: null, timeline: [], place: box, canPlace: true, ...over,
})

const ev = (id: number, phraseKey: string, extra: Record<string, unknown>) => ({
  id, eventType: phraseKey, phraseKey, actor: 'Ahmed', isSystem: false, fromStatus: 'available', toStatus: 'available',
  orderNumber: null, orderId: null, trackingNumber: null, locationName: 'Main Warehouse', metadata: null,
  occurredAt: new Date().toISOString(), ...extra,
})

describe('Put away — Lookup', () => {
  test('lk1 place + path; Move for a worker: scan, pick, No place', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.includes('/lookup?q=')) return piece()
      if (url.includes('/put-away/places')) return [shelf, box]
      if (url.includes('/put-away/pieces/01PIECE/place') && method === 'PUT') return { kind: 'MOVED', from: box, to: shelf }
      return {}
    }
    renderWithProviders(<LookupPage />, { initialEntries: ['/lookup?q=P800001'] })
    const place = await screen.findByTestId('piece-place')
    expect(place).toHaveTextContent('BOX-07')
    expect(place).toHaveTextContent('BOX-07 on Z1-R01-S2')
    expect(screen.queryByTestId('reprint-label-btn')).toBeNull()

    await userEvent.click(screen.getByTestId('move-place-btn'))
    const dialog = await screen.findByTestId('move-place-dialog')
    expect(dialog).toHaveTextContent('Now in BOX-07')
    const scan = within(dialog).getByTestId('move-place-scan')
    expect(document.activeElement).toBe(scan)
    await userEvent.type(scan, ' q000 107 {Enter}')
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ scan: 'q000107' })
    await waitFor(() => expect(screen.queryByTestId('move-place-dialog')).toBeNull())

    await userEvent.click(await screen.findByTestId('move-place-btn'))
    await userEvent.click(await screen.findByTestId('move-place-option-Z1-R01-S2'))
    await waitFor(() => expect(calls.filter(c => c.method === 'PUT')).toHaveLength(2))
    expect(calls.filter(c => c.method === 'PUT')[1].body).toEqual({ placeId: 'b-s2' })

    await userEvent.click(await screen.findByTestId('move-place-btn'))
    await userEvent.click(within(await screen.findByTestId('move-place-dialog')).getByRole('button', { name: 'No place' }))
    await waitFor(() => expect(calls.filter(c => c.method === 'PUT')).toHaveLength(3))
    expect(calls.filter(c => c.method === 'PUT')[2].body).toEqual({})
  })

  test('lk1b a refused Move shows the server message; no Move when the piece can\'t be placed', async () => {
    setup(true, 'owner')
    routes = (url, method) => {
      if (url.includes('/lookup?q=')) return piece({ place: null })
      if (url.includes('/put-away/places')) return [shelf]
      if (method === 'PUT') return { status: 409, reply: { code: 'NOT_A_SHELF_OR_BOX', message_en: 'Pieces go on a shelf or in a box — scan a shelf or box', message_ar: 'x' } }
      return {}
    }
    renderWithProviders(<LookupPage />, { initialEntries: ['/lookup?q=P800001'] })
    expect(await screen.findByTestId('piece-no-place')).toHaveTextContent('No place yet')
    expect(screen.getByTestId('reprint-label-btn')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('move-place-btn'))
    const dialog = await screen.findByTestId('move-place-dialog')
    expect(within(dialog).queryByRole('button', { name: 'No place' })).toBeNull()
    await userEvent.type(within(dialog).getByTestId('move-place-scan'), 'Q000001{Enter}')
    expect(await within(dialog).findByTestId('move-place-error')).toHaveTextContent('Pieces go on a shelf or in a box')

    setup(true, 'owner')
    routes = url => (url.includes('/lookup?q=') ? piece({ status: 'sold', canPlace: false, place: null }) : {})
    const { unmount } = renderWithProviders(<LookupPage />, { initialEntries: ['/lookup?q=P800002'] })
    await screen.findAllByTestId('piece-no-place')
    unmount()
  })

  test('lk2 timeline phrases, EN and AR', async () => {
    const timeline = [
      ev(4, 'box_moved', { box: 'BOX-07', fromPlace: 'Z1', toPlace: 'Z1-R01-S1', fromStatus: null, toStatus: null }),
      ev(3, 'place_cleared', { fromPlace: 'BOX-07', toPlace: null }),
      ev(2, 'place_moved', { fromPlace: 'Z1-R01-S2', toPlace: 'BOX-07' }),
      ev(1, 'put_away', { fromPlace: null, toPlace: 'Z1-R01-S2' }),
    ]
    setup(true, 'owner')
    routes = url => (url.includes('/lookup?q=') ? piece({ timeline }) : {})
    const { unmount } = renderWithProviders(<LookupPage />, { initialEntries: ['/lookup?q=P800001'] })
    expect(await screen.findByText('Put away in Z1-R01-S2')).toBeInTheDocument()
    expect(screen.getByText('Moved from Z1-R01-S2 to BOX-07')).toBeInTheDocument()
    expect(screen.getByText('Place cleared (was BOX-07)')).toBeInTheDocument()
    expect(screen.getByText('Box BOX-07 moved from Z1 to Z1-R01-S1')).toBeInTheDocument()
    unmount()

    await i18n.changeLanguage('ar')
    setup(true, 'owner')
    routes = url => (url.includes('/lookup?q=') ? piece({ timeline }) : {})
    renderWithProviders(<I18nextProvider i18n={i18n}><LookupPage /></I18nextProvider>, { initialEntries: ['/lookup?q=P800001'] })
    expect(await screen.findByText('وُضعت في Z1-R01-S2')).toBeInTheDocument()
    expect(screen.getByText('نُقلت من Z1-R01-S2 إلى BOX-07')).toBeInTheDocument()
    expect(screen.getByText('أُزيل المكان (كانت في BOX-07)')).toBeInTheDocument()
    expect(screen.getByText('نُقل الصندوق BOX-07 من Z1 إلى Z1-R01-S1')).toBeInTheDocument()
    expect(screen.getByTestId('piece-place')).toHaveTextContent('BOX-07 على Z1-R01-S2')
    expect(document.documentElement.dir).toBe('rtl')
  })

  test('lk3 a scanned place sticker', async () => {
    setup(true, 'worker')
    routes = url => (url.includes('/lookup?q=') ? { type: 'place', ...shelf, scanCode: 'Q000107', archived: false,
      locationName: 'Main Warehouse', pieces: 36 } : {})
    renderWithProviders(<LookupPage />, { initialEntries: ['/lookup?q=Q000107'] })
    const view = await screen.findByTestId('place-sticker-view')
    expect(view).toHaveTextContent("That's a place sticker")
    expect(view).toHaveTextContent('Z1-R01-S2')
    expect(view).toHaveTextContent('Z1 › R01 › S2 · Main Warehouse')
    expect(view).toHaveTextContent('36 pieces here')
  })
})

// ── Settings › Places ─────────────────────────────────────────────────────────

test('st1 Settings › Places "no place yet" links to Put away', async () => {
  setup(true, 'owner')
  const zone = { id: 'z1', kind: 'zone', code: 'Z1', label: 'Z1', name: null, purpose: null, parentId: null, scanCode: 'Q000001',
    path: [], depth: 0, pieces: 0, totalPieces: 0, archived: false, createdAt: new Date().toISOString() }
  routes = url => {
    if (url.includes('/places')) return { locationId: 'loc-main', locationName: 'Main Warehouse', nextZoneCode: 'Z2', nodes: [zone], noPlaceYet: 3 }
    if (url.includes('/locations')) return []
    return {}
  }
  renderWithProviders(
    <Routes>
      <Route path="/" element={<PlacesTab />} />
      <Route path="/put-away" element={<p>put away page</p>} />
    </Routes>)
  const link = await screen.findByTestId('places-put-away-link')
  expect(screen.getByTestId('places-no-place')).toHaveTextContent('3 pieces have no place yet')
  await userEvent.click(link)
  expect(await screen.findByText('put away page')).toBeInTheDocument()
})
