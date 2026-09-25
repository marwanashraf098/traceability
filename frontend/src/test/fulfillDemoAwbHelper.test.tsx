import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { DEMO_TENANT_ID } from '../demoConstants'
import Fulfill from '../pages/Fulfill'

/**
 * Demo-only AWB helper in the post-Complete verify-scan dialog. A demo visitor has no
 * physical waybill; for the demo tenant only, the dialog offers THIS order's own linked
 * forward tracking number with "Use this AWB", which submits through the dialog's real
 * handleLink (POST /fulfill/{id}/link) — same path as a typed/scanned AWB, no skip.
 * Non-demo tenants: the dialog is unchanged.
 */

function jsonOk(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: async () => structuredClone(data) })
}

function tokenFor(tenant: string) {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', tenant, role: 'owner' })}.sig`
}

const OWN_AWB = '999000000001'

const QUEUE = [
  { id: 'order-1', number: '#DEMO-Q1', customer_name: 'Nour Hassan', status: 'new', payment_method: 'cod',
    cod_amount: null, total_units: 1, scanned_units: 1, locked_by: null, locked_at: null, is_self_pickup: false },
  // A second order with a different AWB — must never appear in order-1's dialog.
  { id: 'order-2', number: '#DEMO-Q2', customer_name: 'Ahmed Fathy', status: 'new', payment_method: 'cod',
    cod_amount: null, total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false },
]

// Fully scanned, linked forward shipment, demo has no courier account → Complete is shown without printing.
const DETAIL = {
  id: 'order-1', number: '#DEMO-Q1', customer_name: 'Nour Hassan', customer_phone: null, status: 'picking',
  payment_method: 'cod', cod_amount: null, locked_by: null, is_self_pickup: false, is_exchange: false,
  cancel_requested_at: null, shipment_id: 'ship-1', tracking_number: OWN_AWB, shipment_has_courier: false,
  items: [{ id: 'item-1', variant_id: 'var-1', sku: 'TSHIRT-S', variant_title: 'Small', product_title: 'Classic Cotton T-Shirt',
            quantity: 1, allocated: 1,
            allocatedPieces: [{ piece_id: 'p1', barcode: 'PC-0000000001', allocation_status: 'active', piece_status: 'reserved' }] }],
}

function makeFetch() {
  return vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/queue')) return jsonOk(QUEUE)
    if (url.endsWith('/fulfill/order-1/complete')) return jsonOk({ packedPieces: 1 })
    if (url.endsWith('/fulfill/order-1/link') && opts?.method === 'POST') {
      return jsonOk({ shipmentId: 'ship-1', trackingNumber: OWN_AWB, linkedPieces: 1, orderStatus: 'awaiting_pickup' })
    }
    if (url.endsWith('/fulfill/order-1')) return jsonOk(DETAIL)
    if (url.includes('/inventory/pieces?')) return jsonOk({ items: [], nextCursor: null })
    return jsonOk({})
  })
}

async function completeOrder1() {
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#DEMO-Q1'))
  await user.click(await screen.findByRole('button', { name: /Complete/ }))
  await screen.findByText('Scan AWB Barcode')
  return user
}

describe('Post-Complete AWB dialog — demo AWB helper', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => {
    clearAccessToken()
    vi.unstubAllGlobals()
  })

  test('demo tenant → shows only this order\'s AWB; "Use this AWB" submits via the real link handler → Order complete', async () => {
    setAccessToken(tokenFor(DEMO_TENANT_ID))
    const fetchFn = makeFetch()
    stubFetchWithShellDefaults(fetchFn)
    const user = await completeOrder1()

    const helper = await screen.findByTestId('demo-awb-helper')
    expect(helper).toHaveTextContent(OWN_AWB)
    expect(helper).not.toHaveTextContent('#DEMO-Q2')
    // Still mandatory — no skip/bypass control was added.
    expect(screen.getByText('Mandatory — this step is required.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /skip/i })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Use this AWB' }))

    const linkCalls = fetchFn.mock.calls.filter(([u, o]) =>
      String(u).endsWith('/fulfill/order-1/link') && (o as RequestInit | undefined)?.method === 'POST')
    expect(linkCalls).toHaveLength(1)
    expect(JSON.parse(String((linkCalls[0][1] as RequestInit).body))).toEqual({ trackingNumber: OWN_AWB })
    expect(await screen.findByText('Order complete')).toBeInTheDocument()
  })

  test('non-demo tenant → the dialog has no AWB helper', async () => {
    setAccessToken(tokenFor('11111111-2222-3333-4444-555555555555'))
    stubFetchWithShellDefaults(makeFetch())
    await completeOrder1()

    await waitFor(() => expect(screen.getByText('Mandatory — this step is required.')).toBeInTheDocument())
    expect(screen.queryByTestId('demo-awb-helper')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Use this AWB' })).toBeNull()
    expect(screen.queryByText(OWN_AWB)).toBeNull()
  })
})
