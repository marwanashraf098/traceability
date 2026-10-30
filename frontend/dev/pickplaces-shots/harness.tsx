// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App with places switched on, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=gather|queue|waybill|lists
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
import type { PickPlace } from '../../src/api'

const q = new URLSearchParams(location.search)
const lang = q.get('lang') === 'ar' ? 'ar' : 'en'
const view = q.get('view') ?? 'gather'
const ar = lang === 'ar'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = ar ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const role = view === 'waybill' ? 'worker' : 'owner'
const payload = btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const L = (en: string, a: string) => (ar ? a : en)

/** The server's walking-order key (PlaceSort). */
const nat = (code: string) => code.toUpperCase().replace(/\d+/g, d => d.replace(/^0+(?=.)/, '').padStart(10, '0'))
function place(code: string, count: number, chain: string[], kind: PickPlace['kind'] = 'shelf'): PickPlace {
  const levels = [...chain, code]
  const parentCode = chain[chain.length - 1] ?? null
  const parentKind: PickPlace['kind'] | null = chain.length === 1 ? 'zone' : chain.length === 2 ? 'rack' : chain.length === 3 ? 'shelf' : null
  const sortKey = levels.map((c, i) => (i === levels.length - 1 && kind === 'box' && parentKind === 'zone' ? '~' : '') + nat(c)).join(' ')
  return { binId: `b-${code}`, code, kind, path: levels, zoneCode: levels[0], parentCode, parentKind, sortKey, count }
}
const S2 = (n: number) => place('Z1-R01-S2', n, ['Z1', 'Z1-R01'])
const S3 = (n: number) => place('Z1-R01-S3', n, ['Z1', 'Z1-R01'])
const R2S4 = (n: number) => place('Z1-R02-S4', n, ['Z1', 'Z1-R02'])
const BOX12 = (n: number) => place('BOX-12', n, ['Z1', 'Z1-R01', 'Z1-R01-S2'], 'box')
const BOX07 = (n: number) => place('BOX-07', n, ['Z1'], 'box')
const Z2S1 = (n: number) => place('Z2-R02-S1', n, ['Z2', 'Z2-R02'])

const tee = L('Black T-shirt', 'تيشيرت أسود'), socks = L('Socks', 'جوارب'), hoodie = L('White Hoodie', 'هودي أبيض')
const jeans = L('Jeans', 'جينز'), cap = L('Cap', 'كاب'), shirt = L('Linen Shirt', 'قميص كتان')

const gather = {
  generatedAt: new Date().toISOString(), orderCount: 9,
  rows: [
    { variantId: 'v1', name: 'M', sku: 'TS-BLK-M', displayName: `${tee} - M`, needed: 6, availableCount: 14, shortage: false, orderNumbers: ['#16501', '#16502', '#16507'] },
    { variantId: 'v2', name: L('Pink', 'وردي'), sku: 'SK-PNK', displayName: `${socks} - ${L('Pink', 'وردي')}`, needed: 4, availableCount: 9, shortage: false, orderNumbers: ['#16503'] },
    { variantId: 'v3', name: 'L', sku: 'HD-WHT-L', displayName: `${hoodie} - L`, needed: 2, availableCount: 3, shortage: false, orderNumbers: ['#16504', '#16505'] },
    { variantId: 'v4', name: '32', sku: 'JN-32', displayName: `${jeans} - 32`, needed: 3, availableCount: 1, shortage: true, orderNumbers: ['#16506'] },
    { variantId: 'v5', name: L('Red', 'أحمر'), sku: 'CP-RED', displayName: `${cap} - ${L('Red', 'أحمر')}`, needed: 1, availableCount: 4, shortage: false, orderNumbers: ['#16508'] },
    { variantId: 'v6', name: 'S', sku: 'LS-S', displayName: `${shirt} - S`, needed: 2, availableCount: 2, shortage: false, orderNumbers: ['#16509'] },
  ],
}
const variantPlaces: Record<string, { places: PickPlace[]; noPlaceCount: number }> = {
  v1: { places: [S2(4), BOX12(3), R2S4(2)], noPlaceCount: 5 },
  v2: { places: [BOX07(9)], noPlaceCount: 0 },
  v3: { places: [S3(1), Z2S1(1)], noPlaceCount: 1 },
  v4: { places: [Z2S1(1)], noPlaceCount: 0 },
  v5: { places: [R2S4(4)], noPlaceCount: 0 },
  v6: { places: [], noPlaceCount: 2 },
}

const line = (id: string, variant: string, product: string, variantTitle: string, sku: string, quantity: number, allocated = 0) => ({
  id, variant_id: variant, sku, variant_title: variantTitle, product_title: product, imageUrl: null, quantity, allocated,
  allocatedPieces: Array.from({ length: allocated }, (_, i) => ({ piece_id: `${id}-p${i}`, barcode: `PC-01J9ZK2Y7Q4W1N3B5C8D0E${i}`, allocation_status: 'active', piece_status: 'reserved' })),
})
const lines = [
  line('item-1', 'v1', tee, 'M', 'TS-BLK-M', 6, 1),
  line('item-2', 'v3', hoodie, 'L', 'HD-WHT-L', 2),
  line('item-3', 'v6', shirt, 'S', 'LS-S', 1),
]
const linePlaces = { orderId: 'order-1', lines: lines.map(l => ({ orderItemId: l.id, variantId: l.variant_id, quantity: l.quantity, ...variantPlaces[l.variant_id] })) }
const pickOrder = {
  id: 'order-1', number: '#16501', customer_name: L('Youssef Adel', 'يوسف عادل'), customer_phone: null, status: 'new',
  payment_method: 'cod', cod_amount: '1250.00', locked_by: null, is_self_pickup: false, cancel_requested_at: null,
  shipment_id: 'sh-1', tracking_number: '74821903', shipment_has_courier: true, awbPrinted: true, items: lines,
}
const queue = [
  { id: 'order-1', number: '#16501', customer_name: L('Youssef Adel', 'يوسف عادل'), status: 'new', payment_method: 'cod', cod_amount: '1250.00', total_units: 9, scanned_units: 1, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false, awb_printed: true },
]
const card = { id: 'order-1', number: '#16501', customer_name: L('Youssef Adel', 'يوسف عادل'), payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: L('Nasr City, Cairo', 'مدينة نصر، القاهرة'), courierType: 'delivery', batchNo: 3,
  batchPrintedAt: new Date(Date.now() - 3600_000).toISOString(), items: lines }
const session = (openOrder: unknown) => ({ id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: new Date(Date.now() - 1800_000).toISOString(),
  workerName: L('Ahmed', 'أحمد'), counters: { packed: 4, setAside: 0, rejected: 0, left: 12 }, recent: [], openOrder })
const notPacked = [
  { orderId: 'o-a', orderNumber: '#16511', customerName: L('Mona', 'منى'), shipmentId: 's-a', trackingNumber: '74821911', batchId: 'b3', batchNo: 3, batchPrintedAt: new Date().toISOString(), status: 'waiting', packerName: null, setAsideReason: null, exceptionType: null, subjectKey: null },
  { orderId: 'o-b', orderNumber: '#16512', customerName: L('Omar', 'عمر'), shipmentId: 's-b', trackingNumber: '74821912', batchId: 'b3', batchNo: 3, batchPrintedAt: new Date().toISOString(), status: 'waiting', packerName: null, setAsideReason: null, exceptionType: null, subjectKey: null },
  { orderId: 'o-c', orderNumber: '#16513', customerName: L('Sara', 'سارة'), shipmentId: 's-c', trackingNumber: '74821913', batchId: 'b3', batchNo: 3, batchPrintedAt: new Date().toISOString(), status: 'set_aside', packerName: null, setAsideReason: 'piece_missing', exceptionType: null, subjectKey: null },
]
const notPackedPlaces = { orders: [
  { orderId: 'o-a', lines: [{ orderItemId: 'a1', variantId: 'v6', quantity: 1, places: [], noPlaceCount: 2 }] },
  { orderId: 'o-b', lines: [{ orderItemId: 'b1', variantId: 'v4', quantity: 1, places: [Z2S1(1)], noPlaceCount: 0 },
                            { orderItemId: 'b2', variantId: 'v5', quantity: 1, places: [R2S4(4)], noPlaceCount: 0 }] },
  { orderId: 'o-c', lines: [{ orderItemId: 'c1', variantId: 'v1', quantity: 2, places: [S2(4)], noPlaceCount: 0 }] },
] }

const mode = view === 'waybill' || view === 'lists' ? 'waybill_scan' : 'order_queue'
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  if (url.endsWith('/me')) return json({ name: L('Ahmed Ali', 'أحمد علي'), email: 'ahmed@example.com', role, places: true })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/put-away/summary')) return json({ waiting: 0, locations: [] })
  if (url.includes('/fulfill/gather')) return json(gather)
  if (url.endsWith('/fulfill/places/variants')) {
    const ids: string[] = JSON.parse(String(init?.body ?? '{}')).variantIds ?? []
    return json({ variants: ids.map(id => ({ variantId: id, ...(variantPlaces[id] ?? { places: [], noPlaceCount: 0 }) })) })
  }
  if (url.endsWith('/fulfill/places/orders')) return json(notPackedPlaces)
  if (url.endsWith('/fulfill/order-1/places')) return json(linePlaces)
  if (url.endsWith('/fulfill/mode')) return json({ mode })
  if (url.endsWith('/fulfill/queue')) return json(queue)
  if (url.includes('awaiting-waybill-count')) return json({ count: 0 })
  if (url.endsWith('/fulfill/order-1') && method === 'GET') return json(pickOrder)
  if (url.endsWith('/fulfill/order-1/lock')) return Promise.resolve(new Response(null, { status: 204 }))
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 31, openSessionId: null })
  if (url.endsWith('/pack-sessions') && method === 'POST') return json(session(null))
  if (url.endsWith('/pack-sessions/sess-1') && method === 'GET') return json(session(null))
  if (url.endsWith('/sess-1/waybill')) return json({ result: 'opened', order: card, code: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json([{ batchId: 'b3', batchNo: 3, printedAt: new Date(Date.now() - 3600_000).toISOString(), printedByName: L('Mona', 'منى'), waybillCount: 16, packed: 4, setAside: 1, cancelled: 0, waiting: 11 }])
  if (url.endsWith('/fulfill/printed-not-packed')) return json(notPacked)
  if (url.includes('/fulfill/print-jobs/latest')) return json(null)
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', view === 'gather' ? '/fulfill/gather' : '/fulfill')
createRoot(document.getElementById('root')!).render(<App />)
