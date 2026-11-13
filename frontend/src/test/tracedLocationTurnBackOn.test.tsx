import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// L1 (2026-11-12) — a reconnect READ found the Traced location switched off in Shopify: the connection card warns and
// only the owner gets "Turn back on". Traced never turns it back on by itself.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(),
    listLocations: vi.fn(),
    getShopifyInventoryReconcileReport: vi.fn(),
    activateShopifyFulfillment: vi.fn(),
    turnTracedLocationBackOn: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

const CONNECTIONS: api.ConnectionsStatus = {
  shopify: {
    connected: true, storeId: 'store-1', shopDomain: 'test-shop.myshopify.com',
    connectionType: 'oauth', status: 'connected', importStatus: 'completed', lastSyncAt: '2026-11-12T00:00:00Z',
  },
  bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
  customAppAvailable: false,
  oauthAvailable: false,
  shopifySetup: { appUrl: 'http://localhost:5173', redirectUrl: 'http://x', webhookApiVersion: '2026-04', scopes: [] },
} as api.ConnectionsStatus

function location(overrides: Partial<api.LocationRow> = {}): api.LocationRow {
  return {
    id: 'loc-1', name: 'Main Warehouse', type: 'warehouse', is_default: true, is_fulfillment: true,
    shopify_location_id: 'gid://shopify/Location/1', shopify_sync_status: 'linked',
    shopify_sync_error: null, shopify_synced_at: null,
    shopify_delivery_profile_status: 'activated', shopify_delivery_profile_error: null,
    shopify_delivery_profile_activated_at: '2026-11-01T00:00:00Z',
    shopify_online_fulfillment_status: 'on',
    ...overrides,
  }
}

describe('Traced location switched off in Shopify — Turn back on (L1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getConnections).mockResolvedValue(CONNECTIONS)
    vi.mocked(api.getShopifyInventoryReconcileReport).mockResolvedValue({ tracedLocationGid: 'gid://shopify/Location/1', rows: [] })
  })

  test('online off → warning; the owner clicks Turn back on, which calls the endpoint and reloads', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations)
      .mockResolvedValueOnce([location({ shopify_online_fulfillment_status: 'off' })])
      .mockResolvedValue([location()])
    vi.mocked(api.turnTracedLocationBackOn).mockResolvedValue({ onlineStatus: 'on', profile: 'ALREADY_MEMBER' })

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-location-off')).toBeTruthy())
    expect(screen.getByText(/Fulfill online orders from this location/)).toBeTruthy()
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()

    await userEvent.click(screen.getByTestId('turn-back-on-btn'))

    await waitFor(() => expect(api.turnTracedLocationBackOn).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByTestId('fulfillment-activated')).toBeTruthy())
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
  })

  test('taken out of the shipping profile → profile wording', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_delivery_profile_status: 'not_activated' })])

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-location-off')).toBeTruthy())
    expect(screen.getByText(/General shipping profile/)).toBeTruthy()
  })

  test('a manager sees the warning but no button — owner only', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('manager')
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_online_fulfillment_status: 'off' })])

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-location-off')).toBeTruthy())
    expect(screen.queryByTestId('turn-back-on-btn')).toBeNull()
    expect(screen.getByTestId('turn-back-on-owner-only')).toBeTruthy()
    expect(api.turnTracedLocationBackOn).not.toHaveBeenCalled()
  })

  test('never activated before (no activated_at) → the existing Activate item, not the warning', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_delivery_profile_status: 'not_activated', shopify_delivery_profile_activated_at: null,
      shopify_online_fulfillment_status: 'unknown',
    })])

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('fulfillment-activation')).toBeTruthy())
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
    expect(screen.getByText(/Moving from my Shopify location/)).toBeTruthy()
  })

  test('predicate: only a linked fulfillment row that is off / taken out', () => {
    expect(api.tracedLocationNeedsTurnBackOn(location())).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off' }))).toBe(true)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'error' }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off', is_fulfillment: false }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off', shopify_sync_status: 'error' }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(null)).toBe(false)
  })
})
