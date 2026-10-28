// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
// Renders the REAL App as an owner of a store that ships with its own courier, fetch answered from the fixture below:
//   harness.html?lang=en|ar&page=lookup|drawer[&piece=available|untagged|reserved][&refusal=CODE][&drawer=reserved|left]
// The refusal texts are the server's exact copy (LookupExchangeException / GenericReturnException).
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

const params = new URLSearchParams(location.search)
const lang = params.get('lang') === 'ar' ? 'ar' : 'en'
const page = params.get('page') ?? 'lookup'
const pieceKind = params.get('piece') ?? 'available'
const refusal = params.get('refusal')
const drawerState = params.get('drawer') ?? 'reserved'
localStorage.setItem('lang', lang)
await i18n.changeLanguage(lang)
document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const payload = btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const ar = lang === 'ar'
const SHIRT = ar ? 'قميص كتان' : 'Linen Shirt'
const PANTS = ar ? 'بنطلون قطن' : 'Cotton Pants'
const CUSTOMER = ar ? 'منى عادل' : 'Mona Adel'

const REFUSALS: Record<string, { en: string; ar: string }> = {
  NOT_AT_MAIN_WAREHOUSE: {
    en: 'Move this piece to your main warehouse first (Transfers), then start the exchange.',
    ar: 'انقل هذه القطعة إلى مخزنك الرئيسي أولاً (التحويلات)، ثم ابدأ الاستبدال.' },
  PIECE_UNTAGGED: {
    en: 'This piece has no label yet. Tag it in Tag stock first, then start the exchange.',
    ar: 'هذه القطعة ليس لها ملصق بعد. ضع لها ملصقًا من تمييز المخزون أولاً، ثم ابدأ الاستبدال.' },
  PIECE_NOT_AVAILABLE: {
    en: 'Only an available piece can be sent as an exchange.', ar: 'يمكن إرسال قطعة متاحة فقط كاستبدال.' },
  LOOKUP_EXCHANGE_UNAVAILABLE: {
    en: 'Exchanges from Lookup are for stores that ship with their own courier (Settings → Couriers) and have no Bosta account connected.',
    ar: 'الاستبدال من البحث متاح للمتاجر التي تشحن مع شركة شحن خاصة بها (الإعدادات ← شركات الشحن) وليس لديها حساب بوسطة متصل.' },
  ORDER_NOT_FOUND: {
    en: "That order can't be exchanged. Search for the customer's original order.",
    ar: 'لا يمكن استبدال هذا الطلب. ابحث عن الطلب الأصلي للعميل.' },
  RETURNING_NOT_ELIGIBLE: {
    en: "That item can't come back on this order — it isn't delivered, or it's already in another return.",
    ar: 'لا يمكن إرجاع هذا المنتج على هذا الطلب — لم يتم توصيله، أو هو بالفعل ضمن إرجاع آخر.' },
  SAME_AS_OUTBOUND: {
    en: "The return waybill must be different from the replacement's outbound waybill. Enter the return's own number, or leave it empty — the old item can still be scanned in by its piece.",
    ar: 'يجب أن تختلف بوليصة الإرجاع عن بوليصة إرسال البديل. أدخل رقم الإرجاع الخاص، أو اتركه فارغًا — يمكن استلام المنتج القديم بمسح قطعته.' },
  TRACKING_TAKEN: {
    en: 'Another shipment in Traced already has that tracking number.', ar: 'يوجد شحنة أخرى في Traced بنفس رقم التتبع.' },
}

const ULID = '01JC8ZQ4M7T2V9X3K5N6P8R0SW'
const piece = {
  type: 'piece', id: ULID, barcode: 'PC-' + ULID, shortCode: pieceKind === 'untagged' ? null : 'P1048327',
  status: pieceKind === 'reserved' ? 'reserved' : 'available', canReprint: pieceKind !== 'untagged', condition: 'good',
  receivedAt: '2026-10-01T09:00:00Z',
  variant: { id: 'v-m', productTitle: SHIRT, title: ar ? 'أبيض / M' : 'White / M', sku: 'LIN-WHT-M' },
  currentLocation: { id: 'loc1', name: ar ? 'المخزن الرئيسي' : 'Main Warehouse' },
  currentOrder: pieceKind === 'reserved' ? { id: 'rep-1', number: 'EXC-RR-7K3F9M', status: 'confirmed', customerName: CUSTOMER, customerPhone: '01012345678' } : null,
  currentShipment: null, receivingSession: null,
  timeline: [{ eventType: 'received', phraseKey: 'received', actor: 'Ahmed', isSystem: false, fromStatus: null, toStatus: 'available',
    orderNumber: null, orderId: null, trackingNumber: null, locationName: ar ? 'المخزن الرئيسي' : 'Main Warehouse', metadata: null,
    occurredAt: '2026-10-01T09:00:00Z' }],
  ...(pieceKind === 'available' ? { canExchange: true } : {}),
  ...(pieceKind === 'reserved' ? { lookupExchange: { requestId: 'rr-1', reference: 'RR-7K3F9M' } } : {}),
}

const left = drawerState === 'left'
const detail = {
  id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#16443', customerName: CUSTOMER,
  customerPhone: '01012345678', type: 'exchange', status: left ? 'pickup_booked' : 'approved', email: null, note: null,
  createdAt: '2026-10-28T13:02:00Z', deliveredAt: '2026-10-21T10:00:00.000+00:00',
  pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
  pickupDistrictName: null, pickupDistrictNameAr: null,
  bookingStatus: 'manual', bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
  decidedAt: '2026-10-28T13:02:00.000+00:00', decidedBy: null, decidedByName: 'Marawan', rejectionReason: null, returnShipmentId: null,
  items: [{ id: 'item-1', pieceId: 'p-old', shortCode: 'P1047710', variantId: 'v-l', productTitle: SHIRT,
    variantTitle: ar ? 'أبيض / L' : 'White / L', imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting',
    replacementVariantId: 'v-m', replacementVariantTitle: ar ? 'أبيض / M' : 'White / M' }],
  otherCourier: {
    forwardCarrierName: 'QP Express', carrierName: 'QP Express',
    pickup: { name: CUSTOMER, phone: '01012345678', addressLines: [ar ? '٥ شارع التحرير' : '5 Tahrir St', ar ? 'القاهرة' : 'Cairo'] },
    replacement: { orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M',
      outboundTrackingNumber: left ? '7712 0048 3391' : null, outboundState: left ? 'with_courier' : null,
      lookupExchange: { pieceId: ULID, pieceShortCode: 'P1048327', pieceStatus: left ? 'with_courier' : 'reserved',
        left, cancelled: false, canCancel: !left } },
  },
  exchange: { trackingNumber: left ? '7712 0048 3391' : null, exchangeStatus: null, orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M',
    orderStatus: left ? 'with_courier' : 'confirmed', shipmentState: left ? 'with_courier' : null, deliveredAt: null,
    withCourierAt: left ? '2026-10-28T13:40:00Z' : null },
  events: [],
}

const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  if (url.endsWith('/me')) return json({ name: 'Marawan', email: 'owner@example.com', role: 'owner' })
  if (url.includes('/exceptions/count')) return json({ count: 0 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/lookup-exchange/orders?q=')) return json([
    { id: 'o-1', number: '#16443', customerName: CUSTOMER, deliveredAt: '2026-10-21T10:00:00Z', carrierName: 'QP Express', itemCount: 2 },
    { id: 'o-2', number: '#16102', customerName: CUSTOMER, deliveredAt: '2026-10-02T10:00:00Z', carrierName: 'QP Express', itemCount: 1 },
  ])
  if (url.includes('/returnables')) return json([
    { kind: 'piece', pieceId: 'p-old', shortCode: 'P1047710', variantId: 'v-l', productTitle: SHIRT, variantTitle: ar ? 'أبيض / L' : 'White / L' },
    { kind: 'unit', orderItemId: 'oi-2', unitNo: 1, variantId: 'v-p', productTitle: PANTS, variantTitle: ar ? 'بيج / 32' : 'Beige / 32' },
  ])
  if (url.endsWith('/exchange') && method === 'POST') {
    if (refusal && REFUSALS[refusal]) {
      return json({ code: refusal, message_en: REFUSALS[refusal].en, message_ar: REFUSALS[refusal].ar }, 409)
    }
    return json({ requestId: 'rr-1', reference: 'RR-7K3F9M', replacementOrderNumber: 'EXC-RR-7K3F9M', dispatched: false }, 201)
  }
  if (url.includes('/lookup?q=')) return json(piece)
  if (url.includes('/generic-shipments/carriers')) return json([{ carrier: 'QP Express', withCourier: 1 }])
  if (url.includes('/return-requests/rr-1') && method === 'GET') return json(detail)
  if (url.includes('/return-requests?')) return json({ items: [], total: 0 })
  if (url.includes('/tenant/portal-settings')) return json({ slug: 'x', enabled: true, autoApprove: false, returnWindowDays: 30,
    logoUrl: null, brandColor: null, policyText: null, pickupBooking: false, portalPickupBooking: false,
    returnLocationId: null, returnLocationName: null, bostaConnected: false })
  if (url.includes('/returns-exchanges')) return json({ items: [], nextCursor: null, stages: {}, tiles: {} })
  return json({})
}) as typeof fetch

history.replaceState(null, '', page === 'drawer' ? '/exchanges?tab=requests&request=rr-1' : '/lookup?q=P1048327')
createRoot(document.getElementById('root')!).render(<App />)
