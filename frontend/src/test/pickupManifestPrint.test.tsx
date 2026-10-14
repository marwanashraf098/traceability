import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import PickupSessions from '../pages/PickupSessions'

/**
 * Pickup manifest — "Print / Download manifest" used to window.open the API URL in a new tab: no
 * Bearer header, so every merchant got a 401 (and the endpoint returns JSON, not a PDF). Now it
 * fetches the manifest through the authenticated request, renders the shipment list inside the
 * Handover Manifest card and prints the page. COD is money: owner / manager only — never in a
 * worker's manifest.
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, statusText: status < 400 ? 'OK' : 'Server Error',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const SCANS = [
  { shipmentId: 'sh-1', trackingNumber: '7001001', orderNumber: '#1001', codAmount: 1250,
    scannedAt: '2026-10-14T08:01:00Z', scannedByName: 'Ahmed' },
  { shipmentId: 'sh-2', trackingNumber: '7001002', orderNumber: '#1002', codAmount: 980,
    scannedAt: '2026-10-14T08:02:00Z', scannedByName: 'Ahmed' },
]
const SESSION = { id: 'pk-1', sessionStatus: 'closed', scheduledDate: '2026-10-14', scheduledTimeSlot: '10:00 - 13:00',
  scannedCount: 2, openedByName: 'Ahmed', createdAt: '2026-10-14T08:00:00Z', notes: null, closedByName: 'Ahmed',
  closedAt: '2026-10-14T09:00:00Z', scans: [] as unknown[] }
const MANIFEST = { pickupId: 'pk-1', scheduledDate: '2026-10-14', timeSlot: '10:00 - 13:00', closedByName: 'Ahmed',
  closedAt: '2026-10-14 09:00:00+00', packageCount: 2, shipments: SCANS }

let fetchFn: ReturnType<typeof vi.fn>
let manifestReply: () => Promise<unknown>
let printSpy: ReturnType<typeof vi.fn>
let openSpy: ReturnType<typeof vi.fn>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([SESSION])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(SESSION)
  if (url.endsWith('/pickup-sessions/pk-1/manifest') && method === 'GET') return manifestReply()
  return json({})
}

async function openClosedSession() {
  const user = userEvent.setup()
  renderWithProviders(<PickupSessions />)
  await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
  await user.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
  await screen.findByText('Handover Manifest')
  return user
}

const manifestCalls = () => fetchFn.mock.calls.filter(([u]) => String(u).endsWith('/pickup-sessions/pk-1/manifest'))

beforeEach(() => {
  clearAccessToken()
  manifestReply = () => json(MANIFEST)
  fetchFn = vi.fn(backend)
  stubFetchWithShellDefaults(fetchFn)
  printSpy = vi.fn()
  openSpy = vi.fn()
  vi.stubGlobal('print', printSpy)
  vi.spyOn(window, 'open').mockImplementation(openSpy as unknown as typeof window.open)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearAccessToken()
})

describe('Pickup manifest — print the page', () => {
  test('owner: authenticated fetch (no new tab), rows with COD in the manifest card, prints once', async () => {
    setAccessToken(fakeJwt('owner'))
    const user = await openClosedSession()

    await user.click(screen.getByRole('button', { name: 'Print / Download manifest' }))

    const card = await screen.findByTestId('pickup-manifest')
    expect(await within(card).findByText('7001001')).toBeInTheDocument()
    expect(within(card).getByText('7001002')).toBeInTheDocument()
    expect(within(card).getByText('#1001')).toBeInTheDocument()
    expect(within(card).getByRole('columnheader', { name: 'COD' })).toBeInTheDocument()
    expect(within(card).getByText(`${(1250).toLocaleString()} EGP`)).toBeInTheDocument()

    expect(manifestCalls()).toHaveLength(1)
    const headers = manifestCalls()[0][1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe(`Bearer ${fakeJwt('owner')}`)
    expect(openSpy).not.toHaveBeenCalled()
    await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
  })

  test('manager also sees COD in the manifest', async () => {
    setAccessToken(fakeJwt('manager'))
    const user = await openClosedSession()
    await user.click(screen.getByRole('button', { name: 'Print / Download manifest' }))

    const card = await screen.findByTestId('pickup-manifest')
    expect(await within(card).findByText(`${(980).toLocaleString()} EGP`)).toBeInTheDocument()
    await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
  })

  test('worker: the same rows, but no COD column and no amount anywhere in the manifest', async () => {
    setAccessToken(fakeJwt('worker'))
    const user = await openClosedSession()
    await user.click(screen.getByRole('button', { name: 'Print / Download manifest' }))

    const card = await screen.findByTestId('pickup-manifest')
    expect(await within(card).findByText('7001001')).toBeInTheDocument()
    expect(within(card).getByText('#1002')).toBeInTheDocument()
    expect(within(card).queryByRole('columnheader', { name: 'COD' })).not.toBeInTheDocument()
    expect(within(card).queryByText(/EGP/)).not.toBeInTheDocument()
    expect(within(card).queryByText(/1,?250|980/)).not.toBeInTheDocument()
    await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
  })

  test('a failed fetch shows an inline error and never opens a print dialog or a tab', async () => {
    setAccessToken(fakeJwt('owner'))
    manifestReply = () => json({ message: 'boom' }, 500)
    const user = await openClosedSession()
    await user.click(screen.getByRole('button', { name: 'Print / Download manifest' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load the manifest")
    expect(manifestCalls()).toHaveLength(1)
    expect(printSpy).not.toHaveBeenCalled()
    expect(openSpy).not.toHaveBeenCalled()
    // The button is usable again for a retry.
    expect(screen.getByRole('button', { name: 'Print / Download manifest' })).toBeEnabled()
  })

  test('while the manifest loads the button says so and is disabled', async () => {
    setAccessToken(fakeJwt('owner'))
    let release!: () => void
    manifestReply = () => new Promise<void>(r => { release = r }).then(() => json(MANIFEST))
    const user = await openClosedSession()
    await user.click(screen.getByRole('button', { name: 'Print / Download manifest' }))

    expect(await screen.findByRole('button', { name: 'Preparing manifest…' })).toBeDisabled()
    expect(printSpy).not.toHaveBeenCalled()
    release()
    await waitFor(() => expect(printSpy).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: 'Print / Download manifest' })).toBeEnabled()
  })
})
