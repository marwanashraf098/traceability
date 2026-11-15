import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import * as api from '../api'
import { ConnectedSummary } from '../pages/onboarding/OnboardingGate'
import { renderWithProviders } from './renderWithProviders'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// L1b-1 (2026-11-15) — market-driven shipping: never "Ready"; the owner gets the instruction and a link to the
// location's page in Shopify (its Shipping row opens "Select rates"). Readiness comes from the server.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(),
    listLocations: vi.fn(),
    getShopifyInventoryReconcileReport: vi.fn(),
    getRoleFromToken: vi.fn(),
    confirmTracedShippingRates: vi.fn(),
  }
})

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return inst
}

function connections(shopify: Partial<api.ConnectionsStatus['shopify']>): api.ConnectionsStatus {
  return {
    shopify: { connected: true, storeId: 's', shopDomain: 'testl1location.myshopify.com', connectionType: 'oauth',
      status: 'connected', importStatus: 'completed', lastSyncAt: '2026-11-15T00:00:00Z', locationState: 'active',
      tracedLocationNumber: '115488981283', ...shopify },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: false,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
  } as api.ConnectionsStatus
}

function gate(lng: 'en' | 'ar' = 'en') {
  const i18n = i18nFor(lng)
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return render(<I18nextProvider i18n={i18n}><ConnectedSummary name="Mona" onContinue={() => {}} /></I18nextProvider>)
}

const LOCATION_URL = 'https://admin.shopify.com/store/testl1location/settings/locations/115488981283'

describe('onboarding gate — Traced Main row (L1b-1)', () => {
  beforeEach(() => { cleanup(); vi.clearAllMocks() })

  test('market-driven: never Ready — "Check shipping rates" + the "make sure" instruction + the location link first', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'add_to_shipping_rates' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Check shipping rates'))
    expect(screen.getByTestId('setup-row-traced-main')).not.toHaveTextContent('Ready')
    expect(screen.getByText('Make sure Traced Main Warehouse is added to your shipping rates in Shopify')).toBeTruthy()
    expect(screen.queryByText(/can.t ship/i)).toBeNull()
    const open = screen.getByTestId('traced-shipping-open-location')
    expect(open).toHaveAttribute('href', LOCATION_URL)
    expect(open).toHaveAttribute('target', '_blank')
    expect(screen.getByTestId('traced-shipping-open-settings'))
      .toHaveAttribute('href', 'https://admin.shopify.com/store/testl1location/settings/shipping')
  })

  test('market-driven in Arabic (RTL): the row and the instruction translated', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'add_to_shipping_rates' }))
    gate('ar')
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('راجِع أسعار الشحن'))
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByText(/تأكّد من إضافة «Traced Main Warehouse» إلى أسعار الشحن/)).toBeTruthy()
    expect(screen.getByTestId('traced-shipping-open-location')).toHaveTextContent('افتح الموقع في Shopify')
    expect(screen.getByTestId('traced-shipping-open-location')).toHaveAttribute('href', LOCATION_URL)
  })

  test('unknown: "Checking…", never Ready, no instruction', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'unknown' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Checking…'))
    expect(screen.getByTestId('setup-row-traced-main')).not.toHaveTextContent('Ready')
    expect(screen.queryByTestId('traced-shipping-add-rates')).toBeNull()
  })

  test('ready: Ready; an active location without the readiness value is NOT Ready', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'ready' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Ready'))
    cleanup()
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: null }))
    gate()
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Setting up…'))
  })

  test('owner: "I\'ve added it" records it → the row says "Confirmed by you", the instruction goes, never Ready', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.getConnections)
      .mockResolvedValueOnce(connections({ tracedMainShipping: 'add_to_shipping_rates' }))
      .mockResolvedValue(connections({ tracedMainShipping: 'owner_confirmed' }))
    vi.mocked(api.confirmTracedShippingRates).mockResolvedValue({ readiness: 'owner_confirmed' })
    gate()
    await userEvent.click(await screen.findByTestId('traced-shipping-confirm-btn'))
    await waitFor(() => expect(api.confirmTracedShippingRates).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Confirmed by you'))
    expect(screen.getByTestId('setup-row-traced-main')).not.toHaveTextContent('Ready')
    expect(screen.queryByTestId('traced-shipping-add-rates')).toBeNull()
    expect(screen.getByTestId('traced-shipping-owner-confirmed')).toBeTruthy()
  })

  test('not the owner: no "I\'ve added it" button, an owner-only note', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('manager')
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'add_to_shipping_rates' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-confirm-owner-only')).toBeTruthy())
    expect(screen.queryByTestId('traced-shipping-confirm-btn')).toBeNull()
    expect(api.confirmTracedShippingRates).not.toHaveBeenCalled()
  })
})

function location(overrides: Partial<api.LocationRow>): api.LocationRow {
  return {
    id: 'loc-1', name: 'Main Warehouse', type: 'warehouse', is_default: true, is_fulfillment: true,
    shopify_location_id: 'gid://shopify/Location/115488981283', shopify_sync_status: 'linked',
    shopify_sync_error: null, shopify_synced_at: null,
    shopify_delivery_profile_status: 'activated', shopify_delivery_profile_error: null,
    shopify_delivery_profile_activated_at: '2026-11-13T19:09:40Z',
    shopify_online_fulfillment_status: 'on',
    ...overrides,
  }
}

describe('connection card — the "live" line (L1b-1)', () => {
  beforeEach(() => {
    cleanup(); vi.clearAllMocks()
    document.documentElement.dir = 'ltr'
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.getConnections).mockResolvedValue(connections({}))
    vi.mocked(api.getShopifyInventoryReconcileReport).mockResolvedValue({ tracedLocationGid: 'x', rows: [] })
  })

  test('market-driven, recorded "activated" (the stale join): no "live", the instruction instead', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_shipping_model: 'market_driven', shopify_shipping_readiness: 'add_to_shipping_rates' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('traced-shipping-add-rates')).toBeTruthy())
    expect(screen.queryByTestId('fulfillment-activated')).toBeNull()
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()
    expect(screen.getByTestId('traced-shipping-open-location')).toHaveAttribute('href', LOCATION_URL)
  })

  test('unknown: no "live", an honest line', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_shipping_readiness: 'unknown' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('fulfillment-shipping-unknown')).toBeTruthy())
    expect(screen.queryByTestId('fulfillment-activated')).toBeNull()
  })

  test('owner confirmed: "Confirmed by you", no instruction, no "live", no Activate', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_shipping_model: 'market_driven', shopify_shipping_readiness: 'owner_confirmed' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('traced-shipping-owner-confirmed')).toBeTruthy())
    expect(screen.getByText('Confirmed by you')).toBeTruthy()
    expect(screen.queryByTestId('traced-shipping-add-rates')).toBeNull()
    expect(screen.queryByTestId('fulfillment-activated')).toBeNull()
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()
  })

  test('legacy, stored not_activated but never read live: "Checking…" — no Activate, no turned-off warning', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_shipping_model: 'legacy', shopify_delivery_profile_status: 'not_activated',
      shopify_delivery_profile_activated_at: '2026-10-01T00:00:00Z', shopify_shipping_readiness: 'unknown' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('fulfillment-shipping-unknown')).toBeTruthy())
    expect(screen.queryByTestId('activate-fulfillment-btn')).toBeNull()
    expect(screen.queryByTestId('traced-location-off')).toBeNull()
  })

  test('ready: live', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_shipping_model: 'legacy', shopify_shipping_readiness: 'ready' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('fulfillment-activated')).toBeTruthy())
  })

  test('admin links: only a numeric location id and a plain myshopify handle', () => {
    expect(api.tracedLocationAdminUrls('a-b.myshopify.com', '12')?.location)
      .toBe('https://admin.shopify.com/store/a-b/settings/locations/12')
    expect(api.tracedLocationAdminUrls('a-b.myshopify.com', 'gid://shopify/Location/12')).toBeNull()
    expect(api.tracedLocationAdminUrls('evil.com/x', '12')).toBeNull()
    expect(api.tracedLocationAdminUrls(null, '12')).toBeNull()
  })
})
