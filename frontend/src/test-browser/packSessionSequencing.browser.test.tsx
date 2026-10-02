import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import * as api from '../api'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'

// PackSessionScreen + useScanner sequencing, in a real browser (Chromium + WebKit). Scans are
// typed into the real scan input as Enter keydowns (React's discrete, Sync-priority path). The
// Enters land right after a scan has started — before React's normal-priority render — which is
// when the old worker could start a second scan built from the old screen state: a piece sent to
// the waybill endpoint while the order was still opening, or the next waybill sent to the old
// order as a piece. Each scan must be sent to the right endpoint for the state the previous
// scan's result produced, one at a time.

const LATENCY_MS = 300

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getRoleFromToken: vi.fn(() => 'worker'),
    getMe: vi.fn(),
    getPackSession: vi.fn(),
    scanPackWaybill: vi.fn(),
    scanPackPiece: vi.fn(),
  }
})

const isWaybill = (code: string) => code.startsWith('D-')
const orderFor = (waybill: string) => waybill === 'D-07-74821903'
  ? { id: 'order-1', number: '#1047' } : { id: 'order-2', number: '#1048' }

function card(o: { id: string; number: string }, quantity: number, allocated: number) {
  return {
    id: o.id, number: o.number, customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
    tracking_number: '74821903', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: null, batchPrintedAt: null,
    items: [{ id: `${o.id}-line`, variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Shirt', imageUrl: null,
      quantity, allocated, allocatedPieces: [] }],
  } as unknown as api.PackOrderCard
}

const view = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: new Date().toISOString(),
  workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null,
} as api.PackSessionView

let waybillCalls: string[]
let pieceCalls: Array<[string, string]>
let inFlight: number
let maxInFlight: number

/** Fake server: same answers PackSessionStore gives, LATENCY_MS each, overlap recorded. */
function server(quantity: number) {
  const allocated = new Map<string, number>()
  const slow = async <T,>(result: () => T) => {
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise(r => setTimeout(r, LATENCY_MS))
    inFlight--
    return result()
  }
  vi.mocked(api.scanPackWaybill).mockImplementation((_id, code) => {
    waybillCalls.push(code)
    return slow(() => (isWaybill(code)
      ? { result: 'opened', order: card(orderFor(code), quantity, 0), code: null, subReason: null,
          orderNumber: orderFor(code).number, who: null, at: null, state: null, messageEn: null, messageAr: null }
      : { result: 'rejected', order: null, code: 'NOT_A_WAYBILL', subReason: null, orderNumber: null,
          who: null, at: null, state: null, messageEn: 'That is not a waybill.', messageAr: 'ليست بوليصة.' }
    ) as api.WaybillOutcome)
  })
  vi.mocked(api.scanPackPiece).mockImplementation((_id, orderId, code) => {
    pieceCalls.push([orderId, code])
    return slow(() => {
      if (isWaybill(code)) {
        return { status: 'rejected', order: null, packed: null, failCode: null, failMessage: null,
          scan: { success: false, code: 'WAYBILL_WHILE_PACKING', message: 'That is a waybill.', pieceId: null,
            barcode: code, allocatedCount: 0, requiredQuantity: quantity, allComplete: false } } as api.PackScanResponse
      }
      const n = (allocated.get(orderId) ?? 0) + 1
      allocated.set(orderId, n)
      const o = orderId === 'order-1' ? { id: 'order-1', number: '#1047' } : { id: 'order-2', number: '#1048' }
      const scan = { success: true, code: 'SCANNED', message: null, pieceId: `${orderId}-p${n}`, barcode: code,
        allocatedCount: n, requiredQuantity: quantity, allComplete: n >= quantity }
      return (n < quantity
        ? { status: 'scanned', scan, order: card(o, quantity, n), packed: null, failCode: null, failMessage: null }
        : { status: 'completed', scan, order: null, failCode: null, failMessage: null,
            packed: { orderId, orderNumber: o.number, customerName: 'Youssef Adel', pieces: n } }) as api.PackScanResponse
    })
  })
}

function scanInput(): HTMLInputElement {
  const el = document.querySelector<HTMLInputElement>('input.input-scan')
  if (!el) throw new Error('scan input not rendered')
  return el
}

/** One scanner read: the code lands in the input, then Enter (a discrete keydown). */
function type(code: string) {
  const el = scanInput()
  el.value = code
  el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
}

/** Lets React's Sync render (a microtask) run — and with it the worker effect — but not its
 *  normal-priority render, which is a separate task. */
const microtasks = async (n = 20) => { for (let i = 0; i < n; i++) await Promise.resolve() }
const settle = () => new Promise(r => setTimeout(r, LATENCY_MS + 200))

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  waybillCalls = []
  pieceCalls = []
  inFlight = 0
  maxInFlight = 0
  vi.mocked(api.getMe).mockResolvedValue({ name: 'Ahmed', email: null, role: 'worker' })
  vi.mocked(api.getPackSession).mockResolvedValue(view)
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('pack session sequencing — each scan uses the state the previous scan produced', () => {
  test('waybill then 3 pieces typed right behind it → the order opens, all 3 go to it as pieces', async () => {
    server(3)
    renderWithProviders(<PackSessionScreen initial={view} onEnded={() => {}} />)
    await expect.poll(() => document.querySelector('input.input-scan')).toBeTruthy()

    type('D-07-74821903')
    await microtasks()                       // the waybill scan has started
    type('P000001')
    type('P000002')
    type('P000003')

    await expect.poll(() => pieceCalls.length, { timeout: 4 * LATENCY_MS + 5_000 }).toBe(3)
    await settle()
    expect(waybillCalls).toEqual(['D-07-74821903'])
    expect(pieceCalls).toEqual([['order-1', 'P000001'], ['order-1', 'P000002'], ['order-1', 'P000003']])
    expect(maxInFlight).toBe(1)
    expect(document.querySelector('[data-testid="session-rejected"]')).toBeNull()
    expect(document.querySelector('[data-testid="packed-flash"]')).toBeTruthy()   // 3 of 3 → auto-completed
  })

  test('last piece then the next waybill typed back to back → the order completes, then the next waybill opens', async () => {
    server(2)
    renderWithProviders(<PackSessionScreen initial={view} onEnded={() => {}} />)
    await expect.poll(() => document.querySelector('input.input-scan')).toBeTruthy()
    type('D-07-74821903')
    await expect.poll(() => document.querySelector('[data-testid="order-card"]')?.textContent ?? '',
      { timeout: LATENCY_MS + 5_000 }).toContain('#1047')

    type('P000001')
    await microtasks()                       // the first piece scan has started
    type('P000002')                          // the last piece…
    type('D-07-74821950')                    // …and the next waybill, back to back

    await expect.poll(() => document.querySelector('[data-testid="order-card"]')?.textContent ?? '',
      { timeout: 4 * LATENCY_MS + 5_000 }).toContain('#1048')
    await settle()
    expect(pieceCalls).toEqual([['order-1', 'P000001'], ['order-1', 'P000002']])
    expect(waybillCalls).toEqual(['D-07-74821903', 'D-07-74821950'])
    expect(maxInFlight).toBe(1)
    expect(document.querySelector('[data-testid="session-rejected"]')).toBeNull()
  })
})
