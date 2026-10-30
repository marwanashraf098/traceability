import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { resetCapabilitiesCache } from '../capabilities'
import { clearAccessToken, setAccessToken } from '../auth'
import type { PickPlace } from '../api'
import Fulfill from '../pages/Fulfill'
import FulfillRoute from '../pages/fulfill/FulfillRoute'
import GatherList from '../pages/GatherList'
import { PrintedNotPacked } from '../pages/fulfill/PackLists'
import { groupByZone, orderPlaceSummary, sortByPlace, splitAcrossPlaces } from '../pages/fulfill/placeSplit'

// Warehouse places S3 — Pick & Pack places, display only.
//   split: biggest place first, remainder → No place yet, exact cover, shortage; walking order / zones
//   gather list: rows split per place, grouped by zone (No place yet last), zone chips, print snapshot
//   pack screen: chips under the right line in queue mode and in waybill mode
//   a failing or never-answering places request leaves the screen and its scanning exactly as they were
//   printed-but-not-packed: a place column, rows sorted by place
//   places off: the gather list, the pick screen and the waybill card render exactly as before, no places request

// ── fixtures ────────────────────────────────────────────────────────────────

/** The server's walking-order key (PlaceSort): digit runs padded to ten, levels joined by a space, '~' = floor box. */
const nat = (code: string) => code.toUpperCase().replace(/\d+/g, d => d.replace(/^0+(?=.)/, '').padStart(10, '0'))
function place(code: string, count: number, chain: string[], kind: PickPlace['kind'] = 'shelf'): PickPlace {
  const levels = [...chain, code]
  const parentCode = chain.length ? chain[chain.length - 1] : null
  const parentKind: PickPlace['kind'] | null = !parentCode ? null
    : chain.length === 1 ? 'zone' : chain.length === 2 ? 'rack' : 'shelf'
  const sortKey = levels.map((c, i) => (i > 0 && kind === 'box' && i === levels.length - 1 && parentKind === 'zone' ? '~' : '') + nat(c)).join(' ')
  return { binId: `b-${code}`, code, kind, path: levels, zoneCode: levels[0], parentCode, parentKind, sortKey, count }
}
const S2 = (n: number) => place('Z1-R01-S2', n, ['Z1', 'Z1-R01'])
const S10 = (n: number) => place('Z1-R01-S10', n, ['Z1', 'Z1-R01'])
const BOX07 = (n: number) => place('BOX-07', n, ['Z1', 'Z1-R01', 'Z1-R01-S1'], 'box')
const FLOOR02 = (n: number) => place('BOX-02', n, ['Z2'], 'box')
const Z10 = (n: number) => place('Z10-R01-S1', n, ['Z10', 'Z10-R01'])

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

const fakeJwt = (role: string) => `h.${btoa(JSON.stringify({ role }))}.s`

let calls: { method: string; url: string; body?: unknown }[]
let routes: (url: string, method: string, body: unknown) => unknown

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  const r = routes(url, method, body)
  if (r instanceof Promise) return r
  if (r && typeof r === 'object' && 'status' in r && 'reply' in r) {
    return json((r as { reply: unknown }).reply, (r as { status: number }).status)
  }
  return json(r ?? {})
}

function setup(placesOn: boolean) {
  calls = []
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  stubFetchWithShellDefaults(vi.fn(backend), {
    me: { name: 'Ahmed', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false,
          flexibleLabeling: false, places: placesOn } })
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

const placesCalls = () => calls.filter(c => c.url.includes('/places'))

// ── split, walking order, zones ─────────────────────────────────────────────

describe('splitting a needed quantity across places', () => {
  test('biggest place first, then the next, until covered', () => {
    // the biggest place is the LAST one in walking order — only the count can put it first
    const parts = splitAcrossPlaces(5, [S2(2), BOX07(1), S10(4)])
    expect(parts.map(p => [p.place?.code ?? null, p.qty, p.index, p.of])).toEqual([
      ['Z1-R01-S10', 4, 1, 2], ['Z1-R01-S2', 1, 2, 2]])
  })
  test('a remainder no placed piece covers is one No place yet part', () => {
    expect(splitAcrossPlaces(6, [S2(3), BOX07(2)]).map(p => [p.place?.code ?? null, p.qty]))
      .toEqual([['Z1-R01-S2', 3], ['BOX-07', 2], [null, 1]])
    expect(splitAcrossPlaces(2, []).map(p => [p.place, p.qty, p.of])).toEqual([[null, 2, 1]])
  })
  test('exact cover: no No place yet part; one place → one part', () => {
    expect(splitAcrossPlaces(5, [S2(3), BOX07(2)]).map(p => p.place?.code ?? null)).toEqual(['Z1-R01-S2', 'BOX-07'])
    expect(splitAcrossPlaces(2, [S2(9)]).map(p => [p.place?.code, p.qty, p.of])).toEqual([['Z1-R01-S2', 2, 1]])
  })
  test('shortage (needed beyond all stock): every placed piece, then the rest as No place yet', () => {
    expect(splitAcrossPlaces(10, [S2(1), BOX07(2)]).map(p => [p.place?.code ?? null, p.qty]))
      .toEqual([['BOX-07', 2], ['Z1-R01-S2', 1], [null, 7]])
  })
  test('equal counts fall back to walking order; nothing needed still shows the item once', () => {
    expect(splitAcrossPlaces(2, [S10(1), S2(1)]).map(p => p.place?.code)).toEqual(['Z1-R01-S2', 'Z1-R01-S10'])
    expect(splitAcrossPlaces(0, [S2(1), BOX07(3)]).map(p => [p.place?.code, p.qty])).toEqual([['BOX-07', 0]])
  })
  test('walking order: S2 before S10, a box under its shelf, floor boxes after the racks, zones Z2 before Z10', () => {
    const rows = [Z10(1), FLOOR02(1), S10(1), place('Z2-R01-S1', 1, ['Z2', 'Z2-R01']), BOX07(1), S2(1)]
      .map(p => ({ place: p })).concat([{ place: null as unknown as PickPlace }])
    expect(sortByPlace(rows).map(r => r.place?.code ?? null))
      .toEqual(['BOX-07', 'Z1-R01-S2', 'Z1-R01-S10', 'Z2-R01-S1', 'BOX-02', 'Z10-R01-S1', null])
    expect(groupByZone(sortByPlace(rows)).map(g => g.zone)).toEqual(['Z1', 'Z2', 'Z10', '__no_place__'])
  })
  test('an order\'s places: walking order, each once, No place yet flagged; key = its first place', () => {
    const s = orderPlaceSummary([{ quantity: 2, places: [S10(5)] }, { quantity: 3, places: [S2(1), BOX07(1)] }])
    expect(s.codes).toEqual(['BOX-07', 'Z1-R01-S2', 'Z1-R01-S10'])
    expect(s.noPlace).toBe(true)
    expect(s.key).toBe(BOX07(1).sortKey)
    expect(orderPlaceSummary([{ quantity: 1, places: [] }])).toEqual({ codes: [], noPlace: true, key: null })
  })
})

// ── gather list ─────────────────────────────────────────────────────────────

const GATHER = {
  generatedAt: '2026-10-30T08:00:00Z', orderCount: 4,
  rows: [
    { variantId: 'v1', name: 'M', sku: 'TEE-M', displayName: 'Tee - M', needed: 5, availableCount: 8, shortage: false, orderNumbers: ['#1', '#2'] },
    { variantId: 'v2', name: 'L', sku: 'TEE-L', displayName: 'Tee - L', needed: 2, availableCount: 5, shortage: false, orderNumbers: ['#3'] },
    { variantId: 'v3', name: 'S', sku: null, displayName: 'Tee - S', needed: 3, availableCount: 1, shortage: true, orderNumbers: ['#4'] },
  ],
}
const GATHER_PLACES = {
  variants: [
    { variantId: 'v1', places: [S2(3), BOX07(1)], noPlaceCount: 4 },
    { variantId: 'v2', places: [FLOOR02(5)], noPlaceCount: 0 },
    { variantId: 'v3', places: [S10(1)], noPlaceCount: 0 },
  ],
}

function gatherRoutes(placesReply: () => unknown) {
  routes = (url, method) => {
    if (url.includes('/fulfill/gather')) return GATHER
    if (url.endsWith('/fulfill/places/variants') && method === 'POST') return placesReply()
    return {}
  }
}

describe('gather list with places', () => {
  test('split per place, grouped by zone in walking order, No place yet last; zone chips; shortage unchanged', async () => {
    setup(true)
    gatherRoutes(() => GATHER_PLACES)
    renderWithProviders(<GatherList />, { initialEntries: ['/fulfill/gather'] })
    const table = await screen.findByTestId('gather-places-table')
    expect(placesCalls()).toHaveLength(1)
    expect(placesCalls()[0].body).toEqual({ variantIds: ['v1', 'v2', 'v3'] })

    const lines = within(table).getAllByRole('row').slice(1).map(r => r.textContent)
    expect(lines).toEqual([
      'Zone Z1',
      'BOX-07Box on shelf Z1-R01-S12 of 3 placesTee - MTEE-M18#1, #2',
      'Z1-R01-S21 of 3 placesTee - MTEE-M38#1, #2',
      expect.stringContaining('Z1-R01-S10'),
      'Zone Z2',
      'BOX-02On the Z2 floorTee - LTEE-L25#3',
      'No place yet',
      'No place yet3 of 3 placesTee - MTEE-M18#1, #2',
      expect.stringContaining('Tee - S'),
    ])
    // the short variant keeps today's note on each of its rows
    const short = within(table).getAllByText('Short 2 — need 3, have 1')
    expect(short).toHaveLength(2)
    expect(within(table).getAllByTestId('gather-place-row')[2]).toHaveTextContent('Z1-R01-S10')

    const chips = screen.getAllByTestId('gather-zone-chip').map(c => c.textContent)
    expect(chips).toEqual(['Zone Z1·5 pieces', 'Zone Z2·2 pieces', 'No place yet·3 pieces'])
  })

  test('print: the same table (A6 @media print rules) — snapshot with the place column and zone grouping', async () => {
    setup(true)
    gatherRoutes(() => GATHER_PLACES)
    const { container } = renderWithProviders(<GatherList />, { initialEntries: ['/fulfill/gather'] })
    await screen.findByTestId('gather-places-table')
    const css = [...container.querySelectorAll('style')].map(s => s.textContent).join('\n')
    expect(css).toContain('@media print')
    expect(css).toContain('size: A6')
    expect(screen.getByTestId('gather-zone-summary').outerHTML + screen.getByTestId('gather-places-table').outerHTML)
      .toMatchSnapshot()
  })

  test('the places request fails → today\'s table', async () => {
    setup(true)
    gatherRoutes(() => ({ status: 500, reply: {} }))
    renderWithProviders(<GatherList />, { initialEntries: ['/fulfill/gather'] })
    await screen.findByText('Tee - M')
    await waitFor(() => expect(placesCalls()).toHaveLength(1))
    await new Promise(r => setTimeout(r, 20))
    expect(screen.queryByTestId('gather-places-table')).not.toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(4)
  })
})

// ── pack screen, queue mode ─────────────────────────────────────────────────

function pickOrder(allocated0: number) {
  return {
    id: 'order-1', number: '#101', customer_name: 'Alice', customer_phone: null, status: 'new', payment_method: null,
    cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null, shipment_id: null,
    tracking_number: null, shipment_has_courier: true, awbPrinted: false,
    items: [
      { id: 'item-0', variant_id: 'v0', sku: 'SKU-0', variant_title: 'Default Title', product_title: 'Product 0',
        quantity: 2, allocated: allocated0,
        allocatedPieces: Array.from({ length: allocated0 }, (_, i) => ({ piece_id: `p${i}`, barcode: `PIECE0${i}`,
          allocation_status: 'active', piece_status: 'reserved' })) },
      { id: 'item-1', variant_id: 'v1', sku: 'SKU-1', variant_title: 'Default Title', product_title: 'Product 1',
        quantity: 1, allocated: 0, allocatedPieces: [] },
    ],
  }
}
const ORDER_PLACES = {
  orderId: 'order-1',
  lines: [
    { orderItemId: 'item-0', variantId: 'v0', quantity: 2, places: [BOX07(3), S2(1)], noPlaceCount: 0 },
    { orderItemId: 'item-1', variantId: 'v1', quantity: 1, places: [], noPlaceCount: 2 },
  ],
}
const QUEUE = [{ id: 'order-1', number: '#101', customer_name: 'Alice', status: 'new', payment_method: null,
  cod_amount: null, total_units: 3, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false }]

let allocated: number
function pickRoutes(placesReply: () => unknown) {
  allocated = 0
  routes = (url, method) => {
    if (url.endsWith('/fulfill/queue')) return QUEUE
    if (url.includes('awaiting-waybill-count')) return { count: 0 }
    if (url.endsWith('/fulfill/mode')) return { mode: 'order_queue' }
    if (url.endsWith('/fulfill/order-1') && method === 'GET') return pickOrder(allocated)
    if (url.endsWith('/fulfill/order-1/places')) return placesReply()
    if (url.endsWith('/fulfill/order-1/scan') && method === 'POST') {
      allocated++
      return { success: true, code: 'SCANNED', message: null, pieceId: 'x', barcode: 'PIECE00',
        allocatedCount: allocated, requiredQuantity: 2, allComplete: false }
    }
    return {}
  }
}

async function openPick() {
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#101'))
  await screen.findByTestId('fulfill-pick')
  return user
}

describe('pack screen — queue mode', () => {
  test('chips under the right line: the still-needed quantity split across places', async () => {
    setup(true)
    pickRoutes(() => ORDER_PLACES)
    await openPick()
    const line0 = await screen.findByTestId('line-places-item-0')
    expect(screen.getByTestId('pick-line-item-0')).toContainElement(line0)
    expect(within(line0).getAllByTestId('place-chip').map(c => c.textContent)).toEqual(['BOX-07·2'])
    const line1 = screen.getByTestId('line-places-item-1')
    expect(screen.getByTestId('pick-line-item-1')).toContainElement(line1)
    expect(within(line1).getAllByTestId('place-chip').map(c => c.textContent)).toEqual(['No place yet·1'])
    // fetched once the order was on screen, never before it
    const order = calls.findIndex(c => c.url.endsWith('/fulfill/order-1') && c.method === 'GET')
    const places = calls.findIndex(c => c.url.endsWith('/fulfill/order-1/places'))
    expect(order).toBeGreaterThanOrEqual(0)
    expect(places).toBeGreaterThan(order)
  })

  test('a places request that never answers: the screen shows and scanning works at once, no chips', async () => {
    setup(true)
    pickRoutes(() => new Promise(() => {}))
    const user = await openPick()
    expect(screen.getByText('0/2')).toBeInTheDocument()
    await user.type(document.querySelector<HTMLInputElement>('[data-testid="fulfill-pick"] input.input-scan')!, 'PIECE00{Enter}')
    await waitFor(() => expect(screen.getByText('1/2')).toBeInTheDocument(), { timeout: 1000 })
    expect(calls.some(c => c.url.endsWith('/fulfill/order-1/scan') && c.method === 'POST')).toBe(true)
    expect(screen.queryAllByTestId('place-chip')).toHaveLength(0)
  })

  test('a failing places request: no chips, scanning works; a scan re-reads places in the background', async () => {
    setup(true)
    pickRoutes(() => ({ status: 500, reply: {} }))
    const user = await openPick()
    await waitFor(() => expect(placesCalls()).toHaveLength(1))
    await user.type(document.querySelector<HTMLInputElement>('[data-testid="fulfill-pick"] input.input-scan')!, 'PIECE00{Enter}')
    await screen.findByText('1/2')
    expect(screen.queryAllByTestId('place-chip')).toHaveLength(0)
    await waitFor(() => expect(placesCalls()).toHaveLength(2))
  })
})

// ── pack screen, waybill mode ───────────────────────────────────────────────

const LINE = { id: 'line-1', variant_id: 'v1', sku: 'LS-OLV-M', variant_title: 'Olive · M', product_title: 'Linen shirt',
  imageUrl: null, quantity: 3, allocated: 0, allocatedPieces: [] as unknown[] }
const CARD = { id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: 3,
  batchPrintedAt: '2026-10-01T07:42:00Z', items: [LINE] }
const SESSION = (openOrder: unknown) => ({ id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-01T07:05:00Z',
  workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 1 }, recent: [], openOrder })

function waybillRoutes(placesReply: () => unknown) {
  routes = (url, method) => {
    if (url.endsWith('/fulfill/mode')) return { mode: 'waybill_scan' }
    if (url.endsWith('/fulfill/queue')) return []
    if (url.endsWith('/pack-sessions/summary')) return { packedToday: 0, openSessionId: null }
    if (url.endsWith('/pack-sessions') && method === 'POST') return SESSION(null)
    if (url.endsWith('/pack-sessions/sess-1') && method === 'GET') return SESSION(null)
    if (url.endsWith('/sess-1/waybill')) return { result: 'opened', order: CARD, code: null }
    if (url.endsWith('/fulfill/order-1/places')) return placesReply()
    if (url.endsWith('/orders/order-1/scan') && method === 'POST') {
      return { status: 'scanned', scan: { success: true, code: 'SCANNED', pieceId: 'p1', barcode: 'P000001' },
        order: { ...CARD, items: [{ ...LINE, allocated: 1,
          allocatedPieces: [{ piece_id: 'p1', barcode: 'P000001', allocation_status: 'active', piece_status: 'reserved' }] }] } }
    }
    return {}
  }
}

async function openWaybillOrder() {
  const user = userEvent.setup()
  renderWithProviders(<Routes><Route path="/fulfill" element={<FulfillRoute />} /></Routes>, { initialEntries: ['/fulfill'] })
  await user.click(await screen.findByRole('button', { name: /Start pack session/ }))
  await screen.findByTestId('session-waiting')
  await user.type(screen.getByRole('textbox'), '74821903{Enter}')
  await screen.findByTestId('order-card')
  return user
}

describe('pack screen — waybill mode', () => {
  test('chips under the line of the open order card', async () => {
    setup(true)
    waybillRoutes(() => ({ orderId: 'order-1',
      lines: [{ orderItemId: 'line-1', variantId: 'v1', quantity: 3, places: [S2(2), FLOOR02(5)], noPlaceCount: 0 }] }))
    await openWaybillOrder()
    const chips = await screen.findByTestId('line-places-line-1')
    expect(screen.getByTestId('pack-line-line-1')).toContainElement(chips)
    expect(within(chips).getAllByTestId('place-chip').map(c => c.textContent)).toEqual(['BOX-02·3'])
    expect(within(chips).getByTitle('On the Z2 floor')).toBeInTheDocument()
  })

  test('a places request that never answers: piece scans still go through at once', async () => {
    setup(true)
    waybillRoutes(() => new Promise(() => {}))
    const user = await openWaybillOrder()
    await user.type(screen.getByRole('textbox'), 'P000001{Enter}')
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument(), { timeout: 1000 })
    expect(screen.queryAllByTestId('place-chip')).toHaveLength(0)
  })
})

// ── printed but not packed ──────────────────────────────────────────────────

const NOT_PACKED = [
  { orderId: 'o-a', orderNumber: '#A', customerName: 'A', shipmentId: 's-a', trackingNumber: '111', batchId: 'b', batchNo: 1,
    batchPrintedAt: '2026-10-30T07:00:00Z', status: 'waiting', packerName: null, setAsideReason: null, exceptionType: null, subjectKey: null },
  { orderId: 'o-b', orderNumber: '#B', customerName: 'B', shipmentId: 's-b', trackingNumber: '222', batchId: 'b', batchNo: 1,
    batchPrintedAt: '2026-10-30T07:00:00Z', status: 'waiting', packerName: null, setAsideReason: null, exceptionType: null, subjectKey: null },
  { orderId: 'o-c', orderNumber: '#C', customerName: 'C', shipmentId: 's-c', trackingNumber: '333', batchId: 'b', batchNo: 1,
    batchPrintedAt: '2026-10-30T07:00:00Z', status: 'waiting', packerName: null, setAsideReason: null, exceptionType: null, subjectKey: null },
]

describe('printed but not packed', () => {
  test('a place column; rows sorted by place like the gather list, no place last', async () => {
    setup(true)
    routes = (url, method) => {
      if (url.endsWith('/fulfill/printed-not-packed')) return NOT_PACKED
      if (url.endsWith('/fulfill/places/orders') && method === 'POST') return { orders: [
        { orderId: 'o-a', lines: [{ orderItemId: 'a1', variantId: 'v', quantity: 1, places: [], noPlaceCount: 1 }] },
        { orderId: 'o-b', lines: [{ orderItemId: 'b1', variantId: 'v', quantity: 2, places: [Z10(5)], noPlaceCount: 0 }] },
        { orderId: 'o-c', lines: [{ orderItemId: 'c1', variantId: 'v', quantity: 2, places: [S10(1), S2(1)], noPlaceCount: 0 }] },
      ] }
      return {}
    }
    renderWithProviders(<PrintedNotPacked refreshKey={0} />)
    await waitFor(() => expect(screen.getAllByTestId('not-packed-places')).toHaveLength(3))
    expect(placesCalls()[0].body).toEqual({ orderIds: ['o-a', 'o-b', 'o-c'] })
    const rows = screen.getAllByTestId('not-packed-row')
    expect(rows.map(r => within(r).getByText(/^#/).textContent)).toEqual(['#C', '#B', '#A'])
    expect(rows.map(r => within(r).getByTestId('not-packed-places').textContent)).toEqual([
      'PlaceZ1-R01-S2 · Z1-R01-S10', 'PlaceZ10-R01-S1', 'PlaceNo place yet'])
  })
})

// ── places off: exactly as before ───────────────────────────────────────────

describe('off: places switched off', () => {
  test('off: gather list renders exactly as before, no places request', async () => {
    setup(false)
    gatherRoutes(() => GATHER_PLACES)
    const { container } = renderWithProviders(<GatherList />, { initialEntries: ['/fulfill/gather'] })
    await screen.findByText('Tee - M')
    await new Promise(r => setTimeout(r, 20))
    expect(placesCalls()).toHaveLength(0)
    expect(container.innerHTML.replace(/Generated at [^<]*/, 'Generated at <time>')).toMatchSnapshot()
  })

  test('off: pick screen renders exactly as before, no places request', async () => {
    setup(false)
    pickRoutes(() => ORDER_PLACES)
    await openPick()
    await new Promise(r => setTimeout(r, 20))
    expect(placesCalls()).toHaveLength(0)
    expect(screen.getByTestId('fulfill-pick').outerHTML).toMatchSnapshot()
  })

  test('off: waybill order card renders exactly as before, no places request', async () => {
    setup(false)
    waybillRoutes(() => ORDER_PLACES)
    await openWaybillOrder()
    await new Promise(r => setTimeout(r, 20))
    expect(placesCalls()).toHaveLength(0)
    expect(screen.getByTestId('session-scanning').outerHTML).toMatchSnapshot()
  })

  test('off: printed but not packed renders exactly as before, no places request', async () => {
    setup(false)
    routes = url => (url.endsWith('/fulfill/printed-not-packed') ? NOT_PACKED : {})
    renderWithProviders(<PrintedNotPacked refreshKey={0} />)
    await waitFor(() => expect(screen.getAllByTestId('not-packed-row')).toHaveLength(3))
    await new Promise(r => setTimeout(r, 20))
    expect(placesCalls()).toHaveLength(0)
    expect(screen.getByTestId('printed-not-packed').outerHTML).toMatchSnapshot()
  })
})
