import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import Fulfill from '../pages/Fulfill'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, PackOrderCard, PackSessionView } from '../api'

// Shopify order edits (2026-10-21): a line removed in Shopify is kept at quantity 0. The pick screen and
// the waybill pack session hide it (nothing to pick); an order whose every line was removed says so and
// offers no Complete; the order drawer shows it struck through, labelled "Removed in Shopify" — or, for a line
// added after the order left Traced (stored at 0), "Added after shipping — not in this parcel".

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

afterEach(() => { vi.unstubAllGlobals() })

// ── Pick screen (queue mode) ─────────────────────────────────────────────────

function pickOrder(items: Array<{ title: string; quantity: number; allocated: number }>) {
  return {
    id: 'order-1', number: '#16443', customer_name: 'Alice', customer_phone: null, status: 'new',
    payment_method: null, cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null,
    shipment_id: 'sh-1', tracking_number: '2805756048', shipment_has_courier: true, awbPrinted: true,
    items: items.map((it, n) => ({
      id: `item-${n}`, variant_id: `v${n}`, sku: `SKU-${n}`, variant_title: 'Default Title',
      product_title: it.title, quantity: it.quantity, allocated: it.allocated,
      allocatedPieces: Array.from({ length: it.allocated }, (_, i) =>
        ({ piece_id: `p${n}-${i}`, barcode: `PIECE${n}${i}`, allocation_status: 'active', piece_status: 'reserved' })),
    })),
  }
}

async function openPick(order: ReturnType<typeof pickOrder>) {
  stubFetchWithShellDefaults(vi.fn((url: string) => {
    if (url.endsWith('/fulfill/queue')) {
      return json([{ id: 'order-1', number: '#16443', customer_name: 'Alice', status: 'new', payment_method: null,
        cod_amount: null, total_units: 1, scanned_units: 0, locked_by: null, locked_at: null,
        is_self_pickup: false, is_exchange: false }])
    }
    if (url.includes('awaiting-waybill-count')) return json({ count: 0 })
    if (url.endsWith('/fulfill/order-1')) return json(order)
    return json({})
  }))
  const user = userEvent.setup()
  renderWithProviders(<Fulfill />)
  await user.click(await screen.findByText('#16443'))
  return screen.findByTestId('fulfill-pick')
}

describe('pick screen', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  test('a line removed in Shopify (quantity 0) is hidden; the remaining line is shown', async () => {
    const pick = await openPick(pickOrder([
      { title: 'BLACK LEATHER SET – M', quantity: 0, allocated: 0 },
      { title: 'BLACK LEATHER SET – S', quantity: 1, allocated: 0 },
    ]))
    expect(within(pick).getByText('BLACK LEATHER SET – S')).toBeInTheDocument()
    expect(within(pick).queryByText('BLACK LEATHER SET – M')).not.toBeInTheDocument()
    expect(within(pick).queryByTestId('all-items-removed')).not.toBeInTheDocument()
  })

  test('every line removed → says so, no Complete button', async () => {
    const pick = await openPick(pickOrder([{ title: 'BLACK LEATHER SET – M', quantity: 0, allocated: 0 }]))
    expect(within(pick).getByTestId('all-items-removed'))
      .toHaveTextContent('Every item on this order was removed in Shopify')
    expect(within(pick).queryByText('BLACK LEATHER SET – M')).not.toBeInTheDocument()
    expect(within(pick).queryByRole('button', { name: 'Complete' })).not.toBeInTheDocument()
  })
})

// ── Waybill pack session ──────────────────────────────────────────────────────

describe('waybill pack session', () => {
  test('a line removed in Shopify is hidden and left out of the progress count', async () => {
    const order: PackOrderCard = {
      id: 'ord-1', number: '#16443', customer_name: 'Salma', payment_method: 'cod', cod_amount: '2199.00',
      tracking_number: '2805756048', area: 'Nasr City, Cairo', courierType: 'delivery', batchNo: 4, batchPrintedAt: null,
      items: [
        { id: 'li-m', variant_id: 'v-m', sku: 'BLS-M', variant_title: 'M', product_title: 'BLACK LEATHER SET',
          imageUrl: null, quantity: 0, allocated: 0, allocatedPieces: [] },
        { id: 'li-s', variant_id: 'v-s', sku: 'BLS-S', variant_title: 'S', product_title: 'BLACK LEATHER SET',
          imageUrl: null, quantity: 1, allocated: 0, allocatedPieces: [] },
      ],
    }
    const view: PackSessionView = {
      id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-21T07:05:00Z', workerName: 'Mona',
      counters: { packed: 0, setAside: 0, rejected: 0, left: 1 }, recent: [], openOrder: order,
    }
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      if (url.endsWith('/pack-sessions/sess-1')) return json(view)
      if (url.endsWith('/me')) return json({ name: 'Mona', email: null, role: 'worker' })
      return json({})
    }))
    renderWithProviders(<PackSessionScreen initial={view} onEnded={() => {}} />)
    const scanning = await screen.findByTestId('session-scanning')
    expect(within(scanning).getByText('BLS-S')).toBeInTheDocument()
    expect(within(scanning).queryByText('BLS-M')).not.toBeInTheDocument()
    expect(within(scanning).getByText('0 / 1')).toBeInTheDocument()
  })
})

// ── Order drawer ──────────────────────────────────────────────────────────────

function drawerOrder(extra: unknown[] = []): OrderDetail {
  return {
    id: 'order-1', number: '#16443', customerName: null, customerPhone: null,
    address: null, paymentMethod: 'cod', codAmount: null, status: 'new', onHold: false,
    holdReason: null, placedAt: '2026-10-20T13:51:58Z', createdAt: '2026-10-20T13:52:05Z',
    items: [
      ...extra,
      { id: 'item-m', productTitle: 'BLACK LEATHER SET', variantTitle: 'M', sku: 'BLS-M', quantity: 0, imageUrl: null, allocatedPieces: [] },
      { id: 'item-s', productTitle: 'BLACK LEATHER SET', variantTitle: 'S', sku: 'BLS-S', quantity: 1, imageUrl: null, allocatedPieces: [] },
    ],
    shipments: [],
    bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: false,
      fulfillmentKey: 'status.new', fulfillmentTone: 'NEUTRAL',
    },
    physicallyWithCourier: false,
  } as unknown as OrderDetail
}

function renderDrawer(lang: 'en' | 'ar', extra: unknown[] = []) {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  const o = drawerOrder(extra)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/notes') || url.includes('/timeline')) return json([])
    if (url.includes('/orders/')) return json(o)
    return json({})
  }))
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>,
  )
}

describe('order drawer', () => {
  test('a removed line is shown struck through, labelled "Removed in Shopify"; the other keeps ×1', async () => {
    renderDrawer('en')
    const labels = await screen.findAllByTestId('item-removed-in-shopify')
    expect(labels).toHaveLength(1)
    expect(labels[0]).toHaveTextContent('Removed in Shopify')
    const removedRow = labels[0].closest('li')!
    expect(within(removedRow).getByText('BLACK LEATHER SET')).toHaveClass('line-through')
    expect(screen.getByText('×1')).toBeInTheDocument()
    expect(screen.queryByText('×0')).not.toBeInTheDocument()
  })

  test('Arabic label', async () => {
    renderDrawer('ar')
    expect(await screen.findByTestId('item-removed-in-shopify')).toHaveTextContent('أُزيل في Shopify')
  })

  // A line Shopify added after the order left Traced is stored at 0 (never in the parcel) — it is not
  // "removed": its own label, not struck through.
  const ADDED = { id: 'item-x', productTitle: 'BROWN LEATHER SET', variantTitle: 'L', sku: 'BRS-L', quantity: 0,
    imageUrl: null, allocatedPieces: [], addedAfterShipping: true }

  test('a line added after shipping is labelled "Added after shipping — not in this parcel", not struck through', async () => {
    renderDrawer('en', [ADDED])
    const added = await screen.findByTestId('item-added-after-shipping')
    expect(added).toHaveTextContent('Added after shipping — not in this parcel')
    const addedRow = added.closest('li')!
    expect(within(addedRow).getByText('BROWN LEATHER SET')).not.toHaveClass('line-through')
    expect(within(addedRow).queryByTestId('item-removed-in-shopify')).not.toBeInTheDocument()
    const removedLabels = screen.getAllByTestId('item-removed-in-shopify')
    expect(removedLabels).toHaveLength(1)
    expect(within(removedLabels[0].closest('li')!).getByText('BLACK LEATHER SET')).toHaveClass('line-through')
  })

  test('Arabic: added after shipping', async () => {
    renderDrawer('ar', [ADDED])
    expect(await screen.findByTestId('item-added-after-shipping')).toHaveTextContent('أُضيف بعد الشحن — ليس في هذا الطرد')
  })
})
