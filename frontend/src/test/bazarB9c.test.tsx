import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarSetupWizard from '../pages/bazar/setup/BazarSetupWizard'
import BazarEvents from '../pages/bazar/BazarEvents'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { StationProvider } from '../components/StationProvider'

/**
 * Bazar B9c — the guided setup's last part: after "Done, I lowered them" the batch goes on the bazar's send
 * (POST /bazar-setup/send-batch), then 4 Prices (Get ready's PricesCard) and 5 Ready (online stock choice, Mark as sent
 * with Get ready's confirm, Start).
 *   c1 the whole tail: Done → send (two chunks, a new id each) → Prices (rows, bulk rule) → Ready → Keep → Mark sent
 *      (the Keep confirm) → Start → live; only the existing calls
 *   c2 a failed send: the error, Try again re-sends the SAME request id
 *   c3 resume from the server: lowered + preparing → Prices (nothing left → no send); lowered + sent → Ready (Start,
 *      no Mark sent, online locked); the bazar live → a new setup (step 1)
 *   c4 "Continue to your bazar" on every step once the bazar exists (pick, barcodes, lower, prices, ready)
 *   c5 off: /bazar-setup 404 → the wizard isn't there, nothing is sent
 *   a1 Arabic
 */

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const W = en.bazar.wizard
const EV = 'ev1'
const TODAY = '2026-11-21'
const jwt = (role: string) => `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
    blob: async () => new Blob([JSON.stringify(body)]),
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
const writes = () => calls.filter(c => c.method !== 'GET').map(c => `${c.method} ${c.url.replace(/^.*\/api\/v1/, '')}`)
const sendIds = () => calls.filter(c => c.url.endsWith('/bazar-setup/send-batch')).map(c => (c.body as { requestId: string }).requestId)

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname}</div> }

function renderAt(path: string) {
  return render(
    <StationProvider>
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>
          <Route path="/bazar/setup" element={<BazarSetupWizard />} />
          <Route path="/bazar" element={<BazarEvents />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </I18nextProvider>
    </MemoryRouter>
    </StationProvider>,
  )
}

const ME_ON = { role: 'owner', name: 'Nour', setupStep: null, setupIntro: 'bazar', bazar: true, bazarSetup: true }

function batch(over: Record<string, unknown> = {}) {
  return { eventId: EV, eventName: 'Zamalek Winter Bazar', eventStatus: 'draft', labelledReceiptId: 'rc-l',
           countOnlyReceiptId: 'rc-c', pieces: 6, receivedAt: '2026-11-21T10:00:00Z', lowered: false, loweredAt: null,
           transferStatus: 'preparing', onSend: 0, leftAtMain: 6, ...over }
}
const LOWER = {
  total: 6, lowered: false, loweredAt: null, rows: [
    { variantId: 'v-scarf', productTitle: 'Knit scarf', variantTitle: null, sku: 'SCARF', quantity: 3 },
    { variantId: 'v-s', productTitle: 'Linen shirt', variantTitle: 'S', sku: 'SHIRT-S', quantity: 3 },
  ],
}
const PRICES = [
  { variant_id: 'v-scarf', sku: 'SCARF', variant_title: 'Default Title', product_title: 'Knit scarf', shopify_price: '400.00', price: '400.00', source: 'shopify' },
  { variant_id: 'v-s', sku: 'SHIRT-S', variant_title: 'S', product_title: 'Linen shirt', shopify_price: '960.00', price: '960.00', source: 'shopify' },
]

function detail(over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status: 'draft',
    location_id: 'l1', location_name: 'Zamalek', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: 'preparing', transfer_sent_at: null, transfer_shopify_sync_mode: null,
    location_shopify_sync_mode: 'remove', online_stock: 'remove', online_stock_locked: false,
    payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null, started_at: null, ended_at: null,
    counts: { sent: 6, at_bazar: 0, sold: 0, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: {}, total: '0', sales: 0 },
    totals: { by_method: {}, total: '0', sales: 0, returns: 0 },
    ...over,
  }
}

/** A server: the setup status, the batch, the bazar (its send, online stock, start). */
function server(state: { batch: Record<string, unknown> | null; ev: Record<string, unknown>; sendPlan?: number[]; failSend?: number }) {
  return [
    on('GET', '/me', () => json(200, ME_ON)),
    on('GET', '/bazar-setup', () => json(200, { on: true, canSetUp: true, batch: state.batch })),
    on('GET', '/bazar-setup/lower', () => json(200, LOWER)),
    on('POST', '/bazar-setup/lower-done', () => {
      state.batch = { ...state.batch, lowered: true, loweredAt: '2026-11-21T11:00:00Z' }
      return json(200, { on: true, canSetUp: true, batch: state.batch })
    }),
    on('POST', '/bazar-setup/send-batch', () => {
      if (state.failSend && state.failSend-- > 0) return json(503, {})
      const left = state.sendPlan?.shift() ?? 0
      state.batch = { ...state.batch, leftAtMain: left, onSend: 6 - left }
      return json(200, { sent: 6 - left, left, replayed: false, status: { on: true, canSetUp: true, batch: state.batch } })
    }),
    on('GET', '/bazar-setup/products', () => json(200, { stockKnown: true, products: [] })),
    on('GET', `/bazar-events/${EV}`, () => json(200, detail(state.ev))),
    on('GET', `/bazar-events/${EV}/prices`, () => json(200, PRICES)),
    on('PUT', `/bazar-events/${EV}/bulk-rule`, () => json(200, PRICES.map(p => ({ ...p, price: '360.00', source: 'rule' })))),
    on('GET', '/transfers/t-1', () => json(200, { lines: [{ variant_id: 'v-scarf', qty_out: 3 }, { variant_id: 'v-s', qty_out: 3 }] })),
    on('PUT', `/bazar-events/${EV}/online-stock`, (_u, b) => {
      state.ev = { ...state.ev, online_stock: (b as { mode: string }).mode }
      return json(200, detail(state.ev))
    }),
    on('POST', `/bazar-events/${EV}/mark-sent`, () => {
      state.ev = { ...state.ev, transfer_status: 'sent', online_stock_locked: true }
      state.batch = { ...state.batch, transferStatus: 'sent' }
      return json(200, detail(state.ev))
    }),
    on('POST', `/bazar-events/${EV}/start`, () => {
      state.ev = { ...state.ev, status: 'live' }
      return json(200, detail(state.ev))
    }),
  ]
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(jwt('owner'))
  resetCapabilitiesCache()
  testI18n.changeLanguage('en')
  try { localStorage.clear() } catch { /* ignore */ }
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

describe('B9c wizard tail', () => {
  test('c1 Done → send (chunks) → Prices → Ready → Keep → Mark sent → Start', async () => {
    const state = { batch: batch() as Record<string, unknown> | null, ev: {}, sendPlan: [2, 0] }
    install(...server(state))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    await user.click(await screen.findByTestId('bazar-lower-done'))

    // The batch goes on the send: two chunks, each its own request id.
    expect(await screen.findByTestId('bazar-setup-prices')).toBeInTheDocument()
    expect(sendIds()).toHaveLength(2)
    expect(sendIds()[0]).not.toBe(sendIds()[1])
    expect(screen.getByRole('heading', { name: W.prices.title })).toBeInTheDocument()
    expect(screen.getByTestId('bazar-setup-step')).toHaveTextContent(W.steps.s4)
    // Get ready's PricesCard: the rows and the bulk rule, same endpoints.
    expect(await screen.findByText('Knit scarf')).toBeInTheDocument()
    expect(screen.getByText('Linen shirt')).toBeInTheDocument()
    await user.type(screen.getByLabelText(en.bazar.prices.discountAria), '10')
    await user.click(within(screen.getByTestId('bazar-prices')).getByRole('button', { name: en.bazar.prices.apply }))
    await waitFor(() => expect(writes()).toContain(`PUT /bazar-events/${EV}/bulk-rule`))

    await user.click(screen.getByTestId('bazar-setup-next'))
    expect(await screen.findByTestId('bazar-setup-ready')).toBeInTheDocument()
    expect(screen.getByTestId('bazar-setup-ready-pieces')).toHaveTextContent('6 pieces ready')
    expect(screen.queryByTestId('bazar-start')).not.toBeInTheDocument()

    // The bazar's online stock choice: Remove → Keep (Get ready's dialog and call).
    expect(screen.getByTestId('bazar-online-row')).toHaveTextContent(en.bazar.online.remove.title)
    await user.click(screen.getByTestId('bazar-online-change'))
    const dialog = await screen.findByTestId('bazar-online-dialog')
    await user.click(within(dialog).getByRole('radio', { name: new RegExp(en.bazar.online.leave.title) }))
    await user.click(within(dialog).getByRole('button', { name: en.bazar.online.save }))
    await waitFor(() => expect(screen.getByTestId('bazar-online-row')).toHaveTextContent(en.bazar.online.leave.title))

    // Mark as sent: Get ready's Keep confirm, then the existing send.
    await user.click(screen.getByTestId('bazar-mark-sent'))
    expect(screen.getByTestId('bazar-send-confirm-body'))
      .toHaveTextContent(en.bazar.transfer.confirmBodyKeep_other.replace('{{count}}', '6'))
    await user.click(screen.getByRole('button', { name: en.bazar.transfer.markSent }))
    expect(await screen.findByTestId('bazar-start')).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-mark-sent')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bazar-online-change')).not.toBeInTheDocument()

    await user.click(screen.getByTestId('bazar-start'))
    expect(await screen.findByTestId('bazar-setup-done')).toHaveTextContent(W.done.title)
    await user.click(screen.getByTestId('bazar-setup-continue'))
    expect(await screen.findByTestId('where')).toHaveTextContent(`/bazar/${EV}`)

    // Only the existing calls — nothing else writes.
    expect(writes()).toEqual([
      'POST /bazar-setup/lower-done', 'POST /bazar-setup/send-batch', 'POST /bazar-setup/send-batch',
      `PUT /bazar-events/${EV}/bulk-rule`, `PUT /bazar-events/${EV}/online-stock`,
      `POST /bazar-events/${EV}/mark-sent`, `POST /bazar-events/${EV}/start`,
    ])
    expect(calls.find(c => c.url.endsWith('/online-stock'))!.body).toEqual({ mode: 'leave' })
  })

  test('c2 a failed send: Try again re-sends the same request id', async () => {
    const state = { batch: batch({ lowered: true }) as Record<string, unknown> | null, ev: {}, failSend: 1 }
    install(...server(state))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-send-error')).toBeInTheDocument()
    await user.click(screen.getByTestId('bazar-setup-send-retry'))
    expect(await screen.findByTestId('bazar-setup-prices')).toBeInTheDocument()
    expect(sendIds()).toHaveLength(2)
    expect(sendIds()[0]).toBe(sendIds()[1])
  })

  test('c3 resume from the server: Prices, Ready, or a new setup once live', async () => {
    install(...server({ batch: batch({ lowered: true, leftAtMain: 0, onSend: 6 }), ev: {} }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-prices')).toBeInTheDocument()
    expect(sendIds()).toHaveLength(0)   // nothing left at Main → nothing sent
    cleanup()

    install(...server({ batch: batch({ lowered: true, leftAtMain: 0, onSend: 6, transferStatus: 'sent' }),
      ev: { transfer_status: 'sent', online_stock_locked: true } }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-ready')).toBeInTheDocument()
    expect(screen.getByTestId('bazar-start')).toBeEnabled()
    expect(screen.queryByTestId('bazar-mark-sent')).not.toBeInTheDocument()
    expect(screen.getByTestId('bazar-online-row')).toHaveTextContent(en.bazar.online.locked)
    expect(writes()).toEqual([])
    cleanup()

    install(...server({ batch: batch({ lowered: true, eventStatus: 'live', transferStatus: 'sent' }), ev: { status: 'live' } }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-bazar')).toBeInTheDocument()
    expect(writes()).toEqual([])
  })

  test('c4 "Continue to your bazar" on every step once the bazar exists', async () => {
    const expectLink = async () => {
      const link = await screen.findByTestId('bazar-setup-later')
      expect(link).toHaveTextContent(W.later)
      expect(link).toHaveAttribute('href', `/bazar/${EV}`)
    }
    // Step 1 before the bazar exists: nothing to continue to.
    install(...server({ batch: null, ev: {} }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-bazar')).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-setup-later')).not.toBeInTheDocument()
    cleanup()

    // Pick and Barcodes (a remembered draft bazar).
    localStorage.setItem('traced.bazarSetup.v1', JSON.stringify({ eventId: EV }))
    install(on('GET', '/bazar-setup/products', () => json(200, {
      stockKnown: true, products: [{ id: 'p', title: 'Knit scarf', imageUrl: null,
        variants: [{ id: 'v-scarf', title: null, sku: 'SCARF', price: '400.00', otherLocations: 2 }] }] })),
      ...server({ batch: null, ev: {} }))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-pick')).toBeInTheDocument()
    await expectLink()
    await user.click(screen.getByRole('checkbox', { name: 'Knit scarf' }))
    await user.click(screen.getByTestId('bazar-setup-next'))
    expect(await screen.findByTestId('bazar-setup-barcodes')).toBeInTheDocument()
    await expectLink()
    cleanup()
    localStorage.clear()

    for (const [b, id] of [
      [batch(), 'bazar-setup-lower'],
      [batch({ lowered: true, leftAtMain: 0 }), 'bazar-setup-prices'],
      [batch({ lowered: true, leftAtMain: 0, transferStatus: 'sent' }), 'bazar-setup-ready'],
    ] as const) {
      install(...server({ batch: b, ev: b.transferStatus === 'sent' ? { transfer_status: 'sent' } : {} }))
      renderAt('/bazar/setup')
      expect(await screen.findByTestId(id)).toBeInTheDocument()
      await expectLink()
      cleanup()
    }
  })

  test('c5 off: /bazar-setup 404 → no wizard, nothing sent', async () => {
    install(on('GET', '/me', () => json(200, { ...ME_ON, bazarSetup: undefined })), on('GET', '/bazar-events', () => json(200, [])))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-events')).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-setup-wizard')).not.toBeInTheDocument()
    expect(sendIds()).toHaveLength(0)
    expect(writes()).toEqual([])
  })

  test('a1 Arabic: Prices and Ready', async () => {
    testI18n.changeLanguage('ar')
    const A = ar.bazar.wizard
    install(...server({ batch: batch({ lowered: true, leftAtMain: 0 }), ev: {} }))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    expect(await screen.findByRole('heading', { name: A.prices.title })).toBeInTheDocument()
    expect(screen.getByTestId('bazar-setup-later')).toHaveTextContent(A.later)
    await user.click(screen.getByTestId('bazar-setup-next'))
    expect(await screen.findByRole('heading', { name: A.ready.title })).toBeInTheDocument()
  })
})
