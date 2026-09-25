import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes } from 'react-router-dom'
import SettingsPage from '../pages/settings/SettingsPage'
import type { PortalSettings } from '../api'

// Returns portal Step 4c-3 — Settings → "Book Bosta pickups when I approve". Fakes match
// PortalSettingsService: GET carries portalPickupBooking / bostaConnected / returnLocationId;
// PUT takes pickupBooking (only when it changed) and answers 409 {field:'pickupBooking', error}.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let settings: PortalSettings
let putReply: { status: number; body: unknown } | null
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.includes('/tenant/portal-settings') && method === 'PUT') {
    if (putReply) return fakeResponse(putReply.body, putReply.status)
    const on = body.pickupBooking ?? settings.portalPickupBooking
    settings = { ...settings, ...body, pickupBooking: on, portalPickupBooking: on }
    return fakeResponse(settings)
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(settings)
  if (url.includes('/tenant/bosta/return-locations')) {
    return fakeResponse([{ id: 'loc-1', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' }])
  }
  if (url.includes('/variants?')) return fakeResponse({ items: [], total: 0 })
  return fakeResponse({})
}

beforeEach(() => {
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
    policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: 'loc-1',
    returnLocationName: 'Maadi Warehouse', bostaConnected: true,
  }
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

const SWITCH = { name: 'Book Bosta pickups when I approve' }

function lastPut() {
  return [...calls].reverse().find(c => c.method === 'PUT' && c.url.includes('/tenant/portal-settings'))
}

describe('Settings → Book Bosta pickups when I approve', () => {
  test('Bosta not connected → disabled, says to connect Bosta', async () => {
    settings = { ...settings, bostaConnected: false, returnLocationId: null, returnLocationName: null }
    await loaded()
    expect(screen.getByRole('switch', SWITCH)).toBeDisabled()
    expect(screen.getByTestId('switch-note')).toHaveTextContent('Connect Bosta first.')
  })

  test('no saved return location → disabled, says to choose and save one', async () => {
    settings = { ...settings, returnLocationId: null, returnLocationName: null }
    await loaded()
    expect(screen.getByRole('switch', SWITCH)).toBeDisabled()
    expect(screen.getByTestId('switch-note')).toHaveTextContent('Choose where returns go back to and save first.')
  })

  test('connected with a saved location → enabled; turning it on sends pickupBooking true', async () => {
    const user = await loaded()
    const sw = screen.getByRole('switch', SWITCH)
    expect(sw).toBeEnabled()
    expect(sw).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText(/the pickup is booked as soon as the request is sent/)).toBeInTheDocument()
    await user.click(sw)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(lastPut()?.body).toMatchObject({ pickupBooking: true }))
    await waitFor(() => expect(screen.getByRole('switch', SWITCH)).toHaveAttribute('aria-checked', 'true'))
  })

  test('a save that does not touch the switch does not send pickupBooking', async () => {
    const user = await loaded()
    await user.click(screen.getByRole('switch', { name: 'Approve requests automatically' }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(lastPut()).toBeDefined())
    expect(lastPut()!.body).not.toHaveProperty('pickupBooking')
  })

  test('backend refuses (409) → the reason under the switch', async () => {
    putReply = { status: 409, body: { field: 'pickupBooking', error: 'BOOKING_NEEDS_RETURN_LOCATION', message: 'x' } }
    const user = await loaded()
    await user.click(screen.getByRole('switch', SWITCH))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByTestId('error-pickupBooking'))
      .toHaveTextContent('Choose where returns go back to and save before turning on pickup booking.')
  })

  test('already on while Bosta got disconnected → still switchable off', async () => {
    settings = { ...settings, pickupBooking: true, portalPickupBooking: true, bostaConnected: false }
    await loaded()
    expect(screen.getByRole('switch', SWITCH)).toBeEnabled()
  })
})
