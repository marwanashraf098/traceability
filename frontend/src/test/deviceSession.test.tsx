import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import { RequireAuth } from '../App'
import Login from '../pages/Login'
import UsersTab from '../pages/settings/UsersTab'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { logoutThisDevice } from '../api'

// Build A (2026-10-05): a station tablet must not drop out of station mode when it bounces to
// /login, the login page tries a silent refresh first, the reload refresh is shared, and the
// everywhere-logout is an explicit Settings action.

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

function jsonResponse(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  })
}

function fakeJwt(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', sub: 'u' })) + '.s'
}

const ROSTER = [{ id: 'worker-1', name: 'Amina', locked: false, lockedUntil: null }]

/** fetch stub: /auth/refresh answers `refreshStatus`; every call is recorded. */
function stubFetch(refreshStatus: number) {
  const calls: string[] = []
  const fn = vi.fn((url: string) => {
    calls.push(url)
    if (url.includes('/auth/refresh')) {
      return refreshStatus === 200 ? jsonResponse(200, { accessToken: fakeJwt('worker') }) : jsonResponse(401, {})
    }
    if (url.includes('/auth/login')) return jsonResponse(200, { accessToken: fakeJwt('owner') })
    if (url.includes('/auth/logout')) return jsonResponse(204, {})
    if (url.includes('/station/roster')) return jsonResponse(200, ROSTER)
    if (url.includes('/scan-pairings') || url.includes('/station/pairing')) return jsonResponse(204, {})
    if (url.endsWith('/users')) return jsonResponse(200, [])
    return jsonResponse(404, {})
  })
  vi.stubGlobal('fetch', fn)
  return calls
}

function renderLoginRoutes() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/login']}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/overview" element={<RequireAuth><div data-testid="overview-page">OVERVIEW</div></RequireAuth>} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('/login silent refresh', () => {
  test('a still-valid refresh cookie goes straight back into the app — no password asked', async () => {
    const calls = stubFetch(200)
    renderLoginRoutes()
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(calls.filter(u => u.includes('/auth/login'))).toHaveLength(0)
  })

  test('no valid cookie (a real logout) → the form stays', async () => {
    const calls = stubFetch(401)
    const { container } = renderLoginRoutes()
    await waitFor(() => expect(calls.some(u => u.includes('/auth/refresh'))).toBe(true))
    expect(container.querySelector('input[type="email"]')).toBeTruthy()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })
})

describe('a station tablet stays in station mode across a /login bounce', () => {
  test('silent refresh succeeds → back on the PIN gate, station mode kept', async () => {
    localStorage.setItem('stationMode', 'true')
    stubFetch(200)
    renderLoginRoutes()
    expect(await screen.findByText(en.station.roster.title)).toBeInTheDocument()
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
    expect(localStorage.getItem('stationMode')).toBe('true')
  })

  test('an owner signs in with the form → still a station (the gate), not the app', async () => {
    localStorage.setItem('stationMode', 'true')
    stubFetch(401)
    const { container } = renderLoginRoutes()
    const user = userEvent.setup()
    await user.type(container.querySelector('input[type="email"]')!, 'owner@example.com')
    await user.type(container.querySelector('input[type="password"]')!, 'correct-password')
    await user.click(screen.getByRole('button', { name: en.login.submit }))
    expect(await screen.findByText(en.station.roster.title)).toBeInTheDocument()
    expect(localStorage.getItem('stationMode')).toBe('true')
  })
})

describe('reload refresh is shared', () => {
  test('two auth-gated views mounting together make ONE /auth/refresh call', async () => {
    const calls = stubFetch(200)
    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/x']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/x" element={<>
                <RequireAuth><div data-testid="a">A</div></RequireAuth>
                <RequireAuth><div data-testid="b">B</div></RequireAuth>
              </>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )
    expect(await screen.findByTestId('a')).toBeInTheDocument()
    expect(await screen.findByTestId('b')).toBeInTheDocument()
    expect(calls.filter(u => u.includes('/auth/refresh'))).toHaveLength(1)
  })
})

describe('logout scope', () => {
  test('"Log out" sends this device\'s id to /auth/logout (this device only)', async () => {
    const calls = stubFetch(200)
    setAccessToken(fakeJwt('owner'))
    await logoutThisDevice('tabletAAAAAAAAAAAAAA')
    expect(calls).toContain('/api/v1/auth/logout?deviceId=tabletAAAAAAAAAAAAAA')
  })

  test('Settings "Log out of all devices" → confirm → /auth/logout-all, then /login', async () => {
    const calls = stubFetch(200)
    setAccessToken(fakeJwt('owner'))
    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/settings']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/settings" element={<UsersTab />} />
              <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )
    fireEvent.click(await screen.findByRole('button', { name: en.settings.signOutAll.button }))
    expect(calls.some(u => u.includes('/auth/logout-all'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: en.settings.signOutAll.confirm }))
    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(calls.filter(u => u.includes('/auth/logout-all'))).toHaveLength(1)
  })
})
