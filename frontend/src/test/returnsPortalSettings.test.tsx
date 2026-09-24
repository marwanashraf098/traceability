import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { fireEvent, renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes } from 'react-router-dom'
import SettingsPage from '../pages/settings/SettingsPage'
import type { PortalSettings, PortalVariantRow } from '../api'

// Returns portal Step 4e-A — Settings → Returns portal (M4 + Branding). Fakes match the
// real response shapes of PortalSettingsService (GET/PUT, 400/409 {field, error, message})
// and GET /variants ({items, total}).

let role: 'owner' | 'manager' | 'worker' = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

const LOGO = 'https://cdn.shopify.com/s/files/1/0001/logo.png'

let settings: PortalSettings
let variants: PortalVariantRow[]
let putResponse: { status: number; body: unknown } | null
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.includes('/tenant/portal-settings') && method === 'PUT') {
    if (putResponse) return fakeResponse(putResponse.body, putResponse.status)
    settings = { ...settings, ...body, slug: body.slug ? String(body.slug).toLowerCase() : null }
    return fakeResponse(settings)
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(settings)
  if (url.includes('/non-returnable') && method === 'PUT') {
    const id = url.split('/variants/')[1].split('/')[0]
    variants = variants.map(v => (v.id === id ? { ...v, nonReturnable: body.value } : v))
    return fakeResponse(null, 204)
  }
  if (url.includes('/variants?') && method === 'GET') {
    const q = (new URL(url, 'http://x').searchParams.get('search') ?? '').toLowerCase()
    const items = variants.filter(v => !q || [v.productTitle, v.variantTitle, v.sku].some(s => s?.toLowerCase().includes(q)))
    return fakeResponse({ items, total: items.length })
  }
  return fakeResponse({})
}

beforeEach(() => {
  role = 'owner'
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30,
    logoUrl: null, brandColor: null, policyText: null, pickupBooking: false,
  }
  variants = [
    { id: 'v-pants', productTitle: 'Flipped Pants', variantTitle: 'Black / XL', sku: '1001-Black-XL', nonReturnable: false },
    { id: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'White / M', sku: '2004-White-M', nonReturnable: false },
    { id: 'v-scarf', productTitle: 'Silk Scarf', variantTitle: 'Sand', sku: '3002-Sand', nonReturnable: true },
  ]
  putResponse = null
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

function renderSettings(tab = 'portal') {
  return renderWithProviders(
    <Routes>
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/overview" element={<p>overview page</p>} />
    </Routes>,
    { initialEntries: [`/settings?tab=${tab}`] },
  )
}

async function loaded() {
  const user = userEvent.setup()
  renderSettings()
  await screen.findByDisplayValue('nourstudio')
  return user
}

function lastPut() {
  return [...calls].reverse().find(c => c.method === 'PUT' && c.url.includes('/tenant/portal-settings'))
}

describe('Settings → Returns portal', () => {
  test('loads the saved settings, shows the full portal link, switches are real switches', async () => {
    await loaded()
    expect(screen.getByTestId('portal-url')).toHaveTextContent('https://returns.tracedtech.com/nourstudio')
    expect(screen.getByLabelText('Return window')).toHaveValue(30)
    const enabled = screen.getByRole('switch', { name: 'Customers can start returns' })
    expect(enabled).toHaveAttribute('aria-checked', 'true')
    expect(enabled.tagName).toBe('BUTTON')
    expect(screen.getByRole('switch', { name: 'Approve requests automatically' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  test('edit and save sends one PUT with every field, including branding', async () => {
    const user = await loaded()
    await user.click(screen.getByRole('switch', { name: 'Approve requests automatically' }))
    const window = screen.getByLabelText('Return window')
    await user.clear(window)
    await user.type(window, '14')
    await user.type(screen.getByLabelText('Logo link'), LOGO)
    await user.type(screen.getByLabelText('Brand colour'), '#1A2B3C')
    await user.type(screen.getByLabelText('Return policy'), 'Unworn items only.')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(lastPut()).toBeDefined())
    expect(lastPut()!.body).toEqual({
      slug: 'nourstudio', enabled: true, autoApprove: true, returnWindowDays: 14,
      logoUrl: LOGO, brandColor: '#1A2B3C', policyText: 'Unworn items only.',
    })
    expect(await screen.findByText('Returns portal settings saved')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled())
  })

  test('policy text counter', async () => {
    const user = await loaded()
    expect(screen.getByText('0 / 2000')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Return policy'), 'Hello')
    expect(screen.getByText('5 / 2000')).toBeInTheDocument()
  })

  test('a field-level 400 shows next to that field', async () => {
    putResponse = { status: 400, body: { field: 'logoUrl', error: 'LOGO_URL', message: 'x' } }
    const user = await loaded()
    await user.type(screen.getByLabelText('Logo link'), 'https://example.com/logo.png')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByTestId('error-logoUrl')).toHaveTextContent('Paste a link that starts with https://cdn.shopify.com/')
    expect(screen.getByLabelText('Logo link')).toHaveAttribute('aria-invalid', 'true')
  })

  test('a 400 on the return window shows under the window field', async () => {
    putResponse = { status: 400, body: { field: 'returnWindowDays', error: 'WINDOW_RANGE', message: 'x' } }
    const user = await loaded()
    const window = screen.getByLabelText('Return window')
    await user.clear(window)
    await user.type(window, '120')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByTestId('error-returnWindowDays')).toHaveTextContent('Enter a number of days from 1 to 90.')
  })

  test('a 409 on the slug reads "That address is already taken."', async () => {
    putResponse = { status: 409, body: { field: 'slug', error: 'SLUG_TAKEN', message: 'That address is already taken.' } }
    const user = await loaded()
    const slug = screen.getByLabelText('Portal link')
    await user.clear(slug)
    await user.type(slug, 'jumi')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByTestId('error-slug')).toHaveTextContent('That address is already taken.')
  })

  test('Copy link copies the full https link', async () => {
    const user = await loaded()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await user.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(writeText).toHaveBeenCalledWith('https://returns.tracedtech.com/nourstudio')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  test('logo preview only for a valid Shopify CDN link', async () => {
    const user = await loaded()
    const logo = screen.getByLabelText('Logo link')
    await user.type(logo, 'https://example.com/logo.png')
    expect(screen.queryByTestId('logo-preview')).not.toBeInTheDocument()
    await user.clear(logo)
    await user.type(logo, 'http://cdn.shopify.com/logo.png')
    expect(screen.queryByTestId('logo-preview')).not.toBeInTheDocument()
    await user.clear(logo)
    await user.type(logo, LOGO)
    expect(screen.getByTestId('logo-preview')).toHaveAttribute('src', LOGO)
  })

  test('colour picker fills the hex field', async () => {
    await loaded()
    // userEvent can't drive a native colour dialog — fire the change the picker would.
    fireEvent.change(screen.getByLabelText('Pick a brand colour'), { target: { value: '#aabbcc' } })
    expect(screen.getByLabelText('Brand colour')).toHaveValue('#AABBCC')
  })

  test('non-returnable list: search, and a switch saves immediately', async () => {
    const user = await loaded()
    const list = await screen.findByTestId('variant-list')
    expect(within(list).getAllByTestId('variant-row')).toHaveLength(3)
    const scarf = screen.getByRole('switch', { name: 'Silk Scarf, Sand can\'t be returned' })
    expect(scarf).toHaveAttribute('aria-checked', 'true')

    const shirt = screen.getByRole('switch', { name: 'Linen Shirt, White / M can\'t be returned' })
    await user.click(shirt)
    await waitFor(() => {
      const call = calls.find(c => c.method === 'PUT' && c.url.endsWith('/variants/v-shirt/non-returnable'))
      expect(call?.body).toEqual({ value: true })
    })
    await waitFor(() => expect(shirt).toHaveAttribute('aria-checked', 'true'))

    await user.type(screen.getByLabelText('Search products'), 'scarf')
    await waitFor(() => expect(calls.some(c => c.url.includes('/variants?') && c.url.includes('search=scarf'))).toBe(true))
    await waitFor(() => expect(within(screen.getByTestId('variant-list')).getAllByTestId('variant-row')).toHaveLength(1))
    // Not part of the form — Save stays disabled.
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  test('manager sees the Returns portal tab', async () => {
    role = 'manager'
    renderSettings()
    expect(await screen.findByDisplayValue('nourstudio')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Returns portal' })).toBeInTheDocument()
  })

  test('worker never sees Returns portal settings', async () => {
    role = 'worker'
    renderSettings()
    expect(await screen.findByText('overview page')).toBeInTheDocument()
    expect(screen.queryByTestId('returns-portal-settings')).not.toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/tenant/portal-settings'))).toBe(false)
  })
})
