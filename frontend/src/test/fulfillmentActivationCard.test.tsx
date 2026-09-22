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
    getConnections: vi.fn(),
    listLocations: vi.fn(),
    getShopifyInventoryReconcileReport: vi.fn(),
    activateShopifyFulfillment: vi.fn(),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const SHOPIFY_SETUP_FIXTURE = {
  appUrl: 'http://localhost:5173',
  redirectUrl: 'http://localhost:8080/auth/shopify/callback',
  webhookApiVersion: '2026-04',
  scopes: ['read_products', 'read_orders'],
}

function connectionsFixture(importStatus: string | null): api.ConnectionsStatus {
  return {
    shopify: {
      connected: true, storeId: 'store-1', shopDomain: 'test-shop.myshopify.com',
      connectionType: 'oauth', status: 'connected',
      importStatus, lastSyncAt: importStatus === 'completed' ? '2026-01-01T00:00:00Z' : null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false,
    oauthAvailable: false,
    shopifySetup: SHOPIFY_SETUP_FIXTURE,
  }
}

function locationFixture(overrides: Partial<api.LocationRow> = {}): api.LocationRow {
  return {
    id: 'loc-1', name: 'Traced Main Warehouse', type: 'warehouse',
    is_default: false, is_fulfillment: true,
    shopify_location_id: 'gid://shopify/Location/1',
    shopify_sync_status: 'unsynced', shopify_sync_error: null, shopify_synced_at: null,
    shopify_delivery_profile_status: 'not_activated',
    shopify_delivery_profile_error: null, shopify_delivery_profile_activated_at: null,
    ...overrides,
  }
}

const EMPTY_RECONCILE_REPORT: api.ShopifyReconcileReport = {
  tracedLocationGid: 'gid://shopify/Location/1',
  rows: [],
}

describe('FulfillmentActivationItem — race + refetch (Part 2 fix)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  // Reproduces the exact race diagnosed in Part 2: the user reaches Settings ->
  // Connections before ShopifyImportJob has linked the Traced Main Warehouse location.
  // On the PRE-FIX code (load() fired once on mount, no poll, no importStatus/
  // lastSyncAt dependency) this test goes RED — the card never appears without an
  // actual remount. On the fixed code, ConnectionsTab's poll picks up the completed
  // import, feeds a new importStatus into FulfillmentActivationItem, which re-fetches
  // listLocations() and reveals the card — no remount, no manual reload.
  test('card appears once the import job finishes in the background, without remounting the page', async () => {
    vi.mocked(api.getConnections)
      .mockResolvedValueOnce(connectionsFixture('importing'))
      .mockResolvedValueOnce(connectionsFixture('completed'))
    vi.mocked(api.listLocations)
      .mockResolvedValueOnce([locationFixture({ shopify_sync_status: 'unsynced' })])
      .mockResolvedValueOnce([locationFixture({ shopify_sync_status: 'linked' })])
    vi.mocked(api.getShopifyInventoryReconcileReport).mockResolvedValue(EMPTY_RECONCILE_REPORT)

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    // Initial mount snapshot: import still in flight, location unsynced — no card.
    await vi.waitFor(() => expect(api.listLocations).toHaveBeenCalledTimes(1))
    expect(screen.queryByTestId('fulfillment-activation')).toBeNull()
    expect(screen.queryByTestId('shopify-disconnect-btn')).toBeTruthy() // page itself is up, not stuck loading

    // Advance past ConnectionsTab's poll interval — it re-fetches getConnections(),
    // importStatus flips to 'completed', which FulfillmentActivationItem's load effect
    // depends on, so it re-fetches listLocations() too. The resulting chain (interval
    // fire -> getConnections resolves -> re-render -> child effect -> listLocations
    // resolves -> re-render) spans more microtask ticks than a single
    // advanceTimersByTimeAsync call flushes, so poll with vi.waitFor (shouldAdvanceTime
    // keeps the fake clock moving with real elapsed time while it does).
    await vi.advanceTimersByTimeAsync(3100)

    await vi.waitFor(() => expect(api.listLocations).toHaveBeenCalledTimes(2))
    expect(api.getConnections).toHaveBeenCalledTimes(2)
    expect(screen.getByTestId('fulfillment-activation')).toBeTruthy()
  })

  test('polling stops once importStatus is already completed on mount — no extra getConnections calls', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connectionsFixture('completed'))
    vi.mocked(api.listLocations).mockResolvedValue([locationFixture({ shopify_sync_status: 'linked' })])
    vi.mocked(api.getShopifyInventoryReconcileReport).mockResolvedValue(EMPTY_RECONCILE_REPORT)

    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await vi.waitFor(() => expect(screen.getByTestId('fulfillment-activation')).toBeTruthy())
    expect(api.getConnections).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10000)

    // No poll ever started (importStatus was already 'completed' on the very first snapshot).
    expect(api.getConnections).toHaveBeenCalledTimes(1)
  })

  test('a failed listLocations() shows a distinct error state with Retry, not "nothing to show"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connectionsFixture('completed'))
    vi.mocked(api.listLocations)
      .mockRejectedValueOnce(new Error('network error'))
      .mockResolvedValueOnce([locationFixture({ shopify_sync_status: 'linked' })])
    vi.mocked(api.getShopifyInventoryReconcileReport).mockResolvedValue(EMPTY_RECONCILE_REPORT)

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const errorBox = await vi.waitFor(() => {
      const el = screen.getByTestId('fulfillment-activation-error')
      expect(el).toBeTruthy()
      return el
    })
    expect(errorBox.textContent).toMatch(/could not load/i)
    expect(screen.queryByTestId('fulfillment-activation')).toBeNull()

    await user.click(screen.getByTestId('fulfillment-retry-btn'))

    await vi.waitFor(() => expect(screen.getByTestId('fulfillment-activation')).toBeTruthy())
    expect(screen.queryByTestId('fulfillment-activation-error')).toBeNull()
    expect(api.listLocations).toHaveBeenCalledTimes(2)
  })
})
