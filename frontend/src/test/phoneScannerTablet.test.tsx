import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import type { RelayHandlers } from '../pages/fulfill/relayStream'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'

// S6 — phone as scanner, the tablet side (PackSessionScreen): the QR modal; a relay event goes
// through the scanner queue to onScan with its relayEventId and gets exactly one outcome; scans
// dropped by clearQueue are answered "not applied"; keyboard scans send no outcome.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getRoleFromToken: vi.fn(() => 'worker'),
    getMe: vi.fn(),
    getPackSession: vi.fn(),
    scanPackWaybill: vi.fn(),
    scanPackPiece: vi.fn(),
    createScanPairing: vi.fn(),
    getScanPairing: vi.fn(),
    unpairScanPairing: vi.fn(),
    postRelayOutcome: vi.fn(),
  }
})

let relay: RelayHandlers | null = null
let streamsOpened = 0
let streamsClosed = 0
vi.mock('../pages/fulfill/relayStream', () => ({
  openRelayStream: (_sessionId: string, h: RelayHandlers) => {
    relay = h
    streamsOpened++
    return () => { streamsClosed++; relay = null }
  },
}))

const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-02T07:00:00Z',
  workerName: 'Ahmed', counters: { packed: 0, setAside: 0, rejected: 0, left: 0 }, recent: [], openOrder: null,
} as api.PackSessionView

const CARD = {
  id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City', courierType: 'delivery', batchNo: null, batchPrintedAt: null,
  items: [{ id: 'line-1', variant_id: 'v1', sku: 'S-1', variant_title: 'M', product_title: 'Cargo pants', imageUrl: null,
    quantity: 2, allocated: 0, allocatedPieces: [] }],
} as unknown as api.PackOrderCard

const NONE: api.ScanPairingStatus = { status: 'none', pairingId: null, deviceLabel: null, pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }
const CONNECTED: api.ScanPairingStatus = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari',
  pairCodeExpiresAt: '2026-10-02T07:02:00Z', claimedAt: '2026-10-02T07:01:00Z', expiresAt: '2026-10-02T19:00:00Z', reason: null }

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}

const outcomes = () => vi.mocked(api.postRelayOutcome).mock.calls.map(c => [c[1], c[2], c[3]])

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  relay = null
  streamsOpened = 0
  streamsClosed = 0
  vi.mocked(api.getMe).mockResolvedValue({ name: 'Ahmed', email: null, role: 'worker' })
  vi.mocked(api.getPackSession).mockResolvedValue(VIEW)
  vi.mocked(api.getScanPairing).mockResolvedValue(NONE)
  vi.mocked(api.unpairScanPairing).mockResolvedValue(undefined)
  vi.mocked(api.postRelayOutcome).mockResolvedValue(undefined)
})
afterEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals() })

describe('pairing UI', () => {
  test('"Use phone" shows the QR of the pair URL with its countdown; Cancel unpairs and closes it', async () => {
    vi.mocked(api.createScanPairing).mockResolvedValue({ pairingId: 'p1',
      pairUrl: 'https://app.tracedtech.com/scan/AbC_123-xyz', pairCodeExpiresAt: new Date(Date.now() + 90_000).toISOString(),
      expiresAt: new Date(Date.now() + 12 * 3600_000).toISOString() })
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Use phone' }))
    const modal = await screen.findByTestId('pair-modal')
    expect(screen.getByText('Scan with your phone camera')).toBeInTheDocument()
    const qr = await within(modal).findByTestId('pair-qr')
    expect(qr).toHaveAttribute('data-value', 'https://app.tracedtech.com/scan/AbC_123-xyz')
    expect(qr.querySelector('path')?.getAttribute('d')).toMatch(/^M\d+,\d+h1v1h-1z/)
    expect(within(modal).getByTestId('pair-expires')).toHaveTextContent(/Code expires in (89|90) s/)
    expect(streamsOpened).toBe(1)                       // listening for the claim

    await user.click(within(modal).getByRole('button', { name: 'Cancel' }))
    expect(api.unpairScanPairing).toHaveBeenCalledWith('sess-1')
    await waitFor(() => expect(screen.queryByTestId('pair-modal')).toBeNull())
    expect(screen.getByRole('button', { name: 'Use phone' })).toBeInTheDocument()
  })

  test('the phone claims the code → the QR closes and the header shows "Phone connected · <device>"; Unpair', async () => {
    vi.mocked(api.createScanPairing).mockResolvedValue({ pairingId: 'p1', pairUrl: 'https://app.tracedtech.com/scan/x',
      pairCodeExpiresAt: new Date(Date.now() + 90_000).toISOString(), expiresAt: new Date(Date.now() + 3600_000).toISOString() })
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await user.click(await screen.findByRole('button', { name: 'Use phone' }))
    await screen.findByTestId('pair-qr')

    act(() => relay!.onPairing(CONNECTED))
    await waitFor(() => expect(screen.queryByTestId('pair-modal')).toBeNull())
    expect(screen.getByTestId('phone-chip')).toHaveTextContent('Phone connected · iPhone · Safari')

    await user.click(within(screen.getByTestId('phone-chip')).getByRole('button', { name: 'Unpair' }))
    expect(api.unpairScanPairing).toHaveBeenCalledWith('sess-1')
    await waitFor(() => expect(screen.queryByTestId('phone-chip')).toBeNull())
    expect(streamsClosed).toBeGreaterThan(0)
  })
})

describe('relay events through the scanner queue', () => {
  test('a phone scan → onScan with its relayEventId → exactly one outcome per scan', async () => {
    vi.mocked(api.getScanPairing).mockResolvedValue(CONNECTED)
    vi.mocked(api.scanPackWaybill).mockResolvedValue({ result: 'opened', order: CARD, code: null, subReason: null,
      orderNumber: '#1047', who: null, at: null, state: null, messageEn: null, messageAr: null } as api.WaybillOutcome)
    vi.mocked(api.scanPackPiece).mockResolvedValue({ status: 'scanned', order: { ...CARD,
      items: [{ ...CARD.items[0], allocated: 1, allocatedPieces: [{ piece_id: 'pc-1' }] }] } as unknown as api.PackOrderCard,
      scan: { success: true, code: 'SCANNED', message: null, pieceId: 'pc-1', barcode: 'P000001', allocatedCount: 1,
        requiredQuantity: 2, allComplete: false }, packed: null, failCode: null, failMessage: null } as api.PackScanResponse)
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await waitFor(() => expect(relay).not.toBeNull())

    act(() => relay!.onScan({ id: 'ev-1', seq: 1, code: 'D-07-74821903', createdAt: '' }))
    await waitFor(() => expect(outcomes()).toEqual([['ev-1', 'accepted', 'Order #1047 opened']]))
    expect(api.scanPackWaybill).toHaveBeenCalledWith('sess-1', 'D-07-74821903')

    act(() => relay!.onScan({ id: 'ev-2', seq: 2, code: 'P000001', createdAt: '' }))
    await waitFor(() => expect(outcomes()).toHaveLength(2))
    expect(outcomes()[1]).toEqual(['ev-2', 'accepted', 'Cargo pants 1/2'])
    expect(api.scanPackPiece).toHaveBeenCalledWith('sess-1', 'order-1', 'P000001')
  })

  test('a rejected waybill clears the queue: the dropped phone scans are answered "Not applied — scan again"', async () => {
    vi.mocked(api.getScanPairing).mockResolvedValue(CONNECTED)
    const waybill = deferred<api.WaybillOutcome>()
    vi.mocked(api.scanPackWaybill).mockReturnValue(waybill.promise)
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await waitFor(() => expect(relay).not.toBeNull())

    act(() => relay!.onScan({ id: 'ev-1', seq: 1, code: 'D-07-11111111', createdAt: '' }))
    await waitFor(() => expect(api.scanPackWaybill).toHaveBeenCalledTimes(1))
    act(() => relay!.onScan({ id: 'ev-2', seq: 2, code: 'P000001', createdAt: '' }))
    act(() => relay!.onScan({ id: 'ev-3', seq: 3, code: 'P000002', createdAt: '' }))
    await act(async () => {
      waybill.resolve({ result: 'rejected', order: null, code: 'NOT_FOUND', subReason: null, orderNumber: null,
        who: null, at: null, state: null, messageEn: null, messageAr: null } as api.WaybillOutcome)
    })

    await waitFor(() => expect(outcomes()).toHaveLength(3))
    expect(outcomes()).toContainEqual(['ev-2', 'rejected', 'Not applied — scan again'])
    expect(outcomes()).toContainEqual(['ev-3', 'rejected', 'Not applied — scan again'])
    const first = outcomes().find(o => o[0] === 'ev-1')!
    expect(first[1]).toBe('rejected')
    expect(api.scanPackWaybill).toHaveBeenCalledTimes(1)        // the dropped ones were never sent
    expect(api.scanPackPiece).not.toHaveBeenCalled()
  })

  test('keyboard scans are unchanged: no outcome is posted for them', async () => {
    vi.mocked(api.getScanPairing).mockResolvedValue(CONNECTED)
    vi.mocked(api.scanPackWaybill).mockResolvedValue({ result: 'opened', order: CARD, code: null, subReason: null,
      orderNumber: '#1047', who: null, at: null, state: null, messageEn: null, messageAr: null } as api.WaybillOutcome)
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    const input = document.querySelector<HTMLInputElement>('input.input-scan')!
    input.value = 'D-07-74821903'
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(api.scanPackWaybill).toHaveBeenCalledWith('sess-1', 'D-07-74821903'))
    await screen.findByTestId('order-card')
    expect(api.postRelayOutcome).not.toHaveBeenCalled()
  })

  test('a phone scan arriving while a keyboard scan is being typed doesn\'t wipe the typed characters', async () => {
    vi.mocked(api.getScanPairing).mockResolvedValue(CONNECTED)
    vi.mocked(api.scanPackWaybill).mockReturnValue(new Promise(() => {}))
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    await waitFor(() => expect(relay).not.toBeNull())
    const input = document.querySelector<HTMLInputElement>('input.input-scan')!
    input.value = 'D-07-7482'
    act(() => relay!.onScan({ id: 'ev-1', seq: 1, code: 'D-07-11111111', createdAt: '' }))
    expect(input.value).toBe('D-07-7482')
  })
})
