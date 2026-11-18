import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangeRefundDrawer from '../pages/exchangesRefunds/ExchangeRefundDrawer'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'
import i18n from '../i18n'

/**
 * Exchange reference S2 (2026-11-18) — the exchange drawer's "Link to order" (replaces the raw order-id box) and
 * "Map replacement"; ExchangeMapping reads the exchange by id (an exchange older than the newest 100 loads).
 */

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 404 ? 'Not Found' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

const EXCHANGE = (status: string, matched: string | null = null) => ({
  id: 'exc-1', tracking_number: '58291044', status, matched_order_id: matched, match_method: matched ? 'reference' : null,
  matched_at: null, outbound_description: 'Linen dress L', inbound_description: 'Linen dress M',
  inbound_description_ar: null, cod: 0, goods_value: 900, auto_matched: false,
  outbound_items_count: 1, inbound_items_count: 1, customer_name: 'Mona Adel', customer_phone: '+201005557788',
})

const ORDER_A = {
  id: 'ord-a', number: '#OD2394', placedAt: '2026-10-12T10:00:00Z', customerName: 'Mona Adel',
  items: [
    { productTitle: 'Linen dress', variantTitle: 'M / Blue', sku: 'LD-M', quantity: 1 },
    { productTitle: 'Silk scarf', variantTitle: 'Rose', sku: 'SS-R', quantity: 2 },
    { productTitle: 'Belt', variantTitle: null, sku: null, quantity: 1 },
  ],
}
const ORDER_B = { id: 'ord-b', number: '#OD2401', placedAt: '2026-10-20T10:00:00Z', customerName: 'Mona Adel', items: [] }

let searches: string[] = []
let attachCalls: unknown[] = []
let importCalls: unknown[] = []
let exchangeStatus = 'needs_mapping'
let searchAnswer: (q: string) => unknown = () => ({ query: '', suggested: true, orders: [], importOption: null })
let importAnswer: (body: { number: string; link: boolean }) => unknown = () => ({})

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/exchanges/exc-1/order-search')) {
    const q = new URL(url, 'http://x').searchParams.get('q') ?? ''
    searches.push(q)
    return fakeResponse(searchAnswer(q))
  }
  if (url.endsWith('/exchanges/exc-1/attach') && method === 'POST') {
    attachCalls.push(JSON.parse(opts.body as string))
    return fakeResponse({ exchangeId: 'exc-1', matchedOrderId: 'ord-a', status: exchangeStatus })
  }
  if (url.endsWith('/exchanges/exc-1/import-parent') && method === 'POST') {
    const body = JSON.parse(opts.body as string)
    importCalls.push(body)
    return fakeResponse(importAnswer(body))
  }
  if (url.endsWith('/exchanges/exc-1') && method === 'GET') return fakeResponse(EXCHANGE(exchangeStatus))
  return fakeResponse({})
}

const ROW = {
  kind: 'exchange' as const, sourceId: 'exc-1', trackingNumber: '58291044', orderNumber: null,
  customerName: 'Mona Adel',
}

function renderDrawer(onChanged = vi.fn()) {
  renderWithProviders(<ExchangeRefundDrawer row={ROW} onClose={() => {}} onChanged={onChanged} />)
  return onChanged
}

beforeEach(() => {
  searches = []; attachCalls = []; importCalls = []
  exchangeStatus = 'needs_mapping'
  searchAnswer = q => q === ''
    ? { query: '', suggested: true, orders: [ORDER_B, ORDER_A], importOption: null }
    : { query: q, suggested: false, orders: [], importOption: null }
  stubFetchWithShellDefaults(vi.fn(backendFetch))
})

describe('Exchange drawer — Link to order (S2)', () => {
  test('l1 suggestions before typing; no raw order-id box', async () => {
    renderDrawer()
    expect(await screen.findByTestId('exchange-order-ord-a')).toBeInTheDocument()
    expect(searches).toEqual([''])
    expect(screen.getByText("Suggested — same phone as the courier's customer")).toBeInTheDocument()
    const row = screen.getByTestId('exchange-order-ord-a')
    expect(within(row).getByText('#OD2394')).toBeInTheDocument()
    expect(within(row).getByText(/Linen dress · M \/ Blue ×1/)).toBeInTheDocument()
    expect(within(row).getByText('+1 more item')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Order ID')).toBeNull()
    expect(screen.queryByTestId('attach-order-id-input')).toBeNull()
  })

  test('l2 search → Link → confirm shows every item → POST /attach with that order', async () => {
    searchAnswer = q => q === ''
      ? { query: '', suggested: true, orders: [], importOption: null }
      : { query: q, suggested: false, orders: q === '#OD2394' ? [ORDER_A] : [], importOption: null }
    const onChanged = renderDrawer()
    expect(await screen.findByText('No suggestions — search above.')).toBeInTheDocument()

    await userEvent.type(screen.getByTestId('exchange-order-search'), '#OD2394')
    const row = await screen.findByTestId('exchange-order-ord-a')
    await userEvent.click(within(row).getByRole('button', { name: 'Link' }))

    const confirm = await screen.findByTestId('exchange-link-confirm')
    const items = within(confirm).getByTestId('exchange-link-confirm-items')
    expect(within(items).getAllByRole('listitem')).toHaveLength(3)
    expect(within(items).getByText('Belt')).toBeInTheDocument()
    expect(within(confirm).getByText("You'll still need to map the replacement item.")).toBeInTheDocument()
    expect(attachCalls).toEqual([])

    await userEvent.click(within(confirm).getByRole('button', { name: 'Link order' }))
    await waitFor(() => expect(attachCalls).toEqual([{ orderId: 'ord-a' }]))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  test('l3 Back from confirm links nothing', async () => {
    renderDrawer()
    const row = await screen.findByTestId('exchange-order-ord-a')
    await userEvent.click(within(row).getByRole('button', { name: 'Link' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Back' }))
    expect(await screen.findByTestId('exchange-order-ord-a')).toBeInTheDocument()
    expect(attachCalls).toEqual([])
  })

  test('l4 Import from Shopify → items → confirm → import-parent link=true', async () => {
    searchAnswer = q => q === ''
      ? { query: '', suggested: true, orders: [], importOption: null }
      : { query: q, suggested: false, orders: [], importOption: { number: '5126' } }
    importAnswer = b => b.link
      ? { status: 'LINKED', reason: 'REUSED', number: '5126', items: [], exchangeStatus: 'needs_mapping' }
      : { status: 'FOUND', reason: 'IMPORTED', number: '5126', items: [ORDER_A.items[0]], exchangeStatus: null }
    const onChanged = renderDrawer()
    await screen.findByText('No suggestions — search above.')
    await userEvent.type(screen.getByTestId('exchange-order-search'), 'wjwy1a-ia:#5126')
    const importRow = await screen.findByTestId('exchange-import-row')
    expect(within(importRow).getByText('#5126')).toBeInTheDocument()
    await userEvent.click(within(importRow).getByRole('button', { name: 'Import from Shopify' }))

    const confirm = await screen.findByTestId('exchange-link-confirm')
    expect(within(confirm).getByText('Imported from Shopify — placed before you connected.')).toBeInTheDocument()
    expect(within(confirm).getByText('Linen dress')).toBeInTheDocument()
    expect(importCalls).toEqual([{ number: '5126', link: false }])

    await userEvent.click(within(confirm).getByRole('button', { name: 'Link order' }))
    await waitFor(() => expect(importCalls).toEqual([{ number: '5126', link: false }, { number: '5126', link: true }]))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(attachCalls).toEqual([])
  })

  test('l5 Shopify NOT_FOUND → a clear "not found in Shopify" message, not an error', async () => {
    searchAnswer = q => q === ''
      ? { query: '', suggested: true, orders: [], importOption: null }
      : { query: q, suggested: false, orders: [], importOption: { number: '3333' } }
    importAnswer = () => ({ status: 'NOT_FOUND', reason: 'NOT_FOUND', number: '3333', items: [], exchangeStatus: null })
    renderDrawer()
    await screen.findByText('No suggestions — search above.')
    await userEvent.type(screen.getByTestId('exchange-order-search'), '#3333')
    await userEvent.click(within(await screen.findByTestId('exchange-import-row')).getByRole('button', { name: 'Import from Shopify' }))
    expect(await screen.findByText("Order #3333 wasn't found in Shopify.")).toBeInTheDocument()
    expect(screen.queryByTestId('exchange-link-confirm')).toBeNull()
    expect(screen.queryByText("That didn't go through — please try again.")).toBeNull()
  })

  test('l6 needs_mapping → "Map replacement" opens /exchanges/:id', async () => {
    renderDrawer()
    const link = await screen.findByTestId('map-replacement-link')
    expect(link).toHaveAttribute('href', '/exchanges/exc-1')
    expect(link).toHaveTextContent('Map replacement')
  })

  test('l7 needs_mapping already linked → Map replacement only, no search', async () => {
    stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) =>
      url.endsWith('/exchanges/exc-1') ? fakeResponse(EXCHANGE('needs_mapping', 'ord-a')) : backendFetch(url, opts)))
    renderDrawer()
    expect(await screen.findByTestId('map-replacement-link')).toBeInTheDocument()
    expect(screen.getByTestId('exchange-original-linked')).toBeInTheDocument()
    expect(screen.queryByTestId('exchange-order-link')).toBeNull()
    expect(searches).toEqual([])
  })

  test('l8 unmatched → search + bare return / dismiss; no Map replacement', async () => {
    exchangeStatus = 'unmatched'
    renderDrawer()
    expect(await screen.findByTestId('exchange-order-link')).toBeInTheDocument()
    expect(screen.getByTestId('bare-return-button')).toBeInTheDocument()
    expect(screen.queryByTestId('map-replacement-link')).toBeNull()
  })
})

describe('Exchange drawer — Arabic (S2)', () => {
  afterEach(async () => { await i18n.changeLanguage('en') })

  test('l9 AR strings for search, suggestions and the import row', async () => {
    await i18n.changeLanguage('ar')
    searchAnswer = q => q === ''
      ? { query: '', suggested: true, orders: [ORDER_A], importOption: null }
      : { query: q, suggested: false, orders: [], importOption: { number: '5126' } }
    renderWithProviders(
      <I18nextProvider i18n={i18n}>
        <ExchangeRefundDrawer row={ROW} onClose={() => {}} onChanged={() => {}} />
      </I18nextProvider>)
    expect(await screen.findByText('الربط بالطلب الأصلي')).toBeInTheDocument()
    expect(screen.getByText('مقترح — نفس هاتف عميل شركة الشحن')).toBeInTheDocument()
    expect(screen.getByText('ربط المنتج البديل')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ربط' })).toBeInTheDocument()
    await userEvent.type(screen.getByTestId('exchange-order-search'), '#5126')
    const importRow = await screen.findByTestId('exchange-import-row')
    expect(within(importRow).getByRole('button', { name: 'استيراد من Shopify' })).toBeInTheDocument()
    // The order number is isolated LTR inside the RTL sentence.
    expect(within(importRow).getByText('#5126').closest('bdi')).toHaveAttribute('dir', 'ltr')
  })
})

describe('Exchange mapping screen — single read (S2)', () => {
  test('m1 an exchange older than the newest 100 loads (GET /exchanges/{id}, never the list)', async () => {
    const newest100 = Array.from({ length: 100 }, (_, i) => ({ ...EXCHANGE('needs_mapping'), id: `exc-new-${i}` }))
    const calls: string[] = []
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      calls.push(url)
      if (url.includes('/exchanges/exc-1/outbound-candidates')) return fakeResponse({ classification: 'NONE', committedVariantId: null, candidates: [] })
      if (url.endsWith('/exchanges/exc-1')) return fakeResponse(EXCHANGE('needs_mapping'))
      if (url.includes('/exchanges?')) return fakeResponse(newest100)
      return fakeResponse({})
    }))
    renderWithProviders(
      <Routes><Route path="/exchanges/:id" element={<ExchangeMapping />} /></Routes>,
      { initialEntries: ['/exchanges/exc-1'] })
    expect(await screen.findByTestId('exchange-leg-outbound')).toBeInTheDocument()
    expect(screen.queryByText('Exchange not found')).toBeNull()
    expect(calls.some(u => u.includes('/exchanges?'))).toBe(false)
  })

  test('m2 a 404 still says not found', async () => {
    stubFetchWithShellDefaults(vi.fn((url: string) =>
      url.endsWith('/exchanges/exc-1') ? fakeResponse({ error: 'Exchange not found' }, 404) : fakeResponse({})))
    renderWithProviders(
      <Routes><Route path="/exchanges/:id" element={<ExchangeMapping />} /></Routes>,
      { initialEntries: ['/exchanges/exc-1'] })
    expect(await screen.findByText('Exchange not found')).toBeInTheDocument()
    expect(screen.queryByTestId('exchange-leg-outbound')).toBeNull()
  })
})
