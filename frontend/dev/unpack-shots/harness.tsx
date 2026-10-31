// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=waybill|queue|refused|lookup|alerts&role=manager|worker
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
import type { PackedOrderRow } from '../../src/api'

const q = new URLSearchParams(location.search)
const lang = q.get('lang') === 'ar' ? 'ar' : 'en'
const view = q.get('view') ?? 'waybill'
const role = q.get('role') === 'worker' ? 'worker' : 'manager'
const ar = lang === 'ar'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = ar ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const payload = btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const L = (en: string, a: string) => (ar ? a : en)
const today = new Date()
const at = (h: number, m: number) => { const d = new Date(today); d.setHours(h, m, 0, 0); return d.toISOString() }
const ROWS: PackedOrderRow[] = [
  { orderId: 'o1', orderNumber: '#1047', customerName: L('Youssef Adel', 'يوسف عادل'), status: 'awaiting_pickup', selfPickup: false,
    trackingNumber: '74821655', courier: 'bosta', carrierName: null, packedAt: at(9, 12), packedByName: L('Ahmed', 'أحمد'),
    pieces: 2, pickupSessionId: null, pickupDate: null, pickupTimeSlot: null,
    editConflict: true, codMismatch: true, bostaCod: 1250, orderCod: 900 },
  { orderId: 'o2', orderNumber: '#1052', customerName: L('Salma Nabil', 'سلمى نبيل'), status: 'awaiting_pickup', selfPickup: false,
    trackingNumber: '74821702', courier: 'bosta', carrierName: null, packedAt: at(9, 40), packedByName: L('Mona', 'منى'),
    pieces: 1, pickupSessionId: 'pu-1', pickupDate: today.toISOString().slice(0, 10), pickupTimeSlot: '12-14',
    editConflict: false, codMismatch: false, bostaCod: null, orderCod: null },
  { orderId: 'o3', orderNumber: '#1055', customerName: L('Omar Fathy', 'عمر فتحي'), status: 'packed', selfPickup: false,
    trackingNumber: 'ARX5521907', courier: 'generic', carrierName: 'Aramex', packedAt: at(10, 5), packedByName: L('Ahmed', 'أحمد'),
    pieces: 3, pickupSessionId: null, pickupDate: null, pickupTimeSlot: null,
    editConflict: false, codMismatch: false, bostaCod: null, orderCod: null },
  { orderId: 'o4', orderNumber: '#1058', customerName: L('Nour Hassan', 'نور حسن'), status: 'self_pickup_pending', selfPickup: true,
    trackingNumber: null, courier: null, carrierName: null, packedAt: at(10, 30), packedByName: L('Mona', 'منى'),
    pieces: 1, pickupSessionId: null, pickupDate: null, pickupTimeSlot: null,
    editConflict: false, codMismatch: false, bostaCod: null, orderCod: null },
]

let unpacked = false
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  ;(window as unknown as { __calls: string[] }).__calls ??= []
  ;(window as unknown as { __calls: string[] }).__calls.push(`${method} ${url}`)
  if (url.endsWith('/me')) return json({ name: L('Mona Samir', 'منى سمير'), email: 'mona@example.com', role })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.endsWith('/fulfill/mode')) return json({ mode: view === 'queue' ? 'order_queue' : 'waybill_scan' })
  if (url.endsWith('/fulfill/queue')) return json([])
  if (url.includes('/fulfill/queue/awaiting-waybill-count')) return json({ count: 0 })
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 14, openSessionId: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json([])
  if (url.endsWith('/fulfill/printed-not-packed')) return json([])
  if (url.endsWith('/fulfill/packed')) return json(unpacked ? ROWS.slice(1) : ROWS)
  if (url.endsWith('/unpack-order') && method === 'POST') {
    if (view === 'refused') {
      return json({ code: 'IN_PICKUP_SESSION',
        message_en: 'Order #1047 is in an open pickup session (2026-10-31). Remove its waybill from that pickup session first, then unpack it.',
        message_ar: 'الطلب #1047 في جلسة استلام مفتوحة (2026-10-31). أزل بوليصته من جلسة الاستلام أولاً، ثم فُكّه.',
        details: { pickupSessionId: 'pu-1', pickupDate: '2026-10-31', pickupTimeSlot: '12-14' } }, 409)
    }
    unpacked = true
    return json({ orderId: 'o1', orderNumber: '#1047', pieces: 2, previousStatus: 'awaiting_pickup', status: 'new', selfPickup: false })
  }
  if (url.includes('/lookup?q=')) {
    const now = Date.now()
    const ev = (id: number, phraseKey: string, eventType: string, from: string | null, to: string, mins: number, extra = {}) => ({
      id, eventType, phraseKey, actor: L('Mona Samir', 'منى سمير'), isSystem: false, fromStatus: from, toStatus: to,
      orderNumber: '#1047', orderId: 'o1', trackingNumber: null, locationName: L('Main Warehouse', 'المخزن الرئيسي'),
      metadata: null, occurredAt: new Date(now - mins * 60000).toISOString(), ...extra })
    return json({ type: 'piece', id: 'p1', barcode: 'PC-1', shortCode: 'P103801', status: 'available',
      receivedAt: new Date(now - 86400000).toISOString(),
      variant: { id: 'v1', productTitle: L('Linen Shirt', 'قميص كتان'), title: 'M', sku: 'LS-OLV-M' },
      currentLocation: { id: 'l', name: L('Main Warehouse', 'المخزن الرئيسي') }, currentOrder: null, currentShipment: null,
      receivingSession: null,
      timeline: [
        ev(4, 'unpacked_order', 'unpacked', 'awaiting_pickup', 'available', 5, { unpackReason: 'customer_edited_order', unpackNote: null }),
        ev(3, 'tracking_linked', 'tracking_linked', 'packed', 'awaiting_pickup', 50),
        ev(2, 'packed_for_order', 'pack', 'reserved', 'packed', 51),
        ev(1, 'reserved_for_order', 'scan', 'available', 'reserved', 52),
      ] })
  }
  if (url.includes('/exceptions?') || url.endsWith('/exceptions')) {
    return json({ total: 1, page: 0, size: 50, counts: { critical: 0, high: 1, medium: 0, low: 0 }, items: [
      { type: 'shopify_edit_conflict', severity: 'HIGH', subject_type: 'order', subject_key: 'shopify_edit_conflict:order:o1',
        order_id: 'o1', order_number: '#1047', occurred_at: new Date(Date.now() - 1800000).toISOString(), ageSeconds: 1800,
        descriptionEn: 'Shopify edited order #1047 (awaiting_pickup) after it entered the fulfillment flow — line items changed',
        descriptionAr: 'تعديل Shopify على الطلب #1047 (awaiting_pickup) بعد دخوله مسار التنفيذ — تغيّرت بنود الطلب',
        suggestedAction: 'review_order', actionUrl: '/orders?order=o1', unpackUrl: '/fulfill?tab=packed&order=o1&unpack=1' }] })
  }
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

const PATHS: Record<string, string> = { waybill: '/fulfill', queue: '/fulfill?tab=packed', refused: '/fulfill',
  lookup: '/lookup', alerts: '/exceptions' }
history.replaceState(null, '', PATHS[view] ?? '/fulfill')
createRoot(document.getElementById('root')!).render(<App />)
