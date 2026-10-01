import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'

/**
 * Pick & Pack S3 — waybill scan mode in the browser:
 *  - /fulfill switches on GET /fulfill/mode (queue page unchanged in order_queue mode)
 *  - waybill page tiles + self-pickup entry
 *  - session: waybill opens the order card (with product images), piece scans, auto-complete
 *    flash, rejection screen, complete_failed + Try again, set aside (reason required),
 *    Undo only while the order is open, End disabled while an order is open.
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const LINE = {
  id: 'line-1', variant_id: 'v1', sku: 'LS-OLV-M', variant_title: 'Olive · M', product_title: 'Linen shirt',
  imageUrl: 'https://cdn.shopify.com/shirt.jpg', quantity: 2, allocated: 0, allocatedPieces: [] as unknown[],
}

function card(allocated: number) {
  return {
    id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
    tracking_number: '74821903', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: 3,
    batchPrintedAt: '2026-10-01T07:42:00Z',
    items: [{ ...LINE, allocated,
      allocatedPieces: Array.from({ length: allocated }, (_, i) => ({ piece_id: `p${i + 1}`, barcode: `P00000${i + 1}`, allocation_status: 'active', piece_status: 'reserved' })) }],
  }
}

function view(openOrder: unknown = null, packed = 0) {
  return {
    id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-01T07:05:00Z', workerName: 'Ahmed',
    counters: { packed, setAside: 0, rejected: 0, left: 16 }, recent: [], openOrder,
  }
}

let mode: string
let calls: Array<{ method: string; url: string; body?: unknown }>
let pieceScans: number
let pieceResponses: Array<() => unknown>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/fulfill/mode')) return json({ mode })
  if (url.endsWith('/fulfill/queue')) {
    return json([
      { id: 'o1', status: 'new', is_self_pickup: false, awb_printed: true },
      { id: 'o2', status: 'new', is_self_pickup: false, awb_printed: false },
      { id: 'o3', status: 'new', is_self_pickup: false, awb_printed: true },
      { id: 'o4', status: 'new', is_self_pickup: true, awb_printed: false, number: '#SP1', customer_name: 'Self', total_units: 1, scanned_units: 0 },
    ])
  }
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 31, openSessionId: null })
  if (url.endsWith('/pack-sessions') && method === 'POST') return json(view())
  if (url.endsWith('/pack-sessions/sess-1') && method === 'GET') return json(view(null, pieceScans >= 2 ? 1 : 0))
  if (url.endsWith('/sess-1/waybill')) {
    if (body.code === 'BAD') {
      return json({ result: 'rejected', order: null, code: 'CANCELLED', subReason: null, orderNumber: '#1039', who: null,
        at: '2026-10-01T08:05:00Z', state: null,
        messageEn: 'Order #1039 was cancelled. Put the waybill to one side and give it to a manager.', messageAr: 'x' })
    }
    return json({ result: 'opened', order: card(0), code: null })
  }
  if (url.endsWith('/orders/order-1/scan') && method === 'POST') {
    pieceScans++
    return json(pieceResponses.shift()!())
  }
  if (url.endsWith('/orders/order-1/complete')) {
    return json({ status: 'completed', scan: null, order: null,
      packed: { orderId: 'order-1', orderNumber: '#1047', customerName: 'Youssef Adel', pieces: 2 }, failCode: null, failMessage: null })
  }
  if (url.includes('/orders/order-1/scan/') && method === 'DELETE') return Promise.resolve({ ok: true, status: 204, headers: { get: () => '0' }, json: async () => null })
  if (url.endsWith('/orders/order-1/set-aside')) return json({ piecesReturned: 1 })
  if (url.endsWith('/sess-1/end')) return Promise.resolve({ ok: true, status: 204, headers: { get: () => '0' }, json: async () => null })
  return json({})
}

const scanned = () => ({ status: 'scanned', scan: { success: true, code: 'SCANNED', pieceId: 'p1', barcode: 'P000001' }, order: card(1) })
const completed = () => ({ status: 'completed', scan: { success: true, code: 'SCANNED' }, order: null,
  packed: { orderId: 'order-1', orderNumber: '#1047', customerName: 'Youssef Adel', pieces: 2 } })
const failed = () => ({ status: 'complete_failed', scan: { success: true, code: 'SCANNED' }, order: card(2),
  failCode: 'WAYBILL_NOT_ON_ORDER', failMessage: 'x' })

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  mode = 'waybill_scan'
  calls = []
  pieceScans = 0
  pieceResponses = [scanned, completed]
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { vi.unstubAllGlobals() })

function renderPickPack(path = '/fulfill') {
  return renderWithProviders(
    <Routes><Route path="/fulfill" element={<FulfillRoute />} /></Routes>,
    { initialEntries: [path] },
  )
}

async function startSession() {
  const user = userEvent.setup()
  renderPickPack()
  await user.click(await screen.findByRole('button', { name: /Start pack session/ }))
  await screen.findByTestId('session-waiting')
  return user
}

async function scan(user: ReturnType<typeof userEvent.setup>, code: string) {
  const input = screen.getByRole('textbox')
  await user.type(input, `${code}{Enter}`)
}

describe('Pick & Pack — waybill scan mode', () => {
  test('order_queue mode renders the existing queue page', async () => {
    mode = 'order_queue'
    renderPickPack()
    expect(await screen.findByTestId('fulfill-queue')).toBeInTheDocument()
    expect(screen.queryByTestId('waybill-pack-page')).not.toBeInTheDocument()
  })

  test('waybill mode: tiles from the queue + summary, self-pickup entry opens the filtered queue', async () => {
    renderPickPack()
    const tiles = await screen.findByTestId('waybill-tiles')
    expect(within(tiles).getByText('Ready to pack').parentElement).toHaveTextContent('3')
    expect(within(tiles).getByText('Waybill printed').parentElement).toHaveTextContent('2')
    expect(within(tiles).getByText('Not printed yet').parentElement).toHaveTextContent('1')
    expect(within(tiles).getByText('Packed today').parentElement).toHaveTextContent('31')
    expect(screen.getByText('Waybill scan mode')).toBeInTheDocument()
    expect(screen.getByTestId('self-pickup-entry')).toHaveAttribute('href', '/fulfill?view=self-pickup')
  })

  test('?view=self-pickup shows the queue with only self-pickup orders', async () => {
    renderPickPack('/fulfill?view=self-pickup')
    expect(await screen.findByText('#SP1')).toBeInTheDocument()
    expect(screen.getByText('Back to waybill packing')).toBeInTheDocument()
  })

  test('waybill opens the order card with images; last piece auto-completes; Undo gone once closed', async () => {
    const user = await startSession()
    await scan(user, 'D-07-74821903')

    const orderCard = await screen.findByTestId('order-card')
    expect(within(orderCard).getByText('#1047')).toBeInTheDocument()
    expect(within(orderCard).getByText('Nasr City, Cairo')).toBeInTheDocument()
    expect(within(orderCard).getByText('Courier Bosta · Delivery')).toBeInTheDocument()
    expect(within(orderCard).getByText('Cash on delivery EGP 1,250')).toBeInTheDocument()
    expect(screen.getByAltText('Linen shirt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'End session' })).toBeDisabled()

    await scan(user, 'P000001')
    expect(await screen.findByTestId('last-scan')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Undo/ })).toBeInTheDocument()

    await scan(user, 'P000002')
    const flash = await screen.findByTestId('packed-flash')
    expect(flash).toHaveTextContent('Order #1047 packed')
    expect(screen.queryByRole('button', { name: /Undo/ })).not.toBeInTheDocument()
    expect(screen.queryByTestId('order-card')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'End session' })).toBeEnabled()
  })

  test('rejected waybill: full-stop screen with the code title, cancel time, OK returns to waiting', async () => {
    const user = await startSession()
    await scan(user, 'BAD')
    const rejected = await screen.findByTestId('session-rejected')
    expect(within(rejected).getByTestId('rejected-title')).toHaveTextContent("Don't pack this one")
    expect(rejected).toHaveTextContent('Order #1039 was cancelled')
    expect(rejected).toHaveTextContent(/Cancelled at/)
    await user.click(within(rejected).getByRole('button', { name: 'OK, scan the next waybill' }))
    expect(await screen.findByTestId('session-waiting')).toBeInTheDocument()
  })

  test('complete_failed shows the reason and Try again completes', async () => {
    pieceResponses = [scanned, failed]
    const user = await startSession()
    await scan(user, '74821903')
    await screen.findByTestId('order-card')
    await scan(user, 'P000001')
    await scan(user, 'P000002')
    const box = await screen.findByTestId('complete-failed')
    expect(box).toHaveTextContent("The waybill that opened this order is no longer this order's waybill")
    await user.click(within(box).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByTestId('packed-flash')).toBeInTheDocument()
    expect(calls.some(c => c.url.endsWith('/orders/order-1/complete') && c.method === 'POST')).toBe(true)
  })

  test('set aside needs a reason, then returns to waiting', async () => {
    const user = await startSession()
    await scan(user, '74821903')
    await scan(user, 'P000001')
    await screen.findByTestId('last-scan')
    await user.click(screen.getByRole('button', { name: 'Set aside this order' }))
    const dialog = await screen.findByTestId('set-aside-dialog')
    const confirm = within(dialog).getByRole('button', { name: 'Set aside' })
    expect(confirm).toBeDisabled()
    await user.click(within(dialog).getByText('Damaged piece'))
    await user.click(confirm)
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/set-aside'))?.body).toEqual({ reason: 'damaged_piece' }))
    expect(await screen.findByTestId('session-waiting')).toBeInTheDocument()
  })
})
