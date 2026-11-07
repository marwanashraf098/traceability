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
import { ReceivingRoute } from '../pages/receiving/GuidedReceiving'
import Receiving from '../pages/Receiving'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { resetCapabilitiesCache } from '../capabilities'

// Onboarding O4 — the guided first receiving (Flow-5 set up, Flow-6 session + guide + label check, Flow-7 done) and
// the renamed receiving mode cards, in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL,
// desktop 1280×900 and phone 390×844: no sideways page scroll, every control inside the viewport.
// VITE_GUIDED_SHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_GUIDED_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data), blob: async () => new Blob(['x']) })

const PRODUCT = { id: 'p-1', title: 'Linen Shirt', status: 'active', imageUrl: null,
  variants: [{ id: 'v-1', title: 'Black / M', sku: 'LS-BM', price: 500, pieceCounts: {}, committed: 0, available: 0 }] }

function session(status: 'open' | 'finalized') {
  return { id: 'sess-1', status, mode: 'print_stick', reference: null, supplier_name: null, location_name: 'Main Warehouse',
    created_at: '2026-11-07T10:00:00Z', finalized_at: status === 'finalized' ? '2026-11-07T10:05:00Z' : null,
    line_units: 10, piece_count: status === 'finalized' ? 10 : 0,
    lines: [{ id: 'l-0', variant_id: 'v-1', variant_title: 'Black / M', sku: 'LS-BM', product_title: 'Linen Shirt',
      quantity: 10, piece_count: status === 'finalized' ? 10 : 0 }] }
}

function backend(status: 'open' | 'finalized') {
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/onboarding/progress')) return json({ steps: [{ key: 'first_receiving', done: false, skippable: false, skipped: false }],
      tasks: [], card: 'full', pickPackChosen: false, labelingPace: null })
    if (url.includes('/onboarding/receiving-suggestion')) return json({ suggestion: { variantId: 'v-1', shopifyQuantity: 42, mainOnHand: 0 } })
    if (url.endsWith('/me')) return json({ name: 'Mona Adel', role: 'owner', flexibleLabeling: true, scanHelpers: false, demoMode: false })
    if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true }])
    if (url.includes('/label-runs')) return json([])
    if (url.includes('/catalog')) return json({ products: [PRODUCT], nextCursor: null })
    if (url.endsWith('/receiving/sessions/sess-1/pieces')) return json(Array.from({ length: 10 }, (_, i) =>
      ({ id: `pc-${i}`, barcode: `BC-${i}`, short_code: `P0002${String(i).padStart(2, '0')}` })))
    if (url.endsWith('/receiving/sessions/sess-1')) return json(session(status))
    if (url.endsWith('/receiving/sessions')) return json([])
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

function mount(path: string, lng: 'en' | 'ar', ui = <ReceivingRoute />) {
  render(
    <StationProvider><MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
        <Routes><Route path="/receiving" element={ui} /><Route path="/overview" element={<div />} /></Routes>
      </ToastProvider></I18nextProvider>
    </MemoryRouter></StationProvider>)
}

const SIZES: [string, number, number][] = [['desktop', 1280, 900], ['phone', 390, 844]]

beforeEach(() => {
  forgetOnboardingProgress(); resetCapabilitiesCache()
  try { sessionStorage.clear() } catch { /* storage blocked */ }
  setAccessToken(token('owner'))
  window.scrollTo(0, 0)
})

afterEach(() => {
  cleanup(); clearAccessToken(); vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('Guided first receiving layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      test(`flow5 ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('open')
        mount('/receiving?guided=1', lng)
        await screen.findByTestId('guided-suggestion')
        checkLayout(w)
        await shot(`flow5-${lng}-${size}`)
      })

      test(`flow6 ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('finalized')
        sessionStorage.setItem('traced.guidedReceiving', JSON.stringify({ id: 'sess-1', mode: 'print_stick', mainBefore: 0 }))
        mount('/receiving?guided=1', lng)
        await screen.findByTestId('guide-panel')
        const input = await screen.findByTestId('label-check')
        expect(input).toBeTruthy()
        checkLayout(w)
        await shot(`flow6-${lng}-${size}`)
      })

      test(`flow7 ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('finalized')
        sessionStorage.setItem('traced.guidedReceiving', JSON.stringify({ id: 'sess-1', mode: 'print_stick', mainBefore: 0 }))
        mount('/receiving?guided=1', lng)
        const done = await screen.findByRole('button', { name: (lng === 'en' ? en : ar).guidedReceiving.done })
        done.click()
        await screen.findByTestId('guided-done')
        window.scrollTo(0, 0)
        checkLayout(w)
        await shot(`flow7-${lng}-${size}`)
      })

      test(`mode cards ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('open')
        mount('/receiving', lng, <Receiving />)
        // The chooser shows only once /me said flexible labeling is on (its Factory runs tab appears with it).
        await screen.findByText((lng === 'en' ? en : ar).labelRuns.tab.runs)
        const [add] = await screen.findAllByRole('button', { name: '+ ' + (lng === 'en' ? en : ar).receiving.new })
        add.click()
        await screen.findByTestId('receiving-mode-factory-needs-run')
        checkLayout(w)
        await shot(`modes-${lng}-${size}`)
      })
    }
  }
})
