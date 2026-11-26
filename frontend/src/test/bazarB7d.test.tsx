import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import TransferScanOut from '../pages/TransferScanOut'
import BazarScanOut from '../pages/bazar/BazarScanOut'
import BazarReturn from '../pages/bazar/phone/BazarReturn'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'

// Bazar POS B7d-1 — send by count on the bazar's scan-out screen (TransferScanOut in bazar mode only): the picker of
// what's at Main, the quantity, the online-orders warning (a second tap, never a block), "Remove N" on a line and the
// ✕ on a scanned row; a generic transfer's screen shows none of it. Plus the Return screen's refund = the net price.

vi.mock('../pages/scanpair/CameraReader', () => ({
  default: () => null,
}))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TID = 't-1'
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

/** Matches the URL's path ending (query string ignored). */
const on = (method: string, end: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.split('?')[0].endsWith(end) ? fn(init) : undefined)

const posts = (end: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(end))

function line(over: Record<string, unknown> = {}) {
  return { id: 'l-1', variant_id: 'v-m', sku: 'SH-M', variant_title: 'White / M', product_title: 'Linen shirt',
           qty_out: 2, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0, ...over }
}

function transfer(over: Record<string, unknown> = {}) {
  return {
    id: TID, transfer_type: 'showroom', transfer_mode: 'round_trip', status: 'preparing', note: null,
    expected_return_at: null, created_by: 'u-owner', created_at: new Date().toISOString(), closed_by: null,
    closed_at: null, sent_at: null, sent_by: null, cancelled_at: null, cancelled_by: null,
    reconcile_started_at: null, reconcile_started_by: null, destination_location_id: 'd-1',
    destination_location_name: 'Zamalek Hall', source_location_id: null, source_location_name: null,
    lines: [line()], ...over,
  }
}

/** What Main has: White / M 3 at Main (2 committed online), White / L 5 (none), a size with no bazar price yet. */
const STOCK = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'draft', categories: [],
  products: [
    { number: null, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: null,
      left: 9, price_min: '960.00', price_max: '960.00', sizes: [
        { variant_id: 'v-m', title: 'White / M', size: 'M', price: '960.00', left: 3, committed: 2 },
        { variant_id: 'v-l', title: 'White / L', size: 'L', price: '960.00', left: 5, committed: 0 },
        { variant_id: 'v-xl', title: 'White / XL', size: 'XL', price: null, left: 1, committed: 0 },
      ] },
  ],
}

function bazarBase(): Handler[] {
  setAccessToken(fakeJwt('owner'))
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })),
    on('GET', `/bazar-events/${EVENT}/send-stock`, () => json(200, STOCK)),
    on('GET', `/bazar-events/${EVENT}`, () => json(200, { id: EVENT, transfer_id: TID, status: 'draft' })),
    on('GET', `/transfers/${TID}`, () => json(200, transfer({ transfer_type: 'bazar' }))),
  ]
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

const bazarRoute = <Route path="/bazar/:id/scan-out" element={<BazarScanOut />} />

async function chooseSize(user: ReturnType<typeof userEvent.setup>, sizeIndex: number) {
  await user.click(await screen.findByRole('button', { name: 'Choose product and size' }))
  const sheet = await screen.findByTestId('bazar-pick-sheet')
  await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
  await user.click(within(sheet).getAllByTestId('pick-size')[sizeIndex])
  await user.click(within(sheet).getByTestId('pick-sell'))
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('generic transfer scan-out — unchanged', () => {
  test('no send-by-count panel, no Remove on a line, no ✕ on a scanned row, no bazar call', async () => {
    setAccessToken(fakeJwt('owner'))
    install(
      on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner' })),
      on('GET', `/transfers/${TID}`, () => json(200, transfer())),
      on('POST', `/transfers/${TID}/scan-out`, () => json(200, { success: true, code: 'SCANNED', pieceId: 'pc-1',
        barcode: 'P0000001', variantId: 'v-m', lineId: 'l-1', qtyOut: 3 })),
    )
    renderAt(`/transfers/${TID}/scan-out`, <Route path="/transfers/:id/scan-out" element={<TransferScanOut />} />)
    const user = userEvent.setup()
    await screen.findByTestId('transfer-scan-out')
    expect(screen.getByText('Linen shirt · White / M').closest('tr')!.querySelectorAll('td')).toHaveLength(2)
    await user.type(screen.getByPlaceholderText('Scan piece barcode…'), 'P0000001{Enter}')
    await screen.findByTestId('last-scan-feedback')
    await waitFor(() => expect(posts(`/transfers/${TID}/scan-out`)).toHaveLength(1))
    expect(screen.queryByTestId('send-by-count')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('remove-scan')).not.toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/bazar-events/'))).toBe(false)
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
  })
})

describe('bazar scan-out — send by count', () => {
  test('pick a size from what Main has, choose how many, send: one request with a request id', async () => {
    install(on('POST', `/bazar-events/${EVENT}/scan-out-by-count`, () => json(200, { success: true, code: 'SENT',
      variantId: 'v-l', count: 2, pieceIds: ['a', 'b'], qtyOut: 2, replayed: false })), ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Choose product and size' }))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    expect(sheet).toHaveTextContent('Send from the main warehouse')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    const sizes = within(sheet).getAllByTestId('pick-size')
    expect(sizes[1]).toHaveTextContent('5 at Main')
    expect(sizes[2]).toBeEnabled()   // no bazar price yet — it can still be sent
    await user.click(sizes[1])
    await user.click(within(sheet).getByTestId('pick-sell'))

    expect(await screen.findByTestId('send-by-count-size')).toHaveTextContent('Linen shirt · White / L')
    expect(screen.getByTestId('send-by-count-numbers')).toHaveTextContent('5 at Main')
    await user.click(screen.getByTestId('send-by-count-more'))
    expect(screen.getByTestId('send-by-count-qty')).toHaveTextContent('2')
    expect(screen.queryByTestId('send-by-count-warning')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send 2 pieces' }))
    await waitFor(() => expect(posts(`/bazar-events/${EVENT}/scan-out-by-count`)).toHaveLength(1))
    const body = posts(`/bazar-events/${EVENT}/scan-out-by-count`)[0].body as Record<string, unknown>
    expect(body).toMatchObject({ variant_id: 'v-l', quantity: 2 })
    expect(typeof body.client_request_id).toBe('string')
    expect(await screen.findByTestId('send-by-count-done')).toHaveTextContent('Sent 2 pieces to the bazar.')
    // The send-out table and Main's stock are read again.
    await waitFor(() => expect(calls.filter(c => c.url.endsWith(`/transfers/${TID}`)).length).toBeGreaterThan(1))
    expect(calls.filter(c => c.url.endsWith('/send-stock')).length).toBeGreaterThan(1)
  })

  test('taking Main below what online orders need warns, and sends only on a second tap', async () => {
    install(on('POST', `/bazar-events/${EVENT}/scan-out-by-count`, () => json(200, { success: true, code: 'SENT',
      variantId: 'v-m', count: 2, pieceIds: ['a', 'b'], qtyOut: 4, replayed: false })), ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await chooseSize(user, 0)                                   // M: 3 at Main, 2 needed online
    expect(await screen.findByTestId('send-by-count-numbers')).toHaveTextContent('3 at Main · 2 needed by online orders')
    expect(screen.queryByTestId('send-by-count-warning')).not.toBeInTheDocument()   // 1 leaves 2: enough
    await user.click(screen.getByTestId('send-by-count-more'))   // 2 leaves 1 < 2
    expect(screen.getByTestId('send-by-count-warning')).toHaveTextContent('2 online orders still need this size')
    await user.click(screen.getByRole('button', { name: 'Send 2 pieces' }))
    expect(posts(`/bazar-events/${EVENT}/scan-out-by-count`)).toHaveLength(0)     // not blocked — just a second tap
    await user.click(screen.getByRole('button', { name: 'Send 2 anyway' }))
    await waitFor(() => expect(posts(`/bazar-events/${EVENT}/scan-out-by-count`)).toHaveLength(1))
    expect(posts(`/bazar-events/${EVENT}/scan-out-by-count`)[0].body).toMatchObject({ variant_id: 'v-m', quantity: 2 })
  })

  test('changing the quantity after the warning asks again', async () => {
    install(...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await chooseSize(user, 0)
    await user.click(await screen.findByTestId('send-by-count-more'))
    await user.click(screen.getByRole('button', { name: 'Send 2 pieces' }))
    expect(screen.getByRole('button', { name: 'Send 2 anyway' })).toBeInTheDocument()
    await user.click(screen.getByTestId('send-by-count-more'))
    expect(screen.getByRole('button', { name: 'Send 3 pieces' })).toBeInTheDocument()
    expect(posts(`/bazar-events/${EVENT}/scan-out-by-count`)).toHaveLength(0)
  })

  test('the server refuses (only N at Main): its reason is shown', async () => {
    install(on('POST', `/bazar-events/${EVENT}/scan-out-by-count`, () => json(409, { code: 'BAZAR_NOT_ENOUGH_AT_MAIN',
      message_en: 'Only 1 of this size is available at the main warehouse.', message_ar: '…', details: { available: 1 } })),
      ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await chooseSize(user, 1)
    await user.click(await screen.findByRole('button', { name: 'Send 1 piece' }))
    expect(await screen.findByTestId('send-by-count-error')).toHaveTextContent('Only 1 of this size is available')
  })
})

describe('bazar scan-out — remove from this send', () => {
  test('"Remove N" on a line sends the size and how many', async () => {
    install(on('POST', `/bazar-events/${EVENT}/remove-from-send`, () => json(200, { success: true, code: 'SENT',
      variantId: 'v-m', count: 2, pieceIds: ['a', 'b'], qtyOut: 0, replayed: false })), ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    const row = (await screen.findByText('Linen shirt · White / M')).closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Remove' }))
    await user.click(within(row).getByTestId('remove-count-more'))
    expect(within(row).getByTestId('remove-count-more')).toBeDisabled()     // never more than on the send (2)
    await user.click(within(row).getByRole('button', { name: 'Remove 2' }))
    await waitFor(() => expect(posts(`/bazar-events/${EVENT}/remove-from-send`)).toHaveLength(1))
    const body = posts(`/bazar-events/${EVENT}/remove-from-send`)[0].body as Record<string, unknown>
    expect(body).toMatchObject({ variant_id: 'v-m', quantity: 2 })
    expect(body.code).toBeUndefined()
    expect(typeof body.client_request_id).toBe('string')
  })

  test('the ✕ on a scanned row takes that piece off the send', async () => {
    install(
      on('POST', `/bazar-events/${EVENT}/scan-out`, () => json(200, { success: true, code: 'SCANNED', pieceId: 'pc-1',
        barcode: 'P0000001', variantId: 'v-m', lineId: 'l-1', qtyOut: 3 })),
      on('POST', `/bazar-events/${EVENT}/remove-from-send`, () => json(200, { success: true, code: 'SENT',
        variantId: 'v-m', count: 1, pieceIds: ['pc-1'], qtyOut: 2, replayed: false })),
      ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await user.type(await screen.findByPlaceholderText('Scan piece barcode…'), 'P0000001{Enter}')
    const x = await screen.findByTestId('remove-scan')
    await user.click(x)
    await waitFor(() => expect(posts(`/bazar-events/${EVENT}/remove-from-send`)).toHaveLength(1))
    const body = posts(`/bazar-events/${EVENT}/remove-from-send`)[0].body as Record<string, unknown>
    expect(body).toMatchObject({ code: 'P0000001' })
    expect(body.variant_id).toBeUndefined()
    await waitFor(() => expect(screen.queryByTestId('remove-scan')).not.toBeInTheDocument())
  })

  test('a refused ✕ (no longer on the send) says why', async () => {
    install(
      on('POST', `/bazar-events/${EVENT}/scan-out`, () => json(200, { success: true, code: 'SCANNED', pieceId: 'pc-1',
        barcode: 'P0000001', variantId: 'v-m', lineId: 'l-1', qtyOut: 3 })),
      on('POST', `/bazar-events/${EVENT}/remove-from-send`, () => json(409, { code: 'BAZAR_NOT_ON_SEND',
        message_en: "This piece isn't on this send.", message_ar: '…', details: { on_send: 0 } })),
      ...bazarBase())
    renderAt(`/bazar/${EVENT}/scan-out`, bazarRoute)
    const user = userEvent.setup()
    await user.type(await screen.findByPlaceholderText('Scan piece barcode…'), 'P0000001{Enter}')
    await user.click(await screen.findByTestId('remove-scan'))
    expect(await screen.findByTestId('remove-scan-error')).toHaveTextContent("This piece isn't on this send.")
  })
})

describe('return — the refund is what the server gives back', () => {
  test('a discounted cart line: the button and the banner show the net price, not the full price', async () => {
    setAccessToken(fakeJwt('worker'))
    const sold = {
      id: 's-7', sale_number: 'B-0107', sold_at: new Date().toISOString(), business_day: TODAY, status: 'active',
      price: '960.00', net_price: '900.00', price_source: 'bulk', method: 'cash', piece_id: 'pc-9',
      piece_code: 'P0000909', variant_id: 'v-s', product_title: 'Linen shirt', variant_title: 'White / S',
      seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null, undone_at: null,
      replayed: false,
    }
    const live = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
                   paymentMethods: ['cash', 'card'] }
    install(
      on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'worker', bazar: true, bazarLive: live })),
      on('GET', `/bazar-events/${EVENT}/catalog`, () => json(200, { ...STOCK, event_status: 'live', products: [
        { ...STOCK.products[0], sizes: [{ variant_id: 'v-s', title: 'White / S', size: 'S', price: '960.00', left: 0 }] }] })),
      on('POST', '/sales/find', () => json(200, { variant_id: 'v-s', count: 1, sales: [sold] })),
    )
    renderAt(`/bazar/${EVENT}/return`, <Route path="/bazar/:id/return" element={<BazarReturn />} />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('return-find'))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    await user.click(within(sheet).getAllByTestId('pick-size')[0])
    await user.click(within(sheet).getByTestId('pick-sell'))
    await user.click(await screen.findByTestId('find-go'))
    await user.click(within(await screen.findByTestId('find-one')).getByTestId('find-use'))

    const banner = await screen.findByTestId('return-amount')
    expect(banner).toHaveTextContent('900')
    expect(banner).not.toHaveTextContent('960')
    const confirm = screen.getByTestId('return-confirm')
    expect(confirm).toHaveTextContent('900')
    expect(confirm).not.toHaveTextContent('960')
    expect(screen.getByTestId('return-sale')).not.toHaveTextContent('960')
  })
})
