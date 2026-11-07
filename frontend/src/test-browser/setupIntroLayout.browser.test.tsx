import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page, server, userEvent } from 'vitest/browser'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { forgetIntroRedirects, markShopifyStepSeen, refreshSetupStep } from '../setupGate'
import { OwnerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { SetupCourierPage, SetupReadyPage } from '../pages/onboarding/SetupIntro'

// Onboarding O2 — Flow-1 "Connected", Flow-3 (Bosta and Another courier) and Flow-4 in a real browser with the
// app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, desktop 1280×900 and phone 390×844: no sideways page
// scroll, every control inside the viewport. VITE_SETUP_SHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_SETUP_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const PROVINCES = [{ code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [] }]

function backend(me: Record<string, unknown>) {
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/me')) return json(me)
    if (url.includes('/connections')) return json({
      shopify: { connected: true, status: 'connected', shopDomain: 'brand-eg.myshopify.com', connectionType: 'oauth',
        storeId: 's', importStatus: 'pending', lastSyncAt: null, locationState: 'active' },
      bosta: { connected: false }, customAppAvailable: false, oauthAvailable: true,
      shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] } })
    if (url.endsWith('/onboarding/progress')) return json({
      steps: [{ key: 'courier', done: false, skippable: true, skipped: true }], tasks: [], card: 'full',
      pickPackChosen: false, labelingPace: null, setupIntroSeen: false })
    if (url.endsWith('/settings/couriers')) return json({ enabled: false, defaultCarrier: null, suggestions: [], bostaConnected: false })
    if (url.includes('/setup/address')) return json({ address: null, provinces: PROVINCES })
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

function checkLayout(w: number) {
  expect(document.documentElement.scrollWidth, 'no sideways page scroll').toBeLessThanOrEqual(w)
  for (const el of Array.from(document.querySelectorAll('a, button, input, select'))) {
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

function mount(path: string, lng: 'en' | 'ar') {
  render(
    <StationProvider><MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
        <Routes>
          <Route path="/overview" element={<OwnerOnlyRoute><div>screen</div></OwnerOnlyRoute>} />
          <Route path="/setup/courier" element={<SetupCourierPage />} />
          <Route path="/setup/ready" element={<SetupReadyPage />} />
        </Routes>
      </ToastProvider></I18nextProvider>
    </MemoryRouter></StationProvider>)
}

const SIZES: [string, number, number][] = [['desktop', 1280, 900], ['phone', 390, 844]]

beforeEach(() => {
  refreshSetupStep()
  forgetIntroRedirects()
  setAccessToken(token('owner'))
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  refreshSetupStep()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('setup intro layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      test(`flow1 connected ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        markShopifyStepSeen()
        backend({ role: 'owner', name: 'Mona Adel', setupStep: 'address' })
        mount('/overview', lng)
        await screen.findByTestId('onboarding-shopify-connected')
        await screen.findByText('brand-eg.myshopify.com')
        checkLayout(w)
        await shot(`flow1-connected-${lng}-${size}`)
      })

      test(`flow3 bosta ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend({ role: 'owner', setupStep: null, setupIntro: 'courier' })
        mount('/setup/courier', lng)
        await screen.findByTestId('setup-courier')
        checkLayout(w)
        await shot(`flow3-bosta-${lng}-${size}`)
      })

      test(`flow3 other ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend({ role: 'owner', setupStep: null, setupIntro: 'courier' })
        mount('/setup/courier', lng)
        await userEvent.click(await screen.findByTestId('setup-courier-other'))
        await screen.findByTestId('setup-courier-other-note')
        checkLayout(w)
        await shot(`flow3-other-${lng}-${size}`)
      })

      test(`flow4 ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend({ role: 'owner', setupStep: null, setupIntro: 'ready' })
        mount('/setup/ready', lng)
        await screen.findByTestId('setup-ready')
        checkLayout(w)
        await shot(`flow4-${lng}-${size}`)
      })
    }
  }
})
