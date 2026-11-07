import { test, expect, describe, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { OwnerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { SetupCourierPage, SetupReadyPage } from '../pages/onboarding/SetupIntro'
import { forgetIntroRedirects, markShopifyStepSeen, refreshSetupStep } from '../setupGate'

/**
 * Onboarding O2 — the setup intro (Flow-3 courier, Flow-4 "You're connected") and Flow-1's connected view.
 *   si1  owner, gate done, setupIntro 'courier' → sent to Flow-3 once per page load (never again after)
 *   si2  the gate finishing here → GET /me again → its intro
 *   si3  Skip writes prefs (skipped += courier, other skipped steps kept) → Flow-4; a failed write still goes on
 *   si4  Another courier → PUT /settings/couriers (switch on, the saved default carrier kept) → Flow-4
 *   si5  Bosta → POST /bosta/connect → the webhook secret (shown once) → Flow-4; a refusal stays with its error
 *   si6  Flow-3 hidden when the courier is done or skipped (setupIntro 'ready' → Flow-4; null → Overview)
 *   si7  Flow-4 shown once: PATCH setupIntroSeen true; nothing to show → Overview
 *   si8  Flow-4 chips + journey: Bosta · later / "your courier"; Bosta connected → Bosta ✓ and "Hand parcels to Bosta";
 *        Start → /receiving, explore → /overview; help and setup-call links
 *   si9  managers, workers and station mode are never routed to Flow-3 / Flow-4 (and are sent away from them)
 *   si10 demo / review tenants: the server sends no intro → the screen, untouched
 *   si11 Flow-1 "Connected": only on the device that showed the Shopify step; an App Store signup (arrives
 *        connected) starts at the address; Continue → the address form; no Shopify call, no counts
 *   si12 AR / RTL: Flow-3 and Flow-4 in Arabic
 */

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return inst
}

function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s'
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

const CONNECTIONS = (bosta: boolean, shopify: Partial<Record<string, unknown>> = {}) => ({
  shopify: { connected: true, storeId: 's', shopDomain: 'brand-eg.myshopify.com', connectionType: 'oauth',
    status: 'connected', importStatus: 'completed', lastSyncAt: null, locationState: 'active', ...shopify },
  bosta: { connected: bosta, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
  customAppAvailable: false, oauthAvailable: true,
  shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
})

const PROGRESS = (courier: { done: boolean; skipped: boolean }, testLabelSkipped = false) => ({
  steps: [
    { key: 'shopify', done: true, skippable: false, skipped: false },
    { key: 'address', done: true, skippable: false, skipped: false },
    { key: 'first_receiving', done: false, skippable: false, skipped: false },
    { key: 'courier', done: courier.done, skippable: true, skipped: courier.skipped },
    { key: 'test_label', done: false, skippable: true, skipped: testLabelSkipped },
  ],
  tasks: [], card: 'full', pickPackChosen: false, labelingPace: null, setupIntroSeen: false,
})

type Calls = { url: string; method: string; body: unknown }[]

function stubApi(opts: {
  me?: Record<string, unknown> | (() => Record<string, unknown>)
  connections?: unknown
  progress?: unknown
  prefsStatus?: number
  bostaStatus?: number
  couriers?: unknown
  address?: unknown
}): Calls {
  const calls: Calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(init.body as string) : null })
    if (url.endsWith('/me')) return json(200, typeof opts.me === 'function' ? opts.me() : (opts.me ?? {}))
    if (url.includes('/connections')) return json(200, opts.connections ?? CONNECTIONS(false))
    if (url.endsWith('/onboarding/progress')) return json(200, opts.progress ?? PROGRESS({ done: false, skipped: false }))
    if (url.endsWith('/onboarding/prefs')) return json(opts.prefsStatus ?? 200, opts.progress ?? {})
    if (url.endsWith('/bosta/connect')) return json(opts.bostaStatus ?? 200, { accountId: 'a', webhookSecret: 'sek-123' })
    if (url.endsWith('/settings/couriers')) {
      return json(200, opts.couriers ?? { enabled: false, defaultCarrier: 'Mylerz', suggestions: [], bostaConnected: false })
    }
    if (url.includes('/setup/address')) {
      if (method === 'PUT') return json(200, { setupStep: null })
      return json(200, opts.address ?? { address: null, provinces: [{ code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [] }] })
    }
    return json(200, {})
  }))
  return calls
}

/** Every path the router showed in this test (a redirect that bounces straight back still shows up here). */
const visited: string[] = []

function Where() {
  const l = useLocation()
  visited.push(l.pathname)
  return <div data-testid="where">{l.pathname}</div>
}

function renderAt(path: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18nFor(lng)}>
          <ToastProvider>
            <Routes>
              <Route path="/overview" element={<OwnerOnlyRoute><div data-testid="screen">overview</div><Where /></OwnerOnlyRoute>} />
              <Route path="/orders" element={<OwnerOnlyRoute><div data-testid="screen">orders</div><Where /></OwnerOnlyRoute>} />
              <Route path="/receiving" element={<div data-testid="receiving"><Where /></div>} />
              <Route path="/setup/courier" element={<><SetupCourierPage /><Where /></>} />
              <Route path="/setup/ready" element={<><SetupReadyPage /><Where /></>} />
              <Route path="/worker-home" element={<div data-testid="worker-home" />} />
            </Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({ matches: false, media: query, onchange: null, addListener: () => {},
      removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }),
  })
})

beforeEach(() => {
  visited.length = 0
  refreshSetupStep()
  forgetIntroRedirects()
  localStorage.removeItem('stationMode')
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

const OWNER_COURIER = { role: 'owner', name: 'Mona Adel', setupStep: null, setupIntro: 'courier' }
const OWNER_READY   = { role: 'owner', name: 'Mona Adel', setupStep: null, setupIntro: 'ready' }

describe('routing to the intro', () => {
  test('si1 an owner with setupIntro courier goes to Flow-3 once per page load', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_COURIER })
    renderAt('/overview')
    expect(await screen.findByTestId('setup-courier')).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/setup/courier')
    cleanup()
    // Same page load, another screen: not sent back.
    renderAt('/orders')
    expect(await screen.findByTestId('screen')).toHaveTextContent('orders')
  })

  test('si2 finishing the gate here asks /me again and follows its intro', async () => {
    setAccessToken(token('owner'))
    let saved = false
    const calls = stubApi({ me: () => saved ? OWNER_COURIER : { role: 'owner', setupStep: 'address' },
      address: { address: { provinceCode: 'C', city: 'Nasr City', street: '12 Abbas', building: null, floor: null },
        provinces: [{ code: 'C', nameEn: 'Cairo', nameAr: 'القاهرة', areas: [] }] } })
    renderAt('/overview')
    await screen.findByTestId('onboarding-step-address')
    await waitFor(() => expect((screen.getByLabelText('Governorate') as HTMLSelectElement).value).toBe('C'))
    saved = true
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save and continue' }))
    expect(await screen.findByTestId('setup-courier')).toBeInTheDocument()
    expect(calls.filter(c => c.url.endsWith('/me')).length).toBeGreaterThanOrEqual(2)
  })
})

describe('Flow-3 — courier', () => {
  test('si3 Skip for now writes skipped += courier (keeping the others) and goes to Flow-4', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({ me: OWNER_COURIER, progress: PROGRESS({ done: false, skipped: false }, true) })
    renderAt('/setup/courier')
    await screen.findByTestId('setup-courier')
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/onboarding/progress'))).toBe(true))
    await userEvent.setup().click(screen.getByTestId('setup-courier-skip'))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/setup/ready'))
    const patch = calls.find(c => c.method === 'PATCH' && c.url.endsWith('/onboarding/prefs'))!
    expect(patch.body).toEqual({ skipped: ['test_label', 'courier'] })
    expect(calls.some(c => c.url.endsWith('/bosta/connect') || (c.method === 'PUT' && c.url.endsWith('/settings/couriers')))).toBe(false)
  })

  test('si3b a failed skip write never traps the owner', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_COURIER, prefsStatus: 500 })
    renderAt('/setup/courier')
    await screen.findByTestId('setup-courier')
    await userEvent.setup().click(screen.getByTestId('setup-courier-skip'))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/setup/ready'))
  })

  test('si4 Another courier switches other couriers on (default carrier kept) and goes on', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({ me: OWNER_COURIER })
    renderAt('/setup/courier')
    await screen.findByTestId('setup-courier')
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/settings/couriers'))).toBe(true))
    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: /Another courier/ }))
    expect(screen.getByTestId('setup-courier-other-note')).toHaveTextContent('Nothing to connect.')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/setup/ready'))
    const put = calls.find(c => c.method === 'PUT' && c.url.endsWith('/settings/couriers'))!
    expect(put.body).toEqual({ enabled: true, defaultCarrier: 'Mylerz' })
    expect(calls.some(c => c.method === 'PATCH' && (c.body as { skipped?: unknown })?.skipped !== undefined)).toBe(false)
  })

  test('si5 Bosta: Test and connect → the webhook secret once → Flow-4; a refusal shows the error', async () => {
    setAccessToken(token('owner'))
    let calls = stubApi({ me: OWNER_COURIER, bostaStatus: 400 })
    renderAt('/setup/courier')
    await screen.findByTestId('setup-courier')
    const user = userEvent.setup()
    expect(screen.getByRole('radio', { name: /Bosta/ })).toHaveAttribute('aria-checked', 'true')
    await user.type(screen.getByLabelText('Bosta API key'), 'bad-key')
    await user.click(screen.getByRole('button', { name: 'Test and connect' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not connect. Check the API key and try again.')
    expect(screen.getByTestId('where')).toHaveTextContent('/setup/courier')
    cleanup(); vi.unstubAllGlobals(); refreshSetupStep(); forgetIntroRedirects()

    calls = stubApi({ me: OWNER_COURIER })
    renderAt('/setup/courier')
    await screen.findByTestId('setup-courier')
    await user.type(screen.getByLabelText('Bosta API key'), '  good-key ')
    await user.click(screen.getByRole('button', { name: 'Test and connect' }))
    const reveal = await screen.findByTestId('setup-bosta-secret')
    expect(reveal).toHaveTextContent('Webhook configuration')
    expect(within(reveal).getByDisplayValue('Bearer sek-123')).toBeInTheDocument()
    expect(calls.find(c => c.url.endsWith('/bosta/connect'))!.body).toEqual({ apiKey: 'good-key' })
    await user.click(screen.getByRole('button', { name: "Done, I've saved it" }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/setup/ready'))
  })

  test('si6 Flow-3 is hidden once the courier is done or skipped', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_READY })
    renderAt('/setup/courier')
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/setup/ready'))
    expect(screen.queryByTestId('setup-courier')).toBeNull()
    cleanup(); refreshSetupStep()
    stubApi({ me: { role: 'owner', setupStep: null, setupIntro: null } })
    renderAt('/setup/courier')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })

  test('si6b no "Recommended" wording, no video', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_COURIER })
    renderAt('/setup/courier')
    const card = await screen.findByTestId('setup-courier')
    expect(card.textContent).not.toMatch(/recommended|most traced stores/i)
    expect(document.body.textContent).not.toMatch(/video/i)
  })
})

describe('Flow-4 — You’re connected', () => {
  test('si7 shown once: PATCH setupIntroSeen true; nothing to show → Overview', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({ me: OWNER_READY, progress: PROGRESS({ done: false, skipped: true }) })
    renderAt('/setup/ready')
    expect(await screen.findByTestId('setup-ready')).toBeInTheDocument()
    await waitFor(() => expect(calls.filter(c => c.method === 'PATCH')).toHaveLength(1))
    expect(calls.find(c => c.method === 'PATCH')!.body).toEqual({ setupIntroSeen: true })
    cleanup(); refreshSetupStep()
    stubApi({ me: { role: 'owner', setupStep: null, setupIntro: null } })
    renderAt('/setup/ready')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
  })

  test('si8 skipped courier: Bosta · later and "your courier"; Start → /receiving', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_READY, progress: PROGRESS({ done: false, skipped: true }) })
    renderAt('/setup/ready')
    await screen.findByTestId('setup-ready')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent("You're connected")
    expect(screen.getByTestId('chip-shopify')).toHaveAttribute('data-done', 'true')
    expect(screen.getByTestId('chip-address')).toHaveAttribute('data-done', 'true')
    expect(screen.getByTestId('chip-courier')).toHaveTextContent('Bosta · later')
    expect(screen.getByTestId('chip-courier')).toHaveAttribute('data-done', 'false')
    const journey = screen.getByRole('list', { name: 'Your journey' })
    expect(within(journey).getAllByRole('listitem').map(li => li.textContent)).toEqual([
      'Start here1 · ReceiveGive your pieces barcodes',
      'Then2 · PackScan pieces into orders',
      'Then3 · PickupHand parcels to your courier',
      'Then4 · ReturnsScan back what comes back',
    ])
    expect(screen.getByTestId('setup-call')).toHaveAttribute('href', 'https://calendly.com/tracedtechnology/traced-setup-call')
    expect(screen.getByTestId('setup-help')).toHaveAttribute('href', 'https://wa.me/201037371204')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Start my first receiving session' }))
    expect(screen.getByTestId('receiving')).toBeInTheDocument()
  })

  test('si8b Bosta connected: Bosta ✓ and "Hand parcels to Bosta"; other courier: Your courier ✓; explore → Overview', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_READY, connections: CONNECTIONS(true), progress: PROGRESS({ done: true, skipped: false }) })
    renderAt('/setup/ready')
    await screen.findByTestId('setup-ready')
    expect(screen.getByTestId('chip-courier')).toHaveTextContent('Bosta')
    expect(screen.getByTestId('chip-courier')).toHaveAttribute('data-done', 'true')
    expect(screen.getByText('Hand parcels to Bosta')).toBeInTheDocument()
    cleanup(); refreshSetupStep(); forgetIntroRedirects()

    stubApi({ me: OWNER_READY, connections: CONNECTIONS(false), progress: PROGRESS({ done: true, skipped: false }) })
    renderAt('/setup/ready')
    await screen.findByTestId('setup-ready')
    expect(screen.getByTestId('chip-courier')).toHaveTextContent('Your courier')
    expect(screen.getByText('Hand parcels to your courier')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByTestId('setup-explore'))
    expect(await screen.findByTestId('where')).toHaveTextContent('/overview')
  })
})

describe('never for managers, workers, station mode, demo or review', () => {
  test('si9 a manager is never routed, and is sent away from the intro screens', async () => {
    setAccessToken(token('manager'))
    const calls = stubApi({ me: { role: 'manager', setupStep: null, setupIntro: 'courier' } })
    renderAt('/overview')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    expect(visited.filter(p => p.startsWith('/setup'))).toEqual([])
    cleanup()
    renderAt('/setup/courier')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    cleanup()
    renderAt('/setup/ready')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    expect(calls.some(c => c.method === 'PATCH')).toBe(false)
  })

  test('si9b a worker and station mode are never routed', async () => {
    setAccessToken(token('worker'))
    stubApi({ me: { role: 'worker', setupStep: null, setupIntro: 'courier' } })
    renderAt('/setup/courier')
    expect(await screen.findByTestId('worker-home')).toBeInTheDocument()
    cleanup()

    localStorage.setItem('stationMode', 'true')
    setAccessToken(token('owner'))
    const calls = stubApi({ me: OWNER_COURIER })
    renderAt('/overview')
    expect(screen.getByTestId('screen')).toHaveTextContent('overview')
    cleanup()
    renderAt('/setup/ready')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    expect(calls.some(c => c.method === 'PATCH')).toBe(false)
    localStorage.removeItem('stationMode')
  })

  test('si10 demo / review tenant (no intro from the server) → the screen, untouched', async () => {
    setAccessToken(token('owner'))
    const calls = stubApi({ me: { role: 'owner', setupStep: null, scanHelpers: true, demoMode: true } })
    renderAt('/overview')
    expect(await screen.findByTestId('screen')).toHaveTextContent('overview')
    expect(calls.some(c => c.url.includes('/onboarding'))).toBe(false)
  })
})

describe('Flow-1 — Connected', () => {
  test('si11 shown on the device that saw the Shopify step; Continue → the address form', async () => {
    setAccessToken(token('owner'))
    markShopifyStepSeen()
    const calls = stubApi({ me: { role: 'owner', name: 'Mona Adel', setupStep: 'address' },
      connections: CONNECTIONS(false, { importStatus: 'pending' }) })
    renderAt('/overview')
    const view = await screen.findByTestId('onboarding-shopify-connected')
    expect(within(view).getByRole('heading', { level: 1 })).toHaveTextContent("Welcome, Mona. Let's connect your store.")
    expect(await within(view).findByText('brand-eg.myshopify.com')).toBeInTheDocument()
    expect(screen.getByTestId('setup-row-products')).toHaveTextContent('ProductsImporting…')
    expect(screen.getByTestId('setup-row-locations')).toHaveTextContent(/^Your Shopify locations$/)
    expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Ready')
    expect(screen.queryByTestId('onboarding-step-address')).toBeNull()
    await userEvent.setup().click(within(view).getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('onboarding-step-address')).toBeInTheDocument()
    // Read-only: only Traced's own endpoints, nothing written.
    expect(calls.filter(c => c.method !== 'GET')).toHaveLength(0)
  })

  test('si11b an App Store signup (arrives connected, never saw the Shopify step) starts at the address', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', setupStep: 'address' } })
    renderAt('/overview')
    expect(await screen.findByTestId('onboarding-step-address')).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-shopify-connected')).toBeNull()
  })

  test('si11c the Shopify step: stepper, corrected panel copy, help link, no video', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: { role: 'owner', name: 'Mona Adel', setupStep: 'shopify' }, connections: { ...CONNECTIONS(false),
      shopify: { ...CONNECTIONS(false).shopify, connected: false, status: 'disconnected', shopDomain: null } } })
    renderAt('/overview')
    await screen.findByTestId('onboarding-step-shopify')
    const stepper = screen.getByTestId('setup-stepper')
    expect(stepper.textContent).toBe('1Shopify2Warehouse address3BostaThen: your first receiving')
    expect(screen.getByTestId('setup-shopify-panel')).toHaveTextContent(
      'Changes your Shopify stock only through actions you take in Traced: receiving, shipping, returns, stock takes and stock adjustments.')
    expect(screen.getByTestId('setup-shopify-panel').textContent).not.toMatch(/keeps selling|move pieces/)
    expect(screen.getByTestId('setup-help')).toHaveAttribute('href', 'https://wa.me/201037371204')
    expect(document.body.textContent).not.toMatch(/watch|video/i)
  })
})

describe('AR / RTL', () => {
  test('si12 Flow-3 and Flow-4 in Arabic', async () => {
    setAccessToken(token('owner'))
    stubApi({ me: OWNER_COURIER })
    renderAt('/setup/courier', 'ar')
    await screen.findByTestId('setup-courier')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('كيف تشحن طلباتك؟')
    expect(screen.getByRole('button', { name: 'اختبار وربط' })).toBeInTheDocument()
    expect(screen.getByTestId('setup-courier-skip')).toHaveTextContent('تخطَّ الآن')
    cleanup(); refreshSetupStep()

    stubApi({ me: OWNER_READY, progress: PROGRESS({ done: false, skipped: true }) })
    renderAt('/setup/ready', 'ar')
    await screen.findByTestId('setup-ready')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('تم الربط')
    expect(screen.getByTestId('chip-courier')).toHaveTextContent('بوستا · لاحقًا')
    expect(screen.getByText('سلّم الطرود لشركة الشحن')).toBeInTheDocument()
  })
})
