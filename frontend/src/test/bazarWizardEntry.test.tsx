import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarSetupWizard from '../pages/bazar/setup/BazarSetupWizard'
import BazarEvents from '../pages/bazar/BazarEvents'
import { SetupReadyPage } from '../pages/onboarding/SetupIntro'
import Signup from '../pages/Signup'
import { SetupGate } from '../components/SetupGate'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { refreshSetupStep } from '../setupGate'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'

/**
 * Bazar wizard entry (2026-11-26).
 *   e1 /bazar: "Set up from new stock" in the header and the empty state — owner of a bazar store with Shopify
 *      connected only (not a manager, not without a store); the empty state leads with it only when Traced Main is empty
 *   e2 Pick: "N already at Traced Main" per size; a warning once such a size is picked
 *   e3 block: blocked 'main_not_counting' with no batch → "Finish setting up Traced Main first", no wizard
 *   e4 copy: the new "only for stock that isn't in Traced yet" line (EN + AR), the wizard titled "Set up from new stock"
 *   p1 gate: setupPurpose 'ask' → "What will you use Traced for?" before Connect Shopify; saved once (PUT body);
 *      bazar → stepper without the address / Bosta steps; orders → the full stepper
 *   p2 no question when /me has no setupPurpose (an intent from signup / the App Store, or an older tenant)
 *   p3 Ready (Flow-4): "Both" is offered "Set up from new stock"; "orders" isn't
 *   s1 signup: "I'm here for the bazar" only when the public config says so (→ ?for=bazar, UTM kept)
 */

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const NS = en.bazar.newStock
const jwt = (role: string) => 'h.' + btoa(JSON.stringify({ role, sub: `u-${role}`, tenant: 't',
  exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s'

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: (body: unknown) => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []
function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn(body)
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: (body: unknown) => Promise<Response>): Handler => ({ method, path, fn })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname + l.search}</div> }

function renderAt(path: string) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes>
              <Route path="/bazar" element={<BazarEvents />} />
              <Route path="/bazar/setup" element={<BazarSetupWizard />} />
              <Route path="/setup/ready" element={<SetupReadyPage />} />
              <Route path="/signup" element={<Signup />} />
              <Route path="/fulfill" element={<SetupGate><div data-testid="screen">fulfill</div></SetupGate>} />
              <Route path="*" element={<Where />} />
            </Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

const ME = { role: 'owner', name: 'Nour', setupStep: null, setupIntro: null, bazar: true, bazarSetup: true, shopifyConnected: true }
const status = (over: Record<string, unknown> = {}) =>
  ({ on: true, canSetUp: true, batch: null, mainAvailable: 0, blocked: null, suggestStart: false, ...over })

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({ matches: false, media: query, onchange: null, addListener: () => {},
      removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }),
  })
})

beforeEach(() => {
  clearAccessToken()
  setAccessToken(jwt('owner'))
  resetCapabilitiesCache()
  refreshSetupStep()
  testI18n.changeLanguage('en')
  try { localStorage.clear() } catch { /* ignore */ }
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

// ── e1 ───────────────────────────────────────────────────────────────────────

describe('e1 Bazar page buttons', () => {
  test('owner, bazar store, Shopify connected, Main empty: header + empty state, the empty state leads with it', async () => {
    install(on('GET', '/me', () => json(200, ME)), on('GET', '/bazar-events', () => json(200, [])),
      on('GET', '/bazar-setup', () => json(200, status({ mainAvailable: 0 }))))
    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-new-stock-header')).toHaveAttribute('href', '/bazar/setup')
    expect(screen.getByTestId('bazar-new-stock-header')).toHaveTextContent(NS.button)
    const links = () => within(screen.getByTestId('bazar-empty-actions')).getAllByRole('link')
    await waitFor(() => expect(links()[0]).toHaveAttribute('data-testid', 'bazar-new-stock-empty'))
    expect(links().map(l => l.getAttribute('href'))).toEqual(['/bazar/setup', '/bazar/new'])
    // The "Bring your products" card isn't suggested to a store that didn't come for bazars.
    expect(screen.queryByTestId('bazar-setup-start-card')).not.toBeInTheDocument()
  })

  test('Main has available pieces: the empty state leads with Plan your first bazar', async () => {
    install(on('GET', '/me', () => json(200, ME)), on('GET', '/bazar-events', () => json(200, [])),
      on('GET', '/bazar-setup', () => json(200, status({ mainAvailable: 12 }))))
    renderAt('/bazar')
    await screen.findByTestId('bazar-new-stock-header')
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/bazar-setup'))).toBe(true))
    const links = () => within(screen.getByTestId('bazar-empty-actions')).getAllByRole('link')
    await waitFor(() => expect(links()).toHaveLength(2))
    expect(links().map(l => l.getAttribute('href'))).toEqual(['/bazar/new', '/bazar/setup'])
    expect(links()[0]).toHaveTextContent(en.bazar.r1.empty.button)
  })

  test('a manager, or no Shopify store connected: no button anywhere', async () => {
    setAccessToken(jwt('manager'))
    resetCapabilitiesCache()
    install(on('GET', '/me', () => json(200, { ...ME, role: 'manager' })), on('GET', '/bazar-events', () => json(200, [])),
      on('GET', '/bazar-setup', () => json(200, status({ canSetUp: false }))))
    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-empty')).toBeInTheDocument()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/me'))).toBe(true))
    expect(screen.queryByTestId('bazar-new-stock-header')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bazar-new-stock-empty')).not.toBeInTheDocument()
    cleanup()

    setAccessToken(jwt('owner'))
    resetCapabilitiesCache()
    install(on('GET', '/me', () => json(200, { ...ME, shopifyConnected: false })), on('GET', '/bazar-events', () => json(200, [])),
      on('GET', '/bazar-setup', () => json(200, status())))
    renderAt('/bazar')
    expect(await screen.findByTestId('bazar-empty')).toBeInTheDocument()
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/me'))).toBe(true))
    expect(screen.queryByTestId('bazar-new-stock-header')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bazar-new-stock-empty')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('bazar-empty-actions')).getAllByRole('link').map(l => l.getAttribute('href')))
      .toEqual(['/bazar/new'])
  })
})

// ── e2 / e3 / e4 — the wizard ───────────────────────────────────────────────

const PRODUCTS = {
  stockKnown: true,
  products: [{ id: 'p-shirt', title: 'Linen shirt', imageUrl: null, variants: [
    { id: 'v-s', title: 'S', sku: 'SHIRT-S', price: '960.00', otherLocations: 4, atMain: 3 },
    { id: 'v-m', title: 'M', sku: 'SHIRT-M', price: '960.00', otherLocations: 7, atMain: 0 }] }],
}
const DETAIL = { id: 'ev1', name: 'Zamalek Winter Bazar', starts_on: '2026-11-20', ends_on: '2026-11-22', status: 'draft',
  location_id: 'l1', location_name: 'Zamalek', transfer_id: 't-1', payment_methods: ['cash'], seller_roles: ['owner'],
  counts: { sent: 0, at_bazar: 0, sold: 0, back: 0, lost: 0 }, today: { business_day: '2026-11-21', by_method: {}, total: '0', sales: 0 },
  totals: { by_method: {}, total: '0', sales: 0, returns: 0 } }

describe('the wizard', () => {
  test('e2 Pick: "N already at Traced Main" per size, and a warning once such a size is picked', async () => {
    localStorage.setItem('traced.bazarSetup.v1', JSON.stringify({ eventId: 'ev1' }))
    install(on('GET', '/me', () => json(200, ME)), on('GET', '/bazar-setup', () => json(200, status())),
      on('GET', '/bazar-events/ev1', () => json(200, DETAIL)), on('GET', '/bazar-setup/products', () => json(200, PRODUCTS)))
    renderAt('/bazar/setup')
    expect(await screen.findByTestId('bazar-setup-pick')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('checkbox', { name: 'Linen shirt' }))
    expect(await screen.findByText(/S · 4 in your other locations/)).toHaveTextContent('3 already at Traced Main')
    expect(screen.getByText(/M · 7 in your other locations/)).not.toHaveTextContent('already at Traced Main')
    // Ticking the product sets 1 of each size: S has 3 at Main → the warning names them.
    expect(await screen.findByTestId('bazar-setup-at-main-warning')).toHaveTextContent(/3 pieces of the sizes you picked/)
  })

  test('e3 blocked: "Finish setting up Traced Main first", no wizard', async () => {
    install(on('GET', '/me', () => json(200, ME)), on('GET', '/bazar-setup', () => json(200, status({ blocked: 'main_not_counting' }))))
    renderAt('/bazar/setup')
    const block = await screen.findByTestId('bazar-setup-main-not-counting')
    expect(block).toHaveTextContent(NS.blockTitle)
    expect(screen.queryByTestId('bazar-setup-wizard')).not.toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/bazar-setup/products'))).toBe(false)
  })

  test('e4 copy: the new-stock line and title (EN), Arabic has its own', async () => {
    install(on('GET', '/me', () => json(200, ME)), on('GET', '/bazar-setup', () => json(200, status())))
    renderAt('/bazar/setup')
    expect(await screen.findByText(en.bazar.wizard.bazar.nothingChanges)).toBeInTheDocument()
    expect(en.bazar.wizard.bazar.nothingChanges).toBe("Use this only for stock that isn't in Traced yet. Traced adds these " +
      'pieces to Traced Main in your Shopify, then you lower the same amounts at the location you took them from. If the ' +
      'pieces are already in Traced, use Plan your first bazar and send them from stock.')
    expect(screen.getByText('Set up from new stock')).toBeInTheDocument()
    expect(ar.bazar.wizard.bazar.nothingChanges).not.toBe(en.bazar.wizard.bazar.nothingChanges)
    expect(ar.bazar.wizard.bazar.nothingChanges).toContain('Traced Main')
  })
})

// ── p1 / p2 / p3 — "What will you use Traced for?" ───────────────────────────

const GATE_ME = { role: 'owner', name: 'Nour Adel', setupStep: 'shopify', setupPurpose: 'ask' }
const CONNECTIONS = {
  shopify: { connected: false, storeId: null, shopDomain: null, connectionType: null, status: 'disconnected', importStatus: null, lastSyncAt: null },
  bosta: { connected: false }, customAppAvailable: false, oauthAvailable: true,
  shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
}
const stepLabels = () => within(screen.getByTestId('setup-stepper')).getAllByText(/./, { selector: 'span[data-state]' })
  .map(s => [s.textContent?.replace(/^\d/, ''), s.getAttribute('data-state')])

describe('setup purpose', () => {
  test.each([
    ['bazar', [['Your plan', 'done'], ['Shopify', 'current']]],
    ['orders', [['Your plan', 'done'], ['Shopify', 'current'], ['Warehouse address', 'todo'], ['Bosta', 'todo']]],
    ['both', [['Your plan', 'done'], ['Shopify', 'current'], ['Warehouse address', 'todo'], ['Bosta', 'todo']]],
  ])('p1 ask → choose %s → saved once → Connect Shopify with its stepper', async (choice, stepper) => {
    let puts = 0
    install(on('GET', '/me', () => json(200, GATE_ME)), on('GET', '/connections', () => json(200, CONNECTIONS)),
      on('PUT', '/setup/purpose', () => { puts++; return json(200, { purpose: choice, setupStep: 'shopify' }) }))
    renderAt('/fulfill')
    expect(await screen.findByTestId('onboarding-step-purpose')).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-step-shopify')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Welcome, Nour. What will you use Traced for?')
    expect(stepLabels()[0]).toEqual(['Your plan', 'current'])
    const user = userEvent.setup()
    const label = { orders: 'Online orders & shipping', bazar: 'Bazars & pop-up events', both: 'Both' }[choice]!
    await user.click(screen.getByRole('radio', { name: new RegExp(`^${label}`) }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('onboarding-step-shopify')).toBeInTheDocument()
    expect(calls.filter(c => c.method === 'PUT').map(c => c.body)).toEqual([{ purpose: choice }])
    expect(puts).toBe(1)
    expect(stepLabels()).toEqual(stepper)
  })

  test('p2 no setupPurpose in /me (intent from signup / App Store, older tenants): straight to Connect Shopify', async () => {
    install(on('GET', '/me', () => json(200, { role: 'owner', setupStep: 'shopify' })), on('GET', '/connections', () => json(200, CONNECTIONS)))
    renderAt('/fulfill')
    expect(await screen.findByTestId('onboarding-step-shopify')).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-step-purpose')).not.toBeInTheDocument()
    expect(screen.queryByText('Your plan')).not.toBeInTheDocument()
  })

  test('p2b a manager never gets the question (waiting screen)', async () => {
    setAccessToken(jwt('manager'))
    install(on('GET', '/me', () => json(200, { ...GATE_ME, role: 'manager' })))
    renderAt('/fulfill')
    expect(await screen.findByTestId('onboarding-manager-waiting')).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-step-purpose')).not.toBeInTheDocument()
  })

  test('p3 Ready: "Both" is offered Set up from new stock; "orders" is not', async () => {
    const ready = (purpose: string) => ({ role: 'owner', name: 'Nour', setupStep: null, setupIntro: 'ready', setupPurpose: purpose,
      bazar: true, bazarSetup: true, shopifyConnected: true })
    install(on('GET', '/me', () => json(200, ready('both'))), on('PATCH', '/onboarding/prefs', () => json(200, {})))
    renderAt('/setup/ready')
    expect(await screen.findByTestId('setup-ready')).toBeInTheDocument()
    const offer = await screen.findByTestId('setup-ready-bazar')
    expect(within(offer).getByRole('link', { name: NS.button })).toHaveAttribute('href', '/bazar/setup')
    cleanup()

    refreshSetupStep()
    resetCapabilitiesCache()
    install(on('GET', '/me', () => json(200, ready('orders'))), on('PATCH', '/onboarding/prefs', () => json(200, {})))
    renderAt('/setup/ready')
    expect(await screen.findByTestId('setup-ready')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/me')).length).toBeGreaterThan(0))
    expect(screen.queryByTestId('setup-ready-bazar')).not.toBeInTheDocument()
  })
})

// ── s1 — signup link ─────────────────────────────────────────────────────────

describe('s1 signup link', () => {
  test('shown only when the public config says so; → ?for=bazar keeping UTM; switches the page to bazar', async () => {
    clearAccessToken()
    window.history.replaceState(null, '', '/signup?utm_source=ig')
    install(on('GET', '/public/signup-config', () => json(200, { bazarSignup: true })))
    renderAt('/signup')
    const link = await screen.findByTestId('signup-bazar-link')
    expect(link).toHaveTextContent("I'm here for the bazar")
    expect(link).toHaveAttribute('href', '/signup?utm_source=ig&for=bazar')
    await userEvent.setup().click(link)
    expect(await screen.findByText(en.signup.subtitleBazar)).toBeInTheDocument()
    expect(screen.queryByTestId('signup-bazar-link')).not.toBeInTheDocument()
    cleanup()

    window.history.replaceState(null, '', '/signup')
    install(on('GET', '/public/signup-config', () => json(200, { bazarSignup: false })))
    renderAt('/signup')
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/public/signup-config'))).toBe(true))
    expect(screen.queryByTestId('signup-bazar-link')).not.toBeInTheDocument()
  })
})
