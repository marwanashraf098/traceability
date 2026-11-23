import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarSetupWizard from '../pages/bazar/setup/BazarSetupWizard'
import BazarEvents from '../pages/bazar/BazarEvents'
import BazarEventPage from '../pages/bazar/BazarEventPage'
import { SetupBazarPage } from '../pages/onboarding/SetupIntro'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { StationProvider } from '../components/StationProvider'

/**
 * Bazar B9b — the guided bazar setup (/bazar/setup) and its reminders.
 *   o1 off (GET /me without bazarSetup): no /bazar-setup request anywhere; /bazar/setup → /bazar; no card, no line;
 *      /setup/bazar stays the B9a placeholder
 *   w1 the whole flow: Your bazar (+ online stock pair) → Pick (other locations, steppers) → Barcodes (mix) → receive
 *      (one request, labels per product) → Lower these (rows, CSV, print) → Done → Continue to your bazar
 *   w2 resume: a received batch opens at its list; a remembered draft bazar opens at Pick
 *   w3 a failed receive keeps the same request id on the next try
 *   w4 count-only pieces are listed exactly like labelled ones ("One size", quantity)
 *   r1 Overview card until Done (View list → Done → gone); lowered → no card; no batch → "Set up your bazar"
 *   r2 Get ready line for the batch's bazar only, gone after Done
 *   s1 /setup/bazar with the setup on → the wizard
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
const setupCalls = () => calls.filter(c => c.url.includes('/bazar-setup'))

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname}</div> }

function renderAt(path: string) {
  return render(
    <StationProvider>
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}>
        <Routes>
          <Route path="/bazar/setup" element={<BazarSetupWizard />} />
          <Route path="/bazar" element={<BazarEvents />} />
          <Route path="/bazar/:id" element={path.startsWith('/bazar/setup') ? <Where /> : <BazarEventPage />} />
          <Route path="/setup/bazar" element={<SetupBazarPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </I18nextProvider>
    </MemoryRouter>
    </StationProvider>,
  )
}

const ME_ON = { role: 'owner', name: 'Nour', setupStep: null, setupIntro: 'bazar', bazar: true, bazarSetup: true }
const ME_OFF = { role: 'owner', name: 'Nour', setupStep: null, setupIntro: 'bazar', bazar: true }

const PRODUCTS = {
  stockKnown: true,
  products: [
    { id: 'p-scarf', title: 'Knit scarf', imageUrl: null,
      variants: [{ id: 'v-scarf', title: null, sku: 'SCARF', price: '400.00', otherLocations: 20 }] },
    { id: 'p-shirt', title: 'Linen shirt', imageUrl: null, variants: [
      { id: 'v-s', title: 'S', sku: 'SHIRT-S', price: '960.00', otherLocations: 4 },
      { id: 'v-m', title: 'M', sku: 'SHIRT-M', price: '960.00', otherLocations: 7 }] },
  ],
}

function batch(over: Record<string, unknown> = {}) {
  return { eventId: EV, eventName: 'Zamalek Winter Bazar', eventStatus: 'draft', labelledReceiptId: 'rc-l',
           countOnlyReceiptId: 'rc-c', pieces: 6, receivedAt: '2026-11-21T10:00:00Z', lowered: false, loweredAt: null, ...over }
}
const LOWER = {
  total: 6, lowered: false, loweredAt: null, rows: [
    { variantId: 'v-scarf', productTitle: 'Knit scarf', variantTitle: null, sku: 'SCARF', quantity: 3 },
    { variantId: 'v-s', productTitle: 'Linen shirt', variantTitle: 'S', sku: 'SHIRT-S', quantity: 2 },
    { variantId: 'v-m', productTitle: 'Linen shirt', variantTitle: 'M', sku: 'SHIRT-M', quantity: 1 },
  ],
}

function detail(over: Record<string, unknown> = {}) {
  return {
    id: EV, name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status: 'draft',
    location_id: 'l1', location_name: 'Zamalek', transfer_id: 't-1', created_at: '2026-11-18T08:00:00Z',
    transfer_status: 'preparing', transfer_sent_at: null, transfer_shopify_sync_mode: null,
    location_shopify_sync_mode: 'remove', online_stock: 'remove', online_stock_locked: false,
    payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null, started_at: null, ended_at: null,
    counts: { sent: 0, at_bazar: 0, sold: 0, back: 0, lost: 0 },
    today: { business_day: TODAY, by_method: {}, total: '0', sales: 0 },
    totals: { by_method: {}, total: '0', sales: 0, returns: 0 },
    ...over,
  }
}
function eventHandlers(id = EV) {
  return [
    on('GET', `/bazar-events/${id}`, () => json(200, detail({ id }))),
    on('GET', `/bazar-events/${id}/prices`, () => json(200, [])),
    on('GET', `/bazar-events/${id}/sellers`, () => json(200, { sellers: [], workers_allowed: true })),
    on('GET', `/transfers/t-1`, () => json(200, { lines: [] })),
  ]
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(jwt('owner'))
  resetCapabilitiesCache()
  testI18n.changeLanguage('en')
  try { localStorage.clear() } catch { /* ignore */ }
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

// ── o1 ───────────────────────────────────────────────────────────────────────

describe('o1 off', () => {
  test('no bazarSetup in /me: nothing asks /bazar-setup; the wizard sends you to /bazar; no card; no line', async () => {
    install(on('GET', '/me', () => json(200, ME_OFF)), on('GET', '/bazar-events', () => json(200, [])),
      on('GET', '/bazar-setup', () => json(200, { on: true, canSetUp: true, batch: batch() })), ...eventHandlers())
    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-events')).toBeInTheDocument()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/me'))).toBe(true))
    expect(screen.queryByTestId('bazar-lower-reminder')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bazar-setup-start-card')).not.toBeInTheDocument()
    cleanup()

    renderAt(`/bazar/${EV}`)
    expect(await screen.findByTestId('bazar-setup')).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-lower-line')).not.toBeInTheDocument()
    expect(setupCalls()).toHaveLength(0)
    cleanup()

    // The server says 404 → the wizard isn't there.
    install(on('GET', '/me', () => json(200, ME_OFF)), on('GET', '/bazar-events', () => json(200, [])))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-events')).toBeInTheDocument()
    expect(screen.queryByTestId('bazar-setup-wizard')).not.toBeInTheDocument()
  })

  test('/setup/bazar stays the B9a placeholder while off', async () => {
    install(on('GET', '/me', () => json(200, ME_OFF)), on('PATCH', '/onboarding/prefs', () => json(200, {})))
    renderAt('/setup/bazar')
    expect(await screen.findByTestId('setup-bazar')).toBeInTheDocument()
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()
    expect(setupCalls()).toHaveLength(0)
  })
})

// ── w1 – w4 ──────────────────────────────────────────────────────────────────

function wizardHandlers(state: { batch: Record<string, unknown> | null; failReceive?: number }) {
  return [
    on('GET', '/me', () => json(200, ME_ON)),
    on('GET', '/bazar-setup', () => json(200, { on: true, canSetUp: true, batch: state.batch })),
    on('POST', '/bazar-events', (_u, b) => json(201, detail({ name: (b as { name: string }).name }))),
    on('GET', '/bazar-setup/products', () => json(200, PRODUCTS)),
    on('POST', '/bazar-setup/receive', () => {
      if (state.failReceive && state.failReceive-- > 0) return json(503, {})
      state.batch = batch()
      return json(200, { on: true, canSetUp: true, batch: state.batch })
    }),
    on('GET', '/bazar-setup/lower', () => json(200, LOWER)),
    on('GET', '/bazar-setup/lower.csv', () => json(200, 'csv')),
    on('POST', '/bazar-setup/lower-done', () => {
      state.batch = batch({ lowered: true, loweredAt: '2026-11-21T11:00:00Z' })
      return json(200, { on: true, canSetUp: true, batch: state.batch })
    }),
    on('GET', `/receiving/sessions/rc-l/labels`, () => json(200, 'pdf')),
    ...eventHandlers(),
  ]
}

async function stepOneAndPick(user: ReturnType<typeof userEvent.setup>, keep = false) {
  expect(await screen.findByTestId('bazar-setup-bazar')).toBeInTheDocument()
  await user.type(screen.getByTestId('bazar-setup-name'), 'Zamalek Winter Bazar')
  await user.type(screen.getByTestId('bazar-setup-where'), 'Zamalek')
  if (keep) await user.click(screen.getByRole('radio', { name: en.bazar.online.leave.phone }))
  await user.click(screen.getByTestId('bazar-setup-next'))
  expect(await screen.findByTestId('bazar-setup-pick')).toBeInTheDocument()
}

describe('w the wizard', () => {
  test('w1 the whole flow', async () => {
    const state = { batch: null as Record<string, unknown> | null }
    install(...wizardHandlers(state))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    // Step 1: the remove/keep pair (Remove by default), Me + team by default.
    expect(await screen.findByRole('radio', { name: en.bazar.online.remove.phone })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('bazar-setup-team')).toHaveAttribute('aria-checked', 'true')
    await stepOneAndPick(user, true)
    const create = calls.find(c => c.method === 'POST' && c.url.endsWith('/bazar-events'))!.body as Record<string, unknown>
    expect(create).toMatchObject({ name: 'Zamalek Winter Bazar', locationName: 'Zamalek', locationId: null,
      sellerRoles: ['owner', 'manager', 'worker'], shopifySyncMode: 'leave', paymentMethods: ['cash', 'instapay', 'card'] })

    // Step 2: "N in your other locations" (not "in your shop"); tick → steppers.
    expect(screen.getByText('20 in your other locations')).toBeInTheDocument()
    expect(screen.queryByText(/in your shop/)).not.toBeInTheDocument()
    expect(screen.getByTestId('bazar-setup-shopify-note')).toHaveTextContent(W.pick.note)
    expect(screen.getByTestId('bazar-setup-next')).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Linen shirt' }))
    expect(screen.getByText('S · 4 in your other locations')).toBeInTheDocument()
    expect(screen.getByText('M · 7 in your other locations')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Linen shirt M +' }))
    await user.click(screen.getByRole('checkbox', { name: 'Knit scarf' }))
    await user.click(screen.getByRole('button', { name: 'Knit scarf One size +' }))
    await user.click(screen.getByRole('button', { name: 'Knit scarf One size +' }))
    expect(screen.getByTestId('bazar-setup-taking')).toHaveTextContent('6')
    await user.click(screen.getByTestId('bazar-setup-filter-selected'))
    expect(screen.getAllByTestId('bazar-setup-product')).toHaveLength(2)
    await user.click(screen.getByTestId('bazar-setup-next'))

    // Step 3: mix — the scarf goes without barcodes.
    expect(await screen.findByTestId('bazar-setup-barcodes')).toBeInTheDocument()
    await user.click(screen.getByTestId('bazar-setup-mode-none'))
    const mix = screen.getByTestId('bazar-setup-mix')
    const scarfRow = within(mix).getAllByTestId('bazar-setup-mix-row').find(r => r.textContent?.includes('Knit scarf'))!
    await user.click(within(scarfRow).getByRole('radio', { name: W.barcodes.mixNone }))
    await user.click(screen.getByTestId('bazar-setup-receive'))

    expect(await screen.findByTestId('bazar-setup-lower')).toBeInTheDocument()
    const receives = calls.filter(c => c.url.endsWith('/bazar-setup/receive'))
    expect(receives).toHaveLength(1)
    const body = receives[0].body as { eventId: string; requestId: string; lines: unknown[] }
    expect(body.eventId).toBe(EV)
    expect(body.requestId).toBeTruthy()
    expect(body.lines).toEqual(expect.arrayContaining([
      { variantId: 'v-s', quantity: 1, labels: true }, { variantId: 'v-m', quantity: 2, labels: true },
      { variantId: 'v-scarf', quantity: 3, labels: false }]))
    expect(body.lines).toHaveLength(3)

    // Lower these: the rows, the CSV, the labels.
    expect(screen.getByRole('heading', { name: W.lower.title })).toBeInTheDocument()
    expect(await screen.findAllByTestId('bazar-lower-row')).toHaveLength(3)
    expect(screen.getByTestId('bazar-lower-total')).toHaveTextContent('6')
    await user.click(screen.getByTestId('bazar-lower-csv'))
    await waitFor(() => expect(calls.some(c => c.url.includes('/bazar-setup/lower.csv?lang=en'))).toBe(true))
    await user.click(screen.getByTestId('bazar-setup-print'))
    await waitFor(() => expect(calls.some(c => c.url.includes('/receiving/sessions/rc-l/labels?widthMm=50&heightMm=25'))).toBe(true))
    expect(window.open).toHaveBeenCalled()

    // Done → Continue to your bazar.
    await user.click(screen.getByTestId('bazar-lower-done'))
    expect(await screen.findByTestId('bazar-setup-done')).toBeInTheDocument()
    expect(calls.filter(c => c.url.endsWith('/bazar-setup/lower-done'))).toHaveLength(1)
    await user.click(screen.getByTestId('bazar-setup-continue'))
    expect(await screen.findByTestId('where')).toHaveTextContent(`/bazar/${EV}`)
  })

  test('w2 resume: a received batch opens at its list; a remembered draft bazar opens at Pick', async () => {
    install(...wizardHandlers({ batch: batch() }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-lower')).toBeInTheDocument()
    expect(calls.some(c => c.method === 'POST')).toBe(false)
    cleanup()

    localStorage.setItem('traced.bazarSetup.v1', JSON.stringify({ eventId: EV }))
    install(...wizardHandlers({ batch: null }))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-pick')).toBeInTheDocument()
    // Back to step 1: the bazar exists — shown, never created twice.
    await userEvent.setup().click(screen.getByTestId('bazar-setup-back'))
    expect(await screen.findByTestId('bazar-setup-bazar-created')).toHaveTextContent('Zamalek Winter Bazar')
    await userEvent.setup().click(screen.getByTestId('bazar-setup-next'))
    expect(await screen.findByTestId('bazar-setup-pick')).toBeInTheDocument()
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/bazar-events'))).toBe(false)
  })

  test('w3 a failed receive: the next try sends the same request id', async () => {
    const state = { batch: null as Record<string, unknown> | null, failReceive: 1 }
    install(...wizardHandlers(state))
    const user = userEvent.setup()
    renderAt('/bazar/setup')
    await stepOneAndPick(user)
    await user.click(screen.getByRole('checkbox', { name: 'Knit scarf' }))
    await user.click(screen.getByTestId('bazar-setup-next'))
    await user.click(await screen.findByTestId('bazar-setup-receive'))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await user.click(screen.getByTestId('bazar-setup-receive'))
    expect(await screen.findByTestId('bazar-setup-lower')).toBeInTheDocument()
    const ids = calls.filter(c => c.url.endsWith('/bazar-setup/receive')).map(c => (c.body as { requestId: string }).requestId)
    expect(ids).toHaveLength(2)
    expect(ids[0]).toBe(ids[1])
  })

  test('w4 count-only pieces are listed like labelled ones: product, size ("One size"), quantity', async () => {
    install(...wizardHandlers({ batch: batch({ labelledReceiptId: null }) }))
    renderAt('/bazar/setup')
    const rows = await screen.findAllByTestId('bazar-lower-row')
    expect(rows.map(r => Array.from(r.querySelectorAll('td')).map(td => td.textContent))).toEqual([
      ['Knit scarfSCARF', 'One size', '3'], ['Linen shirtSHIRT-S', 'S', '2'], ['Linen shirtSHIRT-M', 'M', '1']])
    expect(screen.queryByTestId('bazar-setup-print')).not.toBeInTheDocument()
  })
})

// ── r1 / r2 ──────────────────────────────────────────────────────────────────

describe('r reminders', () => {
  test('r1 Overview card until Done; lowered → none; no batch → Set up your bazar', async () => {
    const state = { batch: batch() as Record<string, unknown> | null }
    install(on('GET', '/bazar-events', () => json(200, [])), ...wizardHandlers(state))
    const user = userEvent.setup()
    renderAt('/bazar')
    const card = await screen.findByTestId('bazar-lower-reminder')
    expect(card).toHaveTextContent('Lower 6 pieces at the location you took them from')
    await user.click(within(card).getByTestId('bazar-lower-view'))
    expect(await screen.findAllByTestId('bazar-lower-row')).toHaveLength(3)
    await user.click(screen.getByTestId('bazar-lower-done'))
    await waitFor(() => expect(screen.queryByTestId('bazar-lower-reminder')).not.toBeInTheDocument())
    cleanup()

    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-events')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/bazar-setup')).length).toBeGreaterThan(1))
    expect(screen.queryByTestId('bazar-lower-reminder')).not.toBeInTheDocument()
    cleanup()

    state.batch = null
    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-setup-start-card')).toBeInTheDocument()
  })

  test('r2 Get ready line for the batch bazar only, gone after Done', async () => {
    const state = { batch: batch() as Record<string, unknown> | null }
    install(...wizardHandlers(state), ...eventHandlers('ev2'))
    const user = userEvent.setup()
    renderAt(`/bazar/${EV}`)
    const line = await screen.findByTestId('bazar-lower-line')
    expect(line).toHaveTextContent(W.line.title)
    expect(line).toHaveTextContent('6 pieces')
    await user.click(within(line).getByTestId('bazar-lower-line-view'))
    await user.click(await screen.findByTestId('bazar-lower-done'))
    await waitFor(() => expect(screen.queryByTestId('bazar-lower-line')).not.toBeInTheDocument())
    cleanup()

    state.batch = batch()
    renderAt('/bazar/ev2')
    expect(await screen.findByTestId('bazar-setup')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/bazar-setup')).length).toBeGreaterThan(1))
    expect(screen.queryByTestId('bazar-lower-line')).not.toBeInTheDocument()
  })
})

// ── s1 / a1 ──────────────────────────────────────────────────────────────────

test('s1 /setup/bazar with the setup on → the wizard', async () => {
  install(...wizardHandlers({ batch: null }), on('PATCH', '/onboarding/prefs', () => json(200, {})))
  renderAt('/setup/bazar')
  expect(await screen.findByTestId('bazar-setup-wizard')).toBeInTheDocument()
  await waitFor(() => expect(calls.some(c => c.method === 'PATCH' && c.url.endsWith('/onboarding/prefs'))).toBe(true))
})

test('a1 Arabic: step 1, Pick, the lower list and the reminder', async () => {
  testI18n.changeLanguage('ar')
  const A = ar.bazar.wizard
  const state = { batch: null as Record<string, unknown> | null }
  install(...wizardHandlers(state))
  const user = userEvent.setup()
  renderAt('/bazar/setup')
  expect(await screen.findByText(A.bazar.title, { selector: 'h2' })).toBeInTheDocument()
  expect(screen.getByTestId('bazar-setup-just-me')).toHaveTextContent(A.bazar.justMe)
  await user.type(screen.getByTestId('bazar-setup-name'), 'بازار')
  await user.type(screen.getByTestId('bazar-setup-where'), 'الزمالك')
  await user.click(screen.getByTestId('bazar-setup-next'))
  expect(await screen.findByText(A.pick.title)).toBeInTheDocument()
  expect(screen.getByText('20 قطعة في مواقعك الأخرى')).toBeInTheDocument()
  cleanup()

  install(...wizardHandlers({ batch: batch() }))
  renderAt('/bazar/setup')
  expect(await screen.findByText(A.lower.title)).toBeInTheDocument()
  expect(await screen.findByText(A.oneSize)).toBeInTheDocument()
  expect(screen.getByTestId('bazar-lower-done')).toHaveTextContent(A.lower.done)
  cleanup()

  install(on('GET', '/bazar-events', () => json(200, [])), ...wizardHandlers({ batch: batch({ pieces: 3 }) }))
  renderAt('/bazar')
  expect(await screen.findByTestId('bazar-lower-reminder')).toHaveTextContent(A.reminder.title_few.replace('{{count}}', '3'))
})
