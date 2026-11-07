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
import { TourCard } from '../tours/TourCard'
import { forgetTours, startTour } from '../tours/tourState'
import type { TourId } from '../tours/tours'
import PickPackChooser from '../pages/fulfill/PickPackChooser'
import Layout from '../components/Layout'
import PickupSessions from '../pages/PickupSessions'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { resetCapabilitiesCache } from '../capabilities'

// Onboarding O5 — each tour's step 1 (the card docked bottom-end on desktop, a bottom sheet on a phone) and the
// Pick & Pack chooser, in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, desktop 1280×900
// and phone 390×844: no sideways page scroll, every control inside the viewport, the card on the END side.
// VITE_TOURS_SHOT_DIR (env, optional) also saves a PNG of each.

const SHOT_DIR = (import.meta.env.VITE_TOURS_SHOT_DIR as string | undefined) ?? ''

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  document.documentElement.lang = lng
  return inst
}

function checkLayout(w: number) {
  expect(document.documentElement.scrollWidth, 'no sideways page scroll').toBeLessThanOrEqual(w)
  for (const el of Array.from(document.querySelectorAll('a, button'))) {
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
const TOUR_IDS: TourId[] = ['pickups', 'scan_returns', 'stock_take', 'transfers']

beforeEach(() => {
  forgetTours()
  setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s')
  vi.stubGlobal('fetch', () => Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }))
})

afterEach(() => {
  cleanup(); clearAccessToken(); vi.unstubAllGlobals(); forgetTours()
  document.documentElement.dir = 'ltr'
})

describe('Tours and the Pick & Pack chooser', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      for (const id of TOUR_IDS) {
        test(`tour ${id} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          render(<MemoryRouter><I18nextProvider i18n={i18nFor(lng)}>
            <div className="p-6 text-primary"><h1 className="text-h1">{(lng === 'en' ? en : ar).tours[id].name}</h1></div>
            <TourCard tourId={id} />
          </I18nextProvider></MemoryRouter>)
          startTour(id)
          const card = await screen.findByTestId(`tour-${id}`)
          // Measure after the 0.2 s fade-in (it starts 4 px lower).
          await Promise.all(card.getAnimations().map(a => a.finished))
          const r = card.getBoundingClientRect()
          if (size === 'desktop') {
            // Docked on the END side: right in LTR, left in RTL.
            if (lng === 'en') expect(w - r.right).toBeLessThan(40)
            else expect(r.left).toBeLessThan(40)
            expect(r.width).toBeLessThanOrEqual(361)
          } else {
            expect(Math.round(r.width)).toBe(w)
            expect(Math.round(h - r.bottom)).toBe(0)
          }
          checkLayout(w)
          await shot(`tour-${id}-${lng}-${size}`)
        })
      }

      for (const bosta of [true, false]) {
        test(`chooser ${bosta ? 'bosta' : 'no-bosta'} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          render(<MemoryRouter><I18nextProvider i18n={i18nFor(lng)}>
            <div className="p-6"><PickPackChooser current="order_queue" bostaConnected={bosta} canChange replay={false} onDone={() => {}} /></div>
          </I18nextProvider></MemoryRouter>)
          await screen.findByTestId('pick-pack-chooser')
          checkLayout(w)
          await shot(`chooser-${bosta ? 'bosta' : 'nobosta'}-${lng}-${size}`)
        })
      }
    }
  }
})

// O5 item 3 — the bottom sheet must never hide a control for good: at phone width, with the tour open on an open
// pickup session (many scans, so Close is the last thing on the page), scrolling Layout's <main> to the end puts
// Close fully ABOVE the sheet.
describe('Phone: controls can be scrolled above the sheet', () => {
  const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data) })
  const scans = Array.from({ length: 14 }, (_, i) => ({ shipmentId: `sh-${i}`, trackingNumber: `81234567${String(i).padStart(2, '0')}`,
    orderNumber: `#10${40 + i}`, codAmount: 450, scannedAt: '2026-11-07T08:01:00Z', scannedByName: 'Ahmed' }))
  const session = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-11-07', scheduledTimeSlot: null, scannedCount: 14,
    openedByName: 'Ahmed', createdAt: '2026-11-07T08:00:00Z', notes: null, closedByName: null, closedAt: null, scans }

  for (const lng of ['en', 'ar'] as const) {
    test(`Pickups Close is reachable above the tour sheet (${lng})`, async () => {
      await page.viewport(390, 844)
      forgetOnboardingProgress(); resetCapabilitiesCache()
      vi.stubGlobal('fetch', (url: string) => {
        if (url.endsWith('/me')) return json({ name: 'Mona', role: 'owner', uiState: { toursDone: ['pickups'], pagesSeen: [] } })
        if (url.endsWith('/pickup-sessions')) return json([session])
        if (url.endsWith('/pickup-sessions/pk-1')) return json(session)
        if (url.includes('/exceptions/count')) return json({ count: 0 })
        return json({})
      })
      render(<StationProvider><MemoryRouter initialEntries={['/pickups']}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
        <Routes><Route path="/pickups" element={<Layout><PickupSessions /></Layout>} /></Routes>
      </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
      const row = await screen.findByText('2026-11-07', { selector: 'td, td *' }).catch(() => null)
      ;(row?.closest('tr') ?? document.querySelector('tr.tbl-row'))!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      const close = await screen.findByText((lng === 'en' ? en : ar).pickups.closeSession, { selector: 'button' })
      startTour('pickups')
      const sheet = await screen.findByTestId('tour-pickups')
      await Promise.all(sheet.getAnimations().map(a => a.finished))
      const main = document.querySelector('main')!
      await new Promise(r => requestAnimationFrame(() => r(null)))
      main.scrollTop = main.scrollHeight
      await new Promise(r => requestAnimationFrame(() => r(null)))
      const c = close.getBoundingClientRect(), sh = sheet.getBoundingClientRect()
      expect(c.top, 'Close below the top of the screen').toBeGreaterThanOrEqual(0)
      expect(c.bottom, 'Close fully above the sheet').toBeLessThanOrEqual(sh.top + 0.5)
      await shot(`pickups-close-above-sheet-${lng}-phone`)
    })
  }
})
