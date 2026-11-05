import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import PickupSessions from '../pages/PickupSessions'

/**
 * Pickup scan of a parcel Bosta already has (2026-11-05, V207): its own status (24 / 30 / 41, or 21) moved
 * the pieces to with_courier before the scan. The server answers ALREADY_WITH_COURIER — a calm success
 * banner, not an error, and the parcel is not added to the session list.
 */

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, statusText: 'OK',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data),
})

const LIVE = { shipmentId: 'sh-1', trackingNumber: '6979330901', orderNumber: '#4201', codAmount: 450,
  scannedAt: '2026-11-05T08:01:00Z', scannedByName: 'Mona' }
const session = { id: 'pk-1', scheduledDate: '2026-11-05', scheduledTimeSlot: '10:00 - 13:00', openedByName: 'Mona',
  createdAt: '2026-11-05T08:00:00Z', notes: null, sessionStatus: 'open', scannedCount: 1, closedByName: null,
  closedAt: null, scans: [LIVE] }

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([{ ...session }])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(session)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') {
    return json({ outcome: 'ALREADY_WITH_COURIER', entry: { shipmentId: 'sh-9', trackingNumber: '7492722098',
      orderNumber: '#4209', codAmount: 300, scannedAt: null, scannedByName: null } })
  }
  return json({})
}

describe('Pickup session — parcel already with the courier', () => {
  beforeEach(() => {
    clearAccessToken()
    setAccessToken(`h.${btoa(JSON.stringify({ role: 'worker' }))}.s`)
    stubFetchWithShellDefaults(vi.fn(backend))
  })
  afterEach(() => { vi.unstubAllGlobals(); clearAccessToken() })

  test('shows a success banner and does not add the parcel to the session', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PickupSessions />)
    await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
    await user.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
    const input = await waitFor(() => {
      const el = document.querySelector<HTMLInputElement>('input')
      expect(el).toBeTruthy()
      return el!
    })

    await user.type(input, '7492722098{Enter}')

    const banner = await screen.findByTestId('pickup-scan-feedback')
    expect(banner.textContent).toContain('Already with the courier — Bosta has this parcel, nothing to do.')
    expect(banner.className).toContain('text-success')
    expect(banner.className).not.toContain('text-danger')
    expect(screen.queryByText('#4209')).toBeNull()
    expect(screen.getByText('#4201')).toBeTruthy()
  })
})
