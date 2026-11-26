import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarReturn from '../pages/bazar/phone/BazarReturn'
import BazarExchange from '../pages/bazar/phone/BazarExchange'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'

// The camera is replaced by a button that "reads" a code — jsdom has no camera.
vi.mock('../pages/scanpair/CameraReader', () => ({
  default: ({ onRead }: { onRead: (c: string) => void }) => (
    <button type="button" onClick={() => onRead('P0000777')}>fake-camera-read</button>
  ),
}))

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

function me(role: string, live: unknown = LIVE) {
  return { name: 'X', email: 'x@y', role, bazar: true, ...(live ? { bazarLive: live } : {}) }
}

let n = 0
function sale(over: Partial<Record<string, unknown>> = {}) {
  n += 1
  return {
    id: `s-${n}`, sale_number: `B-${String(n).padStart(4, '0')}`, sold_at: new Date().toISOString(),
    business_day: TODAY, status: 'active', price: '960.00', price_source: 'bulk', method: 'cash',
    piece_id: `p-${n}`, piece_code: 'P0000001', variant_id: 'v-1', product_title: 'Linen shirt',
    variant_title: 'White / M', seller_id: 'u-worker', seller_name: 'Omar', customer_name: null,
    customer_phone: null, undone_at: null, replayed: false, ...over,
  }
}

type Handler = (url: string, init: RequestInit | undefined) => Promise<Response> | undefined
let handlers: Handler[] = []
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  handlers = hs
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    for (const h of handlers) {
      const r = h(url, init)
      if (r) return r
    }
    return json(404, {})
  }))
}

const on = (method: string, frag: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.includes(frag) ? fn(init) : undefined)

const sells = () => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/sell`))

function Where() {
  const loc = useLocation()
  return <div data-testid="where">{loc.pathname}</div>
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
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  sellTiming.undoCardMs = 8_000
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})


// Bazar POS B7c — returns / exchanges without a label: find the sale by product + size (+ phone / receipt), one match
// to confirm, several to pick from (masked customer), none; return / exchange sent by sale id; a tapped new piece.

const CATALOG = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live',
  categories: [{ name: 'Knitwear', count: 1 }, { name: 'Shirts', count: 1 }],
  products: [
    { number: 12, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: 'Shirts',
      left: 6, price_min: '960.00', price_max: '960.00', sizes: [
        { variant_id: 'v-s', title: 'White / S', size: 'S', price: '960.00', left: 0 },
        { variant_id: 'v-m', title: 'White / M', size: 'M', price: '960.00', left: 3 },
        { variant_id: 'v-l', title: 'White / L', size: 'L', price: '960.00', left: 3 },
      ] },
    { number: 14, product_id: 'p-cardigan', title: 'Wool cardigan', colour: 'Beige', image_url: null, category: 'Knitwear',
      left: 1, price_min: '800.00', price_max: '800.00', sizes: [
        { variant_id: 'v-cs', title: 'Beige / S', size: 'S', price: '800.00', left: 1 },
      ] },
  ],
}

function soldSale(variant: string) {
  return {
    id: 's-1', sale_number: 'B-0101', sold_at: new Date().toISOString(), business_day: TODAY, status: 'active',
    price: '960.00', price_source: 'bulk', method: 'cash', piece_id: 'pc-9', piece_code: 'P0000909', variant_id: variant,
    product_title: 'Linen shirt', variant_title: 'White / M', seller_id: 'u-worker', seller_name: 'Omar',
    customer_name: null, customer_phone: null, undone_at: null, replayed: false,
  }
}

function base(role: 'worker' | 'owner' = 'worker'): Handler[] {
  setAccessToken(fakeJwt(role))
  return [
    on('GET', '/api/v1/me', () => json(200, me(role))),
    on('GET', `/bazar-events/${EVENT}/catalog`, () => json(200, CATALOG)),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0,
      by_method: { cash: '0' }, per_seller: [] })),
  ]
}


function found(sales: unknown[], count = sales.length) {
  return { variant_id: 'v-s', count, sales }
}
function soldOf(id: string, number: string, over: Record<string, unknown> = {}) {
  return { ...soldSale('v-s'), id, sale_number: number, variant_title: 'White / S', price: '960.00', net_price: '960.00',
           customer_name: null, customer_phone: null, ...over }
}
const finds = () => calls.filter(c => c.method === 'POST' && c.url.includes('/sales/find'))
const posts = (path: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(path))

async function pickSize(user: ReturnType<typeof userEvent.setup>, productIndex: number, sizeIndex: number) {
  const sheet = await screen.findByTestId('bazar-pick-sheet')
  await user.click(within(sheet).getAllByTestId('pick-product')[productIndex].querySelector('button')!)
  await user.click(within(sheet).getAllByTestId('pick-size')[sizeIndex])
  await user.click(within(sheet).getByTestId('pick-sell'))
}

describe('return without a label', () => {
  test('find by size (a sold-out size too) → one match → confirm → the return goes by sale id', async () => {
    install(on('POST', '/sales/find', () => json(200, found([soldOf('s-7', 'B-0107', { customer_name: 'Mona', customer_phone: '010•••••678' })]))),
      on('POST', '/return', () => json(200, { sale: { ...soldOf('s-7', 'B-0107'), status: 'returned', return_condition: 'good' },
        refund: { method: 'cash', amount: '960.00' }, replayed: false })), ...base())
    renderAt(`/bazar/${EVENT}/return`, <Route path="/bazar/:id/return" element={<BazarReturn />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('return-find'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    expect(sheet).toHaveTextContent('Find the sale')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    const sizes = within(sheet).getAllByTestId('pick-size')
    expect(sizes[0]).toBeEnabled()   // S is sold out at the bazar — exactly the size that can come back
    await user.click(sizes[0])
    expect(within(sheet).getByTestId('pick-sell')).toHaveTextContent('Find sales of White / S')
    await user.click(within(sheet).getByTestId('pick-sell'))
    await user.click(await screen.findByTestId('find-go'))
    expect(finds()[0].body).toMatchObject({ variant_id: 'v-s' })
    const one = await screen.findByTestId('find-one')
    expect(one).toHaveTextContent('B-0107')
    expect(one).toHaveTextContent('010•••••678')
    await user.click(within(one).getByTestId('find-use'))
    expect(await screen.findByTestId('return-sale')).toHaveTextContent('Linen shirt')
    await user.click(screen.getByTestId('return-confirm'))
    await waitFor(() => expect(posts('/return')).toHaveLength(1))
    const body = posts('/return')[0].body as Record<string, unknown>
    expect(body.sale_id).toBe('s-7')
    expect(body.code).toBeUndefined()
    expect(body).toMatchObject({ condition: 'good', method: 'cash' })
    expect(await screen.findByTestId('return-done')).toBeInTheDocument()
  })

  test('several matches are listed (never one picked for the seller); the seller picks the right one', async () => {
    install(on('POST', '/sales/find', () => json(200, found([
        soldOf('s-9', 'B-0109', { seller_name: 'Hana' }), soldOf('s-8', 'B-0108', { customer_phone: '011•••••321' })], 14))),
      on('POST', '/return', () => json(200, { sale: { ...soldOf('s-8', 'B-0108'), status: 'returned' }, refund: null })), ...base())
    renderAt(`/bazar/${EVENT}/return`, <Route path="/bazar/:id/return" element={<BazarReturn />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('return-find'))
    await pickSize(user, 0, 0)
    await user.click(await screen.findByTestId('find-go'))
    const list = await screen.findByTestId('find-several')
    expect(list).toHaveTextContent('14 sales match')
    expect(list).toHaveTextContent('Showing the newest 2 of 14')
    expect(screen.queryByTestId('return-sale')).not.toBeInTheDocument()
    // Several: only the list — no single "use this sale" offered for the seller to accept blindly.
    expect(screen.queryByTestId('find-one')).not.toBeInTheDocument()
    expect(screen.queryByTestId('find-use')).not.toBeInTheDocument()
    const rows = within(list).getAllByTestId('find-row')
    expect(rows[1]).toHaveTextContent('011•••••321')
    await user.click(rows[1])
    await user.click(await screen.findByTestId('return-confirm'))
    await waitFor(() => expect(posts('/return')).toHaveLength(1))
    expect((posts('/return')[0].body as Record<string, unknown>).sale_id).toBe('s-8')
  })

  test('none → says so, nothing to confirm; phone and receipt narrow the search', async () => {
    install(on('POST', '/sales/find', () => json(200, found([]))), ...base())
    renderAt(`/bazar/${EVENT}/return`, <Route path="/bazar/:id/return" element={<BazarReturn />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('return-find'))
    await pickSize(user, 1, 0)
    await user.type(await screen.findByTestId('find-phone'), '01012345678')
    await user.type(screen.getByTestId('find-receipt'), 'B-0057')
    await user.click(screen.getByTestId('find-go'))
    expect(await screen.findByTestId('find-none')).toHaveTextContent('No sale of Wool cardigan · Beige / S')
    expect(finds()[0].url).not.toContain('?')
    expect(finds()[0].body).toEqual({ variant_id: 'v-cs', phone: '01012345678', receipt: 'B-0057' })
    expect(screen.queryByTestId('return-confirm')).not.toBeInTheDocument()
  })
})

describe('exchange without a label', () => {
  test('the old piece by its sale, the new one tapped → one exchange request with both ids', async () => {
    install(on('POST', '/sales/find', () => json(200, found([soldOf('s-3', 'B-0103', { price: '800.00', net_price: '800.00',
        product_title: 'Wool cardigan', variant_title: 'Beige / S' })]))),
      on('POST', '/exchange', () => json(200, { old_sale: { ...soldOf('s-3', 'B-0103'), status: 'returned' },
        new_sale: soldOf('s-4', 'B-0104'), difference: '160.00', money: { kind: 'payment', method: 'cash', amount: '160.00' } })), ...base())
    renderAt(`/bazar/${EVENT}/exchange`, <Route path="/bazar/:id/exchange" element={<BazarExchange />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('exchange-find'))
    await pickSize(user, 1, 0)
    await user.click(await screen.findByTestId('find-go'))
    await user.click(within(await screen.findByTestId('find-one')).getByTestId('find-use'))
    expect(await screen.findByTestId('exchange-old')).toHaveTextContent('Wool cardigan')
    await user.click(screen.getByTestId('exchange-pick'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    expect(within(sheet).getAllByTestId('pick-size')[0]).toBeDisabled()   // nothing of S left to give
    await user.click(within(sheet).getAllByTestId('pick-size')[1])
    expect(within(sheet).getByTestId('pick-sell')).toHaveTextContent('Use White / M · 960')
    await user.click(within(sheet).getByTestId('pick-sell'))
    expect(await screen.findByTestId('exchange-new')).toHaveTextContent('Linen shirt · White / M')
    expect(screen.getByTestId('exchange-difference')).toHaveTextContent('160')
    await user.click(screen.getByTestId('exchange-confirm'))
    await waitFor(() => expect(posts('/exchange')).toHaveLength(1))
    const body = posts('/exchange')[0].body as Record<string, unknown>
    expect(body).toMatchObject({ old_sale_id: 's-3', new_variant_id: 'v-m', condition: 'good', method: 'cash' })
    expect(body.old_code).toBeUndefined()
    expect(body.new_code).toBeUndefined()
    expect(await screen.findByTestId('exchange-done')).toBeInTheDocument()
  })
})

describe('exchange of a discounted cart line', () => {
  test('the difference before confirm is what the server credits (the line\'s net price), = the amount after', async () => {
    // A cart line sold at 960 with 60 of the cart's discount on it: the server credits 900 (B6c), so a 960
    // replacement costs the customer 60 — not 0.
    const cartLine = soldOf('s-21', 'B-0121', { price: '960.00', net_price: '900.00', discount: '60.00', receipt_id: 'r-9',
      line_no: 2, piece_code: 'P0000021' })
    install(
      on('GET', '/piece?code=P0000021', () => json(200, { piece_id: 'pc-21', piece_code: 'P0000021',
        product_title: 'Linen shirt', variant_title: 'White / S', price: '960.00', state: 'sold', sale: cartLine, damaged: false })),
      on('GET', '/piece?code=P0000031', () => json(200, { piece_id: 'pc-31', piece_code: 'P0000031',
        product_title: 'Linen shirt', variant_title: 'White / M', price: '960.00', state: 'for_sale', sale: null, damaged: false })),
      on('POST', '/exchange', () => json(200, { old_sale: { ...cartLine, status: 'returned' },
        new_sale: soldOf('s-22', 'B-0122', { variant_title: 'White / M' }), difference: '60.00',
        money: { kind: 'payment', method: 'cash', amount: '60.00' } })),
      ...base())
    renderAt(`/bazar/${EVENT}/exchange`, <Route path="/bazar/:id/exchange" element={<BazarExchange />} />)
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('bazar-code-input'), 'P0000021{Enter}')
    expect(await screen.findByTestId('exchange-old')).toHaveTextContent('900')
    await user.type(screen.getByTestId('bazar-code-input'), 'P0000031{Enter}')
    const banner = await screen.findByTestId('exchange-difference')
    expect(banner).toHaveTextContent('Customer pays')
    expect(banner).toHaveTextContent('60')
    const before = banner.textContent!.match(/[\d,]+(?:\.\d+)?/)![0]
    await user.click(screen.getByTestId('exchange-confirm'))
    const after = await screen.findByTestId('exchange-done-money')
    expect(after.textContent!.match(/[\d,]+(?:\.\d+)?/)![0]).toBe(before)
  })
})
