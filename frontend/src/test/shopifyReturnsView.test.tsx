import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { renderWithProviders, screen, within, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import i18n from '../i18n'
import OrderDrawer from '../components/OrderDrawer'
import { ShopifyStateChip } from '../components/ShopifyReturnCard'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { OrderDetail, ReturnCase, ShopifyReturnView } from '../api'

/**
 * Shopify-native returns S2 — read-only display of returns made in Shopify.
 *   card     the order drawer's "From Shopify" card: going out / coming back (reason + notes), On hold · Awaiting
 *            payment, In Traced, Restocked in Shopify ×N; the exchange order line "Exchange item for #…-R1"
 *   chips    every Shopify state's chip label
 *   filter   source chips (All / Traced portal / From Shopify) with counts; From Shopify → the mockup's columns
 *   dup      "Duplicate return" on both the Traced request and the Shopify return
 *   note     owners see the not-granted note, managers don't
 *   none     no Shopify returns → no chips, no source labels (page as before)
 *   ar       Arabic, RTL
 */

let role = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

const RET: ShopifyReturnView = {
  id: 'sr1', orderId: 'order-1', orderNumber: '#1006', name: '#1006-R1', status: 'OPEN',
  requestedAt: '2026-11-04T10:00:00Z', closedAt: null, linesTruncated: false, duplicate: false, duplicateOf: [],
  comingBack: [{ variantId: 'v1', productTitle: 'Henley Long Sleeve Tee', variantTitle: 'Black / XL', sku: 'HLST-BLK-XL',
    imageUrl: null, quantity: 1, reason: 'size-too-large', reasonNote: 'Too big on the shoulders', customerNote: null }],
  goingOut: [{ variantId: 'v2', productTitle: 'Henley Long Sleeve Tee', variantTitle: 'White / L', sku: 'HLST-WH-L',
    imageUrl: null, quantity: 1 }],
  restockedInShopify: 1, state: 'on_hold', holdReasons: ['AWAITING_PAYMENT'], kind: 'exchange',
  inTraced: { state: 'expected', units: 1, received: 0, pending: 0, sellable: 0, damaged: 0 },
}

function order(over: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#1006', customerName: 'Ahmed M.', customerPhone: null, address: null, paymentMethod: 'cod',
    codAmount: 850, status: 'delivered', onHold: false, holdReason: null, placedAt: null, createdAt: '2026-10-20T10:00:00Z',
    items: [
      { id: 'i1', productTitle: 'Henley Long Sleeve Tee', variantTitle: 'Black / XL', sku: 'HLST-BLK-XL', quantity: 1,
        imageUrl: null, allocatedPieces: [] },
      { id: 'i2', productTitle: 'Henley Long Sleeve Tee', variantTitle: 'White / L', sku: 'HLST-WH-L', quantity: 0,
        imageUrl: null, allocatedPieces: [], addedAfterShipping: true, exchangeFor: '#1006-R1' },
    ],
    shipments: [], bostaLinkStatus: null, notTracedAt: null, isExchange: false,
    derivedStatus: {
      primaryKey: 'status.delivered', tone: 'SUCCESS', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: true,
      fulfillmentKey: 'status.delivered', fulfillmentTone: 'SUCCESS',
    } as unknown as OrderDetail['derivedStatus'],
    shopifyOrderUrl: null, physicallyWithCourier: false, shippingBadge: null, shopifyReturns: [RET],
    ...over,
  }
}

function renderDrawer(lang: 'en' | 'ar', o: OrderDetail) {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/notes') || url.includes('/timeline')) return json([])
    if (url.includes('/orders/')) return json(o)
    return json({})
  }))
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <div dir={lang === 'ar' ? 'rtl' : 'ltr'}><OrderDrawer orderId="order-1" onClose={() => {}} /></div>
      </I18nextProvider>
    </MemoryRouter>,
  )
}

afterEach(async () => { vi.unstubAllGlobals(); role = 'owner'; await i18n.changeLanguage('en') })

describe('order drawer — From Shopify card', () => {
  test('card: going out, coming back with reason + note, on hold · awaiting payment, in Traced, restocked', async () => {
    renderDrawer('en', order())
    const card = await screen.findByTestId('shopify-return-card')
    expect(within(card).getByRole('heading')).toHaveTextContent('Exchange #1006-R1')
    expect(within(card).getByTestId('source-chip')).toHaveTextContent('From Shopify')
    expect(within(card).getByTestId('shopify-state')).toHaveTextContent('On hold · Awaiting payment')
    expect(within(card).getByTestId('going-out')).toHaveTextContent('White / L')
    const back = within(card).getByTestId('coming-back')
    expect(back).toHaveTextContent('Black / XL')
    expect(within(back).getByTestId('return-reason')).toHaveTextContent('Reason: Size too large')
    expect(within(back).getByTestId('return-note')).toHaveTextContent('Too big on the shoulders')
    expect(within(card).getByTestId('in-traced')).toHaveTextContent('In Traced: Expected back · not scanned yet')
    expect(within(card).getByTestId('restocked-in-shopify')).toHaveTextContent('Restocked in Shopify ×1')
    expect(within(card).queryByTestId('duplicate-chip')).not.toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()   // read-only: no actions
  })

  test('the exchange order line says "Exchange item for #1006-R1" instead of "Added after shipping"', async () => {
    renderDrawer('en', order())
    const label = await screen.findByTestId('item-exchange-for')
    expect(label).toHaveTextContent('Exchange item for #1006-R1')
    expect(screen.queryByTestId('item-added-after-shipping')).not.toBeInTheDocument()
    expect(within(label.closest('li')!).getByText('Henley Long Sleeve Tee')).not.toHaveClass('line-through')
  })

  test('a plain return (no exchange) has no going-out section; nothing restocked → no line', async () => {
    renderDrawer('en', order({ shopifyReturns: [{ ...RET, kind: 'return', goingOut: [], restockedInShopify: 0,
      state: 'requested', holdReasons: [] }] }))
    const card = await screen.findByTestId('shopify-return-card')
    expect(within(card).getByRole('heading')).toHaveTextContent('Return #1006-R1')
    expect(within(card).queryByTestId('going-out')).not.toBeInTheDocument()
    expect(within(card).queryByTestId('restocked-in-shopify')).not.toBeInTheDocument()
    expect(within(card).getByTestId('shopify-state')).toHaveTextContent('Requested')
  })

  test('duplicate: chip and the Traced request it duplicates', async () => {
    renderDrawer('en', order({ shopifyReturns: [{ ...RET, duplicate: true, duplicateOf: ['RR-K3P9QD'] }] }))
    const card = await screen.findByTestId('shopify-return-card')
    expect(within(card).getByTestId('duplicate-chip')).toHaveTextContent('Duplicate return')
    expect(within(card).getByTestId('duplicate-of')).toHaveTextContent('Also requested in Traced: RR-K3P9QD')
  })

  test('no Shopify returns → no card', async () => {
    const o = order({ shopifyReturns: undefined })
    o.items = [o.items[0]]
    renderDrawer('en', o)
    await screen.findByText('Ahmed M.')
    expect(screen.queryByTestId('shopify-returns')).not.toBeInTheDocument()
  })

  test('Arabic (RTL): card and exchange line', async () => {
    renderDrawer('ar', order())
    const card = await screen.findByTestId('shopify-return-card')
    expect(within(card).getByTestId('source-chip')).toHaveTextContent('من Shopify')
    expect(within(card).getByTestId('shopify-state')).toHaveTextContent('معلّق · بانتظار الدفع')
    expect(within(card).getByTestId('return-reason')).toHaveTextContent('السبب: المقاس كبير')
    expect(within(card).getByTestId('in-traced')).toHaveTextContent('في Traced')
    expect(card.closest('[dir="rtl"]')).not.toBeNull()
    expect(await screen.findByTestId('item-exchange-for')).toHaveTextContent('قطعة استبدال للطلب #1006-R1')
  })
})

describe('S3b — kept aside', () => {
  const kept = (state: 'kept_aside' | 'short' | 'ended', shortBy = 0) => order({ shopifyReturns: [{ ...RET,
    keptAside: { orderId: 'shadow-1', number: '#1006-X1', state, units: state === 'ended' ? 0 : 1, shortBy, endReason: null } }] })

  test('"#1006-X1 · kept aside", "short by N" and "no longer kept aside"; absent without a shadow', async () => {
    const { unmount } = renderDrawer('en', kept('kept_aside'))
    expect(await screen.findByTestId('kept-aside')).toHaveTextContent('#1006-X1 · kept aside')
    unmount()
    const r2 = renderDrawer('en', kept('short', 2))
    expect(await screen.findByTestId('kept-aside')).toHaveTextContent('#1006-X1 · short by 2')
    r2.unmount()
    const r3 = renderDrawer('en', kept('ended'))
    expect(await screen.findByTestId('kept-aside')).toHaveTextContent('#1006-X1 · no longer kept aside')
    r3.unmount()
    renderDrawer('en', order())
    await screen.findByTestId('shopify-return-card')
    expect(screen.queryByTestId('kept-aside')).not.toBeInTheDocument()
  })

  test('Arabic: kept aside / short', async () => {
    const r = renderDrawer('ar', kept('kept_aside'))
    expect(await screen.findByTestId('kept-aside')).toHaveTextContent('#1006-X1 · محجوز جانبًا')
    r.unmount()
    renderDrawer('ar', kept('short', 1))
    expect(await screen.findByTestId('kept-aside')).toHaveTextContent('#1006-X1 · ناقص 1')
  })
})

describe('Shopify state chips', () => {
  const CASES: Array<[ShopifyReturnView['state'], string[], string]> = [
    ['requested', [], 'Requested'], ['open', [], 'Open'], ['on_hold', ['AWAITING_PAYMENT'], 'On hold · Awaiting payment'],
    ['on_hold', [], 'On hold'], ['released', [], 'Released'], ['shipped', [], 'Shipped'], ['closed', [], 'Closed'],
    ['declined', [], 'Declined'], ['cancelled', [], 'Cancelled'], ['on_hold', ['SOME_NEW_REASON'], 'On hold · Some new reason'],
  ]
  test.each(CASES)('%s %j → %s', (state, holds, label) => {
    renderWithProviders(<ShopifyStateChip state={state} holdReasons={holds} />)
    expect(screen.getByTestId('shopify-state')).toHaveTextContent(label)
  })
})

// ── Returns page ──────────────────────────────────────────────────────────────

function kase(id: string, over: Partial<ReturnCase> = {}): ReturnCase {
  return {
    caseType: 'A', id, kind: 'refund', reference: `RR-${id.toUpperCase()}`, source: 'returns_page',
    customerName: 'Mariam Saleh', orderNumber: '#1047', stage: 'in_progress', nextStep: 'on_the_way', tone: 'moving',
    overdueDays: null, status: 'pickup_booked',
    reason: {
      bookingStatus: null, bookingError: null, itemsCount: 1, arrivedCount: 0, awaitingCount: 1, candidateCount: 0,
      candidateReferences: null, trackingNumber: null, refundTotal: '0.00', currency: 'EGP', closeReason: null,
      inspectionState: null,
    },
    alerts: [], itemsSummary: 'Knit Sweater Grey / M', itemsSummaryAr: 'Knit Sweater Grey / M', notScanned: false,
    updatedAt: new Date().toISOString(), legStatus: null,
    target: { requestId: id, exchangeId: null, shipmentId: null },
    ...over,
  }
}

const SHOPIFY_ROW = kase('sr1', {
  caseType: 'S', kind: 'exchange', reference: '#1006-R1', source: 'shopify', orderNumber: '#1006',
  nextStep: 'shopify_open', status: 'OPEN', duplicate: true,
  target: { requestId: null, exchangeId: null, shipmentId: null, orderId: 'order-1' },
  shopify: { ...RET, duplicate: true, duplicateOf: ['RR-A1'] },
})
const PORTAL_ROW = kase('a1', { orderNumber: '#1006', duplicate: true })

let rows: ReturnCase[]
let withSources: boolean
let connections: unknown
let calls: string[]

function backend(url: string) {
  calls.push(url)
  const u = new URL(url, 'http://x')
  if (u.pathname.endsWith('/returns-exchanges/counts')) {
    const body: Record<string, unknown> = {
      stages: { all: rows.length, to_do: 0, in_progress: rows.length, done: 0 },
      tiles: { toApprove: 0, replacementToChoose: 0, toLinkOrder: 0, refundToRecord: 0, bookingProblem: 0 },
    }
    if (withSources) body.sources = { traced: rows.filter(r => r.caseType !== 'S').length, shopify: rows.filter(r => r.caseType === 'S').length }
    return json(body)
  }
  if (u.pathname.endsWith('/returns-exchanges')) {
    const src = u.searchParams.get('source')
    const items = rows.filter(r => !src || (src === 'shopify') === (r.caseType === 'S'))
    return json({ items, nextCursor: null })
  }
  if (u.pathname.endsWith('/connections')) return json(connections)
  if (url.includes('/notes') || url.includes('/timeline')) return json([])
  if (url.includes('/orders/')) return json(order())
  return json({})
}

describe('Returns page — Shopify returns', () => {
  beforeEach(() => {
    rows = [PORTAL_ROW, SHOPIFY_ROW]
    withSources = true
    connections = { shopify: { connected: true } }
    calls = []
    stubFetchWithShellDefaults(vi.fn(backend))
  })

  test('source chips with counts; Shopify row: chip, state, In Traced, lines; duplicate on both rows', async () => {
    renderWithProviders(<ExchangesRefunds />)
    const filter = await screen.findByTestId('source-filter')
    expect(within(filter).getByTestId('source-all')).toHaveTextContent('All · 2')
    expect(within(filter).getByTestId('source-traced')).toHaveTextContent('Traced portal · 1')
    expect(within(filter).getByTestId('source-shopify')).toHaveTextContent('From Shopify · 1')

    const rowEls = await screen.findAllByTestId('case-row')
    expect(rowEls.map(r => r.getAttribute('data-case-id'))).toEqual(['a1', 'sr1'])   // Traced request listed first
    const portal = rowEls[0], shopify = rowEls[1]
    expect(within(portal).getByTestId('source-chip')).toHaveTextContent('Traced portal')
    expect(within(portal).getByTestId('duplicate-chip')).toHaveTextContent('Duplicate return')
    expect(within(shopify).getByTestId('source-chip')).toHaveTextContent('From Shopify')
    expect(within(shopify).getByTestId('duplicate-chip')).toBeInTheDocument()
    expect(within(shopify).getByTestId('shopify-state')).toHaveTextContent('On hold · Awaiting payment')
    expect(within(shopify).getByTestId('case-in-traced')).toHaveTextContent('Expected back')
    expect(within(shopify).getByTestId('case-items')).toHaveTextContent('↩ Henley Long Sleeve Tee · Black / XL coming back')
    expect(within(shopify).getByTestId('case-items')).toHaveTextContent('↪ Henley Long Sleeve Tee · White / L going out')
    expect(screen.getByTestId('shopify-read-only-note')).toBeInTheDocument()
  })

  test('From Shopify filter → source=shopify, the mockup columns', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExchangesRefunds />)
    await user.click(await screen.findByTestId('source-shopify'))
    await waitFor(() => expect(calls.some(c => c.includes('/returns-exchanges?') && c.includes('source=shopify'))).toBe(true))
    expect(calls.some(c => c.includes('/returns-exchanges/counts') && c.includes('source=shopify'))).toBe(true)
    const table = screen.getByTestId('cases-table')
    const headers = within(table).getAllByRole('columnheader').map(h => h.textContent)
    expect(headers).toEqual(['Request', 'Source', 'Items', 'Shopify status', 'In Traced'])
    await waitFor(() => expect(screen.getAllByTestId('case-row')).toHaveLength(1))
    const row = screen.getByTestId('case-row')
    expect(row).toHaveTextContent('#1006-R1')
    expect(within(row).getByTestId('case-in-traced')).toHaveTextContent('Expected back · not scanned yet')
    expect(screen.getByTestId('source-shopify')).toHaveAttribute('aria-pressed', 'true')
  })

  test('a Shopify row opens its order (read-only card)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExchangesRefunds />)
    const rowEls = await screen.findAllByTestId('case-row')
    await user.click(rowEls[1])
    expect(await screen.findByTestId('shopify-return-card')).toBeInTheDocument()
    expect(calls.some(c => c.endsWith('/orders/order-1'))).toBe(true)
  })

  test('owner: the not-granted note', async () => {
    connections = { shopify: { connected: true, newPermissions: { openAppUrl: 'https://admin.shopify.com/x' } } }
    renderWithProviders(<ExchangesRefunds />)
    expect(await screen.findByTestId('shopify-permissions-note'))
      .toHaveTextContent("Approve Traced's new Shopify permissions to see returns made in Shopify")
  })

  test('manager: no note, /connections not even asked', async () => {
    role = 'manager'
    connections = { shopify: { connected: true, newPermissions: { openAppUrl: 'https://admin.shopify.com/x' } } }
    renderWithProviders(<ExchangesRefunds />)
    await screen.findAllByTestId('case-row')
    expect(screen.queryByTestId('shopify-permissions-note')).not.toBeInTheDocument()
    expect(calls.some(c => c.includes('/connections'))).toBe(false)
  })

  test('granted (no newPermissions) → no note', async () => {
    renderWithProviders(<ExchangesRefunds />)
    await screen.findAllByTestId('case-row')
    await waitFor(() => expect(calls.some(c => c.includes('/connections'))).toBe(true))
    expect(screen.queryByTestId('shopify-permissions-note')).not.toBeInTheDocument()
  })

  test('no Shopify returns → no source chips, no source labels (as before)', async () => {
    rows = [kase('a1')]
    withSources = false
    renderWithProviders(<ExchangesRefunds />)
    const row = await screen.findByTestId('case-row')
    expect(screen.queryByTestId('source-filter')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('source-chip')).not.toBeInTheDocument()
    expect(row).toHaveTextContent('Returns page')
    expect(screen.queryByTestId('shopify-read-only-note')).not.toBeInTheDocument()
  })

  test('Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderWithProviders(<I18nextProvider i18n={i18n}><div dir="rtl"><ExchangesRefunds /></div></I18nextProvider>)
    const filter = await screen.findByTestId('source-filter')
    expect(within(filter).getByTestId('source-shopify')).toHaveTextContent(`من Shopify · ${(1).toLocaleString('ar')}`)
    const rowEls = await screen.findAllByTestId('case-row')
    expect(within(rowEls[1]).getByTestId('shopify-state')).toHaveTextContent('معلّق · بانتظار الدفع')
    expect(within(rowEls[1]).getByTestId('duplicate-chip')).toHaveTextContent('مرتجع مكرر')
    expect(within(rowEls[1]).getByTestId('case-in-traced')).toHaveTextContent('متوقع رجوعه')
  })
})
