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
    getConnections:       vi.fn(),
    shopifyCustomConnect: vi.fn(),
    listLocations:        vi.fn(),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const SHOP_DOMAIN    = 'wizard-test.myshopify.com'
const CLIENT_ID      = 'wizard-test-client-id'
const CLIENT_SECRET  = 'wizard-test-client-secret'

function disconnectedFixture(): api.ConnectionsStatus {
  return {
    shopify: {
      connected: false, storeId: null, shopDomain: null,
      connectionType: null, status: 'disconnected',
      importStatus: null, lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: true,
    oauthAvailable: false,
    shopifySetup: {
      appUrl: 'https://app.tracedtech.com',
      redirectUrl: 'https://app.tracedtech.com/auth/shopify/callback',
      webhookApiVersion: '2026-04',
      scopes: ['read_products', 'read_orders'],
    },
  }
}

function connectedEaFixture(): api.ConnectionsStatus {
  return {
    shopify: {
      connected: true, storeId: 'store-1', shopDomain: SHOP_DOMAIN,
      connectionType: 'custom_app_cc', status: 'connected',
      importStatus: 'pending', lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: true,
    oauthAvailable: false,
    shopifySetup: disconnectedFixture().shopifySetup,
  }
}

// Drives the card from "disconnected" to the wizard's step 10 connect form.
async function openWizardAtStep10(user: ReturnType<typeof userEvent.setup>) {
  const merchantCta = await screen.findByRole('button', { name: 'Connect my store' })
  await user.click(merchantCta)

  const selfCta = await screen.findByRole('button', { name: /Set it up myself/ })
  await user.click(selfCta)

  for (let i = 0; i < 9; i++) {
    const next = await screen.findByRole('button', { name: 'Next' })
    await user.click(next)
  }

  await screen.findByLabelText('Store domain')
}

describe('SetupWizard — step 10 input preservation on failed submit', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('failed submit keeps domain/clientId/clientSecret and shows the error; retry succeeds without retyping', async () => {
    vi.mocked(api.getConnections)
      .mockResolvedValueOnce(disconnectedFixture())
      .mockResolvedValueOnce(connectedEaFixture())
    vi.mocked(api.shopifyCustomConnect)
      .mockRejectedValueOnce(new Error('500: Internal Server Error'))
      .mockResolvedValueOnce({ storeId: 'store-1', importStatus: 'pending' })

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await openWizardAtStep10(user)

    await user.type(screen.getByLabelText('Store domain'), SHOP_DOMAIN)
    await user.type(screen.getByLabelText('Client ID'), CLIENT_ID)
    await user.type(screen.getByLabelText('Client Secret'), CLIENT_SECRET)

    await user.click(screen.getByRole('button', { name: 'Connect' }))

    // Transient "connecting" panel, then back to step 10 with the error shown.
    await waitFor(() => expect(api.shopifyCustomConnect).toHaveBeenCalledTimes(1))
    await screen.findByRole('alert')

    // The load-bearing assertions: nothing was cleared.
    const domainInput = await screen.findByLabelText('Store domain') as HTMLInputElement
    const clientIdInput = screen.getByLabelText('Client ID') as HTMLInputElement
    const clientSecretInput = screen.getByLabelText('Client Secret') as HTMLInputElement
    expect(domainInput.value).toBe(SHOP_DOMAIN)
    expect(clientIdInput.value).toBe(CLIENT_ID)
    expect(clientSecretInput.value).toBe(CLIENT_SECRET)

    // Retry without retyping anything — succeeds this time.
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    await waitFor(() => expect(api.shopifyCustomConnect).toHaveBeenCalledTimes(2))
    expect(api.shopifyCustomConnect).toHaveBeenNthCalledWith(2, SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET)

    // Card moved on to the connected state — the wizard is gone.
    await waitFor(() => expect(screen.queryByLabelText('Store domain')).toBeNull())
  })

  test('leaving the wizard (Back to choose) clears the fields — reopening starts blank', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(disconnectedFixture())

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await openWizardAtStep10(user)
    await user.type(screen.getByLabelText('Store domain'), SHOP_DOMAIN)

    // Back nine times returns to step 1, a tenth returns to "choose".
    for (let i = 0; i < 10; i++) {
      const back = screen.getByRole('button', { name: 'Back' })
      await user.click(back)
    }

    await screen.findByRole('button', { name: /Set it up myself/ })

    // Re-enter the wizard fresh — the domain field must be blank again.
    const selfCta = await screen.findByRole('button', { name: /Set it up myself/ })
    await user.click(selfCta)
    for (let i = 0; i < 9; i++) {
      const next = await screen.findByRole('button', { name: 'Next' })
      await user.click(next)
    }
    const domainInput = await screen.findByLabelText('Store domain') as HTMLInputElement
    expect(domainInput.value).toBe('')
  })
})
