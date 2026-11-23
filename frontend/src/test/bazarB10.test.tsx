import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarInvite from '../pages/bazar/phone/BazarInvite'
import BazarJoin from '../pages/bazar/phone/BazarJoin'
import { SellersPanel } from '../pages/bazar/SellersPanel'
import { CloseDayForm } from '../pages/bazar/CloseDayForm'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, getAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { BazarError, type BazarDay } from '../bazar/api'
import { request } from '../api'
import { sessionSwitch } from '../session'

// Bazar B10 — the seller invite's landing page (open ≠ use), the signed-in confirm, refusal reasons, the session
// switch reload, human 403s, the "new link cancels the old one" note.

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TENANT = 't-a'
const INVITE = `/bazar/invite?t=${TENANT}#token=tok-1`

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
let calls: { url: string; method: string; body: unknown; auth: string | null }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    const h = (init?.headers ?? {}) as Record<string, string>
    calls.push({ url, method, body, auth: h.Authorization ?? null })
    for (const fn of hs) {
      const r = fn(url, init)
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

const inviteRoutes = (
  <>
    <Route path="/bazar/invite" element={<BazarInvite />} />
    <Route path="/bazar/join/:id" element={<><BazarJoin /><Where /></>} />
    <Route path="/bazar/:id/sell" element={<Where />} />
    <Route path="/" element={<Where />} />
  </>
)

const VALID = { status: 'valid', store_name: 'Smart Choices', event_name: 'Zamalek Winter Bazar',
                seller_name: 'Mona', seller_id: 'u-mona' }

const noSession = on('POST', '/auth/refresh', () => json(401, {}))
const ownerSession = [
  on('POST', '/auth/refresh', () => json(200, { accessToken: fakeJwt('owner', 'u-owner') })),
  on('GET', '/api/v1/me', () => json(200, { name: 'marwan', email: 'o@x', role: 'owner' })),
]

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  cleanup()
})

describe('the landing page — opening the link uses nothing up', () => {
  test('shows the store, bazar and seller; only "Start selling" redeems; then PIN → Sell', async () => {
    let pinSet = false
    install(
      noSession,
      on('POST', '/auth/seller-invite/check', () => json(200, VALID)),
      on('POST', '/auth/seller-invite/redeem', () => json(200, { access_token: fakeJwt('worker', 'u-mona'), event_id: EVENT })),
      on('POST', '/bazar-sellers/me/pin', () => { pinSet = true; return json(200, { pin_set: true }) }),
      on('GET', `/bazar-events/${EVENT}/sellers/me`, () => json(200, {
        event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', name: 'Mona',
        needs_pin: !pinSet, removed: false, can_sell: true,
      })),
    )
    renderAt(INVITE, inviteRoutes)
    expect(await screen.findByTestId('invite-event')).toHaveTextContent('Zamalek Winter Bazar')
    expect(screen.getByTestId('invite-store')).toHaveTextContent('Smart Choices')
    expect(screen.getByText("You'll sell as Mona.")).toBeInTheDocument()
    expect(calls.find(c => c.url.includes('/check'))?.body).toEqual({ t: TENANT, token: 'tok-1' })
    expect(calls.some(c => c.url.includes('/redeem'))).toBe(false)

    await userEvent.click(screen.getByRole('button', { name: 'Start selling' }))
    expect(await screen.findByText('Welcome, Mona')).toBeInTheDocument()
    expect(calls.filter(c => c.url.includes('/redeem'))).toHaveLength(1)
    expect(calls.find(c => c.url.includes('/redeem'))?.body).toEqual({ t: TENANT, token: 'tok-1' })
    expect(getAccessToken()).toBe(fakeJwt('worker', 'u-mona'))

    await userEvent.type(screen.getByTestId('pin-new'), '4821')
    await userEvent.type(screen.getByTestId('pin-again'), '4821')
    await userEvent.click(screen.getByRole('button', { name: 'Save PIN and start selling' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent(`/bazar/${EVENT}/sell`))
  })

  test('an owner signed in on this browser → asked first, never switched silently; Cancel keeps the owner', async () => {
    install(...ownerSession, on('POST', '/auth/seller-invite/check', () => json(200, VALID)))
    renderAt(INVITE, inviteRoutes)
    const box = await screen.findByTestId('invite-signed-in')
    expect(box).toHaveTextContent("You're signed in as marwan (Owner) on this phone.")
    expect(box).toHaveTextContent('Continue as Mona?')
    expect(screen.queryByRole('button', { name: 'Start selling' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/))
    expect(calls.some(c => c.url.includes('/redeem'))).toBe(false)
    expect(getAccessToken()).toBe(fakeJwt('owner', 'u-owner'))
  })

  test('Continue redeems with the owner\'s token so the server ends that session', async () => {
    install(...ownerSession,
      on('POST', '/auth/seller-invite/check', () => json(200, VALID)),
      on('POST', '/auth/seller-invite/redeem', () => json(200, { access_token: fakeJwt('worker', 'u-mona'), event_id: EVENT })),
      on('GET', `/bazar-events/${EVENT}/sellers/me`, () => json(200, {
        event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', name: 'Mona',
        needs_pin: true, removed: false, can_sell: true })))
    renderAt(INVITE, inviteRoutes)
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))
    expect(await screen.findByText('Welcome, Mona')).toBeInTheDocument()
    expect(calls.find(c => c.url.includes('/redeem'))?.auth).toBe(`Bearer ${fakeJwt('owner', 'u-owner')}`)
  })

  test('the same seller already signed in → just "Start selling"', async () => {
    install(
      on('POST', '/auth/refresh', () => json(200, { accessToken: fakeJwt('worker', 'u-mona') })),
      on('GET', '/api/v1/me', () => json(200, { name: 'Mona', email: null, role: 'worker' })),
      on('POST', '/auth/seller-invite/check', () => json(200, VALID)))
    renderAt(INVITE, inviteRoutes)
    expect(await screen.findByRole('button', { name: 'Start selling' })).toBeInTheDocument()
    expect(screen.queryByTestId('invite-signed-in')).not.toBeInTheDocument()
  })

  test.each([
    ['used', 'This link was already used. Ask the owner to send you a new one.'],
    ['expired', 'This link has expired — ask the owner for a new one.'],
    ['replaced', 'A newer link was sent to you, so this one no longer works.'],
    ['removed', "You're no longer a seller at this bazar."],
    ['unknown', "This link doesn't work. Ask the owner to send you a new one."],
  ])('check says %s → the page says why', async (status, text) => {
    install(noSession, on('POST', '/auth/seller-invite/check', () => json(200, { status })))
    renderAt(INVITE, inviteRoutes)
    const page = await screen.findByTestId('bazar-invite-expired')
    expect(page).toHaveTextContent(text)
    expect(page.dataset.reason).toBe(status)
    expect(calls.some(c => c.url.includes('/redeem'))).toBe(false)
  })

  test('redeem refused (used in between) → the reason page', async () => {
    install(noSession,
      on('POST', '/auth/seller-invite/check', () => json(200, VALID)),
      on('POST', '/auth/seller-invite/redeem', () => json(409, { code: 'BAZAR_INVITE_REFUSED', message_en: 'x',
        details: { reason: 'used' } })))
    renderAt(INVITE, inviteRoutes)
    await userEvent.click(await screen.findByRole('button', { name: 'Start selling' }))
    expect(await screen.findByTestId('bazar-invite-expired')).toHaveTextContent('This link was already used.')
    expect(getAccessToken()).toBeNull()
  })

  test('no #token (an app dropped the fragment) → "incomplete", the refusal is logged, nothing is redeemed', async () => {
    install(noSession, on('POST', '/auth/seller-invite/check', () => json(200, { status: 'missing_token' })))
    renderAt(`/bazar/invite?t=${TENANT}`, inviteRoutes)
    const page = await screen.findByTestId('bazar-invite-expired')
    expect(page).toHaveTextContent(
      'This link is incomplete. Open it again from the message, or ask the owner for a new one.')
    expect(page.dataset.reason).toBe('missing_token')
    await waitFor(() => expect(calls.some(c => c.url.includes('/seller-invite/check'))).toBe(true))
    expect(calls.find(c => c.url.includes('/seller-invite/check'))!.body).toEqual({ t: TENANT, token: '' })
    expect(calls.some(c => c.url.includes('/redeem'))).toBe(false)
  })
})

describe('a session that changed hands under an open tab', () => {
  test('a refresh that returns ANOTHER user → reload as them, the call is not retried', async () => {
    const reload = vi.spyOn(sessionSwitch, 'reload').mockImplementation(() => {})
    setAccessToken(fakeJwt('owner', 'u-owner'))
    let attempts = 0
    install(
      on('GET', '/api/v1/bazar-thing', () => { attempts++; return json(401, {}) }),
      on('POST', '/auth/refresh', () => json(200, { accessToken: fakeJwt('worker', 'u-mona') })),
    )
    await expect(request('/bazar-thing')).rejects.toThrow('Unauthenticated')
    expect(reload).toHaveBeenCalledTimes(1)
    expect(attempts).toBe(1)
  })

  test('a refresh for the same user → retried as before, no reload', async () => {
    const reload = vi.spyOn(sessionSwitch, 'reload').mockImplementation(() => {})
    setAccessToken(fakeJwt('owner', 'u-owner'))
    let attempts = 0
    install(
      on('GET', '/api/v1/bazar-thing', () => { attempts++; return attempts === 1 ? json(401, {}) : json(200, { ok: 1 }) }),
      on('POST', '/auth/refresh', () => json(200, { accessToken: fakeJwt('owner', 'u-owner') })),
    )
    await expect(request('/bazar-thing')).resolves.toEqual({ ok: 1 })
    expect(reload).not.toHaveBeenCalled()
    expect(attempts).toBe(2)
  })
})

describe('403s read like words', () => {
  test('a bare 403 never shows its code', () => {
    const e = new BazarError(403, { code: 'HTTP_403' })
    expect(e.message_('en')).toBe("You can't do this — ask an owner or manager.")
    expect(e.message_('ar')).not.toContain('HTTP_')
  })

  const DAY: BazarDay = {
    business_day: '2026-11-22', is_today: true, status: 'open', close_count: 0, close: null,
    money: { cash: '0', card: '0', instapay: '0', total: '0', sales: 0 },
    opening_float_default: { amount: '0', source: 'none' },
  } as unknown as BazarDay

  test('Close day is not offered to a worker', () => {
    setAccessToken(fakeJwt('worker'))
    install()
    renderAt('/', <Route path="/" element={<CloseDayForm eventId={EVENT} day={DAY} methods={['cash']} onClosed={() => {}} />} />)
    expect(screen.getByTestId('close-managers-only')).toHaveTextContent('Only owners and managers can close the day.')
    expect(screen.queryByTestId('close-section-cash')).not.toBeInTheDocument()
  })

  test('a 403 from the preview → the sentence, not "You counted 0"', async () => {
    setAccessToken(fakeJwt('owner'))
    install(on('GET', '/close-preview', () => json(403, { error: 'ACCESS_DENIED', path: '/x' })))
    renderAt('/', <Route path="/" element={<CloseDayForm eventId={EVENT} day={DAY} methods={['cash']} onClosed={() => {}} />} />)
    await userEvent.type(screen.getByTestId('close-section-cash').querySelector('input')!, '100')
    expect(await screen.findByTestId('close-managers-only', {}, { timeout: 2000 }))
      .toHaveTextContent('Only owners and managers can close the day.')
    expect(document.body).not.toHaveTextContent('HTTP_403')
  })
})

describe('Sellers panel — a new link cancels the old one', () => {
  test('the note shows with the new link and on a seller who was sent one', async () => {
    setAccessToken(fakeJwt('owner'))
    install(
      on('GET', `/bazar-events/${EVENT}/sellers`, () => json(200, {
        event_name: 'Zamalek Winter Bazar', event_status: 'live', workers_allowed: true,
        sellers: [{ user_id: 'u-mona', name: 'Mona', phone: null, has_pin: false, added_at: '2026-11-19T10:00:00Z',
                    last_invited_at: '2026-11-22T10:00:00Z' }],
      })),
      on('POST', '/sellers/u-mona/link', () => json(200, {
        link: 'https://app.tracedtech.com/auth/seller-invite?t=t-a#token=new', expires_at: '2026-11-23T10:00:00Z',
        event_name: 'Zamalek Winter Bazar' })),
    )
    renderAt('/', <Route path="/" element={<SellersPanel eventId={EVENT} />} />)
    expect(await screen.findByTestId('seller-row-replaces'))
      .toHaveTextContent('Sending a new link cancels the one you sent before')
    await userEvent.click(screen.getByRole('button', { name: 'Get a new link' }))
    expect(await screen.findByTestId('seller-link-replaces')).toHaveTextContent('only the newest link works')
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
    expect(screen.getByTestId('seller-whatsapp')).toBeInTheDocument()
  })
})
