import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'

/**
 * Exchange reference S3b (2026-11-19) — a multi-item exchange opens the mapping screen with rows prefilled from Bosta
 * (GET /exchanges/{id} → prefill: one row per label line, the variant suggested by exact SKU; else Bosta's count with
 * its text). A person still confirms every outbound row before anything is mapped.
 */

const CATALOG = { products: [{ id: 'p-jeans', title: 'Relaxed Fit Denim Jeans', status: 'active', imageUrl: null, variants: [
  { id: 'v-raw-32', title: 'Raw / 32', sku: 'BROEK-Raw-Denim-RFD-2', price: 900 },
  { id: 'v-blk-32', title: 'Washed Black / 32', sku: 'BRK-JEANS-BLK-RF-002', price: 900 }] }] }

const EXCHANGE = {
  id: 'exc-1', tracking_number: '7756985263', status: 'needs_mapping', matched_order_id: null, match_method: null,
  matched_at: null,
  outbound_description: 'x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // x1 Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2',
  inbound_description: 'x1 Washed Black Relaxed Fit Denim Jeans - 34 - SKU: BRK-JEANS-BLK-RF-003 // x1 Raw Denim Relaxed Fit Jeans - 34 - SKU: BROEK-Raw-Denim-RFD-3',
  inbound_description_ar: null, cod: 0, goods_value: 1800, auto_matched: false, outbound_items_count: 2, inbound_items_count: 2,
  customer_name: 'Omar', customer_phone: '+201012345678',
  prefill: {
    labelParsed: true,
    outbound: [
      { variantId: 'v-blk-32', productTitle: 'Relaxed Fit Denim Jeans', variantTitle: 'Washed Black / 32', sku: 'BRK-JEANS-BLK-RF-002',
        quantity: 1, descriptionHint: 'Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002' },
      { variantId: null, productTitle: null, variantTitle: null, sku: 'BROEK-Raw-Denim-RFD-2',
        quantity: 1, descriptionHint: 'Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2' },
    ],
    inbound: [
      { variantId: null, productTitle: null, variantTitle: null, sku: null, quantity: 2, descriptionHint: 'two pairs back' },
    ],
  },
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

let mapItemsBody: unknown = null

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/api/v1/catalog')) return fakeResponse(CATALOG)
  if (url.endsWith('/outbound-candidates')) return fakeResponse({ classification: 'NONE', committedVariantId: null, candidates: [] })
  if (url.endsWith('/exchanges/exc-1/map-items') && method === 'POST') {
    mapItemsBody = JSON.parse(opts.body as string)
    return fakeResponse({ exchangeId: 'exc-1', orderId: 'order-9', status: 'mapped' })
  }
  if (url.endsWith('/exchanges/exc-1')) return fakeResponse(EXCHANGE)
  return fakeResponse({})
}

beforeEach(() => { mapItemsBody = null; stubFetchWithShellDefaults(vi.fn(backendFetch)) })

describe('Exchange mapping — prefill (S3b)', () => {
  test('p1 rows come from the prefill; the suggested one is selected; every outbound row must be confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Routes><Route path="/exchanges/:id" element={<ExchangeMapping />} /></Routes>,
      { initialEntries: ['/exchanges/exc-1'] })
    const out0 = await screen.findByTestId('exchange-row-outbound-0')
    const out1 = screen.getByTestId('exchange-row-outbound-1')
    expect(within(out0).getByText('Washed Black / 32')).toBeInTheDocument()
    expect(within(out0).getByTestId('exchange-row-outbound-0-hint')).toHaveValue('Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002')
    expect(within(out1).getByTestId('exchange-row-outbound-1-hint')).toHaveValue('Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2')
    expect(within(screen.getByTestId('exchange-row-inbound-0')).getByTestId('exchange-row-inbound-0-qty')).toHaveTextContent('2')

    const confirm = screen.getByRole('button', { name: /Confirm mapping/i })
    await user.click(screen.getByTestId('exchange-row-inbound-0-unknown'))
    expect(confirm).toBeDisabled()

    await user.click(within(out1).getByRole('button', { name: /Select variant/i }))
    await screen.findByTestId('exchange-variant-picker')
    await user.click(screen.getByTestId('exchange-picker-product-p-jeans'))
    await screen.findByTestId('exchange-picker-variant-list')
    await user.click(screen.getByTestId('exchange-picker-variant-v-raw-32'))
    expect(confirm).not.toBeDisabled()

    await user.click(confirm)
    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapItemsBody).toEqual({
      outbound: [
        { variantId: 'v-blk-32', quantity: 1, descriptionHint: 'Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002' },
        { variantId: 'v-raw-32', quantity: 1, descriptionHint: 'Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2' },
      ],
      inbound: [{ variantId: null, quantity: 2, descriptionHint: 'two pairs back' }],
    })
  })
})
