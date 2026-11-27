// Dev-only screenshot harness for analytics slices 4–6 (2026-11-27; copy of dev/payout-fixes-shots, never in the build):
// the BROEK analytics fixture, with /orders, /profit/summary and /customers/watch in the new shapes.
// Renders the REAL App (shell, routing, i18n) as an owner, with fetch answered from the analytics
// fixtures:  harness.html?fixture=broek|femine|highline&lang=en|ar&path=/analytics/summary
// Run with VITE_ANALYTICS_ENABLED=true (shoot.mjs does).
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
import { BROEK, FEMINE, HIGH_LINE, analyticsFetch } from '../../src/test/analyticsFixtures'

const q = new URLSearchParams(location.search)
const fixture = { broek: BROEK, femine: FEMINE, highline: HIGH_LINE }[q.get('fixture') ?? 'broek'] ?? BROEK
const lang = q.get('lang') === 'ar' ? 'ar' : 'en'
const path = q.get('path') ?? '/analytics/summary'

localStorage.setItem('lang', lang)
localStorage.setItem('traced-analytics-nav', 'open')
await i18n.changeLanguage(lang)
document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
document.documentElement.lang = lang

const payload = btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))
setAccessToken(`h.${payload}.s`)

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }))
const route = analyticsFetch(fixture)
const RANGE = { from: '2026-11-01', to: '2026-11-30', tz: 'Africa/Cairo' }
const row = (name: string, lines: number, shipping: number | null, fees: number, net: number | null, payment: string,
             fin = 'paid', delivery = { key: 'delivered', label: 'Delivered' }, items = 1) => ({
  orderId: `id-${name}`, name, placedAt: '2026-11-10T10:00:00Z', customer: 'Mona A.',
  governorate: { key: 'cairo', label: 'Cairo', labelAr: 'القاهرة' }, items, total: lines + (shipping ?? 0), lines, shipping,
  paymentGroup: payment, deliveryStatus: delivery, financialStatus: fin, bostaFees: fees, feesEstimated: false, netToYou: net,
  refundAmountUnknown: false, trackingNumbers: ['7300000001'],
})
const ORDERS = { range: RANGE, asOf: '2026-11-27T09:40:00Z', page: 0, size: 50, total: 4,
  counts: { other_carrier: 0, lost: 1, refunded: 0, paid: 2, overdue: 0, awaiting_payout: 1, expected: 0 },
  orders: [row('BRK-46818-EG', 1299, 85, 92.34, 1291.66, 'Card'), row('BRK-46776-EG', 1745, 85, 103.74, 1726.26, 'COD'),
           row('BRK-46802-EG', 1300, 0, 112.86, -112.86, 'COD', 'lost', { key: 'refused', label: 'Refused' }, 2),
           row('BRK-46911-EG', 650, null, 70, null, 'COD', 'awaiting_payout')] }
const PROFIT = { range: RANGE,
  coverage: { variantsSold: 214, variantsCosted: 210, keptUnits: 3010, costedUnits: 2882, realizedTotal: 2105400, realizedCosted: 2011300,
    costedRevenueShare: 0.955, costUnknownVariants: 4, costUnknownUnits: 128 },
  cogs: 804500, grossProfit: 1206800, grossMargin: 0.6, feesTotal: 241900, feesOnCosted: 230100,
  contributionProfit: 1140200, contributionMargin: 0.567, shippingIncome: 171300, shippingIncomeOnCosted: 163500 }
const WATCH = { asOf: '2026-11-27T09:40:00Z', minRefused: 2, refusedCodFromShopify: 11, customers: [
  ['Mostafa E.', 'Assiut', 'أسيوط', 5, 4], ['Youssef M.', 'Sohag', 'سوهاج', 4, 3], ['Dina S.', 'Giza', 'الجيزة', 6, 2], ['Karim H.', 'Cairo', 'القاهرة', 3, 2],
].map(([name, g, gAr, orders, refused], i) => ({ customerRef: `ref-${i}`, displayName: name, governorate: g, governorateAr: gAr, orders,
  refusedCodOrders: refused, deliveredOrders: (orders as number) - (refused as number), refusedValue: (refused as number) * 1385, blocked: i === 2,
  suggestion: 'Ask for prepayment', blocklistLink: '/blocklist' })) }
window.fetch = ((input: RequestInfo | URL) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.endsWith('/me')) return json({ name: 'Demo Owner', email: 'owner@example.com', role: 'owner' })
  if (url.includes('/exceptions/count')) return json({ count: 2 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (/\/analytics\/orders(\?|$)/.test(url)) return json(ORDERS)
  if (url.includes('/analytics/profit/summary')) return json(PROFIT)
  if (url.includes('/analytics/customers/watch')) return json(WATCH)
  if (url.includes('/analytics/') || url.endsWith('/connections')) {
    return (route(url) as Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>)
      .then(async r => new Response(r.ok ? JSON.stringify(await r.json()) : '{}', { status: r.status, headers: { 'content-type': 'application/json' } }))
  }
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', path)
createRoot(document.getElementById('root')!).render(<App />)
