import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { commands, userEvent } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import * as api from '../api'
import Returns from '../pages/Returns'
import PickupSessions from '../pages/PickupSessions'

// R1 — Scan returns and Pickups on useScanner, in a real browser (Chromium + WebKit): scanner
// bursts typed with no clicks while each scan request takes ~300 ms. Before R1, Scan returns
// disabled its input while a scan was in flight (keystrokes of the next scan dropped) and
// focused the still-disabled input afterwards (focus lost after a rejection); Pickups returned
// early while a scan was in flight — leaving the next code's text in the input, so the scan
// after it was sent concatenated.

const LATENCY_MS = 300
const KEY_DELAY_MS = 4

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : k.toLowerCase() === 'content-length' ? '10' : null) },
  json: async () => structuredClone(data),
})
const noContent = () => Promise.resolve({ ok: true, status: 204, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '0' : null) }, json: async () => null })

let seen: string[]
let inFlight: number
let maxInFlight: number
let reject: (code: string) => boolean

function slow<T>(code: string, reply: () => Promise<T>): Promise<T> {
  seen.push(code)
  inFlight++
  maxInFlight = Math.max(maxInFlight, inFlight)
  return new Promise(r => setTimeout(r, LATENCY_MS)).then(() => { inFlight--; return reply() })
}

// ── Scan returns ─────────────────────────────────────────────────────────────

let returnItems: unknown[]
function returnsBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1/scan') && method === 'POST') {
    const code = JSON.parse(String(opts.body)).scan as string
    return slow(code, () => (reject(code) ? json({ message_en: 'no match' }, 422) : noContent()))
  }
  if (url.endsWith('/returns/sessions/rs-1') && method === 'GET') {
    return json({ id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-10-04T08:00:00Z', closed_by: null,
      closed_at: null, note: null, items: returnItems, expectedPieces: [] })
  }
  return json({})
}

async function openReturnSession() {
  renderWithProviders(<Returns />)
  await expect.poll(() => document.querySelector('[data-testid="open-session-button"]')).toBeTruthy()
  document.querySelector<HTMLElement>('[data-testid="open-session-button"]')!.click()
  await expect.poll(() => document.querySelector('[data-testid="scan-input"]')).toBeTruthy()
  const input = document.querySelector<HTMLInputElement>('[data-testid="scan-input"]')!
  input.focus()
  return input
}

// ── Pickups ──────────────────────────────────────────────────────────────────

function pickupsBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const session = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-10-04', scheduledTimeSlot: null,
    scannedCount: 0, openedByName: 'Ahmed', createdAt: '2026-10-04T08:00:00Z', notes: null, closedByName: null,
    closedAt: null, scans: [] }
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([session])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(session)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') {
    const tn = JSON.parse(String(opts.body)).trackingNumber as string
    return slow(tn, () => json(reject(tn)
      ? { outcome: 'UNKNOWN_AWB', entry: null }
      : { outcome: 'ACCEPTED', entry: { shipmentId: `sh-${tn}`, trackingNumber: tn, orderNumber: '#1', codAmount: 0,
          scannedAt: '2026-10-04T08:01:00Z', scannedByName: 'Ahmed' } }))
  }
  return json({})
}

async function openPickupSession() {
  renderWithProviders(<PickupSessions />)
  await expect.poll(() => document.querySelector('tr.tbl-row')).toBeTruthy()
  document.querySelector<HTMLElement>('tr.tbl-row')!.click()
  await expect.poll(() => document.querySelector('input[placeholder="Scan or type AWB…"]')).toBeTruthy()
  const input = document.querySelector<HTMLInputElement>('input[placeholder="Scan or type AWB…"]')!
  input.focus()
  return input
}

const codes = (n: number, prefix: string) => Array.from({ length: n }, (_, i) => `${prefix}${String(i + 1).padStart(6, '0')}`)

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  seen = []; inFlight = 0; maxInFlight = 0
  reject = () => false
  returnItems = []
})
afterEach(async () => {
  // A request still in flight when a test ends would finish in the next one and skew its counters.
  await expect.poll(() => inFlight, { timeout: 3_000 }).toBe(0)
  cleanup()
  vi.unstubAllGlobals()
})

describe('Scan returns', () => {
  beforeEach(() => stubFetchWithShellDefaults(vi.fn(returnsBackend)))

  test('20 scanner bursts, ~300 ms each: all sent, in order, one at a time; input focused after the last', async () => {
    const input = await openReturnSession()
    const sent = codes(20, 'PC-R')
    await commands.scannerBurst(sent, KEY_DELAY_MS)
    await expect.poll(() => seen.length, { timeout: 20 * LATENCY_MS + 10_000, interval: 100 }).toBe(20)
    await new Promise(r => setTimeout(r, LATENCY_MS + 300))
    expect(seen).toEqual(sent)
    expect(maxInFlight).toBe(1)
    expect(document.activeElement).toBe(input)
  })

  test('after a rejected scan (422) the input keeps focus — the next burst lands with no click', async () => {
    const input = await openReturnSession()
    reject = code => code === 'GARBAGE1'
    await commands.scannerBurst(['GARBAGE1'], KEY_DELAY_MS)
    await expect.poll(() => document.querySelector('[data-testid="rejected-scan-toast"]')).toBeTruthy()
    await new Promise(r => setTimeout(r, 200))
    expect(document.activeElement).toBe(input)
    await commands.scannerBurst(['PC-R000777'], KEY_DELAY_MS)
    await expect.poll(() => seen).toEqual(['GARBAGE1', 'PC-R000777'])
  })

  test('damage reason: clicking into it keeps focus there, typing works, closing it returns focus to the scan input', async () => {
    returnItems = [{ id: 'item-1', piece_id: 'piece-1', barcode: 'PC-piece-1', status: 'return_pending_inspection',
      variant_title: 'Black · M', product_title: 'T-Shirt', sku: 'TS-BLK-M', disposition: 'pending',
      unexpected: false, damage_reason: null }]
    const input = await openReturnSession()
    await expect.poll(() => [...document.querySelectorAll('button')].some(b => b.textContent === 'Damage')).toBe(true)
    ;[...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === 'Damage')!.click()
    await expect.poll(() => document.querySelector('input[placeholder="Reason (required)"]')).toBeTruthy()
    const reason = document.querySelector<HTMLInputElement>('input[placeholder="Reason (required)"]')!
    reason.click()
    await new Promise(r => setTimeout(r, 100))
    expect(document.activeElement).toBe(reason)
    await userEvent.keyboard('torn')                              // real keystrokes land in the reason, not the scan input
    expect(reason.value).toBe('torn')
    expect(seen).toEqual([])
    ;[...document.querySelectorAll<HTMLButtonElement>('button')].filter(b => b.textContent?.trim() === 'Cancel').pop()!.click()
    await expect.poll(() => document.querySelector('input[placeholder="Reason (required)"]')).toBeNull()
    await expect.poll(() => document.activeElement).toBe(input)
  })
})

describe('Pickups', () => {
  beforeEach(() => stubFetchWithShellDefaults(vi.fn(pickupsBackend)))

  test('20 scanner bursts, ~300 ms each: all sent, in order, one at a time; input focused after the last', async () => {
    const input = await openPickupSession()
    const sent = codes(20, '84848')
    await commands.scannerBurst(sent, KEY_DELAY_MS)
    await expect.poll(() => seen.length, { timeout: 20 * LATENCY_MS + 10_000, interval: 100 }).toBe(20)
    await new Promise(r => setTimeout(r, LATENCY_MS + 300))
    expect(seen).toEqual(sent)
    expect(maxInFlight).toBe(1)
    expect(document.activeElement).toBe(input)
  })

  test('two scans typed back to back → two requests with the exact codes (no concatenation)', async () => {
    await openPickupSession()
    await commands.scannerBurst(['1111111111', '2222222222'], KEY_DELAY_MS)
    await expect.poll(() => seen.length, { timeout: 5_000 }).toBe(2)
    await new Promise(r => setTimeout(r, LATENCY_MS + 200))
    expect(seen).toEqual(['1111111111', '2222222222'])
  })

  test('after a rejected scan the input keeps focus — the next scan lands with no click', async () => {
    const input = await openPickupSession()
    reject = tn => tn === '9999999999'
    await commands.scannerBurst(['9999999999'], KEY_DELAY_MS)
    await expect.poll(() => seen).toEqual(['9999999999'])
    await new Promise(r => setTimeout(r, LATENCY_MS + 200))
    expect(document.activeElement).toBe(input)
    await commands.scannerBurst(['3333333333'], KEY_DELAY_MS)
    await expect.poll(() => seen).toEqual(['9999999999', '3333333333'])
  })
})

void api
