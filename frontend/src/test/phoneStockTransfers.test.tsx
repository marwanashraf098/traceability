import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import StockTakeScan from '../pages/StockTakeScan'
import StockTakeReview from '../pages/StockTakeReview'
import TransferScanOut from '../pages/TransferScanOut'
import TransferReconcile from '../pages/TransferReconcile'
import TransferDetail from '../pages/TransferDetail'

// Q1b — the phone scanner on stock take and transfers: each scan screen registers as the
// tablet's scan target with its label and shows the header phone button; a phone scan sends its
// relayEventId (a keyboard scan none) and gets exactly one outcome with the screen's line;
// dialogs / the shortfall fields pause it ("Tablet busy"); "via phone" tags and the review's
// "N of M scans came from a phone".

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let relay: RelayHandlers | null = null
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_d: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const CONNECTED = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari', pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }

const SESSION = { sessionId: 'st-1', status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: true,
  openedBy: 'u1', openedByName: 'Owner', openedAt: '2026-10-05T07:00:00Z', finalizedBy: null, finalizedByName: null,
  finalizedAt: null, note: 'Shelf A', shopifySync: null }
const line = (over: Record<string, unknown> = {}) => ({ id: 'line-1', variant_id: 'v1', sku: 'TEE-M', variant_title: 'M',
  product_title: 'Tee', qty_out: 2, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0, phone_scans: 0, ...over })
const transfer = (status: string, over: Record<string, unknown> = {}) => ({ id: 'tr-1', transfer_type: 'showroom',
  transfer_mode: 'round_trip', status, note: null, expected_return_at: null, created_by: 'u1', created_at: '2026-10-05T07:00:00Z',
  closed_by: null, closed_at: null, sent_at: null, sent_by: null, cancelled_at: null, cancelled_by: null,
  reconcile_started_at: null, reconcile_started_by: null, destination_location_id: 'loc-2',
  destination_location_name: 'Zamalek Showroom', source_location_id: null, source_location_name: null,
  lines: [line()], outstandingCount: 2, piecesEverCount: 2, phoneScanCount: 0, ...over })

let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>
let routes: (url: string, method: string, body?: Record<string, unknown>) => unknown | undefined

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  relay = null
  calls = []
  routes = () => undefined
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(String(opts.body)) : undefined
    calls.push({ method, url, body })
    if (url.includes('/station/pairings/current') && method === 'GET') return json(CONNECTED)
    const hit = routes(url, method, body)
    return json(hit ?? {})
  }))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

const outcomes = () => calls.filter(c => c.method === 'POST' && c.url.includes('/station/relay-events/'))
  .map(c => [c.url.split('/relay-events/')[1].split('/')[0], c.body!.result, c.body!.message])
const targets = () => calls.filter(c => c.method === 'PUT' && c.url.includes('/station/pairings/current/target')).map(c => c.body!.label)
const posts = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix)).map(c => c.body!)

function at(path: string, pattern: string, page: React.ReactNode) {
  return renderWithProviders(
    <PhoneScanProvider><Routes><Route path={pattern} element={page} /></Routes><PhoneControl /></PhoneScanProvider>,
    { initialEntries: [path] })
}
const phoneScan = (id: string, code: string) => act(() => relay!.onScan({ id, seq: 1, code, createdAt: '' }))

// ── stock take ───────────────────────────────────────────────────────────────

describe('stock take scan', () => {
  beforeEach(() => {
    routes = (url, method, body) => {
      if (url.endsWith('/stock-takes/sessions/st-1')) return SESSION
      if (url.endsWith('/stock-takes/sessions/st-1/scan') && method === 'POST') {
        const code = body!.barcode as string
        return { sessionId: 'st-1', barcode: code, pieceId: code === 'NOPE' ? null : 'p-' + code,
          classification: code === 'NOPE' ? 'unknown' : 'match', alreadyScanned: false,
          scanDevice: body!.relayEventId ? 'phone' : 'hardware' }
      }
      return undefined
    }
  })

  test('registers as "Stock take · <shelf>", shows the header phone button; a phone scan sends its relayEventId and gets "Counted · Match"; the scan is tagged "via phone"', async () => {
    at('/stock-take/st-1/scan', '/stock-take/:id/scan', <StockTakeScan />)
    await screen.findByTestId('stocktake-scan')
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone connected · iPhone · Safari')
    await waitFor(() => expect(targets()).toContain('Stock take · Shelf A'))
    await waitFor(() => expect(relay).not.toBeNull())
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Counted · Match']]))
    expect(posts('/stock-takes/sessions/st-1/scan')).toEqual([{ barcode: 'PC-1', condition: 'good', relayEventId: 'ev-1' }])
    expect(await screen.findByTestId('via-phone')).toHaveTextContent('via phone')

    phoneScan('ev-2', 'NOPE')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'rejected', 'Unknown barcode'])
  })

  test('a keyboard scan sends no relayEventId and isn\'t tagged', async () => {
    at('/stock-take/st-1/scan', '/stock-take/:id/scan', <StockTakeScan />)
    await screen.findByTestId('stocktake-scan')
    const input = document.querySelector<HTMLInputElement>('input.input-scan')!
    input.value = 'PC-9'
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(posts('/stock-takes/sessions/st-1/scan')).toEqual([{ barcode: 'PC-9', condition: 'good' }]))
    await screen.findAllByText('Match', { selector: 'span' })
    expect(screen.queryByTestId('via-phone')).toBeNull()
    expect(outcomes()).toEqual([])
  })

  test('the abandon dialog pauses it: a phone scan → "Tablet busy — finish the dialog", nothing sent', async () => {
    const user = userEvent.setup()
    at('/stock-take/st-1/scan', '/stock-take/:id/scan', <StockTakeScan />)
    await screen.findByTestId('stocktake-scan')
    await waitFor(() => expect(relay).not.toBeNull())
    await user.click(screen.getByTestId('abandon-link'))
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    expect(posts('/stock-takes/sessions/st-1/scan')).toEqual([])
  })
})

describe('stock take review', () => {
  test('"N of M scans came from a phone" before Finalize (and in the finalize dialog); phone rows tagged', async () => {
    const user = userEvent.setup()
    const row = (pieceId: string, scanDevice: string | null) => ({ pieceId, variantId: 'v1', variantTitle: 'M', sku: 'TEE-M',
      productTitle: 'Tee', liveStatus: 'available', scanDevice })
    routes = url => {
      if (url.endsWith('/stock-takes/sessions/st-1')) return SESSION
      if (url.endsWith('/stock-takes/sessions/st-1/reconciliation')) {
        return { sessionId: 'st-1', status: 'open', completeCount: true, coveragePercent: 100,
          buckets: { on_shelf_counted: [row('p1', 'phone'), row('p2', 'hardware')], on_shelf_uncounted: [], committed_to_orders: [],
            with_courier_or_delivered: [], returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [] },
          variantRollup: [], scanCount: 5, phoneScanCount: 2,
          finalizePlan: { writeOffs: 0, byVariant: [], requiresTypedConfirmation: false, blockedReason: null } }
      }
      return undefined
    }
    at('/stock-take/st-1/review', '/stock-take/:id/review', <StockTakeReview />)
    expect(await screen.findByTestId('phone-scan-share')).toHaveTextContent('2 of 5 scans came from a phone')
    expect(screen.getAllByTestId('via-phone')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: /finalize/i }))
    await waitFor(() => expect(screen.getAllByTestId('phone-scan-share')).toHaveLength(2))
  })

  test('no phone scans → no count shown', async () => {
    routes = url => {
      if (url.endsWith('/stock-takes/sessions/st-1')) return SESSION
      if (url.endsWith('/reconciliation')) return { sessionId: 'st-1', status: 'open', completeCount: true, coveragePercent: 0,
        buckets: { on_shelf_counted: [], on_shelf_uncounted: [], committed_to_orders: [], with_courier_or_delivered: [],
          returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [] },
        variantRollup: [], scanCount: 3, phoneScanCount: 0 }
      return undefined
    }
    at('/stock-take/st-1/review', '/stock-take/:id/review', <StockTakeReview />)
    await screen.findByText(/attested|attest/i)
    expect(screen.queryByTestId('phone-scan-share')).toBeNull()
  })
})

// ── transfers ────────────────────────────────────────────────────────────────

describe('transfer scan-out', () => {
  test('registers as "Transfer out · <destination>"; a phone scan → "Scanned out", a rejection → the backend\'s text', async () => {
    routes = (url, method, body) => {
      if (url.endsWith('/transfers/tr-1') && method === 'GET') return transfer('preparing')
      if (url.endsWith('/transfers/tr-1/scan-out')) {
        return body!.barcode === 'BAD'
          ? { success: false, code: 'PIECE_NOT_FOUND', message_en: 'Barcode not found in inventory', message_ar: 'x' }
          : { success: true, code: 'OK', qtyOut: 3 }
      }
      return undefined
    }
    at('/transfers/tr-1/scan-out', '/transfers/:id/scan-out', <TransferScanOut />)
    await screen.findByTestId('transfer-scan-out')
    expect(screen.getByTestId('phone-chip')).toBeInTheDocument()
    await waitFor(() => expect(targets()).toContain('Transfer out · Zamalek Showroom'))
    await waitFor(() => expect(relay).not.toBeNull())
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Scanned out']]))
    expect(posts('/transfers/tr-1/scan-out')).toEqual([{ barcode: 'PC-1', relayEventId: 'ev-1' }])
    phoneScan('ev-2', 'BAD')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'rejected', 'Barcode not found in inventory'])
  })
})

describe('transfer reconcile', () => {
  beforeEach(() => {
    routes = (url, method, body) => {
      if (url.endsWith('/transfers/tr-1') && method === 'GET') return transfer('reconciling')
      if (url.endsWith('/transfers/tr-1/scan-back')) return { success: true, code: 'OK', outcome: body!.condition === 'good' ? 'returned_good' : 'condemned' }
      return undefined
    }
  })

  test('registers as "Transfer reconcile · <destination>"; a phone scan → "Returned · Good" with its relayEventId', async () => {
    at('/transfers/tr-1/reconcile', '/transfers/:id/reconcile', <TransferReconcile />)
    await screen.findByTestId('reconcile-outstanding')
    expect(screen.getByTestId('phone-chip')).toBeInTheDocument()
    await waitFor(() => expect(targets()).toContain('Transfer reconcile · Zamalek Showroom'))
    await waitFor(() => expect(relay).not.toBeNull())
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Returned · Good']]))
    expect(posts('/transfers/tr-1/scan-back')).toEqual([{ barcode: 'PC-1', condition: 'good', relayEventId: 'ev-1' }])
  })

  test('typing a shortfall quantity pauses it: "Tablet busy"; leaving the field resumes', async () => {
    at('/transfers/tr-1/reconcile', '/transfers/:id/reconcile', <TransferReconcile />)
    await screen.findByTestId('reconcile-outstanding')
    await waitFor(() => expect(relay).not.toBeNull())
    const field = screen.getByTestId('shortfall-sold-line-1')
    act(() => field.focus())
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    act(() => field.blur())
    phoneScan('ev-2', 'PC-2')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'accepted', 'Returned · Good'])
  })

  test('the close confirm pauses it', async () => {
    const user = userEvent.setup()
    routes = (url, method) => (url.endsWith('/transfers/tr-1') && method === 'GET'
      ? transfer('reconciling', { outstandingCount: 0, lines: [line({ qty_returned_good: 2 })] }) : undefined)
    at('/transfers/tr-1/reconcile', '/transfers/:id/reconcile', <TransferReconcile />)
    await screen.findByTestId('reconcile-outstanding')
    await waitFor(() => expect(relay).not.toBeNull())
    await user.click(screen.getByRole('button', { name: /close transfer/i }))
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
  })
})

describe('transfer detail', () => {
  test('a line with phone scans carries a "via phone · N" tag; others none', async () => {
    routes = (url, method) => (url.endsWith('/transfers/tr-1') && method === 'GET'
      ? transfer('reconciling', { lines: [line({ phone_scans: 2 }), line({ id: 'line-2', variant_title: 'L', phone_scans: 0 })] })
      : undefined)
    at('/transfers/tr-1', '/transfers/:id', <TransferDetail />)
    expect(await screen.findByTestId('via-phone')).toHaveTextContent('via phone · 2')
    expect(screen.getAllByTestId('via-phone')).toHaveLength(1)
  })
})
