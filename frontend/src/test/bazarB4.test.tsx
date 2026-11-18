import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
import BazarCloseDay from '../pages/bazar/phone/BazarCloseDay'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import BazarSell from '../pages/bazar/phone/BazarSell'
import BazarExchange from '../pages/bazar/phone/BazarExchange'
import { parseAmount } from '../pages/bazar/CloseDayForm'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'

// Bazar POS B4 — close the day: Home's Close day (owner / manager only), the phone's close screen (first close asks
// the opening float; the preview request follows what's typed; the red "not received" box and its split; kept more
// than counted blocks the confirm; the close body; weak-signal retry with the SAME client_request_id; a later day
// starts from the carried-over float), and the desktop event page's days list with its status badges.

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TODAY = '2026-11-20'

function fakeJwt(role: 'owner' | 'manager' | 'worker', sub = `u-${role}`): string {
  return `h.${btoa(JSON.stringify({ role, sub }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

const LIVE = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
               paymentMethods: ['cash', 'card', 'instapay'] }

type Handler = (url: string, init: RequestInit | undefined) => Promise<Response> | undefined
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    for (const h of hs) {
      const r = h(url, init)
      if (r) return r
    }
    return json(404, {})
  }))
}

const on = (method: string, frag: string, fn: (url: string, init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.includes(frag) ? fn(url, init) : undefined)

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>
}

function renderAt(path: string, routes: React.ReactNode) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes>{routes}</Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

// ── fixtures ────────────────────────────────────────────────────────────────

const money = { by_method: { cash: '18200.00', card: '3400.00', instapay: '9850.00' }, total: '31450.00',
                sales: 4, returns: 0, exchanges: 0, revenue: '31450.00' }

function day(over: Record<string, unknown> = {}) {
  return { business_day: TODAY, day_number: 1, is_today: true, money, status: 'open', close: null, close_count: 0,
           opening_float_default: { amount: null, source: 'none' }, ...over }
}

/** The server's close calculation, mirrored for the stub (the real one is BazarDayService.calculate). */
function calc(q: URLSearchParams, floatDefault = 0) {
  const n = (k: string) => (q.get(k) == null ? null : Number(q.get(k)))
  const flt = n('float') ?? floatDefault
  const cash = n('cash') ?? 0, card = n('card') ?? 0, kept = n('kept') ?? flt
  const insta = q.get('instapay') !== 'false'
  const cashDiff = cash - (flt + 18200), cardDiff = card - 3400, instaDiff = insta ? 0 : -9850
  const short = (d: number) => Math.max(0, -d)
  const st = (d: number, ip = false) => (d === 0 ? 'even' : d > 0 ? 'over' : ip ? 'not_received' : 'short')
  return {
    business_day: TODAY, status: 'open', money,
    opening_float_source: q.get('float') != null ? 'entered' : floatDefault ? 'previous_close' : 'none',
    needs_opening_float: q.get('float') == null && !floatDefault,
    problems: q.get('cash') != null && kept > cash ? ['kept_more_than_counted'] : [],
    result: {
      opening_float: flt, cash_kept: kept, take_home: cash - kept,
      not_received_total: short(cashDiff) + short(cardDiff) + short(instaDiff),
      not_received: { cash: short(cashDiff), card: short(cardDiff), instapay: short(instaDiff) },
      over_total: Math.max(0, cashDiff) + Math.max(0, cardDiff),
      methods: [
        { method: 'cash', expected: flt + 18200, entered: cash, difference: cashDiff, state: st(cashDiff) },
        { method: 'card', expected: 3400, entered: card, difference: cardDiff, state: st(cardDiff) },
        { method: 'instapay', expected: 9850, entered: insta ? 9850 : 0, difference: instaDiff, received: insta,
          state: st(instaDiff, true) },
      ],
    },
  }
}

function owner(dayOver: Record<string, unknown> = {}, floatDefault = 0): Handler[] {
  setAccessToken(fakeJwt('owner'))
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true, bazarLive: LIVE })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0,
      by_method: {}, per_seller: [] })),
    on('GET', `/bazar-events/${EVENT}/days/${TODAY}/close-preview`, url =>
      json(200, calc(new URLSearchParams(url.split('?')[1]), floatDefault))),
    on('GET', `/bazar-events/${EVENT}/days`, () => json(200, { today: TODAY,
      payment_methods: ['cash', 'card', 'instapay'], days: [day(dayOver)] })),
  ]
}

const previews = () => calls.filter(c => c.url.includes('/close-preview'))
const lastPreview = () => new URLSearchParams(previews()[previews().length - 1].url.split('?')[1])
const closes = () => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/days/${TODAY}/close`))

async function type(testId: string, value: string) {
  const el = await screen.findByTestId(testId)
  await userEvent.clear(el)
  await userEvent.type(el, value)
}

const closeRoutes = (
  <>
    <Route path="/bazar/:id/close" element={<BazarCloseDay />} />
    <Route path="/bazar/:id/home" element={<><BazarPhoneHome /><Where /></>} />
  </>
)

// ── Home ────────────────────────────────────────────────────────────────────

describe('Phone home — Close day', () => {
  test('an owner opens the Close day screen', async () => {
    install(...owner())
    renderAt(`/bazar/${EVENT}/home`, <>
      <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />
      <Route path="/bazar/:id/close" element={<Where />} />
    </>)
    await userEvent.click(await screen.findByTestId('home-close-day'))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EVENT}/close`)
  })

  test('a worker has no Close day button', async () => {
    setAccessToken(fakeJwt('worker'))
    install(
      on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'worker', bazar: true, bazarLive: LIVE })),
      on('GET', `/bazar-events/${EVENT}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0,
        by_method: {}, per_seller: [] })),
    )
    renderAt(`/bazar/${EVENT}/home`, <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />)
    await screen.findByTestId('bazar-phone-home')
    expect(screen.queryByTestId('home-close-day')).toBeNull()
  })
})

// ── The close screen ────────────────────────────────────────────────────────

describe('Phone — Close the day', () => {
  test('the mockup example: 118 not received (cash 50, card 68), take-home 18,150, then the close body', async () => {
    install(...owner(), on('POST', `/days/${TODAY}/close`, () => json(200, day({ status: 'closed_short', close_count: 1,
      close: { id: 'c-1', closed_at: new Date().toISOString(), closed_by_name: 'Owner', note: null, supersedes_id: null,
               result: calc(new URLSearchParams('cash=19150&card=3332&instapay=true&float=1000')).result } }))))
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)

    await type('close-float', '1000')     // the bazar's first close asks the opening float
    await type('close-cash', '19150')
    await type('close-card', '3332')

    await waitFor(() => {
      const q = lastPreview()
      expect([q.get('cash'), q.get('card'), q.get('instapay'), q.get('float')]).toEqual(['19150', '3332', 'true', '1000'])
    })
    const box = await screen.findByTestId('close-not-received')
    await waitFor(() => expect(box.textContent).toContain('EGP 118 not received'))
    expect(within(box).getByText('Cash short').nextSibling?.textContent).toBe('50')
    expect(within(box).getByText(/Card short/).nextSibling?.textContent).toBe('68')
    expect(screen.getByTestId('close-take-home').textContent).toContain('Take home EGP 18,150 in cash')
    expect(screen.getByTestId('close-take-home').textContent).toContain('EGP 1,000 stays in the drawer')
    expect(screen.getByTestId('close-row-cash').textContent).toContain('Short 50')
    expect(screen.getByTestId('close-row-card').textContent).toContain('Short 68')
    expect(screen.getByTestId('close-row-instapay').textContent).toContain('Matches')

    await userEvent.click(screen.getByTestId('close-confirm'))
    await screen.findByTestId('close-done')
    expect(closes()).toHaveLength(1)
    const body = closes()[0].body as Record<string, unknown>
    expect(body).toMatchObject({ cash_counted: 19150, card_settlement: 3332, instapay_received: true, cash_kept: 1000,
                                 opening_float: 1000 })
    expect(typeof body.client_request_id).toBe('string')
  })

  test('InstaPay unticked → the whole InstaPay amount shows as not received', async () => {
    install(...owner())
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)
    await type('close-float', '1000')
    await type('close-cash', '19150')
    await type('close-card', '3332')
    await userEvent.click(screen.getByTestId('close-instapay'))
    await waitFor(() => expect(lastPreview().get('instapay')).toBe('false'))
    const box = await screen.findByTestId('close-not-received')
    await waitFor(() => expect(box.textContent).toContain('EGP 9,968 not received'))
    expect(within(box).getByText('InstaPay not received').nextSibling?.textContent).toBe('9,850')
  })

  test('cash over shows as over, never hidden', async () => {
    install(...owner())
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)
    await type('close-float', '1000')
    await type('close-cash', '19300')
    await type('close-card', '3400')
    await waitFor(() => expect(screen.getByTestId('close-over').textContent).toContain('EGP 100 over'))
    expect(screen.getByTestId('close-all-received')).toBeTruthy()
  })

  test('keeping more than counted blocks the confirm', async () => {
    install(...owner())
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)
    await type('close-float', '1000')
    await type('close-cash', '500')
    await type('close-card', '3400')
    await waitFor(() => expect(screen.getByText("You can't keep more cash than you counted.")).toBeTruthy())
    expect((screen.getByTestId('close-confirm') as HTMLButtonElement).disabled).toBe(true)
  })

  test('weak signal: the close is retried with the SAME request id until the server answers', async () => {
    let attempt = 0
    install(...owner(), on('POST', `/days/${TODAY}/close`, () => {
      attempt += 1
      return attempt === 1 ? Promise.reject(new TypeError('Failed to fetch'))
        : json(200, day({ status: 'closed_balanced', close_count: 1, close: { id: 'c', closed_at: TODAY,
            closed_by_name: 'O', note: null, supersedes_id: null,
            result: calc(new URLSearchParams('cash=19200&card=3400&instapay=true&float=1000')).result } }))
    }))
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)
    await type('close-float', '1000')
    await type('close-cash', '19200')
    await type('close-card', '3400')
    await waitFor(() => expect(lastPreview().get('cash')).toBe('19200'))
    await waitFor(() => expect((screen.getByTestId('close-confirm') as HTMLButtonElement).disabled).toBe(false))
    await userEvent.click(screen.getByTestId('close-confirm'))
    await screen.findByTestId('close-done')
    expect(closes()).toHaveLength(2)
    const ids = closes().map(c => (c.body as Record<string, unknown>).client_request_id)
    expect(ids[0]).toBe(ids[1])
  })

  test('a later day starts from the float kept at the last close — not asked again, not sent', async () => {
    install(...owner({ day_number: 2, opening_float_default: { amount: '800.00', source: 'previous_close' } }, 800),
      on('POST', `/days/${TODAY}/close`, () => json(200, day({ status: 'closed_balanced', close_count: 1,
        close: { id: 'c', closed_at: TODAY, closed_by_name: 'O', note: null, supersedes_id: null,
                 result: calc(new URLSearchParams('cash=19000&card=3400&instapay=true'), 800).result } }))))
    renderAt(`/bazar/${EVENT}/close`, closeRoutes)
    expect((await screen.findByTestId('close-float-line')).textContent).toContain('Drawer started with EGP 800')
    expect(screen.queryByTestId('close-float')).toBeNull()
    expect((screen.getByTestId('close-kept') as HTMLInputElement).value).toBe('800')
    await type('close-cash', '19000')
    await type('close-card', '3400')
    await waitFor(() => expect(lastPreview().get('cash')).toBe('19000'))
    expect(lastPreview().get('float')).toBeNull()
    await waitFor(() => expect((screen.getByTestId('close-confirm') as HTMLButtonElement).disabled).toBe(false))
    await userEvent.click(screen.getByTestId('close-confirm'))
    await screen.findByTestId('close-done')
    expect(closes()[0].body).not.toHaveProperty('opening_float')
    expect(closes()[0].body).toMatchObject({ cash_kept: 800 })
  })

  test('amounts: Arabic digits and thousands separators are read; junk is refused', () => {
    expect(parseAmount('١٩١٥٠')).toBe(19150)
    expect(parseAmount('19,150.50')).toBe(19150.5)
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('12a')).toBeNaN()
  })
})

// ── Desktop ─────────────────────────────────────────────────────────────────

function desktopHandlers(): Handler[] {
  const detail = {
    id: EVENT, name: 'Zamalek Winter Bazar', starts_on: '2026-11-19', ends_on: '2026-11-21', status: 'live',
    location_id: 'l', location_name: 'Hall', transfer_id: 't-1', transfer_status: 'sent', created_at: TODAY,
    transfer_sent_at: TODAY, transfer_shopify_sync_mode: 'remove', payment_methods: ['cash', 'card', 'instapay'],
    seller_roles: ['owner'], discount_pct: null, round_to: null, started_at: TODAY, ended_at: null,
    counts: { sent: 10, at_bazar: 6, sold: 4, back: 0, lost: 0 },
    today: { business_day: TODAY, ...money }, totals: money,
  }
  const closedDay = day({ business_day: '2026-11-19', day_number: 1, is_today: false, status: 'changed_after_close',
    close_count: 1, close: { id: 'c', closed_at: TODAY, closed_by_name: 'O', note: null, supersedes_id: null,
      result: calc(new URLSearchParams('cash=19200&card=3400&instapay=true&float=1000')).result } })
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })),
    on('GET', `/bazar-events/${EVENT}/days/${TODAY}/close-preview`, url =>
      json(200, calc(new URLSearchParams(url.split('?')[1]), 1000))),
    on('GET', `/bazar-events/${EVENT}/days`, () => json(200, { today: TODAY,
      payment_methods: ['cash', 'card', 'instapay'],
      days: [closedDay, day({ day_number: 2, opening_float_default: { amount: '1000.00', source: 'previous_close' } })] })),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/prices`, () => json(200, [{ variant_id: 'v-1', sku: 'LS-M',
      variant_title: 'White / M', product_title: 'Linen shirt', shopify_price: '1200.00', price: '960.00', source: 'bulk' }])),
    on('GET', `/transfers/t-1`, () => json(200, { lines: [] })),
    on('GET', `/bazar-events/${EVENT}`, () => json(200, detail)),
  ]
}

describe('Desktop event page — days', () => {
  test('lists each day with its status badge and opens the close form', async () => {
    setAccessToken(fakeJwt('owner'))
    install(...desktopHandlers())
    renderAt(`/bazar/${EVENT}`, <Route path="/bazar/:id" element={<BazarEventPage />} />)

    const days = await screen.findByTestId('bazar-days')
    await waitFor(() => expect(within(days).getByTestId('day-status-2026-11-19').textContent)
      .toBe('Changed after close — close again'))
    expect(within(days).getByTestId(`day-status-${TODAY}`).textContent).toBe('Open')
    await userEvent.click(within(within(days).getByTestId(`day-row-${TODAY}`)).getByText('Close this day'))
    expect(await screen.findByTestId('close-day-form')).toBeTruthy()
    expect(screen.getByTestId('close-float-line').textContent).toContain('EGP 1,000')
  })

  test("a live event's prices can be changed from its page — new sales use them, past ones keep theirs", async () => {
    setAccessToken(fakeJwt('owner'))
    install(...desktopHandlers())
    renderAt(`/bazar/${EVENT}`, <Route path="/bazar/:id" element={<BazarEventPage />} />)
    await screen.findByTestId('bazar-days')
    expect(screen.queryByTestId('bazar-prices')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Change prices' }))
    const prices = await screen.findByTestId('bazar-prices')
    expect(await within(prices).findByText(/Linen shirt/)).toBeTruthy()
    expect(within(prices).getByTestId('bazar-prices-live-note').textContent)
      .toContain('Sales already made keep the price they were sold at.')
  })
})

// ── B4 (from the B3 live test): a piece returned damaged can't be sold or given in an exchange ─────────────────

describe('Damaged piece', () => {
  const me = () => on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'worker', bazar: true, bazarLive: LIVE }))
  const quiet = [
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0,
      by_method: {}, per_seller: [] })),
  ]

  test('selling it shows "returned damaged", not "not at this bazar"', async () => {
    setAccessToken(fakeJwt('worker'))
    install(me(), ...quiet, on('POST', '/sell', () => json(409, { code: 'BAZAR_PIECE_DAMAGED',
      message_en: "This piece was returned damaged — it can't be sold.", message_ar: 'x' })))
    renderAt(`/bazar/${EVENT}/sell`, <Route path="/bazar/:id/sell" element={<BazarSell />} />)
    await userEvent.type(await screen.findByTestId('bazar-scan-input'), 'P0000001{Enter}')
    const card = await screen.findByTestId('sell-card-error')
    expect(card).toHaveTextContent("This piece was returned damaged — it can't be sold")
    expect(card).toHaveTextContent('It was taken back as damaged at this bazar. Put it aside.')
    expect(card).not.toHaveTextContent('Not at this bazar')
  })

  test('scanning it as the new piece of an exchange is refused the same way, nothing is sent', async () => {
    setAccessToken(fakeJwt('worker'))
    const sold = { piece_id: 'pc-old', piece_code: 'P0000001', product_title: 'Linen shirt', variant_title: 'White / M',
      price: '960.00', state: 'sold', sale: { id: 's-1', sale_number: 'B-0001', sold_at: new Date().toISOString(),
        business_day: TODAY, status: 'active', price: '960.00', price_source: 'bulk', method: 'cash', piece_id: 'pc-old',
        piece_code: 'P0000001', variant_id: 'v-1', product_title: 'Linen shirt', variant_title: 'White / M',
        seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null, undone_at: null } }
    const damaged = { piece_id: 'pc-dmg', piece_code: 'P0000002', product_title: 'Denim jacket', variant_title: 'Blue / M',
      price: '1200.00', state: 'returned', damaged: true, sale: { ...sold.sale, id: 's-2', status: 'returned',
        return_condition: 'damaged' } }
    install(me(), ...quiet, (url, init) => {
      if ((init?.method ?? 'GET') !== 'GET' || !url.includes('/piece?')) return undefined
      const code = new URL(url, 'http://x').searchParams.get('code')
      return json(200, code === 'P0000001' ? sold : damaged)
    })
    renderAt(`/bazar/${EVENT}/exchange`, <Route path="/bazar/:id/exchange" element={<BazarExchange />} />)
    await userEvent.type(await screen.findByTestId('bazar-code-input'), 'P0000001{Enter}')
    await screen.findByTestId('exchange-old')
    await userEvent.type(await screen.findByTestId('bazar-code-input'), 'P0000002{Enter}')
    await waitFor(() => expect(screen.getByTestId('cmd-error'))
      .toHaveTextContent("This piece was returned damaged — it can't be sold"))
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)
  })
})
