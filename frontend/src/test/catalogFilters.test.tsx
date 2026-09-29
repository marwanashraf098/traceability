import { test, expect, vi, afterEach } from 'vitest'
import { screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from './renderWithProviders'
import { ProductStatusFilter, productStatusParam, DEFAULT_PRODUCT_STATUSES } from '../components/ui'
import type { ProductStatusOption } from '../components/ui'
import ProductSelectionGrid from '../pages/receiving/ProductSelectionGrid'
import ExchangeVariantPicker from '../pages/exchanges/ExchangeVariantPicker'
import StockTab from '../pages/inventory/StockTab'
import type { Line } from '../pages/Receiving'

// Server-side catalog filters + pagination (activation perf, Part C):
//   cf1 — shared filter: toggles, the last option can't be cleared, all three = no filter
//   cf2 — Stock tab: first request filters Active + Draft; a toggle refetches server-side
//   cf3 — Receiving: first request is one page (limit) with Active + Draft; search goes to the
//         server; "load more" sends the cursor and appends
//   cf4 — Receiving: a session line whose product isn't in the results is loaded by variantIds
//         and shown in the selected summary
//   cf5 — Exchange picker: Active only by default; the toggle drops the status filter

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

function product(id: string, title: string, status = 'active', variantIds = [`${id}-v`]) {
  return {
    id, title, status, imageUrl: null,
    variants: variantIds.map(v => ({ id: v, title: `${title} ${v}`, sku: `SKU-${v}`, price: 100, pieceCounts: {}, committed: 0, available: 1 })),
  }
}

function params(url: string) {
  return new URL(url, 'http://x').searchParams
}

afterEach(() => { vi.unstubAllGlobals() })

test('cf1: filter toggles, keeps at least one, and all three means no status param', async () => {
  const user = userEvent.setup()
  let value: ProductStatusOption[] = [...DEFAULT_PRODUCT_STATUSES]
  const onChange = vi.fn((next: ProductStatusOption[]) => { value = next })
  const { rerender } = renderWithProviders(<ProductStatusFilter value={value} onChange={onChange} />)

  expect(screen.getByTestId('product-status-filter-active').getAttribute('aria-pressed')).toBe('true')
  expect(screen.getByTestId('product-status-filter-archived').getAttribute('aria-pressed')).toBe('false')

  await user.click(screen.getByTestId('product-status-filter-archived'))
  expect(value).toEqual(['active', 'draft', 'archived'])
  expect(productStatusParam(value)).toBeUndefined()

  rerender(<ProductStatusFilter value={['draft']} onChange={onChange} />)
  onChange.mockClear()
  await user.click(screen.getByTestId('product-status-filter-draft'))
  expect(onChange).not.toHaveBeenCalled()
  expect(productStatusParam(['active', 'draft'])).toEqual(['active', 'draft'])
})

test('cf2: Stock tab asks the server for Active + Draft, and a toggle refetches with the new set', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    if (url.includes('/inventory/stock')) return json({ items: [], nextCursor: null })
    return json([])
  }))
  const user = userEvent.setup()
  renderWithProviders(<StockTab />)

  await waitFor(() => expect(calls.some(u => u.includes('/inventory/stock'))).toBe(true))
  const first = calls.find(u => u.includes('/inventory/stock'))!
  expect(params(first).get('status')).toBe('active,draft')

  await user.click(screen.getByTestId('product-status-filter-archived'))
  await waitFor(() => expect(calls.filter(u => u.includes('/inventory/stock')).length).toBe(2))
  expect(params(calls.filter(u => u.includes('/inventory/stock'))[1]).get('status')).toBeNull()
})

test('cf3: Receiving loads one server page, searches on the server, and "load more" appends the next page', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const p = params(url)
    if (p.get('cursor') === 'c1') return json({ products: [product('p3', 'Gamma')], nextCursor: null })
    if (p.get('q') === 'bet') return json({ products: [product('p2', 'Beta')], nextCursor: null })
    return json({ products: [product('p1', 'Alpha'), product('p2', 'Beta')], nextCursor: 'c1' })
  }))
  const user = userEvent.setup()
  renderWithProviders(
    <ProductSelectionGrid sessionId="s1" lines={[]} onRefresh={() => {}} onFinalizeClick={() => {}} finalizing={false} />)

  await screen.findByTestId('product-card-p1')
  const first = params(calls[0])
  expect(first.get('status')).toBe('active,draft')
  expect(first.get('limit')).toBe('48')
  expect(first.get('q')).toBeNull()

  await user.click(screen.getByRole('button', { name: 'Load more' }))
  await screen.findByTestId('product-card-p3')
  expect(params(calls[calls.length - 1]).get('cursor')).toBe('c1')
  expect(screen.getByTestId('product-card-p1')).toBeTruthy()

  fireEvent.change(screen.getByPlaceholderText(/Search products or SKU/i), { target: { value: 'bet' } })
  await waitFor(() => expect(screen.queryByTestId('product-card-p1')).toBeNull())
  expect(params(calls[calls.length - 1]).get('q')).toBe('bet')
  expect(screen.getByTestId('product-card-p2')).toBeTruthy()
})

test('cf4: a session line outside the current results is loaded by variantIds into the selected summary', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    if (params(url).get('variantIds')) return json({ products: [product('p9', 'Old Archived Coat', 'archived', ['v9'])], nextCursor: null })
    return json({ products: [product('p1', 'Alpha')], nextCursor: null })
  }))
  const lines: Line[] = [{ id: 'l1', variant_id: 'v9', variant_title: 'L', sku: 'S9', product_title: 'Old Archived Coat', quantity: 4, piece_count: 0 }]
  renderWithProviders(
    <ProductSelectionGrid sessionId="s1" lines={lines} onRefresh={() => {}} onFinalizeClick={() => {}} finalizing={false} />)

  const row = await screen.findByTestId('summary-row-p9')
  expect(row.textContent).toContain('Old Archived Coat')
  const byVariant = calls.filter(u => params(u).get('variantIds'))
  expect(byVariant).toHaveLength(1)
  expect(params(byVariant[0]).get('variantIds')).toBe('v9')
  expect(params(byVariant[0]).get('status')).toBeNull()
})

test('cf5: the exchange picker asks for Active only; "Show draft & archived" drops the filter', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    return json({ products: [product('p1', 'Alpha')], nextCursor: null })
  }))
  const user = userEvent.setup()
  renderWithProviders(<ExchangeVariantPicker onSelect={() => {}} onClose={() => {}} />)

  await screen.findByTestId('exchange-picker-product-p1')
  expect(params(calls[0]).get('status')).toBe('active')

  await user.click(screen.getByTestId('exchange-picker-show-all').querySelector('button, input, [role="switch"]') as HTMLElement)
  await waitFor(() => expect(calls.length).toBe(2))
  expect(params(calls[1]).get('status')).toBeNull()
})
