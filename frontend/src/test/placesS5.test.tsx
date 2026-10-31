import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { Routes, Route, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import type { PlaceCount, PlaceCountPiece, PlaceCountScanResult, PlaceKind, PlaceRef } from '../api'
import PlaceCountScan from '../pages/placecount/PlaceCountScan'
import MoveBoxScan from '../pages/putaway/MoveBoxScan'
import PutAway from '../pages/putaway/PutAway'
import StockTake from '../pages/StockTake'
import StockTakeScan from '../pages/StockTakeScan'
import StockTab from '../pages/inventory/StockTab'
import LookupPage from '../pages/Lookup'
import i18n from '../i18n'
import { I18nextProvider } from 'react-i18next'

/**
 * Warehouse places S5 — one-place counts, place stickers in the full stock take, the Inventory place filter,
 * "Where is it", Move a box.
 *   pc1  the count screen: counters + found / moved here / not found from the server; every scan result reads right;
 *        nothing is finished before Finish; Finish asks first, then ONE summary view
 *   pc2  a box sticker during a shelf count → "That's BOX-12 — count it separately"
 *   pc3  20 fast scans: none dropped, sent in order, one input
 *   pc4  Cancel → nothing applied, back where the count started
 *   pc5  Arabic / RTL: plural forms on the columns
 *   sc1  Stock take › Count one place: scan a sticker → the count opens
 *   st1  full stock take: a place sticker shows "Counting place X" (not counted); a piece after it says where it went
 *   iv1  Inventory: the place filter appears once a location is chosen; a shelf → placeId, "No place yet" → noPlace
 *   wh1  Where is it: from an Inventory variant row and from Lookup; per location, places with counts, No place yet
 *   bm1  Move a box: scan the box, then a shelf → moved; a refusal keeps the box
 *   pw1  Put away › Count one place, a WORKER session: the button is there, a sticker opens the count (Back → Put away)
 *   iv2  the place picker: a shelf → "Boxes on this shelf aren't included"; a rack / No place yet → no hint
 *   off  capability off → Stock take, the stock-take scan, Inventory › Stock and a Lookup piece render as on main
 *        (snapshots recorded from MAIN's original files) and nothing asks the S5 endpoints
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '1' : 'application/json') },
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
  routes = () => ({})
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Ahmed Ali', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true, places } })
}

beforeEach(async () => { await i18n.changeLanguage('en') })
afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

const s5Calls = () => calls.filter(c => /\/place-counts|\/places\/where|\/box-moves|placeId=|noPlace=/.test(c.url))

function LocationProbe() {
  const loc = useLocation()
  return <p data-testid="probe">{loc.pathname}{loc.search}</p>
}

// ── fixtures ──────────────────────────────────────────────────────────────────

function place(code: string, kind: PlaceKind, trail: string[]): PlaceRef {
  return { id: `b-${code}`, kind, code, trail, parentCode: null, parentKind: null }
}
const S2 = place('Z1-R01-S2', 'shelf', ['Z1', 'R01', 'S2'])
const S4 = place('Z1-R02-S4', 'shelf', ['Z1', 'R02', 'S4'])
const BOX12 = place('BOX-12', 'box', ['Z1', 'R01', 'S2', 'BOX-12'])

function piece(code: string, variant = 'v1'): PlaceCountPiece {
  const names: Record<string, [string, string]> = { v1: ['Black T-shirt', 'M'], v2: ['White Hoodie', 'L'], v3: ['Socks', 'Pink'] }
  return { pieceId: `p-${code}`, code, variantId: variant, productTitle: names[variant][0], variantTitle: names[variant][1], sku: null }
}

function count(over: Partial<PlaceCount> = {}): PlaceCount {
  return {
    id: 'C1', status: 'open', place: S2, locationId: 'loc-main', locationName: 'Main Warehouse', openedByName: 'Ahmed',
    scannedBy: [], finishedByName: null, createdAt: new Date().toISOString(), finishedAt: null, expected: 3, found: 0, movedHere: 0, notFound: 3,
    foundLines: [], movedLines: [], notFoundPieces: [piece('P000001'), piece('P000002'), piece('P000003', 'v2')],
    otherLines: [], applied: null, ...over,
  }
}

function scanResult(outcome: PlaceCountScanResult['outcome'], over: Partial<PlaceCountScanResult> = {}): PlaceCountScanResult {
  return { outcome, code: 'P000001', piece: piece('P000001'), place: null, fromPlace: null, locationName: null, status: null,
    viaPhone: false, count: count(), ...over }
}

function renderCount(path = '/place-counts/C1?from=settings') {
  return renderWithProviders(
    <Routes>
      <Route path="/place-counts/:id" element={<PlaceCountScan />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>, { initialEntries: [path] })
}

async function scan(code: string) {
  const input = document.querySelector('input') as HTMLInputElement
  await userEvent.type(input, `${code}{Enter}`)
}

// ── the count screen ─────────────────────────────────────────────────────────

describe('One-place count', () => {
  test('pc1 counters and columns, every result, nothing finished before Finish, one summary view', async () => {
    setup(true, 'worker')
    const results: Record<string, PlaceCountScanResult> = {
      P000001: scanResult('FOUND_HERE', { count: count({ found: 1, notFound: 2, foundLines: [line('P000001', 'found_here')],
        notFoundPieces: [piece('P000002'), piece('P000003', 'v2')] }) }),
      P000009: scanResult('MOVE_HERE', { code: 'P000009', piece: piece('P000009', 'v3'), fromPlace: S4 }),
      P000010: scanResult('PLACE_HERE', { code: 'P000010', piece: piece('P000010', 'v3') }),
      P000011: scanResult('OTHER_LOCATION', { code: 'P000011', piece: piece('P000011'), locationName: 'Zamalek Showroom' }),
      P000012: scanResult('NOT_PLACEABLE', { code: 'P000012', piece: piece('P000012'), status: 'reserved' }),
      P000013: scanResult('UNTAGGED', { code: 'P000013' }),
      P099999: scanResult('UNKNOWN', { code: 'P099999', piece: null }),
      P000014: scanResult('DUPLICATE', { code: 'P000014' }),
    }
    routes = (url, method, body) => {
      if (url.endsWith('/place-counts/C1') && method === 'GET') return count()
      if (url.endsWith('/place-counts/C1/scan')) return results[String(body?.code)]
      if (url.endsWith('/place-counts/C1/finish')) {
        return count({ status: 'finished', finishedAt: new Date().toISOString(), found: 1, notFoundPieces: [],
          scannedBy: ['Ahmed', 'Mona'], finishedByName: 'Mona',
          applied: { placed: 1, moved: 1, cleared: 2, alreadyHere: 0, skipped: [], reason: null } })
      }
      return {}
    }
    renderCount()
    expect(await screen.findByTestId('place-count-code')).toHaveTextContent('Z1-R01-S2')
    expect(screen.getByTestId('place-count-safety')).toHaveTextContent('never writes off stock and never changes Shopify')
    expect(screen.getByTestId('place-count-n-expected')).toHaveTextContent('3')
    expect(screen.getByTestId('place-count-not-found')).toHaveTextContent('Black T-shirt · M ×2')

    await scan('P000001')
    await waitFor(() => expect(screen.getByTestId('place-count-found')).toHaveTextContent('1 / 2'))
    for (const code of ['P000009', 'P000010', 'P000011', 'P000012', 'P000013', 'P099999', 'P000014']) await scan(code)
    await waitFor(() => expect(screen.getAllByTestId('place-count-scan-row')).toHaveLength(8))
    const rows = screen.getAllByTestId('place-count-scan-row').map(r => r.textContent)
    expect(rows).toEqual([
      expect.stringContaining('Already scanned'),
      expect.stringContaining('Unknown code'),
      expect.stringContaining('Untagged piece'),
      expect.stringContaining('This piece is Reserved'),
      expect.stringContaining('Belongs to Zamalek Showroom'),
      expect.stringContaining('had no place — will be placed here'),
      expect.stringContaining('was on Z1-R02-S4 — will be moved here'),
      expect.stringContaining('found on Z1-R01-S2'),
    ])
    expect(calls.some(c => c.url.endsWith('/finish'))).toBe(false)
    expect(document.querySelectorAll('input')).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'Finish count' }))
    const confirm = await screen.findByTestId('place-count-confirm')
    expect(confirm).toHaveTextContent('3 pieces not found go to "No place yet".')
    expect(calls.some(c => c.url.endsWith('/finish'))).toBe(false)
    await userEvent.click(within(confirm).getByRole('button', { name: 'Finish count' }))
    const done = await screen.findByTestId('place-count-done')
    expect(within(done).getByTestId('place-count-applied')).toHaveTextContent('Not found → No place yet2')
    expect(done).toHaveTextContent('2 pieces now show "No place yet". Nothing was written off.')
    expect(within(done).getByTestId('place-count-who')).toHaveTextContent('Counted by Ahmed, Mona · Finished by Mona')
    expect(screen.queryByTestId('place-count-scan')).toBeNull()
    await userEvent.click(within(done).getByRole('button', { name: 'Back' }))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/settings?tab=places'))
  })

  test('pc2 a box sticker during a shelf count → count it separately', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.endsWith('/place-counts/C1') && method === 'GET') return count()
      if (url.endsWith('/scan')) return scanResult('COUNT_SEPARATELY', { code: 'Q000012', piece: null, place: BOX12 })
      return {}
    }
    renderCount()
    await screen.findByTestId('place-count-scan')
    await scan('Q000012')
    expect(await screen.findByText("That's BOX-12 — count it separately")).toBeInTheDocument()
    expect(screen.getByText('Nothing recorded')).toBeInTheDocument()
  })

  test('pc3 20 fast scans: none dropped, in order, one input', async () => {
    setup(true, 'worker')
    routes = (url, method, body) => {
      if (url.endsWith('/place-counts/C1') && method === 'GET') return count()
      if (url.endsWith('/scan')) {
        return new Promise(r => setTimeout(() => r(scanResult('FOUND_HERE', { code: String(body?.code) })), 5))
      }
      return {}
    }
    renderCount()
    await screen.findByTestId('place-count-scan')
    const codes = Array.from({ length: 20 }, (_, i) => `P1000${String(i).padStart(2, '0')}`)
    const input = document.querySelector('input') as HTMLInputElement
    for (const c of codes) await userEvent.type(input, `${c}{Enter}`, { delay: null })
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/scan'))).toHaveLength(20), { timeout: 4000 })
    expect(calls.filter(c => c.url.endsWith('/scan')).map(c => c.body?.code)).toEqual(codes)
    expect(document.querySelectorAll('input')).toHaveLength(1)
  })

  test('pc4 Cancel → nothing applied, back to Stock take', async () => {
    setup(true, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/place-counts/C1') && method === 'GET') return count()
      if (url.endsWith('/cancel')) return count({ status: 'cancelled', applied: { placed: 0, moved: 0, cleared: 0, alreadyHere: 0, skipped: [], reason: 'cancelled' } })
      return {}
    }
    renderCount('/place-counts/C1?from=stock-take')
    await screen.findByTestId('place-count-scan')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel count' }))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/stock-take'))
    expect(calls.some(c => c.url.endsWith('/finish'))).toBe(false)
  })

  test('pc5 Arabic, RTL: plural forms on the columns', async () => {
    await i18n.changeLanguage('ar')
    setup(true, 'worker')
    routes = (url, method) => (url.endsWith('/place-counts/C1') && method === 'GET' ? count() : {})
    renderWithProviders(
      <I18nextProvider i18n={i18n}>
        <Routes><Route path="/place-counts/:id" element={<PlaceCountScan />} /></Routes>
      </I18nextProvider>, { initialEntries: ['/place-counts/C1'] })
    expect(await screen.findByTestId('place-count-not-found')).toHaveTextContent('لم يُعثر عليها على هذا الرف · 3 قطع')
    expect(document.documentElement.dir).toBe('rtl')
    for (const [n, text] of [[0, 'لا قطع'], [1, 'قطعة واحدة'], [2, 'قطعتان'], [5, '5 قطع'], [11, '11 قطعة'], [100, '100 قطعة']] as const) {
      expect(i18n.t('whereIsIt.pieces', { count: n })).toBe(text)
    }
    expect(i18n.t('placeCount.confirm.cleared', { count: 2 })).toBe('قطعتان لم يُعثر عليهما تصبحان «بلا مكان بعد».')
  })

  test('sc1 Stock take › Count one place: scan a sticker → the count opens', async () => {
    setup(true, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/stock-takes/sessions')) return []
      if (url.endsWith('/stock-takes/summary')) return { countsThisMonth: 0, piecesWrittenOff: 0, avgVariancePercent: null, openSessions: 0 }
      if (url.endsWith('/place-counts') && method === 'POST') return count()
      return {}
    }
    renderWithProviders(
      <Routes>
        <Route path="/stock-take" element={<StockTake />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>, { initialEntries: ['/stock-take'] })
    await userEvent.click(await screen.findByRole('button', { name: 'Count one place' }))
    const dialog = await screen.findByTestId('count-one-place')
    await userEvent.type(within(dialog).getByRole('textbox'), 'Q000002{Enter}')
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/place-counts/C1?from=stock-take'))
    expect(calls.find(c => c.url.endsWith('/place-counts'))?.body).toEqual({ scan: 'Q000002' })
  })
})

// ── the full stock take ──────────────────────────────────────────────────────

const stSession = (over: Record<string, unknown> = {}) => ({
  sessionId: 'ST1', status: 'open', scopeType: 'all', locationId: 'loc-main', completeCount: false, openedBy: 'u',
  openedByName: 'Ahmed', openedAt: new Date().toISOString(), finalizedBy: null, finalizedByName: null, finalizedAt: null,
  note: null, shopifySync: null, ...over,
})

function renderStockTakeScan() {
  return renderWithProviders(
    <Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>,
    { initialEntries: ['/stock-take/ST1/scan'] })
}

describe('Full stock take — place stickers', () => {
  test('st1 a place sticker → "Counting place X", not counted; a piece after it says where it went', async () => {
    setup(true, 'manager')
    routes = (url, method, body) => {
      if (url.endsWith('/stock-takes/sessions/ST1')) return stSession()
      if (url.endsWith('/scan') && method === 'POST') {
        return body?.barcode === 'Q000002'
          ? { sessionId: 'ST1', barcode: 'Q000002', pieceId: null, classification: 'place_set', place: S2 }
          : { sessionId: 'ST1', barcode: 'P000001', pieceId: 'p1', classification: 'match', alreadyScanned: false,
              scanDevice: 'hardware', placed: { place: S2, outcome: 'MOVED' } }
      }
      return {}
    }
    renderStockTakeScan()
    await screen.findByTestId('stocktake-scan')
    expect(screen.queryByTestId('stocktake-counting-place')).toBeNull()
    await scan('Q000002')
    expect(await screen.findByTestId('stocktake-counting-place')).toHaveTextContent('Z1-R01-S2')
    expect(screen.getByTestId('counted-display')).toHaveTextContent('0')
    await scan('P000001')
    await waitFor(() => expect(screen.getByTestId('counted-display')).toHaveTextContent('1'))
    expect(screen.getByTestId('last-scan-feedback')).toHaveTextContent('moved to Z1-R01-S2')
  })
})

// ── Inventory and Lookup ─────────────────────────────────────────────────────

const locations = [
  { id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true, is_default: true },
  { id: 'loc-show', name: 'Showroom', is_fulfillment: false, is_default: false },
]
const stockPage = {
  items: [{ id: 'pr1', title: 'Black T-shirt', imageUrl: null, status: 'active', onHand: 6, committed: null, available: 6,
    variants: [{ id: 'v1', title: 'M', sku: 'TS-BLK-M', price: '100.00', onHand: 6, committed: null, available: 6, shopifySync: 'none' }] }],
  nextCursor: null,
}
const placeTree = {
  locationId: 'loc-main', locationName: 'Main Warehouse', nextZoneCode: 'Z2', noPlaceYet: 3,
  nodes: [
    { id: 'z1', kind: 'zone', code: 'Z1', label: 'Z1', depth: 0 },
    { id: 'r1', kind: 'rack', code: 'Z1-R01', label: 'R01', depth: 1 },
    { id: 's2', kind: 'shelf', code: 'Z1-R01-S2', label: 'S2', depth: 2 },
  ].map(n => ({ ...n, name: null, purpose: null, parentId: null, scanCode: 'Q1', path: [], pieces: 0, totalPieces: 0, archived: false, createdAt: '' })),
}
const where = {
  variantId: 'v1', productTitle: 'Black T-shirt', variantTitle: 'M', sku: 'TS-BLK-M',
  locations: [{
    locationId: 'loc-main', locationName: 'Main Warehouse', main: true, total: 7,
    places: [{ binId: 's2', code: 'Z1-R01-S2', kind: 'shelf', path: ['Z1', 'R01', 'S2'], zoneCode: 'Z1', parentCode: 'Z1-R01',
      parentKind: 'rack', sortKey: '1', counts: { available: 4, onHold: 1, damaged: 0, total: 5 } }],
    noPlace: { available: 2, onHold: 0, damaged: 0, total: 2 },
  }],
}

function inventoryRoutes(url: string) {
  if (url.endsWith('/locations')) return locations
  if (url.includes('/inventory/stock')) return stockPage
  if (url.includes('/places?locationId=')) return placeTree
  if (url.includes('/places/where/v1')) return where
  return {}
}

async function chooseOption(current: string, option: string) {
  await userEvent.click(screen.getByRole('button', { name: current }))
  await userEvent.click(await screen.findByRole('button', { name: option }))
}

describe('Inventory place filter and Where is it', () => {
  test('iv1 the place filter shows once a location is chosen; shelf → placeId, No place yet → noPlace', async () => {
    setup(true, 'owner')
    routes = inventoryRoutes
    renderWithProviders(<StockTab />)
    await screen.findByText('Black T-shirt')
    expect(screen.queryByRole('button', { name: 'Every place' })).toBeNull()
    await chooseOption('All locations', 'Main Warehouse')
    await chooseOption('Every place', 'S2')
    await waitFor(() => expect(calls.some(c => c.url.includes('/inventory/stock') && c.url.includes('placeId=s2')
      && c.url.includes('locationId=loc-main'))).toBe(true))
    expect(screen.getByTestId('place-filter-shelf-hint')).toHaveTextContent("Boxes on this shelf aren't included")
    await chooseOption('S2', 'No place yet')
    expect(screen.queryByTestId('place-filter-shelf-hint')).toBeNull()
    await waitFor(() => expect(calls.some(c => c.url.includes('/inventory/stock') && c.url.includes('noPlace=true'))).toBe(true))
  })

  test('wh1 Where is it — from an Inventory variant row and from Lookup', async () => {
    setup(true, 'owner')
    routes = inventoryRoutes
    const inv = renderWithProviders(<StockTab />)
    await userEvent.click(await screen.findByText('Black T-shirt'))
    await userEvent.click(await screen.findByTestId('where-is-it-v1'))
    const drawer = await screen.findByTestId('where-is-it')
    expect(within(drawer).getAllByTestId('where-is-it-place')[0]).toHaveTextContent('Z1-R01-S2')
    expect(within(drawer).getAllByTestId('where-is-it-place')[0]).toHaveTextContent('4 available · 1 on hold')
    expect(within(drawer).getByTestId('where-is-it-no-place')).toHaveTextContent('2')
    inv.unmount()

    setup(true, 'worker')
    routes = url => (url.includes('/lookup?q=') ? lookupPiece(true) : inventoryRoutes(url))
    renderWithProviders(<LookupPage />)
    await userEvent.type(screen.getByRole('textbox'), 'P000001{Enter}')
    await userEvent.click(await screen.findByTestId('lookup-where-is-it'))
    expect(await screen.findByTestId('where-is-it')).toHaveTextContent('Main Warehouse')
  })

  test('bm1 Move a box: scan the box, then a shelf → moved; a refusal keeps the box', async () => {
    setup(true, 'worker')
    let refuse = true
    routes = (url, _method, body) => {
      if (url.endsWith('/box-moves/box')) return { box: BOX12, locationId: 'loc-main', locationName: 'Main Warehouse', pieces: 11 }
      if (url.endsWith('/box-moves')) {
        if (refuse) {
          refuse = false
          return { status: 400, reply: { code: 'BOX_TARGET', message_en: 'A box goes on a shelf or on a zone\'s floor — scan a shelf or a zone', message_ar: 'x' } }
        }
        expect(body).toEqual({ box: 'Q000012', target: 'Q000004' })
        return { outcome: 'MOVED', box: { ...BOX12, trail: ['Z1', 'R02', 'S4', 'BOX-12'] }, from: S2, to: S4 }
      }
      return {}
    }
    renderWithProviders(<Routes><Route path="/" element={<MoveBoxScan />} /></Routes>)
    await scan('Q000012')
    expect(await screen.findByTestId('move-box-chosen')).toHaveTextContent('BOX-12')
    expect(screen.getByTestId('move-box-chosen')).toHaveTextContent('11 pieces inside')
    await scan('Q000003')
    expect(await screen.findByText(/A box goes on a shelf/)).toBeInTheDocument()
    expect(screen.getByTestId('move-box-chosen')).toBeInTheDocument()
    await scan('Q000004')
    expect(await screen.findByTestId('move-box-done')).toHaveTextContent('Z1-R02-S4')
    expect(screen.getByText('BOX-12 moved to Z1-R02-S4')).toBeInTheDocument()
    expect(document.querySelectorAll('input')).toHaveLength(1)
  })
})

describe('Count one place from Put away, and the shelf hint', () => {
  test('pw1 a worker on Put away: Count one place → scan a sticker → the count opens, Back returns to Put away', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.includes('/put-away/waiting')) {
        return { locationId: 'loc-main', locationName: 'Main Warehouse', hasPlaces: true, total: 0,
          counts: { receiving: 0, returns: 0, transfers: 0, stock_take: 0, other: 0 }, source: 'all', truncated: false,
          mine: null, rows: [] }
      }
      if (url.endsWith('/put-away/summary')) return { waiting: 0, locations: [] }
      if (url.endsWith('/place-counts') && method === 'POST') return count()
      if (url.endsWith('/place-counts/C1') && method === 'GET') return count()
      if (url.endsWith('/place-counts/C1/cancel')) return count({ status: 'cancelled' })
      return {}
    }
    renderWithProviders(
      <Routes>
        <Route path="/put-away" element={<PutAway />} />
        <Route path="/place-counts/:id" element={<PlaceCountScan />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>, { initialEntries: ['/put-away'] })
    await userEvent.click(await screen.findByRole('button', { name: 'Count one place' }))
    const dialog = await screen.findByTestId('count-one-place')
    await userEvent.type(within(dialog).getByRole('textbox'), 'Q000002{Enter}')
    expect(await screen.findByTestId('place-count-scan')).toBeInTheDocument()
    expect(calls.find(c => c.url.endsWith('/place-counts'))?.body).toEqual({ scan: 'Q000002' })
    await userEvent.click(screen.getByRole('button', { name: 'Cancel count' }))
    expect(await screen.findByTestId('put-away-page')).toBeInTheDocument()
  })

  test('iv2 the shelf hint only for a shelf', async () => {
    setup(true, 'owner')
    routes = inventoryRoutes
    renderWithProviders(<StockTab />)
    await screen.findByText('Black T-shirt')
    await chooseOption('All locations', 'Main Warehouse')
    await chooseOption('Every place', 'R01')
    expect(screen.queryByTestId('place-filter-shelf-hint')).toBeNull()
    await chooseOption('R01', 'S2')
    expect(screen.getByTestId('place-filter-shelf-hint')).toHaveTextContent("Boxes on this shelf aren't included")
  })
})

function line(code: string, result: 'found_here' | 'move_here' | 'place_here') {
  return { piece: piece(code), raw: code, result, fromPlace: null, locationName: null, status: 'available', viaPhone: false,
    scannedAt: new Date().toISOString(), scannedByName: 'Ahmed' }
}

function lookupPiece(withPlace: boolean) {
  return {
    type: 'piece', id: 'p1', barcode: 'PC-P1', shortCode: 'P000001', status: 'available', canReprint: false,
    receivedAt: '2026-10-01T10:00:00Z', condition: 'good',
    variant: { id: 'v1', productTitle: 'Black T-shirt', title: 'M', sku: 'TS-BLK-M' },
    currentLocation: { id: 'loc-main', name: 'Main Warehouse' }, currentOrder: null, currentShipment: null,
    receivingSession: null, timeline: [],
    ...(withPlace ? { place: S2, canPlace: true } : {}),
  }
}

// ── capability off ───────────────────────────────────────────────────────────

describe('Places S5 — capability off', () => {
  test('off screens render as on main and never ask the S5 endpoints', async () => {
    setup(false, 'manager')
    routes = url => {
      if (url.endsWith('/stock-takes/sessions')) return []
      if (url.endsWith('/stock-takes/summary')) return { countsThisMonth: 1, piecesWrittenOff: 0, avgVariancePercent: null, openSessions: 0 }
      return {}
    }
    const list = renderWithProviders(<StockTake />)
    await screen.findByTestId('stocktake-summary')
    expect(list.container.innerHTML).toMatchSnapshot('off: stock take list')
    expect(screen.queryByRole('button', { name: 'Count one place' })).toBeNull()
    list.unmount()

    setup(false, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/stock-takes/sessions/ST1')) return stSession()
      if (url.endsWith('/scan') && method === 'POST') {
        return { sessionId: 'ST1', barcode: 'Q000002', pieceId: null, classification: 'unknown', scanDevice: 'hardware' }
      }
      return {}
    }
    const st = renderStockTakeScan()
    await screen.findByTestId('stocktake-scan')
    await scan('Q000002')
    await screen.findByTestId('last-scan-feedback')
    // The unscan button's test id carries the scan's time-based key — normalised so the snapshot is stable.
    expect(st.container.innerHTML.replace(/unscan-\d+-/g, 'unscan-KEY-')).toMatchSnapshot('off: stock take scan after a sticker')
    st.unmount()

    setup(false, 'owner')
    routes = url => (url.includes('/places') ? { status: 404, reply: {} } : inventoryRoutes(url))
    const inv = renderWithProviders(<StockTab />)
    await userEvent.click(await screen.findByText('Black T-shirt'))
    await chooseOption('All locations', 'Main Warehouse')
    await waitFor(() => expect(calls.filter(c => c.url.includes('/inventory/stock'))).toHaveLength(2))
    await new Promise(r => setTimeout(r, 30))
    expect(inv.container.innerHTML).toMatchSnapshot('off: inventory stock, a location chosen')
    inv.unmount()

    setup(false, 'owner')
    routes = url => (url.includes('/lookup?q=') ? lookupPiece(false) : {})
    const lk = renderWithProviders(<LookupPage />)
    await userEvent.type(screen.getByRole('textbox'), 'P000001{Enter}')
    await screen.findByTestId('piece-short-code')
    await new Promise(r => setTimeout(r, 30))
    expect(lk.container.innerHTML).toMatchSnapshot('off: lookup piece')
    lk.unmount()

    expect(s5Calls()).toHaveLength(0)
  })
})
