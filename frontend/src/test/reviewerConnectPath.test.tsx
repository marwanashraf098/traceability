import { test, expect, describe, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import en from '../locales/en.json'
import { renderWithProviders } from './renderWithProviders'
import * as api from '../api'
import { setAccessToken, clearAccessToken } from '../auth'
import { RequireAuth } from '../App'
import Login from '../pages/Login'
import { returnPath } from '../pages/loginReturnPath'
import { StationProvider } from '../components/StationProvider'
import ConnectionsTab from '../pages/settings/ConnectionsTab'
import { NotLinked, notLinkedTracedUrl } from '../embedded/EmbeddedApp'

/**
 * Review mode S7, fix A — the reviewer's path from the embedded NotLinked card to connecting
 * THIS store: the card links to Settings → Connections with ?shop=<this store>; signing in comes
 * back to that page; the Shopify card prefills the connect form from ?shop= (a *.myshopify.com
 * domain only).
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getConnections: vi.fn(), listLocations: vi.fn(), login: vi.fn() }
})

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({ matches: false, media: query, onchange: null, addListener: () => {},
      removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }),
  })
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

// ── the embedded card's link ──────────────────────────────────────────────────

describe('NotLinked → Settings → Connections with this store', () => {
  test('a *.myshopify.com ?shop= is carried to /settings?tab=connections&shop=…', () => {
    expect(notLinkedTracedUrl('?shop=review-store.myshopify.com&host=abc'))
      .toBe('https://app.tracedtech.com/settings?tab=connections&shop=review-store.myshopify.com')
  })

  test('anything else is dropped — plain Connections page', () => {
    expect(notLinkedTracedUrl('')).toBe('https://app.tracedtech.com/settings?tab=connections')
    expect(notLinkedTracedUrl('?shop=evil.example.com')).toBe('https://app.tracedtech.com/settings?tab=connections')
    expect(notLinkedTracedUrl('?shop=a.myshopify.com.evil.io')).toBe('https://app.tracedtech.com/settings?tab=connections')
  })

  test('the card shows the reload hint', () => {
    render(<PolarisProvider i18n={polarisEn}><NotLinked /></PolarisProvider>)
    expect(screen.getByText('After connecting this store in Traced, reload this page.')).toBeInTheDocument()
  })
})

// ── signing in comes back ─────────────────────────────────────────────────────

describe('login returns to where RequireAuth sent it from', () => {
  test('returnPath: only an in-app path', () => {
    expect(returnPath({ from: '/settings?tab=connections&shop=a.myshopify.com' }))
      .toBe('/settings?tab=connections&shop=a.myshopify.com')
    expect(returnPath({ from: '//evil.example.com' })).toBeNull()
    expect(returnPath({ from: '/\\evil.example.com' })).toBeNull()
    expect(returnPath({ from: 'https://evil.example.com' })).toBeNull()
    expect(returnPath({ from: '/login' })).toBeNull()
    expect(returnPath(null)).toBeNull()
    expect(returnPath({ from: 42 })).toBeNull()
  })

  function Where() {
    const location = useLocation()
    return <div data-testid="where">{location.pathname + location.search}</div>
  }

  test('signed out at /settings?tab=connections&shop=… → /login → sign in → back at that URL', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve({
      ok: false, status: 401, statusText: '', headers: { get: () => 'application/json' }, json: async () => ({}),
      url,
    })))
    vi.mocked(api.login).mockResolvedValue({ accessToken: 'h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't' })) + '.s' } as never)
    const target = '/settings?tab=connections&shop=review-store.myshopify.com'
    const { container } = render(
      <StationProvider>
        <MemoryRouter initialEntries={[target]}>
          <I18nextProvider i18n={testI18n}>
            <Routes>
              <Route path="/settings" element={<RequireAuth><Where /></RequireAuth>} />
              <Route path="/login" element={<Login />} />
              <Route path="/overview" element={<div data-testid="where">/overview</div>} />
            </Routes>
          </I18nextProvider>
        </MemoryRouter>
      </StationProvider>,
    )
    const user = userEvent.setup()
    await screen.findByRole('button', { name: 'Sign in' })
    await user.type(container.querySelector('input[type="email"]')!, 'reviewer@tracedtech.com')
    await user.type(container.querySelector('input[type="password"]')!, 'pw-test-123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent(target))
  })
})

// ── the Shopify card prefill ──────────────────────────────────────────────────

function disconnected(): api.ConnectionsStatus {
  return {
    shopify: { connected: false, storeId: null, shopDomain: null, connectionType: null, status: 'disconnected',
      importStatus: null, lastSyncAt: null },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: false,
    shopifySetup: { appUrl: 'http://localhost:5173', redirectUrl: 'http://localhost:8080/auth/shopify/callback',
      webhookApiVersion: '2026-04', scopes: ['read_products', 'read_orders'] },
  } as api.ConnectionsStatus
}

describe('Shopify card prefill from ?shop=', () => {
  beforeEach(() => {
    setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't' })) + '.s')
    vi.mocked(api.getConnections).mockResolvedValue(disconnected())
    vi.mocked(api.listLocations).mockResolvedValue([])
  })

  test('a *.myshopify.com shop is prefilled into the connect form', async () => {
    renderWithProviders(<ConnectionsTab readOnly={false} />,
      { initialEntries: ['/settings?tab=connections&shop=Review-Store.myshopify.com'] })
    expect(await screen.findByDisplayValue('review-store.myshopify.com')).toBeInTheDocument()
  })

  test('anything else is ignored', async () => {
    renderWithProviders(<ConnectionsTab readOnly={false} />,
      { initialEntries: ['/settings?tab=connections&shop=evil.example.com'] })
    const input = await screen.findByLabelText('Shop domain')
    expect(input).toHaveValue('')
  })
})
