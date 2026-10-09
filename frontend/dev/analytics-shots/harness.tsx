// Dev-only screenshot harness (never part of the production build — vite builds index.html only).
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
window.fetch = ((input: RequestInfo | URL) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.endsWith('/me')) return json({ name: 'Demo Owner', email: 'owner@example.com', role: 'owner' })
  if (url.includes('/exceptions/count')) return json({ count: 2 })
  if (url.includes('/onboarding/status')) return json({ steps: [], allDone: true, dismissed: true })
  if (url.includes('/analytics/') || url.endsWith('/connections')) {
    return (route(url) as Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>)
      .then(async r => new Response(r.ok ? JSON.stringify(await r.json()) : '{}', { status: r.status, headers: { 'content-type': 'application/json' } }))
  }
  return Promise.resolve(new Response('{}', { status: 404 }))
}) as typeof fetch

history.replaceState(null, '', path)
createRoot(document.getElementById('root')!).render(<App />)
