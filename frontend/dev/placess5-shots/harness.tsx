// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App with places switched on, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=count|countdone|stocktake|inventory|movebox
import '@fontsource-variable/geist'
import '@fontsource/geist-mono'
import '@fontsource/cairo/400.css'
import '@fontsource/cairo/600.css'
import '@fontsource/cairo/700.css'
import { createRoot } from 'react-dom/client'
import '../../src/index.css'
import i18n from '../../src/i18n'
import App from '../../src/App'
import { setAccessToken } from '../../src/auth'
import type { PlaceCount, PlaceCountLine, PlaceCountPiece, PlaceKind, PlaceRef } from '../../src/api'

const q = new URLSearchParams(location.search)
const lang = q.get('lang') === 'ar' ? 'ar' : 'en'
const view = q.get('view') ?? 'count'
const ar = lang === 'ar'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = ar ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const role = view === 'count' || view === 'movebox' ? 'worker' : 'owner'
const payload = btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const L = (en: string, a: string) => (ar ? a : en)
const place = (code: string, kind: PlaceKind, trail: string[]): PlaceRef =>
  ({ id: `b-${code}`, kind, code, trail, parentCode: trail.length > 1 ? trail[trail.length - 2] : null, parentKind: null })
const S2 = place('A-R01-S2', 'shelf', ['A', 'R01', 'S2'])
const S4 = place('A-R02-S4', 'shelf', ['A', 'R02', 'S4'])
const BOX07 = place('BOX-07', 'box', ['A', 'BOX-07'])
const BOX12 = place('BOX-12', 'box', ['A', 'R01', 'S2', 'BOX-12'])

const names: Record<string, [string, string]> = {
  v1: [L('Black T-shirt', 'تيشيرت أسود'), 'M'], v2: [L('White Hoodie', 'هودي أبيض'), 'L'],
  v3: [L('Socks', 'جوارب'), L('Pink', 'وردي')], v4: [L('Cap', 'كاب'), L('Black', 'أسود')],
}
let k = 0
const piece = (v: string): PlaceCountPiece => ({ pieceId: `p${++k}`, code: `P10${3800 + k}`, variantId: v,
  productTitle: names[v][0], variantTitle: names[v][1], sku: null })
const line = (p: PlaceCountPiece, result: PlaceCountLine['result'], from: PlaceRef | null = null, extra: Partial<PlaceCountLine> = {}): PlaceCountLine =>
  ({ piece: p, raw: p.code ?? '', result, fromPlace: from, locationName: null, status: 'available', viaPhone: false,
     scannedAt: new Date().toISOString(), ...extra })

const found = [...Array(6)].map(() => line(piece('v1'), 'found_here')).concat([line(piece('v2'), 'found_here', null, { viaPhone: true }),
  line(piece('v2'), 'found_here')])
const moved = [line(piece('v3'), 'move_here', BOX07), line(piece('v4'), 'move_here', S4), line(piece('v4'), 'place_here')]
const notFound = [piece('v2'), piece('v3'), piece('v3')]
const other = [line(piece('v1'), 'other_location', null, { locationName: L('Zamalek Showroom', 'معرض الزمالك') })]
const count = (over: Partial<PlaceCount> = {}): PlaceCount => ({
  id: 'C1', status: 'open', place: S2, locationId: 'loc-main', locationName: L('Main Warehouse', 'المخزن الرئيسي'),
  openedByName: 'Ahmed', createdAt: new Date().toISOString(), finishedAt: null,
  expected: found.length + notFound.length, found: found.length, movedHere: moved.length, notFound: notFound.length,
  foundLines: found, movedLines: moved, notFoundPieces: notFound, otherLines: other, applied: null, ...over,
})

const stSession = { sessionId: 'ST1', status: 'open', scopeType: 'all', locationId: 'loc-main', completeCount: false, openedBy: 'u',
  openedByName: 'Ahmed', openedAt: new Date().toISOString(), finalizedBy: null, finalizedByName: null, finalizedAt: null, note: null,
  shopifySync: null }

const locations = [
  { id: 'loc-main', name: L('Main Warehouse', 'المخزن الرئيسي'), is_fulfillment: true, is_default: true },
  { id: 'loc-show', name: L('Zamalek Showroom', 'معرض الزمالك'), is_fulfillment: false, is_default: false },
]
const variant = (id: string, title: string, sku: string, n: number) =>
  ({ id, title, sku, price: '450.00', onHand: n, committed: null, available: n, shopifySync: 'synced' })
const stock = {
  items: [
    { id: 'pr1', title: names.v1[0], imageUrl: null, status: 'active', onHand: 9, committed: null, available: 9,
      variants: [variant('v1', 'M', 'TS-BLK-M', 6), variant('v1l', 'L', 'TS-BLK-L', 3)] },
    { id: 'pr2', title: names.v2[0], imageUrl: null, status: 'active', onHand: 2, committed: null, available: 2,
      variants: [variant('v2', 'L', 'HD-WHT-L', 2)] },
  ],
  nextCursor: null,
}
const node = (id: string, kind: PlaceKind, code: string, label: string, depth: number) => ({ id, kind, code, label, depth,
  name: null, purpose: null, parentId: null, scanCode: 'Q1', path: [], pieces: 0, totalPieces: 0, archived: false, createdAt: '' })
const tree = { locationId: 'loc-main', locationName: locations[0].name, nextZoneCode: 'B', noPlaceYet: 4,
  nodes: [node('za', 'zone', 'A', 'A', 0), node('r1', 'rack', 'A-R01', 'R01', 1), node('s1', 'shelf', 'A-R01-S1', 'S1', 2),
    node('s2', 'shelf', 'A-R01-S2', 'S2', 2), node('b12', 'box', 'BOX-12', 'BOX-12', 3), node('r2', 'rack', 'A-R02', 'R02', 1),
    node('s4', 'shelf', 'A-R02-S4', 'S4', 2), node('b07', 'box', 'BOX-07', 'BOX-07', 1)] }
const counts = (a: number, h = 0, d = 0) => ({ available: a, onHold: h, damaged: d, total: a + h + d })
const where = { variantId: 'v1', productTitle: names.v1[0], variantTitle: 'M', sku: 'TS-BLK-M', locations: [
  { locationId: 'loc-main', locationName: locations[0].name, main: true, total: 11, noPlace: counts(2),
    places: [
      { binId: 's2', code: 'A-R01-S2', kind: 'shelf', path: ['A', 'R01', 'S2'], zoneCode: 'A', parentCode: 'A-R01', parentKind: 'rack', sortKey: '1', counts: counts(6) },
      { binId: 'b12', code: 'BOX-12', kind: 'box', path: ['A', 'R01', 'S2', 'BOX-12'], zoneCode: 'A', parentCode: 'A-R01-S2', parentKind: 'shelf', sortKey: '2', counts: counts(1, 1) },
      { binId: 'b07', code: 'BOX-07', kind: 'box', path: ['A', 'BOX-07'], zoneCode: 'A', parentCode: 'A', parentKind: 'zone', sortKey: '3', counts: counts(0, 0, 1) },
    ] },
  { locationId: 'loc-show', locationName: locations[1].name, main: false, total: 2, noPlace: counts(0),
    places: [{ binId: 'x', code: 'S-R01-S1', kind: 'shelf', path: ['S', 'R01', 'S1'], zoneCode: 'S', parentCode: 'S-R01', parentKind: 'rack', sortKey: '1', counts: counts(2) }] },
] }

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  const body = init?.body ? JSON.parse(String(init.body)) : {}
  if (url.endsWith('/me')) return json({ name: L('Ahmed Ali', 'أحمد علي'), email: 'ahmed@example.com', role, places: true, flexibleLabeling: true })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/put-away/summary')) return json({ waiting: 0, locations: [] })
  // one-place count
  if (url.endsWith('/place-counts/C1') && method === 'GET') {
    return json(view === 'countdone'
      ? count({ status: 'finished', finishedAt: new Date().toISOString(), notFoundPieces: [],
          applied: { placed: 1, moved: 2, cleared: 3, alreadyHere: 0, reason: null,
            skipped: [{ pieceId: 'px', code: 'P103870', reason: 'moved_since' }] } })
      : count())
  }
  if (url.endsWith('/place-counts/C1/scan')) {
    const code = String(body.code)
    if (code.startsWith('Q')) return json({ outcome: 'COUNT_SEPARATELY', code, piece: null, place: BOX12, fromPlace: null, locationName: null, status: null, viaPhone: false, count: count() })
    const outcome = code === 'P103890' ? 'MOVE_HERE' : 'FOUND_HERE'
    return json({ outcome, code, piece: { ...piece(outcome === 'MOVE_HERE' ? 'v3' : 'v1'), code }, place: null,
      fromPlace: outcome === 'MOVE_HERE' ? BOX07 : null, locationName: null, status: 'available', viaPhone: false, count: count() })
  }
  // stock take
  if (url.endsWith('/stock-takes/sessions/ST1')) return json(stSession)
  if (url.endsWith('/stock-takes/sessions/ST1/scan')) {
    const code = String(body.barcode)
    if (code.startsWith('Q')) return json({ sessionId: 'ST1', barcode: code, pieceId: null, classification: 'place_set', place: S2 })
    return json({ sessionId: 'ST1', barcode: code, pieceId: `p-${code}`, classification: 'match', alreadyScanned: false,
      scanDevice: 'hardware', placed: { place: S2, outcome: code.endsWith('2') ? 'MOVED' : 'PLACED' } })
  }
  // inventory
  if (url.endsWith('/locations')) return json(locations)
  if (url.includes('/inventory/stock')) return json(stock)
  if (url.includes('/places?locationId=')) return json(tree)
  if (url.includes('/places/where/')) return json(where)
  // move a box
  if (url.endsWith('/box-moves/box')) return json({ box: BOX12, locationId: 'loc-main', locationName: locations[0].name, pieces: 11 })
  if (url.endsWith('/box-moves')) {
    return json({ outcome: 'MOVED', box: { ...BOX12, trail: ['A', 'R02', 'S4', 'BOX-12'] }, from: S2, to: S4 })
  }
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

const PATHS: Record<string, string> = {
  count: '/place-counts/C1?from=settings', countdone: '/place-counts/C1?from=settings',
  stocktake: '/stock-take/ST1/scan', inventory: '/inventory', movebox: '/put-away/move-box',
}
history.replaceState(null, '', PATHS[view] ?? '/')
createRoot(document.getElementById('root')!).render(<App />)
