import { test, expect, describe, vi, afterEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import Receiving from '../pages/Receiving'
import Fulfill from '../pages/Fulfill'
import Inventory from '../pages/Inventory'
import SettingsPage from '../pages/settings/SettingsPage'
import StockTakeScan from '../pages/StockTakeScan'

/**
 * Flexible labeling for everyone (V194, 2026-10-29): a tenant with the switch ON and NO untagged stock sees
 * today's flows.
 *   ro1  Receiving step 1: "Print & stick" pre-selected ("Your default"); picking it goes on to today's form and the
 *        session is created as print_stick
 *   ro2  Pick & Pack: the single "Print" dialog — waybills ticked, piece labels at 0, disabled with its reason;
 *        Print sends the waybill job only
 *   ro3  Inventory: an "Untagged (0)" tab saying every piece has a label, no rows
 *   ro4  Settings → Labels is there, "Print & stick" saved as the default
 *   ro5  Stock take: no untagged-count panel; a scan works as today
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['%PDF']),
  })
}

const NO_LABELS = { labels: 0, orders: 0, reused: 0, unitsWithoutLabel: 0, ordersConsidered: 2, parts: [] }
const QUEUE = [
  { id: 'o1', number: '#101', customer_name: 'Mona', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 2, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, awb_printed: false },
  { id: 'o2', number: '#102', customer_name: 'Omar', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, awb_printed: false },
]
const JOB = { jobId: 'job-1', kind: 'print', status: 'done', total: 2, done: 2, candidateCount: 2, remainingCount: 0,
  printedCount: 2, excluded: [], batchId: 'batch-1', batchNo: 1, reprintBatchId: null, reprintBatchNo: null,
  orderGuaranteed: true, pdfAvailable: true, pdfBytes: 10, message: null, createdAt: '2026-10-29T10:00:00Z',
  finishedAt: '2026-10-29T10:00:05Z', expiresAt: null }
const ST_SESSION = { sessionId: 'st1', status: 'open', scopeType: 'all', locationId: 'loc-main', completeCount: false,
  openedBy: 'u', openedByName: 'O', openedAt: new Date().toISOString(), finalizedBy: null, finalizedByName: null,
  finalizedAt: null, note: null, shopifySync: null, untagged: [] }

let calls: { method: string; url: string; body?: unknown }[]

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  // Receiving + Settings → Labels
  if (url.endsWith('/label-settings')) return json({ receivingDefaultMode: 'print_stick' })
  if (url.includes('/label-runs')) return json([])
  if (url.endsWith('/receiving/sessions') && method === 'POST') return json({ sessionId: 'sess-1' }, 201)
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/sess-1')) {
    return json({ id: 'sess-1', status: 'open', mode: 'print_stick', reference: null, supplier_name: null,
      location_name: 'Main', created_at: new Date().toISOString(), finalized_at: null, line_units: 0, piece_count: 0, lines: [] })
  }
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main', is_fulfillment: true }])
  if (url.includes('/tenant/settings')) return json({ pickPackMode: 'order_queue' })
  // Pick & Pack
  if (url.endsWith('/fulfill/queue')) return json(QUEUE)
  if (url.endsWith('/pack-labels/plan')) return json({ waybills: 2, lastBatchId: null, labels: NO_LABELS })
  if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
  if (url.endsWith('/fulfill/print-jobs') && method === 'POST') return json({ jobId: 'job-1' }, 202)
  if (url.endsWith('/fulfill/print-jobs/latest')) return json(null)
  if (url.endsWith('/fulfill/print-jobs/job-1')) return json(JOB)
  if (url.includes('/pack-sessions/summary')) return json({ packedToday: 0, openSessionId: null })
  // Inventory: every piece labeled
  if (url.endsWith('/tag-stock') || url.includes('/tag-stock?')) return json({ labeled: 12, untagged: 0, variants: [], leftovers: [] })
  if (url.includes('/inventory/stock')) return json({ items: [], nextCursor: null })
  if (url.includes('/inventory') || url.includes('/catalog')) return json([])
  // Stock take
  if (url.endsWith('/stock-takes/sessions/st1/scan') && method === 'POST') {
    return json({ sessionId: 'st1', barcode: body.barcode, pieceId: 'p1', alreadyScanned: false,
      classification: 'match', scanDevice: 'hardware' })
  }
  if (url.endsWith('/stock-takes/sessions/st1')) return json(ST_SESSION)
  return json({})
}

function setup(role: 'owner' | 'manager' = 'owner') {
  calls = []
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

const posted = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.includes(suffix))

describe('Flexible labeling on, no untagged stock — today\'s flows', () => {
  test('ro1 — Receiving step 1: "Print & stick" pre-selected; the session is created as print_stick', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<Receiving />)
    await user.click(await screen.findByRole('button', { name: /new/i }))
    const printStick = await screen.findByTestId('receiving-mode-print_stick')
    await waitFor(() => expect(printStick.getAttribute('aria-pressed')).toBe('true'))
    expect(within(printStick).getByTestId('receiving-mode-default-print_stick').textContent).toBe('Your default')
    expect(screen.getByTestId('receiving-mode-count_only').getAttribute('aria-pressed')).toBe('false')

    await user.click(printStick)
    expect(screen.getByTestId('receiving-mode-chosen').textContent).toBe('Print & stick now · Change')
    await user.click(screen.getByRole('button', { name: /create/i }))
    await waitFor(() => expect(posted('/receiving/sessions')[0]?.body).toMatchObject({ mode: 'print_stick' }))
  })

  test('ro2 — Pick & Pack: one Print; waybills ticked; piece labels 0, disabled with its reason', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await screen.findByText('#101')
    expect(screen.queryByRole('button', { name: 'Print waybills' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Print' }))
    const dialog = await screen.findByTestId('print-for-packing')

    const waybills = within(dialog).getByTestId('print-what-waybills')
    await waitFor(() => expect(waybills.textContent).toContain('Waybills — 2 (Bosta orders)'))
    expect(within(waybills).getByRole('checkbox')).toBeChecked()
    expect(within(waybills).getByRole('checkbox')).not.toBeDisabled()
    const labels = within(dialog).getByTestId('print-what-labels')
    expect(labels.textContent).toContain('Piece labels — 0')
    expect(within(labels).getByRole('checkbox')).not.toBeChecked()
    expect(within(labels).getByRole('checkbox')).toBeDisabled()
    expect(within(dialog).getByTestId('print-reason-labels').textContent).toBe('No untagged pieces in these orders')

    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await screen.findByTestId('print-ready')
    expect(posted('/fulfill/print-jobs')).toHaveLength(1)
    expect(posted('/pack-labels/print')).toHaveLength(0)
  })

  test('ro3 — Inventory: "Untagged (0)" — every piece has a label, no rows', async () => {
    setup()
    renderWithProviders(<Routes><Route path="/inventory" element={<Inventory />} /></Routes>,
      { initialEntries: ['/inventory?tab=untagged'] })
    await screen.findByRole('button', { name: 'Untagged (0)' })
    expect(await screen.findByText('Every piece in the main warehouse has a label.')).toBeTruthy()
    expect(screen.queryByTestId('untagged-variants')).toBeNull()
  })

  test('ro4 — Settings → Labels is there with "Print & stick" as the default', async () => {
    setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=labels'] })
    expect(await screen.findByRole('button', { name: 'Labels' })).toBeTruthy()
    const tab = await screen.findByTestId('labels-settings')
    await waitFor(() => expect(within(tab).getByTestId('labels-mode-print_stick').querySelector('input')!.checked).toBe(true))
  })

  test('ro5 — Stock take: no untagged-count panel; a scan counts as today', async () => {
    setup()
    renderWithProviders(<Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>,
      { initialEntries: ['/stock-take/st1/scan'] })
    await userEvent.type(await screen.findByPlaceholderText('Scan barcode…'), 'P000200{Enter}')
    await waitFor(() => expect(screen.getByTestId('last-scan-feedback').textContent).toContain('Match'))
    expect(posted('/stock-takes/sessions/st1/scan')[0].body).toMatchObject({ barcode: 'P000200', condition: 'good' })
    expect(screen.queryByTestId('untagged-counts')).toBeNull()
  })
})
