import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import Returns from '../pages/Returns'
import { PieceCode } from '../pages/exchangesRefunds/RequestLifecycle'

// Q2 — Scan returns as a phone target: the scan request's body is byte-identical to before for a
// keyboard scan and carries relayEventId only for a phone scan; the screen registers as
// "Scan returns · <ref>" with the header phone button; each phone scan gets one outcome
// ("Received · #1047" / "Not expected" / "Not recognized"); the damage-reason field and the
// abandon dialog pause it; "via phone" tags on items, the session's phone-scan count and the
// case detail's piece code.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let relay: RelayHandlers | null = null
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_d: string, h: RelayHandlers) => { relay = h; return () => { relay = null } },
}))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : k.toLowerCase() === 'content-length' ? '10' : null) },
  json: async () => structuredClone(data),
})
const noContent = () => Promise.resolve({ ok: true, status: 204, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '0' : null) }, json: async () => null })

const CONNECTED = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari', pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }

type Item = Record<string, unknown>
const item = (n: number, over: Item = {}): Item => ({ id: `item-${n}`, piece_id: `piece-${n}`, barcode: `PC-${n}`,
  status: 'return_pending_inspection', variant_title: 'M', product_title: 'Hoodie', sku: 'HOOD-M', disposition: 'pending',
  unexpected: false, damage_reason: null, via_phone: false, order_number: '#1047',
  scanned_at: `2026-10-05T08:00:${String(n).padStart(2, '0')}Z`, ...over })

let items: Item[]
let phoneScanCount: number
let scanBodies: string[]
let reject: (code: string) => boolean
let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(String(opts.body)) : undefined
  calls.push({ method, url, body })
  if (url.includes('/station/pairings/current') && method === 'GET') return json(CONNECTED)
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1/scan') && method === 'POST') {
    scanBodies.push(String(opts.body))
    const code = body!.scan as string
    if (reject(code)) return json({ message_en: 'no match' }, 422)
    const n = items.length + 1
    items = [...items, item(n, { barcode: code, unexpected: code.startsWith('UNEXP'), via_phone: !!body!.relayEventId })]
    if (body!.relayEventId) phoneScanCount++
    return noContent()
  }
  if (url.endsWith('/returns/sessions/rs-1') && method === 'GET') {
    const last = items[items.length - 1]
    return json({ id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-10-05T08:00:00Z', closed_by: null, closed_at: null,
      note: null, items, expectedPieces: [], phoneScanCount,
      lastScan: last ? { kind: 'piece', code: last.barcode, label: last.product_title, at: last.scanned_at } : null })
  }
  return json({})
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  relay = null; items = []; phoneScanCount = 0; scanBodies = []; calls = []
  reject = () => false
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

const outcomes = () => calls.filter(c => c.method === 'POST' && c.url.includes('/station/relay-events/'))
  .map(c => [c.url.split('/relay-events/')[1].split('/')[0], c.body!.result, c.body!.message])
const targets = () => calls.filter(c => c.method === 'PUT' && c.url.includes('/station/pairings/current/target')).map(c => c.body!.label)
const phoneScan = (id: string, code: string) => act(() => relay!.onScan({ id, seq: 1, code, createdAt: '' }))

async function openSession() {
  const user = userEvent.setup()
  renderWithProviders(<PhoneScanProvider><Returns /><PhoneControl /></PhoneScanProvider>)
  await user.click(await screen.findByTestId('open-session-button'))
  await screen.findByTestId('scan-input')
  await waitFor(() => expect(relay).not.toBeNull())
  return user
}

describe('the scan request body', () => {
  test('a keyboard scan\'s body is byte-identical to before — no relayEventId key at all', async () => {
    await openSession()
    const input = screen.getByTestId('scan-input') as HTMLInputElement
    input.value = 'PC-77'
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(scanBodies).toHaveLength(1))
    expect(scanBodies[0]).toBe('{"scan":"PC-77","locationId":null}')
    expect(scanBodies[0]).not.toContain('relayEventId')
  })

  test('a phone scan\'s body carries its relayEventId', async () => {
    await openSession()
    phoneScan('ev-1', 'PC-88')
    await waitFor(() => expect(scanBodies).toHaveLength(1))
    expect(scanBodies[0]).toBe('{"scan":"PC-88","locationId":null,"relayEventId":"ev-1"}')
  })
})

describe('Scan returns as a phone target', () => {
  test('registers as "Scan returns · <ref>" with the header phone button; phone scans get one outcome each', async () => {
    await openSession()
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone connected · iPhone · Safari')
    await waitFor(() => expect(targets()).toContain('Scan returns · RT-RS1'))

    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Received · #1047']]))
    phoneScan('ev-2', 'UNEXP-2')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'accepted', 'Not expected'])
    reject = code => code === 'GARBAGE'
    phoneScan('ev-3', 'GARBAGE')
    await waitFor(() => expect(outcomes()).toHaveLength(3))
    expect(outcomes()[2]).toEqual(['ev-3', 'rejected', 'Not recognized'])
  })

  test('the abandon dialog pauses it: "Tablet busy — finish the dialog", nothing sent', async () => {
    const user = await openSession()
    await user.click(screen.getByTestId('abandon-link'))
    phoneScan('ev-1', 'PC-1')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    expect(scanBodies).toEqual([])
  })

  test('the damage-reason field pauses it', async () => {
    items = [item(1)]
    const user = await openSession()
    await user.click(await screen.findByRole('button', { name: 'Damage' }))
    await screen.findByPlaceholderText('Reason (required)')
    phoneScan('ev-1', 'PC-2')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    expect(scanBodies).toEqual([])
  })

  test('"via phone" tags on phone-scanned items and the session\'s "N scans came from a phone"', async () => {
    items = [item(1, { via_phone: true }), item(2), item(3, { via_phone: true, disposition: 'restocked', status: 'available' })]
    phoneScanCount = 2
    await openSession()
    await waitFor(() => expect(screen.getAllByTestId('via-phone')).toHaveLength(2))
    expect(screen.getByTestId('returns-phone-scan-count')).toHaveTextContent('2 scans came from a phone')
  })

  test('no phone scans → no count line', async () => {
    items = [item(1)]
    await openSession()
    await screen.findAllByText('PC-1')
    expect(screen.queryByTestId('returns-phone-scan-count')).toBeNull()
    expect(screen.queryByTestId('via-phone')).toBeNull()
  })
})

describe('the return case detail', () => {
  test('a piece received through a phone scan carries "via phone"; others don\'t', () => {
    renderWithProviders(<><PieceCode item={{ shortCode: 'P000123', viaPhone: true }} /><PieceCode item={{ shortCode: 'P000456', viaPhone: false }} /></>)
    expect(screen.getAllByTestId('via-phone')).toHaveLength(1)
    expect(screen.getByText('P000123').closest('span')!.parentElement).toHaveTextContent('via phone')
  })
})
