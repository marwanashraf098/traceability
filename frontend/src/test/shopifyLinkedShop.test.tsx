import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// Review mode S1 — shop binding rule + simulated courier, as the Connections tab shows them.
//   f1 a disconnected store with a known domain: "linked to X" notice, shop input prefilled
//   f2 a different shop → the backend's SHOPIFY_SHOP_MISMATCH message is shown as-is
//   f3 a simulated courier: read-only "Simulated" Bosta card, no connect wizard

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections:  vi.fn(),
    shopifyInitiate: vi.fn(),
    listLocations:   vi.fn(),
  }
})

const LINKED = 'linked-shop.myshopify.com'

const SETUP = {
  appUrl: 'http://localhost:5173',
  redirectUrl: 'http://localhost:8080/auth/shopify/callback',
  webhookApiVersion: '2026-04',
  scopes: ['read_products', 'read_orders'],
}

function fixture(over: { shopDomain?: string | null; simulated?: boolean } = {}): api.ConnectionsStatus {
  return {
    shopify: {
      connected: false, storeId: over.shopDomain ? 'store-1' : null, shopDomain: over.shopDomain ?? null,
      connectionType: over.shopDomain ? 'oauth' : null, status: 'disconnected',
      importStatus: null, lastSyncAt: null,
    },
    bosta: over.simulated
      ? { connected: true, businessName: null, pickupMode: null, awbFormat: null, awbLang: null, simulated: true }
      : { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null, simulated: false },
    customAppAvailable: false,
    oauthAvailable: true,
    shopifySetup: SETUP,
  }
}

describe('ConnectionsTab — review mode S1', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  test('f1: disconnected store with a domain shows the linked-shop notice and prefills the shop', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture({ shopDomain: LINKED }))
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const notice = await screen.findByTestId('shopify-linked-shop')
    expect(notice.textContent).toContain(LINKED)
    // Reconnect: no input — the linked shop and one "Connect with Shopify" button (store finder build).
    expect(screen.getByTestId('shopify-reconnect')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect with Shopify' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  test('f2: reconnect calls initiate with the linked shop; a backend error is shown as-is', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture({ shopDomain: LINKED }))
    vi.mocked(api.shopifyInitiate).mockRejectedValue(new api.TransferCommandError({
      code: 'SHOPIFY_SHOP_MISMATCH',
      message_en: `This account is linked to ${LINKED}. It can only reconnect that store.`,
      message_ar: `هذا الحساب مرتبط بالمتجر ${LINKED}. لا يمكن إلا إعادة ربط هذا المتجر.`,
    }))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    // The reconnect card has no input (store finder build) — the only shop it can send is the linked one.
    await user.click(await screen.findByRole('button', { name: 'Connect with Shopify' }))

    expect(api.shopifyInitiate).toHaveBeenCalledWith(LINKED)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain(`This account is linked to ${LINKED}`)
  })

  test('f3: simulated courier shows a read-only Simulated Bosta card, no connect wizard', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture({ simulated: true }))
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const card = await screen.findByTestId('bosta-simulated-card')
    expect(card.textContent).toContain('Simulated (review mode)')
    expect(card.querySelector('button')).toBeNull()
    expect(screen.queryByTestId('shopify-linked-shop')).toBeNull()
  })
})
