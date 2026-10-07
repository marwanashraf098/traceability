import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ConnectionsTab from '../pages/settings/ConnectionsTab'
import { recogniseStoreAddress, looksLikeDomain } from '../pages/settings/connections/storeAddress'

// "Find your store" (design/Traced_shopify_connect_wizard_dc.html):
//   r  the frontend recognition preview (the backend ShopDomainNormalizer is the truth)
//   c  new tenant card → recognition → resolve → confirm → "That's not my store" / connect
//   e  errors: not an address, not a Shopify address, store not found, backend message as-is
//   k  reconnect (linked shop): no input; store-not-found
//   g  the "Where do I find this?" guide: 3 steps + "On your phone?"

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getConnections: vi.fn(), shopifyInitiate: vi.fn(), shopifyResolveStore: vi.fn(), listLocations: vi.fn(),
  }
})

const PH = 'yourstore.myshopify.com or your Shopify admin link'

function fixture(shopDomain: string | null): api.ConnectionsStatus {
  return {
    shopify: {
      connected: false, storeId: shopDomain ? 'store-1' : null, shopDomain,
      connectionType: shopDomain ? 'custom_app_cc' : null, status: 'disconnected', importStatus: null, lastSyncAt: null,
    },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null, simulated: false },
    customAppAvailable: false,
    oauthAvailable: false,
    shopifySetup: { appUrl: 'http://localhost:5173', redirectUrl: 'http://localhost:8080/auth/shopify/callback',
                    webhookApiVersion: '2026-04', scopes: ['read_orders'] },
  }
}

function cmdError(code: string, en = 'backend message', ar = 'رسالة') {
  return new api.TransferCommandError({ code, message_en: en, message_ar: ar })
}

describe('storeAddress — recognition preview', () => {
  test('r1: .myshopify.com addresses and admin links, with capitals, scheme, path and invisible characters', () => {
    expect(recogniseStoreAddress('https://ABC123.myshopify.com/')).toEqual({ shopDomain: 'abc123.myshopify.com', source: 'myshopify' })
    expect(recogniseStoreAddress('​abc123.my‏shopify.com ')).toEqual({ shopDomain: 'abc123.myshopify.com', source: 'myshopify' })
    expect(recogniseStoreAddress('admin.shopify.com/store/abc123/orders?x=1')).toEqual({ shopDomain: 'abc123.myshopify.com', source: 'admin_link' })
    expect(recogniseStoreAddress('abc123.myshopify.com/admin/orders')).toEqual({ shopDomain: 'abc123.myshopify.com', source: 'admin_link' })
  })
  test('r2: anything else is not recognised', () => {
    for (const s of ['', 'my store abc', 'thesnouts.com', 'abc123.myshopify.com.evil.com', 'admin.shopify.com/settings',
                     'user@abc123.myshopify.com', 'abc123.myshopify.com:8443']) {
      expect(recogniseStoreAddress(s)).toBeNull()
    }
    expect(looksLikeDomain('thesnouts.com')).toBe(true)
    expect(looksLikeDomain('my store abc')).toBe(false)
  })
})

describe('Shopify card — find your store', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listLocations).mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  test('c1: a never-connected tenant gets "Connect your Shopify store", the input and the format examples', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    expect(await screen.findByText('Connect your Shopify store')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(PH)).toBeInTheDocument()
    expect(screen.getByText('admin.shopify.com/store/abc123')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Find my store' })).toBeDisabled()
    expect(screen.queryByTestId('shopify-reconnect')).toBeNull()
  })

  test('c2: live recognition while typing', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await user.type(await screen.findByPlaceholderText(PH), 'https://ABC123.myshopify.com/')
    const rec = screen.getByTestId('store-finder-recognised')
    expect(rec.textContent).toContain('Store address')
    expect(rec.textContent).toContain('abc123.myshopify.com')

    await user.clear(screen.getByPlaceholderText(PH))
    await user.type(screen.getByPlaceholderText(PH), 'admin.shopify.com/store/abc123/orders')
    expect(screen.getByTestId('store-finder-recognised').textContent).toContain('Shopify admin link')
  })

  test('c3: Find → resolve → confirm; "That\'s not my store" returns with the text kept; Connect → initiate', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    vi.mocked(api.shopifyResolveStore).mockResolvedValue({ shopDomain: 'abc123.myshopify.com', source: 'admin_link' })
    vi.mocked(api.shopifyInitiate).mockReturnValue(new Promise(() => {}))   // stays "Opening Shopify…"
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await user.type(await screen.findByPlaceholderText(PH), 'admin.shopify.com/store/abc123')
    await user.click(screen.getByRole('button', { name: 'Find my store' }))

    expect(api.shopifyResolveStore).toHaveBeenCalledWith('admin.shopify.com/store/abc123')
    expect(await screen.findByText('We found your store')).toBeInTheDocument()
    expect(screen.getByTestId('store-finder-found').textContent).toBe('abc123.myshopify.com')
    expect(screen.getByText('From your Shopify admin link')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: "That's not my store" }))
    expect(screen.getByPlaceholderText(PH)).toHaveValue('admin.shopify.com/store/abc123')

    await user.click(screen.getByRole('button', { name: 'Find my store' }))
    await user.click(await screen.findByRole('button', { name: /Connect this store/ }))
    expect(api.shopifyInitiate).toHaveBeenCalledWith('abc123.myshopify.com')
    expect(await screen.findByText('Opening Shopify…')).toBeInTheDocument()
  })

  test('e1: free text → "doesn\'t look like a store address"; e2: another website → "not a Shopify address"', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    vi.mocked(api.shopifyResolveStore).mockRejectedValue(cmdError('NOT_SHOPIFY_ADDRESS'))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    const input = await screen.findByPlaceholderText(PH)

    await user.type(input, 'my store abc')
    await user.click(screen.getByRole('button', { name: 'Find my store' }))
    expect(await screen.findByText("That doesn't look like a store address.")).toBeInTheDocument()

    await user.clear(input)
    await user.type(input, 'thesnouts.com')
    await user.click(screen.getByRole('button', { name: 'Find my store' }))
    expect(await screen.findByText("That's not a Shopify address.")).toBeInTheDocument()
    expect(screen.getByText('Paste the link from your Shopify admin.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Where do I find this?' }).length).toBeGreaterThan(0)
  })

  test('e3: store not found → "We couldn\'t find a store called …"; e4: other backend errors shown as-is', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    vi.mocked(api.shopifyResolveStore).mockResolvedValue({ shopDomain: 'abc132.myshopify.com', source: 'myshopify' })
    vi.mocked(api.shopifyInitiate)
      .mockRejectedValueOnce(cmdError('STORE_NOT_FOUND'))
      .mockRejectedValueOnce(cmdError('SHOPIFY_SHOP_MISMATCH', 'This account is linked to j90uuk-yz.myshopify.com. It can only reconnect that store.'))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await user.type(await screen.findByPlaceholderText(PH), 'abc132.myshopify.com')
    await user.click(screen.getByRole('button', { name: 'Find my store' }))
    await user.click(await screen.findByRole('button', { name: /Connect this store/ }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain("We couldn't find a store called abc132.myshopify.com.")
    expect(alert.textContent).toContain('Check the spelling.')
    expect(screen.getByRole('button', { name: 'Change the address' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Connect this store/ }))
    await waitFor(() => expect(screen.getByRole('alert').textContent)
      .toContain('This account is linked to j90uuk-yz.myshopify.com'))
  })

  test('k1: reconnect (linked shop) — no input, one button; store not found shows the not-found message', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture('j90uuk-yz.myshopify.com'))
    vi.mocked(api.shopifyInitiate).mockRejectedValue(cmdError('STORE_NOT_FOUND'))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await screen.findByTestId('shopify-reconnect')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByText('Connect your Shopify store')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Connect with Shopify' }))
    expect(api.shopifyInitiate).toHaveBeenCalledWith('j90uuk-yz.myshopify.com')
    expect((await screen.findByRole('alert')).textContent)
      .toContain("We couldn't find a store called j90uuk-yz.myshopify.com.")
  })

  test('g1: the guide — 3 steps, back to the card, and step 3 resolves what was pasted', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    vi.mocked(api.shopifyResolveStore).mockResolvedValue({ shopDomain: 'abc123.myshopify.com', source: 'admin_link' })
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await user.click(await screen.findByRole('button', { name: 'Where do I find this?' }))

    expect(screen.getByText('Find your store address')).toBeInTheDocument()
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('Open your Shopify admin')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open admin\.shopify\.com/ })).toHaveAttribute('href', 'https://admin.shopify.com')

    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(await screen.findByText('Connect your Shopify store')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Where do I find this?' }))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
    expect(screen.getByText('Copy the address bar')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
    await user.type(screen.getByRole('textbox'), 'admin.shopify.com/store/abc123/orders')
    await user.click(screen.getByRole('button', { name: 'Find my store' }))

    expect(api.shopifyResolveStore).toHaveBeenCalledWith('admin.shopify.com/store/abc123/orders')
    expect(await screen.findByText('We found your store')).toBeInTheDocument()
  })

  test('g2: "On your phone?" — Settings › Domains, then "I have it" goes to step 3', async () => {
    vi.mocked(api.getConnections).mockResolvedValue(fixture(null))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionsTab readOnly={false} />)
    await user.click(await screen.findByRole('button', { name: 'Where do I find this?' }))
    await user.click(screen.getByRole('button', { name: 'On your phone?' }))

    expect(screen.getByTestId('store-finder-guide-phone')).toBeInTheDocument()
    expect(screen.getByText('Open Settings › Domains in Shopify')).toBeInTheDocument()
    expect(screen.getByText(/Your store address ends in \.myshopify\.com/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'I have it' }))
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
  })
})
