import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// L1 (2026-11-12) — a reconnect READ found the Traced location switched off in Shopify: the connection card warns and
// only the owner gets "Turn back on". Traced never turns it back on by itself.
// L1b-2 (2026-11-16): Turn back on is ONLY "Fulfill online orders". A location out of the shipping profile gets the
// owner's instruction + a deep link to the profile + Check again — Traced never adds it to a profile.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(),
    listLocations: vi.fn(),
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
  })

  test('online off → warning; the owner clicks Turn back on, which calls the endpoint and reloads', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations)
      .mockResolvedValueOnce([location({ shopify_online_fulfillment_status: 'off' })])
      .mockResolvedValue([location({ shopify_shipping_readiness: 'ready' })])
    vi.mocked(api.turnTracedLocationBackOn).mockResolvedValue({ onlineStatus: 'on', profile: 'member' })

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-location-off')).toBeTruthy())
    expect(screen.getByText(/Fulfill online orders from this location/)).toBeTruthy()
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()

    await userEvent.click(screen.getByTestId('turn-back-on-btn'))

    await waitFor(() => expect(api.turnTracedLocationBackOn).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByTestId('fulfillment-activated')).toBeTruthy())
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
  })

  test('taken out of the shipping profile → the instruction + a deep link to the profile, no Turn back on', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_delivery_profile_status: 'not_activated', shopify_shipping_model: 'legacy',
      shopify_shipping_readiness: 'profile_removed', shopify_delivery_profile_number: '77' })])

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-shipping-add-profile')).toBeTruthy())
    expect(screen.getByText('Traced Main Warehouse was taken out of your shipping profile in Shopify')).toBeTruthy()
    expect(screen.getByTestId('traced-shipping-open-profile'))
      .toHaveAttribute('href', 'https://admin.shopify.com/store/test-shop/settings/shipping/profiles/77')
    expect(screen.getByTestId('traced-shipping-check-btn')).toBeTruthy()
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
    expect(screen.queryByTestId('turn-back-on-btn')).toBeNull()
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

  test('never in the profile (no activated_at) → add-to-profile instruction, never an Activate button', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_delivery_profile_status: 'not_activated', shopify_delivery_profile_activated_at: null,
      shopify_shipping_model: 'legacy', shopify_shipping_readiness: 'activate_needed',
    })])

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await waitFor(() => expect(screen.getByTestId('traced-shipping-add-profile')).toBeTruthy())
    expect(screen.getByText('Add Traced Main Warehouse to your shipping profile in Shopify')).toBeTruthy()
    // No profile id yet → the link falls back to shipping settings.
    expect(screen.getByTestId('traced-shipping-open-profile'))
      .toHaveAttribute('href', 'https://admin.shopify.com/store/test-shop/settings/shipping')
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()
  })

  test('predicate: only a linked fulfillment row whose online selling is off', () => {
    expect(api.tracedLocationNeedsTurnBackOn(location())).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off' }))).toBe(true)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'error' }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off', is_fulfillment: false }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_online_fulfillment_status: 'off', shopify_sync_status: 'error' }))).toBe(false)
    expect(api.tracedLocationNeedsTurnBackOn(null)).toBe(false)
    // L1b-2: out of the profile is never a Turn back on (the instruction handles it).
    expect(api.tracedLocationNeedsTurnBackOn(location({ shopify_shipping_readiness: 'profile_removed',
      shopify_delivery_profile_status: 'not_activated' }))).toBe(false)
  })
})
