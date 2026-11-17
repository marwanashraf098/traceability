import { test, expect, describe, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import * as api from '../api'
import { ConnectedSummary } from '../pages/onboarding/OnboardingGate'
import { renderWithProviders } from './renderWithProviders'
import ConnectionsTab from '../pages/settings/ConnectionsTab'

// L1b-2 fix (2026-11-17) — live test on testl1location (country Egypt, read_markets granted, Traced Main in no active
// rate): the onboarding gate showed "Traced can't check this from here yet" + "I've added it" although Traced had read
// every market and found it missing. The words under the instruction now follow TracedMarketCheck:
//
//   g1 the Egypt store in the gate: not_covered → the firm "Traced checked your shipping rates…", no "I've added it",
//      "Not added for: Egypt, Canada, United States", Check again
//   g2 read_markets granted, no verdict → "couldn't find your main market's shipping" (never "can't check from here");
//      the owner's "I've added it" stays (Traced couldn't decide)
//   g3 granted, never read → "hasn't read your shipping rates yet — use Check again"
//   g4 not granted → "can't check this from here yet" (the only case), unchanged
//   g5 the connection card reads the row's shopify_market_check
//   g6 Arabic: the no-verdict words

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
      status: 'connected', importStatus: 'completed', lastSyncAt: '2026-11-17T00:00:00Z', locationState: 'active',
      tracedLocationNumber: '115488981283', tracedMainShipping: 'add_to_shipping_rates',
      tracedShippingModel: 'market_driven', ...shopify },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: false,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
  } as api.ConnectionsStatus
}

function gate(lng: 'en' | 'ar' = 'en') {
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return render(<I18nextProvider i18n={i18nFor(lng)}><ConnectedSummary name="Mona" onContinue={() => {}} /></I18nextProvider>)
}

describe('market check words (L1b-2 fix)', () => {
  beforeEach(() => {
    cleanup(); vi.clearAllMocks()
    document.documentElement.dir = 'ltr'
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
  })

  test('g1 Egypt store in the gate: not_covered is firm, no "I\'ve added it"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMarketCheck: 'not_covered',
      tracedNotAddedFor: ['Egypt', 'Canada', 'United States'] }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-not-covered')).toBeTruthy())
    expect(screen.getByText(/Traced checked your shipping rates/)).toBeTruthy()
    expect(screen.queryByText(/can’t check this from here/)).toBeNull()
    expect(screen.queryByTestId('traced-shipping-confirm-btn')).toBeNull()
    expect(screen.getByTestId('traced-shipping-not-added-for')).toHaveTextContent('Not added for: Egypt, Canada, United States')
    expect(screen.getByTestId('traced-shipping-check-btn')).toBeTruthy()
  })

  test('g2 granted, no verdict: says why, never "can\'t check from here"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMarketCheck: 'no_verdict' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-reason-noVerdict')).toBeTruthy())
    expect(screen.getByTestId('traced-shipping-reason-noVerdict'))
      .toHaveTextContent('couldn’t find your main market’s shipping')
    expect(screen.queryByText(/can’t check this from here/)).toBeNull()
    expect(screen.getByTestId('traced-shipping-confirm-btn')).toBeTruthy()
  })

  test('g3 granted, never read: use Check again', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMarketCheck: 'not_checked' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-reason-notChecked')).toHaveTextContent('use Check again'))
    expect(screen.queryByText(/can’t check this from here/)).toBeNull()
  })

  test('g4 not granted: "can\'t check this from here yet"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMarketCheck: 'no_access' }))
    gate()
    await waitFor(() => expect(screen.getByTestId('traced-shipping-reason-cantConfirm'))
      .toHaveTextContent('can’t check this from here yet'))
    expect(screen.getByTestId('traced-shipping-confirm-btn')).toBeTruthy()
  })

  test('g5 the connection card reads the row\'s shopify_market_check', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({}))
    vi.mocked(api.listLocations).mockResolvedValue([{
      id: 'loc-1', name: 'Main Warehouse', type: 'warehouse', is_default: true, is_fulfillment: true,
      shopify_location_id: 'gid://shopify/Location/115488981283', shopify_sync_status: 'linked',
      shopify_sync_error: null, shopify_synced_at: null, shopify_delivery_profile_status: 'not_activated',
      shopify_delivery_profile_error: null, shopify_delivery_profile_activated_at: null,
      shopify_online_fulfillment_status: 'on', shopify_shipping_model: 'market_driven',
      shopify_shipping_readiness: 'add_to_shipping_rates', shopify_market_shipping_status: null,
      shopify_market_shipping_checked_at: '2026-11-17T00:00:00Z', shopify_market_check: 'no_verdict',
    } as api.LocationRow])
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await waitFor(() => expect(screen.getByTestId('traced-shipping-reason-noVerdict')).toBeTruthy())
    expect(screen.queryByText(/can’t check this from here/)).toBeNull()
  })

  test('g6 Arabic: the no-verdict words', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connections({ tracedMarketCheck: 'no_verdict' }))
    gate('ar')
    await waitFor(() => expect(screen.getByTestId('traced-shipping-reason-noVerdict'))
      .toHaveTextContent('لم يجد شحن سوقك الرئيسي'))
  })
})
