import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import PrintWaybillsDialog from '../pages/fulfill/PrintWaybillsDialog'

/**
 * Merged-PDF slice A — Bosta busy (rate limit) or not answering is "print again", never a rejection:
 *  - batch dialog: a "Bosta is busy" alert, the skipped waybills counted as "to print again", each listed
 *  - single print while Bosta is busy: the busy message, not the raw reason code
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const RESULT = {
  batchId: 'batch-7', batchNo: 7, waybillCount: 25, candidateCount: 49, remainingCount: 0, orderGuaranteed: true,
  pdfBase64: btoa('%PDF-1.4 fake'),
  excluded: [
    { orderNumber: '72375', trackingNumber: '5489799252', reason: 'BOSTA_BUSY' },
    { orderNumber: '71618', trackingNumber: '8404104765', reason: 'BOSTA_TEMPORARY' },
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

describe('print result — Bosta busy / not answering', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    fetchFn = vi.fn((url: string, opts?: RequestInit) => {
      if (url.endsWith('/fulfill/queue')) return json(QUEUE)
      if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
      if (url.endsWith('/fulfill/print-batches') && opts?.method === 'POST') return json(RESULT)
      if (url.endsWith('/fulfill/o1')) return json(DETAIL)
      if (url.endsWith('/bosta/awb/print')) {
        return json({ pdfBase64List: [], emailMessage: null,
                      exceptions: [{ trackingNumber: '5489799252', reason: 'BOSTA_BUSY' }] })
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

  test('batch result: busy alert, skipped counted as "to print again", every waybill listed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={49} allCount={49} onClose={vi.fn()} />)
    const dialog = await screen.findByTestId('print-batch-dialog')
    await user.click(within(dialog).getByRole('button', { name: /^Print \d+ waybills?$/ }))

    const result = await screen.findByTestId('print-batch-result')
    expect(within(result).getByText('Bosta is busy — print again in a few minutes.')).toBeInTheDocument()
    expect(within(result).getByTestId('print-batch-summary'))
      .toHaveTextContent('Printed 25 · Skipped 2 (2 to print again)')
    for (const tn of ['5489799252', '8404104765']) expect(within(result).getByText(tn)).toBeInTheDocument()
    expect(within(result).getByText('Bosta is busy — print again in a few minutes')).toBeInTheDocument()
    expect(within(result).getByText('Not printed — print again')).toBeInTheDocument()
    expect(within(result).queryByText(/BOSTA_BUSY|BOSTA_TEMPORARY|rejected/)).toBeNull()
  })

  test('single print while Bosta is busy shows the busy message, not the reason code', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1001'))
    await user.click(await screen.findByTestId('btn-print-awb'))

    expect(await screen.findByText('Bosta is busy — print again in a few minutes.')).toBeInTheDocument()
    expect(screen.queryByText('BOSTA_BUSY')).toBeNull()
  })
})
