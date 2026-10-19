import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import * as api from '../api'
import Exceptions from '../pages/Exceptions'
import PickupSessions from '../pages/PickupSessions'
import ar from '../locales/ar.json'

/**
 * Deleted-in-Bosta slices 2 + 3 (screens).
 *  - Exceptions: the five condition-only alerts show what clears them instead of a Resolve button;
 *    every other alert keeps Resolve.
 *  - Pickup session: a LEG_ENDED scan says "cancelled or deleted in Bosta — don't hand it to the courier";
 *    a close that left waybills out lists them.
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, request: vi.fn() }
})

const CLEARS: Record<string, [string, string]> = {
  cancelled_live_shipment: ['BOSTA_LEG_ENDED', 'Cancel it in Bosta to stop a courier coming.'],
  cancelled_but_delivered: ['DELIVERED_WHILE_CANCELLED', 'Collect the payment from the customer or arrange a return.'],
  guided_unpack:           ['ORDER_UNPACKED', 'Open the order (Go →) and unpack them.'],
  exchange_needs_mapping:  ['EXCHANGE_MAPPED', 'Open it (Go →) to choose them.'],
  exchange_unmapped_state: ['BOSTA_STATE_RECOGNISED', 'If it looks stuck, check the exchange in Bosta.'],
}

function alert(type: string, i: number, clearsWhen?: string) {
  return {
    type, severity: 'HIGH', subject_key: `${type}:order:${i}`, subject_type: 'order',
    order_id: `o-${i}`, order_number: `#${4100 + i}`, tracking_number: String(6979330980 + i), ageSeconds: 600,
    descriptionEn: `Alert ${type}`, descriptionAr: `تنبيه ${type}`, suggestedAction: 'x', actionUrl: '/orders',
    ...(clearsWhen ? { clearsWhen } : {}),
  }
}

describe('Exceptions — condition-only alerts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const items = [
      ...Object.entries(CLEARS).map(([type, [code]], i) => alert(type, i, code)),
      alert('stuck_shipment', 9),
    ]
    vi.mocked(api.request).mockImplementation(async () => ({
      total: items.length, page: 0, size: 50, items, counts: { critical: 0, high: items.length, medium: 0, low: 0 },
    }))
  })

  test('the five show their clearsWhen text and no Resolve; stuck_shipment keeps Resolve', async () => {
    renderWithProviders(<Exceptions />)
    await screen.findByText('Alert stuck_shipment')

    for (const [type, [, text]] of Object.entries(CLEARS)) {
      const row = screen.getByText(`Alert ${type}`).closest<HTMLElement>('.card')!
      expect(within(row).getByTestId('exception-clears-when').textContent).toContain(text)
      expect(within(row).queryByRole('button', { name: 'Resolve' })).toBeNull()
    }
    const stuck = screen.getByText('Alert stuck_shipment').closest<HTMLElement>('.card')!
    expect(within(stuck).getByRole('button', { name: 'Resolve' })).toBeTruthy()
    expect(within(stuck).queryByTestId('exception-clears-when')).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Resolve' })).toHaveLength(1)
  })

  test('every clearsWhen code has an Arabic text', () => {
    const texts = (ar as { exc: { clearsWhen: Record<string, string> } }).exc.clearsWhen
    for (const [code] of Object.values(CLEARS)) expect(texts[code]?.length).toBeGreaterThan(20)
  })
})

// ── Pickup session ───────────────────────────────────────────────────────────

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, statusText: 'OK',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data),
})

const LIVE = { shipmentId: 'sh-1', trackingNumber: '6979330901', orderNumber: '#4201', codAmount: 450,
  scannedAt: '2026-10-19T08:01:00Z', scannedByName: 'Mona' }
const ENDED = { shipmentId: 'sh-2', trackingNumber: '6979330902', orderNumber: '#4202', codAmount: 300,
  scannedAt: '2026-10-19T08:02:00Z', scannedByName: 'Mona' }
const base = { id: 'pk-1', scheduledDate: '2026-10-19', scheduledTimeSlot: '10:00 - 13:00', openedByName: 'Mona',
  createdAt: '2026-10-19T08:00:00Z', notes: null }
let session: Record<string, unknown>
let fetchFn: ReturnType<typeof vi.fn>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([{ ...base, sessionStatus: session.sessionStatus, scannedCount: 2 }])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(session)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') return json({ outcome: 'LEG_ENDED', entry: {} })
  if (url.endsWith('/pickup-sessions/pk-1/close') && method === 'POST') {
    session = { ...session, sessionStatus: 'closed', closedByName: 'Mona', closedAt: '2026-10-19T09:00:00Z', scans: [LIVE] }
    return json({ shipmentsClosed: 1, pieceExceptions: [], leftOut: [
      { shipmentId: 'sh-2', trackingNumber: '6979330902', orderNumber: '#4202' }] })
  }
  return json({})
}

async function openSession() {
  const user = userEvent.setup()
  renderWithProviders(<PickupSessions />)
  await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
  await user.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
  return user
}

describe('Pickup session — legs that ended in Bosta', () => {
  beforeEach(() => {
    clearAccessToken()
    setAccessToken(`h.${btoa(JSON.stringify({ role: 'worker' }))}.s`)
    session = { ...base, sessionStatus: 'open', scannedCount: 2, closedByName: null, closedAt: null, scans: [LIVE, ENDED] }
    fetchFn = vi.fn(backend)
    stubFetchWithShellDefaults(fetchFn)
  })
  afterEach(() => { vi.unstubAllGlobals(); clearAccessToken() })

  test('a LEG_ENDED scan tells the worker not to hand it over', async () => {
    const user = await openSession()
    const input = await waitFor(() => {
      const el = document.querySelector<HTMLInputElement>('input')
      expect(el).toBeTruthy()
      return el!
    })
    await user.type(input, '6979330903{Enter}')
    expect(await screen.findByText("This waybill is cancelled or deleted in Bosta — don't hand it to the courier."))
      .toBeTruthy()
  })

  test('close lists the waybills left out of the handover', async () => {
    const user = await openSession()
    await user.click(await screen.findByRole('button', { name: /close session/i }))
    await user.click(await screen.findByRole('button', { name: /confirm handover/i }))
    const box = await screen.findByTestId('pickup-left-out')
    expect(box.textContent).toContain('1 waybill(s) left out of the handover')
    expect(box.textContent).toContain('6979330902')
    expect(box.textContent).toContain('#4202')
    expect(box.textContent).not.toContain('6979330901')
  })
})
