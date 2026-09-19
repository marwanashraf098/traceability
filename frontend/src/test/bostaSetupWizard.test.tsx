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
    bostaConnect:       vi.fn(),
    bostaGetSyncStatus: vi.fn(),
    listLocations:      vi.fn(),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const API_KEY = 'bosta-test-api-key'
const WEBHOOK_SECRET = 'a'.repeat(64)

const SHOPIFY_SETUP_FIXTURE = {
  appUrl: 'http://localhost:5173',
  redirectUrl: 'http://localhost:8080/auth/shopify/callback',
  webhookApiVersion: '2026-04',
  scopes: ['read_products', 'read_orders'],
}

function disconnectedFixture(): api.ConnectionsStatus {
  return {
    shopify: {
      connected: false, storeId: null, shopDomain: null,
      connectionType: null, status: 'disconnected',
      importStatus: null, lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false,
    oauthAvailable: false,
    shopifySetup: SHOPIFY_SETUP_FIXTURE,
  }
}

function connectedFixture(): api.ConnectionsStatus {
  return {
    shopify: disconnectedFixture().shopify,
    bosta: { connected: true, businessName: 'Acme Logistics', pickupMode: 'TRACED_MANAGED', awbFormat: 'A4', awbLang: 'en' },
    customAppAvailable: false,
    oauthAvailable: false,
    shopifySetup: SHOPIFY_SETUP_FIXTURE,
  }
}

// Drives the card from "disconnected" into the wizard (step 1).
async function openWizard(user: ReturnType<typeof userEvent.setup>) {
  const cta = await screen.findByRole('button', { name: 'Connect Bosta' })
  await user.click(cta)
  await screen.findByTestId('bosta-setup-wizard')
}

// From step 1, clicks Next through to step 5 (the API-key form).
async function advanceToStep5(user: ReturnType<typeof userEvent.setup>) {
  for (let i = 0; i < 4; i++) {
    const next = await screen.findByRole('button', { name: 'Next' })
    await user.click(next)
  }
  await screen.findByLabelText('API key')
}

describe('BostaSetupWizard — navigation and connect flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.bostaGetSyncStatus).mockResolvedValue({ lastBackfillAt: null, lastBackfillTotal: 0, lastBackfillEnqueued: 0 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('bw1 — Next advances through all 5 steps; Back retreats; Back on step 1 exits to the disconnected entry', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(disconnectedFixture())
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await openWizard(user)
    await screen.findByText('Open Applications in Bosta')

    await advanceToStep5(user)
    await screen.findByText('Copy your API key')

    // Back four times returns to step 1.
    for (let i = 0; i < 4; i++) {
      await user.click(screen.getByRole('button', { name: 'Back' }))
    }
    await screen.findByText('Open Applications in Bosta')

    // Back once more (from step 1) exits the wizard entirely.
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await screen.findByRole('button', { name: 'Connect Bosta' })
    expect(screen.queryByTestId('bosta-setup-wizard')).toBeNull()
  })

  test('bw2 — a successful connect at step 5 surfaces the webhook reveal panel (URL, static header name, Bearer value)', async () => {
    vi.mocked(api.getConnections)
      .mockResolvedValueOnce(disconnectedFixture())
      .mockResolvedValueOnce(connectedFixture())
    vi.mocked(api.bostaConnect).mockResolvedValueOnce({ accountId: 'acct-1', webhookSecret: WEBHOOK_SECRET })

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await openWizard(user)
    await advanceToStep5(user)

    await user.type(screen.getByLabelText('API key'), API_KEY)
    await user.click(screen.getByRole('button', { name: 'Connect Bosta' }))

    await waitFor(() => expect(api.bostaConnect).toHaveBeenCalledWith(API_KEY))

    // Webhook reveal panel — the wizard is gone, the reveal is showing.
    await screen.findByText("Save this now — it won't be shown again.")
    expect(screen.queryByTestId('bosta-setup-wizard')).toBeNull()

    // The three copy rows: webhook URL, the static "Authorization" header name
    // (not a per-store secret), and the Bearer value.
    expect(screen.getByDisplayValue(`${window.location.origin}/api/v1/webhooks/bosta`)).toBeInTheDocument()
    expect(screen.getByDisplayValue('Authorization')).toBeInTheDocument()
    expect(screen.getByDisplayValue(`Bearer ${WEBHOOK_SECRET}`)).toBeInTheDocument()

    // Done dismisses the reveal, showing the connected view underneath.
    await user.click(screen.getByRole('button', { name: "Done, I've saved it" }))
    await screen.findByText('Acme Logistics')
  })

  test('bw3 — a failed connect at step 5 shows an inline error, keeps the wizard mounted, and preserves the typed key', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(disconnectedFixture())
    vi.mocked(api.bostaConnect).mockRejectedValueOnce(new Error('401: Unauthorized'))

    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    await openWizard(user)
    await advanceToStep5(user)

    await user.type(screen.getByLabelText('API key'), API_KEY)
    await user.click(screen.getByRole('button', { name: 'Connect Bosta' }))

    await waitFor(() => expect(api.bostaConnect).toHaveBeenCalledTimes(1))
    await screen.findByRole('alert')

    expect(screen.getByTestId('bosta-setup-wizard')).toBeInTheDocument()
    expect((screen.getByLabelText('API key') as HTMLInputElement).value).toBe(API_KEY)
  })

  test('bw4 — Reconnect from the connected panel reopens the wizard; backing out returns to the connected panel (not disconnected)', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(connectedFixture())
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)

    const reconnectBtn = await screen.findByRole('button', { name: 'Reconnect' })
    await user.click(reconnectBtn)
    await screen.findByTestId('bosta-setup-wizard')

    await user.click(screen.getByRole('button', { name: 'Back' }))

    // Still connected — never fell back to the disconnected entry.
    await screen.findByText('Acme Logistics')
    expect(screen.queryByRole('button', { name: 'Connect Bosta' })).toBeNull()
  })
})
