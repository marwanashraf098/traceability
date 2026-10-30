import { test, expect, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'

// Orders search (2026-10-30): every /orders request runs the alert check, so the search and tracking inputs wait
// 300 ms before fetching, and a newer request aborts the one in flight — an older response never overwrites a newer
// one. Both tests are red on the undebounced, unaborted page.

type Resp = { ok: true; status: number; headers: { get: (k: string) => string | null }; json: () => Promise<unknown> }
const resp = (data: unknown): Resp => ({
  ok: true, status: 200,
  headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const derived = {
  primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null, conflictKey: null,
  notTraced: false, packedConfirmed: false, fulfillmentKey: 'status.new', fulfillmentTone: 'NEUTRAL',
}
const order = (id: string, number: string) => ({
  id, number, customerName: 'Mona Said', customerPhone: '01022334455', status: 'new', onHold: false,
  codAmount: 540, placedAt: '2026-07-20T09:12:00Z', trackingNumber: null, deliveryState: null,
  exceptionReason: null, bostaLinkStatus: null, failedDeliveryAttempts: 0, isDelayed: null, slaBreached: null,
  notTracedAt: null, isExchange: false, derivedStatus: derived, shippingBadge: null, attentionReasons: [],
})
const page = (...items: ReturnType<typeof order>[]) => ({ items, page: 0, size: 20, total: items.length })
const SUMMARY = { total: 1, processing: 1, withCourier: 0, delivered: 0, returned: 0, needsAttention: 0 }

const listCalls = (f: ReturnType<typeof vi.fn>) =>
  f.mock.calls.map(c => String(c[0])).filter(u => u.includes('/orders?'))

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

test('typing "abcde" quickly fetches the list exactly once, 300 ms after the last key', async () => {
  const appFetch = vi.fn((url: string) => {
    if (url.includes('/orders/summary')) return Promise.resolve(resp(SUMMARY))
    if (url.includes('/orders?')) return Promise.resolve(resp(page(order('o1', '#1001'))))
    return Promise.resolve(resp({}))
  })
  stubFetchWithShellDefaults(appFetch)
  renderWithProviders(<Layout><Orders /></Layout>)
  await screen.findByText('#1001')
  const search = screen.getByPlaceholderText('Search by order #, name, or phone')

  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  // Testing Library's async wrapper advances fake timers only when it sees Jest — point it at vitest's.
  vi.stubGlobal('jest', { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) })
  appFetch.mockClear()
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  await user.type(search, 'abcde')
  expect(listCalls(appFetch)).toHaveLength(0)
  await vi.advanceTimersByTimeAsync(299)
  expect(listCalls(appFetch)).toHaveLength(0)
  await vi.advanceTimersByTimeAsync(1)
  expect(listCalls(appFetch)).toHaveLength(1)
  expect(listCalls(appFetch)[0]).toContain('q=abcde')
})

test('a slow first response resolving after a fast second one never replaces the second result', async () => {
  let releaseSlow: (r: Resp) => void = () => {}
  const appFetch = vi.fn((url: string) => {
    if (url.includes('/orders/summary')) return Promise.resolve(resp(SUMMARY))
    if (url.includes('/orders?')) {
      const q = new URL(url, 'http://x').searchParams.get('q')
      if (q === 'a') return new Promise<Resp>(r => { releaseSlow = r })            // slow, ignores the signal
      if (q === 'ab') return Promise.resolve(resp(page(order('o2', '#SECOND'))))   // fast
      return Promise.resolve(resp(page(order('o1', '#1001'))))
    }
    return Promise.resolve(resp({}))
  })
  stubFetchWithShellDefaults(appFetch)
  renderWithProviders(<Layout><Orders /></Layout>)
  await screen.findByText('#1001')
  const search = screen.getByPlaceholderText('Search by order #, name, or phone')

  const user = userEvent.setup()
  await user.type(search, 'a')
  await waitFor(() => expect(listCalls(appFetch).some(u => u.includes('q=a&') || u.endsWith('q=a'))).toBe(true))
  await user.type(search, 'b')
  expect(await screen.findByText('#SECOND')).toBeInTheDocument()

  releaseSlow(resp(page(order('o3', '#FIRST'))))
  await new Promise(r => setTimeout(r, 50))
  expect(screen.queryByText('#FIRST')).toBeNull()
  expect(screen.getByText('#SECOND')).toBeInTheDocument()
})
