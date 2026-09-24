import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// A failed Shopify OAuth install/callback redirects to
// /settings?tab=connections&shopify_error=<CODE> (ShopifyOAuthController.BrowserError).
// The Shopify card must show a plain message for the code, then strip the param.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(),
    listLocations:  vi.fn(),
  }
})

function disconnectedFixture(): api.ConnectionsStatus {
  return {
    shopify: {
      connected: false, storeId: null, shopDomain: null,
      connectionType: null, status: 'disconnected',
      importStatus: null, lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false,
    oauthAvailable: false,
    shopifySetup: {
      appUrl: 'http://localhost:5173',
      redirectUrl: 'http://localhost:8080/auth/shopify/callback',
      webhookApiVersion: '2026-04',
      scopes: ['read_products', 'read_orders'],
    },
  }
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location-search">{location.search}</div>
}

function renderAt(search: string) {
  return renderWithProviders(
    <>
      <ConnectionsTab readOnly={false} />
      <LocationProbe />
    </>,
    { initialEntries: [`/settings${search}`] },
  )
}

const GENERIC = "Couldn't connect your Shopify store. Please try again."

const CASES: Array<[string, string]> = [
  ['SHOP_LINKED_ELSEWHERE',
    'This Shopify store is already connected to a different Traced account. Sign in to that account to manage it, or uninstall Traced from the store first.'],
  ['SHOP_MISMATCH', 'This Traced account is already connected to a different Shopify store.'],
  ['INSTALL_EXPIRED', 'The Shopify connection request expired. Please try connecting again.'],
  ['INSTALL_FAILED', GENERIC],
]

describe('ShopifyConnectionCard — shopify_error from the OAuth redirect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getConnections).mockResolvedValue(disconnectedFixture())
    vi.mocked(api.listLocations).mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test.each(CASES)('%s → its message is shown, then the param is stripped (tab kept)', async (code, message) => {
    renderAt(`?tab=connections&shopify_error=${code}`)

    const alert = await screen.findByText(message)
    expect(alert.closest('[role="alert"]')).not.toBeNull()

    await waitFor(() =>
      expect(screen.getByTestId('location-search').textContent).toBe('?tab=connections'))
    // Still shown after the strip — captured once, not re-derived from the URL.
    expect(screen.getByText(message)).toBeTruthy()
  })

  test('unknown code → generic message, never the raw value, never blank', async () => {
    renderAt('?tab=connections&shopify_error=%3Cb%3Eowned+by+tenant+123%3C%2Fb%3E')

    expect(await screen.findByText(GENERIC)).toBeTruthy()
    expect(screen.queryByText(/owned by tenant/)).toBeNull()
    await waitFor(() =>
      expect(screen.getByTestId('location-search').textContent).toBe('?tab=connections'))
  })

  test('empty code → generic message', async () => {
    renderAt('?tab=connections&shopify_error=')

    expect(await screen.findByText(GENERIC)).toBeTruthy()
    await waitFor(() =>
      expect(screen.getByTestId('location-search').textContent).toBe('?tab=connections'))
  })

  test('dismiss hides the message; it does not come back', async () => {
    const user = userEvent.setup()
    renderAt('?tab=connections&shopify_error=SHOP_LINKED_ELSEWHERE')

    await screen.findByText(/already connected to a different Traced account/)
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))

    expect(screen.queryByText(/already connected to a different Traced account/)).toBeNull()
    expect(screen.getByTestId('location-search').textContent).toBe('?tab=connections')
  })

  test('no param → no message', async () => {
    renderAt('?tab=connections')

    // Card rendered (connect form visible) with no callback alert.
    await screen.findByPlaceholderText('your-store.myshopify.com')
    expect(screen.queryByText(GENERIC)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull()
  })
})
