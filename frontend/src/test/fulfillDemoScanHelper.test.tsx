import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { DEMO_TENANT_ID } from '../demoConstants'
import { resetCapabilitiesCache } from '../capabilities'
import Fulfill from '../pages/Fulfill'

/**
 * Click-to-scan helper on the pick screen. A demo visitor (or a review tenant) has no physical
 * labels; for a tenant whose /me says scanHelpers (S7), each unfinished line lists available
 * barcodes with a Scan button that goes through PickScreen's real handleScan
 * (POST /fulfill/{id}/scan). Any other tenant: nothing renders and /scan-helpers is never called.
 */

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

/** Unsigned JWT-shaped token — the frontend only base64-decodes the payload. */
function tokenFor(tenant: string) {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', tenant, role: 'owner' })}.sig`
}

const QUEUE_ORDER = {
  id: 'order-1', number: '#DEMO-Q1', customer_name: 'Demo Customer', status: 'new',
  payment_method: 'cod', cod_amount: null, total_units: 1, scanned_units: 0,
  locked_by: null, locked_at: null, is_self_pickup: false,
}

function detail(allocated: number) {
  return {
    id: 'order-1', number: '#DEMO-Q1', customer_name: 'Demo Customer', customer_phone: null,
    status: 'new', payment_method: 'cod', cod_amount: null, locked_by: null,
    is_self_pickup: false, is_exchange: false, cancel_requested_at: null,
    shipment_id: 'ship-1', tracking_number: '999000000001', shipment_has_courier: false,
    items: [{
      id: 'item-1', variant_id: 'var-1', sku: 'DEMO-SKU-1', variant_title: 'M',
      product_title: 'Demo Tee', quantity: 1, allocated,
      allocatedPieces: allocated
        ? [{ piece_id: 'p1', barcode: 'TRC-DEMO-0000000001', allocation_status: 'active', piece_status: 'reserved' }]
        : [],
    }],
  }
}

function makeFetch() {
  let scanned = false
  return vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/fulfill/queue')) return jsonOk([QUEUE_ORDER])
    if (url.endsWith('/fulfill/order-1/scan') && opts?.method === 'POST') {
      scanned = true
      return jsonOk({ success: true, code: 'OK', message: null, pieceId: 'p1', barcode: 'TRC-DEMO-0000000001',
                      allocatedCount: 1, requiredQuantity: 1, allComplete: true })
    }
    if (url.endsWith('/fulfill/order-1')) return jsonOk(detail(scanned ? 1 : 0))
    if (url.includes('/scan-helpers/pieces?variantId=var-1')) {
      return jsonOk({ items: scanned
        ? [{ code: 'TRC-DEMO-0000000002', label: '2' }]
        : [{ code: 'TRC-DEMO-0000000001', label: '1' }, { code: 'TRC-DEMO-0000000002', label: '2' }] })
    }
    return jsonOk({})
  })
}

async function openPickScreen() {
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#DEMO-Q1'))
  await screen.findByTestId('fulfill-pick')
  return user
}

describe('Pick screen — demo first-scan helper', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => {
    clearAccessToken()
    resetCapabilitiesCache()
    vi.unstubAllGlobals()
  })

  test('demo tenant → barcodes render; Scan posts through the real scan handler; helper leaves once the line is done', async () => {
    setAccessToken(tokenFor(DEMO_TENANT_ID))
    const fetchFn = makeFetch()
    stubFetchWithShellDefaults(fetchFn, { me: { name: 'Demo', email: null, role: 'owner', scanHelpers: true, demoMode: true } })
    const user = await openPickScreen()

    expect(await screen.findByTestId('scan-helper-pieces')).toBeInTheDocument()
    const buttons = screen.getAllByRole('button', { name: /^Scan TRC-DEMO-/ })
    expect(buttons).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Scan TRC-DEMO-0000000001' }))

    const scanCall = fetchFn.mock.calls.find(([u, o]) =>
      String(u).endsWith('/fulfill/order-1/scan') && (o as RequestInit | undefined)?.method === 'POST')
    expect(scanCall).toBeDefined()
    expect(JSON.parse(String((scanCall![1] as RequestInit).body))).toEqual({ barcode: 'TRC-DEMO-0000000001' })

    // Real handler ran: the order reloaded, the line is 1/1, the helper is gone.
    await waitFor(() => expect(screen.queryByTestId('scan-helper-pieces')).toBeNull())
    expect(screen.getByText('1/1')).toBeInTheDocument()
  })

  test('real merchant (/me scanHelpers false) → no helper and /scan-helpers is never requested', async () => {
    setAccessToken(tokenFor('11111111-2222-3333-4444-555555555555'))
    const fetchFn = makeFetch()
    stubFetchWithShellDefaults(fetchFn)
    await openPickScreen()

    await screen.findByText('Demo Tee')
    expect(screen.queryByTestId('scan-helper-pieces')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Scan TRC-/ })).toBeNull()
    expect(fetchFn.mock.calls.some(([u]) => String(u).includes('/scan-helpers'))).toBe(false)
  })
})
