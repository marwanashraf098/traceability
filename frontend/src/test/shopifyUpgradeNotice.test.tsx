import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import ConnectionsTab from '../pages/settings/ConnectionsTab'
import * as api from '../api'
import { setAccessToken, clearAccessToken } from '../auth'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getConnections: vi.fn(), listLocations: vi.fn() }
})

// The app-wide Shopify upgrade notice (Layout, from /me's shopifyUpgradeStage):
//   u1 owner, 'upgrade' → warning banner + "Upgrade now" → /settings?tab=connections&focus=upgrade
//   u2 manager, 'upgrade' → same text, no button, "Ask your account owner to upgrade."
//   u3 owner, 'delete_old_app' → urgent banner, steps collapsed until opened, "I've deleted it" → POST confirm
//      once → /me reloaded → banner gone
//   u4 manager, 'delete_old_app' → no button, ask-the-owner line
//   u5 /me {} (the shell default) or a worker's null stage → nothing renders
//   u6 never on /pickups or /transfers/:id/reconcile (in-shell scan loops); still on /transfers/:id
//   u7 ?focus=upgrade marks the Connections card's own upgrade banner; without it, unmarked

type Stage = api.ShopifyUpgradeStage

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
}

function ok(body: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400, status,
    headers: { get: (k: string) => (k === 'content-type' && status !== 204 ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function me(role: 'owner' | 'manager' | 'worker', stage: Stage) {
  return { name: 'Some One', email: 'x@y.z', role, shopifyUpgradeStage: stage }
}

function renderShell(path = '/orders') {
  return renderWithProviders(<><Layout><p>page</p></Layout><LocationProbe /></>, { initialEntries: [path] })
}

const banner = () => screen.queryByTestId('shopify-upgrade-banner')

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearAccessToken()
})

describe('app-wide upgrade notice — Layout', () => {
  function shell(role: 'owner' | 'manager' | 'worker', stage: Stage) {
    setAccessToken(fakeJwt(role))
    stubFetchWithShellDefaults(() => ok({}), { me: me(role, stage) })
  }

  test('u1 — owner, upgrade stage: banner + "Upgrade now" goes to the focused Connections card', async () => {
    shell('owner', 'upgrade')
    renderShell()
    const b = await screen.findByTestId('shopify-upgrade-banner')
    expect(b).toHaveAttribute('data-stage', 'upgrade')
    expect(within(b).getByText('Action required: upgrade to the official Traced Shopify app.')).toBeInTheDocument()
    expect(within(b).getByText('Your early-access connection will be retired.')).toBeInTheDocument()
    expect(within(b).queryByText(/Delete your old custom app/)).not.toBeInTheDocument()
    await userEvent.click(within(b).getByRole('button', { name: 'Upgrade now' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/settings?tab=connections&focus=upgrade')
  })

  test('u2 — manager, upgrade stage: no button, ask the owner', async () => {
    shell('manager', 'upgrade')
    renderShell()
    const b = await screen.findByTestId('shopify-upgrade-banner')
    expect(within(b).getByText('Action required: upgrade to the official Traced Shopify app.')).toBeInTheDocument()
    expect(within(b).getByText('Ask your account owner to upgrade.')).toBeInTheDocument()
    expect(within(b).queryByRole('button')).not.toBeInTheDocument()
  })

  test('u3 — owner, delete stage: steps open on demand; "I\'ve deleted it" confirms once and the banner goes', async () => {
    setAccessToken(fakeJwt('owner'))
    let stage: Stage = 'delete_old_app'
    const posts: string[] = []
    stubFetchWithShellDefaults((url: string, opts?: RequestInit) => {
      if (url.includes('/shopify/custom-app-deletion/confirm')) {
        posts.push(opts?.method ?? 'GET')
        stage = null
        return ok(null, 204)
      }
      return ok({})
    })
    // /me changes after the confirmation — layered over the shell stub, which never sees /me then.
    const shellFetch = globalThis.fetch
    vi.stubGlobal('fetch', (url: string, opts?: RequestInit) =>
      typeof url === 'string' && url.endsWith('/me') ? ok(me('owner', stage)) : shellFetch(url, opts))

    renderShell()
    const b = await screen.findByTestId('shopify-upgrade-banner')
    expect(b).toHaveAttribute('data-stage', 'delete_old_app')
    expect(within(b).getByText('Upgrade complete. Delete your old custom app now.')).toBeInTheDocument()
    expect(within(b).getByText('It can still access your store data.')).toBeInTheDocument()
    expect(within(b).queryByText(/Action required/)).not.toBeInTheDocument()

    expect(within(b).queryByText('Delete the app.')).not.toBeInTheDocument()
    await userEvent.click(within(b).getByRole('button', { name: 'How to delete it' }))
    expect(within(b).getAllByRole('listitem').map(li => li.textContent)).toEqual([
      'Open your Shopify Dev Dashboard.',
      'Go to Apps and open your early-access Traced app.',
      'Delete the app.',
    ])

    await userEvent.click(within(b).getByRole('button', { name: "I've deleted it" }))
    await waitFor(() => expect(banner()).not.toBeInTheDocument())
    expect(posts).toEqual(['POST'])
  })

  test('u4 — manager, delete stage: no button, ask the owner to delete and confirm', async () => {
    shell('manager', 'delete_old_app')
    renderShell()
    const b = await screen.findByTestId('shopify-upgrade-banner')
    expect(within(b).getByText('Ask your account owner to delete it and confirm here.')).toBeInTheDocument()
    expect(within(b).queryByRole('button', { name: "I've deleted it" })).not.toBeInTheDocument()
  })

  test('u5 — the shell default /me ({}) and a worker render nothing', async () => {
    setAccessToken(fakeJwt('owner'))
    stubFetchWithShellDefaults(() => ok({}))
    const { unmount } = renderShell()
    await screen.findByText('page')
    await new Promise(r => setTimeout(r, 20))
    expect(banner()).not.toBeInTheDocument()
    unmount()

    shell('worker', null)
    renderShell('/worker-home')
    await waitFor(() => expect(screen.getAllByText('Some One').length).toBeGreaterThan(0))
    expect(banner()).not.toBeInTheDocument()
  })

  test('u6 — never on /pickups or a transfer reconcile scan; still on the transfer page itself', async () => {
    shell('owner', 'delete_old_app')
    for (const path of ['/pickups', '/transfers/t-1/reconcile']) {
      const { unmount } = renderShell(path)
      await waitFor(() => expect(screen.getAllByText('Some One').length).toBeGreaterThan(0))
      expect(banner()).not.toBeInTheDocument()
      unmount()
    }
    renderShell('/transfers/t-1')
    expect(await screen.findByTestId('shopify-upgrade-banner')).toBeInTheDocument()
  })
})

describe('Connections card — ?focus=upgrade', () => {
  function fixture(): api.ConnectionsStatus {
    return {
      shopify: {
        connected: true, storeId: 'store-1', shopDomain: 'custom-shop.myshopify.com', connectionType: 'custom_app_cc',
        status: 'connected', importStatus: 'completed', lastSyncAt: null,
      },
      bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null, simulated: false },
      customAppAvailable: false,
      oauthAvailable: true,
      shopifySetup: { appUrl: 'http://localhost:5173', redirectUrl: 'http://localhost:8080/auth/shopify/callback',
                      webhookApiVersion: '2026-04', scopes: ['read_orders'] },
    }
  }

  beforeEach(() => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture())
    vi.mocked(api.listLocations).mockResolvedValue([])
  })

  test('u7 — the card\'s own upgrade banner is marked only when the notice sent you', async () => {
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    const first = renderWithProviders(<ConnectionsTab readOnly={false} />,
      { initialEntries: ['/settings?tab=connections&focus=upgrade'] })
    const marked = await screen.findByTestId('shopify-card-upgrade-banner')
    expect(marked).toHaveAttribute('data-highlighted', 'true')
    expect(scroll).toHaveBeenCalled()
    first.unmount()

    renderWithProviders(<ConnectionsTab readOnly={false} />, { initialEntries: ['/settings?tab=connections'] })
    const plain = await screen.findByTestId('shopify-card-upgrade-banner')
    expect(plain).not.toHaveAttribute('data-highlighted')
  })
})
