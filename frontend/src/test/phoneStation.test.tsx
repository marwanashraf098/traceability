import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { useState } from 'react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { RelayHandlers } from '../phone/relayStream'
import type { PackSessionView, ScanPairingStatus } from '../api'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import PickupSessions from '../pages/PickupSessions'
import Fulfill from '../pages/Fulfill'
import StationGate from '../components/StationGate'

// Q1 — phone as scanner, per tablet: the floating control (Use phone → QR, connected chip,
// Unpair, reconnecting), the tablet's id, the station lock, and the routing of every phone scan
// to exactly one outcome — no target, a paused target, the pack session, PickScreen, the AWB
// link step on top, a screen unmounting mid-stream, the same event twice, one stream per tablet,
// and the phone-header label.

let relay: RelayHandlers | null = null
let streamDevices: string[] = []
let streamsClosed = 0
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (deviceId: string, h: RelayHandlers) => {
    relay = h
    streamDevices.push(deviceId)
    return () => { streamsClosed++; relay = null }
  },
}))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const CONNECTED: ScanPairingStatus = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari',
  pairCodeExpiresAt: '2026-10-04T07:02:00Z', claimedAt: '2026-10-04T07:01:00Z', expiresAt: '2026-10-04T19:00:00Z', reason: null }
const NONE: ScanPairingStatus = { status: 'none', pairingId: null, deviceLabel: null, pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }

let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>
let pairing: ScanPairingStatus
let routes: (url: string, method: string, body?: Record<string, unknown>) => unknown | Promise<unknown> | undefined
let store: Map<string, string>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(String(opts.body)) : undefined
  calls.push({ method, url, body })
  if (url.includes('/station/pairings/current') && method === 'GET') return json(pairing)
  if (url.endsWith('/station/pairings') && method === 'POST') {
    return json({ pairingId: 'p1', pairUrl: 'https://app.tracedtech.com/scan/AbC_123-xyz',
      pairCodeExpiresAt: new Date(Date.now() + 90_000).toISOString(), expiresAt: new Date(Date.now() + 12 * 3600_000).toISOString() })
  }
  const hit = routes(url, method, body)
  if (hit === undefined && method === 'GET' && url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
  if (hit instanceof Promise) return hit.then(h => json(h ?? {}))
  if (hit !== undefined) return json(hit)
  return json({})
}

/** Every outcome posted for a phone scan: [eventId, result, message]. */
const outcomes = () => calls.filter(c => c.method === 'POST' && c.url.includes('/station/relay-events/'))
  .map(c => [c.url.split('/relay-events/')[1].split('/')[0], c.body!.result, c.body!.message])
const targetPuts = () => calls.filter(c => c.method === 'PUT' && c.url.includes('/station/pairings/current/target'))
  .map(c => c.body!.label)

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}

function phoneScan(id: string, code: string) {
  act(() => relay!.onScan({ id, seq: Number(id.replace(/\D/g, '')) || 1, code, createdAt: '' }))
}

function withPhone(ui: React.ReactNode) {
  return renderWithProviders(<PhoneScanProvider>{ui}<PhoneControl /></PhoneScanProvider>)
}

async function streaming() {
  await waitFor(() => expect(relay).not.toBeNull())
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  store = new Map()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => { store.set(k, v) },
    removeItem: (k: string) => { store.delete(k) },
  })
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'worker' })).replace(/=+$/, '') + '.s')
  relay = null; streamDevices = []; streamsClosed = 0
  calls = []
  pairing = CONNECTED
  routes = () => undefined
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals(); vi.useRealTimers() })

// ── the floating control ─────────────────────────────────────────────────────

describe('floating control', () => {
  test('"Use phone" shows the QR of the pair URL with its countdown; the stream opens; Cancel unpairs and closes it', async () => {
    pairing = NONE
    const user = userEvent.setup()
    withPhone(<p>any page</p>)
    await user.click(await screen.findByRole('button', { name: 'Use phone' }))
    const modal = await screen.findByTestId('pair-modal')
    expect(screen.getByText('Scan with your phone camera')).toBeInTheDocument()
    const qr = await within(modal).findByTestId('pair-qr')
    expect(qr).toHaveAttribute('data-value', 'https://app.tracedtech.com/scan/AbC_123-xyz')
    expect(qr.querySelector('path')?.getAttribute('d')).toMatch(/^M\d+,\d+h1v1h-1z/)
    expect(within(modal).getByTestId('pair-expires')).toHaveTextContent(/Code expires in (89|90) s/)
    await streaming()                                    // listening for the claim
    const created = calls.find(c => c.method === 'POST' && c.url.endsWith('/station/pairings'))!
    expect(created.body).toEqual({ deviceId: streamDevices[0] })

    await user.click(within(modal).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByTestId('pair-modal')).toBeNull())
    expect(calls.some(c => c.method === 'DELETE' && c.url.includes(`/station/pairings/current?deviceId=${streamDevices[0]}`))).toBe(true)
    expect(screen.getByRole('button', { name: 'Use phone' })).toBeInTheDocument()
  })

  test('the phone claims the code → the QR closes and the chip shows "Phone connected · <device>"; Unpair', async () => {
    pairing = NONE
    const user = userEvent.setup()
    withPhone(<p>any page</p>)
    await user.click(await screen.findByRole('button', { name: 'Use phone' }))
    await screen.findByTestId('pair-qr')
    await streaming()
    act(() => relay!.onPairing(CONNECTED))
    await waitFor(() => expect(screen.queryByTestId('pair-modal')).toBeNull())
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone connected · iPhone · Safari')

    await user.click(within(screen.getByTestId('phone-chip')).getByRole('button', { name: 'Unpair' }))
    await waitFor(() => expect(screen.queryByTestId('phone-chip')).toBeNull())
    expect(screen.getByRole('button', { name: 'Use phone' })).toBeInTheDocument()
    expect(streamsClosed).toBeGreaterThan(0)
  })

  test('a link down for 5 s shows "Phone link reconnecting…"; back up clears it', async () => {
    withPhone(<p>any page</p>)
    await streaming()
    await screen.findByText('Phone connected · iPhone · Safari')
    vi.useFakeTimers()
    act(() => relay!.onConnection(false))
    act(() => { vi.advanceTimersByTime(4000) })
    expect(screen.getByTestId('phone-chip')).toHaveAttribute('data-state', 'connected')
    act(() => { vi.advanceTimersByTime(1200) })
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone link reconnecting…')
    act(() => relay!.onConnection(true))
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone connected · iPhone · Safari')
  })

  test('the pairing ending (pushed on the stream) closes the stream and offers "Use phone" again', async () => {
    withPhone(<p>any page</p>)
    await streaming()
    act(() => relay!.onPairing({ ...NONE, reason: 'worker_switched' }))
    await screen.findByRole('button', { name: 'Use phone' })
    expect(streamsClosed).toBe(1)
  })

  test("the tablet's id: random, URL-safe, kept in localStorage and used for every call", async () => {
    withPhone(<p>any page</p>)
    await streaming()
    const id = store.get('stationDeviceId')!
    expect(id).toMatch(/^[A-Za-z0-9_-]{16,64}$/)
    expect(streamDevices).toEqual([id])
    expect(calls.find(c => c.url.includes('/station/pairings/current'))!.url).toContain(`deviceId=${id}`)
  })

  test('station lock: the PIN gate revokes the last worker\'s phone (DELETE …/pairings/mine?reason=station_locked)', async () => {
    routes = url => (url.includes('/station/roster') ? [] : undefined)
    renderWithProviders(<StationGate />)
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE'
      && c.url.endsWith('/pack-sessions/pairings/mine?reason=station_locked'))).toBe(true))
  })
})

// ── routing ──────────────────────────────────────────────────────────────────

const LINE = { id: 'line-1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Cargo pants', imageUrl: null,
  quantity: 2, allocated: 0, allocatedPieces: [] as unknown[] }
const CARD = { id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City', courierType: 'delivery', batchNo: null, batchPrintedAt: null, items: [LINE] }
const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-04T07:00:00Z', workerName: 'Ahmed',
  counters: { packed: 0, setAside: 0, rejected: 0, left: 3 }, recent: [], openOrder: null } as unknown as PackSessionView
const OPENED = { result: 'opened', order: CARD, code: null, orderNumber: '#1047' }
const PIECE_OK = { status: 'scanned', order: { ...CARD, items: [{ ...LINE, allocated: 1, allocatedPieces: [{ piece_id: 'pc-1' }] }] },
  scan: { success: true, code: 'SCANNED', message: null, pieceId: 'pc-1', barcode: 'P000001', allocatedCount: 1,
    requiredQuantity: 2, allComplete: false }, packed: null, failCode: null, failMessage: null }
const waybillPosts = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/pack-sessions/sess-1/waybill')).map(c => c.body!.code)
const piecePosts = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/orders/order-1/scan') && c.url.includes('/pack-sessions/'))
  .map(c => c.body!.code)

describe('routing: exactly one outcome per phone scan', () => {
  test('no scanning screen open (the pickups page) → rejected "No scanning screen open on the tablet"', async () => {
    routes = url => (url.endsWith('/pickup-sessions') ? [] : undefined)
    withPhone(<PickupSessions />)
    await streaming()
    phoneScan('ev-1', '74821903')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'No scanning screen open on the tablet']]))
  })

  test('pack session: a phone scan → onScan with its relayEventId → one outcome each ("Order #1047 opened", "Cargo pants 1/2")', async () => {
    routes = (url, method) => {
      if (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST') return OPENED
      if (url.endsWith('/pack-sessions/sess-1/orders/order-1/scan') && method === 'POST') return PIECE_OK
      if (url.endsWith('/pack-sessions/sess-1')) return VIEW
      return undefined
    }
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await streaming()
    phoneScan('ev-1', 'D-07-74821903')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Order #1047 opened']]))
    expect(waybillPosts()).toEqual(['D-07-74821903'])
    phoneScan('ev-2', 'P000001')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'accepted', 'Cargo pants 1/2'])
    expect(piecePosts()).toEqual(['P000001'])
  })

  test('a rejected waybill clears the queue: the dropped phone scans are answered "Not applied — scan again", never sent', async () => {
    const waybill = deferred<unknown>()
    routes = (url, method) => (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST' ? waybill.promise : undefined)
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await streaming()
    phoneScan('ev-1', 'D-07-11111111')
    await waitFor(() => expect(waybillPosts()).toHaveLength(1))
    phoneScan('ev-2', 'P000001')
    phoneScan('ev-3', 'P000002')
    await act(async () => { waybill.resolve({ result: 'rejected', order: null, code: 'NOT_FOUND' }) })
    await waitFor(() => expect(outcomes()).toHaveLength(3))
    expect(outcomes()).toContainEqual(['ev-2', 'rejected', 'Not applied — scan again'])
    expect(outcomes()).toContainEqual(['ev-3', 'rejected', 'Not applied — scan again'])
    expect(outcomes().find(o => o[0] === 'ev-1')![1]).toBe('rejected')
    expect(waybillPosts()).toHaveLength(1)
    expect(piecePosts()).toHaveLength(0)
  })

  test('keyboard scans are unchanged: no outcome is posted for them', async () => {
    routes = (url, method) => (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST' ? OPENED : undefined)
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await streaming()
    const input = document.querySelector<HTMLInputElement>('input.input-scan')!
    input.value = 'D-07-74821903'
    fireEvent.keyDown(input, { key: 'Enter' })
    await screen.findByTestId('order-card')
    expect(waybillPosts()).toEqual(['D-07-74821903'])
    await new Promise(r => setTimeout(r, 50))
    expect(outcomes()).toEqual([])
  })

  test("a phone scan arriving while a keyboard scan is being typed doesn't wipe the typed characters", async () => {
    routes = (url, method) => (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST' ? new Promise(() => {}) : undefined)
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await streaming()
    const input = document.querySelector<HTMLInputElement>('input.input-scan')!
    input.value = 'D-07-7482'
    phoneScan('ev-1', 'D-07-11111111')
    expect(input.value).toBe('D-07-7482')
  })

  test('paused: the set-aside dialog is open → "Tablet busy — finish the dialog", nothing sent', async () => {
    const user = userEvent.setup()
    withPhone(<PackSessionScreen initial={{ ...VIEW, openOrder: CARD } as unknown as PackSessionView} onEnded={() => {}} />)
    await streaming()
    await user.click(await screen.findByRole('button', { name: /set aside/i }))
    await screen.findByText('Set aside order #1047?')
    phoneScan('ev-1', 'P000001')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    expect(piecePosts()).toHaveLength(0)
  })

  test('navigation mid-stream: the screen unmounts → its queued phone scans are answered "Not applied"; the one in flight when it finishes', async () => {
    const waybill = deferred<unknown>()
    routes = (url, method) => (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST' ? waybill.promise : undefined)
    function Nav() {
      const [open, setOpen] = useState(true)
      return <>{open && <PackSessionScreen initial={VIEW} onEnded={() => {}} />}
        <button onClick={() => setOpen(false)}>leave</button></>
    }
    const user = userEvent.setup()
    withPhone(<Nav />)
    await streaming()
    phoneScan('ev-1', 'D-07-11111111')
    await waitFor(() => expect(waybillPosts()).toHaveLength(1))
    phoneScan('ev-2', 'P000001')
    await user.click(screen.getByRole('button', { name: 'leave' }))
    await waitFor(() => expect(outcomes()).toEqual([['ev-2', 'rejected', 'Not applied — scan again']]))
    await act(async () => { waybill.resolve(OPENED) })
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1][0]).toBe('ev-1')
    phoneScan('ev-3', 'P000002')                          // nothing open now
    await waitFor(() => expect(outcomes()).toHaveLength(3))
    expect(outcomes()[2]).toEqual(['ev-3', 'rejected', 'No scanning screen open on the tablet'])
    expect(piecePosts()).toHaveLength(0)
  })

  test('the same event delivered twice is applied once and answered once', async () => {
    routes = (url, method) => (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST' ? OPENED : undefined)
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await streaming()
    phoneScan('ev-1', 'D-07-74821903')
    phoneScan('ev-1', 'D-07-74821903')
    await waitFor(() => expect(outcomes()).toHaveLength(1))
    await new Promise(r => setTimeout(r, 100))
    expect(outcomes()).toHaveLength(1)
    expect(waybillPosts()).toHaveLength(1)
  })

  test('one stream per tablet: moving between scanning screens keeps the one stream; the phone header follows the top screen', async () => {
    routes = (url, method) => {
      if (url.endsWith('/pack-sessions/sess-1/waybill') && method === 'POST') return OPENED
      if (url.endsWith('/pickup-sessions')) return []
      return undefined
    }
    function Nav() {
      const [page, setPage] = useState<'pack' | 'pickups'>('pack')
      return <>{page === 'pack' ? <PackSessionScreen initial={VIEW} onEnded={() => {}} /> : <PickupSessions />}
        <button onClick={() => setPage('pickups')}>pickups</button></>
    }
    const user = userEvent.setup()
    withPhone(<Nav />)
    await streaming()
    await waitFor(() => expect(targetPuts()).toContain('Waybill packing · scan a waybill'))
    phoneScan('ev-1', 'D-07-74821903')
    await waitFor(() => expect(targetPuts()).toContain('Waybill packing · #1047'))
    await user.click(screen.getByRole('button', { name: 'pickups' }))
    await waitFor(() => expect(targetPuts()[targetPuts().length - 1]).toBeNull())
    expect(streamDevices).toHaveLength(1)
    expect(streamsClosed).toBe(0)
  })
})

// ── PickScreen and the AWB link step ─────────────────────────────────────────

function pickDetail(allocated: number) {
  return {
    id: 'order-1', number: '#101', customer_name: 'Alice', customer_phone: null, status: 'new', payment_method: null,
    cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null, shipment_id: null,
    tracking_number: null, shipment_has_courier: true, awbPrinted: false,
    items: [{ id: 'item-0', variant_id: 'v0', sku: 'SKU-0', variant_title: 'Default Title', product_title: 'Product 0',
      quantity: 1, allocated,
      allocatedPieces: Array.from({ length: allocated }, (_, i) => ({ piece_id: `p${i}`, barcode: `PIECE${i}`,
        allocation_status: 'active', piece_status: 'reserved' })) }],
  }
}
const QUEUE = [{ id: 'order-1', number: '#101', customer_name: 'Alice', status: 'new', payment_method: null, cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false }]

describe('PickScreen and the AWB link step', () => {
  let allocated: number
  let linkReply: (tn: string) => { status: number; body: unknown }
  beforeEach(() => {
    allocated = 0
    linkReply = tn => ({ status: 200, body: { trackingNumber: tn, shipmentId: 'sh-1' } })
    stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
      const method = (opts.method ?? 'GET').toUpperCase()
      if (url.endsWith('/fulfill/queue')) { calls.push({ method, url }); return json(QUEUE) }
      if (url.includes('awaiting-waybill-count')) return json({ count: 0 })
      if (url.endsWith('/fulfill/order-1') && method === 'GET') return json(pickDetail(allocated))
      if (url.endsWith('/fulfill/order-1/scan') && method === 'POST') {
        const code = JSON.parse(String(opts.body)).barcode
        calls.push({ method, url, body: { barcode: code } })
        if (code === 'WRONG') return json({ success: false, code: 'WRONG_VARIANT', message: null, pieceId: null, barcode: code,
          allocatedCount: 0, requiredQuantity: 1, allComplete: false })
        allocated = 1
        return json({ success: true, code: 'SCANNED', message: null, pieceId: 'p0', barcode: code, allocatedCount: 1,
          requiredQuantity: 1, allComplete: true })
      }
      if (url.endsWith('/fulfill/order-1/link')) {
        const tn = JSON.parse(String(opts.body)).trackingNumber
        calls.push({ method, url, body: { trackingNumber: tn } })
        const { status, body } = linkReply(tn)
        return json(body, status)
      }
      return backend(url, opts)
    }))
  })

  async function openPick() {
    const user = userEvent.setup()
    withPhone(<Fulfill />)
    await user.click(await screen.findByText('#101'))
    await screen.findByTestId('fulfill-pick')
    await streaming()
    return user
  }
  const pickScans = () => calls.filter(c => c.url.endsWith('/fulfill/order-1/scan')).map(c => c.body!.barcode)
  const links = () => calls.filter(c => c.url.endsWith('/fulfill/order-1/link')).map(c => c.body!.trackingNumber)

  test('a phone scan picks through PickScreen\'s queue; the phone gets the line the screen shows', async () => {
    await openPick()
    await waitFor(() => expect(targetPuts()).toContain('Pick & Pack · #101'))
    phoneScan('ev-1', 'WRONG')
    await waitFor(() => expect(outcomes()).toHaveLength(1))
    expect(outcomes()[0]).toEqual(['ev-1', 'rejected', expect.any(String)])
    expect(outcomes()[0][2]).not.toBe('Not accepted')                // the screen's own rejection text
    phoneScan('ev-2', 'PIECE0')
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'accepted', 'PIECE0 · 1/1'])
    expect(pickScans()).toEqual(['WRONG', 'PIECE0'])
  })

  test('the AWB link step registers on top: a phone scan goes to the link handler → "AWB linked"; PickScreen gets nothing', async () => {
    allocated = 1
    const user = await openPick()
    await user.click(screen.getByTestId('btn-scan-to-link'))
    await waitFor(() => expect(targetPuts()).toContain('Link AWB · #101'))
    phoneScan('ev-1', ' 7482 1903 ')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'AWB linked']]))
    expect(links()).toEqual(['74821903'])
    expect(pickScans()).toEqual([])
  })

  test('the AWB link step: a mismatch answers the phone with the step\'s own error line', async () => {
    allocated = 1
    linkReply = () => ({ status: 409, body: { code: 'AWB_MISMATCH', scannedAwb: '11111111', existingAwb: '22222222' } })
    const user = await openPick()
    await user.click(screen.getByTestId('btn-scan-to-link'))
    await waitFor(() => expect(targetPuts()).toContain('Link AWB · #101'))
    phoneScan('ev-1', '11111111')
    await waitFor(() => expect(outcomes()).toHaveLength(1))
    expect(outcomes()[0][1]).toBe('rejected')
    expect(outcomes()[0][2]).toContain('11111111')
    expect(outcomes()[0][2]).toContain('22222222')
  })

  test('the cancel confirm is open → "Tablet busy — finish the dialog"', async () => {
    const user = await openPick()
    await user.click(screen.getByText('Cancel Order'))
    phoneScan('ev-1', 'PIECE0')
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'rejected', 'Tablet busy — finish the dialog']]))
    expect(pickScans()).toEqual([])
  })
})
