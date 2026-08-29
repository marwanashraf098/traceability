import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, fireEvent, render, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import Layout from '../components/Layout'
import Login from '../pages/Login'
import { RequireAuth, OwnerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { stubFetchWithShellDefaults } from './mockShellFetch'

// Fresh i18next instance — mirrors renderWithProviders.tsx / stationGate.test.tsx.
const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
})

/** Minimal fake JWT — getRoleFromToken() only reads the middle segment's `role` claim. */
function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  const payload = btoa(JSON.stringify({ role }))
  return `h.${payload}.s`
}

function jsonResponse(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  })
}

function renderIn18n(ui: React.ReactNode, initialEntries: string[]) {
  return render(
    // Layout now reads useStation() (worker "Switch / Lock" control) — every
    // render of it needs a StationProvider ancestor, same as App.tsx's real nesting.
    <StationProvider>
      <MemoryRouter initialEntries={initialEntries}>
        <I18nextProvider i18n={testI18n}>{ui}</I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

beforeEach(() => {
  localStorage.clear()
  clearAccessToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

// ── (a) worker sidebar shows only the 4 worker items ────────────────────────

describe('(a) Layout sidebar — worker role filter', () => {
  test('worker sees Home/Pick & Pack/Returns/Pickups only, none of the hidden 7', async () => {
    setAccessToken(fakeJwt('worker'))
    stubFetchWithShellDefaults(() => jsonResponse(404, {}), {
      me: { name: 'Sara Worker', email: null, role: 'worker' },
    })

    renderIn18n(<Layout><div /></Layout>, ['/fulfill'])

    expect(await screen.findByText(en.nav.home)).toBeInTheDocument()
    expect(screen.getByText(en.nav.fulfill)).toBeInTheDocument()
    expect(screen.getByText(en.nav.returns)).toBeInTheDocument()
    expect(screen.getByText(en.nav.pickups)).toBeInTheDocument()

    for (const hidden of [
      en.nav.overview, en.nav.orders, en.nav.catalog, en.nav.receiving,
      en.nav.stocktake, en.nav.transfers, en.nav.exceptions, en.nav.settings,
    ]) {
      expect(screen.queryByText(hidden)).not.toBeInTheDocument()
    }
  })
})

// ── (e) owner/manager nav unchanged — regression guard ──────────────────────

describe('(e) Layout sidebar — owner/manager regression guard', () => {
  test('owner still sees the full unchanged 10-item nav + Settings, no Home item', async () => {
    setAccessToken(fakeJwt('owner'))
    stubFetchWithShellDefaults(() => jsonResponse(404, {}), {
      me: { name: 'Owner Person', email: null, role: 'owner' },
    })

    renderIn18n(<Layout><div /></Layout>, ['/overview'])

    for (const visible of [
      en.nav.overview, en.nav.orders, en.nav.catalog, en.nav.receiving,
      en.nav.stocktake, en.nav.fulfill, en.nav.pickups, en.nav.transfers,
      en.nav.returns, en.nav.exceptions, en.nav.settings,
    ]) {
      expect(await screen.findByText(visible)).toBeInTheDocument()
    }
    expect(screen.queryByText(en.nav.home)).not.toBeInTheDocument()
  })
})

// ── (b) worker sign-in via the station gate navigates to /worker-home ───────

const ROSTER = [{ id: 'worker-1', name: 'Amina', locked: false, lockedUntil: null }]

function mockGateFetch() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/station/roster')) return jsonResponse(200, ROSTER)
    if (url.includes('/auth/pin')) return jsonResponse(200, { accessToken: fakeJwt('worker') })
    if (url.endsWith('/me') || url.includes('/me?')) {
      return jsonResponse(200, { name: 'Amina', email: null, role: 'worker' })
    }
    return jsonResponse(404, {})
  }))
}

async function signInViaGate() {
  const tile = await screen.findByText('Amina')
  fireEvent.click(tile.closest('button')!)
  for (const d of ['1', '2', '3', '4']) {
    fireEvent.click(await screen.findByRole('button', { name: d }))
  }
}

describe('(b) StationGate — worker sign-in lands on /worker-home', () => {
  test('successful PIN sign-in navigates from the intercepted route to /worker-home', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token')
    mockGateFetch()

    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/fulfill']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/fulfill" element={<RequireAuth><div data-testid="fulfill-page">FULFILL</div></RequireAuth>} />
              <Route path="/worker-home" element={<RequireAuth><div data-testid="worker-home-page">WORKER HOME</div></RequireAuth>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    await signInViaGate()

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.queryByTestId('fulfill-page')).not.toBeInTheDocument()
  })
})

// ── (c) worker hitting an owner-only route is redirected to /worker-home ────

describe('(c) OwnerOnlyRoute — worker redirected off owner-only routes', () => {
  test('a worker with a valid token hitting /overview lands on /worker-home, not the owner screen', async () => {
    setAccessToken(fakeJwt('worker'))
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(404, {})))

    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/overview']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route
                path="/overview"
                element={<RequireAuth><OwnerOnlyRoute><div data-testid="overview-page">OVERVIEW</div></OwnerOnlyRoute></RequireAuth>}
              />
              <Route path="/worker-home" element={<div data-testid="worker-home-page">WORKER HOME</div>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })

  test('an owner with a valid token hitting /overview renders it normally (unaffected)', async () => {
    setAccessToken(fakeJwt('owner'))
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(404, {})))

    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/overview']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route
                path="/overview"
                element={<RequireAuth><OwnerOnlyRoute><div data-testid="overview-page">OVERVIEW</div></OwnerOnlyRoute></RequireAuth>}
              />
              <Route path="/worker-home" element={<div data-testid="worker-home-page">WORKER HOME</div>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(screen.queryByTestId('worker-home-page')).not.toBeInTheDocument()
  })
})

// ── (d) owner login with stationMode set lands in the app, not the gate ─────

describe('(d) Login — exitStationMode clears a stale gate flag on real login', () => {
  test('owner email+password login clears stationMode and lands on /overview, not the gate', async () => {
    localStorage.setItem('stationMode', 'true')
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/auth/login')) return jsonResponse(200, { accessToken: fakeJwt('owner') })
      return jsonResponse(404, {})
    }))

    const { container } = render(
      <StationProvider>
        <MemoryRouter initialEntries={['/login']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route path="/overview" element={<div data-testid="overview-page">OVERVIEW</div>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    const user = userEvent.setup()
    const emailInput = container.querySelector('input[type="email"]') as HTMLInputElement
    const passwordInput = container.querySelector('input[type="password"]') as HTMLInputElement
    await user.type(emailInput, 'owner@example.com')
    await user.type(passwordInput, 'correct-password')
    await user.click(screen.getByRole('button', { name: en.login.submit }))

    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem('stationMode')).toBeNull())
  })
})
