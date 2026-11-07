import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Route, Routes } from 'react-router-dom'
import { fireEvent, renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import Layout from '../components/Layout'
import SettingsPage from '../pages/settings/SettingsPage'
import i18n from '../i18n'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import type { CustomerFeeSettings, FeeRule, PortalSettings, PortalVariantRow } from '../api'

// Returns portal redesign Pass B — ONE "Save changes" for the whole Returns portal tab (Settings, inside the app shell).
//   draft      — changing a control sends nothing; the sticky bar counts the changes (plurals EN + AR) and marks rows
//                "Edited"; Save changes runs the plan; Discard restores the saved state
//   partial    — call 2 fails: call 1 is saved, the failed section and the ones after it stay Edited, the bar names
//                the failed section with the backend's message, and "Changes saved" never shows
//   role/flag  — a manager's save has no owner-only call; with fees hidden there is no fee call
//   merged     — price mode both ways incl. the stored conflicting state, empty cap blocks, 0 valid; one waiver switch
//                (all on / mixed / no rules)
//   location   — a location the merchant picks in the same draft unlocks pickup booking
//   leave      — a sidebar link and a Settings menu item ask first; beforeunload while dirty
//   products   — the excluded list loads with nonReturnable=true; Add products → Exclude → one PUT on save
// Fakes behave like the real endpoints: PUT /variants/{id}/non-returnable → 204 with no body; fee / other-courier
// errors → {code, message_en, message_ar}; portal-settings errors → {field, error, message}.

const PORTAL: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: 'loc-1',
  returnLocationName: 'Maadi Warehouse', bostaConnected: true, exchangesEnabled: true, font: 'cairo',
  refundMethods: ['instapay'], requirePhotos: false,
}

const rule = (kind: 'exchange' | 'refund', extra: Partial<FeeRule> = {}): FeeRule => ({
  kind, enabled: true, mode: 'fixed', amount: 50, percent: null, minAmount: null, maxAmount: null,
  waiveMerchantFault: true, updatedAt: '2026-11-06T10:00:00Z', ...extra,
})

const FEES = (): CustomerFeeSettings => ({
  visible: true,
  rules: { exchange: rule('exchange'), refund: rule('refund') },
  merchantFaultReasons: ['damaged', 'wrong_item'],
  overrides: [],
  exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: 200, freeVariantSwaps: true },
})

let portal: PortalSettings
let fees: CustomerFeeSettings
let other: { available: boolean; enabled: boolean; since: string | null } | null
let variants: PortalVariantRow[]
let locations: Array<{ id: string; name: string; isDefault: boolean; cityName: string | null }>
let fail: Partial<Record<'portal' | 'pricing' | 'reasons' | 'other' | 'variant', { status: number; body: unknown }>>
let calls: Array<{ method: string; url: string; body?: unknown }>

function respond(status: number, data?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 204 ? 'No Content' : 'OK',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && status !== 204 ? 'application/json' : null) },
    json: async () => { if (status === 204) throw new SyntaxError('Unexpected end of JSON input'); return structuredClone(data) },
    text: async () => (status === 204 ? '' : JSON.stringify(data)),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  const failWith = (k: keyof typeof fail) => fail[k] && respond(fail[k]!.status, fail[k]!.body)
  if (url.includes('/portal-settings/other-couriers')) {
    if (method === 'PUT') {
      const f = failWith('other'); if (f) return f
      other = { available: true, enabled: body.enabled, since: body.enabled ? '2026-11-07T10:00:00Z' : null }
    }
    return respond(200, other)
  }
  if (url.includes('/customer-fees')) {
    if (method === 'GET') return respond(200, fees)
    if (url.endsWith('/exchange-pricing')) {
      const f = failWith('pricing'); if (f) return f
      fees = { ...fees, exchangePricing: body }
    } else if (url.includes('/rules/')) {
      const kind = url.split('/rules/')[1] as 'exchange' | 'refund'
      fees = { ...fees, rules: { ...fees.rules, [kind]: { kind, ...body, updatedAt: '2026-11-07T10:00:00Z' } } }
    } else if (url.endsWith('/merchant-fault-reasons')) {
      const f = failWith('reasons'); if (f) return f
      fees = { ...fees, merchantFaultReasons: body.reasons }
    }
    return respond(200, fees)
  }
  if (url.endsWith('/tenant/portal-settings')) {
    if (method === 'PUT') {
      const f = failWith('portal'); if (f) return f
      portal = { ...portal, ...body, portalPickupBooking: body.pickupBooking ?? portal.portalPickupBooking,
        pickupBooking: body.pickupBooking ?? portal.pickupBooking, exchangesEnabled: body.exchangesEnabled ?? portal.exchangesEnabled }
    }
    return respond(200, portal)
  }
  if (url.includes('/bosta/return-locations')) return respond(200, locations)
  if (url.includes('/non-returnable') && method === 'PUT') {
    const f = failWith('variant'); if (f) return f
    const id = url.split('/variants/')[1].split('/')[0]
    variants = variants.map(v => (v.id === id ? { ...v, nonReturnable: body.value } : v))
    return respond(204)
  }
  if (url.includes('/variants?')) {
    const p = new URL(url, 'http://x').searchParams
    const q = (p.get('search') ?? '').toLowerCase()
    const items = variants.filter(v => (p.get('nonReturnable') !== 'true' || v.nonReturnable)
      && (!q || [v.productTitle, v.variantTitle, v.sku].some(s => s?.toLowerCase().includes(q))))
    return respond(200, { items, total: items.length })
  }
  return respond(200, {})
}

/**
 * The app shell's own calls, matched exactly. (The shared stubFetchWithShellDefaults treats any URL containing "/me"
 * as GET /me — it would swallow PUT …/customer-fees/merchant-fault-reasons.)
 */
function login(role: 'owner' | 'manager') {
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role, sub: 'u1', tenant: 't1', exp: 9999999999 }))}.s`)
  vi.stubGlobal('fetch', vi.fn((url: string, opts?: RequestInit) => {
    if (url.endsWith('/api/v1/me')) return respond(200, { name: 'Nour', role, genericCourier: !!other })
    if (url.endsWith('/exceptions/count')) return respond(200, {})
    if (url.endsWith('/onboarding/status')) return respond(200, { steps: [], allDone: true, dismissed: true })
    return backend(url, opts)
  }))
}

beforeEach(async () => {
  portal = structuredClone(PORTAL)
  fees = FEES()
  other = { available: true, enabled: false, since: null }
  variants = [
    { id: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'White / M', sku: '2004-White-M', nonReturnable: false },
    { id: 'v-scarf', productTitle: 'Silk Scarf', variantTitle: 'Sand', sku: '3002-Sand', nonReturnable: true },
  ]
  locations = [{ id: 'loc-1', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' }]
  fail = {}
  calls = []
  await i18n.changeLanguage('en')
})

afterEach(async () => {
  clearAccessToken()
  resetCapabilitiesCache()
  vi.unstubAllGlobals()
  await i18n.changeLanguage('en')
})

async function open(role: 'owner' | 'manager' = 'owner') {
  login(role)
  const user = userEvent.setup()
  renderWithProviders(
    <I18nextProvider i18n={i18n}>
      <Routes>
        <Route path="/settings" element={<Layout><SettingsPage /></Layout>} />
        <Route path="/orders" element={<p>orders page</p>} />
      </Routes>
    </I18nextProvider>,
    { initialEntries: ['/settings?tab=portal'] })
  // The whole app shell renders here — give the first load room under a loaded full-suite run.
  const slow = { timeout: 4000 }
  await screen.findByTestId('portal-url', {}, slow)
  await screen.findByTestId('excluded-products', {}, slow)
  if (fees.visible) await screen.findByTestId('fee-rules-table', {}, slow)
  await waitFor(() => expect(calls.some(c => c.url.includes('nonReturnable=true'))).toBe(true), slow)
  return user
}

const writes = () => calls.filter(c => c.method !== 'GET')
const saveButton = () => screen.queryByRole('button', { name: i18n.t('settings.portal.save') })
const windowField = () => screen.getByLabelText(i18n.t('settings.portal.window.label'))

describe('one save for the tab', () => {
  test('nothing is saved until Save changes; the bar counts the changes and rows say Edited', async () => {
    const user = await open()
    expect(saveButton()).toBeNull()
    expect(screen.queryAllByTestId('edited-tag')).toHaveLength(0)

    await user.clear(windowField())
    await user.type(windowField(), '14')
    await user.click(within(screen.getByTestId('fee-rule-refund')).getByRole('switch'))
    expect(writes()).toEqual([])
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('2 unsaved changes')
    expect(within(screen.getByTestId('fee-rule-refund')).getByTestId('edited-tag')).toHaveTextContent('Edited')
    expect(within(document.getElementById('portal-eligibility')!).queryAllByTestId('edited-tag')).toHaveLength(1)

    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull(), { timeout: 4000 })
    expect(writes().map(c => [c.method, c.url.replace(/^.*\/api\/v1/, '')])).toEqual([
      ['PUT', '/tenant/portal-settings'],
      ['PUT', '/tenant/portal-settings/customer-fees/rules/refund'],
    ])
    expect(writes()[0].body).toMatchObject({ returnWindowDays: 14 })
    expect(writes()[1].body).toEqual({ enabled: false, mode: 'fixed', waiveMerchantFault: true, amount: 50, percent: null,
      minAmount: null, maxAmount: null })
    expect(await screen.findByText('Changes saved. Customers see them right away.', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.queryAllByTestId('edited-tag')).toHaveLength(0)
  })

  test('Discard restores the saved state and sends nothing', async () => {
    const user = await open()
    await user.clear(windowField())
    await user.type(windowField(), '7')
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.fees.waiver.title') }))
    await user.click(screen.getByRole('button', { name: 'Remove Silk Scarf, Sand' }))
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('3 unsaved changes')

    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(saveButton()).toBeNull()
    expect(windowField()).toHaveValue(30)
    expect(screen.getByRole('switch', { name: i18n.t('settings.portal.fees.waiver.title') })).toBeChecked()
    expect(screen.getByRole('button', { name: 'Remove Silk Scarf, Sand' })).toBeInTheDocument()
    expect(screen.queryAllByTestId('edited-tag')).toHaveLength(0)
    expect(writes()).toEqual([])
  })

  test('call 2 fails: call 1 is saved, the failed section and the rest stay Edited, no "Changes saved"', async () => {
    fail.pricing = { status: 400, body: { code: 'FEE_MAX_DIFFERENCE', message_en: 'Enter an amount from 0 to 30,000.',
      message_ar: 'أدخل مبلغًا من 0 إلى 30,000.' } }
    const user = await open()
    await user.click(within(screen.getByTestId('other-couriers-row')).getByRole('switch'))
    await user.click(screen.getByRole('radio', { name: /^Any product in stock/ }))
    await user.click(screen.getByTestId('reason-other'))
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('3 unsaved changes')

    await user.click(saveButton()!)
    const failure = await screen.findByTestId('save-failure')
    expect(failure).toHaveTextContent('Exchange pricing wasn\'t saved. Enter an amount from 0 to 30,000.')
    expect(failure).toHaveTextContent('The changes listed before it were saved')
    expect(writes().map(c => c.url.replace(/^.*\/portal-settings/, ''))).toEqual(['/other-couriers', '/customer-fees/exchange-pricing'])
    expect(screen.getByTestId('error-exchange-pricing')).toHaveTextContent('Enter an amount from 0 to 30,000.')
    // Step 1 is saved (no Edited); pricing (failed) and reasons (after it) are still Edited.
    expect(within(screen.getByTestId('other-couriers-row')).queryByTestId('edited-tag')).toBeNull()
    expect(within(screen.getByTestId('exchange-pricing')).getAllByTestId('edited-tag').length).toBeGreaterThan(0)
    expect(within(screen.getByTestId('merchant-fault-reasons')).getByTestId('edited-tag')).toBeInTheDocument()
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('2 unsaved changes')
    expect(screen.queryByText('Changes saved. Customers see them right away.')).toBeNull()

    // Fixed on the server side → the next save sends only what is left.
    delete fail.pricing
    calls = []
    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull())
    expect(writes().map(c => c.url.replace(/^.*\/portal-settings/, ''))).toEqual(
      ['/customer-fees/exchange-pricing', '/customer-fees/merchant-fault-reasons'])
  })

  test('a portal-settings error maps to its field and names the section', async () => {
    fail.portal = { status: 400, body: { field: 'returnWindowDays', error: 'WINDOW_RANGE', message: 'x' } }
    const user = await open()
    await user.clear(windowField())
    await user.type(windowField(), '120')
    await user.click(saveButton()!)
    expect(await screen.findByTestId('error-returnWindowDays')).toHaveTextContent('Enter a number of days from 1 to 90.')
    expect(screen.getByTestId('save-failure')).toHaveTextContent('Portal settings wasn\'t saved. Enter a number of days from 1 to 90.')
    expect(screen.getByTestId('save-failure')).not.toHaveTextContent('listed before it')
  })
})

describe('who and what is sent', () => {
  test('a manager\'s save has no owner-only call', async () => {
    const user = await open('manager')
    expect(within(screen.getByTestId('other-couriers-row')).getByRole('switch')).toBeDisabled()
    expect(within(screen.getByTestId('fee-rule-refund')).getByRole('switch')).toBeDisabled()
    await user.clear(windowField())
    await user.type(windowField(), '21')
    await user.click(screen.getByRole('button', { name: 'Remove Silk Scarf, Sand' }))
    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull())
    expect(writes().map(c => c.url.replace(/^.*\/api\/v1/, ''))).toEqual(['/tenant/portal-settings', '/variants/v-scarf/non-returnable'])
  })

  test('fees hidden for the store: no fee or pricing UI, no fee call', async () => {
    fees = { ...fees, visible: false }
    const user = await open()
    expect(screen.queryByTestId('fee-rules-table')).toBeNull()
    expect(screen.queryByTestId('exchange-pricing')).toBeNull()
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.requirePhotos.title') }))
    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull())
    expect(writes().some(c => c.url.includes('/customer-fees'))).toBe(false)
    expect(writes()).toHaveLength(1)
  })
})

describe('merged controls', () => {
  test('price mode: cap → same or lower writes (true, null)', async () => {
    const user = await open()
    expect(screen.getByRole('radio', { name: /^Up to a limit/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('max-difference')).toHaveValue(200)
    await user.click(screen.getByRole('radio', { name: /^Same price or lower only/ }))
    expect(screen.queryByTestId('max-difference')).toBeNull()
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: false, sameOrLowerOnly: true, maxDifference: null, freeVariantSwaps: true })
  })

  test('price mode: the stored conflicting state reads "same or lower", is not an edit, and No limit writes (false, null)', async () => {
    fees.exchangePricing = { anyProduct: false, sameOrLowerOnly: true, maxDifference: 300, freeVariantSwaps: true }
    const user = await open()
    expect(screen.getByRole('radio', { name: /^Same price or lower only/ })).toHaveAttribute('aria-checked', 'true')
    expect(saveButton()).toBeNull()
    await user.click(screen.getByRole('radio', { name: /^No limit/ }))
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true })
  })

  test('price mode: an empty cap blocks Save (nothing sent); 0 is valid', async () => {
    fees.exchangePricing = { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true }
    const user = await open()
    await user.click(screen.getByRole('radio', { name: /^Up to a limit/ }))
    await user.click(saveButton()!)
    expect(await screen.findByTestId('error-max-difference')).toHaveTextContent(
      'Enter the most a customer can pay extra, or choose another option.')
    expect(screen.getByTestId('save-blocked')).toBeInTheDocument()
    expect(writes()).toEqual([])
    await user.type(screen.getByTestId('max-difference'), '0')
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: false, sameOrLowerOnly: false, maxDifference: 0, freeVariantSwaps: true })
  })

  test('waiver: all on → on; switching it off writes both fees', async () => {
    const user = await open()
    const sw = screen.getByRole('switch', { name: 'Waive fees when it\'s the store\'s fault' })
    expect(sw).toBeChecked()
    expect(screen.queryByTestId('waiver-mixed')).toBeNull()
    await user.click(sw)
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(2))
    expect(writes().map(c => [c.url.split('/rules/')[1], (c.body as { waiveMerchantFault: boolean }).waiveMerchantFault]))
      .toEqual([['exchange', false], ['refund', false]])
  })

  test('waiver: mixed → off with a note; a save makes them match', async () => {
    fees.rules.refund = rule('refund', { waiveMerchantFault: false })
    const user = await open()
    expect(screen.getByRole('switch', { name: 'Waive fees when it\'s the store\'s fault' })).not.toBeChecked()
    expect(screen.getByTestId('waiver-mixed')).toHaveTextContent('Saving applies this setting to every fee.')
    expect(saveButton()).toBeNull()
    await user.click(screen.getByTestId('reason-other'))
    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull())
    expect(writes().map(c => c.url.replace(/^.*\/customer-fees/, ''))).toEqual(['/rules/exchange', '/merchant-fault-reasons'])
    expect(writes()[0].body).toMatchObject({ waiveMerchantFault: false, amount: 50 })
    expect(fees.rules.exchange!.waiveMerchantFault).toBe(fees.rules.refund!.waiveMerchantFault)
  })

  test('waiver: no rules → on, and a fee created now takes it', async () => {
    fees.rules = { exchange: null, refund: null }
    const user = await open()
    const sw = screen.getByRole('switch', { name: 'Waive fees when it\'s the store\'s fault' })
    expect(sw).toBeChecked()
    await user.click(sw)
    expect(saveButton()).toBeNull()     // nothing to store it in yet
    const card = screen.getByTestId('fee-rule-exchange')
    await user.click(within(card).getByRole('switch'))
    await user.type(within(card).getByTestId('fee-amount-exchange'), '30')
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ enabled: true, mode: 'fixed', waiveMerchantFault: false, amount: 30, percent: null,
      minAmount: null, maxAmount: null })
  })

  test('a return location picked in the same draft unlocks pickup booking; one PUT carries both', async () => {
    portal = { ...portal, returnLocationId: null, returnLocationName: null, exchangesEnabled: false }
    locations = [{ id: 'loc-1', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' },
      { id: 'loc-2', name: 'Store front', isDefault: false, cityName: 'Giza' }]
    const user = await open()
    const booking = screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' })
    await screen.findByTestId('default-location-hint')
    expect(screen.getByLabelText('Returns go back to')).toHaveValue('')   // Bosta's default is suggested, not filled in
    expect(booking).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Returns go back to'), 'loc-2')
    expect(screen.queryByTestId('default-location-hint')).toBeNull()
    expect(booking).toBeEnabled()
    await user.click(booking)
    expect(writes()).toEqual([])
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toMatchObject({ returnLocationId: 'loc-2', pickupBooking: true })
  })
})

describe('Bosta\'s default return location (nothing saved)', () => {
  beforeEach(() => {
    portal = { ...portal, returnLocationId: null, returnLocationName: null, exchangesEnabled: false }
  })

  test('shown with "Not saved yet" — not an unsaved change: no bar, no Edited, no question on leave', async () => {
    const user = await open()
    const hint = await screen.findByTestId('default-location-hint')
    expect(hint).toHaveTextContent('Maadi Warehouse · Cairo (default)')
    expect(hint).toHaveTextContent('Not saved yet — choose a location to turn on pickups')
    expect(within(hint).getByRole('button', { name: 'Use this location' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' })).toBeDisabled()
    expect(screen.queryByTestId('save-bar')).toBeNull()
    expect(saveButton()).toBeNull()
    expect(screen.queryAllByTestId('edited-tag')).toHaveLength(0)

    const e = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(e)
    expect(e.defaultPrevented).toBe(false)

    await user.click(screen.getAllByRole('link', { name: /Orders/ })[0])
    expect(await screen.findByText('orders page')).toBeInTheDocument()
    expect(screen.queryByTestId('leave-guard')).toBeNull()
    expect(writes()).toEqual([])
  })

  test('"Use this location" → 1 unsaved change, pickup booking unlocks, Save sends the location', async () => {
    const user = await open()
    const booking = screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' })
    await user.click(await screen.findByRole('button', { name: 'Use this location' }))
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('1 unsaved change')
    expect(screen.getByLabelText('Returns go back to')).toHaveValue('loc-1')
    expect(screen.queryByTestId('default-location-hint')).toBeNull()
    expect(within(screen.getByTestId('return-location-row')).getByTestId('edited-tag')).toBeInTheDocument()
    expect(booking).toBeEnabled()
    expect(writes()).toEqual([])

    await user.click(booking)
    await user.click(saveButton()!)
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].url).toMatch(/\/tenant\/portal-settings$/)
    expect(writes()[0].body).toMatchObject({ returnLocationId: 'loc-1', pickupBooking: true })
  })

  test('a store with a saved location is unaffected: no suggestion, nothing unsaved, booking available', async () => {
    portal = { ...portal, returnLocationId: 'loc-1', returnLocationName: 'Maadi Warehouse' }
    await open()
    await waitFor(() => expect(screen.getByLabelText('Returns go back to')).toHaveValue('loc-1'))
    expect(screen.queryByTestId('default-location-hint')).toBeNull()
    expect(screen.queryByTestId('save-bar')).toBeNull()
    expect(screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' })).toBeEnabled()
  })
})

describe('leave guard', () => {
  test('a sidebar link asks first; Stay keeps the edits, Leave goes', async () => {
    const user = await open()
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.requirePhotos.title') }))
    const orders = screen.getAllByRole('link', { name: /Orders/ })[0]
    await user.click(orders)
    const dialog = await screen.findByTestId('leave-guard')
    expect(dialog).toHaveTextContent('You have 1 unsaved change on this page.')
    expect(screen.queryByText('orders page')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Stay on this page' }))
    expect(screen.queryByTestId('leave-guard')).toBeNull()
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('1 unsaved change')

    await user.click(orders)
    await user.click(await screen.findByRole('button', { name: 'Leave without saving' }))
    expect(await screen.findByText('orders page')).toBeInTheDocument()
    expect(writes()).toEqual([])
  })

  test('another Settings menu item asks first; nothing edited → no question', async () => {
    const user = await open()
    await user.click(screen.getByRole('button', { name: 'Business' }))
    expect(screen.queryByTestId('leave-guard')).toBeNull()
    expect(await screen.findByRole('button', { name: 'Business', current: 'page' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Returns portal' }))
    await screen.findByTestId('portal-url')
    await screen.findByTestId('excluded-products')
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.requirePhotos.title') }))
    await user.click(screen.getByRole('button', { name: 'Business' }))
    expect(await screen.findByTestId('leave-guard')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Returns portal', current: 'page' })).toBeInTheDocument()
  })

  test('beforeunload is held only while there are unsaved changes', async () => {
    const user = await open()
    const unload = () => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented }
    expect(unload()).toBe(false)
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.requirePhotos.title') }))
    expect(unload()).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(unload()).toBe(false)
  })
})

describe('products that can\'t be returned', () => {
  test('the excluded list loads with the filter; Exclude and Remove are saved one PUT per changed product', async () => {
    const user = await open()
    const listCall = calls.find(c => c.url.includes('/variants?') && c.url.includes('nonReturnable=true'))
    expect(listCall).toBeDefined()
    expect(within(screen.getByTestId('variant-list')).getAllByTestId('variant-row')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Remove Silk Scarf, Sand' }))
    expect(screen.queryByTestId('excluded-count')).toBeNull()     // 0 left
    await user.click(screen.getByRole('button', { name: 'Add products' }))
    await user.click(await screen.findByRole('button', { name: 'Exclude Linen Shirt, White / M' }))
    expect(screen.getByTestId('excluded-count')).toHaveTextContent('1 product can\'t be returned')
    expect(writes()).toEqual([])

    await user.click(saveButton()!)
    await waitFor(() => expect(saveButton()).toBeNull())
    // In the order they were changed; only these two products, no bulk PUT.
    expect(writes().map(c => [c.url.replace(/^.*\/api\/v1/, ''), c.body])).toEqual([
      ['/variants/v-scarf/non-returnable', { value: false }],
      ['/variants/v-shirt/non-returnable', { value: true }],
    ])
    expect(within(screen.getByTestId('variant-list')).getAllByTestId('variant-row')).toHaveLength(1)
    expect(screen.getByTestId('variant-list')).toHaveTextContent('Linen Shirt')
  })

  test('nothing excluded → "Every product can be returned"', async () => {
    variants = variants.map(v => ({ ...v, nonReturnable: false }))
    await open()
    expect(screen.getByTestId('excluded-empty')).toHaveTextContent('Every product can be returned.')
    expect(screen.queryByTestId('excluded-count')).toBeNull()
  })
})

describe('Arabic', () => {
  test('the bar, its plurals, the excluded list and a partial failure read in Arabic', async () => {
    await i18n.changeLanguage('ar')
    fail.reasons = { status: 400, body: { code: 'FEE_REASON', message_en: 'Choose from the listed reasons.',
      message_ar: 'اختر من الأسباب المعروضة.' } }
    const user = await open()
    expect(screen.getByTestId('excluded-count')).toHaveTextContent('منتج واحد لا يمكن إرجاعه')
    await user.click(screen.getByRole('switch', { name: i18n.t('settings.portal.requirePhotos.title') }))
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('تغيير واحد غير محفوظ')
    await user.click(screen.getByTestId('reason-other'))
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('تغييران غير محفوظين')
    fireEvent.change(windowField(), { target: { value: '9' } })
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('3 تغييرات غير محفوظة')
    expect(screen.getAllByTestId('edited-tag')[0]).toHaveTextContent('معدّل')

    await user.click(screen.getByRole('button', { name: 'حفظ التغييرات' }))
    const failure = await screen.findByTestId('save-failure')
    expect(failure).toHaveTextContent('لم يُحفظ قسم «أسباب خطأ المتجر». اختر من الأسباب المعروضة.')
    expect(failure).toHaveTextContent('تم حفظ التغييرات التي قبله')
    expect(screen.getByTestId('unsaved-count')).toHaveTextContent('تغيير واحد غير محفوظ')
  })
})
