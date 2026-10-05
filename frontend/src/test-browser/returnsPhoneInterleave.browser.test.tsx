import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { commands } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import Returns from '../pages/Returns'

// Q2 — Scan returns with phone scans (relay events) and the station's own scanner (real keystroke
// bursts) interleaved, in a real browser (Chromium + WebKit): every scan is sent strictly one at a
// time, in arrival order; each phone scan gets exactly one outcome and carries its relayEventId,
// a keyboard scan none.

const LATENCY_MS = 120
const KEY_DELAY_MS = 4

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let relay: RelayHandlers | null = null
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_d: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data) })
const noContent = () => Promise.resolve({ ok: true, status: 204, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '0' : null) }, json: async () => null })

let sent: Array<{ code: string; relayEventId?: string }>
let inFlight: number
let maxInFlight: number
let arrived: string[]
let outcomes: Array<{ eventId: string; result: string }>
let afterKeyboardScan: () => void = () => {}
const recordEnter = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.dataset.testid === 'scan-input') {
    arrived.push(e.target.value.trim())
    afterKeyboardScan()
  }
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  sent = []; arrived = []; outcomes = []; inFlight = 0; maxInFlight = 0; relay = null
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
    if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
    if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
    if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
      expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
    if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
    if (url.endsWith('/returns/sessions/rs-1/scan') && method === 'POST') {
      sent.push({ code: body.scan, relayEventId: body.relayEventId })
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      return new Promise(r => setTimeout(r, LATENCY_MS)).then(() => { inFlight--; return noContent() })
    }
    if (url.endsWith('/returns/sessions/rs-1')) {
      return json({ id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-10-05T08:00:00Z', closed_by: null,
        closed_at: null, note: null, items: [], expectedPieces: [] })
    }
    return json({})
  }))
  window.addEventListener('keydown', recordEnter, true)
})
afterEach(async () => {
  window.removeEventListener('keydown', recordEnter, true)
  await expect.poll(() => inFlight, { timeout: 3_000 }).toBe(0)
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
})

test('Scan returns: phone scans and keyboard bursts interleaved — one in flight, arrival order, one outcome per phone scan', async () => {
  renderWithProviders(<PhoneScanProvider><Returns /><PhoneControl /></PhoneScanProvider>)
  await expect.poll(() => document.querySelector('[data-testid="open-session-button"]')).toBeTruthy()
  document.querySelector<HTMLElement>('[data-testid="open-session-button"]')!.click()
  await expect.poll(() => relay !== null && !!document.querySelector('[data-testid="scan-input"]')).toBe(true)
  document.querySelector<HTMLInputElement>('[data-testid="scan-input"]')!.focus()

  const phoneCodes = Array.from({ length: 8 }, (_, i) => `R${String(i + 1).padStart(6, '0')}`)
  const keyCodes = Array.from({ length: 8 }, (_, i) => `K${String(i + 1).padStart(6, '0')}`)
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

  const total = phoneCodes.length + keyCodes.length
  await expect.poll(() => sent.length, { timeout: total * LATENCY_MS + 10_000, interval: 50 }).toBe(total)
  await expect.poll(() => outcomes.length, { timeout: 5_000 }).toBe(phoneCodes.length)
  await new Promise(r => setTimeout(r, LATENCY_MS + 200))

  const firstK = arrived.findIndex(c => c.startsWith('K'))
  const lastK = arrived.map(c => c.startsWith('K')).lastIndexOf(true)
  expect(arrived.slice(firstK, lastK).some(c => c.startsWith('R'))).toBe(true)          // really interleaved

  expect(maxInFlight).toBe(1)
  expect(sent.map(s => s.code)).toEqual(arrived)                                        // arrival order
  expect(sent.filter(s => s.code.startsWith('K')).every(s => s.relayEventId === undefined)).toBe(true)
  expect(sent.filter(s => s.code.startsWith('R')).map(s => s.relayEventId)).toEqual(phoneCodes.map((_, i) => `ev-${i + 1}`))
  const perEvent = new Map<string, number>()
  for (const o of outcomes) perEvent.set(o.eventId, (perEvent.get(o.eventId) ?? 0) + 1)
  expect([...perEvent.values()].every(n => n === 1)).toBe(true)
  expect(outcomes.every(o => o.result === 'accepted')).toBe(true)
})
