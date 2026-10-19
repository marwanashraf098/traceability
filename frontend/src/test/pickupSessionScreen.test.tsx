import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken, getAccessToken } from '../auth'
import PickupSessions from '../pages/PickupSessions'

/**
 * Pickup session screen.
 *  - Deleted-in-Bosta slice 3: a LEG_ENDED scan says "cancelled or deleted in Bosta — don't hand it to the
 *    courier"; a close that left waybills out lists them.
 *  - Nuvira (2026-10): a worker's session sits open for hours; a Close after the 15-minute access token
 *    expired must refresh the token and close — the old page helper logged the worker out instead. A close
 *    that fails says so inside the confirm dialog.
 */

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
/** The token the server accepts; any other Bearer gets a 401 (an expired access token). */
let validToken: string
let closeStatus: number

const bearer = (opts: RequestInit) => (opts.headers as Record<string, string> | undefined)?.Authorization

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/auth/refresh') && method === 'POST') {
    validToken = 'h.' + btoa(JSON.stringify({ role: 'worker', v: 2 })) + '.s'
    return json({ accessToken: validToken })
  }
  if (bearer(opts) !== `Bearer ${validToken}`) return json({ code: 'UNAUTHORIZED' }, 401)
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([{ ...base, sessionStatus: session.sessionStatus, scannedCount: 2 }])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(session)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') return json({ outcome: 'LEG_ENDED', entry: {} })
  if (url.endsWith('/pickup-sessions/pk-1/close') && method === 'POST') {
    if (closeStatus !== 200) return json({ code: 'INTERNAL_ERROR' }, closeStatus)
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
    validToken = `h.${btoa(JSON.stringify({ role: 'worker' }))}.s`
    closeStatus = 200
    setAccessToken(validToken)
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

  test('a Close after the access token expired refreshes the token and closes (no logout)', async () => {
    const user = await openSession()
    await user.click(await screen.findByRole('button', { name: /close session/i }))
    // The worker waited for the courier: the 15-minute access token has expired.
    validToken = 'h.' + btoa(JSON.stringify({ role: 'worker', expiredBefore: true })) + '.s-server-side'
    await user.click(await screen.findByRole('button', { name: /confirm handover/i }))

    expect(await screen.findByTestId('pickup-left-out')).toBeTruthy()
    expect(getAccessToken()).toBe(validToken)
    const calls = fetchFn.mock.calls.map(([u, o]) => `${(o?.method ?? 'GET').toUpperCase()} ${String(u)}`)
    expect(calls.filter(c => c.endsWith('/auth/refresh'))).toHaveLength(1)
    expect(calls.filter(c => c.endsWith('/pickup-sessions/pk-1/close'))).toHaveLength(2)   // 401, then the retry
    expect(screen.queryByTestId('pickup-close-error')).toBeNull()
  })

  test('a Close that fails says so inside the confirm dialog', async () => {
    closeStatus = 500
    const user = await openSession()
    await user.click(await screen.findByRole('button', { name: /close session/i }))
    await user.click(await screen.findByRole('button', { name: /confirm handover/i }))
    const err = await screen.findByTestId('pickup-close-error')
    expect(err.textContent).toContain("Couldn't close the session")
    // Inside the dialog (next to its buttons), not on the page behind it.
    const dialog = screen.getByRole('button', { name: /confirm handover/i }).closest<HTMLElement>('.shadow-elevated')!
    expect(within(dialog).getByTestId('pickup-close-error')).toBe(err)
  })
})
