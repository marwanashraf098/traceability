import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestRow, ReturnRequestStatus } from '../api'

// Returns Step 4d-1 — labels only: the Requests list and drawer show the new lifecycle statuses
// (received, refund_pending, closed) in EN and AR, and the drawer shows why a closed request
// was closed. Fakes match ReturnRequestService.detail(): closeReason / closeNote on the detail.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
  bostaConnected: true,
}

const ROW_STATUSES: Array<[string, ReturnRequestStatus]> = [
  ['RR-RCVD22', 'received'], ['RR-PEND33', 'refund_pending'], ['RR-CLSD44', 'closed'],
]

let detail: ReturnRequestDetail

function rows(): ReturnRequestRow[] {
  return ROW_STATUSES.map(([reference, status], i) => ({
    id: `rr-${i}`, reference, orderNumber: `#10${i}`, customerName: 'Mariam Saleh', itemCount: 1,
    reasonCodes: ['wrong_size'], status, bookingStatus: null, createdAt: new Date().toISOString(),
  }))
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: rows(), total: rows().length })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

function closedDetail(extra: Partial<ReturnRequestDetail> = {}) {
  detail = {
    id: 'rr-2', reference: 'RR-CLSD44', orderId: 'order-1', orderNumber: '#102', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status: 'closed', email: null, note: 'It runs small.',
    createdAt: new Date().toISOString(), deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City', pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: null, bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: '2026-09-24T10:00:00.000+00:00', decidedBy: null, decidedByName: 'Nour', rejectionReason: null,
    returnShipmentId: null, closeReason: 'rest_not_coming', closeNote: 'Customer kept it', closedAt: '2026-09-26T10:00:00.000+00:00',
    closedByName: 'Nour',
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: false }],
    ...extra,
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  closedDetail()
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openRequests() {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
  await user.click(await screen.findByRole('button', { name: /Requests/ }))
  await screen.findByText('RR-CLSD44')
  return user
}

function rowOf(reference: string): HTMLElement {
  return screen.getByText(reference).closest('tr') as HTMLElement
}

describe('Requests list → 4d-1 status pills', () => {
  test('EN: Received, Refund pending, Closed', async () => {
    await openRequests()
    expect(within(rowOf('RR-RCVD22')).getByText('Received')).toBeInTheDocument()
    expect(within(rowOf('RR-PEND33')).getByText('Refund pending')).toBeInTheDocument()
    expect(within(rowOf('RR-CLSD44')).getByText('Closed')).toBeInTheDocument()
  })

  test('AR: تم الاستلام، بانتظار الاسترداد، مغلق', async () => {
    await openRequests()
    await act(async () => { await i18n.changeLanguage('ar') })
    expect(within(rowOf('RR-RCVD22')).getByText('تم الاستلام')).toBeInTheDocument()
    expect(within(rowOf('RR-PEND33')).getByText('بانتظار الاسترداد')).toBeInTheDocument()
    expect(within(rowOf('RR-CLSD44')).getByText('مغلق')).toBeInTheDocument()
  })
})

describe('Drawer → closed request', () => {
  test('EN: Closed pill and why it was closed, with the note', async () => {
    const user = await openRequests()
    await user.click(screen.getByText('RR-CLSD44'))
    const drawer = screen.getByTestId('return-request-drawer')
    await within(drawer).findByText('It runs small.')
    expect(within(drawer).getByText('Closed')).toBeInTheDocument()
    const reason = within(drawer).getByTestId('request-close-reason')
    expect(reason).toHaveTextContent('Why it was closed')
    expect(reason).toHaveTextContent('Nothing came back')
    expect(reason).toHaveTextContent('Customer kept it')
  })

  test('EN: closed without a refund', async () => {
    closedDetail({ closeReason: 'no_refund', closeNote: null })
    const user = await openRequests()
    await user.click(screen.getByText('RR-CLSD44'))
    const drawer = screen.getByTestId('return-request-drawer')
    expect(await within(drawer).findByTestId('request-close-reason')).toHaveTextContent('Closed without a refund')
  })

  test('AR: سبب الإغلاق', async () => {
    const user = await openRequests()
    await user.click(screen.getByText('RR-CLSD44'))
    const drawer = screen.getByTestId('return-request-drawer')
    await within(drawer).findByText('It runs small.')
    await act(async () => { await i18n.changeLanguage('ar') })
    const reason = within(drawer).getByTestId('request-close-reason')
    expect(reason).toHaveTextContent('سبب الإغلاق')
    expect(reason).toHaveTextContent('لم يصل أي شيء')
    expect(within(drawer).getByText('مغلق')).toBeInTheDocument()
  })

  test('a request that is not closed shows no close reason', async () => {
    closedDetail({ status: 'refund_pending', closeReason: null })
    const user = await openRequests()
    await user.click(screen.getByText('RR-CLSD44'))
    const drawer = screen.getByTestId('return-request-drawer')
    await within(drawer).findByText('It runs small.')
    expect(within(drawer).getByText('Refund pending')).toBeInTheDocument()
    expect(within(drawer).queryByTestId('request-close-reason')).not.toBeInTheDocument()
  })
})
