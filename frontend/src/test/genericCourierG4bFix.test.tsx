import { test, expect, describe, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { render, screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ReturnsPortalTab from '../pages/settings/ReturnsPortalTab'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import i18n from '../i18n'
import { resetCapabilitiesCache } from '../capabilities'
import { setAccessToken, clearAccessToken } from '../auth'
import type { PortalSettings } from '../api'
import type { LookupResult, PortalConfig } from '../portal/api'

// G4b fix — "Allow exchanges" without Bosta for a store that accepts returns for other couriers:
//   settings — the switch can be turned on with the other-couriers switch on (no Bosta); neutral description
//              then; no "coming soon" on the other-couriers switch; turning pickup booking off keeps exchanges on
//              while other couriers are on; the other-couriers switch says what turning it off does
//   golden   — a Bosta-only store's settings text is byte-identical to before the fix (snapshot recorded on
//              origin/main 0fe62fe, before any of this change)
//   portal   — lookup exchangesOffered:false (a Bosta order the store can't exchange) → no Return / Exchange choice

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const BASE: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
  bostaConnected: false, exchangesEnabled: false, exchangesSince: null,
}

let settings: PortalSettings
let other: { available: boolean; enabled: boolean } | null
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function stub(s: PortalSettings, o: { available: boolean; enabled: boolean } | null, genericCourier: boolean) {
  settings = s
  other = o
  calls = []
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner', sub: 'u1', tenant: 't1' }))}.s`)
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(opts.body as string) : undefined
    calls.push({ method, url, body })
    if (url.includes('/portal-settings/other-couriers')) {
      if (method === 'PUT') other = { available: true, enabled: body.enabled }
      return fakeResponse({ ...other, since: null })
    }
    if (url.includes('/tenant/portal-settings') && method === 'PUT') {
      settings = { ...settings, ...body, portalPickupBooking: body.pickupBooking ?? settings.portalPickupBooking }
      return fakeResponse(settings)
    }
    if (url.includes('/tenant/portal-settings')) return fakeResponse(settings)
    if (url.includes('/tenant/bosta/return-locations')) {
      return fakeResponse([{ id: 'loc-1', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' }])
    }
    if (url.includes('/variants')) return fakeResponse({ items: [], total: 0 })
    return fakeResponse({})
  }), { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier } })
}

async function renderTab() {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ReturnsPortalTab /></I18nextProvider>)
  await screen.findByTestId('portal-url')
  return user
}

const SWITCH = { name: 'Allow exchanges' }

/** The exchanges switch's row (title + description + note). */
function exchangesRow() {
  return screen.getByRole('switch', SWITCH).closest('.p-6') as HTMLElement
}

describe('Settings — Allow exchanges with other couriers (no Bosta)', () => {
  afterEach(async () => { clearAccessToken(); await i18n.changeLanguage('en') })

  test('other couriers on, no Bosta: the switch is enabled, neutral text; turning it on sends exchangesEnabled', async () => {
    stub(BASE, { available: true, enabled: true }, true)
    const user = await renderTab()
    await screen.findByTestId('other-couriers-row')
    const sw = screen.getByRole('switch', SWITCH)
    expect(sw).toBeEnabled()
    expect(exchangesRow()).toHaveTextContent('Orders shipped with other couriers: you book the exchange with your courier.')
    expect(exchangesRow()).toHaveTextContent('Orders shipped with Bosta: approving books one Bosta exchange trip')
    expect(screen.queryByTestId('exchanges-note')).toBeNull()
    await user.click(sw)
    await user.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.endsWith('/tenant/portal-settings')
      && (c.body as { exchangesEnabled?: boolean }).exchangesEnabled === true)).toBe(true))
  })

  test('other couriers available but off, no Bosta: disabled, says Bosta or the other-couriers switch', async () => {
    stub(BASE, { available: true, enabled: false }, true)
    await renderTab()
    await screen.findByTestId('other-couriers-row')
    expect(screen.getByRole('switch', SWITCH)).toBeDisabled()
    expect(screen.getByTestId('exchanges-note')).toHaveTextContent(
      'Connect Bosta, or turn on "Accept returns for orders shipped with other couriers" below.')
  })

  test('the other-couriers switch has no "coming soon" (EN + AR)', async () => {
    stub(BASE, { available: true, enabled: false }, true)
    await renderTab()
    const row = await screen.findByTestId('other-couriers-row')
    expect(row).not.toHaveTextContent(/coming soon/i)
    expect(row).toHaveTextContent('or an exchange, when "Allow exchanges" is on')
    await i18n.changeLanguage('ar')
    await waitFor(() => expect(screen.getByTestId('other-couriers-row')).not.toHaveTextContent('قادم قريبًا'))
    expect(screen.getByTestId('other-couriers-row')).toHaveTextContent('أو استبدال، عند تفعيل «السماح بالاستبدال»')
  })

  test('exchanges on with no Bosta: the other-couriers switch says turning it off leaves exchanges on', async () => {
    stub({ ...BASE, exchangesEnabled: true }, { available: true, enabled: true }, true)
    const user = await renderTab()
    const row = await screen.findByTestId('other-couriers-row')
    expect(within(row).getByTestId('other-couriers-note')).toHaveTextContent(
      'Turning this off leaves "Allow exchanges" on, but orders shipped with other couriers can no longer be returned or exchanged in the portal.')
    await user.click(within(row).getByRole('switch'))
    await waitFor(() => expect(screen.getByTestId('exchanges-note')).toHaveTextContent(
      'No order can be exchanged right now. Turn on Bosta pickup booking, or returns for orders shipped with other couriers.'))
    expect(screen.getByRole('switch', SWITCH)).toBeChecked()
  })

  test('Bosta + other couriers: turning pickup booking off keeps exchanges on', async () => {
    stub({ ...BASE, bostaConnected: true, returnLocationId: 'loc-1', returnLocationName: 'Maadi Warehouse',
      pickupBooking: true, portalPickupBooking: true, exchangesEnabled: true }, { available: true, enabled: true }, true)
    const user = await renderTab()
    await screen.findByTestId('other-couriers-row')
    expect(within(screen.getByTestId('other-couriers-row')).queryByTestId('other-couriers-note')).toBeNull()
    await user.click(screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' }))
    expect(screen.getByRole('switch', SWITCH)).toBeChecked()
  })

  test('Arabic: the neutral description', async () => {
    await i18n.changeLanguage('ar')
    stub(BASE, { available: true, enabled: true }, true)
    await renderTab()
    await screen.findByTestId('other-couriers-row')
    expect(document.body.textContent).toContain('الطلبات المشحونة مع شركات شحن أخرى: أنت تحجز الاستبدال مع شركة الشحن.')
  })
})

describe('Golden — a Bosta-only store’s settings text is unchanged', () => {
  afterEach(async () => { clearAccessToken(); await i18n.changeLanguage('en') })

  const CASES: Array<[string, PortalSettings]> = [
    ['no Bosta', BASE],
    ['Bosta, no location', { ...BASE, bostaConnected: true }],
    ['Bosta, booking off', { ...BASE, bostaConnected: true, returnLocationId: 'loc-1', returnLocationName: 'Maadi Warehouse' }],
    ['Bosta, booking on', { ...BASE, bostaConnected: true, returnLocationId: 'loc-1', returnLocationName: 'Maadi Warehouse',
      pickupBooking: true, portalPickupBooking: true }],
    ['Bosta, exchanges on', { ...BASE, bostaConnected: true, returnLocationId: 'loc-1', returnLocationName: 'Maadi Warehouse',
      pickupBooking: true, portalPickupBooking: true, exchangesEnabled: true, exchangesSince: '2026-10-01T10:00:00Z' }],
  ]

  for (const lang of ['en', 'ar'] as const) {
    for (const [name, s] of CASES) {
      test(`${lang}: ${name}`, async () => {
        await i18n.changeLanguage(lang)
        stub(s, null, false)
        await renderTab()
        await waitFor(() => expect(screen.getByRole('switch', { name: i18n.t('settings.portal.exchanges.title') })).toBeInTheDocument())
        const sw = screen.getByRole('switch', { name: i18n.t('settings.portal.exchanges.title') })
        const booking = screen.getByRole('switch', { name: i18n.t('settings.portal.booking.title') })
        expect({
          exchanges: (sw.closest('.p-6') as HTMLElement).textContent,
          exchangesDisabled: (sw as HTMLButtonElement).disabled,
          booking: (booking.closest('.p-6') as HTMLElement).textContent,
          otherCouriersRow: screen.queryByTestId('other-couriers-row') !== null,
        }).toMatchSnapshot()
        expect(calls.some(c => c.url.includes('/other-couriers'))).toBe(false)
      })
    }
  }

  test('Bosta-only: turning pickup booking off still turns exchanges off', async () => {
    stub(CASES[4][1], null, false)
    const user = await renderTab()
    await user.click(screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' }))
    expect(screen.getByRole('switch', SWITCH)).not.toBeChecked()
  })
})

// ── Customer portal ───────────────────────────────────────────────────────────

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 14, reasonCodes: ['wrong_size'], logoUrl: null, brandColor: '#1F5C4A',
  policyText: null, autoApprove: false, pickupBooking: false, exchangesEnabled: true, otherCouriers: true,
}

const LOOKUP: LookupResult = {
  token: 'tok', orderNumber: '#6001', deliveredAt: '2026-10-18T10:00:00Z',
  lines: [{ variantId: 'v1', productTitle: 'Linen Shirt', variantTitle: null, imageUrl: null,
    deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false }],
  pickup: null,
}

async function lookedUp(lookup: LookupResult) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const respond = (status: number, body?: unknown) => Promise.resolve({ ok: status < 300, status,
      headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
      json: async () => structuredClone(body), text: async () => JSON.stringify(body) })
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, lookup)
    return respond(404)
  }))
  localStorage.clear()
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { level: 1 })
  await user.type(document.getElementById('pp-order')!, '#6001')
  await user.type(document.getElementById('pp-phone')!, '01012345678')
  await user.click(document.querySelector('form button[type=submit]') as HTMLElement)
  await screen.findAllByTestId('portal-line')
}

describe('Customer portal — exchangesOffered', () => {
  test('false: no Return / Exchange choice', async () => {
    await lookedUp({ ...LOOKUP, exchangesOffered: false })
    expect(document.querySelector('input[name="pp-mode"]')).toBeNull()
  })

  test('absent: the choice as before', async () => {
    await lookedUp(LOOKUP)
    expect(document.querySelector('input[name="pp-mode"]')).not.toBeNull()
  })
})
