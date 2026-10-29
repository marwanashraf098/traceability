// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App with places switched on, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=list|scan|lookup
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
import type { PlaceRef, PutAwayRow, PutAwayScanResult, PutAwaySession } from '../../src/api'

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
const shelf = (code: string, trail: string[]): PlaceRef => ({ id: code, kind: 'shelf', code, trail, parentCode: code.split('-').slice(0, 2).join('-'), parentKind: 'rack' })
const box = (code: string, parent: string, parentKind: 'zone' | 'shelf'): PlaceRef => ({ id: code, kind: 'box', code, trail: [code], parentCode: parent, parentKind })
const S2 = shelf('Z1-R01-S2', ['Z1', 'R01', 'S2'])
const S3 = shelf('Z1-R01-S3', ['Z1', 'R01', 'S3'])
const B21 = shelf('Z2-R02-S1', ['Z2', 'R02', 'S1'])
const A24 = shelf('Z1-R02-S4', ['Z1', 'R02', 'S4'])
const BOX07 = box('BOX-07', 'Z1', 'zone')
const BOX03 = box('BOX-03', 'Z1-R01-S2', 'shelf')
const ago = (h: number) => new Date(Date.now() - h * 3600_000).toISOString()

const tee = L('Black T-shirt', 'تيشيرت أسود'), socks = L('Socks', 'جوارب'), hoodie = L('White Hoodie', 'هودي أبيض')
const rows: PutAwayRow[] = [
  { pieceId: 'p1', code: 'P104233', variantId: 'v1', productTitle: tee, variantTitle: 'M', sku: null, status: 'available', source: 'receiving', sourceRef: null, waitingSince: ago(2), suggestion: { place: S2, reason: 'most_of_variant' } },
  { pieceId: 'p2', code: 'P104234', variantId: 'v1', productTitle: tee, variantTitle: 'M', sku: null, status: 'available', source: 'receiving', sourceRef: null, waitingSince: ago(2), suggestion: { place: S2, reason: 'most_of_variant' } },
  { pieceId: 'p3', code: 'P104240', variantId: 'v2', productTitle: socks, variantTitle: L('Pink', 'وردي'), sku: null, status: 'available', source: 'receiving', sourceRef: null, waitingSince: ago(2), suggestion: { place: BOX07, reason: 'most_of_variant' } },
  { pieceId: 'p4', code: 'P103918', variantId: 'v3', productTitle: hoodie, variantTitle: 'L', sku: null, status: 'available', source: 'returns', sourceRef: '#16443', waitingSince: ago(5), suggestion: { place: S3, reason: 'last_place' } },
  { pieceId: 'p5', code: 'P103502', variantId: 'v4', productTitle: L('Jeans', 'جينز'), variantTitle: '32', sku: null, status: 'damaged', source: 'returns', sourceRef: '#16391', waitingSince: ago(26), suggestion: { place: B21, reason: 'last_place' } },
  { pieceId: 'p6', code: 'P102877', variantId: 'v5', productTitle: L('Cap', 'كاب'), variantTitle: L('Red', 'أحمر'), sku: null, status: 'available', source: 'transfers', sourceRef: null, waitingSince: ago(30), suggestion: { place: A24, reason: 'last_place' } },
  { pieceId: 'p7', code: 'P101200', variantId: 'v6', productTitle: L('Linen Shirt', 'قميص كتان'), variantTitle: 'S', sku: null, status: 'available', source: 'stock_take', sourceRef: null, waitingSince: ago(74), suggestion: null },
]
const location_ = L('Main warehouse', 'المخزن الرئيسي')
let placed = 17, here = 11, onShelf = 35, waiting = 28
const session = (current: PlaceRef | null): PutAwaySession => ({ id: 'S1', locationId: 'loc-main', locationName: location_, status: 'open',
  current: current ? { place: current, putHereNow: here, onThisPlace: onShelf } : null, placed, placesUsed: 3, stillWaiting: waiting,
  createdAt: ago(1), closedAt: null })
let current: PlaceRef | null = BOX07
const piece = (code: string, product: string, variant: string) => ({ id: code, code, productTitle: product, variantTitle: variant })
function scan(code: string): PutAwayScanResult {
  const base = { code, piece: null, place: null, fromPlace: null, locationName: null, status: null, viaPhone: false }
  if (code === 'Q000107') { const from = current; current = S2; here = 0; onShelf = 24; return { ...base, outcome: 'PLACE_SET', place: S2, fromPlace: from, session: session(current) } }
  if (code === 'Q999999') return { ...base, outcome: 'UNKNOWN_STICKER', session: session(current) }
  placed++; here++; onShelf++; waiting--
  if (code === 'P103918') return { ...base, outcome: 'MOVED', piece: piece(code, hoodie, 'L'), fromPlace: BOX03, session: session(current) }
  if (code === 'P104240') return { ...base, outcome: 'PLACED', piece: piece(code, socks, L('Pink', 'وردي')), session: session(current) }
  return { ...base, outcome: 'PLACED', piece: piece(code, tee, 'M'), session: session(current) }
}

const lookupPiece = {
  type: 'piece', id: '01PIECE', barcode: 'PC-01J9ZK2Y7Q4W1N3B5C8D0E6F2G', shortCode: 'P104233', status: 'available', canReprint: true,
  condition: 'good', receivedAt: ago(30), variant: { id: 'v1', title: 'M', sku: 'TS-BLK-M', productTitle: tee },
  currentLocation: { id: 'loc-main', name: location_ }, currentOrder: null, currentShipment: null, receivingSession: null,
  place: { ...box('BOX-12', 'Z1-R01-S2', 'shelf'), trail: ['Z1', 'R01', 'S2', 'BOX-12'] }, canPlace: true,
  timeline: [
    { id: 4, eventType: 'box_moved', phraseKey: 'box_moved', box: 'BOX-12', fromPlace: 'Z1', toPlace: 'Z1-R01-S2', actor: L('Mona', 'منى'), isSystem: false, fromStatus: null, toStatus: null, orderNumber: null, orderId: null, trackingNumber: null, locationName: null, metadata: null, occurredAt: ago(1) },
    { id: 3, eventType: 'place_moved', phraseKey: 'place_moved', fromPlace: 'Z1-R01-S3', toPlace: 'BOX-12', actor: L('Ahmed', 'أحمد'), isSystem: false, fromStatus: 'available', toStatus: 'available', orderNumber: null, orderId: null, trackingNumber: null, locationName: location_, metadata: null, occurredAt: ago(5) },
    { id: 2, eventType: 'put_away', phraseKey: 'put_away', fromPlace: null, toPlace: 'Z1-R01-S3', actor: L('Ahmed', 'أحمد'), isSystem: false, fromStatus: 'available', toStatus: 'available', orderNumber: null, orderId: null, trackingNumber: null, locationName: location_, metadata: null, occurredAt: ago(28) },
    { id: 1, eventType: 'received', phraseKey: 'received_at', actor: L('Ahmed', 'أحمد'), isSystem: false, fromStatus: null, toStatus: 'available', orderNumber: null, orderId: null, trackingNumber: null, locationName: location_, metadata: null, occurredAt: ago(30) },
  ],
}

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.endsWith('/me')) return json({ name: L('Ahmed Ali', 'أحمد علي'), email: 'ahmed@example.com', role, places: true })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/put-away/summary')) return json({ waiting: 45, locations: [{ id: 'loc-main', name: location_, main: true, waiting: 45 }] })
  if (url.includes('/put-away/waiting')) return json({ locationId: 'loc-main', locationName: location_, hasPlaces: true, total: 45,
    counts: { receiving: 32, returns: 9, transfers: 3, stock_take: 1, other: 0 }, source: 'all', rows, truncated: false,
    mine: { id: 'S1', placed: 17, createdAt: ago(1), lastActivityAt: ago(0.3) } })
  if (url.endsWith('/put-away/sessions') && (init?.method ?? 'GET') === 'GET') return json([
    { id: 'S7', locationId: 'loc-main', locationName: location_, openedBy: 'u7', openedByName: L('Mona', 'منى'),
      createdAt: ago(9), lastActivityAt: ago(6), placed: 23 },
    { id: 'S1', locationId: 'loc-main', locationName: location_, openedBy: 'u1', openedByName: L('Ahmed Ali', 'أحمد علي'),
      createdAt: ago(1), lastActivityAt: ago(0.3), placed: 17 }])
  if (url.endsWith('/put-away/sessions/S1/scan')) return json(scan(JSON.parse(String(init?.body ?? '{}')).code))
  if (url.endsWith('/put-away/sessions/S1')) return json(session(current))
  if (url.includes('/put-away/places')) return json([S2, S3, A24, BOX07])
  if (url.includes('/lookup?q=')) return json(lookupPiece)
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', view === 'scan' ? '/put-away/S1/scan' : view === 'lookup' ? '/lookup?q=P104233' : '/put-away')
createRoot(document.getElementById('root')!).render(<App />)
