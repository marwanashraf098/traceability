import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import BazarReportPage from '../pages/bazar/BazarReport'
import CloseDayPage from '../pages/bazar/r3/CloseDayPage'
import EndEventPage from '../pages/bazar/r3/EndEventPage'
import BazarSell from '../pages/bazar/phone/BazarSell'
import { RemoveCount, SendByCountPanel } from '../pages/bazar/SendByCount'
import { PickSheet } from '../pages/bazar/phone/PickSheet'
import { BackToSellingBar, rememberSelling } from '../pages/bazar/BackToSelling'
import { counterSize } from '../pages/bazar/r3/SetupView'
import { needsScan, productSizeName, sizeName } from '../bazar/catalog'
import { pieceTitle, variantName } from '../bazar/format'
import type { BazarReport } from '../bazar/api'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

// U1b — Bazar smaller fixes from the 2026-11-22 E2E test (screen side). Each block below names its ClickUp item.

vi.mock('../pages/scanpair/CameraReader', () => ({ default: () => null }))

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
    blob: async () => new Blob(['x']),
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: (body: unknown) => Promise<Response> }
let calls: { method: string; path: string; url: string; body: unknown }[] = []
function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    let body: unknown
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ method, path, url, body })
    for (const h of hs) if (h.method === method && h.path === path) return h.fn(body)
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: (body: unknown) => Promise<Response>): Handler => ({ method, path, fn })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname}</div> }

function renderAt(path: string) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes>
              <Route path="/bazar" element={<Where />} />
              <Route path="/bazar/:id" element={<BazarEventPage />} />
              <Route path="/bazar/:id/report" element={<BazarReportPage />} />
              <Route path="/bazar/:id/close-day" element={<CloseDayPage />} />
              <Route path="/bazar/:id/end-event" element={<EndEventPage />} />
              <Route path="/bazar/:id/sell" element={<BazarSell />} />
              <Route path="*" element={<><Where /><BackToSellingBar /></>} />
            </Routes>
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
  setAccessToken(jwt('owner'))
  testI18n.changeLanguage('en')
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

function detail(status: 'draft' | 'live' | 'ended', over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status,
    location_id: 'l1', location_name: 'Zamalek Bazar', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: status === 'draft' ? 'preparing' : status === 'live' ? 'sent' : 'reconciling', transfer_sent_at: null,
    transfer_shopify_sync_mode: null, online_stock: 'remove', online_stock_locked: false,
    payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null,
    started_at: null, ended_at: status === 'ended' ? '2026-11-22T20:09:00Z' : null,
    counts: { sent: 0, at_bazar: 0, sold: 0, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: {}, total: '0', sales: 0 },
    totals: { by_method: {}, total: '0', sales: 0, returns: 0 },
    ...over,
  }
}

// ── units ────────────────────────────────────────────────────────────────────

describe('no "Default Title" anywhere', () => {
  test('variantName / pieceTitle / sizeName drop Shopify\'s "Default Title" and blanks', () => {
    expect(variantName('Default Title')).toBeNull()
    expect(variantName(' default title ')).toBeNull()
    expect(variantName('')).toBeNull()
    expect(variantName('White / M')).toBe('White / M')
    expect(pieceTitle({ product_title: 'Oxygen', variant_title: 'Default Title' })).toBe('Oxygen')
    expect(pieceTitle({ product_title: 'Shirt', variant_title: 'M' })).toBe('Shirt · M')
    const p = { number: 1, product_id: 'p', title: 'Oxygen', colour: null, image_url: null, category: null, left: 1,
                price_min: null, price_max: null, sizes: [] }
    const s = { variant_id: 'v', title: 'Default Title', size: null, price: '820', left: 1 }
    expect(sizeName(p, s)).toBe('')
    expect(productSizeName(p, s)).toBe('Oxygen')
  })

  test('the phone pick sheet: a product without sizes reads "One size", never "Default Title"', async () => {
    const catalog = { event_id: EV, event_name: 'Z', event_status: 'live' as const, categories: [], products: [
      { number: 3, product_id: 'p', title: 'Oxygen', colour: null, image_url: null, category: null, left: 1,
        price_min: '820', price_max: '820', sizes: [{ variant_id: 'v', title: 'Default Title', size: null, price: '820', left: 1 }] }] }
    render(<MemoryRouter><I18nextProvider i18n={testI18n}>
      <PickSheet catalog={catalog} mode="quick" onPick={() => {}} onClose={() => {}} />
    </I18nextProvider></MemoryRouter>)
    const row = (await screen.findAllByTestId('pick-product'))[0]
    await userEvent.click(within(row).getAllByRole('button')[0])
    const size = within(row).getByTestId('pick-size')
    expect(size.textContent).toContain(en.bazar.pick.oneSize)
    expect(document.body.textContent).not.toContain('Default Title')
  })

  test('the "EGP at bazar" tile steps its size down for big numbers', () => {
    expect(counterSize('960')).toBe('text-h4')
    expect(counterSize('7,607')).toBe('text-h4')
    expect(counterSize('76,075')).toBe('text-h4')
    expect(counterSize('760,750')).toBe('text-body-lg')
    expect(counterSize('7,607,500')).toBe('text-body-lg')
    expect(counterSize('76,075,000')).toBe('text-body')
  })

  test('needsScan: only labelled pieces left', () => {
    const s = (left: number, unl?: number) => ({ variant_id: 'v', title: 'M', size: 'M', price: '1', left, left_unlabelled: unl })
    expect(needsScan(s(2, 0))).toBe(true)
    expect(needsScan(s(2, 1))).toBe(false)
    expect(needsScan(s(0, 0))).toBe(false)
    expect(needsScan(s(2))).toBe(false)   // a view without the count (send stock): never asks
  })
})

// ── Get ready ───────────────────────────────────────────────────────────────

const PRICES = [
  { variant_id: 'v-eq', sku: null, variant_title: 'Default Title', product_title: 'Oxygen', shopify_price: '820', price: '820', source: 'default' },
  { variant_id: 'v-rule', sku: 'SH-M', variant_title: 'M', product_title: 'Linen shirt', shopify_price: '1200', price: '960', source: 'bulk' },
  { variant_id: 'v-custom', sku: null, variant_title: 'Indigo', product_title: 'Denim jacket', shopify_price: '1000', price: '800', source: 'override' },
]

function draftHandlers(over: Record<string, unknown> = {}, extra: Handler[] = []) {
  return [
    on('GET', `/bazar-events/${EV}`, () => json(200, detail('draft', over))),
    on('GET', `/bazar-events/${EV}/prices`, () => json(200, PRICES)),
    on('GET', '/transfers/t-1', () => json(200, { lines: [{ variant_id: 'v-eq', qty_out: 1 }] })),
    on('GET', `/bazar-events/${EV}/sellers`, () => json(200, { sellers: [], workers_allowed: true })),
    ...extra,
  ]
}

describe('Get ready', () => {
  test('delete a draft nothing was scanned out for: confirm → DELETE → back to the bazar list', async () => {
    install(...draftHandlers({ deletable: true }, [on('DELETE', `/bazar-events/${EV}`, () => json(204, null))]))
    renderAt(`/bazar/${EV}`)
    await userEvent.click(await screen.findByTestId('bazar-delete'))
    expect(screen.getByText(/Nothing was scanned out for it yet/)).toBeTruthy()
    await userEvent.click(screen.getAllByRole('button', { name: 'Delete bazar' }).at(-1)!)
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.path === `/bazar-events/${EV}`)).toBe(true))
    expect((await screen.findByTestId('where')).textContent).toBe('/bazar')
  })

  test('no Delete once something was scanned out (deletable false)', async () => {
    install(...draftHandlers({ deletable: false }))
    renderAt(`/bazar/${EV}`)
    await screen.findByTestId('bazar-setup')
    expect(screen.queryByTestId('bazar-delete')).toBeNull()
  })

  test('Keep mode: the bottom bar says online stock doesn\'t change; Remove keeps its own wording', async () => {
    install(...draftHandlers({ online_stock: 'leave' }))
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('bazar-setup-hint')).textContent).toBe(
      "Mark them as sent when they leave the warehouse — your online stock doesn't change.")
    cleanup()
    install(...draftHandlers())
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('bazar-setup-hint')).textContent).toContain('they leave online stock then')
  })

  test('online stock: says it\'s for the whole location; a clash names and links the bazar in the way', async () => {
    install(...draftHandlers({}, [on('PUT', `/bazar-events/${EV}/online-stock`, () => json(409, {
      code: 'BAZAR_ONLINE_STOCK_CONFLICT', message_en: '"Maadi Pop-up" is at this location too, …', message_ar: 'x',
      details: { blocking_event_id: 'ev-maadi', blocking_event_name: 'Maadi Pop-up' } }))]))
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('bazar-online-row')).textContent).toContain('Applies to every bazar at Zamalek Bazar.')
    await userEvent.click(screen.getByTestId('bazar-online-change'))
    const dialog = await screen.findByTestId('bazar-online-dialog')
    expect(within(dialog).getByTestId('bazar-online-whole-location').textContent).toContain('Zamalek Bazar')
    await userEvent.click(within(dialog).getByTestId('online-stock-leave'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    const link = await within(dialog).findByTestId('bazar-blocking-link')
    expect(link.getAttribute('href')).toBe('/bazar/ev-maadi')
    expect(link.textContent).toBe('Open “Maadi Pop-up”')
  })
})

describe('Prices', () => {
  async function prices() {
    install(...draftHandlers({}, [on('PUT', `/bazar-events/${EV}/prices/v-eq`, () => json(200, PRICES))]))
    renderAt(`/bazar/${EV}`)
    const card = await screen.findByTestId('bazar-prices')
    await within(card).findByText('Oxygen')
    return card
  }

  test('Shopify price crossed out only when the bazar price differs; Custom shows its % change; no Default Title', async () => {
    const card = await prices()
    expect(screen.getByTestId('price-shop-v-eq').className).not.toContain('line-through')
    expect(screen.getByTestId('price-shop-v-rule').className).toContain('line-through')
    expect(within(screen.getByTestId('price-row-v-custom')).getByText(/Custom/).textContent).toContain('−20%')
    expect(card.textContent).not.toContain('Default Title')
    expect(screen.getByTestId('price-row-v-rule').textContent).toContain('M · SH-M')
  })

  test('an emptied box goes back to its price and says a price is needed; no save', async () => {
    await prices()
    const input = within(screen.getByTestId('price-row-v-eq')).getByRole('textbox')
    await userEvent.clear(input)
    fireEvent.blur(input)
    expect((input as HTMLInputElement).value).toBe('820')
    expect(screen.getByTestId('price-kept-v-eq').textContent).toBe('A price is needed. Still 820.')
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
  })

  test('the red outline clears as soon as a valid price is typed', async () => {
    await prices()
    const input = within(screen.getByTestId('price-row-v-eq')).getByRole('textbox')
    await userEvent.clear(input)
    await userEvent.type(input, '81.5')
    fireEvent.blur(input)
    expect(input.getAttribute('aria-invalid')).toBe('true')
    await userEvent.clear(input)
    await userEvent.type(input, '820')
    expect(input.getAttribute('aria-invalid')).not.toBe('true')
  })

  test('discount box: empty with a hint until Apply, then "Applied −20%"', async () => {
    await prices()
    const box = screen.getByRole('textbox', { name: /discount/i }) as HTMLInputElement
    expect(box.value).toBe('')
    expect(box.placeholder).toBe('')
    expect(screen.getByTestId('bazar-discount-state').textContent).toBe('Type a discount, then Apply.')
    cleanup()
    install(...draftHandlers({ discount_pct: '20.00', round_to: '5.00' }))
    renderAt(`/bazar/${EV}`)
    expect((await screen.findByTestId('bazar-discount-state')).textContent).toBe('Applied −20%')
  })
})

// ── Remove from this send ───────────────────────────────────────────────────

describe('Remove from this send', () => {
  function renderRemove() {
    render(
      <MemoryRouter><I18nextProvider i18n={testI18n}>
        <RemoveCount eventId={EV} variantId="v1" max={3} onRemoved={() => {}} />
      </I18nextProvider></MemoryRouter>,
    )
  }

  test('a labelled piece removed by count is named: "Removed P000057 — put this one back."', async () => {
    install(on('POST', `/bazar-events/${EV}/remove-from-send`, () => json(200, { success: true, code: 'SENT', variantId: 'v1',
      count: 1, pieceIds: ['p1'], qtyOut: 2, replayed: false, labelled_codes: ['P000057'] })))
    renderRemove()
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await userEvent.click(screen.getByRole('button', { name: /Remove 1/ }))
    expect((await screen.findByTestId('remove-count-done')).textContent).toBe('Removed P000057 — put this one back.')
  })

  test('an unlabelled piece: says it had no label', async () => {
    install(on('POST', `/bazar-events/${EV}/remove-from-send`, () => json(200, { success: true, code: 'SENT', variantId: 'v1',
      count: 1, pieceIds: ['p1'], qtyOut: 2, replayed: false, labelled_codes: [] })))
    renderRemove()
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await userEvent.click(screen.getByRole('button', { name: /Remove 1/ }))
    expect((await screen.findByTestId('remove-count-done')).textContent).toBe('Removed 1 piece without a label.')
  })
})

describe('Send by count', () => {
  // U1c: a send by count takes only pieces WITHOUT a label — the labelled ones are scanned; no "collect" list.
  function stockOf(left: number, unlabelled: number) {
    return { event_id: EV, event_name: 'Z', event_status: 'draft', categories: [], products: [
      { number: null, product_id: 'p1', title: 'Linen shirt', colour: null, image_url: null, category: null, left,
        price_min: '960', price_max: '960',
        sizes: [{ variant_id: 'v1', title: 'M', size: 'M', price: '960', left, left_unlabelled: unlabelled, committed: 0 }] }] }
  }

  async function pickSize() {
    render(<MemoryRouter><I18nextProvider i18n={testI18n}><SendByCountPanel eventId={EV} onSent={() => {}} /></I18nextProvider></MemoryRouter>)
    await waitFor(() => expect(calls.some(c => c.path.endsWith('/send-stock'))).toBe(true))
    await userEvent.click(screen.getByRole('button', { name: en.bazar.count.chooseProduct }))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await userEvent.click(within(within(sheet).getAllByTestId('pick-product')[0]).getAllByRole('button')[0])
    await userEvent.click(within(sheet).getAllByTestId('pick-size')[0])
    await userEvent.click(within(sheet).getByTestId('pick-sell'))
  }

  test('fewer without a label than asked: the split is shown, the picker stops at them, the partial warning, no codes', async () => {
    install(
      on('GET', `/bazar-events/${EV}/send-stock`, () => json(200, stockOf(5, 2))),
      on('POST', `/bazar-events/${EV}/scan-out-by-count`, () => json(200, { success: true, code: 'SENT_PARTIAL', variantId: 'v1',
        count: 2, pieceIds: ['a', 'b'], qtyOut: 2, replayed: false, requested: 3, labelled_at_main: 3,
        message_en: 'Only 2 without labels. The other 1 has a label — scan it.', message_ar: '…' })),
    )
    await pickSize()
    expect(await screen.findByTestId('send-by-count-numbers')).toHaveTextContent('2 without labels · 3 with labels (scan them)')
    const more = screen.getByTestId('send-by-count-more')
    await userEvent.click(more)
    expect(more).toBeDisabled()                                     // 2 = the ones without a label, not 5
    await userEvent.click(screen.getByRole('button', { name: /Send 2/ }))
    expect((await screen.findByTestId('send-by-count-partial')).textContent)
      .toBe('Only 2 without labels. The other 1 has a label — scan it.')
    expect(screen.queryByTestId('send-by-count-collect')).toBeNull()
  })

  test('every piece at Main has a label: says scan them (or reprint), send is off', async () => {
    install(on('GET', `/bazar-events/${EV}/send-stock`, () => json(200, stockOf(3, 0))))
    await pickSize()
    expect((await screen.findByTestId('send-by-count-all-labelled')).textContent).toBe(
      'These pieces have labels — scan them instead. If you never printed their labels, reprint them from the receipt.')
    expect(screen.getByRole('button', { name: /Send 1/ })).toBeDisabled()
    expect(calls.some(c => c.path.endsWith('/scan-out-by-count'))).toBe(false)
  })
})

// ── Selling ─────────────────────────────────────────────────────────────────

const LIVE = { eventId: EV, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY, paymentMethods: ['cash'] }
const CATALOG = {
  event_id: EV, event_name: 'Zamalek Winter Bazar', event_status: 'live', categories: [],
  products: [
    { number: 3, product_id: 'p-oxy', title: 'Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition', colour: null,
      image_url: null, category: null, left: 2, price_min: '820', price_max: '820', sizes: [
        { variant_id: 'v-oxy', title: 'Default Title', size: null, price: '820', left: 2, left_unlabelled: 0 }] },
    { number: 4, product_id: 'p-scarf', title: 'Knit scarf', colour: null, image_url: null, category: null, left: 1,
      price_min: '300', price_max: '300', sizes: [
        { variant_id: 'v-scarf', title: 'Default Title', size: null, price: '300', left: 1, left_unlabelled: 1 }] },
  ],
}

function sellHandlers(extra: Handler[] = []) {
  setAccessToken(jwt('worker'))
  return [
    on('GET', '/me', () => json(200, { name: 'X', email: 'x@y', role: 'worker', bazar: true, bazarLive: LIVE })),
    on('GET', `/bazar-events/${EV}/catalog`, () => json(200, CATALOG)),
    on('GET', `/bazar-events/${EV}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EV}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0, by_method: {}, per_seller: [] })),
    ...extra,
  ]
}

describe('Selling — the product grid', () => {
  test('a size with only labelled pieces left asks for a scan of the piece; nothing is sold', async () => {
    install(...sellHandlers())
    renderAt(`/bazar/${EV}/sell`)
    const grid = await screen.findByTestId('bazar-product-grid')
    const tiles = await within(grid).findAllByTestId('grid-tile')
    await userEvent.click(tiles[0])
    expect((await screen.findByTestId('bazar-scan-ask')).textContent).toContain('scan the piece in the customer\'s hand')
    expect(calls.some(c => c.method === 'POST' && c.path.endsWith('/sell'))).toBe(false)
    await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId('bazar-scan-input')))
  })

  test('a size with an unlabelled piece left sells by tap as before', async () => {
    install(...sellHandlers([on('POST', `/bazar-events/${EV}/sell`, () => json(200, {
      id: 's1', sale_number: 'B-0001', sold_at: new Date().toISOString(), business_day: TODAY, status: 'active', price: '300',
      price_source: 'bulk', method: 'cash', piece_id: 'p', piece_code: null, variant_id: 'v-scarf', product_title: 'Knit scarf',
      variant_title: 'Default Title', seller_id: 'u', seller_name: 'O', customer_name: null, customer_phone: null,
      undone_at: null, replayed: false }))]))
    renderAt(`/bazar/${EV}/sell`)
    const tiles = await within(await screen.findByTestId('bazar-product-grid')).findAllByTestId('grid-tile')
    await userEvent.click(tiles[1])
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.path.endsWith('/sell'))).toBe(true))
    expect(screen.queryByTestId('bazar-scan-ask')).toBeNull()
  })

  test('names: two lines, never cut on one; a product without sizes shows no "—" or Default Title', async () => {
    install(...sellHandlers())
    renderAt(`/bazar/${EV}/sell`)
    const grid = await screen.findByTestId('bazar-product-grid')
    const names = await within(grid).findAllByTestId('grid-tile-name')
    expect(names[0].className).toContain('line-clamp-2')
    expect(names[0].className).not.toContain('truncate')
    const tile = within(grid).getAllByTestId('grid-tile')[0]
    expect(tile.textContent).not.toContain('—')
    expect(tile.textContent).not.toContain('Default Title')
    expect(tile.getAttribute('title')).toBe('Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition')
  })

  test('the sell screen is remembered for "Back to selling"', async () => {
    install(...sellHandlers())
    renderAt(`/bazar/${EV}/sell`)
    await screen.findByTestId('bazar-scan-input')
    expect(sessionStorage.getItem('bazar-selling-event')).toBe(EV)
  })
})

describe('Back to selling', () => {
  test('another screen shows the bar to the sell screen; Hide removes it', async () => {
    rememberSelling(EV)
    install()
    renderAt('/overview')
    const bar = await screen.findByTestId('back-to-selling')
    expect(within(bar).getByRole('link', { name: 'Back to selling' }).getAttribute('href')).toBe(`/bazar/${EV}/sell`)
    await userEvent.click(within(bar).getByRole('button', { name: 'Hide' }))
    expect(screen.queryByTestId('back-to-selling')).toBeNull()
    expect(sessionStorage.getItem('bazar-selling-event')).toBeNull()
  })

  test('nothing remembered → no bar', async () => {
    install()
    renderAt('/overview')
    await screen.findByTestId('where')
    expect(screen.queryByTestId('back-to-selling')).toBeNull()
  })
})

// ── Close day ───────────────────────────────────────────────────────────────

describe('Close day', () => {
  test('neutral until a number is typed: no red "Short" for the whole day; typed → the difference', async () => {
    const result = (entered: number) => ({
      opening_float: '0', cash_kept: '0', take_home: String(entered), not_received_total: String(900 - entered),
      not_received: { cash: String(900 - entered), card: '0', instapay: '0' }, over_total: '0',
      methods: [{ method: 'cash', expected: '900', entered: String(entered), difference: String(entered - 900),
                  state: entered === 900 ? 'even' : 'short' }],
    })
    install(
      on('GET', `/bazar-events/${EV}`, () => json(200, detail('live'))),
      on('GET', `/bazar-events/${EV}/days`, () => json(200, { today: TODAY, payment_methods: ['cash'], days: [{
        business_day: TODAY, day_number: 1, is_today: true, needs_close: true, money: { by_method: {}, total: '900', sales: 3 },
        status: 'open', close: null, close_count: 0, opening_float_default: { amount: '0', source: 'previous_close' } }] })),
      { method: 'GET', path: `/bazar-events/${EV}/days/${TODAY}/close-preview`, fn: () => {
        const url = calls[calls.length - 1].url
        const cash = new URL(url, 'http://x').searchParams.get('cash')
        return json(200, { business_day: TODAY, status: 'open', money: { by_method: {}, total: '900', sales: 3 },
          opening_float_source: 'previous_close', needs_opening_float: false, result: result(cash ? Number(cash) : 0), problems: [] })
      } },
    )
    renderAt(`/bazar/${EV}/close-day`)
    const summary = await screen.findByTestId('close-summary')
    await waitFor(() => expect(within(summary).getByTestId('close-row-cash').textContent).toContain('Not counted'))
    expect(summary.textContent).not.toMatch(/Short/)
    expect(within(summary).getByTestId('close-difference').textContent).toContain('—')
    expect(screen.getByTestId('close-pending').textContent).toBe('Type what you counted to see the difference.')
    expect(screen.queryByTestId('close-not-received')).toBeNull()

    await userEvent.type(screen.getByTestId('close-cash'), '500')
    await waitFor(() => expect(within(screen.getByTestId('close-summary')).getByTestId('close-row-cash').textContent).toMatch(/Short/))
    expect(screen.queryByTestId('close-pending')).toBeNull()
  })
})

// ── End event ───────────────────────────────────────────────────────────────

describe('End event', () => {
  test('the step header counts live with each scan back ("1 of 2 scanned" → "2 of 2 scanned")', async () => {
    const piece = (id: string) => ({ piece_id: id, piece_code: `P-${id}`, product_title: 'Linen shirt', variant_title: 'M', sku: null })
    const left = (back: number, out: string[]) => ({
      event_id: EV, event_status: 'ended', transfer_id: 't-1', transfer_status: 'reconciling', balanced: out.length === 0,
      counts: { sent: 4, sold: 2, back, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: out.length },
      outstanding: out.map(piece), damaged_to_bring_home: [],
    })
    install(
      on('GET', `/bazar-events/${EV}`, () => json(200, detail('ended'))),
      on('GET', `/bazar-events/${EV}/leftovers`, () => json(200, left(1, ['b']))),
      on('POST', `/bazar-events/${EV}/scan-back`, () => json(200, { result: 'back_good', piece: piece('b'), replayed: false,
        leftovers: left(2, []) })),
    )
    renderAt(`/bazar/${EV}/end-event`)
    const steps = await screen.findByTestId('end-steps')
    expect(steps.textContent).toContain('1 of 2 scanned')
    await userEvent.type(await screen.findByTestId('bazar-code-input'), 'P-b{Enter}')
    await waitFor(() => expect(screen.getByTestId('end-steps').textContent).toContain('2 of 2 scanned'))
  })
})

// ── Report ──────────────────────────────────────────────────────────────────

function report(over: Partial<BazarReport> = {}): BazarReport {
  return {
    event: { id: EV, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: '2026-11-20', ends_on: '2026-11-22',
             started_at: null, ended_at: null, location_name: 'Zamalek Bazar' },
    revenue: '2000', money_in: '2000', pieces_sold: 6, pieces_sent: 10, sell_through: '0.6', average_sale: '333',
    sales: 6, discounts: '0', returns: 0, exchanges: 0, sales_by_day: [],
    busiest_hours: [{ hour: 12, sales: 1 }, { hour: 13, sales: 0 }, { hour: 14, sales: 2 }],
    top_products: [{ product_title: 'Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition', sold: 6, sent: 8,
                     sell_through: '0.75', revenue: '2000' }],
    unsold_products: [{ product_title: 'Knit scarf', sold: 0, sent: 2 }],
    has_sizes: false, top_sizes: [],
    payment_split: [], money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0',
      instapay_not_received: '0', total: '0', days_closed: 2, days_not_closed: 0 },
    per_seller: [], customers_saved: 0,
    transfer: { sent: 10, sold: 6, back: 4, damaged: 0, damaged_at_bazar: 0, lost: 0, outstanding: 0,
                transfer_id: 't-1', status: 'closed', balanced: true },
    ...over,
  } as BazarReport
}

describe('Report', () => {
  test('What sold lists what didn\'t sell (0/N); long names not cut; no-size products: "These products have no sizes."', async () => {
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, report())))
    renderAt(`/bazar/${EV}/report`)
    const sold = await screen.findByTestId('report-top-products')
    const unsold = within(sold).getByTestId('report-unsold')
    expect(unsold.textContent).toContain('Knit scarf')
    expect(unsold.textContent).toContain('0/2')
    const name = within(sold).getByText('Oxygen Long Sleeve Cotton Crew-Neck, Navy Blue Edition')
    expect(name.className).not.toContain('truncate')
    // U1c: the Top sizes card is gone — "What sold" names the variants itself.
    expect(screen.queryByTestId('report-no-sizes')).toBeNull()
    expect(screen.queryByTestId('report-top-sizes')).toBeNull()
  })

  test('the hours chart shows every hour it gets — quiet ones too', async () => {
    install(on('GET', `/bazar-events/${EV}/report`, () => json(200, report())))
    renderAt(`/bazar/${EV}/report`)
    const hours = await screen.findByTestId('report-hours')
    expect(hours.textContent).toContain('12:00')
    expect(hours.textContent).toContain('13:00')
    expect(hours.textContent).toContain('14:00')
  })

  test('Export orders (CSV) and Download PDF fetch their files', async () => {
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }))
    install(
      on('GET', `/bazar-events/${EV}/report`, () => json(200, report())),
      on('GET', `/bazar-events/${EV}/report/orders.csv`, () => json(200, {})),
      on('GET', `/bazar-events/${EV}/report.pdf`, () => json(200, {})),
    )
    renderAt(`/bazar/${EV}/report`)
    const box = await screen.findByTestId('report-downloads')
    await userEvent.click(within(box).getByRole('button', { name: 'Export orders (CSV)' }))
    await waitFor(() => expect(calls.some(c => c.path === `/bazar-events/${EV}/report/orders.csv`)).toBe(true))
    await userEvent.click(within(box).getByRole('button', { name: 'Download PDF' }))
    await waitFor(() => expect(calls.some(c => c.path === `/bazar-events/${EV}/report.pdf`)).toBe(true))
    expect(calls.find(c => c.path === `/bazar-events/${EV}/report.pdf`)!.url).toContain('lang=en')
  })
})
