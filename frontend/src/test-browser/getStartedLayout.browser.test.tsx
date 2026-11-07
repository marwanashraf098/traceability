import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page, server } from 'vitest/browser'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import Layout from '../components/Layout'
import Overview from '../pages/Overview'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { forgetPagesSeen } from '../pageSeen'
import { resetCapabilitiesCache } from '../capabilities'

// Onboarding O3 — the Get started card on the real Overview (inside Layout, with the sidebar's "not opened yet"
// dots), full / slim / hidden, in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL,
// desktop 1280×900 and phone 390×844: no sideways page scroll, every control inside the viewport.
// VITE_GETSTARTED_SHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_GETSTARTED_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

function progress(card: 'full' | 'slim' | 'hidden') {
  return {
    steps: [
      { key: 'shopify', done: true, skippable: false, skipped: false },
      { key: 'address', done: true, skippable: false, skipped: false },
      { key: 'first_receiving', done: true, skippable: false, skipped: false },
      { key: 'courier', done: false, skippable: true, skipped: false },
      { key: 'test_label', done: false, skippable: true, skipped: false },
    ],
    tasks: ['receive', 'pack', 'pickup', 'returns', 'transfer', 'stock_take', 'portal', 'team']
      .map(key => ({ key, tried: key === 'receive' || key === 'pack' })),
    card, cardChosen: true, setupCompletedAt: new Date().toISOString(), bostaConnected: false,
    pickPackChosen: false, labelingPace: null,
  }
}

const TRENDS = ['orders', 'delivered', 'returns', 'exchanges', 'exceptions'].map(metric => ({
  metric, total: metric === 'orders' ? 6 : 1,
  series: Array.from({ length: 7 }, (_, i) => ({ date: `2026-11-0${i + 1}`, count: i % 3 })),
}))

function backend(card: 'full' | 'slim' | 'hidden') {
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/onboarding/progress')) return json(progress(card))
    if (url.endsWith('/me/ui-state')) return json({ toursDone: [], pagesSeen: ['overview'] })
    if (url.endsWith('/me')) return json({ name: 'Mona Adel', email: 'mona@brand.eg', role: 'owner',
      uiState: { toursDone: [], pagesSeen: ['overview', 'orders', 'receiving', 'inventory'] } })
    if (url.includes('/exceptions/count')) return json({ count: 0 })
    if (url.includes('/overview/trends')) return json(TRENDS)
    if (url.includes('/overview/late-to-pack')) return json({ overdue: 0, over48: 0 })
    if (url.includes('/overview/top-skus')) return json([])
    if (url.includes('/orders/funnel')) return json({ newCount: 2, picking: 1, packed: 1, courier: 1, delivered: 1 })
    if (url.includes('/orders/summary')) return json({ total: 6, processing: 2, withCourier: 2, delivered: 1, returned: 1 })
    if (url.includes('/inventory/valuation')) return json({ lowStockCount: 0, inventoryValue: 0, variantsCosted: 0, variantsTotal: 0 })
    if (url.includes('/exceptions?')) return json({ items: [] })
    if (url.includes('/orders?')) return json({ items: [], page: 0, size: 20, total: 0 })
    if (url.includes('/connections')) return json({ shopify: { connected: true, status: 'connected' }, bosta: { connected: false } })
    if (url.includes('/locations')) return json([])
    return json({})
  })
}

function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s'
}

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return inst
}

// Scoped to the Get started card: Overview's own date-range picker (SegmentedControl) is ~7 px too wide at 390 px in
// English — existing, not part of O3 (reported separately).
function checkLayout(w: number, region: HTMLElement) {
  expect(document.documentElement.scrollWidth, 'no sideways page scroll').toBeLessThanOrEqual(w)
  for (const el of Array.from(region.querySelectorAll('a, button, input, select'))) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    expect(r.left, `${el.outerHTML.slice(0, 60)} inside on the left`).toBeGreaterThanOrEqual(-0.5)
    expect(r.right, `${el.outerHTML.slice(0, 60)} inside on the right`).toBeLessThanOrEqual(w + 0.5)
  }
}

async function shot(name: string) {
  if (!SHOT_DIR) return
  await page.screenshot({ path: `${SHOT_DIR}/${server.browser}-${name}.png` })
}

const SIZES: [string, number, number][] = [['desktop', 1280, 900], ['phone', 390, 844]]
const TEST_ID = { full: 'get-started-card', slim: 'get-started-slim', hidden: 'get-started-hidden' } as const

beforeEach(() => {
  forgetOnboardingProgress()
  forgetPagesSeen()
  resetCapabilitiesCache()
  try { localStorage.setItem('traced-sidebar', 'expanded') } catch { /* storage blocked */ }
  setAccessToken(token('owner'))
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('Get started layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      for (const card of ['full', 'slim', 'hidden'] as const) {
        test(`${card} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          backend(card)
          render(
            <StationProvider><MemoryRouter initialEntries={['/overview']}>
              <I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
                <Routes><Route path="/overview" element={<Layout><Overview /></Layout>} /></Routes>
              </ToastProvider></I18nextProvider>
            </MemoryRouter></StationProvider>)
          const region = await screen.findByTestId(TEST_ID[card])
          await screen.findByTestId('stat-cards')
          if (card !== 'hidden' && size === 'desktop') await screen.findByTestId('nav-dot-fulfill')
          checkLayout(w, region)
          await shot(`getstarted-${card}-${lng}-${size}`)
        })
      }
    }
  }
})
