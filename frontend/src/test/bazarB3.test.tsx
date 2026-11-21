import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarSell from '../pages/bazar/phone/BazarSell'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
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


// Bazar POS B3 — the phone's Return and Exchange screens: the preview from GET /piece, the request bodies, the
// weak-signal retry with the SAME client_request_id (never a success before the server confirms), refusals, the
// exchange's difference banner, Home's buttons and the recent list's badges.

function todaySummary(over: Partial<Record<string, unknown>> = {}) {
  return { business_day: TODAY, total: '0.00', sale_count: 0, by_method: { cash: '0.00' }, per_seller: [], ...over }
}

function base(role: 'worker' | 'owner' = 'worker'): Handler[] {
  setAccessToken(fakeJwt(role))
  return [
    on('GET', '/api/v1/me', () => json(200, me(role))),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, todaySummary())),
  ]
}

/** GET /piece answers per code. */
function pieces(map: Record<string, unknown>): Handler {
  return (url, init) => {
    if ((init?.method ?? 'GET').toUpperCase() !== 'GET' || !url.includes(`/bazar-events/${EVENT}/piece?`)) return undefined
    const code = new URL(url, 'http://x').searchParams.get('code') ?? ''
    return map[code] ? json(200, map[code]) : json(404, { code: 'BAZAR_PIECE_NOT_FOUND', message_en: 'nope' })
  }
}

const soldPiece = (code: string, over: Partial<Record<string, unknown>> = {}) => ({
  piece_id: `pc-${code}`, piece_code: code, product_title: 'Linen shirt', variant_title: 'White / M', price: '960.00',
  state: 'sold', sale: sale({ piece_id: `pc-${code}`, piece_code: code, method: 'card', customer_name: 'Nour',
                             customer_phone: '010*****678', seller_name: 'Salma', ...over }),
})

const forSale = (code: string, price: string | null = '1200.00') => ({
  piece_id: `pc-${code}`, piece_code: code, product_title: 'Denim jacket', variant_title: 'Blue / M', price,
  state: 'for_sale', sale: null,
})

const posts = (frag: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/${frag}`))

const routes = (
  <>
    <Route path="/bazar/:id/return" element={<BazarReturn />} />
    <Route path="/bazar/:id/exchange" element={<BazarExchange />} />
    <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />
    <Route path="/bazar/:id/sell" element={<BazarSell />} />
  </>
)

async function scan(code: string) {
  const user = userEvent.setup()
  const input = await screen.findByTestId('bazar-code-input')
  await user.type(input, `${code}{Enter}`)
  return user
}

function returnResult(method = 'card', condition = 'good') {
  return { sale: sale({ status: 'returned', return_condition: condition, returned_at: new Date().toISOString() }),
           refund: { method, amount: '960.00' }, replayed: false }
}

describe('Return', () => {
  test('scan → the sale (piece, price, when / by whom, customer); good + the method they paid by; confirm', async () => {
    install(...base(), pieces({ P1: soldPiece('P1') }),
      on('POST', '/return', () => json(200, returnResult())))
    renderAt(`/bazar/${EVENT}/return`, routes)
    const user = await scan('P1')
    const card = await screen.findByTestId('return-sale')
    expect(card).toHaveTextContent('Linen shirt · White / M')
    expect(card).toHaveTextContent('Salma')
    expect(card).toHaveTextContent('Nour 010*****678')
    expect(card).toHaveTextContent('960')
    expect(screen.getByRole('button', { name: /Good/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Card' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('return-amount')).toHaveTextContent('Give back 960 EGP')

    await user.click(screen.getByTestId('return-confirm'))
    expect(await screen.findByTestId('return-done')).toHaveTextContent('RETURNED')
    expect(screen.getByTestId('return-done-refund')).toHaveTextContent('Give back 960 EGP · Card')
    const body = posts('return')[0].body as Record<string, string>
    expect(body).toMatchObject({ code: 'P1', condition: 'good', method: 'card' })
    expect(body.client_request_id).toBeTruthy()

    await user.click(screen.getByTestId('return-next'))
    expect(screen.queryByTestId('return-done')).not.toBeInTheDocument()
    expect(screen.getByTestId('bazar-code-input')).toBeInTheDocument()
  })

  test('damaged + another method are what is sent', async () => {
    install(...base(), pieces({ P1: soldPiece('P1') }), on('POST', '/return', () => json(200, returnResult('instapay', 'damaged'))))
    renderAt(`/bazar/${EVENT}/return`, routes)
    const user = await scan('P1')
    await screen.findByTestId('return-sale')
    await user.click(screen.getByRole('button', { name: /Damaged/ }))
    await user.click(screen.getByRole('button', { name: 'InstaPay' }))
    await user.click(screen.getByTestId('return-confirm'))
    expect(await screen.findByTestId('return-done')).toHaveTextContent("won't be sold again")
    expect(posts('return')[0].body).toMatchObject({ condition: 'damaged', method: 'instapay' })
  })

  test('weak signal: Checking…, retried with the SAME client_request_id, never Returned before the server says so', async () => {
    let attempt = 0
    let release: () => void = () => {}
    install(...base(), pieces({ P1: soldPiece('P1') }), on('POST', '/return', () => {
      attempt += 1
      if (attempt === 1) return Promise.reject(new TypeError('Failed to fetch'))
      if (attempt === 2) return json(502, {})
      return new Promise<Response>(res => { release = () => res(json(200, { ...returnResult(), replayed: true }) as unknown as Response) })
    }))
    renderAt(`/bazar/${EVENT}/return`, routes)
    const user = await scan('P1')
    await screen.findByTestId('return-sale')
    await user.click(screen.getByTestId('return-confirm'))
    expect(await screen.findByTestId('cmd-checking')).toHaveTextContent('Checking')
    await waitFor(() => expect(posts('return')).toHaveLength(3))
    expect(screen.queryByTestId('return-done')).not.toBeInTheDocument()
    expect(screen.getByTestId('return-confirm')).toBeDisabled()
    release()
    expect(await screen.findByTestId('return-done')).toBeInTheDocument()
    expect(new Set(posts('return').map(c => (c.body as Record<string, string>).client_request_id)).size).toBe(1)
  })

  test('a refusal is shown and not retried; a piece not sold here can\'t be confirmed', async () => {
    install(...base(), pieces({ P1: soldPiece('P1'), P2: forSale('P2') }),
      on('POST', '/return', () => json(409, { code: 'BAZAR_ALREADY_RETURNED', message_en: 'x',
        details: { sale_number: 'B-0001', returned_by_name: 'Hana' } })))
    renderAt(`/bazar/${EVENT}/return`, routes)
    let user = await scan('P2')
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent('Not sold at this bazar')
    expect(screen.queryByTestId('return-confirm')).not.toBeInTheDocument()

    user = await scan('P1')
    await screen.findByTestId('return-sale')
    await user.click(screen.getByTestId('return-confirm'))
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent('Already returned')
    expect(screen.getByTestId('cmd-error')).toHaveTextContent('B-0001 was returned by Hana.')
    await new Promise(r => setTimeout(r, 30))
    expect(posts('return')).toHaveLength(1)
  })
})

describe('Exchange', () => {
  test('up: old then new → "Customer pays EGP 240", the method chosen, confirm sends both codes', async () => {
    install(...base(), pieces({ OLD: soldPiece('OLD', { method: 'cash' }), NEW: forSale('NEW') }),
      on('POST', '/exchange', () => json(200, {
        old_sale: sale({ status: 'returned' }), new_sale: sale({ price: '1200.00' }), difference: '240.00',
        money: { kind: 'payment', method: 'instapay', amount: '240.00' }, replayed: false })))
    renderAt(`/bazar/${EVENT}/exchange`, routes)
    let user = await scan('OLD')
    expect(await screen.findByTestId('exchange-old')).toHaveTextContent('Linen shirt')
    user = await scan('NEW')
    expect(await screen.findByTestId('exchange-new')).toHaveTextContent('Denim jacket')
    expect(screen.getByTestId('exchange-difference')).toHaveTextContent('Customer pays 240 EGP')
    expect(screen.getByRole('button', { name: 'Cash' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'InstaPay' }))
    await user.click(screen.getByTestId('exchange-confirm'))
    expect(await screen.findByTestId('exchange-done-money')).toHaveTextContent('Take 240 EGP · InstaPay')
    const body = posts('exchange')[0].body as Record<string, string>
    expect(body).toMatchObject({ old_code: 'OLD', new_code: 'NEW', method: 'instapay', condition: 'good' })
    expect(body.client_request_id).toBeTruthy()
  })

  test('down: "Give back EGP 760"; even: "Even swap", no method chips, no method sent', async () => {
    install(...base(), pieces({ OLD: soldPiece('OLD'), CHEAP: forSale('CHEAP', '200.00'), SAME: forSale('SAME', '960.00') }),
      on('POST', '/exchange', () => json(200, { old_sale: sale(), new_sale: sale(), difference: '0.00', money: null })))
    renderAt(`/bazar/${EVENT}/exchange`, routes)
    let user = await scan('OLD')
    await screen.findByTestId('exchange-old')
    user = await scan('CHEAP')
    expect(await screen.findByTestId('exchange-difference')).toHaveTextContent('Give back 760 EGP')
    expect(screen.getByText('Give it back by')).toBeInTheDocument()
    await user.click(within(screen.getByTestId('exchange-new')).getByRole('button', { name: 'Change' }))
    user = await scan('SAME')
    expect(await screen.findByTestId('exchange-difference')).toHaveTextContent('Even swap')
    expect(screen.queryByRole('button', { name: 'Cash' })).not.toBeInTheDocument()
    await user.click(screen.getByTestId('exchange-confirm'))
    expect(await screen.findByTestId('exchange-done-money')).toHaveTextContent('Even swap')
    expect(posts('exchange')[0].body).not.toHaveProperty('method')
  })

  test('weak signal: retried with the SAME id; a refusal after that is shown, nothing more sent', async () => {
    let attempt = 0
    install(...base(), pieces({ OLD: soldPiece('OLD'), NEW: forSale('NEW') }), on('POST', '/exchange', () => {
      attempt += 1
      if (attempt <= 2) return Promise.reject(new TypeError('Failed to fetch'))
      return json(409, { code: 'BAZAR_ALREADY_SOLD', message_en: 'x',
                         details: { sale_number: 'B-0009', sold_at: new Date().toISOString(), seller_name: 'Hana' } })
    }))
    renderAt(`/bazar/${EVENT}/exchange`, routes)
    let user = await scan('OLD')
    await screen.findByTestId('exchange-old')
    user = await scan('NEW')
    await screen.findByTestId('exchange-new')
    await user.click(screen.getByTestId('exchange-confirm'))
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent('Already sold')
    expect(posts('exchange')).toHaveLength(3)
    expect(new Set(posts('exchange').map(c => (c.body as Record<string, string>).client_request_id)).size).toBe(1)
    expect(screen.queryByTestId('exchange-done')).not.toBeInTheDocument()
  })

  test('the new piece can\'t be the old one, sold, or without a price', async () => {
    install(...base(), pieces({ OLD: soldPiece('OLD'), SOLD: soldPiece('SOLD'), NOPRICE: forSale('NOPRICE', null) }))
    renderAt(`/bazar/${EVENT}/exchange`, routes)
    await scan('OLD')
    await screen.findByTestId('exchange-old')
    await scan('OLD')
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent("That's the piece coming back")
    await scan('SOLD')
    expect(await screen.findByText('Already sold')).toBeInTheDocument()
    await scan('NOPRICE')
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent(/price/i)
    expect(screen.queryByTestId('exchange-confirm')).not.toBeInTheDocument()
  })
})

describe('Home + recent list', () => {
  test('Home: Return and Exchange open their screens', async () => {
    install(...base(), pieces({}))
    renderAt(`/bazar/${EVENT}/home`, routes)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Return' }))
    expect(await screen.findByTestId('bazar-return')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Exchange' }))
    expect(await screen.findByTestId('bazar-exchange')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Sell' }))
    expect(await screen.findByTestId('bazar-sell')).toBeInTheDocument()
  })

  test('recent sales show Returned / Exchanged / Exchange badges', async () => {
    setAccessToken(fakeJwt('worker'))
    install(
      on('GET', '/api/v1/me', () => json(200, me('worker'))),
      on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { next_cursor: null, items: [
        sale({ exchange_of_sale_number: 'B-0002', exchange_of_sale_id: 's-x' }),
        sale({ status: 'returned', exchanged_for_sale_number: 'B-0003' }),
        sale({ status: 'returned' }),
      ] })),
      on('GET', `/bazar-events/${EVENT}/today`, () => json(200, todaySummary({ returns: 1, exchanges: 1 }))),
    )
    renderAt(`/bazar/${EVENT}/sell`, routes)
    expect(await screen.findByTestId('badge-exchange')).toHaveTextContent('Exchange')
    expect(screen.getByTestId('badge-exchanged')).toHaveTextContent('Exchanged')
    expect(screen.getByTestId('badge-returned')).toHaveTextContent('Returned')
  })
})
