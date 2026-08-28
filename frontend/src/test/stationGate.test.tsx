import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { useEffect } from 'react'
import { screen, fireEvent, render, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useNavigate } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import { RequireAuth } from '../App'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'

// Fresh i18next instance — mirrors renderWithProviders.tsx's pattern (does not
// share state with the src/i18n.ts singleton or its localStorage.getItem('lang') read).
const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
})

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

function mockFetchDefault() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/station/roster')) return jsonResponse(200, ROSTER)
    if (url.includes('/auth/pin')) return jsonResponse(200, { accessToken: 'worker-token' })
    if (url.endsWith('/me') || url.includes('/me?')) {
      return jsonResponse(200, { name: 'Amina', email: null, role: 'worker' })
    }
    return jsonResponse(404, {})
  }))
}

function renderGated(child: React.ReactNode, path = '/x') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path={path} element={<RequireAuth>{child}</RequireAuth>} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

/** Renders a fresh (StationGate visible) or already-signed-in worker via the gate's own flow. */
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
  mockFetchDefault()
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('Worker Station Gate — key behaviors', () => {
  test('(a) stationMode set + currentWorker null -> StationGate renders, children do not', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token')

    renderGated(<div data-testid="scan-screen">SCAN SCREEN</div>)

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    expect(screen.queryByTestId('scan-screen')).not.toBeInTheDocument()
  })

  test('(b) after signInWorker (via the gate), children render', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token')

    // Worker sign-in now navigates to /worker-home (Worker experience frontend
    // pass, FIX 3) — render the intercepted route AT /worker-home so this test's
    // "children render after sign-in" assertion still lands on a matched route.
    renderGated(<div data-testid="scan-screen">SCAN SCREEN</div>, '/worker-home')
    await signInViaGate()

    expect(await screen.findByTestId('scan-screen')).toBeInTheDocument()
    expect(screen.queryByText(/who's working/i)).not.toBeInTheDocument()
  })

  test('(c) a simulated reload (currentWorker reset, stationMode persisted) -> gate again, not children', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token')

    // Worker sign-in now navigates to /worker-home (FIX 3) — same rationale as (b).
    const { unmount } = renderGated(<div data-testid="scan-screen">SCAN SCREEN</div>, '/worker-home')
    await signInViaGate()
    expect(await screen.findByTestId('scan-screen')).toBeInTheDocument()

    // A real reload wipes the in-memory access token AND the StationProvider's
    // in-memory currentWorker, then RequireAuth's /auth/refresh restores a fresh
    // access token from the httpOnly cookie — but nothing restores currentWorker.
    // Unmounting (fresh StationProvider) + re-setting the token models exactly
    // that: stationMode survives (localStorage), currentWorker does not.
    unmount()
    setAccessToken('worker-token')
    renderGated(<div data-testid="scan-screen">SCAN SCREEN</div>, '/worker-home')

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    expect(screen.queryByTestId('scan-screen')).not.toBeInTheDocument()
  })

  test('(d) navigating between routes does not reset currentWorker (store is above the router)', async () => {
    localStorage.setItem('stationMode', 'true')
    setAccessToken('owner-token')

    function NavigateOnMount({ to }: { to: string }) {
      const navigate = useNavigate()
      useEffect(() => { navigate(to) }, [navigate, to])
      return null
    }

    render(
      <StationProvider>
        <MemoryRouter initialEntries={['/a']}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/a" element={<RequireAuth><NavigateOnMount to="/b" /></RequireAuth>} />
              <Route path="/b" element={<RequireAuth><div data-testid="route-b">ROUTE B</div></RequireAuth>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    )

    // Sign in on /a — children there (NavigateOnMount) only mount once signed in,
    // and immediately navigate to /b within the SAME StationProvider instance.
    await signInViaGate()

    // If currentWorker had been lost across the navigation, /b's RequireAuth would
    // render the gate again instead of route-b.
    expect(await screen.findByTestId('route-b')).toBeInTheDocument()
    expect(screen.queryByText(/who's working/i)).not.toBeInTheDocument()
  })
})
