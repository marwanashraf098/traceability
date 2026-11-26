import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarReportPage from '../pages/bazar/BazarReport'
import type { BazarReport } from '../bazar/api'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

// U1c — Bazar small fixes from the 2026-11-26 E2E test (report screen): the hours chart's bars are capped so one busy
// hour looks like a normal bar; "What sold" names the variant ("Board · Electric", never "Default Title").
// Send by count's screen side is in bazarU1b.test.tsx ("Send by count").

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EV = 'ev1'
const jwt = (role: string) => `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

function serve(r: BazarReport) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    return path === `/bazar-events/${EV}/report` ? json(200, r) : json(404, {})
  }))
}

function renderReport() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[`/bazar/${EV}/report`]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes><Route path="/bazar/:id/report" element={<BazarReportPage />} /></Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  setAccessToken(jwt('owner'))
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

function report(over: Partial<BazarReport> = {}): BazarReport {
  return {
    event: { id: EV, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: '2026-11-20', ends_on: '2026-11-22',
             started_at: null, ended_at: null, location_name: 'Zamalek Bazar' },
    revenue: '2000', money_in: '2000', pieces_sold: 6, pieces_sent: 10, sell_through: '0.6', average_sale: '333',
    sales: 6, discounts: '0', returns: 0, exchanges: 0, sales_by_day: [],
    busiest_hours: [{ hour: 12, sales: 1 }, { hour: 13, sales: 0 }, { hour: 14, sales: 2 }],
    top_products: [{ product_title: 'Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition', sold: 6, sent: 8,
                     sell_through: '0.75', revenue: '2000' }],
    unsold_products: [{ product_title: 'Knit scarf', sold: 0, sent: 2 }],
    has_sizes: false, top_sizes: [],
    payment_split: [], money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0',
      instapay_not_received: '0', total: '0', days_closed: 2, days_not_closed: 0 },
    per_seller: [], customers_saved: 0,
    transfer: { sent: 10, sold: 6, back: 4, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 0,
                transfer_id: 't-1', status: 'closed', balanced: true },
    ...over,
  } as BazarReport
}

describe('U1c report', () => {
  test('busiest hours: one hour is a normal, capped bar — not the whole card', async () => {
    serve(report({ busiest_hours: [{ hour: 18, sales: 7 }] }))
    renderReport()
    const hours = await screen.findByTestId('report-hours')
    const cols = within(hours).getAllByTestId('report-hour')
    expect(cols).toHaveLength(1)
    expect(cols[0].className).toContain('max-w-[48px]')
    expect(cols[0].parentElement!.className).toContain('justify-center')
  })

  test('What sold names the variant when the product has real ones; a single-variant product just its name', async () => {
    serve(report({
      top_items: [
        { product_title: 'The Complete Snowboard', variant_title: 'Electric', sold: 2, sent: 3, sell_through: '0.6667', revenue: '1400' },
        { product_title: 'Plain scarf', sold: 1, sent: 1, sell_through: '1', revenue: '200' },
      ],
    }))
    renderReport()
    const card = await screen.findByTestId('report-top-products')
    const rows = within(card).getAllByTestId('report-sold')
    expect(rows.map(r => r.textContent)).toEqual([
      expect.stringContaining('The Complete Snowboard · Electric'),
      expect.not.stringContaining('·'),
    ])
    expect(rows[1].textContent).toContain('Plain scarf')
    expect(card.textContent).not.toContain('Default Title')
    expect(card.textContent).not.toContain('Oxygen')                 // top_products is the fallback only
  })

  test('an older server without top_items: What sold falls back to the products', async () => {
    serve(report())
    renderReport()
    const card = await screen.findByTestId('report-top-products')
    expect(within(card).getAllByTestId('report-sold')[0].textContent)
      .toContain('Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition')
  })
})
