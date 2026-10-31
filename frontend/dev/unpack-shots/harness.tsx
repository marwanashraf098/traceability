// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App, fetch answered from the fixture below:
//   harness.html?lang=en|ar&view=waybill|queue&role=manager|worker
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
  if (url.endsWith('/fulfill/packed')) return json(ROWS)
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

const PATHS: Record<string, string> = { waybill: '/fulfill', queue: '/fulfill?tab=packed' }
history.replaceState(null, '', PATHS[view] ?? '/fulfill')
createRoot(document.getElementById('root')!).render(<App />)
