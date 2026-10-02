import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { commands } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import * as api from '../api'
import type { RelayHandlers } from '../pages/fulfill/relayStream'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'

// S6 — phone scans (relay events) and the station's own scanner (real keystroke bursts) arriving
// interleaved and fast, in a real browser (Chromium + WebKit): every scan is sent strictly one at
// a time, in the order it arrived, and each phone scan gets exactly one outcome (keyboard scans
// none).

const LATENCY_MS = 120
const KEY_DELAY_MS = 4

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getRoleFromToken: vi.fn(() => 'worker'),
    getMe: vi.fn(),
    getPackSession: vi.fn(),
    getScanPairing: vi.fn(),
    scanPackWaybill: vi.fn(),
    scanPackPiece: vi.fn(),
    postRelayOutcome: vi.fn(),
  }
})

let relay: RelayHandlers | null = null
vi.mock('../pages/fulfill/relayStream', () => ({
  openRelayStream: (_id: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const PIECES = 60
const card = (allocated: number) => ({
  id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City', courierType: 'delivery', batchNo: null, batchPrintedAt: null,
  items: [{ id: 'line-1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Cargo pants', imageUrl: null,
    quantity: PIECES, allocated, allocatedPieces: [] }],
}) as unknown as api.PackOrderCard
const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: new Date().toISOString(),
  workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null,
} as api.PackSessionView

let sent: string[]
let inFlight: number
let maxInFlight: number
let arrived: string[]
/** Called after each keyboard scan's Enter — the test sends a phone scan from here. */
let afterKeyboardScan: () => void = () => {}
const recordEnter = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.classList.contains('input-scan')) {
    arrived.push(e.target.value.trim())
    afterKeyboardScan()
  }
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  sent = []; arrived = []; inFlight = 0; maxInFlight = 0; relay = null
  let pieces = 0
  const slow = async <T,>(code: string, result: () => T) => {
    sent.push(code)
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise(r => setTimeout(r, LATENCY_MS))
    inFlight--
    return result()
  }
  vi.mocked(api.getMe).mockResolvedValue({ name: 'Ahmed', email: null, role: 'worker' })
  vi.mocked(api.getPackSession).mockResolvedValue(VIEW)
  vi.mocked(api.getScanPairing).mockResolvedValue({ status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari',
    pairCodeExpiresAt: null, claimedAt: null, expiresAt: null, reason: null })
  vi.mocked(api.postRelayOutcome).mockResolvedValue(undefined)
  vi.mocked(api.scanPackWaybill).mockImplementation((_id, code) => slow(code, () => ({ result: 'opened', order: card(0),
    code: null, subReason: null, orderNumber: '#1047', who: null, at: null, state: null, messageEn: null, messageAr: null })))
  vi.mocked(api.scanPackPiece).mockImplementation((_id, _order, code) => slow(code, () => {
    pieces++
    return { status: 'scanned', order: card(pieces), packed: null, failCode: null, failMessage: null,
      scan: { success: true, code: 'SCANNED', message: null, pieceId: `pc-${pieces}`, barcode: code,
        allocatedCount: pieces, requiredQuantity: PIECES, allComplete: false } } as api.PackScanResponse
  }))
  window.addEventListener('keydown', recordEnter, true)       // sees the typed code before React clears it
})
afterEach(() => { window.removeEventListener('keydown', recordEnter, true); cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals() })

test('phone scans and keyboard bursts interleaved: one in flight, arrival order, one outcome per phone scan', async () => {
  renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
  await expect.poll(() => relay !== null && !!document.querySelector('input.input-scan')).toBe(true)
  document.querySelector<HTMLInputElement>('input.input-scan')!.focus()

  const phoneCodes = Array.from({ length: 8 }, (_, i) => `R${String(i + 1).padStart(6, '0')}`)
  const keyCodes = Array.from({ length: 8 }, (_, i) => `K${String(i + 1).padStart(6, '0')}`)

  // The waybill comes from the phone; then the phone keeps sending while the keyboard bursts.
  arrived.push('D-07-74821903')
  relay!.onScan({ id: 'ev-0', seq: 0, code: 'D-07-74821903', createdAt: '' })
  // Each keyboard scan is followed 5 ms later by a phone scan — it lands while the next keyboard
  // code is being typed, so the two sources always interleave, whatever the machine's speed.
  let next = 0
  afterKeyboardScan = () => {
    setTimeout(() => {
      if (next >= phoneCodes.length) return
      const code = phoneCodes[next]
      arrived.push(code)
      relay!.onScan({ id: `ev-${next + 1}`, seq: next + 1, code, createdAt: '' })
      next++
    }, 5)
  }
  await commands.scannerBurst(keyCodes, KEY_DELAY_MS)
  await expect.poll(() => next).toBe(phoneCodes.length)
  afterKeyboardScan = () => {}

  const total = 1 + phoneCodes.length + keyCodes.length
  await expect.poll(() => sent.length, { timeout: total * LATENCY_MS + 10_000, interval: 50 }).toBe(total)
  await new Promise(r => setTimeout(r, LATENCY_MS + 200))

  // The two sources really were interleaved (a phone scan arrived between two keyboard scans).
  const firstK = arrived.findIndex(c => c.startsWith('K'))
  const lastK = arrived.map(c => c.startsWith('K')).lastIndexOf(true)
  expect(arrived.slice(firstK, lastK).some(c => c.startsWith('R'))).toBe(true)

  expect(maxInFlight).toBe(1)
  expect(sent).toEqual(arrived)                                 // processed in the order they arrived
  expect(new Set(sent.filter(c => c.startsWith('K'))).size).toBe(keyCodes.length)   // keyboard: none lost

  const outcomes = vi.mocked(api.postRelayOutcome).mock.calls
  const perEvent = new Map<string, number>()
  for (const c of outcomes) perEvent.set(c[1], (perEvent.get(c[1]) ?? 0) + 1)
  expect([...perEvent.keys()].sort()).toEqual(Array.from({ length: phoneCodes.length + 1 }, (_, i) => `ev-${i}`).sort())
  expect([...perEvent.values()].every(n => n === 1)).toBe(true)  // exactly one outcome each, none for keyboard
  expect(outcomes.every(c => c[2] === 'accepted')).toBe(true)
})
