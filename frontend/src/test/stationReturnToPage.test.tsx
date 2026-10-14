import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import Layout from '../components/Layout'
import GatherList from '../pages/GatherList'
import { RequireAuth, OwnerOnlyRoute, RootRoute, WorkerOnlyRoute } from '../App'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { stubFetchWithShellDefaults } from './mockShellFetch'

/**
 * Station-mode new-tab / reload bug (Nuvira, 2026-10-14). A station tablet's signed-in worker lives
 * in memory per tab, so a reload or a new tab shows the PIN gate on the page it was opened for.
 * After the PIN the worker must come back to THAT page — the route guards decide (an owner-only
 * page still sends a worker to /worker-home). A deliberate hand-over ("Switch / Lock") still lands
 * the next worker on /worker-home.
 */

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
})

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(body: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

const ROSTER = [
  { id: 'worker-1', name: 'Amina', locked: false, lockedUntil: null },
  { id: 'worker-2', name: 'Omar', locked: false, lockedUntil: null },
]

let fetchFn: ReturnType<typeof vi.fn>

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function renderAt(path: string) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <Routes>
            <Route path="/" element={<RootRoute />} />
            <Route
              path="/overview"
              element={<RequireAuth><OwnerOnlyRoute><div data-testid="overview-page">OVERVIEW</div></OwnerOnlyRoute></RequireAuth>}
            />
            <Route path="/fulfill" element={<RequireAuth><div data-testid="fulfill-page">FULFILL</div></RequireAuth>} />
            <Route path="/fulfill/gather" element={<RequireAuth><GatherList /></RequireAuth>} />
            <Route
              path="/returns"
              element={<RequireAuth><Layout><div data-testid="returns-page">RETURNS</div></Layout></RequireAuth>}
            />
            <Route
              path="/worker-home"
              element={<RequireAuth><WorkerOnlyRoute><div data-testid="worker-home-page">WORKER HOME</div></WorkerOnlyRoute></RequireAuth>}
            />
          </Routes>
          <LocationProbe />
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

async function pinIn(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: new RegExp(name) }))
  for (const d of ['1', '2', '3', '4']) {
    await user.click(await screen.findByRole('button', { name: d }))
  }
}

beforeEach(() => {
  localStorage.clear()
  clearAccessToken()
  // A station tablet: stationMode persisted, the refresh-restored token in memory, no worker yet.
  localStorage.setItem('stationMode', 'true')
  setAccessToken(fakeJwt('owner'))
  fetchFn = vi.fn((url: string) => {
    if (url.includes('/station/roster')) return json(ROSTER)
    if (url.includes('/auth/pin')) return json({ accessToken: fakeJwt('worker') })
    if (url.includes('/fulfill/gather')) {
      return json({ generatedAt: new Date().toISOString(), orderCount: 0, rows: [] })
    }
    return json({}, 404)
  })
  stubFetchWithShellDefaults(fetchFn, { me: { name: 'Test User', email: null, role: 'worker' } })
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('Station gate — the worker returns to the page the gate interrupted', () => {
  test('new tab on /fulfill/gather?batch=X -> gate -> PIN -> the batch pick list, not Worker Home', async () => {
    const user = userEvent.setup()
    renderAt('/fulfill/gather?batch=batch-X')

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    await pinIn(user, 'Amina')

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/fulfill/gather?batch=batch-X'))
    expect(screen.queryByTestId('worker-home-page')).not.toBeInTheDocument()
    expect(screen.queryByText(/who's working/i)).not.toBeInTheDocument()
    // GatherList itself rendered and asked for exactly that batch.
    await waitFor(() => expect(fetchFn.mock.calls.some(([u]) =>
      String(u).includes('/fulfill/gather?batchId=batch-X'))).toBe(true))
  })

  test('gated on an owner-only page (/overview) -> PIN -> the worker ends on /worker-home', async () => {
    const user = userEvent.setup()
    renderAt('/overview')

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    await pinIn(user, 'Amina')

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/worker-home$/)
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })

  test('gated open from the bare domain "/" -> PIN -> /worker-home', async () => {
    const user = userEvent.setup()
    renderAt('/')

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    await pinIn(user, 'Amina')

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/worker-home$/)
  })

  test('hand-over: worker on /returns taps Switch / Lock -> gate -> next worker\'s PIN -> /worker-home', async () => {
    const user = userEvent.setup()
    renderAt('/returns')

    await pinIn(user, 'Amina')
    expect(await screen.findByTestId('returns-page')).toBeInTheDocument()

    // Layout's user menu (topbar trigger is the last avatar) -> "Switch / Lock".
    const avatars = await screen.findAllByText('TU')
    await user.click(avatars[avatars.length - 1].closest('button')!)
    await user.click(await screen.findByText(en.nav.switchLock))

    expect(await screen.findByText(/who's working/i)).toBeInTheDocument()
    await pinIn(user, 'Omar')

    expect(await screen.findByTestId('worker-home-page')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/worker-home$/)
    expect(screen.queryByTestId('returns-page')).not.toBeInTheDocument()
  })
})
