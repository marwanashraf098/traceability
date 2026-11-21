import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarSell from '../pages/bazar/phone/BazarSell'
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


// Bazar POS B6c — cart mode on the Sell screen.

function todaySummary(over: Partial<Record<string, unknown>> = {}) {
  return { business_day: TODAY, total: '0.00', sale_count: 0, by_method: { cash: '0.00' }, per_seller: [], ...over }
}

const PRICES: Record<string, { title: string; size: string; price: string }> = {
  P0000001: { title: 'Linen shirt', size: 'M', price: '500.00' },
  P0000002: { title: 'Linen shirt', size: 'L', price: '800.00' },
  P0000003: { title: 'Scarf', size: '', price: '300.00' },
}

/** The server's preview: a known code is a line with its price; P0009999 isn't at this bazar. */
function previewOf(codes: string[]) {
  let subtotal = 0
  const lines = codes.map((code, index) => {
    const p = PRICES[code]
    if (!p) {
      return { index, code, error: { code: 'BAZAR_PIECE_NOT_HERE', message_en: 'Not here', message_ar: 'ليست هنا' } }
    }
    subtotal += Number(p.price)
    return { index, code, piece_id: `pc-${code}`, piece_code: code, variant_id: `v-${code}`, product_title: p.title,
             variant_title: p.size || null, price: p.price, error: null }
  })
  return { lines, subtotal: subtotal.toFixed(2), ok: lines.every(l => !l.error) && lines.length > 0 }
}

function receipt(over: Partial<Record<string, unknown>> = {}) {
  return {
    kind: 'receipt', id: 'r-1', sale_number: 'B-0057', sold_at: new Date().toISOString(), business_day: TODAY,
    status: 'active', subtotal: '1300.00', discount: '100.00', total: '1200.00', price: '1200.00', method: 'card',
    seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null, undone_at: null,
    line_count: 2,
    lines: [
      { id: 'l-1', kind: 'sale', sale_number: 'B-0057', sold_at: new Date().toISOString(), business_day: TODAY,
        status: 'active', price: '500.00', net_price: '462.00', discount: '38.00', price_source: 'default',
        method: 'card', piece_id: 'pc-1', piece_code: 'P0000001', variant_id: 'v', product_title: 'Linen shirt',
        variant_title: 'M', seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null,
        undone_at: null, receipt_id: 'r-1', line_no: 1 },
      { id: 'l-2', kind: 'sale', sale_number: 'B-0057', sold_at: new Date().toISOString(), business_day: TODAY,
        status: 'active', price: '800.00', net_price: '738.00', discount: '62.00', price_source: 'default',
        method: 'card', piece_id: 'pc-2', piece_code: 'P0000002', variant_id: 'v', product_title: 'Linen shirt',
        variant_title: 'L', seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null,
        undone_at: null, receipt_id: 'r-1', line_no: 2 },
    ],
    replayed: false, ...over,
  }
}

function sellBase(role: 'worker' | 'owner' = 'worker', list: unknown[] = []): Handler[] {
  setAccessToken(fakeJwt(role))
  return [
    on('GET', '/api/v1/me', () => json(200, me(role))),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: list, next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, todaySummary())),
    on('POST', '/cart/preview', init => json(200, previewOf((JSON.parse(String(init?.body)) as { codes: string[] }).codes))),
  ]
}

const completes = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/cart/complete'))
const previews = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/cart/preview'))

async function openCart() {
  renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('radio', { name: 'Cart' }))
  return user
}

async function add(user: ReturnType<typeof userEvent.setup>, code: string) {
  await user.type(screen.getByTestId('bazar-scan-input'), `${code}{Enter}`)
}

describe('Cart mode', () => {
  test('Quick is the default; in Cart a scan only joins the list — nothing is sold until Complete', async () => {
    install(...sellBase())
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    expect(await screen.findByRole('radio', { name: 'Quick' })).toHaveAttribute('aria-checked', 'true')
    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: 'Cart' }))
    await add(user, 'P0000001')
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getAllByTestId('cart-line')).toHaveLength(2))
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('1,300'))
    expect(screen.getAllByTestId('cart-line')[1]).toHaveTextContent('Linen shirt · L')
    expect(calls.filter(c => c.url.endsWith('/sell'))).toHaveLength(0)
    expect(completes()).toHaveLength(0)
    expect((previews().at(-1)!.body as { codes: string[] }).codes).toEqual(['P0000001', 'P0000002'])
    // The same code again is not added twice.
    await add(user, 'P0000001')
    expect(screen.getByTestId('cart-notice')).toHaveTextContent('P0000001 is already in the cart.')
    expect(screen.getAllByTestId('cart-line')).toHaveLength(2)
  })

  test('remove (×) takes a line out; the discount updates the total; a discount over the subtotal blocks Complete', async () => {
    install(...sellBase())
    const user = await openCart()
    await add(user, 'P0000001')
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('1,300'))
    await user.click(screen.getByRole('button', { name: 'Remove Linen shirt · M from the cart' }))
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('800'))
    await user.type(screen.getByTestId('cart-discount'), '50')
    expect(screen.getByTestId('cart-total')).toHaveTextContent('750')
    expect(screen.getByTestId('cart-complete')).toHaveTextContent('Charge 750 EGP · Cash')
    expect(screen.getByTestId('cart-complete')).toBeEnabled()
    await user.clear(screen.getByTestId('cart-discount'))
    await user.type(screen.getByTestId('cart-discount'), '900')
    expect(screen.getByTestId('cart-complete')).toBeDisabled()
    expect(screen.getByText('Whole pounds, from 0 up to 800.')).toBeInTheDocument()
  })

  test('a piece the preview refuses is marked and Complete stays off until it is removed', async () => {
    install(...sellBase())
    const user = await openCart()
    await add(user, 'P0000001')
    await add(user, 'P0009999')
    const err = await screen.findByTestId('cart-line-error')
    expect(err).toHaveTextContent('Not at this bazar')
    expect(screen.getByTestId('cart-complete')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Remove P0009999 from the cart' }))
    await waitFor(() => expect(screen.getByTestId('cart-complete')).toBeEnabled())
  })

  test('Complete: one request with the codes, discount, method and customer → SOLD card, the cart empties', async () => {
    install(...sellBase(), on('POST', '/cart/complete', () => json(200, receipt())))
    const user = await openCart()
    await add(user, 'P0000001')
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getByTestId('cart-complete')).toBeEnabled())
    await user.type(screen.getByTestId('cart-discount'), '100')
    await user.click(within(screen.getByTestId('cart-summary')).getByRole('button', { name: 'Card' }))
    await user.click(screen.getByLabelText('Name'))
    await user.type(screen.getByLabelText('Name'), 'Mona')
    await user.type(screen.getByLabelText('Mobile number'), '01012345678')
    await user.click(screen.getByTestId('cart-complete'))
    const card = await screen.findByTestId('cart-card-sold')
    expect(card).toHaveTextContent('SOLD · B-0057')
    expect(card).toHaveTextContent('1,200')
    expect(completes()).toHaveLength(1)
    const body = completes()[0].body as Record<string, unknown>
    expect(body.codes).toEqual(['P0000001', 'P0000002'])
    expect(body.discount).toBe(100)
    expect(body.method).toBe('card')
    expect(body.customer).toEqual({ name: 'Mona', phone: '01012345678' })
    expect(body.client_request_id).toMatch(/.+/)
    expect(screen.getByTestId('cart-empty')).toBeInTheDocument()
  })

  test('Complete refused for one piece: that line says why and the cart stays as it was', async () => {
    install(...sellBase(), on('POST', '/cart/complete', () => json(409, {
      code: 'BAZAR_ALREADY_SOLD', message_en: 'P0000002: Already sold', message_ar: 'P0000002: تم بيعها',
      details: { sale_number: 'B-0042', sold_at: '2026-11-20T16:42:00Z', seller_name: 'Hana', line_index: 1,
                 line_code: 'P0000002' },
    })))
    const user = await openCart()
    await add(user, 'P0000001')
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getByTestId('cart-complete')).toBeEnabled())
    await user.click(screen.getByTestId('cart-complete'))
    const lines = await screen.findAllByTestId('cart-line')
    await waitFor(() => expect(within(lines[1]).getByTestId('cart-line-error')).toHaveTextContent('Already sold'))
    expect(within(lines[1]).getByTestId('cart-line-error')).toHaveTextContent('B-0042 at 18:42 by Hana')
    expect(within(lines[0]).queryByTestId('cart-line-error')).toBeNull()
    expect(screen.getAllByTestId('cart-line')).toHaveLength(2)
    expect(screen.getByTestId('cart-complete')).toBeDisabled()
    expect(screen.queryByTestId('cart-card-sold')).toBeNull()
  })

  test('no answer: Complete is re-sent with the SAME request id until the server answers — one in flight', async () => {
    let tries = 0
    install(...sellBase(), on('POST', '/cart/complete', () => {
      tries += 1
      return tries < 3 ? json(503, {}) : json(200, receipt())
    }))
    const user = await openCart()
    await add(user, 'P0000001')
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getByTestId('cart-complete')).toBeEnabled())
    await user.click(screen.getByTestId('cart-complete'))
    await user.click(screen.getByTestId('cart-complete'))       // a second tap while sending starts nothing
    await screen.findByTestId('cart-card-sold')
    const ids = completes().map(c => (c.body as Record<string, string>).client_request_id)
    expect(ids).toHaveLength(3)
    expect(new Set(ids).size).toBe(1)
  })

  test('the recent list shows a cart as one row; its sheet lists the pieces and undoes / re-pays the WHOLE cart', async () => {
    const r = receipt()
    install(...sellBase('worker', [r]),
      on('PUT', '/receipts/r-1/payment', () => json(200, receipt({ method: 'cash' }))),
      on('POST', '/receipts/r-1/undo', () => json(200, receipt({ status: 'undone', undone_at: new Date().toISOString() }))))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()
    const row = await screen.findByTestId('recent-receipt')
    expect(row).toHaveTextContent('Cart · 2 pieces')
    expect(row).toHaveTextContent('1,200')
    await user.click(row)
    const sheet = await screen.findByTestId('bazar-receipt-sheet')
    expect(within(sheet).getByTestId('receipt-lines')).toHaveTextContent('Linen shirt · M')
    expect(within(sheet).getByTestId('receipt-lines')).toHaveTextContent('462')
    await user.click(within(sheet).getByRole('button', { name: 'Cash' }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.endsWith('/receipts/r-1/payment'))).toBe(true))
    expect(calls.find(c => c.url.endsWith('/receipts/r-1/payment'))!.body).toEqual({ method: 'cash' })
    await user.click(within(sheet).getByRole('button', { name: 'Undo cart' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/receipts/r-1/undo'))).toBe(true))
    expect(calls.some(c => c.url.includes('/sales/l-1/undo'))).toBe(false)
  })

  test('a cart with a returned piece offers no Undo', async () => {
    const r = receipt({ sold_at: new Date().toISOString() })
    ;(r.lines as Record<string, unknown>[])[1].status = 'returned'
    install(...sellBase('owner', [r]))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('recent-receipt'))
    const sheet = await screen.findByTestId('bazar-receipt-sheet')
    expect(within(sheet).queryByRole('button', { name: 'Undo cart' })).toBeNull()
  })
})

// ── Focus safety (B6c review): a HID scan (a fast burst of keys + Enter) while a cart / customer field has focus goes
// to the cart (or, in Quick, is a sale) and leaves the field as it was; normal typing there never scans. The scanner
// hook (useScanner, SAFETY-CRITICAL) is not touched. ──────────────────────────────────────────────────────────────

/** A HID scanner: every key a few ms apart, then Enter. */
const hid = () => userEvent.setup({ delay: 0 })
/** A person typing: 60 ms between keys. */
const person = () => userEvent.setup({ delay: 60 })
const sellsSent = () => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/sell`))

describe('Cart mode — focus safety', () => {
  test('a HID scan while the discount box has focus adds the piece; the discount is unchanged', async () => {
    install(...sellBase())
    const user = await openCart()
    await add(user, 'P0000002')
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('800'))
    const box = screen.getByTestId('cart-discount') as HTMLInputElement
    await person().type(box, '50')
    expect(box).toHaveFocus()
    await hid().keyboard('P0000001{Enter}')
    await waitFor(() => expect(screen.getAllByTestId('cart-line')).toHaveLength(2))
    expect(box.value).toBe('50')
    expect(screen.getByTestId('cart-total')).toHaveTextContent('1,250')
    expect(sellsSent()).toHaveLength(0)
  })

  test('a HID scan while a customer box has focus adds the piece; the box is unchanged', async () => {
    install(...sellBase())
    const user = await openCart()
    await user.click(screen.getByLabelText('Name'))
    const name = screen.getByLabelText('Name') as HTMLInputElement
    await person().type(name, 'Mona')
    expect(name).toHaveFocus()
    await hid().keyboard('P0000003{Enter}')
    await waitFor(() => expect(screen.getAllByTestId('cart-line')).toHaveLength(1))
    expect(screen.getAllByTestId('cart-line')[0]).toHaveTextContent('Scarf')
    expect(name.value).toBe('Mona')
    const phone = screen.getByLabelText('Mobile number') as HTMLInputElement
    await person().type(phone, '010')
    await hid().keyboard('P0000002{Enter}')
    await waitFor(() => expect(screen.getAllByTestId('cart-line')).toHaveLength(2))
    expect(phone.value).toBe('010')
  })

  test('typing normally in the discount and customer boxes (Enter included) never scans or sells', async () => {
    install(...sellBase())
    const user = await openCart()
    await add(user, 'P0000001')
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('500'))
    const previewsBefore = previews().length
    const p = person()
    await p.type(screen.getByTestId('cart-discount'), '1234{Enter}')
    expect(screen.getByTestId('cart-discount')).toHaveValue('1234')
    await user.click(screen.getByLabelText('Name'))
    await p.type(screen.getByLabelText('Name'), 'Mona Ali Hassan{Enter}')
    await p.type(screen.getByLabelText('Mobile number'), '01012345678{Enter}')
    expect(screen.getByLabelText('Name')).toHaveValue('Mona Ali Hassan')
    expect(screen.getByLabelText('Mobile number')).toHaveValue('01012345678')
    expect(screen.getAllByTestId('cart-line')).toHaveLength(1)
    expect(previews().length).toBe(previewsBefore)
    expect(sellsSent()).toHaveLength(0)
    expect(completes()).toHaveLength(0)
  })
})

describe('Quick mode "+ Customer" sheet — focus safety', () => {
  async function openCustomerSheet() {
    install(...sellBase(), on('POST', '/sell', () => json(200, {
      kind: 'sale', id: 's-9', sale_number: 'B-0090', sold_at: new Date().toISOString(), business_day: TODAY,
      status: 'active', price: '500.00', price_source: 'default', method: 'cash', piece_id: 'p', piece_code: 'P0000001',
      variant_id: 'v', product_title: 'Linen shirt', variant_title: 'M', seller_id: 'u-worker', seller_name: 'Omar',
      customer_name: null, customer_phone: null, undone_at: null, discount: '0.00', net_price: '500.00' })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('bazar-scan-input'), 'P0000001{Enter}')
    await screen.findByTestId('sell-card-sold')
    await user.click(screen.getByRole('button', { name: 'Customer name' }))
    return within(await screen.findByTestId('bazar-customer-sheet'))
  }

  test('a HID scan while the sheet\'s name box has focus is a sale (as any scan in Quick); the box is unchanged', async () => {
    const sheet = await openCustomerSheet()
    const name = sheet.getByLabelText('Name') as HTMLInputElement
    await person().type(name, 'Laila')
    expect(sellsSent()).toHaveLength(1)
    await hid().keyboard('P0000002{Enter}')
    await waitFor(() => expect(sellsSent()).toHaveLength(2))
    expect((sellsSent()[1].body as Record<string, string>).code).toBe('P0000002')
    expect(name.value).toBe('Laila')
  })

  test('typing normally in the sheet (Enter included) never sells', async () => {
    const sheet = await openCustomerSheet()
    const p = person()
    await p.type(sheet.getByLabelText('Name'), 'Laila Hassan{Enter}')
    await p.type(sheet.getByLabelText('Mobile number'), '01098765432{Enter}')
    expect(sheet.getByLabelText('Name')).toHaveValue('Laila Hassan')
    expect(sheet.getByLabelText('Mobile number')).toHaveValue('01098765432')
    expect(sellsSent()).toHaveLength(1)
  })
})
