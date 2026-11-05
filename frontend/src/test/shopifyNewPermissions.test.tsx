import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import i18n from '../i18n'
import ShopifyConnectionCard from '../pages/settings/connections/ShopifyConnectionCard'

/**
 * Shopify-native returns S1 — Settings › Connections, Shopify card: "Approve Traced's new Shopify permissions".
 *   np1 — owner, store hasn't granted the new scopes (backend sent newPermissions): title + open-app link
 *   np2 — no newPermissions (granted, notice off, or not the owner per the backend): nothing
 *   np3 — a manager never sees it, even if the field were present
 *   np4 — Arabic
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listLocations: vi.fn(),
    getShopifyInventoryReconcileReport: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

const URL = 'https://admin.shopify.com/store/broek/apps/trace'

function shopify(over: Partial<api.ConnectionsStatus['shopify']> = {}): api.ConnectionsStatus['shopify'] {
  return {
    connected: true, storeId: 's1', shopDomain: 'broek.myshopify.com', connectionType: 'oauth',
    status: 'connected', importStatus: 'completed', lastSyncAt: null, locationState: 'active', resendableFailed: 0,
    ...over,
  }
}

const SETUP: api.ConnectionsStatus['shopifySetup'] = {
  appUrl: 'http://localhost:5173', redirectUrl: 'http://localhost:8080/auth/shopify/callback',
  webhookApiVersion: '2026-04', scopes: ['read_products'],
}

function card(s: api.ConnectionsStatus['shopify']) {
  renderWithProviders(
    <I18nextProvider i18n={i18n}>
      <ShopifyConnectionCard shopify={s} customAppAvailable={false} oauthAvailable={false} shopifySetup={SETUP}
                             reload={vi.fn(async () => {})} />
    </I18nextProvider>)
}

describe('Shopify card — new Shopify permissions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
  })

  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  test('np1 — owner sees the notice and the open-app link', () => {
    card(shopify({ newPermissions: { openAppUrl: URL } }))
    const notice = screen.getByTestId('shopify-new-permissions')
    expect(notice).toHaveTextContent("Approve Traced's new Shopify permissions")
    const link = screen.getByRole('link', { name: /open traced in shopify/i })
    expect(link).toHaveAttribute('href', URL)
    expect(link).toHaveAttribute('target', '_blank')
  })

  test('np2 — nothing without newPermissions', () => {
    card(shopify())
    expect(screen.queryByTestId('shopify-new-permissions')).toBeNull()
  })

  test('np3 — a manager never sees it', () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('manager')
    card(shopify({ newPermissions: { openAppUrl: URL } }))
    expect(screen.queryByTestId('shopify-new-permissions')).toBeNull()
  })

  test('np4 — Arabic', async () => {
    await i18n.changeLanguage('ar')
    card(shopify({ newPermissions: { openAppUrl: URL } }))
    expect(screen.getByTestId('shopify-new-permissions')).toHaveTextContent('وافق على صلاحيات شوبيفاي الجديدة')
  })
})
