import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import type { PackSessionView } from '../api'

// Hotfix (production, Jumi 2026-10-01): a scan that isn't a waybill now gets one of two answers —
// a piece code → "That's a piece, not a waybill"; anything else → "Not a waybill we recognise"
// (try the bottom barcode). Rejected rows in the side list show what was scanned.

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const VIEW: PackSessionView = {
  id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-01T07:05:00Z', workerName: 'Ahmed',
  counters: { packed: 0, setAside: 0, rejected: 2, left: 0 },
  recent: [
    { orderId: null, orderNumber: null, customerName: null, outcome: 'rejected', reason: 'UNRECOGNISED_BARCODE',
      rawScan: 'G - 0 2 - 8 4 8 4 8 0 5 6 9 9', at: '2026-10-01T08:03:00Z' },
    { orderId: null, orderNumber: null, customerName: null, outcome: 'rejected', reason: 'NOT_A_WAYBILL',
      rawScan: 'P000123', at: '2026-10-01T08:02:00Z' },
  ],
  openOrder: null,
}

function rejection(code: string, messageEn: string) {
  return { result: 'rejected', order: null, code, subReason: null, orderNumber: null, who: null, at: null,
    state: null, messageEn, messageAr: 'x' }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const body = opts.body ? JSON.parse(opts.body as string) : {}
    if (url.endsWith('/sess-1/waybill')) {
      return body.code === 'P000123'
        ? json(rejection('NOT_A_WAYBILL', "That's not a waybill. Scan the waybill first to open an order."))
        : json(rejection('UNRECOGNISED_BARCODE',
            "This barcode isn't a waybill we recognise. Try the barcode at the bottom of the waybill (Tracking Number)."))
    }
    if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
    if (url.endsWith('/me')) return json({ name: 'Ahmed', email: null, role: 'worker' })
    return json({})
  }))
})
afterEach(() => { vi.unstubAllGlobals() })

async function scan(code: string) {
  const user = userEvent.setup()
  renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
  await user.type(screen.getByRole('textbox'), `${code}{Enter}`)
  return screen.findByTestId('session-rejected')
}

describe('pack session — non-waybill scans and the side list', () => {
  test('a piece code: "That\'s a piece, not a waybill"', async () => {
    const r = await scan('P000123')
    expect(within(r).getByTestId('rejected-title')).toHaveTextContent("That's a piece, not a waybill")
  })

  test('anything else: "Not a waybill we recognise" + try the bottom barcode', async () => {
    const r = await scan('ABC-XYZ')
    expect(within(r).getByTestId('rejected-title')).toHaveTextContent('Not a waybill we recognise')
    expect(r).toHaveTextContent('Try the barcode at the bottom of the waybill (Tracking Number)')
  })

  test('rejected rows show the raw scan (truncated, full value on hover) instead of a dash', async () => {
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    const raws = await screen.findAllByTestId('recent-raw-scan')
    expect(raws).toHaveLength(2)
    expect(raws[0]).toHaveTextContent('G - 0 2 - 8 4 8 4 8 0…')
    expect(raws[0]).toHaveAttribute('title', 'G - 0 2 - 8 4 8 4 8 0 5 6 9 9')
    expect(raws[0].className).toContain('font-mono')
    expect(raws[1]).toHaveTextContent('P000123')
    expect(within(screen.getByTestId('session-recent')).queryByText('—')).not.toBeInTheDocument()
  })
})
