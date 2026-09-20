import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, render, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import DemoLanding, { DEMO_SESSION_MARKER } from '../pages/DemoLanding'
import { RootRoute, RequireAuth } from '../App'
import { StationProvider } from '../components/StationProvider'
import { clearAccessToken, getAccessToken } from '../auth'

// Mirrors rootRoute.test.tsx's fresh-instance pattern — independent of the app's
// singleton i18n.ts (avoids the localStorage.getItem('lang') read at import time).
function makeI18n(lng: 'en' | 'ar') {
  const instance = i18next.createInstance()
  instance.use(initReactI18next).init({
    lng,
    fallbackLng: 'en',
    initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  return instance
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

function renderDemoRoute(initialEntry: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[initialEntry]}>
        <I18nextProvider i18n={makeI18n(lng)}>
          <Routes>
            <Route path="/demo" element={<DemoLanding />} />
            <Route path="/overview" element={<div data-testid="overview-page">OVERVIEW</div>} />
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
  document.documentElement.removeAttribute('dir')
  document.documentElement.removeAttribute('lang')
  cleanup()
})

async function fillAndEnableSubmit() {
  await userEvent.type(screen.getByLabelText(/your name|اسمك/i), 'Nadia')
  await userEvent.type(screen.getByLabelText(/^email$|البريد الإلكتروني/i), 'nadia@example.com')
  await userEvent.type(screen.getByLabelText(/phone|الهاتف/i), '01012345678')
}

describe('DemoLanding — /demo', () => {
  test('renders the modal (no ?expired param)', () => {
    renderDemoRoute('/demo')
    expect(screen.getByRole('heading', { name: 'Try the live demo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start demo' })).toBeInTheDocument()
  })

  test('?expired=1 renders the ended-state, not the modal', () => {
    renderDemoRoute('/demo?expired=1')
    expect(screen.getByText('Your demo session ended')).toBeInTheDocument()
    // Modal form fields must NOT be present until "Start demo" is clicked.
    expect(screen.queryByLabelText('Your name')).not.toBeInTheDocument()
  })

  test('?expired=1 clears a stale demo marker on mount', () => {
    sessionStorage.setItem(DEMO_SESSION_MARKER, '1')
    renderDemoRoute('/demo?expired=1')
    expect(sessionStorage.getItem(DEMO_SESSION_MARKER)).toBeNull()
  })

  test('consent gating: submit stays disabled until the checkbox is checked', async () => {
    renderDemoRoute('/demo')
    await fillAndEnableSubmit()
    const submit = screen.getByRole('button', { name: 'Start demo' })
    expect(submit).toBeDisabled()

    await userEvent.click(screen.getByRole('checkbox'))
    expect(submit).toBeEnabled()
  })

  test('submit success: setAccessToken called, demo marker set, navigates to redirect', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/public/demo/start')) {
        return jsonResponse(200, { accessToken: 'demo.jwt.token', redirect: '/overview' })
      }
      return jsonResponse(404, {})
    }))

    renderDemoRoute('/demo')
    await fillAndEnableSubmit()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Start demo' }))

    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(getAccessToken()).toBe('demo.jwt.token')
    expect(sessionStorage.getItem(DEMO_SESSION_MARKER)).toBe('1')
  })

  test('submit 429 DEMO_RATE_LIMITED: localized message shown, no navigation', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/public/demo/start')) {
        return jsonResponse(429, {
          code: 'DEMO_RATE_LIMITED',
          message_en: 'Too many demo requests right now — try again shortly.',
          message_ar: 'طلبات تجريبية كثيرة الآن — حاول مرة أخرى قريبًا.',
        })
      }
      return jsonResponse(404, {})
    }))

    renderDemoRoute('/demo')
    await fillAndEnableSubmit()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Start demo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many demo requests right now — try again shortly.'
    )
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })

  test('submit 400 DEMO_INPUT_INVALID: localized message shown, no navigation', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/public/demo/start')) {
        return jsonResponse(400, {
          code: 'DEMO_INPUT_INVALID',
          message_en: 'Please check your details and try again.',
          message_ar: 'يرجى مراجعة بياناتك والمحاولة مرة أخرى.',
        })
      }
      return jsonResponse(404, {})
    }))

    renderDemoRoute('/demo')
    await fillAndEnableSubmit()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'Start demo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Please check your details and try again.'
    )
    expect(screen.queryByTestId('overview-page')).not.toBeInTheDocument()
  })

  test('Arabic: modal renders translated labels and body dir is RTL', () => {
    document.documentElement.setAttribute('dir', 'rtl')
    document.documentElement.setAttribute('lang', 'ar')
    renderDemoRoute('/demo', 'ar')

    expect(document.documentElement.getAttribute('dir')).toBe('rtl')
    expect(screen.getByRole('heading', { name: 'جرّب النسخة التجريبية الحية' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ابدأ التجربة' })).toBeInTheDocument()
  })

  test('Arabic: 429 error body renders message_ar, not message_en', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/public/demo/start')) {
        return jsonResponse(429, {
          code: 'DEMO_RATE_LIMITED',
          message_en: 'Too many demo requests right now — try again shortly.',
          message_ar: 'طلبات تجريبية كثيرة الآن — حاول مرة أخرى قريبًا.',
        })
      }
      return jsonResponse(404, {})
    }))

    renderDemoRoute('/demo', 'ar')
    await userEvent.type(screen.getByLabelText('اسمك'), 'ندى')
    await userEvent.type(screen.getByLabelText('البريد الإلكتروني'), 'nada@example.com')
    await userEvent.type(screen.getByLabelText('الهاتف'), '01012345678')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: 'ابدأ التجربة' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'طلبات تجريبية كثيرة الآن — حاول مرة أخرى قريبًا.'
    )
  })
})

// -----------------------------------------------------------------------
// RequireAuth/RootRoute — lost demo session must redirect to /demo?expired=1,
// never the bare /login form. A real (non-demo) logged-out visitor still goes
// to /login. Extends rootRoute.test.tsx's own harness/mocking shape.
// -----------------------------------------------------------------------
function mockRefreshUnauthenticated() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/auth/refresh')) return jsonResponse(401, {})
    return jsonResponse(404, {})
  }))
}

function DemoPageProbe() {
  const location = useLocation()
  return <div data-testid="demo-page">DEMO {location.search}</div>
}

function renderRequireAuthAtOverview() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/overview']}>
        <I18nextProvider i18n={makeI18n('en')}>
          <Routes>
            <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
            <Route path="/demo" element={<DemoPageProbe />} />
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

function renderRootRoute() {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/']}>
        <I18nextProvider i18n={makeI18n('en')}>
          <Routes>
            <Route path="/" element={<RootRoute />} />
            <Route path="/login" element={<div data-testid="login-page">LOGIN</div>} />
            <Route path="/demo" element={<div data-testid="demo-page">DEMO</div>} />
          </Routes>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}

describe('RequireAuth / RootRoute — lost demo session redirect', () => {
  test('RequireAuth: demo marker set + no token -> /demo?expired=1, not /login', async () => {
    sessionStorage.setItem(DEMO_SESSION_MARKER, '1')
    mockRefreshUnauthenticated()

    renderRequireAuthAtOverview()

    const demoPage = await screen.findByTestId('demo-page')
    expect(demoPage.textContent).toContain('expired=1')
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
  })

  test('RequireAuth: no demo marker + no token -> /login', async () => {
    mockRefreshUnauthenticated()

    renderRequireAuthAtOverview()

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
    expect(screen.queryByTestId('demo-page')).not.toBeInTheDocument()
  })

  test('RootRoute: demo marker set + no token -> /demo?expired=1, not /login', async () => {
    sessionStorage.setItem(DEMO_SESSION_MARKER, '1')
    mockRefreshUnauthenticated()

    renderRootRoute()

    expect(await screen.findByTestId('demo-page')).toBeInTheDocument()
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
  })

  test('RootRoute: no demo marker + no token -> /login (unchanged real-user behavior)', async () => {
    mockRefreshUnauthenticated()

    renderRootRoute()

    expect(await screen.findByTestId('login-page')).toBeInTheDocument()
  })
})
