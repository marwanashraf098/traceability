import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page, server } from 'vitest/browser'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import Layout from '../components/Layout'
import HelpCenter from '../pages/help/HelpCenter'
import HelpArticle from '../pages/help/HelpArticle'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { resetCapabilitiesCache } from '../capabilities'

// Onboarding O6 — /help and one article (/help/order-not-in-pick-pack) inside the real Layout, in a real browser with
// the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, desktop 1280×900 and phone 390×844: no sideways page scroll,
// every control inside the viewport. VITE_HELP_SHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_HELP_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return inst
}

function checkLayout(w: number) {
  expect(document.documentElement.scrollWidth, 'no sideways page scroll').toBeLessThanOrEqual(w)
  const main = document.querySelector('main')
  for (const el of Array.from((main ?? document).querySelectorAll('a, button, input'))) {
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

beforeEach(() => {
  forgetOnboardingProgress(); resetCapabilitiesCache()
  try { localStorage.setItem('traced-sidebar', 'expanded') } catch { /* storage blocked */ }
  setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s')
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/me')) return json({ name: 'Mona Adel', role: 'owner', uiState: { toursDone: [], pagesSeen: ['overview', 'settings'] } })
    if (url.includes('/exceptions/count')) return json({ count: 0 })
    return json({})
  })
})

afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals(); document.documentElement.dir = 'ltr' })

describe('Help center layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      for (const [name, path] of [['center', '/help'], ['article', '/help/order-not-in-pick-pack']] as const) {
        test(`${name} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          render(
            <StationProvider><MemoryRouter initialEntries={[path]}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
              <Routes>
                <Route path="/help" element={<Layout><HelpCenter /></Layout>} />
                <Route path="/help/:id" element={<Layout><HelpArticle /></Layout>} />
              </Routes>
            </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
          // The first test also loads the articles' lazy chunk (all markdown, parsed) — allow for it under load.
          await screen.findByTestId(name === 'center' ? 'help-center' : 'help-article', {}, { timeout: 10_000 })
          checkLayout(w)
          await shot(`help-${name}-${lng}-${size}`)
        })
      }
    }
  }
})
