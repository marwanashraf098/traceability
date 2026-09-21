import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import DemoLanding from '../pages/DemoLanding'
import { RequireAuth, RootRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { getTenantIdFromToken } from '../api'
import { DEMO_TENANT_ID, DEMO_SESSION_MARKER } from '../demoConstants'

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
})

function fakeJwt(claims: Record<string, unknown>): string {
  const payload = btoa(JSON.stringify(claims))
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

function mockFetchDefault() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/station/roster')) return jsonResponse(200, [])
    if (url.includes('/auth/refresh')) return jsonResponse(401, {})
    return jsonResponse(404, {})
  }))
}

function renderGated(path = '/x') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path={path} element={<RequireAuth><div data-testid="scan-screen">SCAN</div></RequireAuth>} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

async function goToExitStep() {
  const exitLink = await screen.findByText('Exit station mode')
  fireEvent.click(exitLink)
  await screen.findByText('Sign in with an owner or manager account')
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  mockFetchDefault()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

// ISSUE 2 — getTenantIdFromToken() read the wrong JWT claim key ("tenantId"
// instead of the real "tenant" JwtService.issueAccessToken() writes), so it
// always returned null for every token, demo or real, and the demo-exit
// button never rendered. Direct unit coverage on the decode itself, plus the
// existing StationGate integration tests below re-exercise it end to end.
describe('getTenantIdFromToken() — claim-key fix (ISSUE 2)', () => {
  afterEach(() => clearAccessToken())

  test('demo token ("tenant" claim = DEMO_TENANT_ID) decodes to the demo tenant id', () => {
    setAccessToken(fakeJwt({ role: 'owner', tenant: DEMO_TENANT_ID }))
    expect(getTenantIdFromToken()).toBe(DEMO_TENANT_ID)
  })

  test('real token ("tenant" claim = some other tenant) decodes to that tenant id, non-null', () => {
    setAccessToken(fakeJwt({ role: 'owner', tenant: 'a-real-tenant-id' }))
    const result = getTenantIdFromToken()
    expect(result).not.toBeNull()
    expect(result).toBe('a-real-tenant-id')
  })

  test('no token in memory -> null', () => {
    expect(getTenantIdFromToken()).toBeNull()
  })
})

describe('StationGate ExitStep — demo-only no-password bypass (FIX 3a)', () => {
  test('demo tenant token: "Exit demo" button appears and exits with no password', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken(fakeJwt({ role: 'owner', tenant: DEMO_TENANT_ID }))

    renderGated()
    await goToExitStep()

    const demoExitButton = screen.getByRole('button', { name: 'Exit demo — no password needed' })
    await userEvent.click(demoExitButton)

    // exitStationMode() fired directly — no login() call, no email/password
    // needed — station mode is off and the gate's children render.
    expect(await screen.findByTestId('scan-screen')).toBeInTheDocument()
    expect(localStorage.getItem('stationMode')).toBeNull()
  })

  test('real tenant token: no demo-exit button, unchanged password re-auth flow', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken(fakeJwt({ role: 'owner', tenant: 'a-real-tenant-id' }))

    renderGated()
    await goToExitStep()

    expect(screen.queryByRole('button', { name: /exit demo/i })).not.toBeInTheDocument()
    // The real password-reauth form is still present and unchanged.
    expect(screen.getByRole('button', { name: 'Exit' })).toBeInTheDocument()
  })

  test('no tenant claim at all (e.g. a non-JWT placeholder token): no demo-exit button', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('not-a-real-jwt')

    renderGated()
    await goToExitStep()

    expect(screen.queryByRole('button', { name: /exit demo/i })).not.toBeInTheDocument()
  })
})

// -----------------------------------------------------------------------
// FIX 3(b) — stationMode is a device-level localStorage flag with no session
// scoping. A demo visitor who enters it must not find every SUBSEQUENT demo
// visit trapped in the gate once their session ends.
// -----------------------------------------------------------------------
function renderRequireAuthThenDemo() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/overview']}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
            <Route path="/demo" element={<DemoLanding />} />
            <Route
              path="/overview"
              element={<RequireAuth><div data-testid="overview-page">OVERVIEW</div></RequireAuth>}
            />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

describe('stationMode persistence fix — demo session end clears it (FIX 3b)', () => {
  test('expired demo session (in station mode) -> next /demo?expired=1 clears stationMode', async () => {
    localStorage.setItem('stationMode', 'true')
    sessionStorage.setItem(DEMO_SESSION_MARKER, '1')
    // No in-memory token, no valid sessionStorage demo token -> useAuthRefresh
    // falls through to the cookie-based refresh, which 401s (no cookie for demo).
    mockFetchDefault()

    renderRequireAuthThenDemo()

    // RequireAuth's unauthenticated branch fires BEFORE the stationMode check,
    // so this redirects straight to /demo?expired=1 rather than showing the gate.
    expect(await screen.findByText('Your demo session ended')).toBeInTheDocument()
    expect(localStorage.getItem('stationMode')).toBeNull()
  })

  test('a real worker/owner session never has stationMode cleared by demo logic', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken(fakeJwt({ role: 'owner', tenant: 'a-real-tenant-id' }))
    // No DEMO_SESSION_MARKER set at all — this is not a demo visitor.

    renderGated('/overview')

    // Real, valid in-memory token -> RequireAuth's stationMode gate fires (not
    // the unauthenticated branch) -> lands on the roster/gate, stationMode intact.
    await screen.findByText('Exit station mode')
    expect(localStorage.getItem('stationMode')).toBe('true')
  })
})
