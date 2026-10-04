import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { commands } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import type { PackSessionView } from '../api'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import Fulfill from '../pages/Fulfill'

// Phone scans (relay events, routed by the tablet's PhoneScanProvider — Q1) and the station's own
// scanner (real keystroke bursts) arriving interleaved and fast, in a real browser (Chromium +
// WebKit), on the waybill pack session and on PickScreen (queue mode): every scan is sent
// strictly one at a time, in the order it arrived, and each phone scan gets exactly one outcome
// (keyboard scans none).

const LATENCY_MS = 120
const KEY_DELAY_MS = 4

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'worker') }
})

let relay: RelayHandlers | null = null
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_deviceId: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const PIECES = 60
const card = (allocated: number) => ({
  id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City', courierType: 'delivery', batchNo: null, batchPrintedAt: null,
  items: [{ id: 'line-1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Cargo pants', imageUrl: null,
    quantity: PIECES, allocated, allocatedPieces: [] }],
})
const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: new Date().toISOString(),
  workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null,
} as unknown as PackSessionView
const pickDetail = (allocated: number) => ({
  id: 'order-1', number: '#101', customer_name: 'Alice', customer_phone: null, status: 'new', payment_method: null,
  cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null, shipment_id: null,
  tracking_number: null, shipment_has_courier: true,
  items: [{ id: 'item-1', variant_id: 'v1', sku: 'SKU-1', variant_title: 'Default Title', product_title: 'Shirt',
    quantity: PIECES, allocated, allocatedPieces: [] }],
})
const QUEUE = [{ id: 'order-1', number: '#101', customer_name: 'Alice', status: 'new', payment_method: null,
  cod_amount: null, total_units: PIECES, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false,
  is_exchange: false }]

let sent: string[]
let inFlight: number
let maxInFlight: number
let arrived: string[]
let outcomes: Array<{ eventId: string; result: string }>
/** Called after each keyboard scan's Enter — the test sends a phone scan from here. */
let afterKeyboardScan: () => void = () => {}
const recordEnter = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.classList.contains('input-scan')) {
    arrived.push(e.target.value.trim())
    afterKeyboardScan()
  }
}

function slow(code: string, reply: () => unknown) {
  sent.push(code)
  inFlight++
  maxInFlight = Math.max(maxInFlight, inFlight)
  return new Promise(r => setTimeout(r, LATENCY_MS)).then(() => { inFlight--; return json(reply()) })
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  sent = []; arrived = []; outcomes = []; inFlight = 0; maxInFlight = 0; relay = null
  let pieces = 0
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(String(opts.body)) : undefined
    if (url.includes('/station/pairings/current') && method === 'GET') {
      return json({ status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari', pairCodeExpiresAt: null,
        claimedAt: null, expiresAt: null, reason: null })
    }
    if (url.includes('/station/relay-events/')) {
      outcomes.push({ eventId: url.split('/relay-events/')[1].split('/')[0], result: body.result })
      return json({})
    }
    // pack session
    if (url.endsWith('/pack-sessions/sess-1') && method === 'GET') return json(VIEW)
    if (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST') {
      return slow(body.code, () => ({ result: 'opened', order: card(0), code: null, orderNumber: '#1047' }))
    }
    if (url.endsWith('/pack-sessions/sess-1/orders/order-1/scan') && method === 'POST') {
      return slow(body.code, () => {
        pieces++
        return { status: 'scanned', order: card(pieces), packed: null, failCode: null, failMessage: null,
          scan: { success: true, code: 'SCANNED', message: null, pieceId: `pc-${pieces}`, barcode: body.code,
            allocatedCount: pieces, requiredQuantity: PIECES, allComplete: false } }
      })
    }
    // PickScreen
    if (url.endsWith('/fulfill/queue')) return json(QUEUE)
    if (url.includes('/fulfill/queue/awaiting-waybill-count')) return json({ count: 0 })
    if (url.endsWith('/fulfill/order-1') && method === 'GET') return json(pickDetail(pieces))
    if (url.endsWith('/fulfill/order-1/scan') && method === 'POST') {
      return slow(body.barcode, () => {
        pieces++
        return { success: true, code: 'SCANNED', message: null, pieceId: `pc-${pieces}`, barcode: body.barcode,
          allocatedCount: pieces, requiredQuantity: PIECES, allComplete: false }
      })
    }
    return json({})
  }))
  window.addEventListener('keydown', recordEnter, true)       // sees the typed code before React clears it
})
afterEach(async () => {
  window.removeEventListener('keydown', recordEnter, true)
  await expect.poll(() => inFlight, { timeout: 3_000 }).toBe(0)
  cleanup()
  vi.unstubAllGlobals()
})

/** Keyboard bursts with a phone scan landing 5 ms after each Enter (while the next code is typed). */
async function interleave(firstPhone: string | null) {
  const phoneCodes = Array.from({ length: 8 }, (_, i) => `R${String(i + 1).padStart(6, '0')}`)
  const keyCodes = Array.from({ length: 8 }, (_, i) => `K${String(i + 1).padStart(6, '0')}`)
  const events: string[] = []
  if (firstPhone) {
    arrived.push(firstPhone)
    events.push('ev-0')
    relay!.onScan({ id: 'ev-0', seq: 0, code: firstPhone, createdAt: '' })
  }
  let next = 0
  afterKeyboardScan = () => {
    setTimeout(() => {
      if (next >= phoneCodes.length) return
      const code = phoneCodes[next]
      arrived.push(code)
      events.push(`ev-${next + 1}`)
      relay!.onScan({ id: `ev-${next + 1}`, seq: next + 1, code, createdAt: '' })
      next++
    }, 5)
  }
  await commands.scannerBurst(keyCodes, KEY_DELAY_MS)
  await expect.poll(() => next).toBe(phoneCodes.length)
  afterKeyboardScan = () => {}

  const total = events.length + keyCodes.length
  await expect.poll(() => sent.length, { timeout: total * LATENCY_MS + 10_000, interval: 50 }).toBe(total)
  await expect.poll(() => outcomes.length, { timeout: 5_000 }).toBe(events.length)
  await new Promise(r => setTimeout(r, LATENCY_MS + 200))

  // The two sources really were interleaved (a phone scan arrived between two keyboard scans).
  const firstK = arrived.findIndex(c => c.startsWith('K'))
  const lastK = arrived.map(c => c.startsWith('K')).lastIndexOf(true)
  expect(arrived.slice(firstK, lastK).some(c => c.startsWith('R'))).toBe(true)

  expect(maxInFlight).toBe(1)
  expect(sent).toEqual(arrived)                                 // processed in the order they arrived
  expect(new Set(sent.filter(c => c.startsWith('K'))).size).toBe(keyCodes.length)   // keyboard: none lost

  const perEvent = new Map<string, number>()
  for (const o of outcomes) perEvent.set(o.eventId, (perEvent.get(o.eventId) ?? 0) + 1)
  expect([...perEvent.keys()].sort()).toEqual([...events].sort())
  expect([...perEvent.values()].every(n => n === 1)).toBe(true)  // exactly one outcome each, none for keyboard
  expect(outcomes.every(o => o.result === 'accepted')).toBe(true)
}

test('pack session: phone scans and keyboard bursts interleaved — one in flight, arrival order, one outcome per phone scan', async () => {
  renderWithProviders(<PhoneScanProvider><PackSessionScreen initial={VIEW} onEnded={() => {}} /><PhoneControl /></PhoneScanProvider>)
  await expect.poll(() => relay !== null && !!document.querySelector('input.input-scan')).toBe(true)
  document.querySelector<HTMLInputElement>('input.input-scan')!.focus()
  await interleave('D-07-74821903')                            // the waybill comes from the phone
})

test('PickScreen: phone scans and keyboard bursts interleaved — one in flight, arrival order, one outcome per phone scan', async () => {
  renderWithProviders(<PhoneScanProvider><Fulfill /><PhoneControl /></PhoneScanProvider>)
  await expect.poll(() => document.body.textContent?.includes('#101')).toBe(true)
  const row = [...document.querySelectorAll<HTMLElement>('*')].reverse().find(el => el.textContent === '#101')!
  row.click()
  await expect.poll(() => relay !== null && !!document.querySelector('[data-testid="fulfill-pick"] input.input-scan')).toBe(true)
  document.querySelector<HTMLInputElement>('[data-testid="fulfill-pick"] input.input-scan')!.focus()
  await interleave(null)
})
