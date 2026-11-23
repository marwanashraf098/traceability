import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarJoin, { BazarInviteExpired } from '../pages/bazar/phone/BazarJoin'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
import { SellersPanel } from '../pages/bazar/SellersPanel'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

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
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})


// Bazar POS B6b — seller invites: the Sellers panel, the seller's first open (PIN), the expired page, Switch seller.

const SELLERS = (over: Partial<Record<string, unknown>> = {}) => ({
  event_name: 'Zamalek Winter Bazar', event_status: 'live', workers_allowed: true,
  sellers: [{ user_id: 'u-mona', name: 'Mona', phone: '01012345678', has_pin: false, added_at: '2026-11-19T10:00:00Z' }],
  ...over,
})
const LINK = 'https://app.tracedtech.com/auth/seller-invite?t=t-1#token=abc'

describe('Sellers panel (owner / manager)', () => {
  test('warns while workers can\'t sell, and allows them', async () => {
    setAccessToken(fakeJwt('owner'))
    let allowed = false
    install(
      on('POST', '/sellers/allow-workers', () => { allowed = true; return json(200, { workers_allowed: true }) }),
      on('GET', `/bazar-events/${EVENT}/sellers`, () => json(200, SELLERS({ workers_allowed: allowed }))),
    )
    renderAt('/x', <Route path="/x" element={<SellersPanel eventId={EVENT} />} />)
    expect(await screen.findByTestId('sellers-workers-warning')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Allow workers to sell at this bazar' }))
    await waitFor(() => expect(screen.queryByTestId('sellers-workers-warning')).not.toBeInTheDocument())
  })

  test('Add seller → name + phone → a link with Copy and Send on WhatsApp (event name + link prefilled)', async () => {
    setAccessToken(fakeJwt('owner'))
    install(
      on('GET', '/sellers/candidates', () => json(200, [])),
      on('POST', `/bazar-events/${EVENT}/sellers`, () => json(201, {
        seller: { user_id: 'u-new', name: 'Omar', phone: '01098765432', has_pin: false, added_at: 'x' },
        link: LINK, expires_at: '2026-11-20T10:00:00Z', event_name: 'Zamalek Winter Bazar', workers_allowed: true,
      })),
      on('GET', `/bazar-events/${EVENT}/sellers`, () => json(200, SELLERS())),
    )
    renderAt('/x', <Route path="/x" element={<SellersPanel eventId={EVENT} />} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Add seller' }))
    await userEvent.type(screen.getByTestId('seller-name'), 'Omar')
    await userEvent.type(screen.getByTestId('seller-phone'), '01098765432')
    await userEvent.click(screen.getByRole('button', { name: 'Create link' }))
    expect(await screen.findByTestId('seller-link-url')).toHaveTextContent(LINK)
    const post = calls.find(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/sellers`))
    expect(post?.body).toEqual({ name: 'Omar', phone: '01098765432' })
    const wa = new URL(screen.getByTestId('seller-whatsapp').getAttribute('href')!)
    expect(wa.origin + wa.pathname).toBe('https://wa.me/201098765432')
    expect(wa.searchParams.get('text')).toContain('Zamalek Winter Bazar')
    expect(wa.searchParams.get('text')).toContain(LINK)
  })

  test('Remove asks first — the text says whether the account is turned off — then removes', async () => {
    setAccessToken(fakeJwt('owner'))
    let removed = false
    const rows = [
      { user_id: 'u-omar', name: 'Omar', phone: null, has_pin: true, added_at: 'x', remove_turns_off_account: true },
      { user_id: 'u-hana', name: 'Hana', phone: null, has_pin: true, added_at: 'x', remove_turns_off_account: false },
    ]
    install(
      on('DELETE', `/sellers/u-hana`, () => { removed = true; return json(200, { sessions_revoked: 1, outcome: 'removed_from_event' }) }),
      on('GET', `/bazar-events/${EVENT}/sellers`, () => json(200, SELLERS({ sellers: removed ? rows.slice(0, 1) : rows }))),
    )
    renderAt('/x', <Route path="/x" element={<SellersPanel eventId={EVENT} />} />)
    const [omar, hana] = await screen.findAllByTestId('seller-row')
    await userEvent.click(within(omar).getByRole('button', { name: 'Remove' }))
    expect(within(omar).getByTestId('seller-remove-confirm')).toHaveTextContent('Remove Omar? Their account will be turned off.')
    await userEvent.click(within(omar).getByRole('button', { name: 'Keep' }))
    await userEvent.click(within(hana).getByRole('button', { name: 'Remove' }))
    expect(removed).toBe(false)
    expect(within(hana).getByTestId('seller-remove-confirm')).toHaveTextContent('Remove Hana from this bazar? They stay on your team.')
    await userEvent.click(within(hana).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(removed).toBe(true))
    await waitFor(() => expect(screen.getAllByTestId('seller-row')).toHaveLength(1))
  })
})

describe('The seller\'s first open', () => {
  const joinRoutes = (
    <>
      <Route path="/bazar/join/:id" element={<><BazarJoin /><Where /></>} />
      <Route path="/bazar/:id/sell" element={<Where />} />
    </>
  )

  test('no PIN → pick one (twice) → saved → lands on Sell', async () => {
    setAccessToken(fakeJwt('worker', 'u-mona'))
    let pinSet = false
    install(
      on('POST', '/bazar-sellers/me/pin', () => { pinSet = true; return json(200, { pin_set: true }) }),
      on('GET', `/bazar-events/${EVENT}/sellers/me`, () => json(200, {
        event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', name: 'Mona',
        needs_pin: !pinSet, removed: false, can_sell: true,
      })),
    )
    renderAt(`/bazar/join/${EVENT}`, joinRoutes)
    expect(await screen.findByText('Welcome, Mona')).toBeInTheDocument()
    await userEvent.type(screen.getByTestId('pin-new'), '4821')
    await userEvent.type(screen.getByTestId('pin-again'), '4822')
    await userEvent.click(screen.getByRole('button', { name: 'Save PIN and start selling' }))
    expect(await screen.findByText("The two PINs don't match.")).toBeInTheDocument()
    expect(pinSet).toBe(false)
    await userEvent.clear(screen.getByTestId('pin-again'))
    await userEvent.type(screen.getByTestId('pin-again'), '4821')
    await userEvent.click(screen.getByRole('button', { name: 'Save PIN and start selling' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent(`/bazar/${EVENT}/sell`))
    expect(calls.find(c => c.url.endsWith('/bazar-sellers/me/pin'))?.body).toEqual({ pin: '4821' })
  })

  test('workers not allowed → says so, no Sell screen', async () => {
    setAccessToken(fakeJwt('worker', 'u-mona'))
    install(on('GET', `/sellers/me`, () => json(200, {
      event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', name: 'Mona',
      needs_pin: false, removed: false, can_sell: false,
    })))
    renderAt(`/bazar/join/${EVENT}`, joinRoutes)
    expect(await screen.findByTestId('join-notAllowed')).toHaveTextContent("You can't sell at this bazar yet")
    expect(screen.getByTestId('where')).toHaveTextContent(`/bazar/join/${EVENT}`)
  })

  test('an expired / used link → the clear page', () => {
    renderAt('/bazar/invite-expired', <Route path="/bazar/invite-expired" element={<BazarInviteExpired />} />)
    expect(screen.getByTestId('bazar-invite-expired'))
      .toHaveTextContent('This link has expired — ask the owner for a new one.')
  })
})

describe('Switch seller (phone bazar menu)', () => {
  test('menu → Switch seller → a seller → PIN → the existing PIN switch', async () => {
    setAccessToken(fakeJwt('owner'))
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    install(
      on('GET', '/api/v1/me', () => json(200, me('owner'))),
      on('GET', '/today', () => json(200, { business_day: TODAY, total: '0.00', sale_count: 0, by_method: {}, per_seller: [] })),
      on('GET', `/sellers/roster`, () => json(200, [{ id: 'u-mona', name: 'Mona', locked: false, lockedUntil: null }])),
      on('POST', '/auth/pin', () => json(200, { accessToken: fakeJwt('worker', 'u-mona') })),
    )
    renderAt(`/bazar/${EVENT}/home`, <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />)
    await userEvent.click(await screen.findByTestId('bazar-menu'))
    expect(screen.getByTestId('menu-sellers')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('menu-switch-seller'))
    await userEvent.click(await screen.findByRole('button', { name: 'Mona' }))
    await userEvent.type(screen.getByLabelText('PIN'), '4821')
    await userEvent.click(screen.getByRole('button', { name: 'Switch' }))
    await waitFor(() => expect(assign).toHaveBeenCalledWith(`/bazar/${EVENT}/home`))
    expect(calls.find(c => c.url.endsWith('/auth/pin'))?.body).toEqual({ userId: 'u-mona', pin: '4821' })
  })

  test('a worker\'s menu has no Sellers', async () => {
    setAccessToken(fakeJwt('worker'))
    install(
      on('GET', '/api/v1/me', () => json(200, me('worker'))),
      on('GET', '/today', () => json(200, { business_day: TODAY, total: '0.00', sale_count: 0, by_method: {}, per_seller: [] })),
    )
    renderAt(`/bazar/${EVENT}/home`, <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />)
    await userEvent.click(await screen.findByTestId('bazar-menu'))
    expect(screen.getByTestId('menu-switch-seller')).toBeInTheDocument()
    expect(screen.queryByTestId('menu-sellers')).not.toBeInTheDocument()
  })
})
