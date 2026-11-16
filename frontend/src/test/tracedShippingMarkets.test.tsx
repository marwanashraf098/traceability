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

// L1b-2 (2026-11-16) — Traced READS whether Traced Main is in the store's shipping (legacy: the General profile;
// market-driven: the primary market's rates) and never writes either. Wording by model, a deep link, "Check again"
// (owner / manager, one per 2 minutes), the other markets that miss it as information, and the optional read_markets.
//
//   s1 ready (market-driven): live + "Ships with" + "Not added for: Canada" (info, still Ready)
//   s2 market-driven not_covered: "Traced checked…", no "I've added it" (a verified miss beats the owner's word)
//   s3 Check again: reads, then the card reloads; inside the 2 minutes → "you can check again at …", no reload
//   s4 Check again is owner / manager only — a worker sees none
//   s5 the generalised notice: read_markets ask (owner) with Shopify's request URL; the returns ask unchanged
//   s6 gate, legacy: "Add to shipping profile" + the profile deep link; market-driven: the read_markets ask inside the
//      instruction (owner)
//   s7 Arabic: the legacy instruction and Check again translated

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(),
    listLocations: vi.fn(),
    getRoleFromToken: vi.fn(),
    confirmTracedShippingRates: vi.fn(),
    checkTracedShipping: vi.fn(),
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
      status: 'connected', importStatus: 'completed', lastSyncAt: '2026-11-16T00:00:00Z', locationState: 'active',
      tracedLocationNumber: '115488981283', ...shopify },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: false,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
  } as api.ConnectionsStatus
}

function location(overrides: Partial<api.LocationRow>): api.LocationRow {
  return {
    id: 'loc-1', name: 'Main Warehouse', type: 'warehouse', is_default: true, is_fulfillment: true,
    shopify_location_id: 'gid://shopify/Location/115488981283', shopify_sync_status: 'linked',
    shopify_sync_error: null, shopify_synced_at: null,
    shopify_delivery_profile_status: 'not_activated', shopify_delivery_profile_error: null,
    shopify_delivery_profile_activated_at: null, shopify_online_fulfillment_status: 'on',
    ...overrides,
  }
}

const MARKETS_URL = 'https://admin.shopify.com/store/testl1location/oauth/install?client_id=c&optional_scopes=read_markets'

function gate(lng: 'en' | 'ar' = 'en') {
  const i18n = i18nFor(lng)
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return render(<I18nextProvider i18n={i18n}><ConnectedSummary name="Mona" onContinue={() => {}} /></I18nextProvider>)
}

describe('connection card — market coverage and Check again (L1b-2)', () => {
  beforeEach(() => {
    cleanup(); vi.clearAllMocks()
    document.documentElement.dir = 'ltr'
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.getConnections).mockResolvedValue(connections({}))
  })

  test('s1 ready: live, what covers it, and the other markets as information', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_shipping_model: 'market_driven', shopify_shipping_readiness: 'ready',
      shopify_market_shipping_status: 'covered', shopify_market_shipping_covered_by: 'United States — Standard',
      shopify_market_shipping_missing: ['Canada'] })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('fulfillment-activated')).toBeTruthy())
    expect(screen.getByTestId('traced-shipping-covered-by')).toHaveTextContent('Ships with: United States — Standard')
    expect(screen.getByTestId('traced-shipping-not-added-for')).toHaveTextContent('Not added for: Canada')
    expect(screen.queryByTestId('traced-shipping-add-rates')).toBeNull()
  })

  test('s2 not_covered: Traced checked it, no "I\'ve added it", Check again offered', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({
      shopify_shipping_model: 'market_driven', shopify_shipping_readiness: 'add_to_shipping_rates',
      shopify_market_shipping_status: 'not_covered', shopify_market_shipping_missing: ['United States'] })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('traced-shipping-not-covered')).toBeTruthy())
    expect(screen.getByText(/Traced checked your shipping rates/)).toBeTruthy()
    expect(screen.queryByTestId('traced-shipping-confirm-btn')).toBeNull()
    expect(screen.getByTestId('traced-shipping-check-btn')).toBeTruthy()
    expect(screen.getByTestId('traced-shipping-not-added-for')).toHaveTextContent('United States')
  })

  test('s3 Check again: reads then reloads; inside 2 minutes it says when, without reloading', async () => {
    vi.mocked(api.listLocations)
      .mockResolvedValueOnce([location({ shopify_shipping_model: 'market_driven',
        shopify_shipping_readiness: 'add_to_shipping_rates' })])
      .mockResolvedValue([location({ shopify_shipping_model: 'market_driven', shopify_shipping_readiness: 'ready',
        shopify_market_shipping_covered_by: 'United States — Standard' })])
    vi.mocked(api.checkTracedShipping).mockResolvedValueOnce({ rateLimited: false, readiness: 'ready',
      shippingModel: 'market_driven', coveredBy: 'United States — Standard', notAddedFor: [] })
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await userEvent.click(await screen.findByTestId('traced-shipping-check-btn'))
    await waitFor(() => expect(screen.getByTestId('fulfillment-activated')).toBeTruthy())
    expect(api.checkTracedShipping).toHaveBeenCalledTimes(1)
    expect(api.listLocations).toHaveBeenCalledTimes(2)

    cleanup()
    vi.mocked(api.listLocations).mockReset()
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_shipping_model: 'legacy',
      shopify_shipping_readiness: 'activate_needed' })])
    vi.mocked(api.checkTracedShipping).mockResolvedValueOnce({ rateLimited: true, nextAllowedAt: '2026-11-16T12:34:00Z' })
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await userEvent.click(await screen.findByTestId('traced-shipping-check-btn'))
    await waitFor(() => expect(screen.getByTestId('traced-shipping-check-note')).toHaveTextContent(/you can check again at/))
    expect(api.listLocations).toHaveBeenCalledTimes(1)
  })

  test('s4 a worker gets no Check again', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_shipping_model: 'legacy',
      shopify_shipping_readiness: 'activate_needed' })])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('traced-shipping-add-profile')).toBeTruthy())
    expect(screen.queryByTestId('traced-shipping-check-btn')).toBeNull()
  })

  test('s5 the notice: the read_markets ask with Shopify\'s request URL; the returns ask stays separate', async () => {
    vi.mocked(api.listLocations).mockResolvedValue([location({ shopify_shipping_model: 'market_driven',
      shopify_shipping_readiness: 'add_to_shipping_rates' })])
    vi.mocked(api.getConnections).mockResolvedValue(connections({ newPermissions: { marketsRequestUrl: MARKETS_URL } }))
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('shopify-markets-permission')).toBeTruthy())
    expect(screen.getByTestId('shopify-markets-permission-link')).toHaveAttribute('href', MARKETS_URL)
    expect(screen.queryByTestId('shopify-new-permissions')).toBeNull()
    // On the card the ask lives in the notice only — never twice.
    expect(screen.queryByTestId('traced-shipping-allow-markets')).toBeNull()
  })
})

describe('onboarding gate — wording by model (L1b-2)', () => {
  beforeEach(() => { cleanup(); vi.clearAllMocks(); vi.mocked(api.getRoleFromToken).mockReturnValue('owner') })

  test('s6 legacy: "Add to shipping profile" + the profile deep link; market-driven: the read_markets ask', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'activate_needed',
      tracedShippingModel: 'legacy', deliveryProfileNumber: '134823837987' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Add to shipping profile'))
    expect(screen.getByTestId('traced-shipping-open-profile'))
      .toHaveAttribute('href', 'https://admin.shopify.com/store/testl1location/settings/shipping/profiles/134823837987')
    expect(screen.queryByTestId('traced-shipping-confirm-btn')).toBeNull()

    cleanup()
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'add_to_shipping_rates',
      tracedShippingModel: 'market_driven', newPermissions: { marketsRequestUrl: MARKETS_URL } }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-allow-markets-link')).toHaveAttribute('href', MARKETS_URL))
    expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('Check shipping rates')
  })

  test('s7 Arabic: the legacy instruction and Check again translated', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMainShipping: 'profile_removed',
      tracedShippingModel: 'legacy' }))
    gate('ar')
    await waitFor(() => expect(screen.getByTestId('setup-row-traced-main')).toHaveTextContent('أضِفه إلى ملف الشحن'))
    expect(screen.getByText('تمت إزالة «Traced Main Warehouse» من ملف الشحن في Shopify')).toBeTruthy()
    expect(screen.getByTestId('traced-shipping-check-btn')).toHaveTextContent('تحقّق مرة أخرى')
    expect(screen.getByTestId('traced-shipping-open-profile')).toHaveTextContent('افتح ملف الشحن')
  })
})
