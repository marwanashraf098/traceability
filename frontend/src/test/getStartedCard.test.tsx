import { test, expect, describe, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import Layout from '../components/Layout'
import Overview from '../pages/Overview'
import GetStartedCard, { shownCardState } from '../components/GetStartedCard'
import { forgetOnboardingProgress, useOnboardingProgress } from '../onboardingProgress'
import { forgetPagesSeen } from '../pageSeen'
import { resetCapabilitiesCache } from '../capabilities'
import type { OnboardingProgress } from '../api'

/**
 * Onboarding O3 — the Get started card, the Setup chip and the sidebar "not opened yet" dots.
 *   gs1  each setup step: done → ✓ (no link); pending → a link to the right page
 *   gs2  courier: "Connect Bosta"; skipped → "Bosta · later"; done → "Bosta" with Bosta, else "Courier"
 *   gs3  tasks: a tick only when tried, "N of M tried", Open only (no Tour), no Lookup tile
 *   gs4  "Set up shelves & boxes" only when the server lists it (places on)
 *   gs5  the pickup tile says Bosta only when Bosta is connected
 *   gs6  the search filters the tiles (EN and AR words); nothing matches → a message
 *   gs7  shownCardState: auto-slim (never written) when 'full' AND (setup > 7 days ago OR 6+ tried); a choice wins
 *   gs8  every action writes PATCH /onboarding/prefs card: Minimise, ×, Show, Show again
 *   gs9  never for a worker or station mode (no request); never for demo / review (not even "Show again")
 *   gs10 the Setup chip: N/5 from progress; gone when the card is hidden or everything is done
 *   gs11 dots: unopened owner / manager items, accessible label; none when the card is hidden, without uiState,
 *        or for a worker; the page on screen never
 *   gs12 a visit posts pageSeen once per page per page load; never for an opened page, a worker or station mode
 *   gs13 AR / RTL
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

function json(body: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

const DAY = 24 * 60 * 60 * 1000

function progress(over: Partial<OnboardingProgress> & { done?: string[]; skipped?: string[]; tried?: string[]; places?: boolean } = {}): OnboardingProgress {
  const { done = ['shopify', 'address'], skipped = [], tried = [], places = false, ...rest } = over
  const keys = ['shopify', 'address', 'first_receiving', 'courier', 'test_label'] as const
  const taskKeys = ['receive', 'pack', 'pickup', 'returns', 'transfer', 'stock_take', 'portal', 'team', ...(places ? ['places'] : [])]
  return {
    steps: keys.map(key => ({ key, done: done.includes(key), skippable: key === 'courier' || key === 'test_label',
      skipped: skipped.includes(key) && !done.includes(key) })),
    tasks: taskKeys.map(key => ({ key, tried: tried.includes(key) })),
    card: 'full', cardChosen: false, setupCompletedAt: new Date(Date.now() - DAY).toISOString(), bostaConnected: false,
    pickPackChosen: false, labelingPace: null,
    ...rest,
  }
}

type Call = { url: string; method: string; body: unknown }

function stubApi(opts: { progress?: OnboardingProgress | (() => OnboardingProgress); me?: Record<string, unknown> }): Call[] {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(init.body as string) : null
    calls.push({ url, method, body })
    if (url.endsWith('/onboarding/progress')) {
      const p = typeof opts.progress === 'function' ? opts.progress() : opts.progress
      return p ? json(p) : json({}, 500)
    }
    if (url.endsWith('/onboarding/prefs')) {
      const p = typeof opts.progress === 'function' ? opts.progress() : opts.progress ?? progress()
      return json({ ...p, ...body, cardChosen: true })
    }
    if (url.endsWith('/me')) return json(opts.me ?? { name: 'Mona', email: null, role: 'owner' })
    if (url.endsWith('/me/ui-state')) return json({ toursDone: [], pagesSeen: [] })
    if (url.includes('/exceptions/count')) return json({ count: 0 })
    if (url.includes('/overview/trends')) return json([])
    if (url.includes('/overview/late-to-pack')) return json({ overdue: 0, over48: 0 })
    if (url.includes('/overview/top-skus')) return json([])
    if (url.includes('/orders/funnel')) return json({ newCount: 0, picking: 0, packed: 0, courier: 0, delivered: 0 })
    if (url.includes('/orders/summary')) return json({ total: 0, processing: 0, withCourier: 0, delivered: 0, returned: 0 })
    if (url.includes('/inventory/valuation')) return json({ lowStockCount: 0, inventoryValue: 0, variantsCosted: 0, variantsTotal: 0 })
    if (url.includes('/connections')) return json({ shopify: { connected: true, status: 'connected' }, bosta: { connected: false } })
    if (url.includes('/orders?')) return json({ items: [], page: 0, size: 20, total: 0 })
    if (url.includes('/exceptions?')) return json({ items: [] })
    if (url.includes('/locations')) return json([])
    return json({})
  }))
  return calls
}

function Harness() {
  const p = useOnboardingProgress()
  return p ? <GetStartedCard progress={p} /> : null
}

function renderCard(p: OnboardingProgress, lng: 'en' | 'ar' = 'en') {
  return render(
    <MemoryRouter><I18nextProvider i18n={i18nFor(lng)}><GetStartedCard progress={p} /></I18nextProvider></MemoryRouter>)
}

function renderTree(ui: React.ReactElement, path = '/overview', lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18nFor(lng)}>
          <ToastProvider>
            <Routes><Route path="*" element={ui} /></Routes>
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
  forgetOnboardingProgress()
  forgetPagesSeen()
  resetCapabilitiesCache()
  localStorage.removeItem('stationMode')
  setAccessToken(token('owner'))
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('setup row', () => {
  test('gs1 done → ✓ with no link; pending → a link to the right page', () => {
    renderCard(progress({ done: ['shopify'] }))
    const shopify = screen.getByTestId('get-started-step-shopify')
    expect(shopify).toHaveAttribute('data-done', 'true')
    expect(within(shopify).queryByRole('link')).toBeNull()
    expect(shopify).toHaveTextContent('Done:Shopify')
    const href = (key: string) => within(screen.getByTestId(`get-started-step-${key}`)).getByRole('link').getAttribute('href')
    expect(href('address')).toBe('/settings?tab=locations')
    expect(href('first_receiving')).toBe('/receiving')
    expect(href('courier')).toBe('/settings?tab=connections')
    expect(href('test_label')).toBe('/receiving')
    expect(screen.getByTestId('get-started-step-test_label')).toHaveTextContent('Print a test label')
    expect(screen.getByTestId('get-started-count')).toHaveTextContent('1 of 5 done')
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  test('gs2 courier wording: Connect Bosta / Bosta · later / Bosta / Courier', () => {
    renderCard(progress())
    expect(screen.getByTestId('get-started-step-courier')).toHaveTextContent('Connect Bosta')
    cleanup()
    renderCard(progress({ skipped: ['courier'] }))
    expect(within(screen.getByTestId('get-started-step-courier')).getByRole('link')).toHaveTextContent('Bosta · later')
    cleanup()
    renderCard(progress({ done: ['courier'], bostaConnected: true }))
    expect(screen.getByTestId('get-started-step-courier')).toHaveTextContent(/^Done:Bosta$/)
    cleanup()
    renderCard(progress({ done: ['courier'], bostaConnected: false }))
    expect(screen.getByTestId('get-started-step-courier')).toHaveTextContent(/^Done:Courier$/)
  })
})

describe('task grid', () => {
  test('gs3 a tick only when tried; N of M tried; Open only, no Tour, no Lookup', () => {
    renderCard(progress({ tried: ['receive', 'returns'] }))
    expect(screen.getByTestId('get-started-tried')).toHaveTextContent('2 of 8 tried')
    expect(screen.getByTestId('get-started-task-receive')).toHaveAttribute('data-tried', 'true')
    expect(screen.getByTestId('get-started-task-receive')).toHaveTextContent('tried')
    expect(screen.getByTestId('get-started-task-pack')).toHaveAttribute('data-tried', 'false')
    expect(within(screen.getByTestId('get-started-task-pack')).getByRole('link', { name: 'Open: Pack an order' }))
      .toHaveAttribute('href', '/fulfill')
    const targets = Object.fromEntries(['receive', 'pickup', 'returns', 'transfer', 'stock_take', 'portal', 'team'].map(k =>
      [k, within(screen.getByTestId(`get-started-task-${k}`)).getByRole('link').getAttribute('href')]))
    expect(targets).toEqual({ receive: '/receiving', pickup: '/pickups', returns: '/returns', transfer: '/transfers',
      stock_take: '/stock-take', portal: '/settings?tab=portal', team: '/settings?tab=users' })
    expect(screen.queryByText(/tour/i)).toBeNull()
    expect(screen.queryByText(/lookup/i)).toBeNull()
  })

  test('gs4 Set up shelves & boxes only when places are on', () => {
    renderCard(progress())
    expect(screen.queryByTestId('get-started-task-places')).toBeNull()
    cleanup()
    renderCard(progress({ places: true }))
    expect(within(screen.getByTestId('get-started-task-places')).getByRole('link')).toHaveAttribute('href', '/settings?tab=places')
    expect(screen.getByTestId('get-started-task-places')).toHaveTextContent('Set up shelves & boxes')
  })

  test('gs5 the pickup tile says Bosta only when Bosta is connected', () => {
    renderCard(progress())
    expect(screen.getByTestId('get-started-task-pickup')).toHaveTextContent('Hand parcels to your courier')
    cleanup()
    renderCard(progress({ bostaConnected: true }))
    expect(screen.getByTestId('get-started-task-pickup')).toHaveTextContent('Hand parcels to Bosta')
  })

  test('gs6 search filters the tiles; nothing matches → a message', async () => {
    renderCard(progress())
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Search tasks'), 'refund')
    expect(screen.getAllByTestId(/^get-started-task-/).map(e => e.dataset.testid))
      .toEqual(['get-started-task-returns', 'get-started-task-portal'])
    await user.clear(screen.getByLabelText('Search tasks'))
    await user.type(screen.getByLabelText('Search tasks'), 'zzz')
    expect(screen.getByTestId('get-started-no-match')).toBeInTheDocument()
    cleanup()
    renderCard(progress(), 'ar')
    await user.type(screen.getByLabelText('ابحث في المهام'), 'جرد')
    expect(screen.getAllByTestId(/^get-started-task-/).map(e => e.dataset.testid)).toEqual(['get-started-task-stock_take'])
  })
})

describe('card states', () => {
  test('gs7 auto-slim when settled (7+ days or 6+ tried), never over a choice', () => {
    const now = Date.now()
    expect(shownCardState(progress({ setupCompletedAt: new Date(now - 6 * DAY).toISOString(),
      tried: ['receive', 'pack', 'pickup', 'returns', 'transfer'] }), now)).toBe('full')
    expect(shownCardState(progress({ setupCompletedAt: new Date(now - 8 * DAY).toISOString() }), now)).toBe('slim')
    expect(shownCardState(progress({ tried: ['receive', 'pack', 'pickup', 'returns', 'transfer', 'team'] }), now)).toBe('slim')
    expect(shownCardState(progress({ setupCompletedAt: new Date(now - 30 * DAY).toISOString(), cardChosen: true }), now)).toBe('full')
    expect(shownCardState(progress({ setupCompletedAt: null }), now)).toBe('full')
    expect(shownCardState(progress({ card: 'hidden' }), now)).toBe('hidden')
    expect(shownCardState(progress({ card: 'slim', cardChosen: true }), now)).toBe('slim')
  })

  test('gs7b the auto-slim is shown, never written', async () => {
    const calls = stubApi({ progress: progress({ setupCompletedAt: new Date(Date.now() - 10 * DAY).toISOString() }) })
    renderTree(<Harness />)
    expect(await screen.findByTestId('get-started-slim')).toHaveTextContent('Next: Do your first receiving')
    expect(calls.some(c => c.method === 'PATCH')).toBe(false)
  })

  test('gs8 Minimise, ×, Show and Show again each PATCH card', async () => {
    const calls = stubApi({ progress: progress() })
    renderTree(<Harness />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Minimise' }))
    await screen.findByTestId('get-started-slim')
    await user.click(screen.getByRole('button', { name: 'Show' }))
    await screen.findByTestId('get-started-card')
    await user.click(screen.getByRole('button', { name: 'Hide Get started' }))
    await user.click(await screen.findByRole('button', { name: 'Show Get started again' }))
    await screen.findByTestId('get-started-card')
    await waitFor(() => expect(calls.filter(c => c.method === 'PATCH').map(c => c.body))
      .toEqual([{ card: 'slim' }, { card: 'full' }, { card: 'hidden' }, { card: 'full' }]))
    expect(calls.filter(c => c.method === 'PATCH').every(c => c.url.endsWith('/onboarding/prefs'))).toBe(true)
  })
})

describe('who sees it', () => {
  test('gs9 never for a worker or in station mode (no request); never for demo / review', async () => {
    setAccessToken(token('worker'))
    let calls = stubApi({ progress: progress() })
    renderTree(<Layout><Overview /></Layout>)
    await new Promise(r => setTimeout(r, 30))
    expect(calls.some(c => c.url.endsWith('/onboarding/progress'))).toBe(false)
    cleanup(); vi.unstubAllGlobals()

    localStorage.setItem('stationMode', 'true')
    setAccessToken(token('owner'))
    calls = stubApi({ progress: progress() })
    renderTree(<Layout><Overview /></Layout>)
    await new Promise(r => setTimeout(r, 30))
    expect(calls.some(c => c.url.endsWith('/onboarding/progress'))).toBe(false)
    expect(screen.queryByTestId('get-started-card')).toBeNull()
    cleanup(); vi.unstubAllGlobals(); localStorage.removeItem('stationMode')

    // Demo / review: the server answers 'hidden' — and the screen shows nothing at all, not even "Show again".
    resetCapabilitiesCache(); forgetOnboardingProgress()
    stubApi({ progress: progress({ card: 'hidden' }), me: { name: 'Demo', role: 'owner', scanHelpers: true, demoMode: true } })
    renderTree(<Layout><Overview /></Layout>)
    await waitFor(() => expect(screen.getByTestId('stat-cards')).toBeInTheDocument())
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByTestId('get-started-hidden')).toBeNull()
    expect(screen.queryByTestId('get-started-card')).toBeNull()
  })

  test('gs9b control: a real owner sees it on Overview', async () => {
    stubApi({ progress: progress() })
    renderTree(<Layout><Overview /></Layout>)
    expect(await screen.findByTestId('get-started-card')).toBeInTheDocument()
  })
})

describe('Layout: Setup chip, dots and pageSeen', () => {
  async function openMenu() {
    await waitFor(() => expect(screen.getAllByText('MO').length).toBeGreaterThan(0))
    const btn = screen.getAllByText('MO').map(e => e.closest('button')).find(Boolean)!
    await userEvent.setup().click(btn)
  }

  test('gs10 chip N/5 from progress; gone when hidden or all done', async () => {
    stubApi({ progress: progress({ done: ['shopify', 'address', 'first_receiving'] }), me: { name: 'Mona Odeh', role: 'owner' } })
    renderTree(<Layout><div /></Layout>)
    await openMenu()
    expect(await screen.findByText('Setup 3/5')).toBeInTheDocument()
    cleanup(); vi.unstubAllGlobals(); forgetOnboardingProgress()

    stubApi({ progress: progress({ card: 'hidden' }), me: { name: 'Mona Odeh', role: 'owner' } })
    renderTree(<Layout><div /></Layout>)
    await openMenu()
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByText(/^Setup \d\/5$/)).toBeNull()
    cleanup(); vi.unstubAllGlobals(); forgetOnboardingProgress()

    stubApi({ progress: progress({ done: ['shopify', 'address', 'first_receiving', 'courier', 'test_label'] }),
      me: { name: 'Mona Odeh', role: 'owner' } })
    renderTree(<Layout><div /></Layout>)
    await openMenu()
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByText(/^Setup \d\/5$/)).toBeNull()
  })

  test('gs11 dots on unopened items (accessible), never on the page on screen', async () => {
    stubApi({ progress: progress(), me: { name: 'Mona', role: 'owner', uiState: { toursDone: [], pagesSeen: ['overview', 'orders'] } } })
    renderTree(<Layout><div /></Layout>, '/inventory')
    const dot = await screen.findByTestId('nav-dot-fulfill')
    expect(dot).toHaveAttribute('aria-hidden')
    expect(screen.getByRole('link', { name: 'Pick & Pack, not opened yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Orders' })).toBeInTheDocument()
    expect(screen.getByTestId('nav-dot-settings')).toBeInTheDocument()
    expect(screen.queryByTestId('nav-dot-orders')).toBeNull()
    expect(screen.queryByTestId('nav-dot-overview')).toBeNull()
    expect(screen.queryByTestId('nav-dot-inventory')).toBeNull()   // the page on screen
  })

  test('gs11b no dots when the card is hidden, without uiState, or for a worker', async () => {
    const seen = { toursDone: [], pagesSeen: [] }
    stubApi({ progress: progress({ card: 'hidden' }), me: { name: 'Mona', role: 'owner', uiState: seen } })
    renderTree(<Layout><div /></Layout>, '/inventory')
    await screen.findByText('Mona')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryAllByTestId(/^nav-dot-/)).toHaveLength(0)
    cleanup(); vi.unstubAllGlobals(); forgetOnboardingProgress()

    stubApi({ progress: progress(), me: { name: 'Mona', role: 'owner' } })
    renderTree(<Layout><div /></Layout>, '/inventory')
    await screen.findByText('Mona')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryAllByTestId(/^nav-dot-/)).toHaveLength(0)
    cleanup(); vi.unstubAllGlobals(); forgetOnboardingProgress()

    setAccessToken(token('worker'))
    stubApi({ progress: progress(), me: { name: 'Wael', role: 'worker', uiState: seen } })
    renderTree(<Layout><div /></Layout>, '/fulfill')
    await screen.findByText('Wael')
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryAllByTestId(/^nav-dot-/)).toHaveLength(0)
  })

  test('gs11c a manager gets dots too', async () => {
    setAccessToken(token('manager'))
    stubApi({ progress: progress(), me: { name: 'Mona', role: 'manager', uiState: { toursDone: [], pagesSeen: [] } } })
    renderTree(<Layout><div /></Layout>, '/overview')
    expect(await screen.findByTestId('nav-dot-orders')).toBeInTheDocument()
  })

  test('gs12 pageSeen posted once per page per page load; never for an opened page, a worker or station', async () => {
    let calls = stubApi({ progress: progress(), me: { name: 'Mona', role: 'owner', uiState: { toursDone: [], pagesSeen: ['orders'] } } })
    renderTree(<Layout><div /></Layout>, '/transfers/abc')
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/me/ui-state'))).toHaveLength(1))
    expect(calls.find(c => c.url.endsWith('/me/ui-state'))!.body).toEqual({ pageSeen: 'transfers' })
    cleanup()
    renderTree(<Layout><div /></Layout>, '/transfers')          // same page, same page load → no second POST
    renderTree(<Layout><div /></Layout>, '/orders')             // already opened → none
    await new Promise(r => setTimeout(r, 50))
    expect(calls.filter(c => c.url.endsWith('/me/ui-state'))).toHaveLength(1)
    cleanup(); vi.unstubAllGlobals(); forgetPagesSeen()

    setAccessToken(token('worker'))
    calls = stubApi({ me: { name: 'Wael', role: 'worker', uiState: { toursDone: [], pagesSeen: [] } } })
    renderTree(<Layout><div /></Layout>, '/fulfill')
    await screen.findByText('Wael')
    cleanup(); vi.unstubAllGlobals()

    localStorage.setItem('stationMode', 'true')
    setAccessToken(token('owner'))
    const calls2 = stubApi({ me: { name: 'Mona', role: 'owner', uiState: { toursDone: [], pagesSeen: [] } } })
    renderTree(<Layout><div /></Layout>, '/fulfill')
    await new Promise(r => setTimeout(r, 50))
    expect([...calls, ...calls2].some(c => c.url.endsWith('/me/ui-state'))).toBe(false)
  })
})

describe('AR / RTL', () => {
  test('gs13 the card in Arabic', () => {
    document.documentElement.dir = 'rtl'
    renderCard(progress({ skipped: ['courier'], tried: ['receive'] }), 'ar')
    const card = screen.getByTestId('get-started-card')
    expect(within(card).getByRole('heading', { level: 2 })).toHaveTextContent('ابدأ مع Traced')
    expect(screen.getByTestId('get-started-step-courier')).toHaveTextContent('بوستا · لاحقًا')
    expect(screen.getByTestId('get-started-tried')).toHaveTextContent('جرّبت 1 من 8')
    expect(screen.getByRole('button', { name: 'تصغير' })).toBeInTheDocument()
  })
})
