import { describe, expect, test, vi, beforeEach, afterEach } from 'vitest'
import { commands } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders } from '../test/renderWithProviders'
import * as api from '../api'
import StockTakeScan from '../pages/StockTakeScan'
import TransferScanOut from '../pages/TransferScanOut'
import TransferReconcile from '../pages/TransferReconcile'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'

// No lost scans, in a real browser (Chromium + WebKit): 20 scanner-like bursts typed back to
// back — no clicks, a few ms between keys — while every scan request takes ~300 ms. Each
// screen must send exactly 20 scan requests, in order, one at a time, and keep the scan
// input focused after the last. Before the useScanner/ScanShell fix the input was disabled
// while a request was in flight (keystrokes of the next scan were dropped) and focus was
// restored onto the still-disabled input (focus lost for good in Chromium).

const LATENCY_MS = 300
const KEY_DELAY_MS = 4
const SCANS = 20

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getRoleFromToken: vi.fn(() => 'owner'),
    getStockTakeSession: vi.fn(),
    scanStockTakePiece: vi.fn(),
    getTransfer: vi.fn(),
    scanOutTransferPiece: vi.fn(),
    scanBackTransferPiece: vi.fn(),
    getMe: vi.fn(),
    getPackSession: vi.fn(),
    scanPackWaybill: vi.fn(),
    scanPackPiece: vi.fn(),
  }
})

/** Records every scan request; each takes LATENCY_MS; tracks how many overlap. */
function slowScanApi<T>(result: (code: string) => T) {
  const seen: string[] = []
  let inFlight = 0
  let maxInFlight = 0
  const fn = async (...args: unknown[]) => {
    const code = args[1] as string
    seen.push(code)
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise(r => setTimeout(r, LATENCY_MS))
    inFlight--
    return result(code)
  }
  return { fn, seen, maxInFlight: () => maxInFlight }
}

const codes = (prefix: string) => Array.from({ length: SCANS }, (_, i) => `${prefix}${String(i + 1).padStart(6, '0')}`)

function scanInput(): HTMLInputElement {
  const el = document.querySelector<HTMLInputElement>('input.input-scan')
  if (!el) throw new Error('scan input not rendered')
  return el
}

async function burstAndCheck(seen: string[], maxInFlight: () => number, sent: string[]) {
  await expect.poll(() => document.querySelector('input.input-scan')).toBeTruthy()
  scanInput().focus()                                   // the station starts with the scan bar focused
  await commands.scannerBurst(sent, KEY_DELAY_MS)
  await expect.poll(() => seen.length, { timeout: SCANS * LATENCY_MS + 10_000, interval: 100 }).toBe(SCANS)
  await new Promise(r => setTimeout(r, LATENCY_MS + 200))
  expect(seen).toEqual(sent)                            // exactly 20, in order, none extra
  expect(maxInFlight()).toBe(1)                         // one at a time
  expect(document.activeElement).toBe(scanInput())      // still ready for the next scan
}

beforeEach(() => { vi.stubGlobal('AudioContext', undefined) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('scanner bursts with ~300 ms latency — no lost scans', () => {
  test('StockTakeScan', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue({
      sessionId: 's1', status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: false,
      openedBy: 'u1', openedByName: 'Owner', openedAt: new Date().toISOString(), finalizedBy: null,
      finalizedByName: null, finalizedAt: null, note: null, shopifySync: null,
    } as api.StockTakeSessionDetail)
    const s = slowScanApi(code => ({ sessionId: 's1', barcode: code, pieceId: `piece-${code}`,
      classification: 'match', alreadyScanned: false }) as api.StockTakeScanResult)
    vi.mocked(api.scanStockTakePiece).mockImplementation(s.fn as typeof api.scanStockTakePiece)

    renderWithProviders(<Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>,
      { initialEntries: ['/stock-take/s1/scan'] })
    await burstAndCheck(s.seen, s.maxInFlight, codes('P'))
  })

  test('TransferScanOut', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(transfer('preparing'))
    const s = slowScanApi(code => ({ success: true, code: 'SCANNED', message_en: null, message_ar: null,
      pieceId: `piece-${code}`, barcode: code, variantId: 'v1' }) as api.TransferScanResult)
    vi.mocked(api.scanOutTransferPiece).mockImplementation(s.fn as typeof api.scanOutTransferPiece)

    renderWithProviders(<Routes><Route path="/transfers/:id/scan-out" element={<TransferScanOut />} /></Routes>,
      { initialEntries: ['/transfers/t1/scan-out'] })
    await burstAndCheck(s.seen, s.maxInFlight, codes('Q'))
  })

  test('TransferReconcile', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(transfer('reconciling'))
    const s = slowScanApi(code => ({ success: true, code: 'SCANNED', message_en: null, message_ar: null,
      pieceId: `piece-${code}`, barcode: code, variantId: 'v1', outcome: 'returned_good' }) as api.TransferScanBackResult)
    vi.mocked(api.scanBackTransferPiece).mockImplementation(s.fn as typeof api.scanBackTransferPiece)

    renderWithProviders(<Routes><Route path="/transfers/:id/reconcile" element={<TransferReconcile />} /></Routes>,
      { initialEntries: ['/transfers/t1/reconcile'] })
    await burstAndCheck(s.seen, s.maxInFlight, codes('R'))
  })
})

describe('pack session — one waybill then 19 pieces, ~300 ms each', () => {
  test('PackSessionScreen', async () => {
    const PIECES = SCANS - 1
    const card = (allocated: number) => ({
      id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
      tracking_number: '74821903', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: null, batchPrintedAt: null,
      items: [{ id: 'line-1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Shirt', imageUrl: null,
        quantity: PIECES, allocated, allocatedPieces: [] }],
    }) as unknown as api.PackOrderCard
    const view = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: new Date().toISOString(),
      workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null,
    } as api.PackSessionView

    // One recorder across both endpoints: order and overlap are checked over the whole burst.
    const seen: string[] = []
    let inFlight = 0, maxInFlight = 0, pieces = 0
    const slow = async <T,>(code: string, result: () => T) => {
      seen.push(code)
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise(r => setTimeout(r, LATENCY_MS))
      inFlight--
      return result()
    }
    vi.mocked(api.getMe).mockResolvedValue({ name: 'Ahmed', email: null, role: 'worker' })
    vi.mocked(api.getPackSession).mockResolvedValue(view)
    vi.mocked(api.scanPackWaybill).mockImplementation((_id, code) =>
      slow(code, () => ({ result: 'opened', order: card(0), code: null, subReason: null, orderNumber: '#1047',
        who: null, at: null, state: null, messageEn: null, messageAr: null }) as api.WaybillOutcome))
    vi.mocked(api.scanPackPiece).mockImplementation((_id, _orderId, code) =>
      slow(code, () => {
        pieces++
        return (pieces < PIECES
          ? { status: 'scanned', scan: { success: true, code: 'SCANNED', message: null, pieceId: `p${pieces}`,
              barcode: code, allocatedCount: pieces, requiredQuantity: PIECES, allComplete: false },
              order: card(pieces), packed: null, failCode: null, failMessage: null }
          : { status: 'completed', scan: { success: true, code: 'SCANNED', message: null, pieceId: `p${pieces}`,
              barcode: code, allocatedCount: pieces, requiredQuantity: PIECES, allComplete: true },
              order: null, packed: { orderId: 'order-1', orderNumber: '#1047', customerName: 'Youssef Adel', pieces },
              failCode: null, failMessage: null }) as api.PackScanResponse
      }))

    renderWithProviders(<PackSessionScreen initial={view} onEnded={() => {}} />)
    const sent = ['D-07-74821903', ...codes('P').slice(0, PIECES)]
    await burstAndCheck(seen, () => maxInFlight, sent)
    expect(vi.mocked(api.scanPackWaybill)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.scanPackPiece)).toHaveBeenCalledTimes(PIECES)
    await expect.poll(() => document.querySelector('[data-testid="packed-flash"]')).toBeTruthy()  // auto-completed
  })
})

function transfer(status: api.TransferDetail['status']): api.TransferDetail {
  return {
    id: 't1', transfer_type: 'showroom', transfer_mode: 'round_trip', status,
    note: null, expected_return_at: null, created_by: 'u1', created_at: new Date().toISOString(),
    closed_by: null, closed_at: null, sent_at: null, sent_by: null, cancelled_at: null,
    cancelled_by: null, reconcile_started_at: null, reconcile_started_by: null,
    destination_location_id: 'd1', destination_location_name: 'Vendor A',
    source_location_id: null, source_location_name: null,
    lines: [{ id: 'line-1', variant_id: 'v1', sku: 'SKU1', variant_title: 'W-1', product_title: 'Widget',
      qty_out: 40, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0 }],
    outstandingCount: 40, piecesEverCount: 40,
  } as api.TransferDetail
}
