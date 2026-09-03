import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import { RootRoute, RequireAuth } from '../App'
import { StationProvider } from '../components/StationProvider'
import { clearAccessToken } from '../auth'

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

function mockRefresh(result: { ok: true; role: 'owner' | 'manager' | 'worker' } | { ok: false }) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/auth/refresh')) {
      if (!result.ok) return jsonResponse(401, {})
      return jsonResponse(200, { accessToken: fakeJwt(result.role) })
    }
    // StationGate's roster fetch, when reached.
    if (url.includes('/station/roster')) return jsonResponse(200, [])
    return jsonResponse(404, {})
  }))
}

/** Same tree shape as App.tsx's real routing: RootRoute at "/", RequireAuth-wrapped
 * destinations at /overview and /worker-home so a stationMode redirect actually
 * exercises the gate, exactly like production. */
function renderAtRoot() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/']}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/" element={<RootRoute />} />
            <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
            <Route
              path="/overview"
              element={<RequireAuth><div data-testid="overview-page">OVERVIEW</div></RequireAuth>}
            />
            <Route
              path="/worker-home"
              element={<RequireAuth><div data-testid="worker-home-page">WORKER HOME</div></RequireAuth>}
            />
          </Routes>
        </I18nextProvider>
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

describe('Root route ("/") — logged-in forwarding', () => {
  test('(a) logged out at "/" -> redirected to /login', async () => {
    mockRefresh({ ok: false })

    renderAtRoot()

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })

  test('(b) logged-in owner at "/" -> redirected to /overview, not /login', async () => {
    mockRefresh({ ok: true, role: 'owner' })

    renderAtRoot()

    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
  })

  test('(c) logged-in worker at "/" -> redirected to /worker-home', async () => {
    mockRefresh({ ok: true, role: 'worker' })

    renderAtRoot()

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
  })

  test('(d) stationMode device at "/" -> lands on the gate, not /login and not straight into the app', async () => {
    localStorage.setItem('stationMode', 'true')
    mockRefresh({ ok: true, role: 'owner' })

    renderAtRoot()

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })

  test('(e) no /login content-flash before redirect — loading state precedes the redirect', async () => {
    mockRefresh({ ok: true, role: 'owner' })

    renderAtRoot()

    // Synchronously after the first render (before the /auth/refresh promise
    // resolves), RootRoute must be in its loading/spinner state — never /login.
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()

    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
  })
})
