import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { render, renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Orders from '../pages/Orders'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, OrderSummary, OrderTabCounts } from '../api'

// Orders tabs & filters — UI slice (2026-10-31). Every list setting lives in the URL; one /orders?withCounts=true
// request per change; the Filters panel previews with /orders/counts?attention=false and applies on "Show N orders".

const json = (data: unknown) => Promise.resolve({
  ok: true, status: 200,
  headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}
const loc = () => new URLSearchParams((screen.getByTestId('location').textContent ?? '').split('?')[1] ?? '')

const derived = (over: Record<string, unknown> = {}) => ({
  primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null, conflictKey: null,
  notTraced: false, packedConfirmed: true, fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS', ...over,
})
const row = (id: string, number: string, over: Partial<OrderSummary> = {}): OrderSummary => ({
  id, number, customerName: 'Mona Said', customerPhone: '0102', status: 'with_courier', onHold: false,
  codAmount: 540, placedAt: '2026-10-20T09:12:00Z', trackingNumber: '889213', deliveryState: 'with_courier',
  exceptionReason: null, bostaLinkStatus: 'linked', failedDeliveryAttempts: 0, isDelayed: null, slaBreached: null,
  notTracedAt: null, isExchange: false, derivedStatus: derived() as OrderSummary['derivedStatus'],
  shippingBadge: null, attentionReasons: [], ...over,
})

const COUNTS: OrderTabCounts = {
  all: 31, attention: 6, toShip: 9, inTransit: 7, delivered: 11, returned: 3, cancelled: 1,
  reasonCounts: { cant_ship: 2, no_courier_update: 3, delivery_failed: 1, cancelled_shipped: 0, return_to_handle: 0, other: 0 },
}

const ORDER = {
  id: 'order-1', number: '#16520', customerName: 'Mona Said', customerPhone: '0102', address: null,
  paymentMethod: 'cod', codAmount: 540, status: 'delivered', onHold: false, holdReason: null,
  placedAt: '2026-10-20T13:51:58Z', createdAt: '2026-10-20T13:52:05Z',
  items: [{ id: 'it-1', productTitle: 'Linen Shirt', variantTitle: 'White / M', sku: 'LIN', quantity: 2, imageUrl: null,
    allocatedPieces: [
      { pieceId: 'p-short', barcode: 'TRC-2026-000104233', shortCode: 'K7Q2', status: 'delivered' },
      { pieceId: 'p-long', barcode: 'TRC-2026-000104234', status: 'delivered' },
    ] }],
  shipments: [], bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
  derivedStatus: derived({ primaryKey: 'status.delivered', tone: 'SUCCESS' }), physicallyWithCourier: false,
} as unknown as OrderDetail

let calls: string[]
let rows: OrderSummary[]
let preview: OrderTabCounts

function backend(url: string) {
  calls.push(url)
  if (/\/orders\/o-77(\?|$)/.test(url)) return json({ ...ORDER, id: 'o-77' })
  if (url.includes('/notes') || url.includes('/timeline')) return json([])
  if (url.includes('/orders/summary')) return json({ total: 31, processing: 9, withCourier: 7, delivered: 11, returned: 3, needsAttention: 6 })
  if (url.includes('/orders/counts?')) return json(preview)
  if (url.includes('/orders?')) {
    const tab = new URL(url, 'http://x').searchParams.get('tab')
    const counts = tab === 'attention' ? COUNTS : { ...COUNTS, attention: 6 }
    return json({ items: rows, page: 0, size: 20, total: rows.length, counts })
  }
  return json({})
}
const listCalls = () => calls.filter(u => u.includes('/orders?'))
const lastList = () => new URL(listCalls()[listCalls().length - 1], 'http://x').searchParams
const countCalls = () => calls.filter(u => u.includes('/orders/counts?'))

function renderOrders(entry = '/orders') {
  return renderWithProviders(<><Orders /><LocationProbe /></>, { initialEntries: [entry] })
}

beforeEach(() => {
  calls = []
  rows = [row('o1', '#1001')]
  preview = { ...COUNTS, attention: null, reasonCounts: null, all: 14, delivered: 4 }
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { vi.unstubAllGlobals() })

describe('tabs', () => {
  test('each tab sends tab=<key> with withCounts, writes it to the URL and shows its count', async () => {
    const user = userEvent.setup()
    renderOrders()
    await screen.findByText('#1001')
    expect(lastList().get('tab')).toBe('all')
    expect(lastList().get('withCounts')).toBe('true')

    const tabs: Array<[RegExp, string, string]> = [
      [/^Needs attention/, 'attention', '6'], [/^To ship/, 'toShip', '9'], [/^In transit/, 'inTransit', '7'],
      [/^Delivered/, 'delivered', '11'], [/^Returned/, 'returned', '3'], [/^Cancelled/, 'cancelled', '1'],
      [/^All/, 'all', '31'],
    ]
    for (const [name, key, count] of tabs) {
      const tab = screen.getByRole('button', { name })
      expect(within(tab).getByText(count)).toBeInTheDocument()
      const before = listCalls().length
      await user.click(tab)
      await waitFor(() => expect(listCalls().length).toBe(before + 1))   // exactly one request per change
      expect(lastList().get('tab')).toBe(key)
      expect(loc().get('tab')).toBe(key === 'all' ? null : key)
    }
  })

  test('the Needs attention pill is amber only while that tab is not selected', async () => {
    const user = userEvent.setup()
    renderOrders()
    await screen.findByText('#1001')
    const pill = () => within(screen.getByRole('button', { name: /^Needs attention/ })).getByText('6')
    expect(pill()).toHaveAttribute('data-tone', 'warning')
    await user.click(screen.getByRole('button', { name: /^Needs attention/ }))
    await waitFor(() => expect(pill()).not.toHaveAttribute('data-tone'))
  })

  test('the tab row scrolls sideways (phones) instead of overflowing the page', async () => {
    renderOrders()
    await screen.findByText('#1001')
    expect(screen.getByTestId('tabs-scroll').className).toContain('overflow-x-auto')
  })
})

describe('Filters panel', () => {
  test('picking filters and "Show N orders" sets the URL and the request params; the button shows a count', async () => {
    const user = userEvent.setup()
    renderOrders()
    await screen.findByText('#1001')
    await user.click(screen.getByTestId('orders-filters-button'))
    const panel = await screen.findByTestId('orders-filters-panel')
    await user.click(within(panel).getByTestId('orders-filter-courier-bosta'))
    await user.click(within(panel).getByTestId('orders-filter-payment-cod'))
    expect(within(panel).getByTestId('orders-filter-courier-bosta')).toHaveAttribute('aria-pressed', 'true')

    const before = listCalls().length
    await user.click(await within(panel).findByRole('button', { name: 'Show 14 orders' }))
    await waitFor(() => expect(listCalls().length).toBe(before + 1))
    expect(lastList().get('courier')).toBe('bosta')
    expect(lastList().get('payment')).toBe('cod')
    expect(loc().get('courier')).toBe('bosta')
    expect(loc().get('payment')).toBe('cod')
    expect(screen.queryByTestId('orders-filters-panel')).toBeNull()
    expect(screen.getByTestId('orders-filters-count')).toHaveTextContent('2')
    expect(screen.getByTestId('orders-filters-button').className).toContain('bg-trace-blue/10')
  })

  test('"Show N orders" counts the PENDING selection with /orders/counts?attention=false for the active tab', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?tab=delivered')
    await screen.findByText('#1001')
    await user.click(screen.getByTestId('orders-filters-button'))
    const panel = await screen.findByTestId('orders-filters-panel')
    preview = { ...preview, delivered: 2 }
    await user.click(within(panel).getByTestId('orders-filter-traced-not_traced'))
    expect(await within(panel).findByRole('button', { name: 'Show 2 orders' })).toBeInTheDocument()
    const last = new URL(countCalls()[countCalls().length - 1], 'http://x').searchParams
    expect(last.get('attention')).toBe('false')
    expect(last.get('traced')).toBe('not_traced')
    // Pending only — the list was not re-requested and the URL is unchanged until "Show".
    expect(loc().get('traced')).toBeNull()
    expect(lastList().get('traced')).toBeNull()
  })

  test('each active filter is a removable tag; removing one updates the URL and the request', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?courier=bosta&traced=not_traced&days=7')
    await screen.findByText('#1001')
    expect(screen.getByTestId('orders-filter-tag-courier')).toHaveTextContent('Courier: Bosta')
    expect(screen.getByTestId('orders-filter-tag-traced')).toHaveTextContent('Traced: Not traced')
    expect(screen.getByTestId('orders-filter-tag-days')).toHaveTextContent('Order date: Last 7 days')

    await user.click(screen.getByRole('button', { name: 'Remove Courier: Bosta' }))
    await waitFor(() => expect(lastList().get('courier')).toBeNull())
    expect(lastList().get('traced')).toBe('not_traced')
    expect(loc().get('courier')).toBeNull()
    expect(screen.queryByTestId('orders-filter-tag-courier')).toBeNull()
  })

  test('Clear all resets the pending selection; Show applies it (URL back to defaults)', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?courier=other&returns=pending&days=90')
    await screen.findByText('#1001')
    await user.click(screen.getByTestId('orders-filters-button'))
    const panel = await screen.findByTestId('orders-filters-panel')
    await user.click(within(panel).getByRole('button', { name: 'Clear all' }))
    expect(within(panel).getByTestId('orders-filter-courier-all')).toHaveAttribute('aria-pressed', 'true')
    expect(within(panel).getByTestId('orders-filter-days-30')).toHaveAttribute('aria-pressed', 'true')
    await user.click(await within(panel).findByRole('button', { name: /^Show/ }))
    await waitFor(() => expect(loc().toString()).toBe(''))
    expect(lastList().get('courier')).toBeNull()
    expect(lastList().get('returns')).toBeNull()
    expect(lastList().get('days')).toBe('30')
    expect(screen.queryByTestId('orders-filters-count')).toBeNull()
  })

  test('Order date shows as not applied while searching and on Needs attention', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?q=mona')
    await screen.findByText('#1001')
    await user.click(screen.getByTestId('orders-filters-button'))
    expect(await screen.findByTestId('orders-filter-days-not-applied')).toBeInTheDocument()
    expect(screen.getByText('Traced = every piece scanned out in Pick & Pack. Applies to Bosta orders.')).toBeInTheDocument()
    expect(screen.getByText('Pending inspection = returned piece scanned in, not yet restocked or marked damaged.')).toBeInTheDocument()
  })
})

describe('Needs attention', () => {
  test('reason chips show each group count; a click filters by reason=, a second click clears it', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?tab=attention')
    const chips = await screen.findByTestId('orders-reason-chips')
    expect(lastList().get('tab')).toBe('attention')
    expect(within(chips).getByTestId('orders-reason-no_courier_update')).toHaveTextContent('No courier update3')
    expect(within(chips).getAllByRole('button')).toHaveLength(6)

    await user.click(within(chips).getByTestId('orders-reason-no_courier_update'))
    await waitFor(() => expect(lastList().get('reason')).toBe('no_courier_update'))
    expect(loc().get('reason')).toBe('no_courier_update')
    expect(within(chips).getByTestId('orders-reason-no_courier_update')).toHaveAttribute('aria-pressed', 'true')

    await user.click(within(chips).getByTestId('orders-reason-no_courier_update'))
    await waitFor(() => expect(lastList().get('reason')).toBeNull())
    expect(loc().get('reason')).toBeNull()
  })

  test('no reason chips on the other tabs; leaving the tab drops reason=', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?tab=attention&reason=cant_ship')
    await screen.findByTestId('orders-reason-chips')
    expect(lastList().get('reason')).toBe('cant_ship')
    await user.click(screen.getByRole('button', { name: /^To ship/ }))
    await waitFor(() => expect(lastList().get('tab')).toBe('toShip'))
    expect(lastList().get('reason')).toBeNull()
    expect(loc().get('reason')).toBeNull()
    expect(screen.queryByTestId('orders-reason-chips')).toBeNull()
  })
})

describe('row lines and tags', () => {
  test('the reason line (Needs attention, with +N) comes before the returnState line under the Delivery badge', async () => {
    rows = [row('o1', '#1001', {
      attentionGroup: { group: 'no_courier_update', reason: 'stuck_shipment', extra: 2 },
      returnState: { state: 'pending', grades: {} },
    })]
    renderOrders('/orders?tab=attention')
    const reasonLine = await screen.findByTestId('orders-reason-line-#1001')
    expect(reasonLine).toHaveTextContent('No courier update for days+2')
    expect(reasonLine.className).toContain('text-warning-text')
    const returnLine = screen.getByTestId('orders-return-line-#1001')
    expect(returnLine).toHaveTextContent('Pending inspection')
    expect(returnLine.className).toContain('text-warning-text')
    expect(reasonLine.compareDocumentPosition(returnLine) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('the reason line shows only on Needs attention; the returnState line on every tab', async () => {
    rows = [
      row('o1', '#1001', { attentionGroup: { group: 'cant_ship', reason: 'missing_awb', extra: 0 },
                           returnState: { state: 'inspected', grades: { restocked: 2 } } }),
      row('o2', '#1002', { returnState: { state: 'inspected', grades: { restocked: 1, damaged: 1 } } }),
      row('o3', '#1003', { returnState: { state: 'inspected', grades: { backToGood: 1 } } }),
      row('o4', '#1004', { returnState: { state: 'inspected', grades: { sellable: 1 } } }),
      row('o5', '#1005', { returnState: { state: 'inspected', grades: { damaged: 1 } } }),
    ]
    renderOrders()
    await screen.findByText('#1001')
    expect(screen.queryByTestId('orders-reason-line-#1001')).toBeNull()
    expect(screen.getByTestId('orders-return-line-#1001')).toHaveTextContent('Inspected · 2 restocked')
    expect(screen.getByTestId('orders-return-line-#1001').className).toContain('text-muted')
    expect(screen.getByTestId('orders-return-line-#1002')).toHaveTextContent('Inspected · 1 restocked · 1 damaged')
    expect(screen.getByTestId('orders-return-line-#1003')).toHaveTextContent('Inspected · back to good')
    expect(screen.getByTestId('orders-return-line-#1004')).toHaveTextContent('Inspected · sellable')
    expect(screen.getByTestId('orders-return-line-#1005')).toHaveTextContent('Inspected · 1 damaged')
  })

  test('the Not traced tag sits beside the order number (grey), no longer in the Fulfillment column', async () => {
    rows = [row('o1', '#1001', { derivedStatus: derived({ notTraced: true }) as OrderSummary['derivedStatus'] })]
    renderOrders()
    const number = await screen.findByText('#1001')
    const cells = number.closest('tr')!.querySelectorAll('td')
    const numberCell = number.closest('td')!
    const fulfillmentCell = cells[Array.from(cells).indexOf(numberCell as HTMLTableCellElement) + 2]
    const tag = within(numberCell).getByText('Not Traced')
    expect(tag.className).toContain('text-neutral-text')
    expect(within(fulfillmentCell).queryByText('Not Traced')).toBeNull()
    expect(within(fulfillmentCell).getByText('Fulfilled')).toBeInTheDocument()
  })
})

describe('URL', () => {
  test('reload / shared link restores tab, filters and search: first request and the inputs follow the URL', async () => {
    renderOrders('/orders?tab=toShip&courier=other&payment=prepaid&days=7&q=mona&tracking=889')
    await screen.findByText('#1001')
    expect(listCalls()).toHaveLength(1)
    const p = lastList()
    expect(p.get('tab')).toBe('toShip')
    expect(p.get('courier')).toBe('other')
    expect(p.get('payment')).toBe('prepaid')
    expect(p.get('days')).toBe('7')
    expect(p.get('q')).toBe('mona')
    expect(p.get('tracking')).toBe('889')
    expect(screen.getByPlaceholderText('Search by order #, name, or phone')).toHaveValue('mona')
    expect(screen.getByPlaceholderText('Tracking number')).toHaveValue('889')
    expect(screen.getByTestId('orders-filters-count')).toHaveTextContent('3')
  })

  test('the debounced search is written to the URL (q=) and ?order= keeps opening the drawer', async () => {
    const user = userEvent.setup()
    renderOrders('/orders?order=o-77&tab=delivered')
    await waitFor(() => expect(calls.some(u => /\/orders\/o-77(\?|$)/.test(u))).toBe(true))
    await user.type(screen.getByPlaceholderText('Search by order #, name, or phone'), 'abc')
    await waitFor(() => expect(loc().get('q')).toBe('abc'))
    expect(loc().get('tab')).toBe('delivered')
    expect(loc().get('order')).toBe('o-77')
    await waitFor(() => expect(lastList().get('q')).toBe('abc'))
  })
})

describe('drawer', () => {
  test('a piece row shows its short code (full code otherwise), never wrapping', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/notes') || url.includes('/timeline')) return json([])
      if (url.includes('/orders/')) return json(ORDER)
      return json({})
    }))
    const i18n = i18next.createInstance()
    await i18n.use(initReactI18next).init({ lng: 'en', resources: { en: { translation: en } }, interpolation: { escapeValue: false } })
    render(<MemoryRouter><I18nextProvider i18n={i18n}><OrderDrawer orderId="order-1" onClose={() => {}} /></I18nextProvider></MemoryRouter>)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Items (1)' }))
    const short = screen.getByTestId('drawer-piece-code-p-short')
    expect(short).toHaveTextContent('K7Q2')
    expect(short).toHaveAttribute('title', 'TRC-2026-000104233')
    expect(short.className).toContain('whitespace-nowrap')
    expect(short.parentElement!.className).toContain('flex-wrap')
    expect(screen.getByTestId('drawer-piece-code-p-long')).toHaveTextContent('TRC-2026-000104234')
  })
})

describe('Arabic', () => {
  test('tabs, filters panel and row lines render in Arabic', async () => {
    rows = [row('o1', '#1001', {
      attentionGroup: { group: 'return_to_handle', reason: 'return_to_receive', extra: 0 },
      returnState: { state: 'inspected', grades: { backToGood: 1 } },
    })]
    const i18n = i18next.createInstance()
    await i18n.use(initReactI18next).init({ lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
    const user = userEvent.setup()
    render(<MemoryRouter initialEntries={['/orders?tab=attention']}><I18nextProvider i18n={i18n}><div dir="rtl"><Orders /></div></I18nextProvider></MemoryRouter>)
    expect(await screen.findByTestId('orders-reason-line-#1001')).toHaveTextContent('منتج مرتجع بانتظار الاستلام في المخزون')
    expect(screen.getByTestId('orders-return-line-#1001')).toHaveTextContent('تم الفحص · عاد سليماً')
    expect(screen.getByRole('button', { name: /^للشحن/ })).toBeInTheDocument()
    expect(within(screen.getByTestId('orders-reason-chips')).getByText('مرتجع يحتاج إجراء')).toBeInTheDocument()
    await user.click(screen.getByTestId('orders-filters-button'))
    const panel = await screen.findByTestId('orders-filters-panel')
    expect(within(panel).getByText('مسح الكل')).toBeInTheDocument()
    expect(within(panel).getByText('شركات شحن أخرى')).toBeInTheDocument()
    expect(within(panel).getByTestId('orders-filter-days-not-applied')).toHaveTextContent('لا يُطبَّق')
  })
})
