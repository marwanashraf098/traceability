import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import BazarEvents from '../pages/bazar/BazarEvents'
import BazarReportPage from '../pages/bazar/BazarReport'
import CloseDayPage from '../pages/bazar/r3/CloseDayPage'
import EndEventPage from '../pages/bazar/r3/EndEventPage'
import { defaultCloseDay } from '../pages/bazar/closeDayDefault'
import type { BazarDay, BazarEventRow, BazarReport } from '../bazar/api'
import { clearAccessToken, setAccessToken } from '../auth'

// U1a — Bazar must-fix bugs 3 and 4 (2026-11-22 E2E):
//   bug 4: Close day opens the OLDEST day still needing a close (needs_close), shows that it is the oldest open day;
//          ?day= still wins; nothing open → today. The event page's button reads "Close day" (+ the date when the day
//          it opens isn't today).
//   bug 3: an ended bazar whose transfer isn't closed: the report and the bazar list say what's left and link to End
//          event; after "Close the transfer" the green box carries the primary "View report".

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EV = 'ev1'
const DAY1 = '2026-11-20'
const TODAY = '2026-11-21'
const jwt = (role: string) => `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: () => Promise<Response> }
function install(...hs: Handler[]) {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn()
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: () => Promise<Response>): Handler => ({ method, path, fn })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname + l.search}</div> }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>
          <Route path="/bazar" element={<BazarEvents />} />
          <Route path="/bazar/:id" element={<BazarEventPage />} />
          <Route path="/bazar/:id/close-day" element={<><CloseDayPage /><Where /></>} />
          <Route path="/bazar/:id/end-event" element={<><EndEventPage /><Where /></>} />
          <Route path="/bazar/:id/report" element={<BazarReportPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </I18nextProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(jwt('owner'))
  testI18n.changeLanguage('en')
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

// ── fixtures ────────────────────────────────────────────────────────────────

function day(business_day: string, n: number, over: Partial<BazarDay> = {}): BazarDay {
  return {
    business_day, day_number: n, is_today: business_day === TODAY, needs_close: false,
    money: { by_method: {}, total: '1000', sales: 3 }, status: 'open', close: null, close_count: 0,
    opening_float_default: { amount: null, source: 'none' }, ...over,
  } as unknown as BazarDay
}

function detail(status: 'live' | 'ended', over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: DAY1, ends_on: '2026-11-22', status,
    location_id: 'l1', location_name: 'Zamalek Bazar', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: status === 'live' ? 'sent' : 'reconciling', transfer_sent_at: null, transfer_shopify_sync_mode: 'leave',
    online_stock: 'leave', payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null,
    started_at: null, ended_at: status === 'ended' ? '2026-11-22T20:09:00Z' : null,
    counts: { sent: 10, at_bazar: 4, sold: 6, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: {}, total: '1000', sales: 3 },
    totals: { by_method: {}, total: '2000', sales: 6, returns: 0 },
    ...over,
  }
}

function closeDayHandlers(days: BazarDay[]) {
  return [
    on('GET', `/bazar-events/${EV}`, () => json(200, detail('live'))),
    on('GET', `/bazar-events/${EV}/days`, () => json(200, { today: TODAY, payment_methods: ['cash'], days })),
  ]
}

// ── bug 4 ───────────────────────────────────────────────────────────────────

describe('Close day opens the oldest day still open', () => {
  test('the rule: oldest needs_close, else today, else the last day', () => {
    expect(defaultCloseDay([day(DAY1, 1, { needs_close: true }), day(TODAY, 2, { needs_close: true })])?.business_day).toBe(DAY1)
    expect(defaultCloseDay([day(DAY1, 1, { status: 'closed_balanced' }), day(TODAY, 2)])?.business_day).toBe(TODAY)
    // An open day with no money doesn't need closing (End event's own rule) → today.
    expect(defaultCloseDay([day(DAY1, 1, { needs_close: false }), day(TODAY, 2, { needs_close: true })])?.business_day).toBe(TODAY)
    expect(defaultCloseDay([day(DAY1, 1), day('2026-11-19', 0)])?.business_day).toBe('2026-11-19')
    expect(defaultCloseDay([])).toBeUndefined()
  })

  test('computer page: yesterday still open → it opens yesterday and says it is the oldest open day', async () => {
    install(...closeDayHandlers([day(DAY1, 1, { needs_close: true }), day(TODAY, 2, { needs_close: true })]))
    renderAt(`/bazar/${EV}/close-day`)
    const page = await screen.findByTestId('bazar-close-day-page')
    await waitFor(() => expect(within(page).getByTestId(`day-status-${DAY1}`)).toBeTruthy())
    expect(within(page).queryByTestId(`day-status-${TODAY}`)).toBeNull()
    expect(within(page).getByTestId('close-day-oldest').textContent).toContain('oldest day still open')
    expect(within(page).getByTestId('close-day-oldest').textContent).toContain('20')
  })

  test('?day= still wins (no "oldest" note); nothing open → today', async () => {
    install(...closeDayHandlers([day(DAY1, 1, { needs_close: true }), day(TODAY, 2, { needs_close: true })]))
    renderAt(`/bazar/${EV}/close-day?day=${TODAY}`)
    let page = await screen.findByTestId('bazar-close-day-page')
    await waitFor(() => expect(within(page).getByTestId(`day-status-${TODAY}`)).toBeTruthy())
    expect(within(page).queryByTestId('close-day-oldest')).toBeNull()
    cleanup()
    install(...closeDayHandlers([day(DAY1, 1, { status: 'closed_balanced', close_count: 1 }), day(TODAY, 2)]))
    renderAt(`/bazar/${EV}/close-day`)
    page = await screen.findByTestId('bazar-close-day-page')
    await waitFor(() => expect(within(page).getByTestId(`day-status-${TODAY}`)).toBeTruthy())
    expect(within(page).queryByTestId('close-day-oldest')).toBeNull()
  })

  test('event page button: "Close day · <date>" when it opens an older day, "Close day" when it opens today', async () => {
    install(...closeDayHandlers([day(DAY1, 1, { needs_close: true }), day(TODAY, 2, { needs_close: true })]))
    renderAt(`/bazar/${EV}`)
    const btn = await screen.findByTestId('desktop-close-day')
    await waitFor(() => expect(btn.textContent).toMatch(/^Close day · .*20/))
    cleanup()
    install(...closeDayHandlers([day(DAY1, 1, { status: 'closed_balanced', close_count: 1 }), day(TODAY, 2, { needs_close: true })]))
    renderAt(`/bazar/${EV}`)
    const btn2 = await screen.findByTestId('desktop-close-day')
    await waitFor(() => expect((btn2 as HTMLButtonElement).disabled).toBe(false))
    expect(btn2.textContent).toBe('Close day')
  })
})

// ── bug 3 ───────────────────────────────────────────────────────────────────

function report(transfer: Partial<BazarReport['transfer']>): BazarReport {
  return {
    event: { id: EV, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: DAY1, ends_on: '2026-11-22',
             started_at: null, ended_at: null, location_name: 'Zamalek Bazar' },
    revenue: '2000', money_in: '2000', pieces_sold: 6, pieces_sent: 10, sell_through: '0.6', average_sale: '333',
    sales: 6, discounts: '0', returns: 0, exchanges: 0, sales_by_day: [], busiest_hours: [], top_products: [], top_sizes: [],
    payment_split: [], money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0',
      instapay_not_received: '0', total: '0', days_closed: 2, days_not_closed: 0 },
    per_seller: [], customers_saved: 0,
    transfer: { sent: 10, sold: 6, back: 2, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 2,
                transfer_id: 't-1', status: 'reconciling', balanced: false, ...transfer },
  } as BazarReport
}

function row(id: string, extra: Partial<BazarEventRow> = {}): BazarEventRow {
  return {
    id, name: `Bazar ${id}`, starts_on: '2026-11-10', ends_on: '2026-11-12', status: 'ended', location_id: `loc-${id}`,
    location_name: `Place ${id}`, transfer_id: `t-${id}`, transfer_status: 'closed', created_at: '2026-11-10T08:00:00Z',
    ...extra,
  }
}

describe('An ended bazar whose transfer is still open', () => {
  test('report: "2 pieces still out" + Finish the event → End event', async () => {
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, report({}))))
    renderAt(`/bazar/${EV}/report`)
    const callout = await screen.findByTestId('report-finish')
    expect(callout.textContent).toContain('2 pieces still out')
    expect(within(callout).getByTestId('report-finish-link').getAttribute('href')).toBe(`/bazar/${EV}/end-event`)
  })

  test('report: nothing out but not closed → "Close the transfer to finish"; closed → no callout', async () => {
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, report({ outstanding: 0, back: 4, balanced: true }))))
    renderAt(`/bazar/${EV}/report`)
    expect((await screen.findByTestId('report-finish')).textContent).toContain('Close the transfer to finish')
    cleanup()
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, report({ outstanding: 0, back: 4, balanced: true, status: 'closed' }))))
    renderAt(`/bazar/${EV}/report`)
    await screen.findByTestId('bazar-report')
    expect(screen.queryByTestId('report-finish')).toBeNull()
  })

  test('bazar list: the unfinished one says what is left and links to End event; a closed one does not', async () => {
    install(on('GET', '/bazar-events', () => json(200, [
      row('zam', { transfer_status: 'reconciling', pieces_out: 1, starts_on: '2026-11-17', ends_on: '2026-11-18' }),
      row('old')])))
    renderAt('/bazar')
    await screen.findByTestId('bazar-event-list')
    const links = await screen.findAllByTestId('bazar-finish-zam')
    for (const l of links) {
      expect(l.getAttribute('href')).toBe('/bazar/zam/end-event')
      expect(l.textContent).toContain('1 piece still out')
    }
    expect(screen.queryByTestId('bazar-finish-old')).toBeNull()
  })

  test('End event page, transfer closed: the green "Transfer closed" box carries the primary View report', async () => {
    install(
      on('GET', `/bazar-events/${EV}`, () => json(200, detail('ended', { transfer_status: 'closed' }))),
      on('GET', `/bazar-events/${EV}/leftovers`, () => json(200, {
        event_id: EV, event_status: 'ended', transfer_id: 't-1', transfer_status: 'closed', balanced: true,
        counts: { sent: 10, sold: 6, back: 4, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 0 },
        outstanding: [], damaged_to_bring_home: [] })),
    )
    renderAt(`/bazar/${EV}/end-event`)
    const box = await screen.findByTestId('transfer-closed')
    const link = within(box).getByTestId('end-view-report')
    expect(link.getAttribute('href')).toBe(`/bazar/${EV}/report`)
    expect(link.textContent).toBe('View report')
    expect(screen.getAllByTestId('end-view-report')).toHaveLength(1)
  })
})
