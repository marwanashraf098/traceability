import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import { RootRoute, RequireAuth } from '../App'
import Login from '../pages/Login'
import BazarSell from '../pages/bazar/phone/BazarSell'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
import BazarReturn from '../pages/bazar/phone/BazarReturn'
import BazarNewEvent from '../pages/bazar/BazarNewEvent'
import { CustomerSheet } from '../pages/bazar/phone/SaleSheets'
import Sidebar from '../components/Sidebar'
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

// ── RootRoute / Login ─────────────────────────────────────────────────────────

function rootTree() {
  return (
    <>
      <Route path="/" element={<RootRoute />} />
      <Route path="/login" element={<Login />} />
      <Route path="/overview" element={<RequireAuth><div data-testid="overview-page">OVERVIEW</div></RequireAuth>} />
      <Route path="/worker-home" element={<RequireAuth><div data-testid="worker-home-page">WORKER</div></RequireAuth>} />
      <Route path="/bazar/:id/home" element={<div data-testid="bazar-home-page"><Where /></div>} />
    </>
  )
}

function refreshAs(role: 'owner' | 'worker', meBody: unknown): Handler[] {
  return [
    on('POST', '/auth/refresh', () => json(200, { accessToken: fakeJwt(role) })),
    on('GET', '/api/v1/me', () => json(200, meBody)),
    on('GET', '/station/roster', () => json(200, [])),
  ]
}

describe('RootRoute — a live bazar opens first', () => {
  test('bazarLive in /me → /bazar/:id/home (worker and owner)', async () => {
    install(...refreshAs('worker', me('worker')))
    renderAt('/', rootTree())
    expect(await screen.findByTestId('bazar-home-page')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent(`/bazar/${EVENT}/home`)
  })

  test('station mode still wins: the gate shows, never the bazar', async () => {
    localStorage.setItem('stationMode', 'true')
    install(...refreshAs('owner', me('owner')))
    renderAt('/', rootTree())
    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-home-page')).not.toBeInTheDocument()
  })

  test('no bazarLive → the old routing (owner /overview, worker /worker-home)', async () => {
    install(...refreshAs('owner', me('owner', null)))
    renderAt('/', rootTree())
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    cleanup()
    clearAccessToken()
    resetCapabilitiesCache()
    install(...refreshAs('worker', { name: 'W', email: 'w@x', role: 'worker' }))
    renderAt('/', rootTree())
    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
  })
})

describe('Login goes through RootRoute', () => {
  async function signIn() {
    const user = userEvent.setup()
    await waitFor(() => expect(document.querySelector('input[type="email"]')).not.toBeNull())
    await user.type(document.querySelector('input[type="email"]') as HTMLInputElement, 'w@x.com')
    await user.type(document.querySelector('input[type="password"]') as HTMLInputElement, 'pw-12345')
    await user.click(document.querySelector('button[type="submit"]') as HTMLButtonElement)
  }

  test('a seller with a live bazar lands on the bazar home', async () => {
    install(
      on('POST', '/auth/refresh', () => json(401, {})),
      on('POST', '/auth/login', () => json(200, { accessToken: fakeJwt('worker') })),
      on('GET', '/api/v1/me', () => json(200, me('worker'))),
    )
    renderAt('/login', rootTree())
    await signIn()
    expect(await screen.findByTestId('bazar-home-page')).toBeInTheDocument()
  })

  test('without a live bazar an owner lands on /overview, as before', async () => {
    install(
      on('POST', '/auth/refresh', () => json(401, {})),
      on('POST', '/auth/login', () => json(200, { accessToken: fakeJwt('owner') })),
      on('GET', '/api/v1/me', () => json(200, me('owner', null))),
    )
    renderAt('/login', rootTree())
    await signIn()
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
  })
})

// ── Quick sell ────────────────────────────────────────────────────────────────

function todaySummary(over: Partial<Record<string, unknown>> = {}) {
  return { business_day: TODAY, total: '0.00', sale_count: 0, by_method: { cash: '0.00' }, per_seller: [], ...over }
}

function sellBase(role: 'worker' | 'owner' | 'manager' = 'worker', list: unknown[] = [],
                  today: unknown = todaySummary()): Handler[] {
  setAccessToken(fakeJwt(role))
  return [
    on('GET', '/api/v1/me', () => json(200, me(role))),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: list, next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, today)),
  ]
}

async function scan(code: string) {
  const user = userEvent.setup()
  const input = await screen.findByTestId('bazar-scan-input')
  await user.type(input, `${code}{Enter}`)
  return user
}

describe('Quick sell — scan = sold', () => {
  test('success: SOLD card with the sale number, piece, price and the event methods only', async () => {
    const s = sale()
    install(...sellBase(), on('POST', '/sell', () => json(200, s)))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    await scan('P0000001')
    const card = await screen.findByTestId('sell-card-sold')
    expect(card).toHaveTextContent(`SOLD · ${s.sale_number}`)
    expect(card).toHaveTextContent('Linen shirt · White / M')
    expect(within(card).getByTestId('sold-price')).toHaveTextContent('960')
    const chips = within(card).getAllByRole('button', { pressed: undefined }).filter(b => b.hasAttribute('aria-pressed'))
    expect(chips.map(c => c.textContent)).toEqual(['Cash', 'InstaPay', 'Card'])
    expect(within(card).getByRole('button', { name: 'Cash' })).toHaveAttribute('aria-pressed', 'true')
    expect(sells()).toHaveLength(1)
    const body = sells()[0].body as Record<string, string>
    expect(body.code).toBe('P0000001')
    expect(body.method).toBe('cash')
    expect(body.client_request_id).toMatch(/.+/)
  })

  test.each([
    ['BAZAR_ALREADY_SOLD', { sale_number: 'B-0042', sold_at: '2026-11-20T16:42:00Z', seller_name: 'Hana' },
      'Already sold', 'B-0042 at 18:42 by Hana.'],
    ['BAZAR_NO_PRICE', undefined, 'No price yet', 'never sold at 0'],
    ['BAZAR_PIECE_NOT_HERE', undefined, 'Not at this bazar', "wasn't sent out"],
    ['BAZAR_EVENT_NOT_LIVE', undefined, 'Selling is closed', "isn't live"],
    ['BAZAR_PIECE_UNTAGGED', undefined, 'No barcode yet', 'untagged'],
    ['BAZAR_PIECE_NOT_FOUND', undefined, 'Unknown code', 'Check the label'],
    ['BAZAR_NOT_A_SELLER', undefined, "Your role can't sell at this bazar.", ''],
  ])('%s → its own clear message', async (code, details, title, detail) => {
    install(...sellBase(), on('POST', '/sell', () => json(409, {
      code, message_en: 'server en', message_ar: 'server ar', ...(details ? { details } : {}) })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    await scan('P0000001')
    const card = await screen.findByTestId('sell-card-error')
    expect(card).toHaveTextContent(title)
    if (detail) expect(card).toHaveTextContent(detail)
    expect(screen.queryByTestId('sell-card-sold')).not.toBeInTheDocument()
    expect(sells()).toHaveLength(1)          // a definite "no" is never retried
  })

  test('weak signal: Checking…, then retries with the SAME client_request_id until the server answers', async () => {
    const s = sale({ replayed: true })
    let attempt = 0
    let releaseThird: () => void = () => {}
    install(...sellBase(), on('POST', '/sell', () => {
      attempt += 1
      if (attempt === 1) return Promise.reject(new TypeError('Failed to fetch'))
      if (attempt === 2) return json(503, {})
      return new Promise<Response>(res => { releaseThird = () => res(json(200, s) as unknown as Response) })
    }))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    await scan('P0000001')
    expect(await screen.findByTestId('sell-card-checking')).toHaveTextContent('Checking…')
    await waitFor(() => expect(sells()).toHaveLength(3))
    expect(screen.queryByTestId('sell-card-sold')).not.toBeInTheDocument()   // never success before the server says so
    releaseThird()
    expect(await screen.findByTestId('sell-card-sold')).toHaveTextContent(s.sale_number)
    const ids = sells().map(c => (c.body as Record<string, string>).client_request_id)
    expect(new Set(ids).size).toBe(1)
  })

  test('a timed-out attempt is retried with the same request id too', async () => {
    sellTiming.timeoutMs = 20
    const s = sale()
    let attempt = 0
    install(...sellBase(), on('POST', '/sell', init => {
      attempt += 1
      if (attempt === 1) {
        return new Promise<Response>((_res, rej) => {
          init?.signal?.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError')))
        })
      }
      return json(200, { ...s, replayed: true })
    }))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    await scan('P0000001')
    expect(await screen.findByTestId('sell-card-sold')).toBeInTheDocument()
    const ids = sells().map(c => (c.body as Record<string, string>).client_request_id)
    expect(ids).toHaveLength(2)
    expect(ids[0]).toBe(ids[1])
  })

  test('no double request: the same code scanned again while its sale is pending starts nothing', async () => {
    const s = sale()
    let release: () => void = () => {}
    install(...sellBase(), on('POST', '/sell', () =>
      new Promise<Response>(res => { release = () => res(json(200, s) as unknown as Response) })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    await scan('P0000001')
    await screen.findByTestId('sell-card-sending')
    await scan('P0000001')
    expect(await screen.findByText(/P0000001 is already being sold/)).toBeInTheDocument()
    expect(sells()).toHaveLength(1)
    await userEvent.setup().click(screen.getByRole('button', { name: 'fake-camera-read' }))   // camera: another piece
    expect(sells()).toHaveLength(1)                                                           // waits its turn
    release()
    expect(await screen.findByTestId('sell-card-sold')).toBeInTheDocument()
    // the OTHER piece (camera) was queued and is sold next — one request for it
    await waitFor(() => expect(sells()).toHaveLength(2))
    expect((sells()[1].body as Record<string, string>).code).toBe('P0000777')
  })

  test('camera: the same code read twice within 2 s is one scan', async () => {
    install(...sellBase(), on('POST', '/sell', () => json(200, sale())))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()
    const btn = await screen.findByRole('button', { name: 'fake-camera-read' })
    await user.click(btn)
    await screen.findByTestId('sell-card-sold')
    await user.click(btn)
    await new Promise(r => setTimeout(r, 30))
    expect(sells()).toHaveLength(1)
  })

  test('payment change on the SOLD card: PUT /payment, the new method is shown', async () => {
    const s = sale()
    install(...sellBase(),
      on('POST', '/sell', () => json(200, s)),
      on('PUT', `/sales/${s.id}/payment`, () => json(200, { ...s, method: 'instapay' })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = await scan('P0000001')
    const card = await screen.findByTestId('sell-card-sold')
    await user.click(within(card).getByRole('button', { name: 'InstaPay' }))
    await waitFor(() => expect(within(card).getByRole('button', { name: 'InstaPay' })).toHaveAttribute('aria-pressed', 'true'))
    const put = calls.find(c => c.method === 'PUT' && c.url.includes('/payment'))
    expect(put?.body).toEqual({ method: 'instapay' })
  })

  test('undo on the SOLD card: shown for the window, then gone; tapping it undoes the sale', async () => {
    sellTiming.undoCardMs = 400
    const s = sale()
    install(...sellBase(),
      on('POST', '/sell', () => json(200, s)),
      on('POST', `/sales/${s.id}/undo`, () => json(200, { ...s, status: 'undone', undone_at: new Date().toISOString() })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = await scan('P0000001')
    expect(await screen.findByTestId('sold-undo')).toHaveTextContent(/Undo · \ds/)
    await waitFor(() => expect(screen.queryByTestId('sold-undo')).not.toBeInTheDocument(), { timeout: 2000 })

    sellTiming.undoCardMs = 8_000
    await scan('P0000002')
    await waitFor(() => expect(sells()).toHaveLength(2))
    await user.click(await screen.findByTestId('sold-undo'))
    expect(await screen.findByTestId('sell-card-undone')).toHaveTextContent(`UNDONE · ${s.sale_number}`)
  })
})

describe('Recent sales — what each role may do', () => {
  const old = new Date(Date.now() - 5 * 60_000).toISOString()

  test('worker: own recent sale → method + customer + Undo; own sale > 2 min → no Undo; someone else’s → read-only', async () => {
    const mineNew = sale({ product_title: 'Mine new' })
    const mineOld = sale({ product_title: 'Mine old', sold_at: old })
    const theirs = sale({ product_title: 'Theirs', seller_id: 'u-other', seller_name: 'Hana', customer_phone: '010*****678' })
    install(...sellBase('worker', [mineNew, mineOld, theirs]))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /Mine new/ }))
    let sheet = await screen.findByTestId('bazar-sale-sheet')
    expect(within(sheet).getByRole('button', { name: 'Undo this sale' })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Card' })).toBeEnabled()
    expect(within(sheet).getByRole('button', { name: 'Add customer' })).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Close' }))

    await user.click(screen.getByRole('button', { name: /Mine old/ }))
    sheet = await screen.findByTestId('bazar-sale-sheet')
    expect(within(sheet).queryByRole('button', { name: 'Undo this sale' })).not.toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Card' })).toBeEnabled()     // method: any time while live
    await user.click(within(sheet).getByRole('button', { name: 'Close' }))

    await user.click(screen.getByRole('button', { name: /Theirs/ }))
    sheet = await screen.findByTestId('bazar-sale-sheet')
    expect(within(sheet).queryByRole('button', { name: 'Undo this sale' })).not.toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Card' })).toBeDisabled()
    expect(sheet).toHaveTextContent('010*****678')                               // masked by the server
    expect(sheet).toHaveTextContent('Only the seller who made this sale')
  })

  test('manager: Undo on any active sale, even an old one by someone else', async () => {
    const theirsOld = sale({ product_title: 'Theirs old', seller_id: 'u-other', sold_at: old })
    install(...sellBase('manager', [theirsOld]),
      on('POST', `/sales/${theirsOld.id}/undo`, () => json(200, { ...theirsOld, status: 'undone' })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Theirs old/ }))
    const sheet = await screen.findByTestId('bazar-sale-sheet')
    await user.click(within(sheet).getByRole('button', { name: 'Undo this sale' }))
    expect(await within(sheet).findByTestId('sale-undone')).toBeInTheDocument()
  })

  test('footer: today total and count come from GET /today, not from the (paged) sales list', async () => {
    // The list holds one page; the server's totals cover the whole day.
    install(...sellBase('worker', [sale({ price: '100.00' }), sale({ price: '250.50' })],
                        todaySummary({ total: '12000.00', sale_count: 60 })))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const footer = await screen.findByTestId('bazar-sell-footer')
    await waitFor(() => expect(footer).toHaveTextContent('EGP 12,000'))
    expect(footer).toHaveTextContent('60 sales')
    expect(footer).toHaveTextContent('default pay: Cash')
  })

  test('a sale reloads the server totals', async () => {
    const s = sale({ price: '960.00' })
    let today = todaySummary({ total: '0.00', sale_count: 0 })
    install(...sellBase('worker', [], undefined).slice(0, 2),
      on('GET', `/bazar-events/${EVENT}/today`, () => json(200, today)),
      on('POST', '/sell', () => { today = todaySummary({ total: '960.00', sale_count: 1 }); return json(200, s) }))
    renderAt(`/bazar/${EVENT}/sell`, sellRoutes)
    const footer = await screen.findByTestId('bazar-sell-footer')
    await waitFor(() => expect(footer).toHaveTextContent('0 sales'))
    await scan('P0000001')
    await screen.findByTestId('sell-card-sold')
    await waitFor(() => expect(footer).toHaveTextContent('EGP 960'))
    expect(footer).toHaveTextContent('1 sale')
  })
})

describe('Phone home', () => {
  test('Start selling, today total with per-seller counts; Return opens the Return screen; workers get no Close day', async () => {
    install(...sellBase('worker', [], todaySummary({ total: '350.00', sale_count: 3, per_seller: [
      { seller_id: 'u-other', seller_name: 'Hana', sale_count: 2, total: '250.00' },
      { seller_id: 'u-worker', seller_name: 'Omar', sale_count: 1, total: '100.00' }] })))
    renderAt(`/bazar/${EVENT}/home`, <>
      <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />
      <Route path="/bazar/:id/return" element={<BazarReturn />} />
    </>)
    expect(await screen.findByRole('button', { name: /Start selling/ })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('bazar-today-total')).toHaveTextContent('EGP 350'))
    expect(screen.getByTestId('bazar-today')).toHaveTextContent('3 pieces · Hana 2 · you 1')
    expect(screen.queryByRole('button', { name: 'Close day' })).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Return' }))
    expect(await screen.findByTestId('bazar-return')).toBeInTheDocument()
  })

  test('an event that is not live for this user → the not-live screen', async () => {
    setAccessToken(fakeJwt('worker'))
    install(on('GET', '/api/v1/me', () => json(200, me('worker', null))))
    renderAt(`/bazar/${EVENT}/home`, <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />)
    expect(await screen.findByTestId('bazar-not-live')).toBeInTheDocument()
  })
})

describe('Customer sheet — a field left empty is not sent', () => {
  function renderSheet(s: ReturnType<typeof sale>, onSaved = vi.fn()) {
    renderAt(`/bazar/${EVENT}/sell`, <Route path="/bazar/:id/sell" element={
      <CustomerSheet eventId={EVENT} sale={s as never} onSaved={onSaved} onClose={() => {}} />} />)
    return onSaved
  }
  const customerPut = () => calls.find(c => c.method === 'PUT' && c.url.includes('/customer'))

  test('worker, masked phone: the field starts empty with the keep hint; saving a new name sends the name only', async () => {
    setAccessToken(fakeJwt('worker'))
    const s = sale({ customer_name: 'Laila', customer_phone: '015*****678' })
    install(on('PUT', `/sales/${s.id}/customer`, () => json(200, { ...s, customer_name: 'Laila Hassan' })))
    const onSaved = renderSheet(s)
    const sheet = await screen.findByTestId('bazar-customer-sheet')
    expect(within(sheet).getByLabelText('Mobile number')).toHaveValue('')
    expect(within(sheet).getByTestId('bazar-customer-keep-hint')).toHaveTextContent('Leave empty to keep the current number: 015*****678')
    const user = userEvent.setup()
    const name = within(sheet).getByDisplayValue('Laila')
    await user.clear(name)
    await user.type(name, 'Laila Hassan')
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(customerPut()?.body).toEqual({ name: 'Laila Hassan' })
  })

  test('worker, masked phone: typing a new number sends it', async () => {
    setAccessToken(fakeJwt('worker'))
    const s = sale({ customer_name: 'Laila', customer_phone: '015*****678' })
    install(on('PUT', `/sales/${s.id}/customer`, () => json(200, s)))
    const onSaved = renderSheet(s)
    const sheet = await screen.findByTestId('bazar-customer-sheet')
    const user = userEvent.setup()
    await user.type(within(sheet).getByLabelText('Mobile number'), '01098765432')
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(customerPut()?.body).toEqual({ name: 'Laila', phone: '01098765432' })
  })

  test('owner sees the full number; emptying it sends an explicit clear', async () => {
    setAccessToken(fakeJwt('owner'))
    const s = sale({ customer_name: 'Laila', customer_phone: '01512345678' })
    install(on('PUT', `/sales/${s.id}/customer`, () => json(200, s)))
    const onSaved = renderSheet(s)
    const sheet = await screen.findByTestId('bazar-customer-sheet')
    expect(within(sheet).queryByTestId('bazar-customer-keep-hint')).not.toBeInTheDocument()
    const user = userEvent.setup()
    await user.clear(within(sheet).getByLabelText('Mobile number'))
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(customerPut()?.body).toEqual({ name: 'Laila', phone: null })
  })
})

// ── Desktop ───────────────────────────────────────────────────────────────────

describe('Sidebar — Bazar only for bazar tenants', () => {
  function renderSidebar() {
    return renderAt('/overview', <Route path="/overview" element={
      <Sidebar role="owner" me={null} navOpen={false} onCloseNav={() => {}} />} />)
  }
  test('bazar on → the Bazar item', async () => {
    setAccessToken(fakeJwt('owner'))
    install(on('GET', '/api/v1/me', () => json(200, me('owner', null))))
    renderSidebar()
    expect(await screen.findByRole('link', { name: /Bazar/ })).toHaveAttribute('href', '/bazar')
  })
  test('bazar off → no Bazar item', async () => {
    setAccessToken(fakeJwt('owner'))
    install(on('GET', '/api/v1/me', () => json(200, { name: 'O', email: 'o@x', role: 'owner' })))
    renderSidebar()
    await screen.findByRole('link', { name: /Transfers/ })
    await new Promise(r => setTimeout(r, 20))
    expect(screen.queryByRole('link', { name: /Bazar/ })).not.toBeInTheDocument()
  })
})

describe('New event', () => {
  test('creates with the chosen methods and roles, never a Shopify mode; says pieces leave online stock', async () => {
    setAccessToken(fakeJwt('owner'))
    install(
      on('GET', '/locations', () => json(200, [])),
      on('POST', '/bazar-events', () => json(201, { id: 'new-ev' })),
    )
    renderAt('/bazar/new', <>
      <Route path="/bazar/new" element={<BazarNewEvent />} />
      <Route path="/bazar/:id" element={<Where />} />
    </>)
    const user = userEvent.setup()
    expect(await screen.findByTestId('bazar-remove-note')).toHaveTextContent("Bazar pieces leave online stock while they're at the bazar")
    expect(screen.queryByText(/Leave online stock as it is/i)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('radio')).toHaveLength(2)   // only the location choice — no Shopify-mode radios
    await user.type(screen.getByLabelText('Event name'), 'Zamalek Winter Bazar')
    await user.type(screen.getByLabelText('Location name'), 'Zamalek Hall')
    await user.click(screen.getByText('Card'))        // untick Card
    await user.click(screen.getByText('Workers'))     // untick Workers
    await user.click(screen.getByRole('button', { name: 'Create event' }))
    expect(await screen.findByTestId('where')).toHaveTextContent('/bazar/new-ev')
    const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/bazar-events'))
    expect(post?.body).toMatchObject({
      name: 'Zamalek Winter Bazar', locationName: 'Zamalek Hall', locationId: null,
      paymentMethods: ['cash', 'instapay'], sellerRoles: ['owner', 'manager'],
    })
    expect(post?.body).not.toHaveProperty('shopifySyncMode')
  })
})
