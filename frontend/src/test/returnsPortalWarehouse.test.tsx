import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes } from 'react-router-dom'
import SettingsPage from '../pages/settings/SettingsPage'
import type { BostaReturnLocation, PortalSettings } from '../api'

// Returns portal Step 4c-2 — Settings → Returns portal → "Returns go back to". Fakes match
// ReturnsPortalAdminController: GET /tenant/bosta/return-locations → [{id,name,isDefault,cityName}]
// or {field, error, message} with 409 / 422 / 502; PUT /tenant/portal-settings accepts
// returnLocationId and answers 400 {field:'returnLocationId', error:'RETURN_LOCATION_UNKNOWN'}.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const LOCATIONS: BostaReturnLocation[] = [
  { id: '5f0a7def4a839b00139d6203', name: 'Original Business Location', isDefault: false, cityName: null },
  { id: 'yfWPU0tP2', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' },
]

let settings: PortalSettings
let locationsReply: { status: number; body: unknown }
let putReply: { status: number; body: unknown } | null
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
  if (url.includes('/tenant/bosta/return-locations')) return fakeResponse(locationsReply.body, locationsReply.status)
  if (url.includes('/tenant/portal-settings') && method === 'PUT') {
    if (putReply) return fakeResponse(putReply.body, putReply.status)
    const loc = LOCATIONS.find(l => l.id === body.returnLocationId)
    settings = { ...settings, ...body, returnLocationName: loc ? loc.name : settings.returnLocationName }
    return fakeResponse(settings)
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(settings)
  if (url.includes('/variants?')) return fakeResponse({ items: [], total: 0 })
  return fakeResponse({})
}

beforeEach(() => {
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
    policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
  }
  locationsReply = { status: 200, body: LOCATIONS }
  putReply = null
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

async function loaded() {
  const user = userEvent.setup()
  renderWithProviders(
    <Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
    { initialEntries: ['/settings?tab=portal'] },
  )
  await screen.findByDisplayValue('nourstudio')
  return user
}

function lastPut() {
  return [...calls].reverse().find(c => c.method === 'PUT' && c.url.includes('/tenant/portal-settings'))
}

describe('Settings → Returns go back to', () => {
  test('nothing saved → Bosta default preselected; saving sends returnLocationId', async () => {
    const user = await loaded()
    const select = await screen.findByLabelText('Returns go back to') as HTMLSelectElement
    await waitFor(() => expect(select).toHaveValue('yfWPU0tP2'))
    expect(within(select).getByRole('option', { name: 'Maadi Warehouse · Cairo (default)' })).toBeInTheDocument()
    expect(within(select).getByRole('option', { name: 'Original Business Location' })).toBeInTheDocument()

    const save = screen.getByRole('button', { name: 'Save changes' })
    expect(save).toBeEnabled()
    await user.selectOptions(select, '5f0a7def4a839b00139d6203')
    await user.click(save)
    await waitFor(() => expect(lastPut()?.body).toMatchObject({ returnLocationId: '5f0a7def4a839b00139d6203' }))
    await waitFor(() => expect(save).toBeDisabled())
  })

  test('a saved location is selected and the form is not dirty', async () => {
    settings = { ...settings, returnLocationId: '5f0a7def4a839b00139d6203', returnLocationName: 'Original Business Location' }
    await loaded()
    const select = await screen.findByLabelText('Returns go back to')
    await waitFor(() => expect(select).toHaveValue('5f0a7def4a839b00139d6203'))
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  test('Bosta refuses the key → the message, the saved location, and a retry', async () => {
    settings = { ...settings, returnLocationId: 'yfWPU0tP2', returnLocationName: 'Maadi Warehouse' }
    locationsReply = { status: 422, body: { field: 'returnLocationId', error: 'BOSTA_KEY_REFUSED', message: "Bosta didn't accept the connected API key for locations" } }
    const user = await loaded()
    expect(await screen.findByTestId('return-location-error')).toHaveTextContent("Bosta didn't accept the connected API key for locations")
    expect(screen.getByText('Saved: Maadi Warehouse')).toBeInTheDocument()
    expect(screen.queryByLabelText('Returns go back to')).not.toBeInTheDocument()

    locationsReply = { status: 200, body: LOCATIONS }
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.getByLabelText('Returns go back to')).toHaveValue('yfWPU0tP2'))
  })

  test('no Bosta account → says to connect Bosta, no retry', async () => {
    locationsReply = { status: 409, body: { field: 'returnLocationId', error: 'NO_BOSTA_ACCOUNT', message: 'Connect Bosta first.' } }
    await loaded()
    expect(await screen.findByTestId('return-location-error')).toHaveTextContent('Connect Bosta to choose where returns go.')
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  test('save rejects an unknown location → the error sits under the select', async () => {
    putReply = { status: 400, body: { field: 'returnLocationId', error: 'RETURN_LOCATION_UNKNOWN', message: 'x' } }
    const user = await loaded()
    await waitFor(() => expect(screen.getByLabelText('Returns go back to')).toHaveValue('yfWPU0tP2'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByTestId('error-returnLocationId'))
      .toHaveTextContent("That location isn't in your Bosta account. Choose another.")
  })
})
