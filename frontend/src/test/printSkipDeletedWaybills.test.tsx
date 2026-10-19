import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import PrintWaybillsDialog from '../pages/fulfill/PrintWaybillsDialog'

/**
 * Deleted-in-Bosta slice 1 — the print result names every waybill that wasn't printed and why:
 *  - batch dialog: "Printed N · Skipped M (X deleted in Bosta / Y rejected)", each waybill number listed
 *  - single print of a deleted waybill: a clear "deleted in Bosta" message, not the raw reason code
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

// Merged-PDF slice C (approved edit): the print is a background job — POST /fulfill/print-jobs, then
// GET /fulfill/print-jobs/{id} returns this finished job.
const RESULT = {
  jobId: 'job-1', kind: 'print', status: 'done', total: 49, done: 49, candidateCount: 49, remainingCount: 0,
  printedCount: 46, batchId: 'batch-5', batchNo: 5, reprintBatchId: null, reprintBatchNo: null,
  orderGuaranteed: true, pdfAvailable: true, pdfBytes: 100,
  createdAt: new Date().toISOString(), finishedAt: new Date().toISOString(), expiresAt: null,
  excluded: [
    { orderNumber: '72375', trackingNumber: '5489799252', reason: 'DELETED_IN_BOSTA' },
    { orderNumber: '71618', trackingNumber: '8404104765', reason: 'BOSTA_REJECTED_UNISOLATED' },
    { orderNumber: null, trackingNumber: '2442100463', reason: 'BOSTA_REJECTED:Bosta mass-awb error (400 BAD_REQUEST): {"success":false,"message":"The Following Orders can\'t be printed…' },
  ],
  message: null,
}

const QUEUE = [{
  id: 'o1', number: '#1001', customer_name: 'Customer', status: 'new', payment_method: 'cod', cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false,
  awb_printed: false,
}]

const DETAIL = {
  id: 'o1', number: '#1001', customer_name: 'Customer', customer_phone: null, status: 'new',
  payment_method: 'cod', cod_amount: null, locked_by: null, is_self_pickup: false, is_exchange: false,
  cancel_requested_at: null, shipment_id: 'ship-1', tracking_number: '5489799252', shipment_has_courier: true,
  awbPrinted: false,
  items: [{ id: 'i1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Shirt', imageUrl: null,
            quantity: 1, allocated: 0, allocatedPieces: [] }],
}

let fetchFn: ReturnType<typeof vi.fn>

describe('print result — deleted in Bosta / rejected', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    fetchFn = vi.fn((url: string, opts?: RequestInit) => {
      if (url.endsWith('/fulfill/queue')) return json(QUEUE)
      if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
      if (url.endsWith('/fulfill/print-jobs') && opts?.method === 'POST') return json({ jobId: 'job-1' }, 202)
      if (url.endsWith('/fulfill/print-jobs/latest')) return json(null, 204)
      if (url.endsWith('/fulfill/print-jobs/job-1')) return json(RESULT)
      if (url.endsWith('/fulfill/o1')) return json(DETAIL)
      if (url.endsWith('/bosta/awb/print')) {
        return json({ pdfBase64List: [], emailMessage: null,
                      exceptions: [{ trackingNumber: '5489799252', reason: 'DELETED_IN_BOSTA' }] })
      }
      return json({})
    })
    stubFetchWithShellDefaults(fetchFn)
    vi.spyOn(window, 'open').mockImplementation(() => ({ location: { href: '' }, close: vi.fn() }) as unknown as Window)
    URL.createObjectURL = vi.fn(() => 'blob:pdf')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('batch result: printed / skipped summary, every skipped waybill number with its reason', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={49} allCount={49} onClose={vi.fn()} />)
    const dialog = await screen.findByTestId('print-batch-dialog')
    await user.click(within(dialog).getByRole('button', { name: /^Print \d+ waybills?$/ }))

    const result = await screen.findByTestId('print-batch-result')
    expect(within(result).getByTestId('print-batch-summary'))
      .toHaveTextContent('Printed 46 · Skipped 3 (1 deleted in Bosta / 2 rejected)')
    for (const tn of ['5489799252', '8404104765', '2442100463']) {
      expect(within(result).getByText(tn)).toBeInTheDocument()
    }
    expect(within(result).getByText('72375')).toBeInTheDocument()
    expect(within(result).getByText('Deleted in Bosta — not printed')).toBeInTheDocument()
    expect(within(result).getByText('Not printed — Bosta refused part of this print. Print again.')).toBeInTheDocument()
    expect(within(result).getByText('Bosta refused to print it')).toBeInTheDocument()
    expect(within(result).queryByText(/BOSTA_REJECTED|DELETED_IN_BOSTA/)).toBeNull()
  })

  test('single print of a deleted waybill shows the "deleted in Bosta" message, not the reason code', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1001'))
    await user.click(await screen.findByTestId('btn-print-awb'))

    expect(await screen.findByText(
      "This waybill was deleted in Bosta, so it can't be printed. Book a new delivery in Bosta for this order.",
    )).toBeInTheDocument()
    expect(screen.queryByText('DELETED_IN_BOSTA')).toBeNull()
  })
})
