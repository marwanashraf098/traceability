import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import type { PackOrderCard, PackSessionView } from '../api'

// Deleted-in-Bosta slice 4: the open waybill was confirmed deleted in Bosta while the packer was on it. The next
// piece scan answers WAYBILL_DELETED_IN_BOSTA — the server already set the order aside — so the screen drops the
// order card and says why, instead of leaving a dead order open with a red line under it.

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const ORDER: PackOrderCard = {
  id: 'ord-1', number: '#72582', customer_name: 'Salma', payment_method: 'cod', cod_amount: '650.00',
  tracking_number: '4039539931', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: 4, batchPrintedAt: null,
  items: [{ id: 'li-1', variant_id: 'v-1', sku: 'AB-BLK-M', variant_title: 'Black / M', product_title: 'Abaya',
    imageUrl: null, quantity: 2, allocated: 1,
    allocatedPieces: [{ piece_id: 'pc-1', barcode: 'PC-1', allocation_status: 'active', piece_status: 'reserved' }] }],
}

const VIEW: PackSessionView = {
  id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-19T07:05:00Z', workerName: 'Mona',
  counters: { packed: 3, setAside: 0, rejected: 0, left: 5 }, recent: [], openOrder: ORDER,
}

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  stubFetchWithShellDefaults(vi.fn((url: string) => {
    if (url.endsWith('/sess-1/orders/ord-1/scan')) {
      return json({ status: 'rejected', order: null, packed: null, failCode: null, failMessage: null,
        scan: { success: false, code: 'WAYBILL_DELETED_IN_BOSTA',
          message: 'This waybill was deleted in Bosta. The order was set aside — put the waybill to one side and give it to a manager.' } })
    }
    if (url.endsWith('/pack-sessions/sess-1')) return json({ ...VIEW, openOrder: null, counters: { ...VIEW.counters, setAside: 1 } })
    if (url.endsWith('/me')) return json({ name: 'Mona', email: null, role: 'worker' })
    return json({})
  }))
})
afterEach(() => { vi.unstubAllGlobals() })

describe('pack session — the open waybill was deleted in Bosta', () => {
  test('the order card goes away and the screen says the order was set aside, and why', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    expect(await screen.findByText('#72582')).toBeTruthy()

    await user.type(screen.getByRole('textbox'), 'P004471{Enter}')

    const card = await screen.findByTestId('session-rejected')
    expect(within(card).getByTestId('rejected-title')).toHaveTextContent('Waybill deleted in Bosta')
    expect(within(card).getByTestId('rejected-deleted-in-bosta'))
      .toHaveTextContent('Order #72582 was set aside: its waybill was deleted in Bosta.')
    expect(screen.queryByText('Abaya')).not.toBeInTheDocument()
  })
})
