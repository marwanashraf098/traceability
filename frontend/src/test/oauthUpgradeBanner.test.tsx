import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// Build C — the official-app upgrade banner and the recovery card's neutral copy.
//   b1 a connected custom-app store with oauthAvailable (the server's per-store rollout answer) sees the banner
//   b2 oauthAvailable false (flag off, or the shop not in SHOPIFY_OAUTH_UPGRADE_SHOPS) → no banner
//   b3 a disconnected store's OAuth card reads "Connect with Shopify" + the reconnect hint — no reviewer copy

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getConnections: vi.fn(), shopifyInitiate: vi.fn(), listLocations: vi.fn() }
})

const SHOP = 'custom-shop.myshopify.com'

function fixture(connected: boolean, oauthAvailable: boolean): api.ConnectionsStatus {
  return {
    shopify: {
      connected, storeId: 'store-1', shopDomain: SHOP, connectionType: 'custom_app_cc',
      status: connected ? 'connected' : 'disconnected', importStatus: 'completed', lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null, simulated: false },
    customAppAvailable: false,
    oauthAvailable,
    shopifySetup: { appUrl: 'http://localhost:5173', redirectUrl: 'http://localhost:8080/auth/shopify/callback',
                    webhookApiVersion: '2026-04', scopes: ['read_orders'] },
  }
}

describe('ConnectionsTab — official-app upgrade (Build C)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  test('b1: offered → the upgrade banner shows', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(true, true))
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    expect(await screen.findByText('The official Traced app is live')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Upgrade to the official app' })).toBeInTheDocument()
  })

  test('b2: not offered → no banner', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(true, false))
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await screen.findByTestId('shopify-disconnect-btn')
    expect(screen.queryByText('The official Traced app is live')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Upgrade to the official app' })).not.toBeInTheDocument()
  })

  test('b3: the disconnected card uses neutral copy, never "For Shopify reviewers"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(false, false))
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    expect(await screen.findByText('Reconnect this store through the official Traced app.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Connect with Shopify' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect with Shopify' })).toBeInTheDocument()
    expect(screen.queryByText(/reviewer/i)).not.toBeInTheDocument()
  })
})
