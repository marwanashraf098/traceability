import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'
import ExceptionsPage from '../pages/Exceptions'

// Pick & Pack S4 — waybill-mode page lists (print batches today + reprint, printed but not
// packed), the end-of-session summary (also after a reload via ?summary=), and the exception a
// list row opens (Exceptions ?key= highlight).

let role: 'owner' | 'manager' | 'worker' = 'manager'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const BATCHES = [
  { batchId: 'b3', batchNo: 3, printedAt: '2026-10-02T07:42:00Z', printedByName: 'Mona', waybillCount: 12,
    packed: 9, setAside: 0, cancelled: 0, waiting: 3 },
  { batchId: 'b2', batchNo: 2, printedAt: '2026-10-02T06:15:00Z', printedByName: 'Mona', waybillCount: 8,
    packed: 7, setAside: 1, cancelled: 0, waiting: 0 },
]
const row = (o: Record<string, unknown>) => ({ orderId: 'o', customerName: 'Customer', shipmentId: String(o.orderNumber),
  trackingNumber: '74821655', batchId: 'b3', batchNo: 3, batchPrintedAt: '2026-10-02T07:42:00Z', packerName: null,
  setAsideReason: null, exceptionType: null, subjectKey: null, ...o })
const NOT_PACKED = [
  row({ orderNumber: '#1039', status: 'cancelled', exceptionType: 'pack_cancelled_after_print', subjectKey: 'pack_cancelled_after_print:s1' }),
  row({ orderNumber: '#1031', status: 'set_aside', setAsideReason: 'piece_missing', exceptionType: 'pack_set_aside', subjectKey: 'pack_set_aside:x9' }),
  row({ orderNumber: '#1047', status: 'packing', packerName: 'Ahmed' }),
  row({ orderNumber: '#1049', status: 'waiting' }),
]
const SUMMARY = {
  sessionId: 'sess-1', workerName: 'Ahmed', startedAt: '2026-10-02T07:05:00Z', endedAt: '2026-10-02T08:48:00Z',
  durationSeconds: 103 * 60, packed: 23, setAside: 2, rejected: 1,
  needsManager: [
    { orderId: 'a', orderNumber: '#1031', customerName: 'Omar Fathy', kind: 'set_aside', reason: 'piece_missing', rawScan: 'x', at: '2026-10-02T08:00:00Z' },
    { orderId: 'b', orderNumber: '#1039', customerName: 'Salma Nabil', kind: 'cancelled', reason: 'CANCELLED', rawScan: 'y', at: '2026-10-02T08:10:00Z' },
  ],
  unscannedFromTodaysBatches: 1,
}
const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-02T07:05:00Z', workerName: 'Ahmed',
  counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null }

let calls: Array<{ method: string; url: string }>
let batches: unknown[]
let notPacked: unknown[]

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url })
  if (url.endsWith('/fulfill/mode')) return json({ mode: 'waybill_scan' })
  if (url.endsWith('/fulfill/queue')) return json([])
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 31, openSessionId: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json(batches)
  if (url.endsWith('/fulfill/printed-not-packed')) return json(notPacked)
  if (url.endsWith('/print-batches/b3/reprint')) {
    return json({ batchId: 'b3', batchNo: 3, waybillCount: 11, candidateCount: 12, remainingCount: 0, orderGuaranteed: true,
      pdfBase64: btoa('%PDF'), excluded: [{ orderNumber: '#1039', trackingNumber: '74821655', reason: 'ORDER_CANCELLED' }], message: null })
  }
  if (url.endsWith('/pack-sessions') && method === 'POST') return json(VIEW)
  if (url.endsWith('/pack-sessions/sess-1/end')) return Promise.resolve({ ok: true, status: 204, headers: { get: () => '0' }, json: async () => null })
  if (url.endsWith('/pack-sessions/sess-1/summary')) return json(SUMMARY)
  if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
  if (url.includes('/exceptions?') || url.endsWith('/exceptions')) {
    return json({ total: 1, page: 0, size: 50, counts: { critical: 0, high: 0, medium: 1, low: 0 }, items: [{
      type: 'pack_set_aside', severity: 'MEDIUM', subject_type: 'order', subject_key: 'pack_set_aside:x9',
      order_id: 'a', order_number: '#1031', occurred_at: '2026-10-02T08:00:00Z', ageSeconds: 600,
      descriptionEn: 'Order #1031 was set aside while packing (piece missing on the shelf) by Ahmed.', descriptionAr: 'x',
      suggestedAction: 'check_set_aside', actionUrl: '/fulfill' }] })
  }
  return json({})
}

function Where() { const l = useLocation(); return <p data-testid="where">{l.pathname + l.search}</p> }

function renderAt(path: string) {
  return renderWithProviders(
    <><Routes>
      <Route path="/fulfill" element={<FulfillRoute />} />
      <Route path="/exceptions" element={<ExceptionsPage />} />
    </Routes><Where /></>,
    { initialEntries: [path] },
  )
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  role = 'manager'
  calls = []
  batches = BATCHES
  notPacked = NOT_PACKED
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('waybill-mode page lists', () => {
  test('print batches today: progress per batch; Reprint opens the PDF and lists what was skipped', async () => {
    const popup = { location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockImplementation(() => popup as unknown as Window)
    URL.createObjectURL = vi.fn(() => 'blob:reprint')
    const user = userEvent.setup()
    renderAt('/fulfill')
    const list = await screen.findByTestId('batches-today')
    const rows = await within(list).findAllByTestId('batch-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('batch-progress')).toHaveTextContent('9 packed · 3 left')
    expect(within(rows[1]).getByTestId('batch-progress')).toHaveTextContent('7 packed · 1 set aside · none left')

    await user.click(within(rows[0]).getByRole('button', { name: /Reprint/ }))
    const result = await screen.findByTestId('reprint-result')
    expect(popup.location.href).toBe('blob:reprint')
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/print-batches/b3/reprint'))).toBe(true)
    expect(result).toHaveTextContent('11 waybills reprinted.')
    expect(result).toHaveTextContent('Order cancelled — not reprinted')
  })

  test('printed but not packed: one chip per status; manager opens the exception, worker can\'t', async () => {
    const user = userEvent.setup()
    renderAt('/fulfill')
    const list = await screen.findByTestId('printed-not-packed')
    await within(list).findAllByTestId('not-packed-row')
    expect(within(list).getByTestId('status-cancelled')).toHaveTextContent('Cancelled after print')
    expect(within(list).getByTestId('status-set_aside')).toHaveTextContent('Set aside · Piece missing on the shelf')
    expect(within(list).getByTestId('status-packing')).toHaveTextContent('Packing now · Ahmed')
    expect(within(list).getByTestId('status-waiting')).toHaveTextContent('Waiting')

    await user.click(within(list).getByText('#1031'))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/exceptions?type=pack_set_aside&key=pack_set_aside%3Ax9'))
    expect(await screen.findByTestId('exception-highlighted')).toHaveTextContent('Order #1031 was set aside while packing')
    expect(screen.getAllByText('Set Aside While Packing').length).toBeGreaterThan(0)
  })

  test('worker sees the same rows read-only (no link)', async () => {
    role = 'worker'
    renderAt('/fulfill')
    const list = await screen.findByTestId('printed-not-packed')
    await within(list).findAllByTestId('not-packed-row')
    expect(within(list).queryAllByRole('button')).toHaveLength(0)
  })

  test('empty states', async () => {
    batches = []
    notPacked = []
    renderAt('/fulfill')
    expect(await screen.findByTestId('batches-empty')).toHaveTextContent('No waybills printed today.')
    expect(await screen.findByTestId('not-packed-empty')).toHaveTextContent('Every printed waybill has been packed.')
  })
})

describe('session summary', () => {
  test('End session shows the summary; it survives a reload (?summary=); Back returns to the page', async () => {
    const user = userEvent.setup()
    renderAt('/fulfill')
    await user.click(await screen.findByRole('button', { name: /Start pack session/ }))
    await user.click(await screen.findByRole('button', { name: 'End session' }))

    const summary = await screen.findByTestId('session-summary')
    expect(screen.getByTestId('where')).toHaveTextContent('/fulfill?summary=sess-1')
    expect(summary).toHaveTextContent('Ahmed')
    expect(summary).toHaveTextContent('1 h 43 min')
    const counts = within(summary).getByTestId('summary-counts')
    expect(counts).toHaveTextContent('23')
    expect(counts).toHaveTextContent('2')
    expect(counts).toHaveTextContent('1')
    const needs = within(summary).getByTestId('summary-needs-manager')
    expect(needs).toHaveTextContent('#1031')
    expect(needs).toHaveTextContent('Set aside · Piece missing on the shelf')
    expect(needs).toHaveTextContent('#1039')
    expect(needs).toHaveTextContent('Cancelled after print')
    expect(within(summary).getByTestId('summary-unscanned'))
      .toHaveTextContent("1 printed waybill from today's batches hasn't been scanned yet.")

    await user.click(within(summary).getByRole('button', { name: 'Back to Pick & Pack' }))
    expect(await screen.findByTestId('waybill-pack-page')).toBeInTheDocument()
  })

  test('opening /fulfill?summary=<id> directly (a reload) shows that summary', async () => {
    renderAt('/fulfill?summary=sess-1')
    expect(await screen.findByTestId('session-summary')).toHaveTextContent('Session ended')
  })
})
