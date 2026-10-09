import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import LocationsTab from '../pages/settings/LocationsTab'

// Issue 2 — Settings → Locations: "While stock is here: Remove from Shopify / Leave Shopify
// unchanged" per non-main location (PUT /api/v1/locations/{id}/shopify-sync-mode {mode}).

const MAIN = 'loc-main'
const SHOWROOM = 'loc-showroom'

function row(id: string, name: string, isMain: boolean, mode: 'remove' | 'leave') {
  return {
    id, name, type: 'warehouse', is_default: isMain, is_fulfillment: isMain, shopify_sync_mode: mode,
    shopify_location_id: isMain ? 'gid://shopify/Location/1' : null, shopify_sync_status: isMain ? 'linked' : 'unsynced',
    shopify_sync_error: null, shopify_synced_at: null,
  }
}

function json(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, statusText: 'OK', json: async () => structuredClone(data) })
}

let server: { showroomMode: 'remove' | 'leave' }
let fetchMock: ReturnType<typeof vi.fn>

describe('Settings → Locations — while stock is here (Issue 2)', () => {
  beforeEach(() => {
    server = { showroomMode: 'remove' }
    fetchMock = vi.fn((url: string, opts?: RequestInit) => {
      if (url === '/api/v1/locations' && (!opts?.method || opts.method === 'GET')) {
        return json([row(MAIN, 'Main Warehouse', true, 'remove'), row(SHOWROOM, 'Zamalek Showroom', false, server.showroomMode)])
      }
      if (url === `/api/v1/locations/${SHOWROOM}/shopify-sync-mode` && opts?.method === 'PUT') {
        server.showroomMode = JSON.parse(String(opts.body)).mode
        return json({ id: SHOWROOM, shopify_sync_mode: server.showroomMode })
      }
      return json({})
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => { vi.unstubAllGlobals() })

  test('l1 a non-main location defaults to "Remove from Shopify"; the main warehouse has no control; the help line shows', async () => {
    renderWithProviders(<LocationsTab />)
    const select = await screen.findByTestId(`sync-mode-${SHOWROOM}`) as HTMLSelectElement
    expect(select.value).toBe('remove')
    expect(screen.queryByTestId(`sync-mode-${MAIN}`)).toBeNull()
    expect(screen.getByText('Always counted in Shopify')).toBeInTheDocument()
    expect(screen.getByTestId('sync-mode-help')).toHaveTextContent('Traced lowers your main warehouse in Shopify')
  })

  test('l2 choosing "Leave Shopify unchanged" saves it, and it is still there after a reload', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<LocationsTab />)
    const select = await screen.findByTestId(`sync-mode-${SHOWROOM}`) as HTMLSelectElement
    await user.selectOptions(select, 'leave')

    await waitFor(() => expect(fetchMock.mock.calls.some(([u, o]) =>
      u === `/api/v1/locations/${SHOWROOM}/shopify-sync-mode` && (o as RequestInit)?.method === 'PUT')).toBe(true))
    const put = fetchMock.mock.calls.find(([u, o]) => u === `/api/v1/locations/${SHOWROOM}/shopify-sync-mode`
      && (o as RequestInit)?.method === 'PUT')!
    expect(JSON.parse(String((put[1] as RequestInit).body))).toEqual({ mode: 'leave' })
    await waitFor(() => expect(select.value).toBe('leave'))

    unmount()
    renderWithProviders(<LocationsTab />)
    const reloaded = await screen.findByTestId(`sync-mode-${SHOWROOM}`) as HTMLSelectElement
    expect(reloaded.value).toBe('leave')
  })
})
