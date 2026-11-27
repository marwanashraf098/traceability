import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import BazarNewEvent from '../pages/bazar/BazarNewEvent'
import { clearAccessToken, setAccessToken } from '../auth'

/**
 * Bazar U1d — Keep showing online only while Main reconcile writes for the store (keep_available).
 *   n1 New bazar (computer): keep_available false → Keep greyed out (disabled) + the note; a click on it changes
 *      nothing and the request carries no mode (Remove); keep_available true → unchanged (Keep selectable)
 *   n2 New bazar (phone): the pair's Keep button disabled + the note
 *   g1 Get ready's Change dialog: the detail's keep_available false → Keep disabled + the note
 *   a1 Arabic note
 */

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

type Handler = { method: string; path: string; fn: (url: string, body: unknown) => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []
function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn(url, body)
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: (url: string, body: unknown) => Promise<Response>): Handler => ({ method, path, fn })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname}</div> }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>
          <Route path="/bazar/new" element={<BazarNewEvent />} />
          <Route path="/bazar/:id" element={path === '/bazar/new' ? <Where /> : <BazarEventPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </I18nextProvider>
    </MemoryRouter>,
  )
}

function phone(on: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true, configurable: true,
    value: (query: string) => ({ matches: on && query.includes('max-width'), media: query, onchange: null,
      addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {},
      dispatchEvent: () => false }),
  })
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(jwt('owner'))
  testI18n.changeLanguage('en')
  phone(false)
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

function detail(status: 'draft' | 'live' | 'ended', over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status,
    location_id: 'l1', location_name: 'Zamalek Bazar', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: status === 'draft' ? 'preparing' : 'sent', transfer_sent_at: null,
    transfer_shopify_sync_mode: status === 'draft' ? null : 'remove', location_shopify_sync_mode: 'remove',
    online_stock: 'remove', online_stock_locked: status !== 'draft',
    payment_methods: ['cash'], seller_roles: ['owner'],
    discount_pct: null, round_to: null, started_at: null, ended_at: status === 'ended' ? '2026-11-22T20:09:00Z' : null,
    counts: { sent: 9, at_bazar: 9, sold: 0, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: { cash: '0' }, total: '0', sales: 0 },
    totals: { by_method: {}, total: '0', sales: 0, returns: 0 },
    ...over,
  }
}

function eventHandlers(ev: () => Record<string, unknown>) {
  return [
    on('GET', `/bazar-events/${EV}`, () => json(200, ev())),
    on('GET', `/bazar-events/${EV}/prices`, () => json(200, [])),
    on('GET', `/bazar-events/${EV}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EV}/days`, () => json(200, { today: TODAY, payment_methods: ['cash'], days: [] })),
    on('GET', `/bazar-events/${EV}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0, by_method: {}, per_seller: [] })),
    on('GET', `/bazar-events/${EV}/report`, () => json(200, { top_products: [], busiest_hours: [] })),
    on('GET', `/transfers/t-1`, () => json(200, { lines: [] })),
    on('GET', `/bazar-events/${EV}/sellers`, () => json(200, { sellers: [], workers_allowed: true })),
    on('GET', `/bazar-events/${EV}/leftovers`, () => json(200, {
      event_id: EV, event_status: 'ended', transfer_id: 't-1', transfer_status: 'sent', balanced: true,
      counts: { sent: 9, sold: 9, back: 0, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 0 },
      outstanding: [], damaged_to_bring_home: [] })),
  ]
}

const NOTE = en.bazar.u1d.keepUnavailable

function newBazar(keep: boolean | null) {
  install(
    on('GET', '/locations', () => json(200, [])),
    on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })),
    ...(keep == null ? [] : [on('GET', '/bazar-events/online-stock', () => json(200, { keep_available: keep }))]),
  )
  renderAt('/bazar/new')
}

describe('U1d — Keep showing online', () => {
  test('n1 New bazar: not available → Keep greyed out with the note; clicking it changes nothing; no mode sent', async () => {
    newBazar(false)
    const cards = await screen.findByTestId('bazar-online-stock')
    expect(await screen.findByTestId('bazar-keep-unavailable')).toHaveTextContent(NOTE)
    expect(NOTE).toBe("Not available for your store yet — pieces will be removed from online stock while they're at the bazar.")
    const keep = within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) })
    expect(keep).toBeDisabled()
    expect(screen.getByTestId('online-stock-leave')).toHaveAttribute('aria-disabled', 'true')
    const user = userEvent.setup()
    await user.click(within(cards).getByText(en.bazar.online.leave.title))
    expect(keep).not.toBeChecked()
    expect(within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.remove.title) })).toBeChecked()
    await user.type(screen.getByLabelText('Event name'), 'Garden Bazar')
    await user.type(screen.getByLabelText('Location name'), 'Garden Spot')
    await user.click(screen.getByRole('button', { name: 'Create · scan pieces out' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true))
    expect(calls.find(c => c.method === 'POST')!.body).not.toHaveProperty('shopifySyncMode')
  })

  test('n1 New bazar: available (or an older server without the flag) → Keep selectable, no note', async () => {
    for (const keep of [true, null]) {
      newBazar(keep)
      const cards = await screen.findByTestId('bazar-online-stock')
      await waitFor(() => expect(calls.some(c => c.url.includes('/bazar-events/online-stock'))).toBe(true))
      const user = userEvent.setup()
      await user.click(within(cards).getByText(en.bazar.online.leave.title))
      expect(within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) })).toBeChecked()
      expect(screen.queryByTestId('bazar-keep-unavailable')).not.toBeInTheDocument()
      cleanup()
    }
  })

  test('n2 New bazar (phone): the Keep button is disabled, with the note', async () => {
    phone(true)
    newBazar(false)
    const pair = await screen.findByTestId('bazar-online-stock')
    expect(await within(pair).findByTestId('bazar-keep-unavailable')).toHaveTextContent(NOTE)
    expect(within(pair).getByTestId('online-stock-leave')).toBeDisabled()
    await userEvent.setup().click(within(pair).getByTestId('online-stock-leave'))
    expect(within(pair).getByTestId('online-stock-leave')).toHaveAttribute('aria-checked', 'false')
  })

  test('g1 Get ready: Change → the dialog greys Keep out when the detail says keep_available false', async () => {
    install(...eventHandlers(() => detail('draft', { keep_available: false })))
    renderAt(`/bazar/${EV}`)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: en.bazar.online.change }))
    const dialog = await screen.findByTestId('bazar-online-dialog')
    expect(within(dialog).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) })).toBeDisabled()
    expect(within(dialog).getByTestId('bazar-keep-unavailable')).toHaveTextContent(NOTE)
  })

  test('a1 Arabic note', async () => {
    testI18n.changeLanguage('ar')
    newBazar(false)
    expect(await screen.findByTestId('bazar-keep-unavailable')).toHaveTextContent(ar.bazar.u1d.keepUnavailable)
    expect(ar.bazar.u1d.keepUnavailable).toBe('غير متاح لمتجرك بعد — ستُزال القطع من المخزون الأونلاين أثناء وجودها في البازار.')
  })
})
