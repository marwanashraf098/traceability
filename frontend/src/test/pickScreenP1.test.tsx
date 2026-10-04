import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import en from '../locales/en.json'
import Fulfill from '../pages/Fulfill'

// P1 — PickScreen on useScanner (queue mode): scans waiting in the queue are dropped when the
// order moves on (Complete, the AWB step, cancel, the completion card); unscanning a piece closes
// the link step and focus returns to the scan input; plus the §4 checklist items no other test
// covered — rejection texts, link errors and whitespace, self-pickup completion + auto-return,
// handover, guided unpack, unscan, beep tones, flash timing, RTL.

type Item = { quantity: number; allocated: number; status?: 'active' | 'packed' }
function detail(o: {
  items?: Item[]; tracking_number?: string | null; awbPrinted?: boolean; is_self_pickup?: boolean;
  cancel_requested_at?: string | null; shipment_has_courier?: boolean
} = {}) {
  const items = (o.items ?? [{ quantity: 2, allocated: 0 }]).map((it, n) => ({
    id: `item-${n}`, variant_id: `v${n}`, sku: `SKU-${n}`, variant_title: 'Default Title', product_title: `Product ${n}`,
    quantity: it.quantity, allocated: it.allocated,
    allocatedPieces: Array.from({ length: it.allocated }, (_, i) =>
      ({ piece_id: `p${n}-${i}`, barcode: `PIECE${n}${i}`, allocation_status: it.status ?? 'active',
         piece_status: it.status === 'packed' ? 'packed' : 'reserved' })),
  }))
  return {
    id: 'order-1', number: '#101', customer_name: 'Alice', customer_phone: null, status: 'new',
    payment_method: null, cod_amount: null, locked_by: null, is_self_pickup: o.is_self_pickup ?? false,
    cancel_requested_at: o.cancel_requested_at ?? null, shipment_id: o.tracking_number ? 'sh-1' : null,
    tracking_number: o.tracking_number ?? null, shipment_has_courier: o.shipment_has_courier ?? true,
    awbPrinted: o.awbPrinted ?? false, items,
  }
}
const queueOrder = (over: Record<string, unknown> = {}) => ({ id: 'order-1', number: '#101', customer_name: 'Alice',
  status: 'new', payment_method: null, cod_amount: null, total_units: 2, scanned_units: 0, locked_by: null,
  locked_at: null, is_self_pickup: false, is_exchange: false, ...over })

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 400, status, json: async () => data })

let order: ReturnType<typeof detail>
let queue: unknown[]
let calls: Array<{ method: string; url: string; body?: string }>
let scanGate: Array<() => void>
let scanReply: (code: string) => { status: number; body: unknown }
let linkReply: (tn: string) => { status: number; body: unknown }

const okScan = (code: string) => ({ status: 200, body: { success: true, code: 'SCANNED', message: null, pieceId: 'x',
  barcode: code, allocatedCount: 1, requiredQuantity: 2, allComplete: false } })

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: opts.body ? String(opts.body) : undefined })
  if (url.endsWith('/fulfill/queue')) return json(queue)
  if (url.includes('awaiting-waybill-count')) return json({ count: 0 })
  if (url.endsWith('/fulfill/order-1') && method === 'GET') return json(order)
  if (url.endsWith('/fulfill/order-1/scan') && method === 'POST') {
    const code = JSON.parse(String(opts.body)).barcode
    return new Promise<void>(r => scanGate.push(r)).then(() => {
      const { status, body } = scanReply(code)
      return json(body, status)
    })
  }
  if (url.includes('/fulfill/order-1/scan/') && method === 'DELETE') {
    order = detail({ ...current, items: order.items.map(i => ({ quantity: i.quantity, allocated: Math.max(0, i.allocated - 1) })) })
    return Promise.resolve({ ok: true, status: 204, json: async () => null })
  }
  if (url.endsWith('/fulfill/order-1/complete')) return json({})
  if (url.endsWith('/fulfill/order-1/link')) {
    const { status, body } = linkReply(JSON.parse(String(opts.body)).trackingNumber)
    return json(body, status)
  }
  if (url.endsWith('/fulfill/order-1/cancel')) return json({ status: 'cancel_requested' })
  if (url.endsWith('/fulfill/order-1/handover')) return json({ deliveredPieces: 2 })
  return json({})
}
let current: Parameters<typeof detail>[0] = {}

const scans = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/scan')).map(c => JSON.parse(c.body!).barcode)
const scanInput = () => within(screen.getByTestId('fulfill-pick')).getAllByRole('textbox')[0] as HTMLInputElement

function type(code: string) {
  const input = document.querySelector<HTMLInputElement>('[data-testid="fulfill-pick"] input.input-scan')!
  input.value = code
  fireEvent.keyDown(input, { key: 'Enter' })
}
async function releaseNextScan() {
  await waitFor(() => expect(scanGate.length).toBeGreaterThan(0))
  await act(async () => { scanGate.shift()!() })
}

async function openOrder(o: Parameters<typeof detail>[0] = {}) {
  current = o
  order = detail(o)
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#101'))
  await screen.findByTestId('fulfill-pick')
  return user
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  calls = []; scanGate = []
  queue = [queueOrder()]
  scanReply = okScan
  linkReply = tn => ({ status: 200, body: { trackingNumber: tn, shipmentId: 'sh-1' } })
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('scans waiting in the queue are dropped when the order moves on', () => {
  test('Complete tapped while scans wait → only the one in flight is sent', async () => {
    const user = await openOrder({ items: [{ quantity: 1, allocated: 1 }], tracking_number: '8484805699', awbPrinted: true })
    type('PX1'); type('PX2'); type('PX3')
    await waitFor(() => expect(scans()).toEqual(['PX1']))
    await user.click(screen.getByRole('button', { name: 'Complete & Pack Order' }))
    await releaseNextScan()
    await new Promise(r => setTimeout(r, 50))
    expect(scans()).toEqual(['PX1'])
  })

  test('opening the AWB link step while scans wait → they are dropped', async () => {
    const user = await openOrder({ items: [{ quantity: 1, allocated: 1 }] })
    type('PX1'); type('PX2')
    await waitFor(() => expect(scans()).toEqual(['PX1']))
    await user.click(screen.getByTestId('btn-scan-to-link'))
    await releaseNextScan()
    await new Promise(r => setTimeout(r, 50))
    expect(scans()).toEqual(['PX1'])
  })

  test('opening the cancel confirm while scans wait → they are dropped', async () => {
    const user = await openOrder()
    type('PX1'); type('PX2')
    await waitFor(() => expect(scans()).toEqual(['PX1']))
    await user.click(screen.getByText('Cancel Order'))
    await releaseNextScan()
    await new Promise(r => setTimeout(r, 50))
    expect(scans()).toEqual(['PX1'])
  })
})

describe('link step and focus', () => {
  test('unscanning a piece while the link step is open closes it; focus returns to the scan input', async () => {
    const user = await openOrder({ items: [{ quantity: 1, allocated: 1 }] })
    await user.click(screen.getByTestId('btn-scan-to-link'))
    const inputs = () => within(screen.getByTestId('fulfill-pick')).getAllByRole('textbox')
    await waitFor(() => expect(inputs()).toHaveLength(2))
    await waitFor(() => expect(document.activeElement).toBe(inputs()[1]))      // the link step owns focus
    await user.click(screen.getByText('Product 0'))                               // a click elsewhere…
    expect(document.activeElement).toBe(inputs()[1])                              // …doesn't take it
    await user.click(screen.getByTitle('Remove'))
    await waitFor(() => expect(inputs()).toHaveLength(1))                        // the step closed
    await waitFor(() => expect(document.activeElement).toBe(scanInput()))
    expect(screen.queryByTestId('btn-scan-to-link')).toBeNull()                  // not complete any more
  })

  test('the link step stays closed when the order is complete again — "Scan to link" reopens it', async () => {
    const user = await openOrder({ items: [{ quantity: 1, allocated: 1 }] })
    await user.click(screen.getByTestId('btn-scan-to-link'))
    await user.click(screen.getByTitle('Remove'))
    await waitFor(() => expect(screen.queryByTestId('btn-scan-to-link')).toBeNull())
    current = { items: [{ quantity: 1, allocated: 1 }] }
    order = detail(current)
    type('PIECE-BACK')
    await releaseNextScan()
    await screen.findByTestId('btn-scan-to-link')
    expect(within(screen.getByTestId('fulfill-pick')).getAllByRole('textbox')).toHaveLength(1)
  })

  test('link: whitespace stripped; a conflict and a generic error show their messages', async () => {
    const user = await openOrder({ items: [{ quantity: 1, allocated: 1 }] })
    let n = 0
    linkReply = () => (++n === 1 ? { status: 409, body: { code: 'CONFLICT' } } : { status: 500, body: {} })
    await user.click(screen.getByTestId('btn-scan-to-link'))
    const dialogInput = within(screen.getByTestId('fulfill-pick')).getAllByRole('textbox')[1] as HTMLInputElement
    dialogInput.value = ' 8484 805\n699 '
    fireEvent.keyDown(dialogInput, { key: 'Enter' })
    await screen.findByText('This waybill is already linked to another order')
    const link = calls.find(c => c.url.endsWith('/link'))!
    expect(JSON.parse(link.body!).trackingNumber).toBe('8484805699')
    dialogInput.value = '1111'
    fireEvent.keyDown(dialogInput, { key: 'Enter' })
    await screen.findByText('Could not link — check the barcode and try again')
  })
})

describe('§4 checklist items', () => {
  test('rejections: CLAIMED_BY_OTHER shows its text; ALREADY_SHIPPED shows the server message', async () => {
    await openOrder()
    scanReply = code => ({ status: 200, body: { success: false, code: code === 'A' ? 'CLAIMED_BY_OTHER' : 'ALREADY_SHIPPED',
      message: code === 'A' ? 'claimed' : 'This order has already shipped', pieceId: null, barcode: code,
      allocatedCount: 0, requiredQuantity: 2, allComplete: false } })
    type('A')
    await releaseNextScan()
    expect((await screen.findAllByText('Another packer is packing this order right now')).length).toBeGreaterThan(0)
    type('B')
    await releaseNextScan()
    expect((await screen.findAllByText(/This order has already shipped/)).length).toBeGreaterThan(0)
  })

  test('unscan sends DELETE and reloads the order', async () => {
    const user = await openOrder({ items: [{ quantity: 2, allocated: 1 }] })
    expect(screen.getByText('1/2')).toBeInTheDocument()
    await user.click(screen.getByTitle('Remove'))
    await screen.findByText('0/2')
    expect(calls.some(c => c.method === 'DELETE' && c.url.includes('/scan/p0-0'))).toBe(true)
  })

  test('self-pickup: Complete → "Packed — awaiting customer collection", then back to the queue after 2.5 s', async () => {
    await openOrder({ is_self_pickup: true, items: [{ quantity: 1, allocated: 1 }] })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    fireEvent.click(screen.getByRole('button', { name: 'Complete & Pack Order' }))
    await screen.findByText('Packed — awaiting customer collection')
    await act(async () => { await vi.advanceTimersByTimeAsync(2600) })
    await waitFor(() => expect(screen.queryByTestId('fulfill-pick-complete')).toBeNull())
  })

  test('self-pickup handover: a self_pickup_pending order opens the handover screen and confirms', async () => {
    queue = [queueOrder({ status: 'self_pickup_pending', is_self_pickup: true })]
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByText('#101'))
    await user.click(await screen.findByRole('button', { name: 'Confirm Handover' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/handover'))).toBe(true))
  })

  test('cancel requested → guided unpack, no scan input', async () => {
    await openOrder({ cancel_requested_at: '2026-10-04T10:00:00Z', items: [{ quantity: 1, allocated: 1, status: 'packed' }] })
    expect(document.querySelector('[data-testid="fulfill-pick"] input.input-scan')).toBeNull()
    expect(screen.getAllByText('Unpack').length).toBeGreaterThan(0)
  })

  test('beep tones (880 Hz success, 300 Hz fail) and the 600 ms flash with the same overlay classes', async () => {
    const freqs: number[] = []
    class FakeAudio {
      currentTime = 0
      destination = {}
      createOscillator() {
        return { connect: () => {}, frequency: { setValueAtTime: (f: number) => freqs.push(f) }, type: '', start: () => {}, stop: () => {} }
      }
      createGain() { return { connect: () => {}, gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} } } }
    }
    vi.stubGlobal('AudioContext', FakeAudio)
    await openOrder()
    scanReply = code => (code === 'BAD' ? { status: 200, body: { success: false, code: 'PIECE_NOT_FOUND', message: null,
      pieceId: null, barcode: code, allocatedCount: 0, requiredQuantity: 2, allComplete: false } } : okScan(code))
    type('GOOD')
    await releaseNextScan()
    await waitFor(() => expect(freqs).toEqual([880]))
    expect(document.querySelector('.bg-success\\/20.animate-flash')).not.toBeNull()
    await waitFor(() => expect(document.querySelector('.animate-flash')).toBeNull(), { timeout: 1500 })
    type('BAD')
    await releaseNextScan()
    await waitFor(() => expect(freqs).toEqual([880, 300]))
    expect(document.querySelector('.bg-danger\\/20.animate-flash')).not.toBeNull()
  })

  test('RTL: in Arabic the back arrow points right', async () => {
    const ar = i18next.createInstance()
    await ar.use(initReactI18next).init({ lng: 'ar', fallbackLng: 'en', resources: { en: { translation: en } },
      interpolation: { escapeValue: false } })
    current = {}
    order = detail()
    const user = userEvent.setup()
    render(
      <StationProvider><MemoryRouter><I18nextProvider i18n={ar}><ToastProvider><Fulfill /></ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    await user.click(await screen.findByText('#101'))
    await screen.findByTestId('fulfill-pick')
    expect(screen.getByTestId('fulfill-pick').querySelector('.lucide-arrow-right')).not.toBeNull()
    expect(screen.getByTestId('fulfill-pick').querySelector('.lucide-arrow-left')).toBeNull()
  })
})
