import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'
import PickupSessions from '../pages/PickupSessions'

// Unpack-order S2 — "Packed, not picked up": a section on the waybill page, the "Packed" tab on the queue page
// (loaded only when opened — the queue page's own requests are unchanged), the open pickup session link, the
// "edited in Shopify" chip and the COD reminder (server-decided), EN and AR; /pickups?session= opens a session.

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const ROWS = [
  { orderId: 'o1', orderNumber: '#1047', customerName: 'Youssef Adel', status: 'awaiting_pickup', selfPickup: false,
    trackingNumber: '74821655', courier: 'bosta', carrierName: null, packedAt: '2026-10-31T09:12:00Z',
    packedByName: 'Ahmed', pieces: 2, pickupSessionId: 'pu-1', pickupDate: '2026-10-31', pickupTimeSlot: '10-12',
    editConflict: true, codMismatch: true, bostaCod: 1250, orderCod: 900 },
  { orderId: 'o2', orderNumber: '#1048', customerName: 'Salma Nabil', status: 'self_pickup_pending', selfPickup: true,
    trackingNumber: null, courier: null, carrierName: null, packedAt: '2026-10-31T08:00:00Z',
    packedByName: null, pieces: 1, pickupSessionId: null, pickupDate: null, pickupTimeSlot: null,
    editConflict: false, codMismatch: false, bostaCod: null, orderCod: null },
]

let mode: 'order_queue' | 'waybill_scan'
let calls: string[]

function backend(url: string) {
  calls.push(url)
  if (url.endsWith('/fulfill/mode')) return json({ mode })
  if (url.endsWith('/fulfill/queue')) return json([])
  if (url.includes('/fulfill/queue/awaiting-waybill-count')) return json({ count: 0 })
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 0, openSessionId: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json([])
  if (url.endsWith('/fulfill/printed-not-packed')) return json([])
  if (url.endsWith('/fulfill/packed')) return json(ROWS)
  if (url.endsWith('/pickup-sessions')) return json([])
  if (url.endsWith('/pickup-sessions/pu-1')) {
    return json({ id: 'pu-1', sessionStatus: 'open', scheduledDate: '2026-10-31', scheduledTimeSlot: '10-12',
      notes: null, openedByName: 'Mona', scans: [] })
  }
  return json({})
}

beforeEach(async () => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  await i18n.changeLanguage('en')
  mode = 'waybill_scan'
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(async () => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  await i18n.changeLanguage('en')
})

function renderFulfill(path = '/fulfill') {
  return renderWithProviders(
    <I18nextProvider i18n={i18n}>
      <Routes>
        <Route path="/fulfill" element={<FulfillRoute />} />
        <Route path="/pickups" element={<PickupSessions />} />
      </Routes>
    </I18nextProvider>,
    { initialEntries: [path] },
  )
}

describe('packed, not picked up', () => {
  test('waybill page: rows, pickup-session link, edited chip, COD reminder', async () => {
    renderFulfill()
    const section = await screen.findByTestId('packed-orders')
    const rows = await within(section).findAllByTestId('packed-row')
    expect(rows).toHaveLength(2)
    expect(within(section).getByText('2 orders')).toBeInTheDocument()

    const first = rows[0]
    expect(first).toHaveTextContent('#1047')
    expect(first).toHaveTextContent('74821655')
    expect(first).toHaveTextContent('2 pieces')
    expect(first).toHaveTextContent('Packed by Ahmed')
    expect(within(first).getByTestId('packed-status-awaiting_pickup')).toHaveTextContent('Waiting for courier')
    expect(within(first).getByTestId('packed-edited')).toHaveTextContent('Edited in Shopify')
    expect(within(first).getByTestId('packed-in-pickup')).toHaveAttribute('href', '/pickups?session=pu-1')
    expect(within(first).getByTestId('packed-cod'))
      .toHaveTextContent('Bosta still has 1,250 EGP, the order is now 900 EGP — update the COD in Bosta.')

    const second = rows[1]
    expect(within(second).getByTestId('packed-status-self_pickup_pending')).toHaveTextContent('Waiting for customer')
    expect(within(second).queryByTestId('packed-cod')).toBeNull()
    expect(within(second).queryByTestId('packed-in-pickup')).toBeNull()
  })

  test('the pickup-session chip opens that session on Pickups', async () => {
    const user = userEvent.setup()
    renderFulfill()
    const chip = await screen.findByTestId('packed-in-pickup')
    await user.click(chip)
    await waitFor(() => expect(calls.some(u => u.endsWith('/pickup-sessions/pu-1'))).toBe(true))
  })

  test('queue page: the Packed tab loads the list only when opened', async () => {
    mode = 'order_queue'
    const user = userEvent.setup()
    renderFulfill()
    await screen.findByTestId('fulfill-queue')
    expect(screen.getByRole('button', { name: 'To pack' })).toBeInTheDocument()
    expect(calls.some(u => u.endsWith('/fulfill/packed'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Packed' }))
    expect(await screen.findAllByTestId('packed-row')).toHaveLength(2)
    expect(calls.filter(u => u.endsWith('/fulfill/packed'))).toHaveLength(1)
  })

  test('queue page: ?tab=packed&order= opens the tab and marks the row', async () => {
    mode = 'order_queue'
    renderFulfill('/fulfill?tab=packed&order=o2')
    const rows = await screen.findAllByTestId('packed-row')
    expect(rows[1].className).toContain('bg-trace-blue')
    expect(rows[0].className).not.toContain('bg-trace-blue')
  })

  test('Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderFulfill()
    const section = await screen.findByTestId('packed-orders')
    expect(within(section).getByText('مغلّفة ولم تُستلم')).toBeInTheDocument()
    const first = (await within(section).findAllByTestId('packed-row'))[0]
    expect(within(first).getByTestId('packed-status-awaiting_pickup')).toHaveTextContent('في انتظار شركة الشحن')
    expect(within(first).getByTestId('packed-cod')).toHaveTextContent('حدّث مبلغ التحصيل في Bosta')
  })
})
