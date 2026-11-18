import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import Fulfill from '../pages/Fulfill'

/**
 * Exchange reference S1 (2026-11-17) — the post-Complete AWB dialog shows the server's "give it to a manager"
 * words when the scanned waybill is a Bosta exchange whose replacement isn't mapped yet (409 EXCHANGE_NOT_MAPPED),
 * and keeps its old "already linked to another order" text for any other 409.
 */

function json(status: number, data: unknown) {
  return Promise.resolve({ ok: status < 400, status, json: async () => structuredClone(data) })
}

function tokenFor(tenant: string) {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', tenant, role: 'worker' })}.sig`
}

const QUEUE = [
  { id: 'order-1', number: '#EX-Q1', customer_name: 'Nour Hassan', status: 'new', payment_method: 'cod',
    cod_amount: null, total_units: 1, scanned_units: 1, locked_by: null, locked_at: null, is_self_pickup: false },
]

// Fully scanned, no forward leg yet → "Scan Waybill to Link" opens the AWB dialog.
const DETAIL = {
  id: 'order-1', number: '#EX-Q1', customer_name: 'Nour Hassan', customer_phone: null, status: 'picking',
  payment_method: 'cod', cod_amount: null, locked_by: null, is_self_pickup: false, is_exchange: false,
  cancel_requested_at: null, shipment_id: null, tracking_number: null, shipment_has_courier: false,
  items: [{ id: 'item-1', variant_id: 'var-1', sku: 'TSHIRT-S', variant_title: 'Small', product_title: 'Classic Cotton T-Shirt',
            quantity: 1, allocated: 1,
            allocatedPieces: [{ piece_id: 'p1', barcode: 'PC-0000000001', allocation_status: 'active', piece_status: 'reserved' }] }],
}

function makeFetch(linkBody: unknown) {
  return vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/queue')) return json(200, QUEUE)
    if (url.endsWith('/fulfill/order-1/complete')) return json(200, { packedPieces: 1 })
    if (url.endsWith('/fulfill/order-1/link') && opts?.method === 'POST') return json(409, linkBody)
    if (url.endsWith('/fulfill/order-1')) return json(200, DETAIL)
    if (url.includes('/scan-helpers/')) return json(200, { items: [] })
    return json(200, {})
  })
}

async function scanAwb(awb: string) {
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#EX-Q1'))
  await user.click(await screen.findByTestId('btn-scan-to-link'))
  const input = await screen.findByPlaceholderText('Scan or type tracking number…')
  await user.type(input, awb + '{Enter}')
}

describe('Post-Complete AWB dialog — exchange waybill not mapped', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    setAccessToken(tokenFor('11111111-2222-3333-4444-555555555555'))
  })
  afterEach(() => {
    clearAccessToken()
    resetCapabilitiesCache()
    vi.unstubAllGlobals()
  })

  test('409 EXCHANGE_NOT_MAPPED → "give it to a manager"', async () => {
    stubFetchWithShellDefaults(makeFetch({
      code: 'EXCHANGE_NOT_MAPPED',
      message_en: "This exchange waybill isn't mapped to a replacement order yet. Give it to a manager.",
      message_ar: 'بوليصة الاستبدال هذه لم تُربط بطلب بديل بعد. سلّمها للمدير.',
    }))
    await scanAwb('7777123456')

    expect(await screen.findByText("This exchange waybill isn't mapped to a replacement order yet. Give it to a manager."))
      .toBeInTheDocument()
    expect(screen.queryByText('This waybill is already linked to another order')).toBeNull()
    expect(screen.queryByText('Order complete')).toBeNull()
  })

  test('any other 409 → the old "already linked" text', async () => {
    stubFetchWithShellDefaults(makeFetch({ error: 'conflict' }))
    await scanAwb('7777123457')

    expect(await screen.findByText('This waybill is already linked to another order')).toBeInTheDocument()
    expect(screen.queryByText(/Give it to a manager/)).toBeNull()
  })
})
