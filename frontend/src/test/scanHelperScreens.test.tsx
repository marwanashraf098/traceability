import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import ScanHelperChips from '../components/scanHelpers/ScanHelperChips'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import PickupSessions from '../pages/PickupSessions'
import Returns from '../pages/Returns'
import LookupPage from '../pages/Lookup'
import type { PackSessionView } from '../api'

/**
 * Review mode S7 — click-to-scan chips on every seeded scan screen (waybill-mode pack session:
 * waybills then pieces; pickup session; return session; Lookup) and the no-stock hint. A chip goes
 * through the screen's own scan request — the same call a real scanner makes. A tenant whose /me
 * doesn't say scanHelpers (every real merchant) gets no chips and never calls /scan-helpers.
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const REVIEW_ME = { name: 'Reviewer', email: 'reviewer@tracedtech.com', role: 'owner', scanHelpers: true, demoMode: false }
const MERCHANT_ME = { name: 'Owner', email: 'o@shop.example', role: 'owner', scanHelpers: false, demoMode: false }

let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>

function token() {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', tenant: 'review-tenant', role: 'owner' })}.sig`
}

/** A URL-routed fake backend: `routes` answers first, then sensible empties. */
function backend(routes: (url: string, method: string, body?: Record<string, unknown>) => unknown | undefined) {
  return vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(opts.body as string) : undefined
    calls.push({ method, url, body })
    const hit = routes(url, method, body)
    if (hit !== undefined) return json(hit)
    return json({})
  })
}

const helperCalls = () => calls.filter(c => c.url.includes('/scan-helpers/'))
const posted = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix))

beforeEach(() => {
  calls = []
  setAccessToken(token())
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
})
afterEach(() => {
  clearAccessToken()
  resetCapabilitiesCache()
  vi.unstubAllGlobals()
})

// ── the chip component ─────────────────────────────────────────────────────────

describe('ScanHelperChips', () => {
  test('lists candidates; Scan hands the code to onScan', async () => {
    stubFetchWithShellDefaults(backend(url => url.includes('/scan-helpers/pickup')
      ? { items: [{ code: '7770000000004', label: '#R1004' }] } : undefined))
    const onScan = vi.fn()
    renderWithProviders(<ScanHelperChips context="pickup" onScan={onScan} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Scan 7770000000004' }))
    expect(onScan).toHaveBeenCalledWith('7770000000004')
    expect(screen.getByText('#R1004')).toBeInTheDocument()
  })

  test('pieces with no stock + showNoStock → "receive some first" with a link to Receiving', async () => {
    stubFetchWithShellDefaults(backend(url => url.includes('/scan-helpers/pieces') ? { items: [] } : undefined))
    renderWithProviders(<ScanHelperChips context="pieces" variantId="v9" onScan={vi.fn()} showNoStock />)
    const hint = await screen.findByTestId('scan-helper-no-stock')
    expect(hint).toHaveTextContent('No stock of this item in Traced yet')
    expect(screen.getByRole('link', { name: 'Go to Receiving' })).toHaveAttribute('href', '/receiving')
    expect(calls.some(c => c.url.endsWith('/scan-helpers/pieces?variantId=v9'))).toBe(true)
  })

  test('the endpoint refuses (404 — not a demo / review tenant) → renders nothing', async () => {
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      calls.push({ method: 'GET', url })
      return url.includes('/scan-helpers/') ? json({}, 404) : json({})
    }))
    renderWithProviders(<ScanHelperChips context="pieces" variantId="v9" onScan={vi.fn()} showNoStock />)
    await waitFor(() => expect(helperCalls()).toHaveLength(1))
    expect(screen.queryByTestId('scan-helper-no-stock')).toBeNull()
    expect(screen.queryByTestId('scan-helper-pieces')).toBeNull()
  })
})

// ── waybill-scan mode pack session ─────────────────────────────────────────────

const LINE = { id: 'line-1', variant_id: 'v1', sku: 'RV-TEE-M', variant_title: 'M', product_title: 'Classic Tee',
  imageUrl: null, quantity: 1, allocated: 0, allocatedPieces: [] as unknown[] }
const CARD = { id: 'order-1', number: '#R1003', customer_name: 'Nour', payment_method: 'cod', cod_amount: '450.00',
  tracking_number: '7770000000003', area: 'Cairo', courierType: 'delivery', batchNo: 1, batchPrintedAt: null, items: [LINE] }
const VIEW: PackSessionView = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-04T08:00:00Z',
  workerName: 'Review Worker', counters: { packed: 0, setAside: 0, rejected: 0, left: 7 }, recent: [], openOrder: null } as PackSessionView

function packBackend() {
  return backend((url, method) => {
    if (url.includes('/scan-helpers/waybills')) return { items: [{ code: '7770000000003', label: '#R1003' }] }
    if (url.includes('/scan-helpers/pieces?variantId=v1')) return { items: [{ code: 'TRC-PIECE-0001', label: '1' }] }
    if (url.endsWith('/sess-1/waybill') && method === 'POST') return { result: 'opened', order: CARD, code: null }
    if (url.endsWith('/orders/order-1/scan') && method === 'POST') {
      return { status: 'scanned', order: { ...CARD, items: [{ ...LINE, allocated: 1 }] },
        scan: { code: 'OK', pieceId: 'p1', barcode: 'TRC-PIECE-0001', allocatedCount: 1, requiredQuantity: 1 } }
    }
    if (url.endsWith('/pack-sessions/sess-1')) return VIEW
    return undefined
  })
}

describe('Waybill-scan pack session (PackSessionScreen)', () => {
  test('review tenant: waybill chip opens the order through the real waybill scan; piece chip scans the piece', async () => {
    stubFetchWithShellDefaults(packBackend(), { me: REVIEW_ME })
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Scan 7770000000003' }))
    await waitFor(() => expect(posted('/sess-1/waybill')).toHaveLength(1))
    expect(posted('/sess-1/waybill')[0].body).toEqual({ code: '7770000000003' })

    await user.click(await screen.findByRole('button', { name: 'Scan TRC-PIECE-0001' }))
    await waitFor(() => expect(posted('/orders/order-1/scan')).toHaveLength(1))
    expect(posted('/orders/order-1/scan')[0].body).toEqual({ code: 'TRC-PIECE-0001' })
  })

  test('real merchant: no chips, /scan-helpers never called', async () => {
    stubFetchWithShellDefaults(packBackend(), { me: MERCHANT_ME })
    const shellFetch = vi.fn(globalThis.fetch)
    vi.stubGlobal('fetch', shellFetch)
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={vi.fn()} />)
    await screen.findByTestId('session-waiting')
    await waitFor(() => expect(shellFetch.mock.calls.some(c => String(c[0]).endsWith('/me'))).toBe(true))
    expect(screen.queryByTestId('scan-helper-waybills')).toBeNull()
    expect(helperCalls()).toHaveLength(0)
  })
})

// ── pickup session ────────────────────────────────────────────────────────────

const PICKUP = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-10-04', scheduledTimeSlot: null, scannedCount: 0,
  openedByName: 'Reviewer', createdAt: '2026-10-04T08:00:00Z' }

function pickupBackend() {
  return backend((url, method) => {
    if (url.includes('/scan-helpers/pickup')) return { items: [{ code: '7770000000004', label: '#R1004' }] }
    if (url.endsWith('/pickup-sessions') && method === 'GET') return [PICKUP]
    if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') {
      return { outcome: 'ACCEPTED', entry: { shipmentId: 's4', trackingNumber: '7770000000004', orderNumber: '#R1004',
        codAmount: 450, scannedAt: '2026-10-04T08:01:00Z', scannedByName: 'Reviewer' } }
    }
    if (url.endsWith('/pickup-sessions/pk-1')) return { ...PICKUP, notes: null, closedByName: null, closedAt: null, scans: [] }
    return undefined
  })
}

describe('Pickup session', () => {
  test('review tenant: the AWB chip goes through the real pickup scan', async () => {
    stubFetchWithShellDefaults(pickupBackend(), { me: REVIEW_ME })
    const user = userEvent.setup()
    renderWithProviders(<PickupSessions />)
    await user.click(await screen.findByText('2026-10-04'))
    await user.click(await screen.findByRole('button', { name: 'Scan 7770000000004' }))
    await waitFor(() => expect(posted('/pickup-sessions/pk-1/scans')).toHaveLength(1))
    expect(posted('/pickup-sessions/pk-1/scans')[0].body).toEqual({ trackingNumber: '7770000000004' })
  })

  test('real merchant: no chips, /scan-helpers never called', async () => {
    stubFetchWithShellDefaults(pickupBackend(), { me: MERCHANT_ME })
    const user = userEvent.setup()
    renderWithProviders(<PickupSessions />)
    await user.click(await screen.findByText('2026-10-04'))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/pickup-sessions/pk-1'))).toBe(true))
    expect(screen.queryByTestId('scan-helper-pickup')).toBeNull()
    expect(helperCalls()).toHaveLength(0)
  })
})

// ── return session ────────────────────────────────────────────────────────────

const RETURN_SESSION = { id: 'rs-1', status: 'open', opened_by: 'u1', opened_at: '2026-10-04T08:00:00Z',
  closed_by: null, closed_at: null, note: null, items: [], expectedPieces: [] }

function returnsBackend() {
  return backend((url, method) => {
    if (url.includes('/scan-helpers/returns')) return { items: [{ code: 'TRC-PIECE-0006', label: '#R1006' }] }
    if (url.includes('/returns/sessions?page')) return { items: [], total: 0 }
    if (url.endsWith('/returns/analytics')) {
      return { totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0, expectedNotScannedCount: 0,
        unassignedPendingCount: 0, unassignedPending: [] }
    }
    if (url.endsWith('/returns/sessions') && method === 'POST') return { sessionId: 'rs-1' }
    if (url.endsWith('/returns/sessions/rs-1/scan') && method === 'POST') return {}
    if (url.endsWith('/returns/sessions/rs-1')) return RETURN_SESSION
    return undefined
  })
}

describe('Return session', () => {
  test('review tenant: the piece chip goes through the real return scan', async () => {
    stubFetchWithShellDefaults(returnsBackend(), { me: REVIEW_ME })
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await screen.findByTestId('scan-input')
    await user.click(await screen.findByRole('button', { name: 'Scan TRC-PIECE-0006' }))
    await waitFor(() => expect(posted('/returns/sessions/rs-1/scan')).toHaveLength(1))
    expect(posted('/returns/sessions/rs-1/scan')[0].body).toEqual({ scan: 'TRC-PIECE-0006', locationId: null })
  })

  test('real merchant: no chips, /scan-helpers never called', async () => {
    stubFetchWithShellDefaults(returnsBackend(), { me: MERCHANT_ME })
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await screen.findByTestId('scan-input')
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/returns/sessions/rs-1'))).toBe(true))
    expect(screen.queryByTestId('returns-scan-helpers')).toBeNull()
    expect(helperCalls()).toHaveLength(0)
  })
})

// ── Lookup ────────────────────────────────────────────────────────────────────

describe('Lookup', () => {
  test('review tenant: a sample chip runs the real lookup', async () => {
    const routed = backend(url => url.includes('/scan-helpers/lookup')
      ? { items: [{ code: 'TRC-PIECE-0005', label: 'delivered' }] } : undefined)
    // The lookup itself answers "not found" — this test is about which request the chip makes.
    stubFetchWithShellDefaults(vi.fn((url: string, opts?: RequestInit) => {
      if (url.includes('/lookup?q=')) { calls.push({ method: 'GET', url }); return json({}, 404) }
      return routed(url, opts)
    }), { me: REVIEW_ME })
    const user = userEvent.setup()
    renderWithProviders(<LookupPage />)
    expect(await screen.findByText('Delivered')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Scan TRC-PIECE-0005' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/lookup?q=TRC-PIECE-0005'))).toBe(true))
  })

  test('real merchant: no chips, /scan-helpers never called', async () => {
    stubFetchWithShellDefaults(backend(() => undefined), { me: MERCHANT_ME })
    renderWithProviders(<LookupPage />)
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(0))
    await new Promise(r => setTimeout(r, 50))
    expect(screen.queryByTestId('scan-helper-lookup')).toBeNull()
    expect(helperCalls()).toHaveLength(0)
  })
})
