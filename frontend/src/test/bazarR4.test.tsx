import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarSell from '../pages/bazar/phone/BazarSell'
import BazarReturn from '../pages/bazar/phone/BazarReturn'
import BazarExchange from '../pages/bazar/phone/BazarExchange'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'

// Bazar R4 — the Sell, Return and Exchange restyle (design/bazar-v2). Appearance only: these tests pin the new look's
// states (header, quick SOLD card, cart, return, exchange pay / give back / even) and guard that the scan path —
// useScanner, useScanBurst, CameraReader, the Sell scan input and the cart's refocus listener — is byte-for-byte what
// it was before R4.

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
const LIVE = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
               paymentMethods: ['cash', 'card', 'instapay'] }

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

let n = 0
function sale(over: Partial<Record<string, unknown>> = {}) {
  n += 1
  return {
    id: `s-${n}`, sale_number: `B-${String(n).padStart(4, '0')}`, sold_at: new Date().toISOString(),
    business_day: TODAY, status: 'active', price: '960.00', price_source: 'bulk', method: 'cash',
    piece_id: `p-${n}`, piece_code: 'P0000231', variant_id: 'v-m', product_title: 'Linen shirt',
    variant_title: 'White / M', seller_id: 'u-worker', seller_name: 'Omar', customer_name: null,
    customer_phone: null, undone_at: null, replayed: false, ...over,
  }
}

const CATALOG = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live',
  categories: [{ name: 'Shirts', count: 1 }],
  products: [
    { number: 12, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: 'Shirts',
      left: 3, price_min: '960.00', price_max: '960.00', sizes: [
        { variant_id: 'v-s', title: 'White / S', size: 'S', price: '960.00', left: 0 },
        { variant_id: 'v-m', title: 'White / M', size: 'M', price: '960.00', left: 3 },
      ] },
  ],
}

const PREVIEW: Record<string, { title: string; variant: string; price: string }> = {
  P0000001: { title: 'Linen shirt', variant: 'M', price: '500.00' },
  P0000002: { title: 'Linen shirt', variant: 'L', price: '800.00' },
}

function today(over: Partial<Record<string, unknown>> = {}) {
  return { business_day: TODAY, total: '18920.00', sale_count: 23, returns: 2, exchanges: 1,
           by_method: { cash: '0' }, per_seller: [], ...over }
}

function base(opts: { role?: 'worker' | 'owner'; name?: string | null; sales?: unknown[] } = {}): Handler[] {
  const role = opts.role ?? 'worker'
  let meReads = 0
  setAccessToken(fakeJwt(role))
  const meBody = { name: opts.name === undefined ? 'Marawan' : opts.name, email: 'x@y', role, bazar: true, bazarLive: LIVE }
  return [
    // name null: the capabilities read (first /me) works, "Selling as"'s own read (the second) fails.
    on('GET', '/api/v1/me', () => (opts.name === null && ++meReads > 1 ? json(500, {}) : json(200, meBody))),
    on('GET', `/bazar-events/${EVENT}/catalog`, () => json(200, CATALOG)),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: opts.sales ?? [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, today())),
    on('POST', '/cart/preview', init => {
      const codes = (JSON.parse(String(init?.body)) as { codes: string[] }).codes
      const lines = codes.map((c, index) => {
        const p = PREVIEW[c]
        return p ? { index, code: c, piece_id: `pc-${c}`, piece_code: c, variant_id: 'v-m', product_title: p.title,
                     variant_title: p.variant, price: p.price, error: null }
          : { index, code: c, error: { code: 'BAZAR_PIECE_NOT_HERE', message_en: 'x', message_ar: 'x' } }
      })
      return json(200, { lines, subtotal: '0', ok: true })
    }),
  ]
}

function renderAt(path: string, i18n = testI18n) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18n}>
          <ToastProvider>
            <Routes>
              <Route path="/bazar/:id/sell" element={<BazarSell />} />
              <Route path="/bazar/:id/return" element={<BazarReturn />} />
              <Route path="/bazar/:id/exchange" element={<BazarExchange />} />
              <Route path="/bazar/:id/home" element={<div data-testid="home-page" />} />
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
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  sellTiming.undoCardMs = 8_000
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

// ── Safety: the scan path is untouched ────────────────────────────────────────────────────────────────────────────

const src = (p: string) => readFileSync(resolve(__dirname, '..', p), 'utf8')
const sha = (s: string) => createHash('sha256').update(s).digest('hex')
const flat = (s: string) => s.replace(/\s+/g, ' ')

describe('R4 safety guard — the scan path is byte-for-byte as before R4', () => {
  test.each([
    ['hooks/useScanner.ts', '192d281261b42ea0b7464d3bdb15b342feaf46a2c284f39cbfac46acd6610697'],
    ['bazar/useScanBurst.ts', '67ac2123d8b807e025fcbd5c12acdc07fa6b03fb3dde03b8c5497a51695e1203'],
    ['pages/scanpair/CameraReader.tsx', '4fcaff9f93f2a94b4f835ef0891eb4651d42099aaf0f56ee6671d3dc6653b66a'],
  ])('%s is unchanged', (path, hash) => {
    expect(sha(src(path))).toBe(hash)
  })

  test('the Sell scan input keeps every prop', () => {
    const m = /<input\n\s*ref=\{scanner\.inputRef\}[\s\S]*?data-testid="bazar-scan-input"\n\s*\/>/.exec(src('pages/bazar/phone/BazarSell.tsx'))
    expect(m).not.toBeNull()
    expect(sha(flat(m![0]))).toBe('f6168e93265bc2d5637800d8f3ae91323dc936eb8ec18b86763d7b57040b11e9')
  })

  test('the Sell scan routing (useScanner options, quick / cart / #number / tap / camera reads) is unchanged', () => {
    const s = src('pages/bazar/phone/BazarSell.tsx')
    const block = s.slice(s.indexOf('  const scanner = useScanner({'), s.indexOf('  const onCameraError'))
    expect(sha(flat(block))).toBe('c3d04c31d76da117776fe2d00532a4b551fefff341d9c447990b2fa485b3ca4a')
  })

  test('the cart\'s click-refocus listener and its scan-burst hook are unchanged', () => {
    const s = src('pages/bazar/phone/CartMode.tsx')
    const m = /  useEffect\(\(\) => \{\n    const keep = [\s\S]*?\n  \}, \[\]\)\n/.exec(s)
    expect(m).not.toBeNull()
    expect(sha(flat(m![0]))).toBe('f525dfcb63aeb1b5468de9dcb0b20fc62658594ddfcec71d4f8f83d0579e8fb9')
    expect(s).toContain('useScanBurst(summaryRef, cart.add)')
  })
})

// ── Sell ───────────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Sell — header and quick SOLD card', () => {
  test('header: event, LIVE · Day 2 of 3, "Selling as {name}", today total + sales count, Return / exchange', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/sell`)
    const header = await screen.findByTestId('bazar-sell-header')
    expect(header).toHaveTextContent('Selling · Zamalek Winter Bazar')
    expect(header).toHaveTextContent('Live · Day 2 of 3')
    await waitFor(() => expect(header).toHaveTextContent('Selling as Marawan'))
    await waitFor(() => expect(header).toHaveTextContent('18,920 EGP'))
    expect(header).toHaveTextContent('23 sales')
    expect(screen.getByTestId('sell-return')).toHaveAttribute('href', `/bazar/${EVENT}/return`)
    expect(screen.queryByTestId('bazar-sell-footer')).toBeNull()
    expect(screen.queryByText(/default pay/)).toBeNull()
    expect(screen.queryByRole('link', { name: 'Exchange' })).toBeNull()      // the old Sell / Return / Exchange tabs
  })

  test('/me failing hides the "Selling as" line (nothing else changes)', async () => {
    install(...base({ name: null }))
    renderAt(`/bazar/${EVENT}/sell`)
    const header = await screen.findByTestId('bazar-sell-header')
    await waitFor(() => expect(header).toHaveTextContent('18,920 EGP'))
    expect(header).not.toHaveTextContent('Selling as')
  })

  test('mode switch reads "Quick · scan = sold" / "Cart" but is named Quick / Cart; "Scanner ready" is display only', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/sell`)
    const quick = await screen.findByRole('radio', { name: 'Quick' })
    expect(quick).toHaveTextContent('Quick · scan = sold')
    expect(screen.getByRole('radio', { name: 'Cart' })).toHaveTextContent('Cart')
    expect(screen.getByTestId('scanner-ready')).toHaveTextContent('Scanner ready')
    expect(screen.getByTestId('camera-state')).toHaveTextContent('Camera ready')
    expect(screen.getByText('Every scan is a sale on its own. Cash unless you change it.')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Type/ }))                // typing: no "Scanner ready"
    expect(screen.queryByTestId('scanner-ready')).toBeNull()
    await user.click(screen.getByRole('button', { name: /Type/ }))
    expect(screen.getByTestId('scanner-ready')).toBeInTheDocument()
    await user.click(screen.getByTestId('open-pick'))                              // a sheet open: none either
    expect(await screen.findByTestId('bazar-pick-sheet')).toBeInTheDocument()
    expect(screen.queryByTestId('scanner-ready')).toBeNull()
  })

  test('SOLD card: number · time, title, piece code, price, Cash / InstaPay / Card, two customer boxes → the sheet, Undo · Ns', async () => {
    const s = sale()
    install(...base(), on('POST', '/sell', () => json(200, s)))
    renderAt(`/bazar/${EVENT}/sell`)
    const user = userEvent.setup()
    await user.type(await screen.findByTestId('bazar-scan-input'), 'P0000231{Enter}')
    const card = await screen.findByTestId('sell-card-sold')
    expect(card).toHaveTextContent(`SOLD · ${s.sale_number} · `)
    expect(card).toHaveTextContent('Linen shirt · White / M')
    expect(card).toHaveTextContent('P0000231')
    expect(within(card).getByTestId('sold-price')).toHaveTextContent('960')
    expect(within(card).getByRole('button', { name: 'Cash' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(card).getByTestId('sold-undo')).toHaveTextContent(/Undo · \ds/)
    expect(screen.getByTestId('sell-ready-next')).toHaveTextContent('Ready for the next piece — just scan')
    // The phone box opens the existing sheet — nothing is saved from the card.
    await user.click(within(card).getByRole('button', { name: 'Phone' }))
    expect(await screen.findByTestId('bazar-customer-sheet')).toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/customer'))).toBe(false)
  })

  test('earlier sales: picture tile, title, time · method, price in numbers, Edit opens the sale', async () => {
    const s = sale({ method: 'instapay', price: '1400.00' })
    install(...base({ sales: [s] }))
    renderAt(`/bazar/${EVENT}/sell`)
    const list = await screen.findByTestId('bazar-recent')
    await waitFor(() => expect(list).toHaveTextContent('Linen shirt · White / M'))
    expect(list).toHaveTextContent('InstaPay')
    expect(list).toHaveTextContent('1,400')
    expect(list).toHaveTextContent('Edit')
    expect(screen.getByLabelText('Earlier today')).toBeInTheDocument()
    await userEvent.setup().click(within(list).getByRole('button'))
    expect(await screen.findByTestId('bazar-sale-sheet')).toBeInTheDocument()
  })

  test('a sold-out size is a faded, disabled tile in the grid', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/sell`)
    const tiles = await screen.findAllByTestId('grid-tile')
    expect(tiles[0]).toBeDisabled()
    expect(tiles[0]).toHaveTextContent('Sold out')
    expect(tiles[1]).toHaveTextContent('3 left')
  })
})

describe('Sell — cart', () => {
  test('Cart · N pieces, Clear, EGP discount, total, customer boxes always shown, "Charge N EGP · method", last sale', async () => {
    const receipt = { kind: 'receipt', id: 'r-1', sale_number: 'B-0057', sold_at: new Date().toISOString(), business_day: TODAY,
      status: 'active', subtotal: '1300.00', discount: '100.00', total: '1200.00', price: '1200.00', method: 'instapay',
      seller_id: 'u-worker', seller_name: 'Omar', customer_name: null, customer_phone: null, undone_at: null, line_count: 2,
      lines: [], replayed: false }
    install(...base(), on('POST', '/cart/complete', () => json(200, receipt)))
    renderAt(`/bazar/${EVENT}/sell`)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('radio', { name: 'Cart' }))
    expect(screen.getByLabelText('Name')).toBeInTheDocument()                 // no "+ Customer" step
    expect(screen.getByLabelText('Mobile number')).toBeInTheDocument()
    await user.type(screen.getByTestId('bazar-scan-input'), 'P0000001{Enter}')
    await user.type(screen.getByTestId('bazar-scan-input'), 'P0000002{Enter}')
    await waitFor(() => expect(screen.getByTestId('cart-total')).toHaveTextContent('1,300 EGP'))
    expect(screen.getByText('Cart · 2 pieces')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear cart' })).toHaveTextContent('Clear')
    await user.type(screen.getByTestId('cart-discount'), '100')
    expect(screen.getByTestId('cart-total')).toHaveTextContent('1,200 EGP')
    await user.click(within(screen.getByTestId('cart-summary')).getByRole('button', { name: 'InstaPay' }))
    expect(screen.getByTestId('cart-complete')).toHaveTextContent('Charge 1,200 EGP · InstaPay')
    await user.click(screen.getByTestId('cart-complete'))
    const done = await screen.findByTestId('cart-card-sold')
    expect(done).toHaveTextContent('SOLD · B-0057')
    expect(done).toHaveTextContent('Last sale')
    expect(done).toHaveTextContent('1,200 EGP')
    expect(screen.getByTestId('cart-undo')).toHaveTextContent(/Undo cart · \ds/)
  })
})

// ── Return / exchange ──────────────────────────────────────────────────────────────────────────────────────────────

function pieces(map: Record<string, unknown>): Handler {
  return (url, init) => {
    if ((init?.method ?? 'GET').toUpperCase() !== 'GET' || !url.includes(`/bazar-events/${EVENT}/piece?`)) return undefined
    const code = new URL(url, 'http://x').searchParams.get('code') ?? ''
    return map[code] ? json(200, map[code]) : json(404, { code: 'BAZAR_PIECE_NOT_FOUND', message_en: 'nope' })
  }
}

const sold = (code: string, over: Partial<Record<string, unknown>> = {}) => ({
  piece_id: `pc-${code}`, piece_code: code, product_title: 'Cargo pants', variant_title: 'Olive / 32', price: '880.00',
  state: 'sold', sale: sale({ piece_id: `pc-${code}`, piece_code: code, product_title: 'Cargo pants', variant_title: 'Olive / 32',
                             price: '880.00', method: 'instapay', seller_name: 'Hana', ...over }),
})
const forSale = (code: string, price: string) => ({
  piece_id: `pc-${code}`, piece_code: code, product_title: 'Cotton tee', variant_title: 'Off-white / M', price,
  state: 'for_sale', sale: null,
})

async function scanCode(code: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByTestId('bazar-code-input'), `${code}{Enter}`)
  return user
}

describe('Return', () => {
  test('back link is named Sell; Refund | Exchange link the two screens; header shows today and returns', async () => {
    install(...base())
    renderAt(`/bazar/${EVENT}/return`)
    expect(await screen.findByRole('link', { name: 'Sell' })).toHaveAttribute('href', `/bazar/${EVENT}/sell`)
    expect(screen.getByRole('link', { name: 'Refund' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Exchange' })).toHaveAttribute('href', `/bazar/${EVENT}/exchange`)
    await waitFor(() => expect(screen.getByRole('banner')).toHaveTextContent('18,920 EGP'))
    expect(screen.getByRole('banner')).toHaveTextContent('2 returns')
    expect(screen.getByTestId('camera-state')).toHaveTextContent('Camera ready')
  })

  test('a found sale: card, Good / Damaged with hints, dark Give back box, refund with = how they paid, confirm label', async () => {
    install(...base(), pieces({ P1: sold('P1') }))
    renderAt(`/bazar/${EVENT}/return`)
    await scanCode('P1')
    const card = await screen.findByTestId('return-sale')
    expect(card).toHaveTextContent('Cargo pants · Olive / 32')
    expect(card).toHaveTextContent('P1')
    expect(card).toHaveTextContent('Hana')
    expect(screen.getByRole('button', { name: /Good/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /Good/ })).toHaveTextContent('Back to the bazar, can sell again')
    expect(screen.getByRole('button', { name: /Damaged/ })).toHaveTextContent('Set aside, comes back to the warehouse')
    expect(screen.getByTestId('return-amount')).toHaveTextContent('Give back 880 EGP')
    expect(screen.getByTestId('return-amount')).toHaveTextContent("Comes out of today's money")
    expect(screen.getByText('same as the sale')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'InstaPay' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('return-confirm')).toHaveTextContent('Confirm refund · give back 880 EGP · InstaPay')
  })
})

describe('Exchange — summary and confirm', () => {
  test.each([
    ['pay', '1240.00', 'Customer pays 360 EGP', 'Confirm exchange · take 360 EGP · Cash', '+ 1,240'],
    ['give back', '500.00', 'Give back 380 EGP', 'Confirm exchange · give back 380 EGP · Cash', '+ 500'],
    ['even', '880.00', 'Even swap', 'Confirm exchange', '+ 880'],
  ])('%s', async (_kind, newPrice, box, confirm, goingOut) => {
    install(...base(), pieces({ P1: sold('P1', { method: 'cash' }), P2: forSale('P2', newPrice) }))
    renderAt(`/bazar/${EVENT}/exchange`)
    const user = await scanCode('P1')
    expect(await screen.findByTestId('exchange-old')).toHaveTextContent('880')
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent('Coming back · 1 piece')
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent('− 880')
    await user.type(screen.getByTestId('bazar-code-input'), 'P2{Enter}')
    expect(await screen.findByTestId('exchange-new')).toHaveTextContent('Cotton tee · Off-white / M')
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent(goingOut)
    expect(screen.getByTestId('exchange-difference')).toHaveTextContent(box)
    expect(screen.getByTestId('exchange-confirm')).toHaveTextContent(new RegExp(`^${confirm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`))
    if (_kind === 'even') expect(screen.queryByRole('button', { name: 'Cash' })).toBeNull()
  })

  test('Good / Damaged cards set the condition that is sent', async () => {
    install(...base(), pieces({ P1: sold('P1'), P2: forSale('P2', '880.00') }),
      on('POST', '/exchange', () => json(200, { old_sale: sale({ status: 'returned' }), new_sale: sale(), difference: '0', money: null })))
    renderAt(`/bazar/${EVENT}/exchange`)
    const user = await scanCode('P1')
    await screen.findByTestId('exchange-old')
    await user.click(screen.getByRole('button', { name: /Damaged/ }))
    await user.type(screen.getByTestId('bazar-code-input'), 'P2{Enter}')
    await user.click(await screen.findByTestId('exchange-confirm'))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/exchange'))).toBe(true))
    expect(calls.find(c => c.url.endsWith('/exchange'))!.body).toMatchObject({ condition: 'damaged' })
  })
})

// ── Strings ────────────────────────────────────────────────────────────────────────────────────────────────────────

describe('bazar.r4 strings', () => {
  test('every EN key has an AR translation (plural forms included) and the same placeholders', () => {
    const e = (en as { bazar: { r4: Record<string, string> } }).bazar.r4
    const a = (ar as unknown as { bazar: { r4: Record<string, string> } }).bazar.r4
    const vars = (s: string) => (s.match(/\{\{\w+\}\}/g) ?? []).sort().join(',')
    for (const [k, v] of Object.entries(e)) {
      const base = k.replace(/_(one|other)$/, '')
      const arKey = k.endsWith('_one') || k.endsWith('_other') ? `${base}_other` : k
      expect(a[arKey], k).toBeTruthy()
      if (!k.endsWith('_one')) expect(vars(a[arKey]), k).toBe(vars(v))
    }
  })

  test('Arabic: the Sell header and the Return box render in Arabic with LTR money', async () => {
    const arI18n = i18next.createInstance()
    arI18n.use(initReactI18next).init({ lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
    install(...base(), pieces({ P1: sold('P1') }))
    renderAt(`/bazar/${EVENT}/return`, arI18n)
    await scanCode('P1')
    const box = await screen.findByTestId('return-amount')
    expect(box).toHaveTextContent('أعد للعميل 880 EGP')
    expect(screen.getByRole('link', { name: 'بيع' })).toBeInTheDocument()
    // In a right-to-left sentence the amount is isolated LTR, so it reads "880 EGP", never "EGP 880".
    expect(screen.getByTestId('return-confirm').textContent).toContain('\u2066880 EGP\u2069')
    expect(box.querySelector('[dir="ltr"]')?.textContent).toBe('880 EGP')
  })
})
