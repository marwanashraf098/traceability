import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes } from 'react-router-dom'
import SettingsPage from '../pages/settings/SettingsPage'

// Pick & Pack S3 — Settings › Pick & Pack: owner edits the packing mode (PUT /tenant/settings
// {pickPackMode}), manager sees it read-only, worker never reaches Settings.

let role: 'owner' | 'manager' | 'worker' = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

let mode: string
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/tenant/settings') && method === 'PUT') {
    mode = body.pickPackMode ?? mode
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/tenant/settings')) {
    return fakeResponse({ name: 'Store', pickupAddress: null, labelSize: '50x25', defaultLanguage: 'en',
      timezone: 'Africa/Cairo', pickPackMode: mode })
  }
  return fakeResponse({})
}

beforeEach(() => {
  role = 'owner'
  mode = 'order_queue'
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

function renderSettings() {
  return renderWithProviders(
    <Routes>
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/overview" element={<p>overview page</p>} />
    </Routes>,
    { initialEntries: ['/settings?tab=pickpack'] },
  )
}

describe('Settings › Pick & Pack', () => {
  test('owner switches to waybill scan and saves only the mode', async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByTestId('pickpack-settings')
    expect(screen.getByText('Self-pickup orders have no waybill, so they always stay in the order queue.')).toBeInTheDocument()
    expect(screen.getByText(/Pack sessions already open finish in their current mode/)).toBeInTheDocument()

    const save = screen.getByTestId('pickpack-save')
    expect(save).toBeDisabled()
    await user.click(screen.getByText('Waybill scan'))
    expect(save).toBeEnabled()
    await user.click(save)

    await waitFor(() => {
      const put = calls.find(c => c.method === 'PUT' && c.url.endsWith('/tenant/settings'))
      expect(put?.body).toEqual({ pickPackMode: 'waybill_scan' })
    })
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument()
  })

  test('manager sees the mode read-only — no save, radios disabled', async () => {
    role = 'manager'
    mode = 'waybill_scan'
    const user = userEvent.setup()
    renderSettings()
    await screen.findByTestId('pickpack-settings')
    expect(screen.getByText('View only — ask an owner to make changes here.')).toBeInTheDocument()
    expect(screen.queryByTestId('pickpack-save')).not.toBeInTheDocument()
    await user.click(screen.getByText('Order queue'))
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
    const radios = screen.getAllByRole('radio')
    expect(radios.every(r => (r as HTMLInputElement).disabled)).toBe(true)
  })

  test('worker never sees it', async () => {
    role = 'worker'
    renderSettings()
    expect(await screen.findByText('overview page')).toBeInTheDocument()
    expect(calls.some(c => c.url.endsWith('/tenant/settings'))).toBe(false)
  })
})
