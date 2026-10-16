import { test, expect, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'

// S2 guard: scan capture (scanKeys.ts) is scoped to useScanner inputs. The Orders search takes
// customer names, so an Arabic layout must keep typing Arabic there — never Latin.

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
})

test('Orders search keeps an Arabic name typed on an Arabic layout and searches for it', async () => {
  const appFetch = vi.fn((url: string) => {
    if (url.includes('/orders/summary')) return jsonOk({ total: 0, processing: 0, withCourier: 0, delivered: 0, returned: 0 })
    if (url.includes('/orders?')) return jsonOk({ items: [], page: 0, size: 20, total: 0 })
    return jsonOk({})
  })
  stubFetchWithShellDefaults(appFetch)
  renderWithProviders(<Layout><Orders /></Layout>)

  const search = await screen.findByPlaceholderText('Search by order #, name, or phone') as HTMLInputElement
  search.focus()
  // "منى" on Windows Arabic 101 / macOS Arabic - PC: KeyL = م, KeyK = ن, KeyN = ى.
  const user = userEvent.setup({ keyboardMap: [
    { code: 'KeyL', key: 'م' }, { code: 'KeyK', key: 'ن' }, { code: 'KeyN', key: 'ى' }] })
  await user.keyboard('[KeyL][KeyK][KeyN]')

  expect(search.value).toBe('منى')
  await waitFor(() => expect(appFetch.mock.calls.some(([u]) =>
    String(u).includes('/orders?') && decodeURIComponent(String(u)).includes('q=منى'))).toBe(true))
})
