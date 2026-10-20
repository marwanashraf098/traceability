import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import Orders from '../pages/Orders'
import PickupSessions from '../pages/PickupSessions'
import { LegStatusBadge } from '../components/ui'

// Non-Bosta couriers G3 — the Orders list's "With <carrier>" filter + Mark delivered, the Pickups screen's one
// courier per session, "Delivered · {carrier}", in EN and AR/RTL. A store that doesn't ship with other couriers
// sees none of it (no filter, no carriers call).

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

function fakeJwt(role: string) {
  return `h.${btoa(JSON.stringify({ role, sub: 'u1', tenant: 't1' }))}.s`
}

const status = (primaryKey: string, tone = 'INFO') => ({
  primaryKey, tone, healthChips: [], historicalNote: null, conflictKey: null, notTraced: false,
  packedConfirmed: true, fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
})

const ROW = (id: string, number: string, key = 'status.with_carrier') => ({
  id, number, customerName: 'Mona', customerPhone: '0101', status: 'packed', onHold: false, codAmount: 900,
  placedAt: '2026-10-18T10:00:00Z', trackingNumber: '341' + id, deliveryState: 'with_courier', exceptionReason: null,
  bostaLinkStatus: null, failedDeliveryAttempts: 0, isDelayed: null, slaBreached: null, notTracedAt: null,
  isExchange: false, derivedStatus: status(key),
  shippingBadge: { state: 'generic_carrier', carrier: 'Aramex', days: null },
})

let calls: Array<{ method: string; url: string; body?: unknown }>
let markReply: () => Promise<unknown>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.includes('/generic-shipments/carriers')) return json([{ carrier: 'Aramex', withCourier: 2 }, { carrier: 'Mylerz', withCourier: 1 }])
  if (url.includes('/generic-shipments/mark-delivered')) return markReply()
  if (url.includes('/orders/summary')) return json({ total: 3, withCourier: 3, delivered: 0, returned: 0 })
  if (url.includes('/orders?')) {
    const items = url.includes('carrier=Aramex')
      ? [ROW('o1', '#5001'), ROW('o2', '#5002')]
      : [ROW('o1', '#5001'), ROW('o2', '#5002'), ROW('o3', '#5003')]
    return json({ items, page: 0, size: 20, total: items.length })
  }
  return json({})
}

function setup(genericCourier: boolean) {
  calls = []
  markReply = () => json([
    { orderId: 'o1', orderNumber: '#5001', outcome: 'DELIVERED' },
    { orderId: 'o2', orderNumber: '#5002', outcome: 'ALREADY_DELIVERED' },
  ])
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

describe('Orders › With <carrier> + Mark delivered', () => {
  beforeEach(() => setup(true))

  test('filter by carrier, select, confirm: sends the selected ids and reports the outcome', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Orders />)
    const filter = await screen.findByTestId('orders-carrier-filter')
    expect(within(filter).getByRole('option', { name: 'With Aramex (2)' })).toBeInTheDocument()
    expect(screen.queryByTestId('orders-mark-bar')).toBeNull()

    await user.selectOptions(filter, 'Aramex')
    await waitFor(() => expect(calls.some(c => c.url.includes('deliveryState=with_courier') && c.url.includes('carrier=Aramex'))).toBe(true))
    const bar = await screen.findByTestId('orders-mark-bar')
    const markBtn = within(bar).getByRole('button', { name: 'Mark delivered' })
    expect(markBtn).toBeDisabled()

    await user.click(within(await screen.findByTestId('orders-select-#5001')).getByRole('checkbox'))
    await user.click(within(screen.getByTestId('orders-select-#5002')).getByRole('checkbox'))
    expect(within(bar).getByText('2 selected')).toBeInTheDocument()
    await user.click(markBtn)

    const dialog = await screen.findByTestId('orders-mark-confirm')
    expect(dialog).toHaveTextContent('2 order(s) with Aramex will be marked delivered')
    await user.click(within(dialog).getByRole('button', { name: 'Mark 2 delivered' }))

    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.includes('/generic-shipments/mark-delivered'))
      expect(post?.body).toEqual({ orderIds: ['o1', 'o2'] })
    })
    expect(await screen.findByText('1 order(s) marked delivered. 1 skipped (already delivered or not with the courier).'))
      .toBeInTheDocument()
    expect(screen.queryByTestId('orders-mark-confirm')).toBeNull()
  })

  test('a failed mark keeps the dialog open with an error, nothing reported as done', async () => {
    markReply = () => json({}, 500)
    const user = userEvent.setup()
    renderWithProviders(<Orders />)
    await user.selectOptions(await screen.findByTestId('orders-carrier-filter'), 'Aramex')
    await user.click(within(await screen.findByTestId('orders-select-#5001')).getByRole('checkbox'))
    await user.click(within(screen.getByTestId('orders-mark-bar')).getByRole('button', { name: 'Mark delivered' }))
    const dialog = await screen.findByTestId('orders-mark-confirm')
    await user.click(within(dialog).getByRole('button', { name: 'Mark 1 delivered' }))
    expect(await within(dialog).findByText(/Couldn't mark them delivered/)).toBeInTheDocument()
    expect(screen.queryByText(/order\(s\) marked delivered\./)).toBeNull()
  })

  test('Delivered · {carrier} label', () => {
    renderWithProviders(<LegStatusBadge legStatus={{ primaryKey: 'status.delivered_with_carrier', tone: 'SUCCESS' }} carrier="Aramex" />)
    expect(screen.getByText('Delivered · Aramex')).toBeInTheDocument()
  })
})

describe('Orders — store that ships only with Bosta', () => {
  beforeEach(() => setup(false))

  test('no carrier filter and no carriers call', async () => {
    renderWithProviders(<Orders />)
    await screen.findByText('#5003')
    expect(screen.queryByTestId('orders-carrier-filter')).toBeNull()
    expect(calls.some(c => c.url.includes('/generic-shipments'))).toBe(false)
  })
})

describe('Orders — AR / RTL', () => {
  beforeEach(() => setup(true))

  test('Arabic filter option and confirm text', async () => {
    const i18n = i18next.createInstance()
    await i18n.use(initReactI18next).init({ lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
    const user = userEvent.setup()
    render(<MemoryRouter><I18nextProvider i18n={i18n}><div dir="rtl"><Orders /></div></I18nextProvider></MemoryRouter>)
    const filter = await screen.findByTestId('orders-carrier-filter')
    expect(within(filter).getByRole('option', { name: 'مع Aramex (2)' })).toBeInTheDocument()
    await user.selectOptions(filter, 'Aramex')
    await user.click(within(await screen.findByTestId('orders-select-#5001')).getByRole('checkbox'))
    await user.click(within(screen.getByTestId('orders-mark-bar')).getByRole('button', { name: 'تعليم كمُوصَّل' }))
    expect(await screen.findByTestId('orders-mark-confirm')).toHaveTextContent('سيتم تعليم 1 طلب مع Aramex كمُوصَّل')
  })
})

// ── Pickups ───────────────────────────────────────────────────────────────────

const OPEN = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-10-20', scheduledTimeSlot: '10:00 to 13:00',
  scannedCount: 1, openedByName: 'Fatma', createdAt: '2026-10-20T08:00:00Z', notes: null, closedByName: null,
  closedAt: null, carrier: 'Aramex',
  scans: [{ shipmentId: 'sh-1', trackingNumber: '34127840151', orderNumber: '#5001', codAmount: 900,
            scannedAt: '2026-10-20T08:01:00Z', scannedByName: 'Fatma', carrierName: 'Aramex' }] }

function pickupBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([OPEN])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(OPEN)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') {
    return json({ outcome: 'WRONG_CARRIER', entry: {}, carrier: 'Aramex' })
  }
  return json({})
}

describe('Pickups — one courier per session', () => {
  beforeEach(() => {
    resetCapabilitiesCache()
    setAccessToken(fakeJwt('worker'))
    stubFetchWithShellDefaults(vi.fn(pickupBackend),
      { me: { name: 'F', role: 'worker', scanHelpers: false, demoMode: false, genericCourier: true } })
  })

  test('the session names its courier; a parcel for another courier is refused naming it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PickupSessions />)
    await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
    await user.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
    expect(await screen.findByTestId('pickup-carrier')).toHaveTextContent('Courier: Aramex')
    const input = screen.getByPlaceholderText('Scan or type AWB…')
    await user.type(input, '8123456789{Enter}')
    expect(await screen.findByText("This parcel goes with Aramex — keep it for that courier's pickup.")).toBeInTheDocument()
  })
})
