import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes } from 'react-router-dom'
import SettingsPage from '../pages/settings/SettingsPage'

// GDPR build A — Settings › Privacy: owner-only list of Shopify customer data requests, each
// downloadable as a JSON file while it hasn't expired. Managers don't get the tab.

let role: 'owner' | 'manager' | 'worker' = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

let calls: string[]
let exportStatus: number

const READY = {
  id: 'req-1', shopifyCustomerId: '7700112233', ordersRequested: 2, status: 'ready',
  createdAt: '2026-10-05T10:00:00Z', expiresAt: '2026-11-04T10:00:00Z', available: true,
  downloadedAt: null, notifiedAt: '2026-10-05T10:00:01Z', redacted: false,
}
const EXPIRED = { ...READY, id: 'req-0', available: false, createdAt: '2026-08-01T10:00:00Z', expiresAt: '2026-08-31T10:00:00Z' }

function fakeResponse(data: unknown, status = 200, extra: Record<string, string> = {}) {
  const headers: Record<string, string> = { 'content-type': 'application/json', ...extra }
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => headers[k.toLowerCase()] ?? null },
    json: async () => structuredClone(data),
    blob: async () => new Blob([JSON.stringify(data)], { type: 'application/json' }),
  })
}

function backend(url: string) {
  calls.push(url)
  if (url.endsWith('/privacy/data-requests')) return fakeResponse([READY, EXPIRED])
  if (url.endsWith('/privacy/data-requests/req-1/export')) {
    return fakeResponse({ request: {} }, exportStatus,
      { 'content-disposition': 'attachment; filename="traced-customer-data-request-2026-10-05-req1.json"' })
  }
  return fakeResponse({})
}

beforeEach(() => {
  role = 'owner'
  calls = []
  exportStatus = 200
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
  stubFetchWithShellDefaults(vi.fn(backend))
})

function renderSettings(tab = 'privacy') {
  return renderWithProviders(
    <Routes>
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/overview" element={<p>overview page</p>} />
    </Routes>,
    { initialEntries: [`/settings?tab=${tab}`] },
  )
}

describe('Settings › Privacy', () => {
  test('owner sees the requests and downloads one', async () => {
    const user = userEvent.setup()
    renderSettings()
    const row = await screen.findByTestId('data-request-req-1')
    expect(row).toHaveTextContent('Shopify customer 7700112233')
    expect(row).toHaveTextContent('2 orders')
    expect(screen.getByTestId('data-request-req-0')).toHaveTextContent('Expired')

    await user.click(screen.getByRole('button', { name: /Download/ }))
    await waitFor(() => expect(calls.some(c => c.endsWith('/privacy/data-requests/req-1/export'))).toBe(true))
    expect(URL.createObjectURL).toHaveBeenCalled()
  })

  test('an expired request answers with a clear message', async () => {
    exportStatus = 410
    const user = userEvent.setup()
    renderSettings()
    await screen.findByTestId('data-request-req-1')
    await user.click(screen.getByRole('button', { name: /Download/ }))
    expect(await screen.findByText("This request expired after 30 days and can't be downloaded.")).toBeInTheDocument()
  })

  test('a manager has no Privacy tab and never calls the endpoint', async () => {
    role = 'manager'
    renderSettings()
    await screen.findByRole('heading', { name: 'Settings' })
    expect(screen.queryByText('Privacy')).not.toBeInTheDocument()
    expect(screen.queryByTestId('privacy-settings')).not.toBeInTheDocument()
    expect(calls.some(c => c.includes('/privacy/'))).toBe(false)
  })
})
