import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { commands } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import Fulfill from '../pages/Fulfill'

// PickScreen (queue mode) in a real browser, Chromium + WebKit: scanner-like bursts typed with no
// clicks while every scan request takes ~300 ms. Before P1 the input was disabled while a scan was
// in flight — keystrokes of the next scan were dropped — and focus() ran on the still-disabled
// input after a rejection, so focus was lost until a click. The AWB link step lost focus the same
// way after AWB_MISMATCH.

const LATENCY_MS = 300
const KEY_DELAY_MS = 4

type Detail = ReturnType<typeof detail>
function detail(over: Partial<{ quantity: number; allocated: number; tracking_number: string | null }> = {}) {
  const quantity = over.quantity ?? 40
  const allocated = over.allocated ?? 0
  return {
    id: 'order-1', number: '#101', customer_name: 'Alice', customer_phone: null, status: 'new',
    payment_method: null, cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null,
    shipment_id: null, tracking_number: over.tracking_number ?? null, shipment_has_courier: true,
    items: [{ id: 'item-1', variant_id: 'v1', sku: 'SKU-1', variant_title: 'Default Title', product_title: 'Shirt',
      quantity, allocated,
      allocatedPieces: Array.from({ length: allocated }, (_, i) =>
        ({ piece_id: `p${i}`, barcode: `P${String(i).padStart(6, '0')}`, allocation_status: 'active' })) }],
  }
}
const QUEUE = [{ id: 'order-1', number: '#101', customer_name: 'Alice', status: 'new', payment_method: null,
  cod_amount: null, total_units: 40, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false,
  is_exchange: false }]

const json = (data: unknown, status = 200) =>
  Promise.resolve({ ok: status < 400, status, json: async () => structuredClone(data) })

let seen: string[]
let inFlight: number
let maxInFlight: number
let order: Detail
let scanReply: (code: string) => { status: number; body: unknown }
let linkReply: (tn: string) => { status: number; body: unknown }

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/fulfill/queue')) return json(QUEUE)
  if (url.includes('/fulfill/queue/awaiting-waybill-count')) return json({ count: 0 })
  if (url.endsWith('/fulfill/order-1') && method === 'GET') return json(order)
  if (url.endsWith('/fulfill/order-1/scan') && method === 'POST') {
    const code = JSON.parse(String(opts.body)).barcode as string
    seen.push(code)
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    return new Promise(r => setTimeout(r, LATENCY_MS)).then(() => {
      inFlight--
      const { status, body } = scanReply(code)
      return json(body, status)
    })
  }
  if (url.endsWith('/fulfill/order-1/link') && method === 'POST') {
    const { status, body } = linkReply(JSON.parse(String(opts.body)).trackingNumber)
    return new Promise(r => setTimeout(r, 150)).then(() => json(body, status))
  }
  if (url.includes('/fulfill/order-1/scan/') && method === 'DELETE') {
    order = detail({ ...order.items[0], allocated: order.items[0].allocated - 1, quantity: order.items[0].quantity })
    return Promise.resolve({ ok: true, status: 204, json: async () => null })
  }
  return json({})
}

const ok = (code: string) => ({ status: 200, body: { success: true, code: 'SCANNED', message: null, pieceId: `pc-${code}`,
  barcode: code, allocatedCount: 1, requiredQuantity: 40, allComplete: false } })
const rejected = (code: string) => ({ status: 200, body: { success: false, code: 'PIECE_NOT_FOUND',
  message: 'Barcode not found in inventory', pieceId: null, barcode: code, allocatedCount: 0, requiredQuantity: 40, allComplete: false } })

async function openPickScreen() {
  renderWithProviders(<Fulfill />)
  await expect.poll(() => document.querySelector('[data-testid="queue-order-card"], button, a') && document.body.textContent?.includes('#101')).toBe(true)
  const row = [...document.querySelectorAll<HTMLElement>('*')].reverse().find(el => el.textContent === '#101')!
  row.click()
  await expect.poll(() => document.querySelector('[data-testid="fulfill-pick"] input.input-scan')).toBeTruthy()
  const input = document.querySelector<HTMLInputElement>('[data-testid="fulfill-pick"] input.input-scan')!
  input.focus()
  return input
}

const codes = (n: number, prefix = 'P') => Array.from({ length: n }, (_, i) => `${prefix}${String(i + 1).padStart(6, '0')}`)

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  seen = []; inFlight = 0; maxInFlight = 0
  order = detail()
  scanReply = ok
  linkReply = () => ({ status: 200, body: { trackingNumber: '8484805699', shipmentId: 'sh-1' } })
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('PickScreen — no lost scans, focus kept', () => {
  test('20 scanner bursts, ~300 ms each: all sent, in order, one at a time; input focused after the last', async () => {
    const input = await openPickScreen()
    const sent = codes(20)
    await commands.scannerBurst(sent, KEY_DELAY_MS)
    await expect.poll(() => seen.length, { timeout: 20 * LATENCY_MS + 10_000, interval: 100 }).toBe(20)
    await new Promise(r => setTimeout(r, LATENCY_MS + 300))
    expect(seen).toEqual(sent)
    expect(maxInFlight).toBe(1)
    expect(document.activeElement).toBe(input)
  })

  test('after a rejected scan the input keeps focus — the next scanner burst lands with no click', async () => {
    const input = await openPickScreen()
    scanReply = code => (code === 'BAD00001' ? rejected(code) : ok(code))
    await commands.scannerBurst(['BAD00001'], KEY_DELAY_MS)
    await expect.poll(() => document.body.textContent?.includes('✗') || document.body.textContent?.includes('Barcode not found')).toBe(true)
    await new Promise(r => setTimeout(r, 200))
    expect(document.activeElement).toBe(input)
    await commands.scannerBurst(['P000777'], KEY_DELAY_MS)
    await expect.poll(() => seen).toEqual(['BAD00001', 'P000777'])
  })

  test('AWB link step: after AWB_MISMATCH its input keeps focus — the next AWB lands with no click', async () => {
    order = detail({ quantity: 1, allocated: 1 })
    let attempts = 0
    linkReply = tn => (++attempts === 1
      ? { status: 409, body: { code: 'AWB_MISMATCH', scannedAwb: tn, existingAwb: '8484805699' } }
      : { status: 200, body: { trackingNumber: tn, shipmentId: 'sh-1' } })
    await openPickScreen()
    const linkBtn = await expect.poll(() => document.querySelector<HTMLElement>('[data-testid="btn-scan-to-link"]')).toBeTruthy()
    void linkBtn
    document.querySelector<HTMLElement>('[data-testid="btn-scan-to-link"]')!.click()
    await expect.poll(() => document.querySelectorAll('input.input-scan').length).toBe(2)
    const dialogInput = document.querySelectorAll<HTMLInputElement>('input.input-scan')[1]
    await expect.poll(() => document.activeElement).toBe(dialogInput)

    await commands.scannerBurst(['1111111111'], KEY_DELAY_MS)
    await expect.poll(() => document.body.textContent?.includes('8484805699')).toBe(true)     // mismatch message
    await new Promise(r => setTimeout(r, 200))
    expect(document.activeElement).toBe(dialogInput)
    await commands.scannerBurst(['8484805699'], KEY_DELAY_MS)
    await expect.poll(() => attempts).toBe(2)
  })
})
