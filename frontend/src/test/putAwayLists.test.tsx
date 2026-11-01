import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { Routes, Route, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { storedSnapshot } from './storedSnapshot'
import type {
  PlaceKind, PutAwayList, PutAwayListPiece, PutAwayListPlace, PutAwayScanResult, PutAwaySession,
} from '../api'
import PutAwayListPage from '../pages/putaway/PutAwayListPage'
import PutAwayScan from '../pages/putaway/PutAwayScan'
import PutAwayListButton from '../pages/putaway/PutAwayListButton'
import { NO_SUGGESTION, groupPutAwayRows, putAwayRows } from '../pages/putaway/putAwayListRows'
import TagStockSummary from '../pages/TagStockSummary'
import Receiving from '../pages/Receiving'
import Returns from '../pages/Returns'
import TransferScanOut from '../pages/TransferScanOut'
import TransferReconcile from '../pages/TransferReconcile'
import i18n from '../i18n'
import { I18nextProvider } from 'react-i18next'

/**
 * Warehouse places S4 — put-away lists after receiving, returns and transfers.
 *   rw1  rows: place × variant, a placed piece ticks its own row, walking order (Z2 before Z10, a floor box after
 *        the zone's racks), "No suggestion" last; zones in walking order
 *   pl1  the list page: zone chips + zone rows in walking order, done rows ticked, codes struck, progress;
 *        Start → POST the scoped session → the scan loop with ?list=1
 *   pl2  my open session on the list → "Resume put away · N placed"; everything placed → no Start
 *   pl3  print: A6 by default, Print A4 switches the @page size; the same table prints
 *   pl4  Arabic / RTL: full plural forms (progress, waiting)
 *   pl5  a worker opening a transfer's list → the server's 403 reads as an error, no Start
 *   ps1  scan loop from a list: progress X / Y, re-read after each scan (ticks off), "extra (not in this list)"
 *   ps2  scan loop of a manual session (no ?list=1): never asks for a list — S2 unchanged
 *   ps3  Finish from a list → the one summary view shows the list progress, Back goes to the list
 *   bt1  the button: no places → nothing rendered, nothing asked; nothing waiting (an all count-only receipt)
 *        → nothing; waiting → "Put away now · N waiting" → the list
 *   rc1  Receiving: a finalized all-count-only receipt has no button; a finalized receipt with tagged pieces has it
 *   tg1  Tag stock summary: the button opens the session's list
 *   rt1  Returns: the button is on the closed-session summary only (restocked > 0); a worker sees it too;
 *        never on the open scan screen
 *   tr1  transfer send-out: the pieces sent, each with the place it left from, in walking order (no place last)
 *   tr2  transfer reconcile: Close → end screen "Put away returned pieces" when pieces wait; nothing waiting /
 *        no places → straight to the transfer, as today
 *   off  capability off → Tag stock summary, finalized receipt, closed returns summary, send-out and reconcile
 *        render exactly as on main (snapshots — RECORD THEM FROM MAIN'S ORIGINAL FILES before merge, the S3
 *        procedure) and nothing asks /put-away
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

const putAwayCalls = () => calls.filter(c => c.url.includes('/put-away'))
/** S4's own requests (lists and a session's list) — S2's landing summary (/put-away/summary) is not one. */
const listCalls = () => calls.filter(c => c.url.includes('/put-away/lists/') || /\/put-away\/sessions\/[^/]+\/list$/.test(c.url))

// ── fixtures ──────────────────────────────────────────────────────────────────

/** The server's walking-order key (PlaceSort): digit runs padded to 10, levels joined by a space, '~' floor boxes. */
const nat = (code: string) => code.toUpperCase().replace(/\d+/g, d => d.replace(/^0+(?=.)/, '').padStart(10, '0'))
function place(code: string, kind: PlaceKind, parents: [string, PlaceKind][]): PutAwayListPlace {
  const levels: [string, PlaceKind][] = [...parents, [code, kind]]
  const sortKey = levels.map(([c, k], i) => (i > 0 && k === 'box' && levels[i - 1][1] === 'zone' ? '~' : '') + nat(c)).join(' ')
  const parent = parents[parents.length - 1] ?? null
  return { id: `b-${code}`, kind, code, trail: levels.map(([c]) => c), parentCode: parent?.[0] ?? null,
    parentKind: parent?.[1] ?? null, zoneCode: levels[0][0], sortKey }
}
const Z10S1 = place('Z10-R01-S1', 'shelf', [['Z10', 'zone'], ['Z10-R01', 'rack']])
const Z2S9 = place('Z2-R01-S9', 'shelf', [['Z2', 'zone'], ['Z2-R01', 'rack']])
const Z2S10 = place('Z2-R01-S10', 'shelf', [['Z2', 'zone'], ['Z2-R01', 'rack']])
const BOX07 = place('BOX-07', 'box', [['Z2', 'zone']])

function piece(code: string, variant: string, over: Partial<PutAwayListPiece> = {}): PutAwayListPiece {
  const names: Record<string, [string, string]> = {
    v1: ['Black T-shirt', 'M'], v2: ['White Hoodie', 'L'], v3: ['Linen Shirt', 'S'], v4: ['Socks', 'Pink'], v5: ['Cap', 'Red'],
  }
  return { pieceId: `p-${code}`, code, variantId: variant, productTitle: names[variant][0], variantTitle: names[variant][1],
    sku: `SKU-${variant}`, status: 'available', done: false, place: null, placeReason: null, ...over }
}

const PIECES: PutAwayListPiece[] = [
  piece('P000006', 'v4'),                                                                    // no suggestion
  piece('P000001', 'v1', { place: Z10S1, placeReason: 'most_of_variant' }),
  piece('P000002', 'v2', { place: Z2S9, placeReason: 'last_place' }),
  piece('P000003', 'v2', { place: Z2S9, placeReason: 'placed', done: true }),                // placed where suggested
  piece('P000004', 'v3', { place: Z2S10, placeReason: 'placed', done: true }),
  piece('P000005', 'v5', { place: BOX07, placeReason: 'most_of_variant' }),
]

function list(over: Partial<PutAwayList> = {}): PutAwayList {
  return { source: 'receiving', sourceId: 'R1', sourceRef: 'PO-118', locationId: 'loc-main', locationName: 'Main Warehouse',
    hasPlaces: true, total: 6, placed: 2, pieces: PIECES, session: null, ...over }
}

const session = (over: Partial<PutAwaySession> = {}): PutAwaySession => ({
  id: 'S1', locationId: 'loc-main', locationName: 'Main Warehouse', status: 'open', current: null,
  placed: 0, placesUsed: 0, stillWaiting: 9, createdAt: new Date().toISOString(), closedAt: null, closeReason: null, ...over,
})

function LocationProbe() {
  const loc = useLocation()
  return <p data-testid="probe">{loc.pathname}{loc.search}</p>
}

function renderListPage(path = '/put-away/list/receiving/R1', appI18n = false) {
  const routes = (
    <Routes>
      <Route path="/put-away/list/:source/:sourceId" element={<PutAwayListPage />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>)
  // The app's own i18n instance (both languages) when a test switches language; else the test instance (English).
  return renderWithProviders(appI18n ? <I18nextProvider i18n={i18n}>{routes}</I18nextProvider> : routes, { initialEntries: [path] })
}

// ── rows ──────────────────────────────────────────────────────────────────────

describe('Put-away list — rows', () => {
  test('rw1 place × variant rows, a placed piece ticks its row, walking order, zones, no suggestion last', () => {
    const rows = putAwayRows(PIECES)
    expect(rows.map(r => r.place?.code ?? null)).toEqual(['Z2-R01-S9', 'Z2-R01-S10', 'BOX-07', 'Z10-R01-S1', null])
    const s9 = rows[0]
    expect([s9.qty, s9.done, s9.reason]).toEqual([2, 1, 'last_place'])
    expect(s9.pieces.map(p => p.code)).toEqual(['P000002', 'P000003'])                    // waiting first
    expect([rows[1].qty, rows[1].done, rows[1].reason]).toEqual([1, 1, 'placed'])
    const groups = groupPutAwayRows(rows)
    expect(groups.map(g => g.zone)).toEqual(['Z2', 'Z10', NO_SUGGESTION])
    expect(groups[0].rows.map(r => r.place?.code)).toEqual(['Z2-R01-S9', 'Z2-R01-S10', 'BOX-07'])
  })
})

// ── the list page ─────────────────────────────────────────────────────────────

describe('Put-away list — page', () => {
  test('pl1 zones in walking order, done ticked, progress; Start opens the scoped scan loop', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.endsWith('/put-away/lists/receiving/R1') && method === 'GET') return list()
      if (url.endsWith('/put-away/lists/receiving/R1/sessions') && method === 'POST') return session({ id: 'S7' })
      return {}
    }
    renderListPage()
    await screen.findByTestId('put-away-list-table')
    expect(screen.getByTestId('put-away-list-subtitle')).toHaveTextContent('Receipt · PO-118 · Main Warehouse')
    expect(screen.getByTestId('put-away-list-progress')).toHaveTextContent('2 of 6 pieces placed')
    expect(screen.getAllByTestId('put-away-zone-row').map(r => r.textContent)).toEqual(['Zone Z2', 'Zone Z10', 'No suggestion'])
    expect(screen.getAllByTestId('put-away-zone-chip').map(c => c.textContent)).toEqual(
      ['Zone Z2·2/4 placed', 'Zone Z10·0/1 placed', 'No suggestion·0/1 placed'])
    const rows = screen.getAllByTestId('put-away-list-row')
    expect(rows.map(r => r.getAttribute('data-done'))).toEqual(['false', 'true', 'false', 'false', 'false'])
    expect(within(rows[0]).getByTestId('put-away-row-qty')).toHaveTextContent('1 / 2')
    expect(within(rows[0]).getByTestId('put-away-row-reason')).toHaveTextContent('Its last place')
    expect(within(rows[2]).getByText('BOX-07 in Z2')).toBeTruthy()
    const codes = within(rows[0]).getAllByTestId('put-away-piece-code')
    expect(codes.map(c => [c.textContent, c.getAttribute('data-done')])).toEqual([['P000002', 'false'], ['P000003', 'true']])
    expect(codes[1].className).toContain('line-through')

    await userEvent.click(within(screen.getByTestId('put-away-list-start')).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/put-away/S7/scan?list=1'))
    expect(calls.filter(c => c.method === 'POST').map(c => c.url)).toEqual([expect.stringContaining('/put-away/lists/receiving/R1/sessions')])
  })

  test('pl2 my open session → Resume; everything placed → no Start', async () => {
    setup(true)
    let current = list({ session: { id: 'S7', status: 'open', placedInSession: 3, extra: 0, lastActivityAt: new Date().toISOString() } })
    routes = url => (url.includes('/put-away/lists/') ? current : {})
    const { unmount } = renderListPage()
    expect(await screen.findByTestId('put-away-list-start')).toHaveTextContent('Resume placing · 3 placed')
    unmount()

    current = list({ placed: 6, pieces: PIECES.map(p => ({ ...p, done: true, placeReason: 'placed', place: p.place ?? Z2S9 })) })
    renderListPage()
    expect(await screen.findByTestId('put-away-list-all-placed')).toHaveTextContent('Every piece on this list has a place')
    expect(screen.queryByTestId('put-away-list-start')).toBeNull()
  })

  test('pl3 print: A6 by default, A4 on request — the same table', async () => {
    setup(true)
    routes = url => (url.includes('/put-away/lists/') ? list() : {})
    const print = vi.fn()
    vi.stubGlobal('print', print)
    renderListPage()
    await screen.findByTestId('put-away-list-table')
    const style = () => [...document.querySelectorAll('style')].map(s => s.textContent ?? '').join('\n')
    expect(style()).toContain('size: A6')
    await userEvent.click(screen.getByRole('button', { name: /Print A4/ }))
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1))
    expect(style()).toContain('size: A4')
    expect(screen.getByTestId('put-away-list-table')).toBeTruthy()
  })

  test('pl4 Arabic, RTL: every count reads with its plural form', async () => {
    await i18n.changeLanguage('ar')
    setup(true)
    routes = url => (url.includes('/put-away/lists/') ? list() : {})
    renderListPage(undefined, true)
    expect(await screen.findByTestId('put-away-list-progress')).toHaveTextContent('وُضعت 2 من 6 قطع')
    expect(document.documentElement.dir).toBe('rtl')
    for (const [count, text] of [[0, 'لا شيء ينتظر'], [1, 'قطعة واحدة تنتظر'], [2, 'قطعتان تنتظران'],
        [5, '5 قطع تنتظر'], [11, '11 قطعة تنتظر'], [100, '100 قطعة تنتظر']] as const) {
      expect(i18n.t('putAway.list.waitingCount', { count })).toBe(text)
    }
    expect(i18n.t('putAway.list.extra', { count: 2 })).toBe('قطعتان إضافيتان (ليستا في هذه القائمة)')
    expect(i18n.t('putAway.list.transferEnd.body', { count: 3 })).toBe('عادت 3 قطع إلى المخزن الرئيسي وتنتظر مكانًا.')
  })

  test('pl5 a worker opening a transfer list: the 403 reads as an error, no Start', async () => {
    setup(true, 'worker')
    routes = url => (url.includes('/put-away/lists/transfer/')
      ? { status: 403, reply: { code: 'FORBIDDEN', message_en: 'Transfers are for owners and managers', message_ar: 'x' } } : {})
    renderListPage('/put-away/list/transfer/T1')
    expect(await screen.findByText('Transfers are for owners and managers')).toBeTruthy()
    expect(screen.queryByTestId('put-away-list-start')).toBeNull()
    expect(screen.queryByTestId('put-away-list-table')).toBeNull()
  })
})

// ── the scan loop on a list ───────────────────────────────────────────────────

function scanRender(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/put-away/:id/scan" element={<PutAwayScan />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>, { initialEntries: [path] })
}

describe('Put-away list — scan loop', () => {
  test('ps1 progress X / Y, re-read after each scan, extra pieces counted', async () => {
    setup(true, 'worker')
    let current = list({ total: 3, placed: 1, pieces: PIECES.slice(1, 4),
      session: { id: 'S1', status: 'open', placedInSession: 0, extra: 0, lastActivityAt: new Date().toISOString() } })
    routes = (url, method) => {
      if (url.endsWith('/put-away/sessions/S1') && method === 'GET') return session()
      if (url.endsWith('/put-away/sessions/S1/list')) return { scoped: true, list: current }
      if (url.endsWith('/put-away/sessions/S1/scan')) {
        current = { ...current, placed: 2, pieces: current.pieces.map(p => (p.code === 'P000002' ? { ...p, done: true, placeReason: 'placed' as const } : p)),
          session: { ...current.session!, placedInSession: 2, extra: 1 } }
        return { outcome: 'PLACED', code: 'P000002', piece: { id: 'p-P000002', code: 'P000002', productTitle: 'White Hoodie', variantTitle: 'L' },
          place: Z2S9, fromPlace: null, locationName: null, status: 'available',
          session: session({ placed: 2, current: { place: Z2S9, putHereNow: 2, onThisPlace: 4 } }), viaPhone: false } as PutAwayScanResult
      }
      return {}
    }
    scanRender('/put-away/S1/scan?list=1')
    expect(await screen.findByTestId('put-away-scope-progress')).toHaveTextContent('1 of 3 pieces placed')
    expect(screen.queryByTestId('put-away-scope-extra')).toBeNull()
    const input = document.querySelector('input') as HTMLInputElement
    await userEvent.type(input, 'P000002{Enter}')
    await waitFor(() => expect(screen.getByTestId('put-away-scope-progress')).toHaveTextContent('2 of 3 pieces placed'))
    expect(screen.getByTestId('put-away-scope-extra')).toHaveTextContent('1 extra (not in this list)')
    const s9 = within(screen.getByTestId('put-away-scope')).getAllByTestId('put-away-list-row')[0]
    expect(s9).toHaveAttribute('data-done', 'true')
    expect(document.querySelectorAll('input')).toHaveLength(1)                               // still one scan input
  })

  test('ps2 a manual session never asks for a list (S2 unchanged)', async () => {
    setup(true, 'worker')
    routes = (url, method) => (url.endsWith('/put-away/sessions/S1') && method === 'GET' ? session() : {})
    scanRender('/put-away/S1/scan')
    await screen.findByTestId('put-away-scan')
    await new Promise(r => setTimeout(r, 30))
    expect(calls.some(c => c.url.endsWith('/list'))).toBe(false)
    expect(screen.queryByTestId('put-away-scope')).toBeNull()
  })

  test('ps3 Finish from a list: the one summary view shows the list, Back goes to the list', async () => {
    setup(true, 'worker')
    routes = (url, method) => {
      if (url.endsWith('/put-away/sessions/S1') && method === 'GET') return session()
      if (url.endsWith('/put-away/sessions/S1/list')) return { scoped: true, list: list({ placed: 5,
        session: { id: 'S1', status: 'closed', placedInSession: 3, extra: 1, lastActivityAt: new Date().toISOString() } }) }
      if (url.endsWith('/put-away/sessions/S1/close')) return { session: session({ status: 'closed', placed: 3, placesUsed: 2, stillWaiting: 0 }), places: [] }
      return {}
    }
    scanRender('/put-away/S1/scan?list=1')
    await screen.findByTestId('put-away-scope')
    await userEvent.click(screen.getByRole('button', { name: 'Finish' }))
    expect(await screen.findByTestId('put-away-done-list')).toHaveTextContent('5 of 6 pieces placed · 1 extra (not in this list)')
    expect(screen.getAllByTestId('put-away-done')).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: 'Back to the list' }))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/put-away/list/receiving/R1'))
  })
})

// ── shared screen fixtures ───────────────────────────────────────────────────

function receivingRoutes(mode: 'count_only' | 'print_stick', listReply: PutAwayList) {
  return (url: string) => {
    if (url.endsWith('/receiving/sessions')) return []
    if (url.endsWith('/receiving/sessions/R1')) {
      return { id: 'R1', status: 'finalized', mode, reference: 'PO-118', supplier_name: null, location_name: 'Main Warehouse',
        created_at: '2026-10-30T08:00:00Z', finalized_at: '2026-10-30T09:00:00Z', line_units: 4, piece_count: 4,
        lines: [{ id: 'l1', variant_id: 'v1', quantity: 4, product_title: 'Black T-shirt', variant_title: 'M', sku: 'SKU-v1' }] }
    }
    if (url.includes('/put-away/lists/receiving/R1')) return listReply
    if (url.includes('/locations')) return [{ id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true }]
    if (url.endsWith('/label-settings')) return { receivingDefaultMode: 'print_stick' }
    return {}
  }
}


const RS = 'aaaaaaaa-0000-0000-0000-000000000001'
function returnsRoutes(status: 'open' | 'closed', dispositions: string[]) {
  return (url: string, method = 'GET') => {
    if (url.includes('/returns/awaiting-scan')) return { count: 0, items: [] }
    if (url.endsWith('/returns/sessions') && method === 'POST') return { sessionId: RS }
    if (url.endsWith(`/returns/sessions/${RS}/close`) && method === 'POST') {
      return { sessionId: RS, pieceCount: dispositions.length,
        restockedCount: dispositions.filter(d => d === 'restocked').length,
        damagedCount: dispositions.filter(d => d === 'damaged').length, mismatchCount: 0 }
    }
    if (url.includes('/returns/sessions?')) {
      return { items: [{ id: RS, status, opened_by: 'user-1', opened_at: '2026-08-10T10:00:00Z',
        closed_at: status === 'closed' ? '2026-08-10T10:30:00Z' : null, piece_count: dispositions.length,
        restocked_count: dispositions.filter(d => d === 'restocked').length,
        damaged_count: dispositions.filter(d => d === 'damaged').length, mismatch_count: 0 }], total: 1 }
    }
    if (url.endsWith('/returns/analytics')) {
      return { totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0, expectedNotScannedCount: 0,
        unassignedPendingCount: 0, unassignedPending: [] }
    }
    if (url.endsWith(`/returns/sessions/${RS}`)) {
      return { id: RS, status, opened_by: 'user-1', opened_at: '2026-08-10T10:00:00Z', closed_by: null,
        closed_at: status === 'closed' ? '2026-08-10T10:30:00Z' : null, note: null, expectedPieces: [],
        items: dispositions.map((d, i) => ({ id: `item-${i}`, piece_id: `piece-${i}`, barcode: `PC-${i}`, status: 'available',
          variant_title: 'Black · M', product_title: 'T-Shirt', sku: 'TS-BLK-M', disposition: d, unexpected: false, damage_reason: null })) }
    }
    if (url.includes(`/put-away/lists/return_session/${RS}`)) {
      return list({ source: 'return_session', sourceId: RS, sourceRef: null, total: 2, placed: 0, pieces: PIECES.slice(1, 3) })
    }
    return {}
  }
}


const transfer = (status: 'preparing' | 'reconciling') => ({
  id: 'T1', transfer_type: 'showroom', transfer_mode: 'round_trip', status, note: null, expected_return_at: null,
  created_by: 'u', created_at: '2026-10-30T08:00:00Z', closed_by: null, closed_at: null, sent_at: null, sent_by: null,
  cancelled_at: null, cancelled_by: null, reconcile_started_at: null, reconcile_started_by: null,
  destination_location_id: 'loc-show', destination_location_name: 'Showroom', source_location_id: null,
  source_location_name: null, outstandingCount: 0, piecesEverCount: 3,
  lines: [{ id: 'tl1', variant_id: 'v1', sku: 'SKU-v1', variant_title: 'M', product_title: 'Black T-shirt',
    qty_out: 3, qty_returned_good: 3, qty_condemned: 0, qty_sold: 0, qty_lost: 0 }],
})


async function closeReconcile() {
  await screen.findByTestId('reconcile-outstanding')
  await userEvent.click(screen.getByRole('button', { name: 'Close Transfer' }))
  // The confirm is a ui Modal (no dialog role): its button by name.
  await userEvent.click(await screen.findByRole('button', { name: 'Confirm Close' }))
}

function reconcileRender() {
  return renderWithProviders(
    <Routes>
      <Route path="/transfers/:id/reconcile" element={<TransferReconcile />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>, { initialEntries: ['/transfers/T1/reconcile'] })
}


// ── the button and where it goes ──────────────────────────────────────────────

describe('Put-away list — buttons', () => {
  test('bt1 no places → nothing; nothing waiting → nothing; waiting → the list', async () => {
    setup(false)
    const off = renderWithProviders(<PutAwayListButton source="receiving" sourceId="R1" label="Place on shelves now" />)
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('put-away-list-button-receiving')).toBeNull()
    expect(putAwayCalls()).toHaveLength(0)
    off.unmount()

    setup(true)
    routes = url => (url.includes('/put-away/lists/') ? list({ total: 0, placed: 0, pieces: [] }) : {})
    const none = renderWithProviders(<PutAwayListButton source="receiving" sourceId="R1" label="Place on shelves now" />)
    await waitFor(() => expect(putAwayCalls()).toHaveLength(1))
    expect(screen.queryByTestId('put-away-list-button-receiving')).toBeNull()
    none.unmount()

    setup(true)
    routes = url => (url.includes('/put-away/lists/') ? list() : {})
    renderWithProviders(
      <Routes>
        <Route path="/" element={<PutAwayListButton source="receiving" sourceId="R1" label="Place on shelves now" />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>)
    const button = await screen.findByTestId('put-away-list-button-receiving')
    expect(button).toHaveTextContent('Place on shelves now · 4 waiting')
    await userEvent.click(within(button).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/put-away/list/receiving/R1'))
  })

  test('rc1 Receiving: an all count-only receipt has no button; tagged pieces waiting → "Put away now"', async () => {
    setup(true)
    routes = receivingRoutes('count_only', list({ total: 0, placed: 0, pieces: [] }))
    const first = renderWithProviders(<Receiving />, { initialEntries: ['/receiving?session=R1'] })
    await screen.findByText('PO-118')
    await waitFor(() => expect(putAwayCalls()).toHaveLength(1))
    expect(screen.queryByTestId('put-away-list-button-receiving')).toBeNull()
    first.unmount()

    setup(true)
    routes = receivingRoutes('print_stick', list())
    renderWithProviders(<Receiving />, { initialEntries: ['/receiving?session=R1'] })
    expect(await screen.findByTestId('put-away-list-button-receiving')).toHaveTextContent('Place on shelves now · 4 waiting')
  })

  test('tg1 Tag stock summary: the button opens the session list', async () => {
    setup(true, 'manager')
    routes = url => {
      if (url.endsWith('/tag-stock/sessions/TS1')) return { id: 'TS1', startedAt: '2026-10-30T08:00:00Z', endedAt: '2026-10-30T09:00:00Z', tagged: 3, variants: [] }
      if (url.includes('/put-away/lists/tag_session/TS1')) return list({ source: 'tag_session', sourceId: 'TS1', sourceRef: null, total: 3, placed: 0 })
      return {}
    }
    renderWithProviders(
      <Routes>
        <Route path="/tag-stock/:id/summary" element={<TagStockSummary />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>, { initialEntries: ['/tag-stock/TS1/summary'] })
    const button = await screen.findByTestId('put-away-list-button-tag_session')
    expect(button).toHaveTextContent('Place on shelves now · 3 waiting')
    await userEvent.click(within(button).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/put-away/list/tag_session/TS1'))
  })

  test('rt1 Returns: one button on the closed-session summary (restocked only) — a worker after Close, a manager reopening it', async () => {
    setup(true, 'worker')
    routes = returnsRoutes('open', ['restocked', 'restocked', 'damaged'])
    const worker = renderWithProviders(
      <Routes>
        <Route path="/returns" element={<Returns />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByRole('button', { name: 'Open return session' }))
    await screen.findByTestId('open-session-screen')
    expect(screen.queryByTestId('put-away-list-button-return_session')).toBeNull()
    await userEvent.click(screen.getByTestId('close-session-button'))
    await screen.findByTestId('close-summary')
    const buttons = await screen.findAllByTestId('put-away-list-button-return_session')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]).toHaveTextContent('Place restocked pieces on shelves · 2 waiting')
    expect(screen.queryByTestId('put-away-list-button-receiving')).toBeNull()
    await userEvent.click(within(buttons[0]).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent(`/put-away/list/return_session/${RS}`))
    worker.unmount()

    setup(true, 'manager')
    routes = returnsRoutes('closed', ['restocked', 'restocked', 'damaged'])
    renderWithProviders(<Returns />, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByText('RT-AAAA'))
    await screen.findByTestId('session-summary-screen')
    expect(await screen.findAllByTestId('put-away-list-button-return_session')).toHaveLength(1)
  })

  test('rt1b Returns: nothing restocked → no button; the open scan screen never has one', async () => {
    setup(true, 'manager')
    routes = returnsRoutes('closed', ['damaged'])
    const closed = renderWithProviders(<Returns />, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByText('RT-AAAA'))
    await screen.findByTestId('session-summary-screen')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('put-away-list-button-return_session')).toBeNull()
    expect(listCalls()).toHaveLength(0)
    closed.unmount()

    setup(true, 'worker')
    routes = returnsRoutes('open', ['restocked'])
    renderWithProviders(<Returns />, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByRole('button', { name: 'Open return session' }))
    await screen.findByTestId('open-session-screen')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('put-away-list-button-return_session')).toBeNull()
    expect(listCalls()).toHaveLength(0)
  })

  test('tr1 send-out collect list: each line where to collect it (biggest place first, rest No place yet), lines by place', async () => {
    setup(true, 'manager')
    const line = (id: string, variant: string, product: string, title: string, qty: number) => ({
      id, variant_id: variant, sku: null, variant_title: title, product_title: product,
      qty_out: qty, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0 })
    const pp = (p: PutAwayListPlace, count: number) => ({ binId: p.id, code: p.code, kind: p.kind, path: p.trail,
      zoneCode: p.zoneCode, parentCode: p.parentCode, parentKind: p.parentKind, sortKey: p.sortKey, count })
    const lines = [line('tl1', 'v1', 'Black T-shirt', 'M', 3), line('tl2', 'v4', 'Socks', 'Pink', 1),
      line('tl3', 'v2', 'White Hoodie', 'L', 1), line('tl4', 'v5', 'Cap', 'Red', 1)]
    routes = (url, method) => {
      if (url.endsWith('/transfers/T1')) return { ...transfer('preparing'), lines }
      if (url.endsWith('/fulfill/places/variants') && method === 'POST') {
        return { variants: [
          { variantId: 'v1', places: [pp(Z10S1, 4), pp(Z2S10, 1)], noPlaceCount: 2 },
          { variantId: 'v4', places: [], noPlaceCount: 0 },
          { variantId: 'v2', places: [], noPlaceCount: 2 },
          { variantId: 'v5', places: [pp(Z2S9, 1)], noPlaceCount: 0 },
        ] }
      }
      return {}
    }
    renderWithProviders(
      <Routes><Route path="/transfers/:id/scan-out" element={<TransferScanOut />} /></Routes>,
      { initialEntries: ['/transfers/T1/scan-out'] })
    const section = await screen.findByTestId('transfer-collect-list')
    expect(calls.find(c => c.url.endsWith('/fulfill/places/variants'))?.body).toEqual({ variantIds: ['v1', 'v4', 'v2', 'v5'] })
    const rows = within(section).getAllByTestId('transfer-collect-line')
    expect(rows.map(r => r.textContent)).toEqual([
      expect.stringContaining('Cap · Red'), expect.stringContaining('Black T-shirt · M'),
      expect.stringContaining('Socks · Pink'), expect.stringContaining('White Hoodie · L')])
    expect(within(rows[1]).getAllByTestId('transfer-collect-place').map(p => p.textContent))
      .toEqual(['Z10-R01-S14 pieces', 'Z2-R01-S101 piece', 'No place yet2 pieces'])
    expect(rows[1]).toHaveTextContent('3 sent')
    expect(rows[2]).toHaveTextContent('None left at the main warehouse')
    expect(within(rows[3]).getAllByTestId('transfer-collect-place').map(p => p.textContent)).toEqual(['No place yet2 pieces'])
    expect(screen.queryByTestId('transfer-sent-pieces')).toBeNull()
    expect(putAwayCalls()).toHaveLength(0)
    expect(document.querySelectorAll('input')).toHaveLength(1)
  })

  test('tr1b a bring-back (relocate_return) shows no collect list and asks for no places', async () => {
    setup(true, 'manager')
    routes = url => (url.endsWith('/transfers/T1')
      ? { ...transfer('preparing'), transfer_mode: 'relocate_return', source_location_id: 'loc-show', source_location_name: 'Showroom' }
      : {})
    renderWithProviders(
      <Routes><Route path="/transfers/:id/scan-out" element={<TransferScanOut />} /></Routes>,
      { initialEntries: ['/transfers/T1/scan-out'] })
    await screen.findByTestId('transfer-scan-out')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('transfer-collect-list')).toBeNull()
    expect(calls.some(c => c.url.includes('/fulfill/places'))).toBe(false)
  })

  test('tr2 reconcile Close → end screen "Put away returned pieces" when pieces wait; else the transfer opens', async () => {
    setup(true, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/transfers/T1') && method === 'GET') return transfer('reconciling')
      if (url.endsWith('/transfers/T1/close') && method === 'POST') return {}
      if (url.includes('/put-away/lists/transfer/T1')) return list({ source: 'transfer', sourceId: 'T1', sourceRef: 'Showroom', total: 3, placed: 0 })
      return {}
    }
    const first = reconcileRender()
    await closeReconcile()
    const end = await screen.findByTestId('transfer-reconcile-end')
    expect(end).toHaveTextContent('3 pieces came back to the main warehouse and are waiting for a place.')
    await userEvent.click(within(screen.getByTestId('transfer-put-away-returned')).getByRole('button'))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/put-away/list/transfer/T1'))
    first.unmount()

    setup(true, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/transfers/T1') && method === 'GET') return transfer('reconciling')
      if (url.endsWith('/transfers/T1/close') && method === 'POST') return {}
      if (url.includes('/put-away/lists/transfer/T1')) return list({ source: 'transfer', sourceId: 'T1', total: 3, placed: 3 })
      return {}
    }
    reconcileRender()
    await closeReconcile()
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/transfers/T1'))
  })
})

// ── capability off: today's screens ───────────────────────────────────────────

describe('Put-away list — capability off', () => {
  test('off screens render as on main and never ask /put-away', async () => {
    setup(false, 'manager')
    routes = url => {
      if (url.endsWith('/tag-stock/sessions/TS1')) return { id: 'TS1', startedAt: '2026-10-30T08:00:00Z', endedAt: '2026-10-30T09:00:00Z', tagged: 3, variants: [] }
      return {}
    }
    const tag = renderWithProviders(
      <Routes><Route path="/tag-stock/:id/summary" element={<TagStockSummary />} /></Routes>,
      { initialEntries: ['/tag-stock/TS1/summary'] })
    await screen.findByTestId('tag-stock-summary')
    await new Promise(r => setTimeout(r, 30))
    expect(tag.container.innerHTML).toMatchSnapshot('off: tag stock summary')
    expect(putAwayCalls()).toHaveLength(0)
    tag.unmount()

    setup(false, 'manager')
    routes = url => (url.endsWith('/transfers/T1') ? transfer('preparing') : {})
    const out = renderWithProviders(
      <Routes><Route path="/transfers/:id/scan-out" element={<TransferScanOut />} /></Routes>,
      { initialEntries: ['/transfers/T1/scan-out'] })
    await screen.findByTestId('transfer-scan-out')
    await new Promise(r => setTimeout(r, 30))
    expect(out.container.innerHTML).toMatchSnapshot('off: transfer send-out')
    expect(putAwayCalls()).toHaveLength(0)
    expect(calls.some(c => c.url.includes('/fulfill/places'))).toBe(false)
    out.unmount()

    setup(false, 'manager')
    routes = (url, method) => {
      if (url.endsWith('/transfers/T1') && method === 'GET') return transfer('reconciling')
      if (url.endsWith('/transfers/T1/close') && method === 'POST') return {}
      return {}
    }
    const rec = reconcileRender()
    await screen.findByTestId('reconcile-outstanding')
    expect(rec.container.innerHTML).toMatchSnapshot('off: transfer reconcile')
    await closeReconcile()
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/transfers/T1'))
    expect(putAwayCalls()).toHaveLength(0)
    rec.unmount()

    setup(false, 'manager')
    routes = returnsRoutes('closed', ['restocked', 'restocked', 'damaged'])
    const ret = renderWithProviders(<Returns />, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByText('RT-AAAA'))
    await screen.findByTestId('session-summary-screen')
    await new Promise(r => setTimeout(r, 30))
    expect(ret.container.innerHTML).toMatchSnapshot('off: closed returns summary')
    expect(putAwayCalls()).toHaveLength(0)
    ret.unmount()

    setup(false, 'owner')
    routes = (url: string) => {
      if (url.endsWith('/receiving/sessions')) return []
      if (url.endsWith('/receiving/sessions/R1')) {
        return { id: 'R1', status: 'finalized', mode: 'print_stick', reference: 'PO-118', supplier_name: null, location_name: 'Main Warehouse',
          created_at: '2026-10-30T08:00:00Z', finalized_at: '2026-10-30T09:00:00Z', line_units: 4, piece_count: 4, lines: [] }
      }
      if (url.includes('/locations')) return [{ id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true }]
      return {}
    }
    const rc = renderWithProviders(<Receiving />, { initialEntries: ['/receiving?session=R1'] })
    await screen.findByText('PO-118')
    await new Promise(r => setTimeout(r, 30))
    expect(rc.container.innerHTML).toMatchSnapshot('off: finalized receipt')
    rc.unmount()

    expect(putAwayCalls()).toHaveLength(0)
  })
})

// ── places rollout: switched on, nothing set up ─────────────────────────────

/** Places switched on, but no location has a shelf or box yet (/me placesDormant). */
function setupDormant(role: 'owner' | 'manager' | 'worker') {
  setup(true, role)
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Ahmed Ali', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true,
            places: true, placesDormant: true } })
}
const OFF = (name: string) => storedSnapshot('putAwayLists.test.tsx',
  `Put-away list — capability off > off screens render as on main and never ask /put-away > off: ${name} 1`)

describe('rollout: places on, no shelf or box yet — exactly the off screens', () => {
  test('dormant: Tag stock summary, transfer send-out and reconcile, closed returns summary, finalized receipt === the off snapshots', async () => {
    setupDormant('manager')
    routes = url => {
      if (url.endsWith('/tag-stock/sessions/TS1')) return { id: 'TS1', startedAt: '2026-10-30T08:00:00Z', endedAt: '2026-10-30T09:00:00Z', tagged: 3, variants: [] }
      return {}
    }
    const tag = renderWithProviders(
      <Routes><Route path="/tag-stock/:id/summary" element={<TagStockSummary />} /></Routes>,
      { initialEntries: ['/tag-stock/TS1/summary'] })
    await screen.findByTestId('tag-stock-summary')
    await new Promise(r => setTimeout(r, 30))
    expect(tag.container.innerHTML).toBe(OFF('tag stock summary'))
    expect(putAwayCalls()).toHaveLength(0)
    tag.unmount()

    setupDormant('manager')
    routes = url => (url.endsWith('/transfers/T1') ? transfer('preparing') : {})
    const out = renderWithProviders(
      <Routes><Route path="/transfers/:id/scan-out" element={<TransferScanOut />} /></Routes>,
      { initialEntries: ['/transfers/T1/scan-out'] })
    await screen.findByTestId('transfer-scan-out')
    await new Promise(r => setTimeout(r, 30))
    expect(out.container.innerHTML).toBe(OFF('transfer send-out'))
    expect(putAwayCalls()).toHaveLength(0)
    expect(calls.some(c => c.url.includes('/fulfill/places'))).toBe(false)
    out.unmount()

    setupDormant('manager')
    routes = (url, method) => {
      if (url.endsWith('/transfers/T1') && method === 'GET') return transfer('reconciling')
      if (url.endsWith('/transfers/T1/close') && method === 'POST') return {}
      return {}
    }
    const rec = reconcileRender()
    await screen.findByTestId('reconcile-outstanding')
    expect(rec.container.innerHTML).toBe(OFF('transfer reconcile'))
    await closeReconcile()
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('/transfers/T1'))
    expect(putAwayCalls()).toHaveLength(0)
    rec.unmount()

    setupDormant('manager')
    routes = returnsRoutes('closed', ['restocked', 'restocked', 'damaged'])
    const ret = renderWithProviders(<Returns />, { initialEntries: ['/returns'] })
    await userEvent.click(await screen.findByText('RT-AAAA'))
    await screen.findByTestId('session-summary-screen')
    await new Promise(r => setTimeout(r, 30))
    expect(ret.container.innerHTML).toBe(OFF('closed returns summary'))
    expect(putAwayCalls()).toHaveLength(0)
    ret.unmount()

    setupDormant('owner')
    routes = (url: string) => {
      if (url.endsWith('/receiving/sessions')) return []
      if (url.endsWith('/receiving/sessions/R1')) {
        return { id: 'R1', status: 'finalized', mode: 'print_stick', reference: 'PO-118', supplier_name: null, location_name: 'Main Warehouse',
          created_at: '2026-10-30T08:00:00Z', finalized_at: '2026-10-30T09:00:00Z', line_units: 4, piece_count: 4, lines: [] }
      }
      if (url.includes('/locations')) return [{ id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true }]
      return {}
    }
    const rc = renderWithProviders(<Receiving />, { initialEntries: ['/receiving?session=R1'] })
    await screen.findByText('PO-118')
    await new Promise(r => setTimeout(r, 30))
    expect(rc.container.innerHTML).toBe(OFF('finalized receipt'))
    rc.unmount()

    expect(putAwayCalls()).toHaveLength(0)
    expect(calls.some(c => c.url.includes('/fulfill/places'))).toBe(false)
  })
})
