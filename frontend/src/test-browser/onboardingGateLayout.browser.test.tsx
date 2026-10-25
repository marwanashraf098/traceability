import { afterEach, describe, expect, test, vi } from 'vitest'
import { page, server } from 'vitest/browser'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { refreshSetupStep } from '../setupGate'
import { OwnerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import WarehouseAddressBanner from '../components/WarehouseAddressBanner'

// Onboarding gate (V185) — in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, at
// desktop 1280×800 and phone 390×844: step 1 (connect Shopify), step 2 (main warehouse address), the manager's
// waiting screen and the soft banner — no sideways page scroll, every control inside the viewport.
// SCREENSHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_GATE_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const PROVINCES = [
  { code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [{ nameEn: 'Nasr City', nameAr: 'مدينة نصر' }] },
  { code: 'GZ', nameEn: 'Giza', nameAr: 'الجيزة', areas: [] },
  { code: 'ALX', nameEn: 'Alexandria', nameAr: 'الإسكندرية', areas: [] },
]

function backend(role: string, step: string | null) {
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/me')) return json({ role, setupStep: step })
    if (url.includes('/connections')) return json({ shopify: { connected: step === 'address', status: step === 'address' ? 'connected' : 'disconnected',
      shopDomain: step === 'address' ? 'brand-eg.myshopify.com' : null, connectionType: null, storeId: null, importStatus: null, lastSyncAt: null },
      bosta: { connected: false }, customAppAvailable: false, oauthAvailable: true, shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] } })
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
  for (const el of Array.from(document.querySelectorAll('button, input, select'))) {
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

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['phone', 390, 844]]

afterEach(() => {
  cleanup()
  clearAccessToken()
  refreshSetupStep()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('onboarding gate layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      for (const [role, step, testId] of [
        ['owner', 'shopify', 'onboarding-step-shopify'],
        ['owner', 'address', 'onboarding-step-address'],
        ['manager', 'shopify', 'onboarding-manager-waiting'],
      ] as const) {
        test(`${role} ${step} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          refreshSetupStep()
          setAccessToken(token(role))
          backend(role, step)
          render(
            <StationProvider><MemoryRouter initialEntries={['/overview']}>
              <I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
                <Routes><Route path="/overview" element={<OwnerOnlyRoute><div>screen</div></OwnerOnlyRoute>} /></Routes>
              </ToastProvider></I18nextProvider>
            </MemoryRouter></StationProvider>)
          await screen.findByTestId(testId)
          if (step === 'address' && role === 'owner') await screen.findByText('brand-eg.myshopify.com')
          checkLayout(w)
          await shot(`${role}-${step}-${lng}-${size}`)
        })
      }

      test(`banner ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        render(<MemoryRouter><I18nextProvider i18n={i18nFor(lng)}>
          <div dir={lng === 'ar' ? 'rtl' : 'ltr'} className="bg-bg">
            <WarehouseAddressBanner me={{ name: 'O', email: null, role: 'owner', warehouseAddressNeeded: true }} />
          </div>
        </I18nextProvider></MemoryRouter>)
        await screen.findByTestId('warehouse-address-banner')
        checkLayout(w)
        await shot(`banner-${lng}-${size}`)
      })
    }
  }
})
