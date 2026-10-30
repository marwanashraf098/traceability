import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import i18n from '../i18n'
import ShopifyConnectionCard from '../pages/settings/connections/ShopifyConnectionCard'

/**
 * The Traced location in Shopify (V196, 2026-10-30) — Settings › Connections, Shopify card.
 *   ls1 — deactivated: the warning (what to do, updates wait), "check now" calls the check; still inactive says so
 *   ls2 — reactivated after a check: the card reloads
 *   ls3 — deleted: reconnect / recreate guidance, no check button
 *   ls4 — active with updates that failed meanwhile: the OWNER sees "Re-send failed updates" (count), it re-sends
 *         and reloads; a manager doesn't see it; nothing at all when there is nothing to re-send
 *   ls5 — Arabic
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listLocations: vi.fn(),
    getShopifyInventoryReconcileReport: vi.fn(),
    checkShopifyLocation: vi.fn(),
    resendShopifyLocationFailures: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

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

function card(s: api.ConnectionsStatus['shopify'], reload = vi.fn(async () => {})) {
  renderWithProviders(
    <ShopifyConnectionCard shopify={s} customAppAvailable={false} oauthAvailable={false} shopifySetup={SETUP} reload={reload} />)
  return reload
}

describe('Shopify card — the Traced location in Shopify', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
  })

  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  test('ls1 — deactivated: the warning, and "check now" says it is still not active', async () => {
    vi.mocked(api.checkShopifyLocation).mockResolvedValue({ locationState: 'inactive' })
    const reload = card(shopify({ locationState: 'inactive' }))

    const notice = await screen.findByTestId('shopify-location-notice')
    expect(notice).toHaveTextContent('Your Traced location in Shopify is deactivated')
    expect(notice).toHaveTextContent('Stock updates are waiting and are sent once it is active again.')
    await userEvent.click(screen.getByRole('button', { name: 'I reactivated it — check now' }))

    await screen.findByText('Shopify still shows the location as not active.')
    expect(api.checkShopifyLocation).toHaveBeenCalledTimes(1)
    expect(reload).not.toHaveBeenCalled()
  })

  test('ls2 — check finds it active again: the card reloads', async () => {
    vi.mocked(api.checkShopifyLocation).mockResolvedValue({ locationState: 'active' })
    const reload = card(shopify({ locationState: 'inactive' }))

    await userEvent.click(await screen.findByRole('button', { name: 'I reactivated it — check now' }))

    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1))
  })

  test('ls3 — deleted: reconnect / recreate guidance, no check button', async () => {
    card(shopify({ locationState: 'deleted' }))

    const notice = await screen.findByTestId('shopify-location-notice')
    expect(notice).toHaveTextContent('Your Traced location in Shopify was deleted')
    expect(notice).toHaveTextContent('Reconnect Shopify, or contact Traced support to recreate the location.')
    expect(screen.queryByRole('button', { name: 'I reactivated it — check now' })).toBeNull()
  })

  test('ls4 — owner re-sends the failed updates; a manager does not see it; nothing when none', async () => {
    vi.mocked(api.resendShopifyLocationFailures).mockResolvedValue({ increments: 3, pieceUpdates: 0, applied: 3 })
    const reload = card(shopify({ resendableFailed: 3 }))

    const box = await screen.findByTestId('shopify-location-resend')
    expect(box).toHaveTextContent('3 stock updates failed while the location was off')
    await userEvent.click(screen.getByRole('button', { name: 'Re-send failed updates' }))
    await screen.findByText('Sent 3 of 3 updates to Shopify.')
    expect(api.resendShopifyLocationFailures).toHaveBeenCalledTimes(1)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  test('ls4b — a manager never sees the re-send; an active location with nothing to re-send shows nothing', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('manager')
    card(shopify({ resendableFailed: 3 }))
    await screen.findByText('broek.myshopify.com')
    expect(screen.queryByTestId('shopify-location-resend')).toBeNull()
    expect(screen.queryByTestId('shopify-location-notice')).toBeNull()
  })

  test('ls5 — Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderWithProviders(
      <I18nextProvider i18n={i18n}>
        <ShopifyConnectionCard shopify={shopify({ locationState: 'inactive' })} customAppAvailable={false}
          oauthAvailable={false} shopifySetup={SETUP} reload={vi.fn(async () => {})} />
      </I18nextProvider>)

    expect(await screen.findByText('موقع Traced في شوبيفاي غير مُفعَّل')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'أعدت تفعيله — تحقّق الآن' })).toBeInTheDocument()
  })
})
