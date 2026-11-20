import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import CloseDayPage from '../pages/bazar/r3/CloseDayPage'
import EndEventPage from '../pages/bazar/r3/EndEventPage'
import { OwnerOnlyRoute } from '../App'
import { clearAccessToken, setAccessToken } from '../auth'

// Bazar R3 restyle — the event page (Get ready, live tabs, ended), the two approved extra reads (…/today on the 30 s
// loop, …/report on open and when Today is opened again; a failure hides its card only), Close today / More ▾ /
// Start selling, and the computer's Close day and End event pages (no modals). Owner and manager see the same page;
// a worker is sent away.

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EV = 'ev1'
const TODAY = '2026-11-21'
const jwt = (role: string) => `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: (url: string) => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []
function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn(url)
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: (url: string) => Promise<Response>): Handler => ({ method, path, fn })
const count = (path: string) => calls.filter(c => c.url.replace(/^.*\/api\/v1/, '').split('?')[0] === path).length

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname + l.search}</div> }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>
          <Route path="/bazar/:id" element={<BazarEventPage />} />
          <Route path="/bazar/:id/close-day" element={<><CloseDayPage /><Where /></>} />
          <Route path="/bazar/:id/end-event" element={<><EndEventPage /><Where /></>} />
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
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); cleanup() })

// ── fixtures ────────────────────────────────────────────────────────────────

function detail(status: 'draft' | 'live' | 'ended', over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status,
    location_id: 'l1', location_name: 'Zamalek Bazar', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: status === 'draft' ? 'preparing' : 'sent', transfer_sent_at: null, transfer_shopify_sync_mode: 'remove',
    payment_methods: ['cash', 'instapay', 'card'], seller_roles: ['owner', 'manager', 'worker'],
    discount_pct: '20', round_to: '5', started_at: null, ended_at: status === 'ended' ? '2026-11-22T20:09:00Z' : null,
    counts: { sent: 64, at_bazar: 24, sold: 38, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: { cash: '9840', instapay: '5600', card: '3480' }, total: '18920', sales: 23 },
    totals: { by_method: {}, total: '43020', sales: 47, returns: 2 },
    ...over,
  }
}
const sale = (id: string, title: string, price: string, over: Record<string, unknown> = {}) => ({
  id, sale_number: `S-${id}`, sold_at: '2026-11-21T16:44:00Z', business_day: TODAY, status: 'active', price, price_source: 'bulk',
  method: 'cash', piece_id: `p-${id}`, piece_code: `P${id}`, variant_id: 'v', product_title: title, variant_title: 'White / M',
  seller_id: 'u-omar', seller_name: 'Omar', customer_name: null, customer_phone: null, undone_at: null, ...over,
})
const SALES = { items: [sale('1', 'Linen shirt', '960'), sale('2', 'Knit scarf', '320', { status: 'returned' })], next_cursor: null }
const DAYS = { today: TODAY, payment_methods: ['cash', 'instapay', 'card'], days: [
  { business_day: '2026-11-20', day_number: 1, is_today: false, money: { by_method: {}, total: '24100', sales: 30 },
    status: 'closed_balanced', close: null, close_count: 1, opening_float_default: { amount: null, source: 'none' } },
  { business_day: TODAY, day_number: 2, is_today: true, money: { by_method: {}, total: '18920', sales: 23 },
    status: 'open', close: null, close_count: 0, opening_float_default: { amount: '1000.00', source: 'previous_close' } },
] }
const TODAY_SUMMARY = { business_day: TODAY, total: '18920', sale_count: 23, by_method: {}, per_seller: [
  { seller_id: 'u-omar', seller_name: 'Omar', sale_count: 11, total: '8640' }] }
const REPORT = { top_products: [{ product_title: 'Linen shirt', sold: 9, sent: 12, sell_through: '0.75', revenue: '8640' }],
  busiest_hours: [{ hour: 17, sales: 6 }, { hour: 18, sales: 7 }] }
const PRICES = [
  { variant_id: 'v1', sku: null, variant_title: 'White / M', product_title: 'Linen shirt', shopify_price: '1200', price: '960', source: 'bulk' },
  { variant_id: 'v2', sku: null, variant_title: 'Indigo', product_title: 'Denim jacket', shopify_price: '1850', price: '1400', source: 'override' },
  { variant_id: 'v3', sku: null, variant_title: null, product_title: 'Pleated skirt', shopify_price: '950', price: null, source: null },
]
const TRANSFER = { lines: [{ variant_id: 'v1', qty_out: 6 }, { variant_id: 'v2', qty_out: 2 }, { variant_id: 'v3', qty_out: 1 }] }

function liveHandlers(over: Partial<Record<'today' | 'report', () => Promise<Response>>> = {}, status: 'live' | 'ended' = 'live') {
  return [
    on('GET', `/bazar-events/${EV}`, () => json(200, detail(status))),
    on('GET', `/bazar-events/${EV}/sales`, () => json(200, SALES)),
    on('GET', `/bazar-events/${EV}/days`, () => json(200, DAYS)),
    on('GET', `/bazar-events/${EV}/today`, over.today ?? (() => json(200, TODAY_SUMMARY))),
    on('GET', `/bazar-events/${EV}/report`, over.report ?? (() => json(200, REPORT))),
    on('GET', `/bazar-events/${EV}/prices`, () => json(200, PRICES)),
    on('GET', `/transfers/t-1`, () => json(200, TRANSFER)),
    on('GET', `/bazar-events/${EV}/sellers`, () => json(200, { sellers: [], workers_allowed: true })),
    on('GET', `/bazar-events/${EV}/leftovers`, () => json(200, {
      event_id: EV, event_status: 'ended', transfer_id: 't-1', transfer_status: 'sent', balanced: false,
      counts: { sent: 64, sold: 38, back: 20, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 5 },
      outstanding: [], damaged_to_bring_home: [] })),
  ]
}

// ── Get ready ───────────────────────────────────────────────────────────────

describe('Get ready (draft)', () => {
  function renderDraft(over: Record<string, unknown> = {}) {
    install(
      on('GET', `/bazar-events/${EV}`, () => json(200, detail('draft', { counts: { sent: 9, at_bazar: 9, sold: 0, back: 0, lost: 0 }, ...over }))),
      on('GET', `/bazar-events/${EV}/prices`, () => json(200, PRICES)),
      on('GET', `/transfers/t-1`, () => json(200, TRANSFER)),
      on('GET', `/bazar-events/${EV}/sellers`, () => json(200, { sellers: [], workers_allowed: true })),
      on('POST', `/bazar-events/${EV}/mark-sent`, () => json(200, detail('draft', { transfer_status: 'sent' }))),
    )
    renderAt(`/bazar/${EV}`)
  }

  test('scan card counts, the value total, and the All / Custom / No price filters (display only)', async () => {
    renderDraft()
    const prices = await screen.findByTestId('bazar-prices')
    await within(prices).findByText('Linen shirt')
    // Shopify value = 1200×6 + 1850×2 + 950×1 = 11,850 → bazar value = 960×6 + 1400×2 + 0 = 8,560.
    await waitFor(() => expect(screen.getByTestId('bazar-price-totals').textContent).toContain('11,850'))
    expect(screen.getByTestId('bazar-price-totals').textContent).toContain('8,560 EGP')
    expect(screen.getByTestId('bazar-sent-count').textContent).toContain('9')
    expect(screen.getByTestId('bazar-transfer-card').textContent).toContain('8,560')
    await userEvent.click(screen.getByTestId('price-filter-custom'))
    expect(within(prices).queryByText('Linen shirt')).toBeNull()
    expect(within(prices).getByText('Denim jacket')).toBeTruthy()
    await userEvent.click(screen.getByTestId('price-filter-none'))
    expect(within(prices).getByText('Pleated skirt')).toBeTruthy()
    expect(within(prices).queryByText('Denim jacket')).toBeNull()
    await userEvent.click(screen.getByTestId('price-filter-all'))
    expect(within(prices).getAllByTestId(/^price-row-/)).toHaveLength(3)
    expect(screen.getByTestId('bazar-missing-prices')).toBeTruthy()
    // Filters never call the server.
    expect(calls.filter(c => c.method !== 'GET')).toHaveLength(0)
  })

  test('Scan pieces out opens the existing scanner; Mark as sent keeps its confirm; Start waits for sending', async () => {
    renderDraft()
    await screen.findByTestId('bazar-setup-bar')
    expect((screen.getByTestId('bazar-start') as HTMLButtonElement).disabled).toBe(true)
    await userEvent.click(screen.getByTestId('bazar-mark-sent'))
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)          // the confirm first
    await userEvent.click(screen.getByRole('button', { name: 'Mark as sent' }))
    await waitFor(() => expect(count(`/bazar-events/${EV}/mark-sent`)).toBe(1))
    await waitFor(() => expect((screen.getByTestId('bazar-start') as HTMLButtonElement).disabled).toBe(false))
  })

  test('the scan button goes to the full-screen scanner (no scanner on this page)', async () => {
    renderDraft()
    await screen.findByTestId('bazar-transfer-card')
    expect(document.querySelector('[data-testid="bazar-setup"] input[autofocus]')).toBeNull()
    await userEvent.click(within(screen.getByTestId('bazar-transfer-card')).getByRole('button', { name: /Scan more/ }))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EV}/scan-out`)
  })
})

// ── Live ────────────────────────────────────────────────────────────────────

describe('Live event page', () => {
  test('Today: the dark today block, whole bazar, yesterday, sold today (…/today), selling best + hours (…/report)', async () => {
    install(...liveHandlers())
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('bazar-today-money')).textContent).toBe('18,920 EGP')
    expect(screen.getByTestId('kpi-event-total').textContent).toContain('43,020')
    await waitFor(() => expect(screen.getByTestId('bazar-yesterday').textContent).toContain('24,100 · Balanced'))
    expect((await screen.findByTestId('bazar-sold-today')).textContent).toContain('11 sales · 8,640')
    expect((await screen.findByTestId('bazar-selling-best')).textContent).toContain('Linen shirt')
    expect(screen.getByTestId('bazar-hours').textContent).toContain('Sales by hour · whole bazar')
    expect(screen.getByTestId('bazar-live-sales').textContent).toContain('Returned')
    expect(screen.getByTestId('bazar-event-tag').className).not.toMatch(/rounded-full/)
    const tabs = within(screen.getByTestId('bazar-tabs')).getAllByRole('tab').map(t => t.textContent)
    expect(tabs).toEqual(['Today', 'Sales · 47', 'Days & cash', 'Pieces · 64', 'Prices', 'Sellers'])
    expect(screen.getByTestId('bazar-start-selling').getAttribute('href')).toBe(`/bazar/${EV}/sell`)
  })

  test('…/today and …/report failing (or the wrong shape) hide only their cards', async () => {
    install(...liveHandlers({ today: () => json(500, {}), report: () => json(200, [1, 2]) }))
    renderAt(`/bazar/${EV}`)
    await screen.findByTestId('bazar-today-money')
    await waitFor(() => expect(count(`/bazar-events/${EV}/report`)).toBe(1))
    await waitFor(() => expect(count(`/bazar-events/${EV}/today`)).toBe(1))
    await screen.findByText('Linen shirt')
    expect(screen.queryByTestId('bazar-sold-today')).toBeNull()
    expect(screen.queryByTestId('bazar-selling-best')).toBeNull()
    expect(screen.queryByTestId('bazar-hours')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByTestId('bazar-pieces')).toBeTruthy()
  })

  test('the report is read on open and again when Today is opened again — never on the 30 s loop; …/today is', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    install(...liveHandlers())
    renderAt(`/bazar/${EV}`)
    await screen.findByTestId('bazar-selling-best')
    expect(count(`/bazar-events/${EV}/report`)).toBe(1)
    expect(count(`/bazar-events/${EV}/today`)).toBe(1)
    await act(async () => { vi.advanceTimersByTime(30_000) })
    await waitFor(() => expect(count(`/bazar-events/${EV}/today`)).toBe(2))
    expect(count(`/bazar-events/${EV}/report`)).toBe(1)
    await userEvent.click(screen.getByRole('tab', { name: /Sales/ }))
    await userEvent.click(screen.getByRole('tab', { name: 'Today' }))
    await waitFor(() => expect(count(`/bazar-events/${EV}/report`)).toBe(2))
  })

  test('Close today → the Close day page (today, no modal); More ▾ → End event → the End event page', async () => {
    install(...liveHandlers(), on('GET', `/bazar-events/${EV}/days/${TODAY}/close-preview`, () => json(200, {
      business_day: TODAY, status: 'open', money: {}, opening_float_source: 'previous_close', needs_opening_float: false, problems: [],
      result: { opening_float: 1000, cash_kept: 1000, take_home: 0, not_received_total: 0, not_received: { cash: 0, card: 0, instapay: 0 },
                over_total: 0, methods: [] } })))
    renderAt(`/bazar/${EV}`)
    const close = await screen.findByTestId('desktop-close-day')
    await waitFor(() => expect((close as HTMLButtonElement).disabled).toBe(false))
    await userEvent.click(close)
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EV}/close-day`)
    const page = await screen.findByTestId('bazar-close-day-page')
    expect(within(page).getByTestId(`day-status-${TODAY}`)).toBeTruthy()
    expect(within(page).getByTestId('close-day-form')).toBeTruthy()
    expect(document.querySelector('.fixed.inset-0')).toBeNull()          // no modal overlay
    expect(within(page).getByTestId('close-day-picker')).toBeTruthy()
  })

  test('More ▾ → End event opens the End event page', async () => {
    install(...liveHandlers())
    renderAt(`/bazar/${EV}`)
    await userEvent.click(await screen.findByRole('button', { name: 'More' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'End event' }))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EV}/end-event`)
    expect(await screen.findByTestId('end-event-panel')).toBeTruthy()
  })

  test('tabs: Sales pages with the existing list; Sellers shows the sellers card; More → Invite sellers opens it', async () => {
    install(on('GET', `/bazar-events/${EV}/sales`, url => json(200, url.includes('cursor=')
      ? { items: [sale('9', 'Old skirt', '500')], next_cursor: null }
      : { ...SALES, next_cursor: 'c-2' })), ...liveHandlers())
    renderAt(`/bazar/${EV}`)
    await userEvent.click(await screen.findByRole('tab', { name: /Sales/ }))
    const tab = await screen.findByTestId('bazar-sales-tab')
    await within(tab).findByText('Linen shirt')
    await userEvent.click(screen.getByTestId('bazar-sales-more'))
    await within(tab).findByText('Old skirt')
    expect(calls.some(c => c.url.includes('cursor=c-2'))).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Invite sellers' }))
    expect(await screen.findByTestId('bazar-sellers-card')).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Sellers' }).getAttribute('aria-selected')).toBe('true')
  })

  test('a manager sees the same page as the owner', async () => {
    setAccessToken(jwt('manager'))
    install(...liveHandlers())
    renderAt(`/bazar/${EV}`)
    expect(await screen.findByTestId('desktop-close-day')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'More' })).toBeTruthy()
    expect(screen.getByTestId('bazar-start-selling')).toBeTruthy()
  })

  test('a worker never reaches the event page (sent to worker home)', () => {
    setAccessToken(jwt('worker'))
    install()
    render(
      <MemoryRouter initialEntries={[`/bazar/${EV}`]}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/bazar/:id" element={<OwnerOnlyRoute><BazarEventPage /></OwnerOnlyRoute>} />
            <Route path="*" element={<Where />} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>,
    )
    expect(screen.getByTestId('where').textContent).toBe('/worker-home')
  })
})

// ── Ended ───────────────────────────────────────────────────────────────────

describe('Ended event', () => {
  test('same tabs minus Prices / Sellers, View report, leftovers under Pieces with a link to End event', async () => {
    install(...liveHandlers({}, 'ended'))
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('view-report')).getAttribute('href')).toBe(`/bazar/${EV}/report`)
    const tabs = within(screen.getByTestId('bazar-tabs')).getAllByRole('tab').map(t => t.textContent)
    expect(tabs).toEqual(['Summary', 'Sales · 47', 'Days & cash', 'Pieces · 64'])
    expect(screen.queryByTestId('desktop-close-day')).toBeNull()
    expect(count(`/bazar-events/${EV}/today`)).toBe(0)
    await userEvent.click(screen.getByRole('tab', { name: /Pieces/ }))
    const left = await screen.findByTestId('bazar-leftovers')
    expect(within(left).getByRole('link', { name: /Bring leftovers back/ }).getAttribute('href')).toBe(`/bazar/${EV}/end-event`)
  })
})

// ── Computer pages ──────────────────────────────────────────────────────────

describe('Close day page', () => {
  test('?day= picks the day; the wide form is R2\'s form (same test ids)', async () => {
    install(...liveHandlers(), on('GET', `/bazar-events/${EV}/days/2026-11-20/close-preview`, () => json(200, {
      business_day: '2026-11-20', status: 'open', money: {}, opening_float_source: 'none', needs_opening_float: true, problems: [],
      result: { opening_float: 0, cash_kept: 0, take_home: 0, not_received_total: 0, not_received: { cash: 0, card: 0, instapay: 0 },
                over_total: 0, methods: [] } })))
    renderAt(`/bazar/${EV}/close-day?day=2026-11-20`)
    const page = await screen.findByTestId('bazar-close-day-page')
    expect(within(page).getByText('Close Day 1 · 20 Nov')).toBeTruthy()
    const form = within(page).getByTestId('close-day-form')
    expect(form.className).toMatch(/grid/)
    expect(within(form).getByTestId('close-cash')).toBe(document.activeElement)
    expect(within(form).getByTestId('close-drawer')).toBeTruthy()
    await userEvent.click(within(page).getByRole('button', { name: /Sat 21 Nov/ }))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EV}/close-day?day=${TODAY}`)
  })
})

describe('End event page', () => {
  test('ended, partly back: steps, header totals, the wide leftovers panel', async () => {
    install(...liveHandlers({}, 'ended'))
    renderAt(`/bazar/${EV}/end-event`)
    const page = await screen.findByTestId('bazar-end-event-page')
    const steps = within(page).getByTestId('end-steps').querySelectorAll('li')
    expect([...steps].map(s => s.getAttribute('data-state'))).toEqual(['done', 'current', 'todo', 'todo'])
    expect(within(page).getByTestId('end-header-totals').textContent).toContain('38')
    expect(within(page).getByTestId('leftovers-panel').className).toMatch(/grid/)
    expect((within(page).getByTestId('close-transfer') as HTMLButtonElement).disabled).toBe(true)
  })
})
