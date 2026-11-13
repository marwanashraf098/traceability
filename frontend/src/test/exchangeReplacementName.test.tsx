import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestItem } from '../api'
import i18n from '../i18n'

// 2026-11-13 — an exchange's replacement is always named product + variant in the request drawer. A replacement
// from ANOTHER product (F3 / Lookup) carries replacementProductTitle / ImageUrl / Sku (ReturnRequestService);
// a same-product swap carries none of them and is named with the returned item's own product.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
}

const SAME: ReturnRequestItem = {
  id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-white-m', productTitle: 'Linen Shirt',
  variantTitle: 'White / M', imageUrl: 'https://cdn.shopify.com/linen.jpg', reasonCode: 'wrong_size', active: true,
  replacementVariantId: 'v-white-l', replacementVariantTitle: 'White / L', replacementAvailable: 3, replacementInStock: true,
}

const OTHER: ReturnRequestItem = {
  ...SAME, replacementVariantId: 'v-pants-bl', replacementVariantTitle: 'Black / L',
  replacementProductTitle: 'Flipped Pants', replacementImageUrl: 'https://cdn.shopify.com/pants.jpg', replacementSku: 'PANTS-BL',
}

let detail: ReturnRequestDetail

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/returns-exchanges')) return fakeResponse({ items: [], nextCursor: null })
  return fakeResponse({})
}

function withItem(item: ReturnRequestItem, status: ReturnRequestDetail['status'] = 'requested'): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'exchange', status, email: null, note: null,
    createdAt: new Date().toISOString(), deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City',
    pickupCityId: null, pickupCityName: null, pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
    decidedAt: null, decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
    refundFallbackOk: true, items: [item],
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openDrawer(testId: string) {
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = await screen.findByTestId('return-request-drawer')
  await within(drawer).findByTestId(testId)
  return drawer
}

describe('Waiting for approval — the going-out card', () => {
  test('another product: its product + variant, its own image and SKU', async () => {
    detail = withItem(OTHER)
    const drawer = await openDrawer('exchange-swap')
    const out = within(drawer).getByTestId('exchange-going-out')
    expect(within(out).getByTestId('exchange-going-out-name')).toHaveTextContent('Flipped Pants · Black / L')
    expect(within(out).getByTestId('exchange-going-out-sku')).toHaveTextContent('SKU PANTS-BL')
    expect(within(out).getByRole('img', { name: 'Flipped Pants · Black / L' }).getAttribute('src')).toContain('pants.jpg')
    // What comes back is still the customer's own item.
    expect(within(drawer).getByTestId('exchange-coming-back')).toHaveTextContent('White / M')
  })

  test('same product: named with the returned product, no SKU line', async () => {
    detail = withItem(SAME)
    const drawer = await openDrawer('exchange-swap')
    const out = within(drawer).getByTestId('exchange-going-out')
    expect(within(out).getByTestId('exchange-going-out-name')).toHaveTextContent('Linen Shirt · White / L')
    expect(within(out).queryByTestId('exchange-going-out-sku')).toBeNull()
    expect(within(out).getByRole('img', { name: 'Linen Shirt · White / L' }).getAttribute('src')).toContain('linen.jpg')
  })

  test('sold out: the title names the other product in full', async () => {
    detail = withItem({ ...OTHER, replacementAvailable: 0, replacementInStock: false })
    const drawer = await openDrawer('exchange-sold-out')
    expect(within(drawer).getByTestId('exchange-sold-out'))
      .toHaveTextContent('Flipped Pants · Black / L sold out since the customer asked')
  })

  test('Arabic: the same full name, the Arabic SKU label', async () => {
    await i18n.changeLanguage('ar')
    detail = withItem(OTHER)
    const drawer = await openDrawer('exchange-swap')
    const out = within(drawer).getByTestId('exchange-going-out')
    expect(within(out).getByTestId('exchange-going-out-name')).toHaveTextContent('Flipped Pants · Black / L')
    expect(within(out).getByTestId('exchange-going-out-sku'))
      .toHaveTextContent(`${i18n.t('exchangesRefunds.requests.exchange.sku')} PANTS-BL`)
    expect(within(drawer).getByTestId('exchange-going-out')).toHaveTextContent(i18n.t('exchangesRefunds.requests.exchange.goingOut'))
  })
})

describe('Approved — the summary line', () => {
  test('another product: "Linen Shirt · White / M → Flipped Pants · Black / L"', async () => {
    detail = withItem(OTHER, 'approved')
    const drawer = await openDrawer('exchange-summary-card')
    expect(within(drawer).getByTestId('exchange-summary-replacement')).toHaveTextContent('Flipped Pants · Black / L')
    expect(within(drawer).getByTestId('exchange-summary-card')).toHaveTextContent('Linen Shirt · White / M → Flipped Pants · Black / L')
  })

  test('same product: keeps "→ White / L"', async () => {
    detail = withItem(SAME, 'approved')
    const drawer = await openDrawer('exchange-summary-card')
    expect(within(drawer).getByTestId('exchange-summary-replacement').textContent).toBe('White / L')
  })
})
