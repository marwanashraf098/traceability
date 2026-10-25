import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import Fulfill from '../pages/Fulfill'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import * as api from '../api'

/**
 * Flexible labeling slice 4 — packing labels in Pick & Pack.
 *   pl1  queue (switch on): a row's "Labels" → summary "N labels for M orders" (+ labeled stock / reprinted lines)
 *        → Print → the single part's PDF opens; another row's "Labels" prints that one order (UI pass 2026-10-25: the
 *        queue-level "Print labels" became the one "Print" — flexibleUiPass pp2–pp5)
 *   pl2  switch off: no "Print labels", no row button — the queue as before
 *   pl3  pick screen: the first unfilled line is highlighted, "Label printed" / "Needs label", the counter; when the
 *        line fills the highlight moves on; "Print 1 replacement label" prints for the highlighted line
 *   pl4  pick screen: a label refusal shows the server's own words (EN), not the code's static text
 *   pl5  more than one part: one "Open part i of n" per part, each opening its own orders
 *   pl6  waybill mode: highlight, counter and replacement on the open order's card; a refusal's own words
 *   st1  stock take: only a refusal WITH a readable message becomes ServerMessageError (body-less stays a plain error)
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    blob: async () => new Blob(['%PDF']),
  })
}

const pdf = () => Promise.resolve({
  ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/pdf' : null) },
  json: async () => null, blob: async () => new Blob(['%PDF']),
})

const QUEUE = [
  { id: 'o1', number: '#101', customer_name: 'Mona', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 2, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, awb_printed: false },
  { id: 'o2', number: '#102', customer_name: 'Omar', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: true, awb_printed: false },
]

function line(id: string, title: string, allocated: number, labelsUnused: number, untaggedStock: number) {
  return { id, variant_id: `v-${id}`, sku: `SKU-${id}`, variant_title: title, product_title: 'Linen Shirt',
    imageUrl: null, quantity: 1, allocated, allocatedPieces: [], labelsUnused, untaggedStock }
}

function detail(firstAllocated: number) {
  return {
    id: 'o1', number: '#101', customer_name: 'Mona', customer_phone: null, status: 'new', payment_method: 'cod',
    cod_amount: '100', locked_by: null, is_self_pickup: false, is_exchange: false, cancel_requested_at: null,
    shipment_id: 's1', tracking_number: '74820001', shipment_has_courier: true, awbPrinted: true,
    items: [line('l1', 'Blue', firstAllocated, firstAllocated ? 0 : 1, 3), line('l2', 'Red', 0, 0, 2)],
    labels: { printed: 1, used: firstAllocated },
  }
}

const SUMMARY = { labels: 3, orders: 2, reused: 1, unitsWithoutLabel: 1, ordersConsidered: 3,
  parts: [{ part: 1, of: 1, labels: 3, orderIds: ['o1', 'o2'] }] }

let calls: Array<{ method: string; url: string; body?: unknown }>
let firstAllocated: number
let summary: typeof SUMMARY
let scanResponse: unknown

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/fulfill/queue')) return json(QUEUE)
  if (url.endsWith('/fulfill/o1') && method === 'GET') return json(detail(firstAllocated))
  if (url.endsWith('/fulfill/o1/scan')) {
    firstAllocated = 1
    return json(scanResponse)
  }
  if (url.endsWith('/pack-labels/preview')) return json(summary)
  if (url.endsWith('/pack-labels/print')) return json(summary)
  if (url.endsWith('/pack-labels/pdf') || url.endsWith('/pack-labels/replacement')) return pdf()
  return json({})
}

function setup(flexibleLabeling: boolean) {
  calls = []
  firstAllocated = 0
  summary = SUMMARY
  scanResponse = { success: true, code: 'SCANNED', message: null, pieceId: 'p1', barcode: 'P000123',
    allocatedCount: 1, requiredQuantity: 1, allComplete: false }
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('worker'))
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'W', role: 'worker', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

const posted = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix))

describe('Print labels — queue', () => {
  test('pl1 — "Print labels" shows the summary, prints and opens the PDF; a row prints one order', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(within(await screen.findByTestId('row-labels-o1')).getByRole('button', { name: 'Labels' }))
    const dialog = await screen.findByTestId('print-labels-dialog')
    await waitFor(() => expect(within(dialog).getByTestId('print-labels-summary').textContent).toContain('3 labels for 2 orders'))
    expect(dialog.textContent).toContain('1 piece is packed from labeled stock')
    expect(dialog.textContent).toContain('1 was printed before')
    expect(posted('/pack-labels/preview')[0].body).toEqual({ orderId: 'o1' })

    await user.click(within(dialog).getByRole('button', { name: 'Print 3 labels' }))
    await waitFor(() => expect(posted('/pack-labels/pdf')).toHaveLength(1))
    expect(posted('/pack-labels/print')[0].body).toEqual({ orderId: 'o1' })
    expect(posted('/pack-labels/pdf')[0].body).toEqual({ orderIds: ['o1', 'o2'] })
    expect(window.open).toHaveBeenCalledWith('blob:x', '_blank')
    await user.click(within(dialog).getByRole('button', { name: 'Done' }))

    await user.click(within(screen.getByTestId('row-labels-o2')).getByRole('button', { name: 'Labels' }))
    await screen.findByTestId('print-labels-dialog')
    await waitFor(() => expect(posted('/pack-labels/preview').at(-1)!.body).toEqual({ orderId: 'o2' }))
    expect(screen.queryByTestId('fulfill-pick')).toBeNull()          // the row button didn't open the order
  })

  test('pl2 — switch off: no "Print labels" and no row button', async () => {
    setup(false)
    renderWithProviders(<Fulfill />)
    await screen.findByText('#101')
    await screen.findByRole('button', { name: 'Print waybills' }).catch(() => null)
    expect(screen.queryByRole('button', { name: 'Print labels' })).toBeNull()
    expect(screen.queryByTestId('row-labels-o1')).toBeNull()
  })

  test('pl5 — several parts: one button per part, each opening its own orders', async () => {
    setup(true)
    summary = { ...SUMMARY, labels: 2600, orders: 3, parts: [
      { part: 1, of: 2, labels: 1500, orderIds: ['o1'] }, { part: 2, of: 2, labels: 1100, orderIds: ['o2', 'o3'] }] }
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(within(await screen.findByTestId('row-labels-o1')).getByRole('button', { name: 'Labels' }))
    const dialog = await screen.findByTestId('print-labels-dialog')
    await waitFor(() => expect(dialog.textContent).toContain('Printed in 2 parts'))
    await user.click(within(dialog).getByRole('button', { name: 'Print 2600 labels' }))
    await user.click(await within(dialog).findByRole('button', { name: 'Open part 2 of 2 (1100 labels)' }))
    await waitFor(() => expect(posted('/pack-labels/pdf')).toHaveLength(1))
    expect(posted('/pack-labels/pdf')[0].body).toEqual({ orderIds: ['o2', 'o3'] })
    expect(within(dialog).getByRole('button', { name: 'Open part 1 of 2 (1500 labels)' })).toBeTruthy()
  })
})

describe('Pick screen (queue mode)', () => {
  test('pl3 — highlight, line status, counter, auto-advance and the replacement label', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#101'))
    await screen.findByTestId('fulfill-pick')
    expect((await screen.findByTestId('pick-line-l1')).dataset.highlighted).toBe('true')
    expect(screen.getByTestId('pick-line-l2').dataset.highlighted).toBeUndefined()
    expect(screen.getByTestId('line-label-l1').textContent).toContain('Label printed')
    expect(screen.getByTestId('line-label-l2').textContent).toContain('Needs label')
    expect(screen.getByTestId('line-label-l2').textContent).toContain('Print 1 replacement label')
    expect(screen.getByTestId('pack-labels-counter').textContent).toBe('Labels for this order: 0 of 1 used')

    await user.click(screen.getByTestId('pick-line-l1'))                // tapped: still the highlighted line
    expect(screen.getByTestId('pick-line-l1').dataset.highlighted).toBe('true')
    await user.type(screen.getByPlaceholderText(/scan/i), 'P000123{Enter}')
    await waitFor(() => expect(screen.getByTestId('pick-line-l2').dataset.highlighted).toBe('true'))
    expect(screen.getByTestId('pack-labels-counter').textContent).toBe('Labels for this order: 1 of 1 used')
    expect(posted('/fulfill/o1/scan')[0].body).toEqual({ barcode: 'P000123' })     // the scan request is unchanged

    await user.click(screen.getByRole('button', { name: 'Print 1 replacement label' }))
    await waitFor(() => expect(posted('/pack-labels/replacement')).toHaveLength(1))
    expect(posted('/pack-labels/replacement')[0].body).toMatchObject({ orderId: 'o1', orderItemId: 'l2' })
    expect(window.open).toHaveBeenCalledWith('blob:x', '_blank')
  })

  test('pl4 — a label refusal shows the server\'s own words', async () => {
    setup(true)
    scanResponse = { success: false, code: 'WRONG_VARIANT', pieceId: null, barcode: null, allocatedCount: 0,
      requiredQuantity: 0, allComplete: false,
      message: 'This label is for Linen Shirt · Green, this order needs Linen Shirt · Blue', messageAr: 'ar' }
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#101'))
    await user.type(await screen.findByPlaceholderText(/scan/i), 'P000999{Enter}')
    expect(await screen.findByText(/This label is for Linen Shirt · Green, this order needs Linen Shirt · Blue/)).toBeTruthy()
  })
})

describe('Waybill mode', () => {
  test('pl6 — highlight, counter and replacement on the open order; a refusal\'s own words', async () => {
    setup(true)
    const card = { ...detail(0), area: null, courierType: 'delivery', batchNo: null, batchPrintedAt: null }
    const view = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-23T08:00:00Z', workerName: 'W',
      counters: { packed: 0, setAside: 0, rejected: 0, left: 3 }, recent: [], openOrder: card }
    stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
      const method = (opts.method ?? 'GET').toUpperCase()
      calls.push({ method, url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
      if (url.endsWith('/orders/o1/scan')) {
        return json({ status: 'rejected', order: card, packed: null, failCode: null, failMessage: null,
          scan: { success: false, code: 'NO_UNTAGGED_STOCK', message: 'No untagged piece of this product is left in the main warehouse — keep this label for the next one',
                  messageAr: 'ar', pieceId: null, barcode: null, allocatedCount: 0, requiredQuantity: 0, allComplete: false } })
      }
      if (url.endsWith('/pack-labels/replacement')) return pdf()
      if (url.endsWith('/pack-sessions/sess-1')) return json(view)
      return json({})
    }), { me: { name: 'W', role: 'worker', flexibleLabeling: true } })
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={view as unknown as api.PackSessionView} onEnded={() => {}} />)

    expect((await screen.findByTestId('pack-line-l1')).dataset.highlighted).toBe('true')
    expect(screen.getByTestId('pack-labels-counter').textContent).toBe('Labels for this order: 0 of 1 used')
    await user.click(screen.getByTestId('pack-line-l2'))                // tap a line: the replacement is for it
    expect(screen.getByTestId('pack-line-l2').dataset.highlighted).toBe('true')
    await user.click(screen.getByRole('button', { name: 'Print 1 replacement label' }))
    await waitFor(() => expect(posted('/pack-labels/replacement')).toHaveLength(1))
    expect(posted('/pack-labels/replacement')[0].body).toMatchObject({ orderId: 'o1', orderItemId: 'l2' })

    await user.type(screen.getByRole('textbox'), 'P000555{Enter}')
    expect(await screen.findByText(/No untagged piece of this product is left in the main warehouse/)).toBeTruthy()
  })
})

describe('Stock take scan refusals', () => {
  beforeEach(() => { setAccessToken(fakeJwt('owner')) })

  test('st1 — only a refusal with a readable message becomes ServerMessageError', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockReturnValueOnce(Promise.resolve({ ok: false, status: 409, statusText: '', json: async () => { throw new Error('no body') } }))
      .mockReturnValueOnce(json({ code: 'LABEL_NOT_USED_YET', message_en: 'Label not used yet', message_ar: 'ar' }, 409)))
    const bare = await api.scanStockTakePiece('st1', 'P000001', 'good').catch(e => e)
    expect(bare).toBeInstanceOf(Error)
    expect(bare).not.toBeInstanceOf(api.ServerMessageError)
    const readable = await api.scanStockTakePiece('st1', 'P000002', 'good').catch(e => e)
    expect(readable).toBeInstanceOf(api.ServerMessageError)
    expect((readable as api.ServerMessageError).messageEn).toBe('Label not used yet')
  })
})
