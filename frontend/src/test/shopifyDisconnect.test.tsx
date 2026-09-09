import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// ── Mock ─────────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections:     vi.fn(),
    shopifyDisconnect:  vi.fn(),
    listLocations:      vi.fn(),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const STORE_ID = 'store-123'
const SHOP_DOMAIN = 'test-shop.myshopify.com'

function connectedFixture(): api.ConnectionsStatus {
  return {
    shopify: {
      connected: true, storeId: STORE_ID, shopDomain: SHOP_DOMAIN,
      importStatus: 'completed', lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    shopifyCustomApp: { connected: false, shopDomain: null, importStatus: null, lastSyncAt: null },
    customAppAvailable: false,
  }
}

function disconnectedFixture(): api.ConnectionsStatus {
  return {
    shopify: { connected: false, storeId: null, shopDomain: null, importStatus: null, lastSyncAt: null },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    shopifyCustomApp: { connected: false, shopDomain: null, importStatus: null, lastSyncAt: null },
    customAppAvailable: false,
  }
}

describe('ConnectionsTab — Shopify disconnect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('owner: click Disconnect → confirm → shopifyDisconnect called → card shows connect form', async () => {
    vi.mocked(api.getConnections)
      .mockResolvedValueOnce(connectedFixture())
      .mockResolvedValueOnce(disconnectedFixture())
    vi.mocked(api.shopifyDisconnect).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const disconnectBtn = await screen.findByTestId('shopify-disconnect-btn')
    expect((disconnectBtn as HTMLButtonElement).disabled).toBe(false)

    await user.click(disconnectBtn)

    expect(window.confirm).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(api.shopifyDisconnect).toHaveBeenCalledWith(STORE_ID))

    // Refetch happened — card flips back to the connect form.
    await screen.findByPlaceholderText('your-store.myshopify.com')
    expect(screen.queryByTestId('shopify-disconnect-btn')).toBeNull()
  })

  test('owner: declining the confirm dialog does not call shopifyDisconnect', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connectedFixture())
    vi.spyOn(window, 'confirm').mockReturnValue(false)

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const disconnectBtn = await screen.findByTestId('shopify-disconnect-btn')
    await user.click(disconnectBtn)

    expect(window.confirm).toHaveBeenCalledTimes(1)
    expect(api.shopifyDisconnect).not.toHaveBeenCalled()
  })

  test('manager (readOnly): Disconnect button is disabled', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connectedFixture())
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={true} />)

    const disconnectBtn = await screen.findByTestId('shopify-disconnect-btn')

    // A <fieldset disabled> ancestor makes descendant controls "actually disabled"
    // per the HTML spec, but does NOT flip their own `.disabled` IDL property — so the
    // meaningful assertion is behavioral: the control must not respond to interaction.
    await user.click(disconnectBtn)

    expect(window.confirm).not.toHaveBeenCalled()
    expect(api.shopifyDisconnect).not.toHaveBeenCalled()
  })
})
