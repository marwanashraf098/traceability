import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarSell from '../pages/bazar/phone/BazarSell'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
import { productNumber } from '../bazar/catalog'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
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

const sellRoutes = (
  <>
    <Route path="/bazar/:id/sell" element={<BazarSell />} />
    <Route path="/bazar/:id/home" element={<div data-testid="home-page">HOME</div>} />
  </>
)

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


// Bazar POS B7a / B7b — selling without barcodes: "#12" in the scan field, the Pick-a-product sheet (sizes, sold out
// faded, one tap = one sale through the quick-sale path), tapped cart lines, the computer's product grid, the price
// sheet from the phone menu, and the shared short-number vectors.

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

const sellCalls = () => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/sell`))
const sellRoute = <Route path="/bazar/:id/sell" element={<BazarSell />} />

describe('the short product number', () => {
  test('matches every shared vector (productNumber key) — the same rule as the backend', () => {
    const doc = JSON.parse(readFileSync(resolve(__dirname, '../../../shared/scan-normalization-vectors.json'), 'utf8'))
    const withKey = doc.vectors.filter((v: { productNumber?: unknown }) => 'productNumber' in v)
    expect(withKey.length).toBeGreaterThanOrEqual(10)
    for (const v of withKey) expect(productNumber(v.input), v.name).toBe(v.productNumber)
  })

  test('"#12" typed in the scan field opens that product\'s sizes — it never sells', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await screen.findByTestId('bazar-scan-input')
    await waitFor(() => expect(calls.some(c => c.url.includes('/catalog'))).toBe(true))
    await user.type(screen.getByTestId('bazar-scan-input'), '#12{Enter}')
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    const rows = within(sheet).getAllByTestId('pick-product')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('Linen shirt')
    expect(within(rows[0]).getAllByTestId('pick-size')).toHaveLength(3)
    expect(sellCalls()).toHaveLength(0)
    // Arabic digits too, and a bare "14".
    await user.click(within(sheet).getByRole('button', { name: 'Close' }))
    await user.type(screen.getByTestId('bazar-scan-input'), '١٤{Enter}')
    expect(within(await screen.findByTestId('bazar-pick-sheet')).getByTestId('pick-product')).toHaveTextContent('Wool cardigan')
    expect(sellCalls()).toHaveLength(0)
  })

  test('a piece code (P + 7 digits) still sells as a scan', async () => {
    install(...base(), on('POST', '/sell', () => json(200, soldSale('v-m'))))
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('bazar-scan-input'), 'P0000909{Enter}')
    await waitFor(() => expect(sellCalls()).toHaveLength(1))
    expect(sellCalls()[0].body).toMatchObject({ code: 'P0000909' })
    expect(screen.queryByTestId('bazar-pick-sheet')).not.toBeInTheDocument()
  })
})

describe('Pick a product (phone)', () => {
  test('sizes: sold out faded and disabled; one tap of Sell = one quick sale of that variant', async () => {
    install(...base(), on('POST', '/sell', () => json(200, soldSale('v-m'))))
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-pick'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    const sizes = within(sheet).getAllByTestId('pick-size')
    expect(sizes[0]).toBeDisabled()
    expect(sizes[0]).toHaveTextContent('Sold out')
    expect(sizes[0].className).toContain('opacity-50')
    expect(sizes[1]).toHaveTextContent('3 left')
    expect(within(sheet).getByTestId('pick-sell')).toBeDisabled()
    await user.click(sizes[1])
    expect(within(sheet).getByTestId('pick-sell')).toHaveTextContent('Sell White / M · 960')
    await user.click(within(sheet).getByTestId('pick-sell'))
    await waitFor(() => expect(sellCalls()).toHaveLength(1))
    const body = sellCalls()[0].body as Record<string, unknown>
    expect(body.variant_id).toBe('v-m')
    expect(body.code).toBeUndefined()
    expect(typeof body.client_request_id).toBe('string')
    expect(await screen.findByText('B-0101', { exact: false })).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-pick-sheet')).not.toBeInTheDocument()
  })

  test('search by name, colour and number; category chips', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-pick'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await waitFor(() => expect(within(sheet).getAllByTestId('pick-product')).toHaveLength(2))
    await user.type(within(sheet).getByTestId('pick-search'), 'beige')
    expect(within(sheet).getAllByTestId('pick-product')).toHaveLength(1)
    await user.clear(within(sheet).getByTestId('pick-search'))
    await user.type(within(sheet).getByTestId('pick-search'), '#12')
    expect(within(sheet).getByTestId('pick-product')).toHaveTextContent('Linen shirt')
    await user.clear(within(sheet).getByTestId('pick-search'))
    await user.click(within(sheet).getByRole('button', { name: 'Knitwear' }))
    expect(within(sheet).getByTestId('pick-product')).toHaveTextContent('Wool cardigan')
    await user.type(within(sheet).getByTestId('pick-search'), '#99')
    expect(within(sheet).getByTestId('pick-empty')).toHaveTextContent('#99')
  })

  test('a server "sold out" (another seller took the last one) shows clearly, nothing sold', async () => {
    install(...base(), on('POST', '/sell', () => json(409, { code: 'BAZAR_SOLD_OUT',
      message_en: 'Wool cardigan · Beige / S is sold out at this bazar.', message_ar: 'نفد',
      details: { variant_id: 'v-cs', left: 0 } })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-pick'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[1].querySelector('button')!)
    await user.click(within(sheet).getByTestId('pick-sell'))   // the only size is pre-selected
    const detail = await screen.findByText(/is sold out at this bazar/)
    expect(detail.closest('section, div')!.parentElement!.textContent).toContain('Sold out')
    expect(screen.queryByText('B-0101', { exact: false })).not.toBeInTheDocument()
  })

  test('cart mode: Add puts the size in the cart; preview and Complete send it as a tapped line', async () => {
    install(...base(),
      on('POST', '/cart/preview', () => json(200, { lines: [{ index: 0, code: null, piece_id: null, piece_code: null,
        variant_id: 'v-l', product_title: 'Linen shirt', variant_title: 'White / L', price: '960.00', error: null }],
        subtotal: '960.00', ok: true })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('radio', { name: 'Cart' }))
    await user.click(screen.getByTestId('open-pick'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    await user.click(within(sheet).getAllByTestId('pick-size')[2])
    expect(within(sheet).getByTestId('pick-sell')).toHaveTextContent('Add White / L · 960 to the cart')
    await user.click(within(sheet).getByTestId('pick-sell'))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/cart/preview'))).toBe(true))
    expect(calls.find(c => c.url.endsWith('/cart/preview'))!.body).toEqual({ lines: [{ variant_id: 'v-l' }] })
    expect(await screen.findByTestId('cart-line-tapped')).toBeInTheDocument()
    expect(screen.getByTestId('cart-line')).toHaveTextContent('Linen shirt')
    expect(sellCalls()).toHaveLength(0)
  })
})

describe('the computer product grid', () => {
  test('a tile per size; sold out disabled; a tap sells that size', async () => {
    install(...base(), on('POST', '/sell', () => json(200, soldSale('v-cs'))))
    renderAt(`/bazar/${EVENT}/sell`, sellRoute)
    const user = userEvent.setup()
    const grid = await screen.findByTestId('bazar-product-grid')
    const tiles = within(grid).getAllByTestId('grid-tile')
    expect(tiles).toHaveLength(4)
    expect(tiles[0]).toBeDisabled()
    expect(tiles[0]).toHaveTextContent('Sold out')
    expect(tiles[3]).toHaveTextContent('1 left')
    await user.click(tiles[3])
    await waitFor(() => expect(sellCalls()).toHaveLength(1))
    expect((sellCalls()[0].body as Record<string, unknown>).variant_id).toBe('v-cs')
  })
})

describe('price sheet from the phone menu', () => {
  test('any seller: the PDF is fetched in the screen language and shared as a file', async () => {
    const pdf = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
    const share = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, share, canShare: () => true })
    install(...base('worker'),
      on('GET', '/price-sheet.pdf', () => Promise.resolve({ ok: true, status: 200, statusText: '',
        headers: { get: () => 'application/pdf' }, blob: async () => pdf } as unknown as Response)))
    renderAt(`/bazar/${EVENT}/home`, <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('bazar-menu'))
    await user.click(screen.getByTestId('menu-price-sheet'))
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1))
    expect(calls.find(c => c.url.includes('/price-sheet.pdf'))!.url).toContain('lang=en')
    const arg = (share.mock.calls[0] as unknown as [{ files: File[] }])[0]
    expect(arg.files[0].name).toBe('bazar-price-sheet.pdf')
    expect(arg.files[0].type).toBe('application/pdf')
  })
})
