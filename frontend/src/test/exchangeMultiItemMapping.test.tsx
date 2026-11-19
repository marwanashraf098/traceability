import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'
import i18n from '../i18n'

/**
 * Exchange reference S3a (2026-11-18) — the N-row mapping screen: add / remove rows, quantity and courier-text hint per
 * row, inbound rows from the linked order's lines (or the catalog) or "don't know yet"; anything but one plain item each
 * way posts /map-items (the single-item /map path is covered, unchanged, by exchangeMapping.test.tsx).
 */

const OUT_AR = 'عدد-2 وايد ليج بيج وكحلي مقاس -1'
const IN_AR = 'عدد-2 وايد ليج اسود مقاس -2'

const CATALOG = {
  products: [{
    id: 'p-wide', title: 'وايد ليج', status: 'active', imageUrl: null,
    variants: [
      { id: 'v-beige', title: 'بيج / 1', sku: 'WL-B1', price: 450 },
      { id: 'v-navy', title: 'كحلي / 1', sku: 'WL-N1', price: 450 },
      { id: 'v-black', title: 'اسود / 2', sku: 'WL-K2', price: 450 },
    ],
  }],
}

const EXCHANGE = (matched: string | null) => ({
  id: 'exc-1', tracking_number: '58291044', status: 'needs_mapping', matched_order_id: matched, match_method: null,
  matched_at: null, outbound_description: OUT_AR, inbound_description: IN_AR, inbound_description_ar: null,
  cod: 0, goods_value: 900, auto_matched: false, outbound_items_count: 2, inbound_items_count: 2,
  customer_name: 'منى عادل', customer_phone: '+201005557788',
})

const LINES = [
  { orderItemId: 'oi-1', variantId: 'v-black', quantity: 1, productTitle: 'وايد ليج', variantTitle: 'اسود / 2', sku: 'WL-K2', imageUrl: null },
  { orderItemId: 'oi-2', variantId: 'v-beige', quantity: 1, productTitle: 'وايد ليج', variantTitle: 'بيج / 1', sku: 'WL-B1', imageUrl: null },
]

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

let mapItemsBody: unknown = null
let mapBody: unknown = null
let matched: string | null = null
let lineCalls = 0

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/api/v1/catalog')) return fakeResponse(CATALOG)
  if (url.endsWith('/exchanges/exc-1/outbound-candidates')) return fakeResponse({ classification: 'NONE', committedVariantId: null, candidates: [] })
  if (url.endsWith('/exchanges/exc-1/original-lines')) { lineCalls++; return fakeResponse(LINES) }
  if (url.endsWith('/exchanges/exc-1/map-items') && method === 'POST') {
    mapItemsBody = JSON.parse(opts.body as string)
    return fakeResponse({ exchangeId: 'exc-1', orderId: 'order-9', status: 'mapped' })
  }
  if (url.endsWith('/exchanges/exc-1/map') && method === 'POST') {
    mapBody = JSON.parse(opts.body as string)
    return fakeResponse({ exchangeId: 'exc-1', orderId: 'order-9', status: 'mapped' })
  }
  if (url.endsWith('/exchanges/exc-1')) return fakeResponse(EXCHANGE(matched))
  return fakeResponse({})
}

function renderScreen(wrapI18n = false) {
  const tree = <Routes><Route path="/exchanges/:id" element={<ExchangeMapping />} /></Routes>
  return renderWithProviders(wrapI18n ? <I18nextProvider i18n={i18n}>{tree}</I18nextProvider> : tree,
    { initialEntries: ['/exchanges/exc-1'] })
}

async function pickCatalog(user: ReturnType<typeof userEvent.setup>, row: HTMLElement, variantId: string) {
  await user.click(within(row).getByRole('button', { name: /Select variant|Change/i }))
  await screen.findByTestId('exchange-variant-picker')
  await user.click(screen.getByTestId('exchange-picker-product-p-wide'))
  await screen.findByTestId('exchange-picker-variant-list')
  await user.click(screen.getByTestId(`exchange-picker-variant-${variantId}`))
}

beforeEach(() => {
  mapItemsBody = null; mapBody = null; matched = null; lineCalls = 0
  stubFetchWithShellDefaults(vi.fn(backendFetch))
})

describe('Exchange mapping — N rows (S3a)', () => {
  test('n1 rows start from Bosta (count + text); a second outbound row → POST /map-items with every row', async () => {
    const user = userEvent.setup()
    renderScreen()
    const out0 = await screen.findByTestId('exchange-row-outbound-0')
    expect(within(out0).getByTestId('exchange-row-outbound-0-hint')).toHaveValue(OUT_AR)
    expect(within(out0).getByTestId('exchange-row-outbound-0-qty')).toHaveTextContent('2')
    expect(within(screen.getByTestId('exchange-row-inbound-0')).getByTestId('exchange-row-inbound-0-hint')).toHaveValue(IN_AR)

    // Split the outbound: 1 beige + 1 navy.
    await user.click(within(out0).getByRole('button', { name: 'Fewer' }))
    await user.click(screen.getByTestId('exchange-leg-outbound-add'))
    const out1 = screen.getByTestId('exchange-row-outbound-1')
    await user.type(within(out1).getByTestId('exchange-row-outbound-1-hint'), 'وايد ليج كحلي')
    await pickCatalog(user, out0, 'v-beige')
    await pickCatalog(user, out1, 'v-navy')
    await pickCatalog(user, screen.getByTestId('exchange-row-inbound-0'), 'v-black')

    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))
    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapBody).toBeNull()
    expect(mapItemsBody).toEqual({
      outbound: [
        { variantId: 'v-beige', quantity: 1, descriptionHint: OUT_AR },
        { variantId: 'v-navy', quantity: 1, descriptionHint: 'وايد ليج كحلي' },
      ],
      inbound: [{ variantId: 'v-black', quantity: 2, descriptionHint: IN_AR }],
    })
  })

  test('n2 an inbound row can stay "don\'t know yet"; confirm needs every outbound row mapped', async () => {
    const user = userEvent.setup()
    renderScreen()
    const out0 = await screen.findByTestId('exchange-row-outbound-0')
    const confirm = screen.getByRole('button', { name: /Confirm mapping/i })
    await user.click(screen.getByTestId('exchange-row-inbound-0-unknown'))
    expect(confirm).toBeDisabled()
    await pickCatalog(user, out0, 'v-beige')
    await user.click(screen.getByTestId('exchange-leg-outbound-add'))
    expect(confirm).toBeDisabled()
    await user.click(screen.getByTestId('exchange-row-outbound-1-remove'))
    expect(confirm).not.toBeDisabled()

    await user.click(confirm)
    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapItemsBody).toEqual({
      outbound: [{ variantId: 'v-beige', quantity: 2, descriptionHint: OUT_AR }],
      inbound: [{ variantId: null, quantity: 2, descriptionHint: IN_AR }],
    })
  })

  test('n3 a linked order → inbound rows pick from its lines; the catalog stays one tap away', async () => {
    matched = 'order-orig'
    const user = userEvent.setup()
    renderScreen()
    const in0 = await screen.findByTestId('exchange-row-inbound-0')
    await waitFor(() => expect(lineCalls).toBe(1))
    await user.click(within(in0).getByRole('button', { name: /Select variant/i }))
    const picker = await screen.findByTestId('exchange-original-line-picker')
    expect(within(picker).getByText('Items on the linked order')).toBeInTheDocument()
    await user.click(within(picker).getByTestId('exchange-original-line-oi-1'))
    expect(within(in0).getByText('اسود / 2')).toBeInTheDocument()

    await user.click(screen.getByTestId('exchange-leg-inbound-add'))
    const in1 = screen.getByTestId('exchange-row-inbound-1')
    await user.click(within(in1).getByRole('button', { name: /Select variant/i }))
    await user.click(await screen.findByTestId('exchange-original-line-catalog'))
    expect(await screen.findByTestId('exchange-variant-picker')).toBeInTheDocument()

  })

  test('n4 one row each way but a changed quantity → /map-items (never the single-item /map)', async () => {
    const user = userEvent.setup()
    renderScreen()
    const out0 = await screen.findByTestId('exchange-row-outbound-0')
    await pickCatalog(user, out0, 'v-beige')
    await pickCatalog(user, screen.getByTestId('exchange-row-inbound-0'), 'v-black')
    await user.click(within(out0).getByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))
    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapBody).toBeNull()
    expect((mapItemsBody as { outbound: { quantity: number }[] }).outbound[0].quantity).toBe(3)
  })

  test('n5 one row each way on a 2-item exchange → map-items with Bosta\'s counts, never the single-item /map (S3b)', async () => {
    // Bosta counted 2 out / 2 in: one row each way at quantity 2 must still write exchange_items rows. The single-item
    // /map (one item each way as Bosta counted it) is covered, unchanged, by exchangeMapping.test.tsx.
    const user = userEvent.setup()
    renderScreen()
    const out0 = await screen.findByTestId('exchange-row-outbound-0')
    await pickCatalog(user, out0, 'v-beige')
    await pickCatalog(user, screen.getByTestId('exchange-row-inbound-0'), 'v-black')
    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))
    await waitFor(() => screen.getByTestId('exchange-mapped-success'))
    expect(mapBody).toBeNull()
    expect(mapItemsBody).toEqual({
      outbound: [{ variantId: 'v-beige', quantity: 2, descriptionHint: expect.anything() }],
      inbound: [{ variantId: 'v-black', quantity: 2, descriptionHint: expect.anything() }],
    })
  })
})

describe('Exchange mapping — Arabic (S3a)', () => {
  afterEach(async () => { await i18n.changeLanguage('en') })

  test('n6 AR strings for rows', async () => {
    await i18n.changeLanguage('ar')
    renderScreen(true)
    await screen.findByTestId('exchange-row-outbound-0')
    expect(screen.getAllByText('الكمية').length).toBeGreaterThan(0)
    expect(screen.getAllByText('نص شركة الشحن لهذا المنتج').length).toBe(2)
    expect(screen.getAllByText('إضافة منتج')).toHaveLength(2)
    expect(screen.getByText('لا أعرف المنتج بعد')).toBeInTheDocument()
  })
})
