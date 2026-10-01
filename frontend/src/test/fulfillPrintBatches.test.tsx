import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import GatherList from '../pages/GatherList'

/**
 * Pick & Pack S2 — batch waybill printing in queue mode.
 *  - queue header: "Waybills: N printed · M not printed yet" from the queue rows' awb_printed
 *  - Print waybills dialog: counts, options sent to POST /fulfill/print-batches, merged PDF
 *    opened, excluded + "may not be in the order you chose" shown
 *  - a batch-printed order doesn't force a reprint before Complete (awbPrinted)
 *  - GatherList ?batch=<id> asks for that batch only
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const row = (id: string, number: string, extra: Record<string, unknown> = {}) => ({
  id, number, customer_name: 'Customer ' + number, status: 'new', payment_method: 'cod', cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false,
  awb_printed: false, ...extra,
})

const QUEUE = [
  row('o1', '#1001', { awb_printed: true }),
  row('o2', '#1002'),
  row('o3', '#1003'),
  row('o4', '#1004', { is_self_pickup: true }),   // never counted — no waybill
]

const RESULT = {
  batchId: 'batch-9', batchNo: 4, waybillCount: 1, candidateCount: 2, orderGuaranteed: false,
  pdfBase64: btoa('%PDF-1.4 fake'),
  excluded: [{ orderNumber: '#1003', trackingNumber: '777', reason: 'BOSTA_EMAIL_PATH' }],
  message: null,
}

const DETAIL = {
  id: 'o1', number: '#1001', customer_name: 'Customer #1001', customer_phone: null, status: 'new',
  payment_method: 'cod', cod_amount: null, locked_by: null, is_self_pickup: false, is_exchange: false,
  cancel_requested_at: null, shipment_id: 'ship-1', tracking_number: '2944282510', shipment_has_courier: true,
  awbPrinted: true,
  items: [{ id: 'i1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Shirt', imageUrl: null,
            quantity: 1, allocated: 1,
            allocatedPieces: [{ piece_id: 'p1', barcode: 'P000001', allocation_status: 'active', piece_status: 'reserved' }] }],
}

let fetchFn: ReturnType<typeof vi.fn>

function makeFetch() {
  return vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/queue')) return json(QUEUE)
    if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
    if (url.endsWith('/fulfill/print-batches') && opts?.method === 'POST') return json(RESULT)
    if (url.endsWith('/fulfill/o1')) return json(DETAIL)
    if (url.includes('/fulfill/gather')) return json({ generatedAt: new Date().toISOString(), orderCount: 1, rows: [] })
    return json({})
  })
}

describe('Pick & Pack — batch waybill printing', () => {
  let popup: { location: { href: string }; close: ReturnType<typeof vi.fn> }

  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    fetchFn = makeFetch()
    stubFetchWithShellDefaults(fetchFn)
    popup = { location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockImplementation(() => popup as unknown as Window)
    URL.createObjectURL = vi.fn(() => 'blob:merged-pdf')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('queue header shows printed / not printed counts, self-pickup excluded', async () => {
    renderWithProviders(<Fulfill />)
    expect(await screen.findByText('Waybills: 1 printed · 2 not printed yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Print waybills/ })).toBeInTheDocument()
  })

  test('dialog sends the chosen options, opens the merged PDF, shows excluded and the order warning', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByRole('button', { name: /Print waybills/ }))

    const dialog = await screen.findByTestId('print-batch-dialog')
    // New only = 2 (not printed), Everything = 3; paper preselected from the store (A6).
    expect(within(dialog).getByText('New only').closest('label')).toHaveTextContent('2')
    expect(within(dialog).getByText('Everything ready to pack').closest('label')).toHaveTextContent('3')
    await waitFor(() => expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/print-batches/options'))).toBe(true))

    await user.click(within(dialog).getByText('Newest order first'))
    await user.click(within(dialog).getByText('Also print a pick list'))
    await user.click(within(dialog).getByRole('button', { name: 'Print 2 waybills' }))

    const result = await screen.findByTestId('print-batch-result')
    const post = fetchFn.mock.calls.find(([u, o]) =>
      String(u).endsWith('/fulfill/print-batches') && (o as RequestInit | undefined)?.method === 'POST')
    expect(JSON.parse((post![1] as RequestInit).body as string)).toEqual({ scope: 'new', paper: 'A6', sort: 'newest' })

    expect(popup.location.href).toBe('blob:merged-pdf')
    expect(within(result).getByText('Batch #4: 1 waybill printed.')).toBeInTheDocument()
    expect(within(result).getByText('Waybills may not be in the order you chose.')).toBeInTheDocument()
    expect(within(result).getByText('#1003')).toBeInTheDocument()
    expect(within(result).getByText('Bosta will email this waybill instead')).toBeInTheDocument()

    await user.click(within(result).getByRole('button', { name: 'Open pick list' }))
    expect(window.open).toHaveBeenLastCalledWith('/fulfill/gather?batch=batch-9', '_blank')
  })

  test('a batch-printed order shows Complete without asking for a reprint', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1001'))
    expect(await screen.findByRole('button', { name: /Complete/ })).toBeInTheDocument()
  })

  test('GatherList ?batch=<id> requests that batch only', async () => {
    renderWithProviders(
      <Routes><Route path="/fulfill/gather" element={<GatherList />} /></Routes>,
      { initialEntries: ['/fulfill/gather?batch=batch-9'] },
    )
    await waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/fulfill/gather?batchId=batch-9'))).toBe(true))
    expect(await screen.findByText(/Pick list for this print batch/)).toBeInTheDocument()
  })
})
