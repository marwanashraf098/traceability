// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App with places switched on, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=receiving|tagstock|returns|reconcile|scanout|list|scan
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
import type { PickPlace, PlaceKind, PutAwayList, PutAwayListPiece, PutAwayListPlace } from '../../src/api'

const q = new URLSearchParams(location.search)
const lang = q.get('lang') === 'ar' ? 'ar' : 'en'
const view = q.get('view') ?? 'list'
const ar = lang === 'ar'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = ar ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const role = view === 'scan' ? 'worker' : 'owner'
const payload = btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const L = (en: string, a: string) => (ar ? a : en)

/** The server's walking-order key (PlaceSort). */
const nat = (code: string) => code.toUpperCase().replace(/\d+/g, d => d.replace(/^0+(?=.)/, '').padStart(10, '0'))
function place(code: string, kind: PlaceKind, parents: [string, PlaceKind][]): PutAwayListPlace {
  const levels: [string, PlaceKind][] = [...parents, [code, kind]]
  const sortKey = levels.map(([c, k], i) => (i > 0 && k === 'box' && levels[i - 1][1] === 'zone' ? '~' : '') + nat(c)).join(' ')
  const parent = parents[parents.length - 1] ?? null
  return { id: `b-${code}`, kind, code, trail: levels.map(([c]) => c), parentCode: parent?.[0] ?? null,
    parentKind: parent?.[1] ?? null, zoneCode: levels[0][0], sortKey }
}
const Z1S2 = place('Z1-R01-S2', 'shelf', [['Z1', 'zone'], ['Z1-R01', 'rack']])
const Z1S3 = place('Z1-R01-S3', 'shelf', [['Z1', 'zone'], ['Z1-R01', 'rack']])
const BOX07 = place('BOX-07', 'box', [['Z1', 'zone']])
const Z2S1 = place('Z2-R02-S1', 'shelf', [['Z2', 'zone'], ['Z2-R02', 'rack']])
const pick = (p: PutAwayListPlace, count: number): PickPlace => ({ binId: p.id, code: p.code, kind: p.kind, path: p.trail,
  zoneCode: p.zoneCode, parentCode: p.parentCode, parentKind: p.parentKind, sortKey: p.sortKey, count })

const names: Record<string, [string, string]> = {
  v1: [L('Black T-shirt', 'تيشيرت أسود'), 'M'], v2: [L('White Hoodie', 'هودي أبيض'), 'L'],
  v3: [L('Linen Shirt', 'قميص كتان'), 'S'], v4: [L('Socks', 'جوارب'), L('Pink', 'وردي')], v5: [L('Cap', 'كاب'), L('Red', 'أحمر')],
}
let k = 0
function piece(variant: string, over: Partial<PutAwayListPiece> = {}): PutAwayListPiece {
  const code = `P${String(4100 + ++k).padStart(7, '0')}`
  return { pieceId: `p-${code}`, code, variantId: variant, productTitle: names[variant][0], variantTitle: names[variant][1],
    sku: `SKU-${variant.toUpperCase()}`, status: 'available', done: false, place: null, placeReason: null, ...over }
}
const PIECES: PutAwayListPiece[] = [
  piece('v1', { place: Z1S2, placeReason: 'last_place' }), piece('v1', { place: Z1S2, placeReason: 'last_place' }),
  piece('v1', { place: Z1S2, placeReason: 'placed', done: true }),
  piece('v2', { place: Z1S3, placeReason: 'most_of_variant' }),
  piece('v5', { place: BOX07, placeReason: 'most_of_variant' }),
  piece('v3', { place: Z2S1, placeReason: 'placed', done: true }), piece('v3', { place: Z2S1, placeReason: 'last_place' }),
  piece('v4'), piece('v4'),
]
const SESSION = { id: 'S1', status: 'open' as const, placedInSession: 3, extra: 1, lastActivityAt: new Date().toISOString() }
function list(source: PutAwayList['source'], sourceId: string, sourceRef: string | null, withSession = false): PutAwayList {
  const pieces = view === 'scan'
    ? PIECES.map((p, i) => (i < 2 ? { ...p, done: true, placeReason: 'placed' as const } : p))
    : PIECES
  return { source, sourceId, sourceRef, locationId: 'loc-main', locationName: L('Main Warehouse', 'المخزن الرئيسي'),
    hasPlaces: true, total: pieces.length, placed: pieces.filter(p => p.done).length, pieces, session: withSession ? SESSION : null }
}

const RS = 'a7c3e1f0-0000-0000-0000-000000000001'
const transfer = (status: 'preparing' | 'reconciling') => ({
  id: 'T1', transfer_type: 'showroom', transfer_mode: 'round_trip', status, note: null, expected_return_at: null,
  created_by: 'u', created_at: '2026-10-30T08:00:00Z', closed_by: null, closed_at: null, sent_at: null, sent_by: null,
  cancelled_at: null, cancelled_by: null, reconcile_started_at: null, reconcile_started_by: null,
  destination_location_id: 'loc-show', destination_location_name: L('Zamalek Showroom', 'معرض الزمالك'), source_location_id: null,
  source_location_name: null, outstandingCount: 0, piecesEverCount: 9,
  lines: [
    ['tl1', 'v1', 3], ['tl2', 'v2', 2], ['tl3', 'v5', 1], ['tl4', 'v4', 2], ['tl5', 'v3', 1],
  ].map(([id, v, n]) => ({ id, variant_id: v, sku: `SKU-${String(v).toUpperCase()}`, variant_title: names[v as string][1],
    product_title: names[v as string][0], qty_out: n, qty_returned_good: status === 'reconciling' ? n : 0,
    qty_condemned: 0, qty_sold: 0, qty_lost: 0 })),
})
const collect: Record<string, { places: PickPlace[]; noPlaceCount: number }> = {
  v1: { places: [pick(Z1S2, 6), pick(Z2S1, 2)], noPlaceCount: 3 },
  v2: { places: [pick(Z1S3, 4)], noPlaceCount: 0 },
  v3: { places: [pick(Z2S1, 2)], noPlaceCount: 1 },
  v4: { places: [], noPlaceCount: 5 },
  v5: { places: [pick(BOX07, 7)], noPlaceCount: 0 },
}
const putAwaySession = { id: 'S1', locationId: 'loc-main', locationName: L('Main Warehouse', 'المخزن الرئيسي'), status: 'open',
  current: { place: Z1S2, putHereNow: 2, onThisPlace: 6 }, placed: 3, placesUsed: 2, stillWaiting: 14,
  createdAt: new Date(Date.now() - 600_000).toISOString(), closedAt: null, closeReason: null }

const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  if (url.endsWith('/me')) return json({ name: L('Ahmed Ali', 'أحمد علي'), email: 'ahmed@example.com', role, places: true, flexibleLabeling: true })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/put-away/summary')) return json({ waiting: 0, locations: [] })
  // receiving
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/R1')) {
    return json({ id: 'R1', status: 'finalized', mode: 'print_stick', reference: 'PO-118', supplier_name: L('Cotton House', 'كوتون هاوس'),
      location_name: L('Main Warehouse', 'المخزن الرئيسي'), created_at: '2026-10-30T08:00:00Z', finalized_at: '2026-10-30T09:00:00Z',
      line_units: 9, piece_count: 9,
      lines: ['v1', 'v2', 'v3', 'v4', 'v5'].map((v, i) => ({ id: `l${i}`, variant_id: v, quantity: [3, 1, 2, 2, 1][i],
        product_title: names[v][0], variant_title: names[v][1], sku: `SKU-${v.toUpperCase()}` })) })
  }
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: L('Main Warehouse', 'المخزن الرئيسي'), is_fulfillment: true }])
  if (url.endsWith('/label-settings')) return json({ receivingDefaultMode: 'print_stick' })
  // tag stock
  if (url.endsWith('/tag-stock/sessions/TS1')) {
    return json({ id: 'TS1', startedAt: '2026-10-30T08:00:00Z', endedAt: '2026-10-30T09:00:00Z', tagged: 9, variants: [] })
  }
  // returns
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) {
    return json({ items: [{ id: RS, status: 'closed', opened_by: 'user-1', opened_at: '2026-10-30T10:00:00Z',
      closed_at: '2026-10-30T10:30:00Z', piece_count: 5, restocked_count: 4, damaged_count: 1, mismatch_count: 0 }], total: 1 })
  }
  if (url.endsWith('/returns/analytics')) {
    return json({ totalReturns: 5, restockedCount: 4, damagedCount: 1, mismatchCount: 0, expectedNotScannedCount: 0,
      unassignedPendingCount: 0, unassignedPending: [] })
  }
  if (url.endsWith(`/returns/sessions/${RS}`)) {
    return json({ id: RS, status: 'closed', opened_by: 'user-1', opened_at: '2026-10-30T10:00:00Z', closed_by: null,
      closed_at: '2026-10-30T10:30:00Z', note: null, expectedPieces: [],
      items: ['restocked', 'restocked', 'restocked', 'restocked', 'damaged'].map((d, i) => ({ id: `item-${i}`, piece_id: `piece-${i}`,
        barcode: `P000${4200 + i}`, status: d === 'damaged' ? 'damaged' : 'available', variant_title: 'M', product_title: names.v1[0],
        sku: 'SKU-V1', disposition: d, unexpected: false, damage_reason: d === 'damaged' ? 'torn' : null })) })
  }
  // transfers
  if (url.endsWith('/transfers/T1') && method === 'GET') return json(transfer(view === 'reconcile' ? 'reconciling' : 'preparing'))
  if (url.endsWith('/transfers/T1/close') && method === 'POST') return json({})
  if (url.endsWith('/fulfill/places/variants')) {
    const ids: string[] = JSON.parse(String(init?.body ?? '{}')).variantIds ?? []
    return json({ variants: ids.map(id => ({ variantId: id, ...(collect[id] ?? { places: [], noPlaceCount: 0 }) })) })
  }
  // put-away lists
  if (url.includes('/put-away/lists/receiving/R1')) return json(list('receiving', 'R1', 'PO-118', view === 'list'))
  if (url.includes('/put-away/lists/tag_session/TS1')) return json(list('tag_session', 'TS1', null))
  if (url.includes(`/put-away/lists/return_session/${RS}`)) return json(list('return_session', RS, null))
  if (url.includes('/put-away/lists/transfer/T1')) return json(list('transfer', 'T1', L('Zamalek Showroom', 'معرض الزمالك')))
  if (url.endsWith('/put-away/sessions/S1') && method === 'GET') return json(putAwaySession)
  if (url.endsWith('/put-away/sessions/S1/list')) return json({ scoped: true, list: { ...list('receiving', 'R1', 'PO-118'), session: SESSION } })
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

const PATHS: Record<string, string> = {
  receiving: '/receiving?session=R1', tagstock: '/tag-stock/TS1/summary', returns: '/returns',
  reconcile: '/transfers/T1/reconcile', scanout: '/transfers/T1/scan-out', list: '/put-away/list/receiving/R1',
  scan: '/put-away/S1/scan?list=1',
}
history.replaceState(null, '', PATHS[view] ?? '/')
createRoot(document.getElementById('root')!).render(<App />)
