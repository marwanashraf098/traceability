import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import PrintWaybillsDialog from '../pages/fulfill/PrintWaybillsDialog'
import { setPrintJobPollMs } from '../pages/fulfill/PrintJobPanel'

/**
 * Merged-PDF slice C — the print dialog follows a background print job:
 *  - progress "Preparing waybills… 98 of 245" with a bar, then the result
 *  - "Open waybills" opens the PDF in a tab opened inside the click; "Download PDF" saves it
 *  - a second print while one runs (409) shows that job's progress, not an error
 *  - queued: "Waiting for another print to finish"
 *  - closing and reopening the dialog finds the running job again (GET /print-jobs/latest)
 *  - Bosta's API key refused: the connection message, on the batch result and on single print
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function pdfResponse() {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-disposition' ? 'attachment; filename="waybills-batch-8.pdf"' : null) },
    blob: async () => new Blob(['%PDF-1.4 fake'], { type: 'application/pdf' }),
  })
}

const job = (extra: Record<string, unknown> = {}) => ({
  jobId: 'job-1', kind: 'print', status: 'done', total: 245, done: 245, candidateCount: 245, remainingCount: 0,
  printedCount: 245, excluded: [], batchId: 'batch-8', batchNo: 8, reprintBatchId: null, reprintBatchNo: null,
  orderGuaranteed: true, pdfAvailable: true, pdfBytes: 1000, message: null,
  createdAt: new Date().toISOString(), finishedAt: new Date().toISOString(), expiresAt: null,
  ...extra,
})

let fetchFn: ReturnType<typeof vi.fn>
let jobResponses: unknown[]
let latest: unknown
let startResponse: () => ReturnType<typeof json>

function makeFetch() {
  return vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/print-batches/options')) return json({ defaultPaper: 'A6' })
    if (url.endsWith('/fulfill/print-jobs') && opts?.method === 'POST') return startResponse()
    if (url.endsWith('/fulfill/print-jobs/latest')) return latest ? json(latest) : json(null, 204)
    if (/\/fulfill\/print-jobs\/job-[a-z0-9]+\/pdf(\?download=true)?$/.test(url)) return pdfResponse()
    if (/\/fulfill\/print-jobs\/job-[a-z0-9]+$/.test(url)) {
      return json(jobResponses.length > 1 ? jobResponses.shift() : jobResponses[0])
    }
    return json({})
  })
}

describe('print jobs — dialog', () => {
  let popup: { location: { href: string }; close: ReturnType<typeof vi.fn> }

  beforeEach(() => {
    setPrintJobPollMs(20)
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    jobResponses = [job()]
    latest = null
    startResponse = () => json({ jobId: 'job-1' }, 202)
    fetchFn = makeFetch()
    stubFetchWithShellDefaults(fetchFn)
    popup = { location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockImplementation(() => popup as unknown as Window)
    URL.createObjectURL = vi.fn(() => 'blob:job-pdf')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('progress "Preparing waybills… 98 of 245" with a bar, then the result', async () => {
    jobResponses = [
      job({ status: 'running', done: 98, printedCount: 98, finishedAt: null }),
      job({ status: 'running', done: 196, printedCount: 196, finishedAt: null }),
      job(),
    ]
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={245} allCount={245} onClose={vi.fn()} />)
    const dialog = await screen.findByTestId('print-batch-dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Print 245 waybills' }))

    expect(await screen.findByText('Preparing waybills… 98 of 245')).toBeInTheDocument()
    expect(screen.getByText(/the print keeps going/)).toBeInTheDocument()
    const result = await screen.findByTestId('print-batch-result')
    expect(within(result).getByText('Batch #8: 245 waybills printed.')).toBeInTheDocument()
    expect(window.open).not.toHaveBeenCalled()                       // nothing opens until the user clicks
  })

  test('Open waybills opens the PDF in a tab opened by the click; Download saves the file', async () => {
    const clicks: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicks.push(this.download)
    })
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={245} allCount={245} onClose={vi.fn()} />)
    await user.click(within(await screen.findByTestId('print-batch-dialog')).getByRole('button', { name: 'Print 245 waybills' }))
    const result = await screen.findByTestId('print-batch-result')

    await user.click(within(result).getByRole('button', { name: 'Open waybills' }))
    expect(window.open).toHaveBeenCalledWith('', '_blank')
    await waitFor(() => expect(popup.location.href).toBe('blob:job-pdf'))

    await user.click(within(result).getByRole('button', { name: 'Download PDF' }))
    await waitFor(() => expect(clicks).toEqual(['waybills-batch-8.pdf']))
    expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/print-jobs/job-1/pdf?download=true'))).toBe(true)
  })

  test('409: a print is already running — its progress is shown, not an error', async () => {
    startResponse = () => json({ code: 'PRINT_JOB_RUNNING', message_en: 'A print is already running for this store.',
                                 message_ar: 'x', jobId: 'job-other' }, 409)
    jobResponses = [job({ jobId: 'job-other', status: 'running', done: 49, total: 300, finishedAt: null })]
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={10} allCount={10} onClose={vi.fn()} />)
    await user.click(within(await screen.findByTestId('print-batch-dialog')).getByRole('button', { name: 'Print 10 waybills' }))

    expect(await screen.findByText('A print is already running — here is its progress.')).toBeInTheDocument()
    expect(await screen.findByText('Preparing waybills… 49 of 300')).toBeInTheDocument()
    expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/print-jobs/job-other'))).toBe(true)
    expect(screen.queryByText("Couldn't print waybills. Try again.")).not.toBeInTheDocument()
  })

  test('queued: "Waiting for another print to finish"', async () => {
    jobResponses = [job({ status: 'queued', done: 0, total: 0, printedCount: 0, finishedAt: null })]
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={10} allCount={10} onClose={vi.fn()} />)
    await user.click(within(await screen.findByTestId('print-batch-dialog')).getByRole('button', { name: 'Print 10 waybills' }))

    expect(await screen.findByText('Waiting for another print to finish')).toBeInTheDocument()
  })

  test('closing and reopening the dialog finds the running job again, without starting a new one', async () => {
    latest = job({ status: 'running', done: 147, finishedAt: null })
    jobResponses = [job({ status: 'running', done: 147, finishedAt: null })]
    renderWithProviders(<PrintWaybillsDialog newCount={245} allCount={245} onClose={vi.fn()} />)

    expect(await screen.findByText('Preparing waybills… 147 of 245')).toBeInTheDocument()
    expect(fetchFn.mock.calls.some(([u, o]) =>
      String(u).endsWith('/print-jobs') && (o as RequestInit | undefined)?.method === 'POST')).toBe(false)
  })

  test('a finished print is offered again on reopening: last print line with Open waybills', async () => {
    latest = job({ printedCount: 120 })
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={5} allCount={5} onClose={vi.fn()} />)

    const last = await screen.findByTestId('last-print')
    expect(last).toHaveTextContent('Last print: 120 printed')
    await user.click(within(last).getByRole('button', { name: 'Open waybills' }))
    await waitFor(() => expect(popup.location.href).toBe('blob:job-pdf'))
    // The options stay usable for a new print.
    expect(screen.getByRole('button', { name: 'Print 5 waybills' })).toBeEnabled()
  })

  test('API key refused: the connection message and each waybill listed "print again"', async () => {
    jobResponses = [job({
      status: 'failed', printedCount: 0, pdfAvailable: false, batchId: null, batchNo: null, total: 2, done: 2,
      excluded: [
        { orderNumber: '#1', trackingNumber: '5000000001', reason: 'BOSTA_AUTH' },
        { orderNumber: '#2', trackingNumber: '5000000002', reason: 'BOSTA_AUTH' },
      ],
      message: { code: 'BOSTA_AUTH', messageEn: 'Bosta connection problem — check Settings → Connections.', messageAr: 'x' },
    })]
    const user = userEvent.setup()
    renderWithProviders(<PrintWaybillsDialog newCount={2} allCount={2} onClose={vi.fn()} />)
    await user.click(within(await screen.findByTestId('print-batch-dialog')).getByRole('button', { name: 'Print 2 waybills' }))

    const result = await screen.findByTestId('print-batch-result')
    expect(within(result).getByText('Bosta connection problem — check Settings → Connections.')).toBeInTheDocument()
    expect(within(result).getByTestId('print-batch-summary')).toHaveTextContent('Printed 0 · Skipped 2 (2 to print again)')
    expect(within(result).getAllByText('Bosta connection problem — check Settings → Connections')).toHaveLength(2)
    expect(within(result).queryByRole('button', { name: 'Open waybills' })).not.toBeInTheDocument()
  })
})

describe('single print — API key refused', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
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
    fetchFn = vi.fn((url: string) => {
      if (url.endsWith('/fulfill/queue')) return json(QUEUE)
      if (url.endsWith('/fulfill/o1')) return json(DETAIL)
      if (url.endsWith('/bosta/awb/print')) {
        return json({ pdfBase64List: [], emailMessage: null, exceptions: [{ trackingNumber: '5489799252', reason: 'BOSTA_AUTH' }] })
      }
      return json({})
    })
    stubFetchWithShellDefaults(fetchFn)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('shows the connection message, not the reason code', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#1001'))
    await user.click(await screen.findByTestId('btn-print-awb'))

    expect(await screen.findByText('Bosta connection problem — check Settings → Connections.')).toBeInTheDocument()
    expect(screen.queryByText('BOSTA_AUTH')).toBeNull()
  })
})
