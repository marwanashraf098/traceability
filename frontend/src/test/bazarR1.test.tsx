import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarEvents from '../pages/bazar/BazarEvents'
import BazarNewEvent from '../pages/bazar/BazarNewEvent'
import { pickOverview } from '../pages/bazar/r1/overviewState'
import type { BazarEventRow } from '../bazar/api'
import { clearAccessToken, setAccessToken } from '../auth'

// Bazar R1 restyle — the overview's state choice (live → upcoming draft → past only → first time; several live;
// a draft whose date passed), the hero falling back to list-row fields when an optional read fails, and the
// New bazar calendar (keyboard + screen reader, Saturday-first, past days selectable, same request as before).

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

// Saturday 21 Nov 2026, noon in Cairo.
const NOW = new Date('2026-11-21T10:00:00Z')

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: () => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []

/** Exact path matching (query string ignored) — no prefix surprises between /bazar-events and /bazar-events/{id}. */
function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn()
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: () => Promise<Response>): Handler => ({ method, path, fn })

function Where() { return <div data-testid="where">{useLocation().pathname}</div> }

function renderAt(path: string, routes: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>{routes}</Routes>
      </I18nextProvider>
    </MemoryRouter>,
  )
}

function renderOverview() {
  return renderAt('/bazar', <>
    <Route path="/bazar" element={<BazarEvents />} />
    <Route path="*" element={<Where />} />
  </>)
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  clearAccessToken()
  setAccessToken(fakeJwt('owner'))
  testI18n.changeLanguage('en')
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  cleanup()
})

// ── fixtures ────────────────────────────────────────────────────────────────

function row(id: string, status: BazarEventRow['status'], starts_on: string, ends_on: string, extra: Partial<BazarEventRow> = {}): BazarEventRow {
  return {
    id, name: `Bazar ${id}`, starts_on, ends_on, status, location_id: `loc-${id}`, location_name: `Place ${id}`,
    transfer_id: `t-${id}`, transfer_status: status === 'draft' ? 'preparing' : status === 'live' ? 'sent' : 'closed',
    created_at: `${starts_on}T08:00:00Z`, ...extra,
  }
}

const detail = (id: string, over: Record<string, unknown> = {}) => ({
  ...row(id, 'live', '2026-11-20', '2026-11-22'),
  transfer_sent_at: null, transfer_shopify_sync_mode: 'remove', payment_methods: ['cash', 'instapay', 'card'],
  seller_roles: ['owner', 'manager', 'worker'], discount_pct: null, round_to: null, started_at: null, ended_at: null,
  counts: { sent: 160, at_bazar: 134, sold: 24, back: 2, lost: 0 },
  today: { business_day: '2026-11-21', by_method: {}, total: '18920', sales: 24 },
  totals: { by_method: {}, total: '43020', sales: 61 },
  ...over,
})

const days = {
  today: '2026-11-21', payment_methods: ['cash', 'instapay', 'card'],
  days: [
    { business_day: '2026-11-20', day_number: 1, is_today: false, money: { by_method: {}, total: '24100', sales: 30 },
      status: 'closed_balanced', close: null, close_count: 1, opening_float_default: { amount: null, source: 'none' } },
    { business_day: '2026-11-21', day_number: 2, is_today: true, money: { by_method: {}, total: '18920', sales: 24 },
      status: 'open', close: null, close_count: 0, opening_float_default: { amount: null, source: 'none' } },
    { business_day: '2026-11-22', day_number: 3, is_today: false, money: { by_method: {}, total: '0', sales: 0 },
      status: 'open', close: null, close_count: 0, opening_float_default: { amount: null, source: 'none' } },
  ],
}

const today = {
  business_day: '2026-11-21', total: '18920', sale_count: 24, by_method: {},
  per_seller: [
    { seller_id: 'u-omar', seller_name: 'Omar', sale_count: 11, total: '8640' },
    { seller_id: 'u-owner', seller_name: 'Nour', sale_count: 4, total: '3100' },
  ],
}

const report = (revenue: string) => ({
  event: {}, revenue, money_in: revenue, pieces_sold: 40, pieces_sent: 64, sell_through: '0.62', average_sale: null,
  sales: 47, returns: 0, exchanges: 0, sales_by_day: [], busiest_hours: [], top_products: [], top_sizes: [],
  payment_split: [], per_seller: [], customers_saved: 18, transfer: {},
  money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0', instapay_not_received: '840',
                        total: '840', days_closed: 3, days_not_closed: 0 },
})

// ── the state choice (pure) ─────────────────────────────────────────────────

describe('pickOverview', () => {
  const ended = row('old', 'ended', '2026-10-02', '2026-10-04')
  const ended2 = row('older', 'ended', '2026-09-01', '2026-09-02')
  const draft = row('next', 'draft', '2026-12-05', '2026-12-06')
  const live = row('now', 'live', '2026-11-20', '2026-11-22')

  test('live wins over upcoming and past', () => {
    const s = pickOverview([ended, draft, live])
    expect(s.kind).toBe('live')
    if (s.kind !== 'live') return
    expect(s.hero.id).toBe('now')
    expect(s.upcoming.map(r => r.id)).toEqual(['next'])
    expect(s.past.map(r => r.id)).toEqual(['old'])
  })

  test('no live → the earliest upcoming draft, with the newest ended as the last bazar', () => {
    const later = row('later', 'draft', '2027-01-10', '2027-01-11')
    const s = pickOverview([later, ended2, draft, ended])
    expect(s.kind).toBe('upcoming')
    if (s.kind !== 'upcoming') return
    expect(s.hero.id).toBe('next')
    expect(s.upcoming.map(r => r.id)).toEqual(['later'])
    expect(s.lastEnded?.id).toBe('old')
  })

  test('past only → the newest ended event', () => {
    const s = pickOverview([ended2, ended])
    expect(s.kind).toBe('past')
    if (s.kind === 'past') expect(s.hero.id).toBe('old')
  })

  test('nothing → first time', () => {
    expect(pickOverview([]).kind).toBe('empty')
  })

  test('several live → the earliest starts_on is the hero, the others are "also live"', () => {
    const a = row('a', 'live', '2026-11-21', '2026-11-23')
    const b = row('b', 'live', '2026-11-19', '2026-11-22')
    const s = pickOverview([a, b])
    expect(s.kind).toBe('live')
    if (s.kind !== 'live') return
    expect(s.hero.id).toBe('b')
    expect(s.alsoLive.map(r => r.id)).toEqual(['a'])
  })

  test('a draft whose date passed is still the next bazar', () => {
    const s = pickOverview([row('late', 'draft', '2026-11-18', '2026-11-19'), ended])
    expect(s.kind).toBe('upcoming')
  })
})

// ── the page ────────────────────────────────────────────────────────────────

describe('Overview page', () => {
  test('live: hero numbers from the detail, days and today reads; the name links to the event', async () => {
    const live = row('now', 'live', '2026-11-20', '2026-11-22')
    install(
      on('GET', '/bazar-events', () => json(200, [live, row('old', 'ended', '2026-10-02', '2026-10-04')])),
      on('GET', '/bazar-events/now', () => json(200, detail('now'))),
      on('GET', '/bazar-events/now/days', () => json(200, days)),
      on('GET', '/bazar-events/now/today', () => json(200, today)),
      // A normal bazar tenant: /me without B9b's bazarSetup (BAZAR_SIGNUP_ENABLED off).
      on('GET', '/me', () => json(200, { role: 'owner', bazar: true })),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-live')
    expect(within(hero).getByTestId('bazar-hero-tag').textContent).toBe('Live now · Day 2 of 3')
    expect((await within(hero).findByTestId('bazar-hero-today')).textContent).toBe('18,920 EGP')
    expect(within(hero).getByTestId('bazar-hero-sales-today').textContent).toBe('24sales today')
    expect(within(hero).getByText('Bazar now').closest('a')?.getAttribute('href')).toBe('/bazar/now')
    const sold = await within(hero).findByTestId('bazar-hero-sold-today')
    expect(sold.textContent).toContain('Sold today')
    expect(sold.textContent).toContain('Nour (you)')
    expect(sold.textContent).toContain('11 sales · 8,640')
    expect(within(await within(hero).findByTestId('bazar-hero-days')).getByTestId('hero-day-2026-11-20').textContent)
      .toContain('Closed · balanced')
    // Nothing the API doesn't give: no "sellers on now", no "last sale", no sparkline.
    expect(hero.textContent).not.toMatch(/sellers on|last sale|busiest/i)
    // The past list keeps the name as the row's link (ended → report).
    expect(screen.getByText('Bazar old').closest('a')?.getAttribute('href')).toBe('/bazar/old/report')
    // Only the three approved reads for the live hero — plus /me: B9b's reminder card asks the capabilities (in the
    // app the shell's one cached /me read per token; here the page renders without the shell). Exact, so any other
    // new request still fails this.
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/me'))).toBe(true))
    const reads = calls.map(c => c.url.replace(/^.*\/api\/v1/, '')).sort()
    expect(reads).toEqual(['/bazar-events', '/bazar-events/now', '/bazar-events/now/days', '/bazar-events/now/today', '/me'])
    // A normal tenant (B9b off): the Overview never asks /bazar-setup.
    await new Promise(r => setTimeout(r, 20))
    expect(calls.some(c => c.url.includes('/bazar-setup'))).toBe(false)
  })

  test('live hero falls back to the list-row fields when its reads fail — the rest of the page is unaffected', async () => {
    const live = row('now', 'live', '2026-11-20', '2026-11-22')
    install(
      on('GET', '/bazar-events', () => json(200, [live, row('old', 'ended', '2026-10-02', '2026-10-04')])),
      on('GET', '/bazar-events/now', () => json(500, { code: 'BOOM' })),
      on('GET', '/bazar-events/now/days', () => json(500, { code: 'BOOM' })),
      on('GET', '/bazar-events/now/today', () => Promise.reject(new TypeError('network'))),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-live')
    await waitFor(() => expect(calls.length).toBe(4))
    expect(within(hero).getByText('Bazar now')).toBeTruthy()
    expect(within(hero).getByTestId('bazar-hero-tag').textContent).toBe('Live now · Day 2 of 3')
    expect(within(hero).queryByTestId('bazar-hero-today')).toBeNull()
    expect(within(hero).queryByTestId('bazar-hero-days')).toBeNull()
    expect(within(hero).getByTestId('bazar-hero-open').getAttribute('href')).toBe('/bazar/now')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByTestId('bazar-event-list').textContent).toContain('Bazar old')
  })

  test('several live: the earliest is the hero, the others are listed under it with Open', async () => {
    install(
      on('GET', '/bazar-events', () => json(200, [
        row('a', 'live', '2026-11-21', '2026-11-23'), row('b', 'live', '2026-11-19', '2026-11-22')])),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-live')
    expect(within(hero).getByText('Bazar b')).toBeTruthy()
    const also = screen.getByTestId('bazar-also-live')
    expect(within(also).getByText('Bazar a').closest('a')?.getAttribute('href')).toBe('/bazar/a')
    expect(within(also).getByRole('link', { name: 'Open Bazar a' }).getAttribute('href')).toBe('/bazar/a')
  })

  test('upcoming: "Next bazar · in N days", the checklist from the detail + prices, and the last bazar from its report', async () => {
    const next = row('next', 'draft', '2026-12-05', '2026-12-06')
    install(
      on('GET', '/bazar-events', () => json(200, [next, row('old', 'ended', '2026-11-01', '2026-11-03')])),
      on('GET', '/bazar-events/next', () => json(200, detail('next', { status: 'draft', discount_pct: '15', counts: { sent: 0, at_bazar: 0, sold: 0, back: 0, lost: 0 } }))),
      on('GET', '/bazar-events/next/prices', () => json(200, [
        { variant_id: 'v1', sku: null, variant_title: 'M', product_title: 'Shirt', shopify_price: '100', price: '85', source: 'bulk' },
        { variant_id: 'v2', sku: null, variant_title: 'L', product_title: 'Shirt', shopify_price: '100', price: '90', source: 'override' },
      ])),
      on('GET', '/bazar-events/old/report', () => json(200, report('62780'))),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-next')
    expect(within(hero).getByTestId('bazar-hero-tag').textContent).toBe('Next bazar · in 14 days')
    await waitFor(() => expect(within(hero).getByTestId('step-prices').dataset.done).toBe('true'))
    expect(within(hero).getByTestId('step-prices').textContent).toContain('Everything −15% · 1 custom')
    expect(within(hero).getByTestId('step-created').dataset.done).toBe('true')
    expect(within(hero).getByTestId('step-sent').dataset.done).toBe('false')
    expect(within(hero).getByTestId('step-started').dataset.done).toBe('false')
    expect(within(hero).getByTestId('bazar-checklist-count').textContent).toBe('2 of 4 done')
    expect(within(hero).getByTestId('bazar-hero-continue').getAttribute('href')).toBe('/bazar/next')
    const last = within(hero).getByTestId('bazar-hero-last')
    expect((await within(last).findByTestId('bazar-last-revenue')).textContent).toBe('62,780 EGP')
    expect(last.textContent).toContain('not received (whole event)')
    expect(last.textContent).not.toMatch(/Fri difference|Bring more/i)
    // No inline Start / Invite actions on the overview.
    expect(within(hero).queryByRole('button')).toBeNull()
  })

  test('a draft whose date passed reads "date passed · N days ago" (never a negative "in N days"); start = today reads "starts today"', async () => {
    install(on('GET', '/bazar-events', () => json(200, [
      row('late', 'draft', '2026-11-18', '2026-11-19'), row('today', 'draft', '2026-11-21', '2026-11-21')])))
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-next')
    expect(within(hero).getByTestId('bazar-hero-tag').textContent).toBe('Next bazar · date passed · 3 days ago')
    expect(screen.getByTestId('bazar-coming-up').textContent).toContain('starts today')
    expect(document.body.textContent).not.toMatch(/in -\d/)
  })

  test('upcoming hero with failed reads still shows the event and the steps known from the list row', async () => {
    install(
      on('GET', '/bazar-events', () => json(200, [row('next', 'draft', '2026-12-05', '2026-12-06')])),
      on('GET', '/bazar-events/next', () => json(500, {})),
      on('GET', '/bazar-events/next/prices', () => json(500, {})),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-next')
    await waitFor(() => expect(within(hero).getByTestId('step-prices').textContent).toContain("Couldn't check right now"))
    expect(within(hero).getByTestId('step-created').dataset.done).toBe('true')
    expect(within(hero).getByTestId('bazar-checklist-count').textContent).toBe('1 of 4 done')
    expect(screen.queryByTestId('bazar-hero-last')).toBeNull()
  })

  test('past only: "First bazar done" with the report numbers and "+ Plan the next bazar" → the new bazar form', async () => {
    install(
      on('GET', '/bazar-events', () => json(200, [row('old', 'ended', '2026-11-01', '2026-11-03')])),
      on('GET', '/bazar-events/old/report', () => json(200, report('62780'))),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-past')
    expect(within(hero).getByTestId('bazar-hero-tag').textContent).toBe('First bazar done')
    expect((await within(hero).findByTestId('bazar-hero-revenue')).textContent).toBe('62,780 EGP')
    expect(hero.textContent).toContain('62%')
    expect(hero.textContent).toContain('customers with phone')
    expect(hero.textContent).not.toMatch(/from this|start from scratch/i)
    expect(within(hero).getByTestId('bazar-plan-next').getAttribute('href')).toBe('/bazar/new')
    expect(within(screen.getByTestId('bazar-event-list')).getByText('Bazar old').closest('a')?.getAttribute('href'))
      .toBe('/bazar/old/report')
  })

  test('past only with a failed report: the hero keeps the event and the plan button', async () => {
    install(
      on('GET', '/bazar-events', () => json(200, [row('old', 'ended', '2026-11-01', '2026-11-03')])),
      on('GET', '/bazar-events/old/report', () => json(500, {})),
    )
    renderOverview()
    const hero = await screen.findByTestId('bazar-hero-past')
    await waitFor(() => expect(calls.length).toBe(2))
    expect(within(hero).queryByTestId('bazar-hero-revenue')).toBeNull()
    expect(within(hero).getByTestId('bazar-plan-next')).toBeTruthy()
  })

  test('first time: no bazars → the first-time block, no extra reads', async () => {
    install(on('GET', '/bazar-events', () => json(200, [])))
    renderOverview()
    const block = await screen.findByTestId('bazar-empty')
    expect(block.textContent).toContain('Sell at pop-up bazars from your phone')
    expect(within(block).getByTestId('bazar-plan-first').getAttribute('href')).toBe('/bazar/new')
    expect(block.textContent).not.toMatch(/how it works/i)
    expect(calls).toHaveLength(1)
  })

  test('Arabic: Latin digits and LTR money in the hero', async () => {
    await testI18n.changeLanguage('ar')
    install(
      on('GET', '/bazar-events', () => json(200, [row('now', 'live', '2026-11-20', '2026-11-22')])),
      on('GET', '/bazar-events/now', () => json(200, detail('now'))),
    )
    renderOverview()
    const money = await screen.findByTestId('bazar-hero-today')
    expect(money.getAttribute('dir')).toBe('ltr')
    expect(money.textContent).toBe('18,920 EGP')
    expect(screen.getByTestId('bazar-hero-tag').textContent).toBe('جارٍ الآن · اليوم 2 من 3')
  })
})

// ── New bazar ───────────────────────────────────────────────────────────────

describe('New bazar form', () => {
  function renderForm() {
    install(
      on('GET', '/locations', () => json(200, [])),
      on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })),
    )
    return renderAt('/bazar/new', <>
      <Route path="/bazar/new" element={<BazarNewEvent />} />
      <Route path="/bazar/:id" element={<Where />} />
    </>)
  }

  test('the calendar starts the week on Saturday and labels each day with its full date', async () => {
    renderForm()
    const grid = await screen.findByRole('grid')
    const heads = within(grid).getAllByRole('columnheader').map(h => h.getAttribute('abbr'))
    expect(heads[0]).toBe('Saturday')
    expect(heads[6]).toBe('Friday')
    expect(within(grid).getByRole('button', { name: 'Saturday, 21 November 2026' })).toHaveAttribute('aria-current', 'date')
  })

  test('click the first day, then the last: the range is selected (aria-selected), past days are selectable', async () => {
    const user = userEvent.setup()
    renderForm()
    const grid = await screen.findByRole('grid')
    await user.click(within(grid).getByRole('button', { name: 'Thursday, 19 November 2026' }))   // in the past
    await user.click(within(grid).getByRole('button', { name: 'Sunday, 22 November 2026' }))
    const cell = (name: string) => within(grid).getByRole('button', { name }).closest('td')!
    expect(cell('Thursday, 19 November 2026')).toHaveAttribute('aria-selected', 'true')
    expect(cell('Saturday, 21 November 2026')).toHaveAttribute('aria-selected', 'true')
    expect(cell('Monday, 23 November 2026')).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByTestId('bazar-selling-days').textContent).toContain('4 selling days')

    await user.type(screen.getByLabelText('Event name'), 'Zamalek Winter Bazar')
    await user.type(screen.getByLabelText('Location name'), 'Zamalek Hall')
    await user.click(screen.getByRole('button', { name: 'Create · scan pieces out' }))
    expect(await screen.findByTestId('where')).toHaveTextContent('/bazar/new-ev')
    const post = calls.find(c => c.method === 'POST')
    expect(post?.body).toMatchObject({ startsOn: '2026-11-19', endsOn: '2026-11-22', locationName: 'Zamalek Hall' })
  })

  test('keyboard: arrows move the day, Enter picks the start then the end, PageDown changes month', async () => {
    renderForm()
    const grid = await screen.findByRole('grid')
    const today = within(grid).getByRole('button', { name: 'Saturday, 21 November 2026' })
    expect(today).toHaveAttribute('tabindex', '0')
    today.focus()
    fireEvent.keyDown(grid, { key: 'Enter' })                    // start = 21
    fireEvent.keyDown(grid, { key: 'ArrowRight' })               // 22
    fireEvent.keyDown(grid, { key: 'ArrowDown' })                // 29
    await waitFor(() => expect(document.activeElement?.getAttribute('aria-label')).toBe('Sunday, 29 November 2026'))
    fireEvent.keyDown(grid, { key: 'Enter' })                    // end = 29
    const cell = (name: string) => within(grid).getByRole('button', { name }).closest('td')!
    expect(cell('Wednesday, 25 November 2026')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('bazar-selling-days').textContent).toContain('9 selling days')
    // PageDown moves a month; the month title follows.
    fireEvent.keyDown(grid, { key: 'PageDown' })
    expect(await screen.findByText('December 2026')).toBeTruthy()
  })

  test('the preview bar and steps are display only; tags and boxes are square (6px), never pills', async () => {
    renderForm()
    const preview = await screen.findByTestId('bazar-preview')
    expect(preview.textContent).toContain('Cash · InstaPay · Card')
    expect(preview.textContent).toContain('3 roles')
    expect(screen.getByTestId('bazar-steps').textContent).toContain('Scan pieces out')
    expect(document.querySelector('[data-testid="bazar-new"] .rounded-full')).toBeNull()
  })
})
