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
import BazarReportPage from '../pages/bazar/BazarReport'
import { clearAccessToken, setAccessToken } from '../auth'

/**
 * Bazar B8 — online stock per bazar.
 *   f1 New bazar (computer): section 5 "Online stock", Remove from online stock checked by default (no mode sent);
 *      Keep showing online → preview "Shown online", the keep note, the request carries shopifySyncMode 'leave'
 *   f2 New bazar (phone): the "Remove online · Default" / "Keep online" pair; leave locations are offered
 *   g1 Get ready: the online stock row; Change → the two cards → Save → PUT …/online-stock {mode}; the page updates;
 *      a refusal stays in the dialog; Keep's Mark-sent confirm says Shopify isn't touched
 *   g2 locked (sent): no Change, the locked note
 *   t1 tags: live and ended pages "Off online" / "Shown online" (the snapshot), the report too
 *   a1 Arabic for every new string
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
          <Route path="/bazar/:id/report" element={<BazarReportPage />} />
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

// ── f1 / f2 — the New bazar form ────────────────────────────────────────────

describe('New bazar — online stock', () => {
  const LOCATIONS = [
    { id: 'l-keep', name: 'Garden Hall', type: 'event', is_fulfillment: false, shopify_location_id: null, shopify_sync_mode: 'leave' },
    { id: 'main', name: 'Main Warehouse', type: 'warehouse', is_fulfillment: true, shopify_location_id: 'gid://x', shopify_sync_mode: 'remove' },
  ]

  test('f1 section 5: Remove by default (no mode sent) …', async () => {
    install(on('GET', '/locations', () => json(200, [])), on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })))
    renderAt('/bazar/new')
    const cards = await screen.findByTestId('bazar-online-stock')
    expect(screen.getByRole('heading', { name: en.bazar.online.title })).toBeInTheDocument()
    expect(within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.remove.title) })).toBeChecked()
    expect(within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) })).not.toBeChecked()
    expect(within(cards).getByText(en.bazar.online.leave.desc)).toBeInTheDocument()
    expect(screen.getByTestId('bazar-preview-online')).toHaveTextContent(en.bazar.online.remove.preview)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Event name'), 'Garden Bazar')
    await user.type(screen.getByLabelText('Location name'), 'Garden Spot')
    await user.click(screen.getByRole('button', { name: 'Create · scan pieces out' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true))
    expect(calls.find(c => c.method === 'POST')!.body).not.toHaveProperty('shopifySyncMode')
  })

  test('f1 … Keep showing online → preview, note and shopifySyncMode leave', async () => {
    install(on('GET', '/locations', () => json(200, [])), on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })))
    renderAt('/bazar/new')
    const user = userEvent.setup()
    const cards = await screen.findByTestId('bazar-online-stock')
    await user.click(within(cards).getByText(en.bazar.online.leave.title))
    expect(within(cards).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) })).toBeChecked()
    expect(screen.getByTestId('bazar-preview-online')).toHaveTextContent(en.bazar.online.leave.preview)
    expect(screen.getByTestId('bazar-keep-note')).toHaveTextContent(en.bazar.online.keepNote)
    expect(screen.queryByTestId('bazar-remove-note')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Event name'), 'Garden Bazar')
    await user.type(screen.getByLabelText('Location name'), 'Garden Spot')
    await user.click(screen.getByRole('button', { name: 'Create · scan pieces out' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true))
    expect(calls.find(c => c.method === 'POST')!.body).toMatchObject({ shopifySyncMode: 'leave', locationName: 'Garden Spot' })
  })

  test('f2 phone: the Remove online · Default / Keep online pair; a leave location is offered', async () => {
    phone(true)
    install(on('GET', '/locations', () => json(200, LOCATIONS)), on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })))
    renderAt('/bazar/new')
    const pair = await screen.findByTestId('bazar-online-stock')
    const remove = within(pair).getByRole('radio', { name: en.bazar.online.remove.phone })
    const keep = within(pair).getByRole('radio', { name: en.bazar.online.leave.phone })
    expect(remove).toHaveAttribute('aria-checked', 'true')
    const user = userEvent.setup()
    await user.click(keep)
    expect(keep).toHaveAttribute('aria-checked', 'true')
    expect(remove).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText(en.bazar.online.leave.desc)).toBeInTheDocument()
    // The leave-mode event location is no longer hidden.
    await user.click(screen.getByRole('button', { name: en.bazar.r1.form.change }))
    await user.click(screen.getByText(en.bazar.form.existingLocation))
    expect(screen.getByRole('option', { name: 'Garden Hall' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Main Warehouse' })).not.toBeInTheDocument()
  })
})

// ── g1 / g2 — Get ready ─────────────────────────────────────────────────────

describe('Get ready — online stock row', () => {
  test('g1 Change → Keep → Save → PUT; the row and the Mark-sent confirm follow', async () => {
    let current = detail('draft')
    install(...eventHandlers(() => current),
      on('PUT', `/bazar-events/${EV}/online-stock`, (_u, body) => {
        current = detail('draft', { online_stock: (body as { mode: string }).mode, location_shopify_sync_mode: (body as { mode: string }).mode })
        return json(200, current)
      }))
    renderAt(`/bazar/${EV}`)
    const row = await screen.findByTestId('bazar-online-row')
    expect(row).toHaveTextContent(en.bazar.online.remove.title)
    expect(within(row).getByTestId('bazar-online-tag')).toHaveTextContent(en.bazar.online.remove.tag)
    const user = userEvent.setup()
    await user.click(within(row).getByTestId('bazar-online-change'))
    const dialog = await screen.findByTestId('bazar-online-dialog')
    await user.click(within(dialog).getByText(en.bazar.online.leave.title))
    await user.click(within(dialog).getByRole('button', { name: en.bazar.online.save }))
    await waitFor(() => expect(screen.queryByTestId('bazar-online-dialog')).not.toBeInTheDocument())
    expect(calls.find(c => c.method === 'PUT')!.body).toEqual({ mode: 'leave' })
    expect(screen.getByTestId('bazar-online-row')).toHaveTextContent(en.bazar.online.leave.title)
    expect(screen.getByTestId('bazar-online-tag')).toHaveTextContent(en.bazar.online.leave.tag)
    // Mark as sent: Keep's confirm says Shopify isn't touched.
    await user.click(screen.getByTestId('bazar-mark-sent'))
    expect(await screen.findByText(/Your Shopify stock isn't touched/)).toBeInTheDocument()
    expect(screen.queryByText(/come off your online stock/)).not.toBeInTheDocument()
  })

  test('g1 a refusal (another unsent bazar) stays in the dialog', async () => {
    install(...eventHandlers(() => detail('draft')),
      on('PUT', `/bazar-events/${EV}/online-stock`, () => json(409, { code: 'BAZAR_ONLINE_STOCK_CONFLICT',
        message_en: 'Another bazar at this location has the other choice.', message_ar: 'بازار آخر' })))
    renderAt(`/bazar/${EV}`)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('bazar-online-change'))
    const dialog = await screen.findByTestId('bazar-online-dialog')
    await user.click(within(dialog).getByText(en.bazar.online.leave.title))
    await user.click(within(dialog).getByRole('button', { name: en.bazar.online.save }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Another bazar at this location has the other choice.')
  })

  test('g2 locked: no Change, the locked note', async () => {
    install(...eventHandlers(() => detail('draft', { transfer_status: 'sent', transfer_shopify_sync_mode: 'leave',
      online_stock: 'leave', online_stock_locked: true })))
    renderAt(`/bazar/${EV}`)
    const row = await screen.findByTestId('bazar-online-row')
    expect(row).toHaveTextContent(en.bazar.online.leave.title)
    expect(row).toHaveTextContent(en.bazar.online.locked)
    expect(within(row).queryByTestId('bazar-online-change')).not.toBeInTheDocument()
  })
})

// ── t1 — tags ───────────────────────────────────────────────────────────────

describe('tags', () => {
  test('t1 live page: "Shown online" from the snapshot; ended page: "Off online"', async () => {
    install(...eventHandlers(() => detail('live', { transfer_shopify_sync_mode: 'leave', online_stock: 'leave',
      location_shopify_sync_mode: 'remove' })))
    renderAt(`/bazar/${EV}`)
    expect(await screen.findByTestId('bazar-online-tag')).toHaveTextContent(en.bazar.online.leave.tag)
    cleanup()
    install(...eventHandlers(() => detail('ended')))
    renderAt(`/bazar/${EV}`)
    expect(await screen.findByTestId('bazar-online-tag')).toHaveTextContent(en.bazar.online.remove.tag)
  })

  test('t1 report: the tag next to the status', async () => {
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, {
      event: { id: EV, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: '2026-11-20', ends_on: '2026-11-22',
               started_at: null, ended_at: null, location_name: 'Zamalek Bazar', online_stock: 'leave' },
      revenue: '0', money_in: '0', pieces_sold: 0, pieces_sent: 0, sell_through: null, average_sale: null,
      sales: 0, discounts: '0', returns: 0, exchanges: 0, sales_by_day: [], busiest_hours: [], top_products: [],
      top_sizes: [], payment_split: [],
      money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0', instapay_not_received: '0',
                            total: '0', days_closed: 0, days_not_closed: 0 },
      per_seller: [], customers_saved: 0,
      transfer: { sent: 0, sold: 0, back: 0, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 0,
                  transfer_id: 't-1', status: 'closed', balanced: true },
    })))
    renderAt(`/bazar/${EV}/report`)
    expect(await screen.findByTestId('bazar-online-tag')).toHaveTextContent(en.bazar.online.leave.tag)
  })
})

// ── a1 — Arabic ─────────────────────────────────────────────────────────────

describe('Arabic', () => {
  test('a1 every online stock string exists in Arabic; the form section renders in Arabic', async () => {
    const walk = (e: Record<string, unknown>, a: Record<string, unknown>, path: string) => {
      for (const [k, v] of Object.entries(e)) {
        if (typeof v === 'string') {
          expect(a[k], `${path}.${k}`).toBeTruthy()
          expect(a[k], `${path}.${k}`).not.toBe(v)
        } else walk(v as Record<string, unknown>, (a[k] ?? {}) as Record<string, unknown>, `${path}.${k}`)
      }
    }
    walk(en.bazar.online, ar.bazar.online as unknown as Record<string, unknown>, 'bazar.online')
    expect(ar.bazar.transfer.confirmBodyKeep_other).toBeTruthy()
    testI18n.changeLanguage('ar')
    install(on('GET', '/locations', () => json(200, [])))
    renderAt('/bazar/new')
    const cards = await screen.findByTestId('bazar-online-stock')
    expect(within(cards).getByText(ar.bazar.online.remove.title)).toBeInTheDocument()
    expect(within(cards).getByText(ar.bazar.online.leave.desc)).toBeInTheDocument()
  })
})
