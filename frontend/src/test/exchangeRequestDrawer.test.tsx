import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'
import i18n from '../i18n'

// Step 5b — exchange requests in the merchant app: the list's Exchange pill, drawer X4 (in stock)
// and X6 (sold out). Fakes match ReturnRequestService: list rows carry `type`; detail carries
// `type`, `refundFallbackOk` and per item replacementVariantId / Title / Available / InStock;
// POST /approve → 204, or 409 REPLACEMENT_OUT_OF_STOCK; POST /switch-to-refund → 204.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const ROW: ReturnRequestRow = {
  id: 'rr-1', reference: 'RR-7K3F9M', orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 1,
  reasonCodes: ['wrong_size'], status: 'requested', createdAt: new Date().toISOString(), type: 'exchange',
}

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
}

let detail: ReturnRequestDetail
let approveStatus: number
let soldOutAfterApprove: boolean
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function soldOut(d: ReturnRequestDetail): ReturnRequestDetail {
  return { ...d, items: d.items.map(i => ({ ...i, replacementAvailable: 0, replacementInStock: false })) }
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/approve') && method === 'POST') {
    if (approveStatus === 409) {
      if (soldOutAfterApprove) detail = soldOut(detail)
      return fakeResponse({ message: 'REPLACEMENT_OUT_OF_STOCK: the replacement is out of stock.' }, 409)
    }
    detail = { ...detail, status: 'approved' }
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/switch-to-refund') && method === 'POST') {
    detail = { ...detail, type: 'refund', status: 'approved', items: detail.items.map(i => ({ ...i, replacementVariantId: null })) }
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/reject') && method === 'POST') {
    detail = { ...detail, status: 'rejected', rejectionReason: body.reason }
    return fakeResponse(null, 204)
  }
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: [{ ...ROW, status: detail.status, type: detail.type }], total: 1 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'exchange', status: 'requested', email: null, note: 'Size M is too tight.',
    createdAt: ROW.createdAt, deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City',
    pickupCityId: null, pickupCityName: null, pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
    decidedAt: null, decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
    refundFallbackOk: true,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-white-m', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true,
      replacementVariantId: 'v-white-l', replacementVariantTitle: 'White / L', replacementAvailable: 3, replacementInStock: true }],
  }
  approveStatus = 204
  soldOutAfterApprove = false
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openDrawer() {
  const user = userEvent.setup()
  // Step 2: the Requests tab is gone — the drawer opens through the alerts' deep link.
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = await screen.findByTestId('return-request-drawer')
  await within(drawer).findByTestId('exchange-swap')
  return { user, drawer }
}

describe('Drawer X4 — in stock', () => {
  test('pill, coming back → going out with live stock, note, fallback line, approve copy', async () => {
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('exchange-pill')).toHaveTextContent('Exchange')
    expect(within(drawer).getByTestId('exchange-coming-back')).toHaveTextContent('Coming back')
    expect(within(drawer).getByTestId('exchange-coming-back')).toHaveTextContent('White / M')
    expect(within(drawer).getByTestId('exchange-coming-back')).toHaveTextContent('P000245')
    expect(within(drawer).getByTestId('exchange-going-out')).toHaveTextContent('White / L')
    expect(within(drawer).getByTestId('exchange-stock')).toHaveTextContent('3 in stock')
    expect(within(drawer).getByText('Size M is too tight.')).toBeInTheDocument()
    expect(within(drawer).getByTestId('exchange-fallback'))
      .toHaveTextContent('The customer agreed to a refund if the new size sells out first.')
    expect(within(drawer).getByTestId('exchange-approve-helper'))
      .toHaveTextContent('Approving books one Bosta exchange trip and adds the new size to Pick & Pack.')
    expect(within(drawer).queryByRole('button', { name: 'Switch to refund and approve' })).toBeNull()
  })

  test('Approve exchange posts approve and the drawer leaves the requested state', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Approve exchange' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/rr-1/approve'))).toBe(true))
    await waitFor(() => expect(within(drawer).queryByRole('button', { name: 'Approve exchange' })).toBeNull())
  })

  test('Reject opens the reason form and posts the reason', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Reject' }))
    await user.type(within(drawer).getByLabelText(/Reason for rejecting/), 'Out of season')
    await user.click(within(drawer).getByRole('button', { name: 'Reject request' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/rr-1/reject'))?.body).toEqual({ reason: 'Out of season' }))
  })
})

describe('Drawer X6 — sold out', () => {
  test('approve answers 409 → the drawer reloads into X6 with Switch to refund and approve', async () => {
    approveStatus = 409
    soldOutAfterApprove = true
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Approve exchange' }))

    await within(drawer).findByTestId('exchange-sold-out')
    expect(within(drawer).getByTestId('exchange-stock')).toHaveTextContent('Out of stock')
    expect(within(drawer).getByTestId('exchange-sold-out')).toHaveTextContent('White / L sold out since the customer asked')
    expect(within(drawer).getByTestId('exchange-sold-out')).toHaveTextContent('Bosta collects the White / M')
    expect(within(drawer).queryByRole('button', { name: 'Approve exchange' })).toBeNull()

    await user.click(within(drawer).getByRole('button', { name: 'Switch to refund and approve' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/rr-1/switch-to-refund'))).toBe(true))
    await waitFor(() => expect(within(drawer).queryByTestId('exchange-sold-out')).toBeNull())
  })

  test('without the customer\'s refund agreement only Reject is offered', async () => {
    detail = soldOut({ ...detail, refundFallbackOk: false })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('exchange-sold-out'))
      .toHaveTextContent("The customer didn't agree to a refund instead")
    expect(within(drawer).queryByRole('button', { name: 'Switch to refund and approve' })).toBeNull()
    expect(within(drawer).queryByRole('button', { name: 'Approve exchange' })).toBeNull()
    expect(within(drawer).getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })
})

describe('Arabic', () => {
  test('pill, swap labels and actions', async () => {
    await i18n.changeLanguage('ar')
    // Step 2: the Requests tab is gone — the drawer opens through the alerts' deep link.
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
      { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
    const drawer = await screen.findByTestId('return-request-drawer')
    await within(drawer).findByTestId('exchange-swap')
    expect(within(drawer).getByTestId('exchange-coming-back')).toHaveTextContent('يرجع')
    expect(within(drawer).getByTestId('exchange-going-out')).toHaveTextContent('يخرج')
    expect(within(drawer).getByRole('button', { name: 'الموافقة على الاستبدال' })).toBeInTheDocument()
  })
})
