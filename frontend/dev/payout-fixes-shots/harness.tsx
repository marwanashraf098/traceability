// Dev-only screenshot harness for the 2026-11-27 payout fixes (copy of dev/analytics-shots, never in the build):
// the BROEK analytics fixture, with /money/payouts and /money/pipeline in the new shapes.
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
const PAYOUTS = { range: RANGE, payouts: [
  { transactionId: 'WEDCOD25NOV26', date: '2026-11-25', trackedShipments: 188, trackedDeposited: 151240.5, codCollected: 167310, bostaFees: 16069.5, negativeDeposits: 21, negativeDepositsAmount: -1683.4 },
  { transactionId: 'SUNCOD22NOV26', date: '2026-11-22', trackedShipments: 64, trackedDeposited: 49880.2, codCollected: 55230, bostaFees: 5349.8, negativeDeposits: 6, negativeDepositsAmount: -472.6 },
  { transactionId: 'WEDCOD18NOV26', date: '2026-11-18', trackedShipments: 201, trackedDeposited: 158412.9, codCollected: 175560, bostaFees: 17147.1, negativeDeposits: 27, negativeDepositsAmount: -2120.8 },
  { transactionId: 'SUNCOD15NOV26', date: '2026-11-15', trackedShipments: 0, trackedDeposited: -310.2, codCollected: 0, bostaFees: 310.2, negativeDeposits: 4, negativeDepositsAmount: -310.2 },
] }
const PIPELINE = {
  range: RANGE, asOf: '2026-11-27T09:40:00Z',
  notFulfilled: { count: 88, value: 101200, expected: 80920, fees: 1938, feesEstimated: 34, prepaid: 34 },
  inTransit: { count: 182, value: 209300, expected: 161940, fees: 12746, feesEstimated: 182, prepaid: 9 },
  awaitingPayout: { count: 171, deposited: 196650, nextCashoutDate: '2026-11-29', deliveredNotYetSettled: 6, deliveredNotYetSettledEstimate: 7100 },
  feesToDeduct: { count: 61, amount: 4321.6, returning: { count: 44, amount: 3116.4 }, customerReturns: { count: 5, amount: 530.1 }, finishedUnsettled: { count: 12, amount: 675.1 } },
  inYourBank: { shipments: 453, deposited: 359223.4, payouts: 4, lastTransferDate: '2026-11-25' },
  openOrderDays: 60,
}
window.fetch = ((input: RequestInfo | URL) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.endsWith('/me')) return json({ name: 'Demo Owner', email: 'owner@example.com', role: 'owner' })
  if (url.includes('/exceptions/count')) return json({ count: 2 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/analytics/money/payouts')) return json(PAYOUTS)
  if (url.includes('/analytics/money/pipeline')) return json(PIPELINE)
  if (url.includes('/analytics/') || url.endsWith('/connections')) {
    return (route(url) as Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>)
      .then(async r => new Response(r.ok ? JSON.stringify(await r.json()) : '{}', { status: r.status, headers: { 'content-type': 'application/json' } }))
  }
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', path)
createRoot(document.getElementById('root')!).render(<App />)
