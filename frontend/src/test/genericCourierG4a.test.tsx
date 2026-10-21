import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { render, screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds, { nextStepLabel, reasonLine } from '../pages/ExchangesRefunds'
import ReturnsPortalTab from '../pages/settings/ReturnsPortalTab'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import i18n from '../i18n'
import { resetCapabilitiesCache } from '../capabilities'
import { setAccessToken, clearAccessToken } from '../auth'
import type { PortalSettings, ReturnCase, ReturnRequestDetail } from '../api'
import type { LookupResult, PortalConfig } from '../portal/api'

// Non-Bosta couriers G4a — portal refunds for orders shipped with another courier, in EN and AR:
//   drawer   — "Book the pickup with <carrier>" (carrier + optional tracking) replaces the Bosta booking row and
//              its spinner; the request footer says the merchant books the courier; a Bosta request is unchanged
//   cases    — the "Book with <carrier>" pill
//   portal   — courier-neutral copy for such an order, no Return / Exchange choice, no Bosta wording
//   settings — "Accept returns for orders shipped with other couriers" only for a store with other couriers

const roleMock = vi.hoisted(() => ({ role: 'owner' as 'owner' | 'manager' }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => roleMock.role) }
})

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc', returnLocationName: 'Maadi',
  bostaConnected: true,
}

const OTHER = {
  forwardCarrierName: 'Aramex', carrierName: 'Aramex',
  pickup: { name: 'Mariam Saleh', phone: '01012345678', addressLines: ['5 Tahrir St', 'Cairo'] },
}

let detail: ReturnRequestDetail
let calls: Array<{ method: string; url: string; body?: unknown }>
let manualReply: () => Promise<unknown>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/manual-courier')) return manualReply()
  if (url.includes('/generic-shipments/carriers')) return fakeResponse([{ carrier: 'Aramex', withCourier: 3 }, { carrier: 'Mylerz', withCourier: 1 }])
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: [], total: 0 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/returns-exchanges')) return fakeResponse({ items: [], nextCursor: null, stages: {}, tiles: {} })
  return fakeResponse({})
}

function request(status: ReturnRequestDetail['status'], extra: Partial<ReturnRequestDetail> = {}) {
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#6001', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status, email: null, note: null,
    createdAt: new Date().toISOString(), deliveredAt: '2026-10-18T10:00:00.000+00:00',
    pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: status === 'requested' ? null : 'manual', bookingError: null, bostaTrackingNumber: null,
    bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: status === 'requested' ? null : '2026-10-20T10:00:00.000+00:00', decidedBy: null, decidedByName: null,
    rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }],
    otherCourier: OTHER,
    ...extra,
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  roleMock.role = 'owner'
  calls = []
  manualReply = () => fakeResponse({ outcome: 'LEG_CREATED', carrierName: 'Aramex', trackingNumber: 'RET-1', returnShipmentId: 's-1' })
  request('approved')
  resetCapabilitiesCache()
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => {
  await i18n.changeLanguage('en')
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('Linen Shirt')
  return { user, drawer }
}

describe('Drawer — a request on an order shipped with another courier', () => {
  test('approved + manual: the "Book the pickup with Aramex" form, no Bosta booking row or spinner', async () => {
    const { user, drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('booking-row')).toBeNull()
    expect(within(drawer).queryByTestId('booking-pending')).toBeNull()
    expect(within(drawer).getByTestId('manual-courier-title')).toHaveTextContent('Book the pickup with Aramex')
    const pickup = within(drawer).getByTestId('manual-courier-pickup')
    expect(pickup).toHaveTextContent('Mariam Saleh')
    expect(pickup).toHaveTextContent('5 Tahrir St')
    expect(drawer).not.toHaveTextContent('Bosta')

    await user.type(within(drawer).getByLabelText(/Return tracking number/), ' RET-1 ')
    await user.click(within(drawer).getByRole('button', { name: 'Save courier and tracking number' }))
    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/manual-courier'))
      expect(post?.body).toEqual({ carrier: 'Aramex', trackingNumber: 'RET-1' })
    })
  })

  test('without a tracking number: saves the courier only', async () => {
    manualReply = () => fakeResponse({ outcome: 'CARRIER_SET', carrierName: 'Mylerz', trackingNumber: null, returnShipmentId: null })
    const { user, drawer } = await openDrawer()
    const carrier = within(drawer).getByLabelText('Courier')
    await user.clear(carrier)
    await user.type(carrier, 'Mylerz')
    await user.click(within(drawer).getByRole('button', { name: 'Save courier' }))
    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/manual-courier'))
      expect(post?.body).toEqual({ carrier: 'Mylerz', trackingNumber: null })
    })
  })

  test('a refusal shows the server message (EN / AR)', async () => {
    manualReply = () => fakeResponse({ code: 'SAME_AS_ORDER_WAYBILL',
      message_en: "That's the order's own delivery waybill.", message_ar: 'هذه بوليصة توصيل الطلب نفسه.' }, 409)
    const { user, drawer } = await openDrawer()
    await user.type(within(drawer).getByLabelText(/Return tracking number/), 'AR6001')
    await user.click(within(drawer).getByRole('button', { name: 'Save courier and tracking number' }))
    expect(await within(drawer).findByTestId('manual-courier-error')).toHaveTextContent("That's the order's own delivery waybill.")
  })

  test('booked with a tracking number: read-only line', async () => {
    request('pickup_booked', { returnShipmentId: 's-1', returnTrackingNumber: 'RET-1', linkSource: 'merchant_entered' } as Partial<ReturnRequestDetail>)
    const { drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('manual-courier-form')).toBeNull()
    expect(within(drawer).getByTestId('manual-courier-booked')).toHaveTextContent('Booked with Aramex · RET-1')
  })

  test('requested: the footer says the merchant books Aramex — not the Bosta helper', async () => {
    request('requested')
    const { drawer } = await openDrawer()
    const helper = within(drawer).getByTestId('approve-helper')
    expect(helper).toHaveTextContent("This order went out with Aramex. Approving doesn't book Bosta")
    expect(helper).not.toHaveTextContent('Approving books a Bosta pickup')
  })

  test('Arabic: title and footer', async () => {
    await i18n.changeLanguage('ar')
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('manual-courier-title')).toHaveTextContent('احجز الاستلام مع Aramex')
  })

  test('a Bosta request (no otherCourier) keeps the Bosta booking row', async () => {
    request('approved', { bookingStatus: 'booked', bostaTrackingNumber: '5100000001', otherCourier: undefined })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('booking-row')).toBeInTheDocument()
    expect(within(drawer).queryByTestId('manual-courier')).toBeNull()
  })
})

describe('Returns & exchanges — the "Book with <carrier>" pill', () => {
  const base = { caseType: 'A', kind: 'refund', nextStep: 'booking', reason: { bookingStatus: 'manual', carrierName: 'Aramex' } }
  test('manual → Book with Aramex (both derived steps); Bosta booking unchanged', () => {
    const t = i18n.getFixedT('en')
    expect(nextStepLabel(t, base as unknown as ReturnCase)).toBe('Book with Aramex')
    expect(nextStepLabel(t, { ...base, nextStep: 'awaiting_items' } as unknown as ReturnCase)).toBe('Book with Aramex')
    expect(reasonLine(t, base as unknown as ReturnCase)).toBe('Book the pickup with the courier, then add it to the request.')
    expect(nextStepLabel(t, { ...base, reason: { bookingStatus: null } } as unknown as ReturnCase))
      .not.toContain('Book with')
    expect(nextStepLabel(i18n.getFixedT('ar'), base as unknown as ReturnCase)).toBe('احجز مع Aramex')
  })
})

describe('Settings → Returns portal — accept returns for other couriers', () => {
  afterEach(() => clearAccessToken())

  function stub(setting: { available: boolean; enabled: boolean } | null, genericCourier: boolean) {
    resetCapabilitiesCache()
    setAccessToken(`h.${btoa(JSON.stringify({ role: roleMock.role, sub: 'u1', tenant: 't1' }))}.s`)
    stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
      const method = (opts.method ?? 'GET').toUpperCase()
      calls.push({ method, url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
      if (url.includes('/portal-settings/other-couriers')) {
        return method === 'PUT' ? fakeResponse({ available: true, enabled: true, since: '2026-10-21T10:00:00Z' })
          : fakeResponse({ ...setting, since: null })
      }
      if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
      if (url.includes('/variants')) return fakeResponse({ items: [], total: 0 })
      return fakeResponse({})
    }), { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier } })
  }

  test('a store with other couriers: the owner switches it on (PUT)', async () => {
    stub({ available: true, enabled: false }, true)
    const user = userEvent.setup()
    renderWithProviders(<I18nextProvider i18n={i18n}><ReturnsPortalTab /></I18nextProvider>)
    const row = await screen.findByTestId('other-couriers-row')
    expect(row).toHaveTextContent('Accept returns for orders shipped with other couriers')
    await user.click(within(row).getByRole('switch'))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.includes('/other-couriers')
      && JSON.stringify(c.body) === '{"enabled":true}')).toBe(true))
  })

  test('a manager sees it read-only', async () => {
    roleMock.role = 'manager'
    stub({ available: true, enabled: false }, true)
    renderWithProviders(<I18nextProvider i18n={i18n}><ReturnsPortalTab /></I18nextProvider>)
    const row = await screen.findByTestId('other-couriers-row')
    expect(within(row).getByRole('switch')).toBeDisabled()
    expect(row).toHaveTextContent('Only the owner can change this.')
  })

  test('a Bosta-only store: no row and no call', async () => {
    stub(null, false)
    renderWithProviders(<I18nextProvider i18n={i18n}><ReturnsPortalTab /></I18nextProvider>)
    await screen.findByTestId('portal-url')
    expect(screen.queryByTestId('other-couriers-row')).toBeNull()
    expect(calls.some(c => c.url.includes('/other-couriers'))).toBe(false)
  })
})

// ── Customer portal ───────────────────────────────────────────────────────────

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 14, reasonCodes: ['wrong_size'], logoUrl: null, brandColor: '#1F5C4A',
  policyText: null, autoApprove: true, pickupBooking: true, exchangesEnabled: true, otherCouriers: true,
}

const LOOKUP: LookupResult = {
  token: 'tok', orderNumber: '#6001', deliveredAt: '2026-10-18T10:00:00Z',
  lines: [{ variantId: 'v1', productTitle: 'Linen Shirt', variantTitle: null, imageUrl: null,
    deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false }],
  pickup: null,
  otherCourier: { carrierName: 'Aramex' },
}

function portalBackend(config: PortalConfig, lookup: LookupResult) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const respond = (status: number, body?: unknown) => Promise.resolve({ ok: status < 300, status,
      headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
      json: async () => structuredClone(body), text: async () => JSON.stringify(body) })
    if (url.endsWith('/config')) return respond(200, config)
    if (url.endsWith('/lookup')) return respond(200, lookup)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'approved' })
    return respond(404)
  }))
}

async function throughPortal(lang: PortalLang) {
  localStorage.clear()
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { level: 1 })
  const note = document.querySelector('.pp-note')?.textContent ?? ''
  await user.type(document.getElementById('pp-order')!, '#6001')
  await user.type(document.getElementById('pp-phone')!, '01012345678')
  await user.click(document.querySelector('form button[type=submit]') as HTMLElement)
  await screen.findAllByTestId('portal-line')
  const modeChoice = document.querySelector('input[name="pp-mode"]')
  const line = screen.getAllByTestId('portal-line')[0]
  await user.click(within(line).getAllByRole('button')[1])
  await user.selectOptions(within(line).getByRole('combobox'), 'wrong_size')
  await user.click(document.querySelector('.pp-bar button') as HTMLElement)
  const pickupCard = await screen.findByText(lang === 'ar' ? /يرتّب Nour Studio استلامًا مع Aramex/ : /Nour Studio arranges a pickup with Aramex/)
  const addressChoice = screen.queryByTestId('address-choice')
  await user.click(await screen.findByRole('button', { name: lang === 'ar' ? 'إرسال طلب الإرجاع' : 'Send return request' }))
  await screen.findByTestId('reference')
  return { note, modeChoice, pickupCard, addressChoice, page: document.body.textContent ?? '' }
}

describe('Customer portal — an order shipped with another courier', () => {
  afterEach(() => { document.documentElement.dir = 'ltr'; document.documentElement.lang = 'en' })

  test('EN: neutral P1 note, no Return / Exchange choice, P3 names the courier, no address choice, no Bosta anywhere', async () => {
    portalBackend(CONFIG, LOOKUP)
    const r = await throughPortal('en')
    expect(r.note).toContain('The store will arrange a courier to collect the item from your address.')
    expect(r.modeChoice).toBeNull()
    expect(r.pickupCard).toHaveTextContent('Nour Studio arranges a pickup with Aramex from the address this order was delivered to.')
    expect(r.addressChoice).toBeNull()
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('Your return is approved. A courier will contact you to collect it.')
    expect(document.body.textContent).not.toContain('Bosta')
  })

  test('AR: the same, in Arabic, with no بوسطة', async () => {
    portalBackend(CONFIG, LOOKUP)
    await throughPortal('ar')
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('تمت الموافقة على الإرجاع. سيتواصل معك مندوب لاستلام المنتج.')
    expect(document.body.textContent).not.toContain('بوسطة')
  })

  test('a Bosta-only store (no otherCouriers): the P1 note keeps the Bosta wording', async () => {
    portalBackend({ ...CONFIG, otherCouriers: undefined }, LOOKUP)
    render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
    expect(await screen.findByText(/After approval, a Bosta courier collects the item from the area you choose\./)).toBeInTheDocument()
  })
})
