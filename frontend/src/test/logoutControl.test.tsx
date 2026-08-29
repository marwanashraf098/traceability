import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, fireEvent, render, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import Layout from '../components/Layout'
import { RequireAuth } from '../App'
import { StationProvider } from '../components/StationProvider'
import { getAccessToken, setAccessToken, clearAccessToken } from '../auth'

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

const ROSTER = [{ id: 'worker-1', name: 'Amina', locked: false, lockedUntil: null }]

/** Records every fetch call and serves the shell's background calls + station/PIN flow. */
function mockFetch(): { calls: string[] } {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    calls.push(url)
    if (url.endsWith('/me') || url.includes('/me?')) {
      return jsonResponse(200, { name: 'Test User', email: null, role: 'worker' })
    }
    if (url.includes('/exceptions/count')) return jsonResponse(200, {})
    if (url.includes('/onboarding/status')) return jsonResponse(200, { steps: [], allDone: true, dismissed: true })
    if (url.includes('/station/roster')) return jsonResponse(200, ROSTER)
    if (url.includes('/auth/pin')) return jsonResponse(200, { accessToken: fakeJwt('worker') })
    if (url.includes('/auth/logout')) return jsonResponse(204, {})
    return jsonResponse(404, {})
  }))
  return { calls }
}

/** Renders Layout directly (no RequireAuth) — isolates Layout's OWN branch logic
 * (role === 'worker' && stationMode) from RequireAuth's separate gating concern. */
function renderLayoutOnly() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/x']}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/x" element={<Layout><div data-testid="page">PAGE</div></Layout>} />
            <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

/** mockFetch's /me always resolves to { name: 'Test User' } -> avatar initials 'TU'.
 * Layout renders 'TU' twice — a static sidebar-footer avatar (no dropdown) and the
 * topbar user-menu trigger; the trigger is the second occurrence in DOM order. */
async function openMenu() {
  const avatars = await screen.findAllByText('TU')
  fireEvent.click(avatars[avatars.length - 1].closest('button')!)
  return screen.findByText(new RegExp(`^(${en.nav.switchLock}|${en.nav.logout})$`))
}

async function signInViaGate() {
  const tile = await screen.findByText('Amina')
  fireEvent.click(tile.closest('button')!)
  for (const d of ['1', '2', '3', '4']) {
    fireEvent.click(await screen.findByRole('button', { name: d }))
  }
}

beforeEach(() => {
  localStorage.clear()
  clearAccessToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('Logout control — worker-in-station "Switch / Lock" vs real logout', () => {
  test('(a) worker in stationMode: control clears currentWorker and RequireAuth re-renders the gate — no /auth/logout, cookie/stationMode untouched', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token') // pre-gate token; overwritten by the PIN sign-in below
    const { calls } = mockFetch()

    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/worker-home']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route
                path="/worker-home"
                element={<RequireAuth><Layout><div data-testid="page">PAGE</div></Layout></RequireAuth>}
              />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    // Sign in as the worker via the real gate flow, landing on the Layout-wrapped page.
    await signInViaGate()
    expect(await screen.findByTestId('page')).toBeInTheDocument()

    const control = await openMenu()
    expect(control.textContent).toBe(en.nav.switchLock)

    calls.length = 0 // only care about calls made by the control itself from here
    fireEvent.click(control)

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    expect(screen.queryByTestId('page')).not.toBeInTheDocument()

    // No server-side teardown call, and the in-memory access token (proxy for
    // "the device session wasn't torn down") is still present — only currentWorker reset.
    expect(calls.some(u => u.includes('/auth/logout'))).toBe(false)
    expect(getAccessToken()).not.toBeNull()
    expect(localStorage.getItem('stationMode')).toBe('true')
  })

  test('(b) owner: control does the real full logout, unchanged', async () => {
    setAccessToken(fakeJwt('owner'))
    const { calls } = mockFetch()

    renderLayoutOnly()

    expect(await screen.findByTestId('page')).toBeInTheDocument()
    const control = await openMenu()
    expect(control.textContent).toBe(en.nav.logout)

    fireEvent.click(control)

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(calls.some(u => u.includes('/auth/logout'))).toBe(true)
    expect(getAccessToken()).toBeNull()
  })

  test('(c) branches on role AND stationMode — worker NOT in stationMode still gets real logout', async () => {
    // stationMode left false (default)
    setAccessToken(fakeJwt('worker'))
    const { calls } = mockFetch()

    renderLayoutOnly()

    expect(await screen.findByTestId('page')).toBeInTheDocument()
    const control = await openMenu()
    expect(control.textContent).toBe(en.nav.logout)

    fireEvent.click(control)

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(calls.some(u => u.includes('/auth/logout'))).toBe(true)
  })

  test('(c) branches on role AND stationMode — owner in stationMode still gets real logout, not Switch / Lock', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken(fakeJwt('owner'))
    const { calls } = mockFetch()

    renderLayoutOnly()

    expect(await screen.findByTestId('page')).toBeInTheDocument()
    const control = await openMenu()
    expect(control.textContent).toBe(en.nav.logout)

    fireEvent.click(control)

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(calls.some(u => u.includes('/auth/logout'))).toBe(true)
  })
})
