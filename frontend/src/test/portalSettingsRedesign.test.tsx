import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import i18n from '../i18n'
import SettingsPage from '../pages/settings/SettingsPage'
import type { CustomerFeeSettings, PortalSettings } from '../api'

// Returns portal redesign Pass A — Settings → Returns portal, appearance only. Fakes follow the real endpoints'
// shapes (PortalSettingsService GET/PUT, CustomerFeeSettingsController GET with visible, GET /variants).
//   header     — Live / Off and "Open portal" follow the SAVED settings; the off note follows the switch
//   summary    — fees hidden (visible:false): no fee / pricing sentence and no fee table; shown: the fee sentences and
//                the Fee / Type / Amount / On table; the sentences follow unsaved edits (window, approval, a fee)
//   approval   — the radio cards write the same autoApprove field "Save changes" sends
//   percent    — choosing Percentage in the fee table shows the stored min / max under the row
//   saves now  — "Saves right away" on the logo, the non-returnable list and fees by area — not on "Save changes" rows
//   branding   — the policy-vs-window warning (Latin and Arabic-Indic digits) and the colour notes
//   jump links — scroll to the section and focus its heading, the URL stays ?tab=portal
//   AR         — Arabic copy and plural forms on the same page

const roleMock = vi.hoisted(() => ({ role: 'owner' as 'owner' | 'manager' }))
vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => roleMock.role) }
})

let settings: PortalSettings
let fees: CustomerFeeSettings
let calls: Array<{ method: string; url: string; body?: unknown }>

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.includes('/customer-fees/areas')) return json([])
  if (url.includes('/customer-fees')) return json(fees)
  if (url.includes('/other-couriers')) return json({ available: false, enabled: false, since: null })
  if (url.includes('/bosta/return-locations')) return json([{ id: 'loc-1', name: 'Maadi Warehouse', cityName: 'Cairo', isDefault: true }])
  if (url.endsWith('/tenant/portal-settings') && method === 'PUT') {
    settings = { ...settings, ...body }
    return json(settings)
  }
  if (url.includes('/tenant/portal-settings')) return json(settings)
  if (url.includes('/variants?')) return json({ items: [], total: 0 })
  return json({})
}

const rule = (kind: 'exchange' | 'refund', r: Record<string, unknown> = {}) => ({
  kind, enabled: true, mode: 'fixed', amount: 50, percent: null, minAmount: null, maxAmount: null, waiveMerchantFault: true,
  updatedAt: '2026-11-05T10:00:00Z', ...r,
})

beforeEach(async () => {
  roleMock.role = 'owner'
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 14, logoUrl: null, brandColor: '#0F766E',
    policyText: null, pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc-1',
    returnLocationName: 'Maadi Warehouse', bostaConnected: true, exchangesEnabled: true, font: 'cairo', storeName: 'Nour Studio',
    refundMethods: ['bank_transfer', 'instapay'], requirePhotos: false, logo: null,
  } as PortalSettings
  fees = {
    visible: false, rules: { exchange: null, refund: null }, merchantFaultReasons: ['damaged'], overrides: [],
    exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
  }
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
  Element.prototype.scrollIntoView = vi.fn()
  await i18n.changeLanguage('en')
})

afterEach(async () => {
  vi.unstubAllGlobals()
  await i18n.changeLanguage('en')
  document.documentElement.dir = 'ltr'
})

async function open() {
  const user = userEvent.setup()
  renderWithProviders(
    <I18nextProvider i18n={i18n}><Routes><Route path="/settings" element={<SettingsPage />} /></Routes></I18nextProvider>,
    { initialEntries: ['/settings?tab=portal'] })
  await screen.findByDisplayValue('nourstudio')
  return user
}

const lines = () => screen.getAllByTestId('policy-line').map(l => l.textContent)
const lastPut = () => [...calls].reverse().find(c => c.method === 'PUT' && c.url.endsWith('/tenant/portal-settings'))

describe('Returns portal — header', () => {
  test('Live and Open portal follow the saved settings; the off note follows the switch', async () => {
    const user = await open()
    expect(screen.getByTestId('portal-status')).toHaveTextContent('Live')
    expect(screen.getByRole('link', { name: 'Open portal' })).toHaveAttribute('href', 'https://returns.tracedtech.com/nourstudio')
    await user.click(screen.getByRole('switch', { name: 'Customers can start returns' }))
    // Not saved yet: still live; the note says what turning it off does; the summary says it's off.
    expect(screen.getByTestId('portal-status')).toHaveTextContent('Live')
    expect(screen.getByTestId('portal-off-note')).toHaveTextContent('Your portal link shows “This returns page isn\'t available”.')
    expect(lines()[0]).toMatch(/^The portal is off/)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByTestId('portal-status')).toHaveTextContent('Off'))
  })
})

describe('Returns portal — summary and fees', () => {
  test('fees hidden: no fee or pricing sentence, no fee table, no pricing under Exchanges', async () => {
    await open()
    expect(lines()).toEqual([
      'Customers can start a return within 14 days of delivery.',
      'You review every request, and Traced books a Bosta pickup when you approve.',
      'Customers can exchange for another size or colour of the same product.',
      'Customers choose how to get their money back: bank transfer or InstaPay.',
    ])
    expect(screen.queryByTestId('fee-rules-table')).toBeNull()
    expect(screen.queryByTestId('exchange-pricing')).toBeNull()
    expect(calls.some(c => c.method !== 'GET')).toBe(false)
  })

  test('fees shown: the table and pricing render in their sections; sentences follow unsaved edits', async () => {
    fees = { ...fees, visible: true, rules: { exchange: rule('exchange') as never, refund: null },
      exchangePricing: { anyProduct: true, sameOrLowerOnly: false, maxDifference: 300, freeVariantSwaps: true } }
    const user = await open()
    const exchanges = document.getElementById('portal-exchanges')!
    const refunds = document.getElementById('portal-refunds')!
    await within(exchanges).findByTestId('exchange-pricing')
    expect(within(refunds).getByTestId('fee-rules-table')).toHaveTextContent('FeeTypeAmountOn')
    expect(lines()).toContain('An exchange fee of EGP 50 applies, except when it\'s the store\'s fault.')
    expect(lines()).toContain('When the new item costs more, they pay the difference on delivery — up to 300 EGP.')

    // Unsaved edits show at once: the window, the approval cards, a fee switched on, "same or lower".
    const window = screen.getByLabelText('Return window')
    await user.clear(window)
    await user.type(window, '30')
    await user.click(screen.getByRole('radio', { name: /^Approve automatically/ }))
    await user.click(within(screen.getByTestId('fee-rule-refund')).getByRole('switch', { name: 'Return fee' }))
    await user.type(screen.getByTestId('fee-amount-refund'), '20')
    await user.click(within(exchanges).getByRole('switch', { name: 'Same price or lower only' }))
    expect(lines()).toEqual(expect.arrayContaining([
      'Customers can start a return within 30 days of delivery.',
      'Requests are approved automatically, and Traced books a Bosta pickup straight away.',
      'A return fee of EGP 20 is taken off the refund, except when it\'s the store\'s fault.',
      'They can only pick an item that costs the same or less.',
    ]))
    // Nothing was saved by any of that (Pass A: saving is unchanged).
    expect(calls.some(c => c.method !== 'GET')).toBe(false)
  })

  test('Percentage in the fee table shows the stored min / max under the row', async () => {
    fees = { ...fees, visible: true, rules: { exchange: null,
      refund: rule('refund', { mode: 'percent', amount: null, percent: 10, minAmount: 20, maxAmount: 300 }) as never } }
    const user = await open()
    const row = await screen.findByTestId('fee-rule-refund')
    expect(row.querySelector('#fee-min-refund')).toHaveValue(20)
    expect(row.querySelector('#fee-max-refund')).toHaveValue(300)
    await user.click(within(row).getByRole('radio', { name: 'Fixed amount' }))
    expect(row.querySelector('#fee-min-refund')).toBeNull()
    await user.click(within(row).getByRole('radio', { name: 'Percentage' }))
    expect(row.querySelector('#fee-min-refund')).toHaveValue(20)
  })

  test('manager: fee parts read-only', async () => {
    roleMock.role = 'manager'
    fees = { ...fees, visible: true }
    await open()
    const refunds = document.getElementById('portal-refunds')!
    await within(refunds).findByTestId('fee-rules-table')
    expect(within(refunds).queryByRole('button', { name: 'Save' })).toBeNull()
    for (const sw of within(refunds).getAllByRole('switch')) expect(sw).toBeDisabled()
    expect(within(document.getElementById('portal-exchanges')!).getByRole('radio', { name: /^Any product in stock/ })).toBeDisabled()
  })
})

describe('Returns portal — approval cards and saving', () => {
  test('the cards write the same autoApprove field "Save changes" sends', async () => {
    const user = await open()
    const manual = screen.getByRole('radio', { name: /^I review each request/ })
    const auto = screen.getByRole('radio', { name: /^Approve automatically/ })
    expect(manual).toHaveAttribute('aria-checked', 'true')
    expect(auto).toHaveTextContent('the Bosta pickup is booked right away')     // the saved booking is on
    await user.click(auto)
    expect(auto).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(lastPut()).toBeDefined())
    expect(lastPut()!.body).toMatchObject({ autoApprove: true })
    expect(lastPut()!.body).not.toHaveProperty('pickupBooking')
  })

  test('arrow keys move between the cards', async () => {
    const user = await open()
    screen.getByRole('radio', { name: /^I review each request/ }).focus()
    await user.keyboard('{ArrowRight}')
    const auto = screen.getByRole('radio', { name: /^Approve automatically/ })
    expect(auto).toHaveAttribute('aria-checked', 'true')
    expect(auto).toHaveFocus()
  })

  test('"Saves right away" marks only what saves on use', async () => {
    fees = { ...fees, visible: true }
    await open()
    await screen.findByTestId('fee-area-overrides')
    expect(screen.getAllByTestId('saves-right-away')).toHaveLength(3)
    expect(screen.getByTestId('fee-area-overrides')).toContainElement(screen.getAllByTestId('saves-right-away')[1])
    expect(document.getElementById('portal-branding')!).toHaveTextContent('LogoSaves right away')
    expect(document.getElementById('portal-eligibility')!).toHaveTextContent('Products that can\'t be returnedSaves right away')
    // Not on rows saved with "Save changes".
    expect(document.getElementById('portal-approval')!.querySelector('[data-testid=saves-right-away]')).toBeNull()
  })
})

describe('Returns portal — branding notes', () => {
  test('policy text vs return window, Latin and Arabic-Indic digits', async () => {
    const user = await open()
    const policy = screen.getByLabelText('Return policy')
    await user.type(policy, 'Return after 30 days')
    expect(screen.getByTestId('policy-mismatch'))
      .toHaveTextContent('Your policy text says “30 days”, but the return window is 14 days. Customers see both — make them match.')
    await user.clear(policy)
    await user.type(policy, 'الإرجاع خلال ١٤ يوم')
    expect(screen.queryByTestId('policy-mismatch')).toBeNull()
    await user.clear(policy)
    await user.type(policy, 'الإرجاع خلال ٧ أيام')
    expect(screen.getByTestId('policy-mismatch')).toHaveTextContent('“٧ أيام”')
  })

  test('colour notes follow the live portal: dark text, default blue, none', async () => {
    const user = await open()
    const hex = screen.getByLabelText('Brand colour')
    expect(screen.queryByTestId(/colour-note/)).toBeNull()
    await user.clear(hex)
    await user.type(hex, '#FF9D98')
    expect(screen.getByTestId('colour-note-dark-text')).toHaveTextContent('Light colour — button text switches to dark')
    expect(screen.getByTestId('preview-en-button').style.color).toBe('var(--on-brand)')
    expect(screen.getByTestId('preview-en').style.getPropertyValue('--on-brand')).toBe('#141821')
    await user.clear(hex)
    await user.type(hex, '#E0457B')
    expect(screen.getByTestId('colour-note-fallback')).toHaveTextContent('uses the default blue')
    expect(screen.getByTestId('preview-en').style.getPropertyValue('--brand')).toBe('#3656E0')
  })

  test('the preview shows the return window being edited', async () => {
    const user = await open()
    expect(screen.getByTestId('preview-en-window')).toHaveTextContent('Returns are accepted within 14 days of delivery.')
    const window = screen.getByLabelText('Return window')
    await user.clear(window)
    await user.type(window, '1')
    expect(screen.getByTestId('preview-en-window')).toHaveTextContent('Returns are accepted within 1 day of delivery.')
  })
})

describe('Returns portal — jump links', () => {
  test('scroll to the section and focus its heading; ?tab=portal is kept', async () => {
    const user = await open()
    const nav = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(nav).getAllByRole('link').map(a => a.textContent))
      .toEqual(['Eligibility', 'Approval & pickup', 'Exchanges', 'Refunds & fees', 'Branding'])
    await user.click(within(nav).getByRole('link', { name: 'Refunds & fees' }))
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Refunds & fees' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Returns portal' })).toHaveAttribute('aria-current', 'page')
  })
})

describe('Returns portal — Arabic', () => {
  test('Arabic copy, plural forms and the AR preview', async () => {
    await i18n.changeLanguage('ar')
    settings = { ...settings, returnWindowDays: 2, policyText: 'الإرجاع خلال ١٤ يوم' }
    await open()
    expect(screen.getByRole('heading', { name: 'بوابة الإرجاع' })).toBeInTheDocument()
    expect(screen.getByTestId('portal-status')).toHaveTextContent('مفعّلة')
    expect(lines()[0]).toBe('يمكن للعملاء بدء الإرجاع خلال يومين من التوصيل.')
    expect(screen.getByTestId('policy-mismatch'))
      .toHaveTextContent('يذكر نص سياستك «١٤ يوم»، لكن مدة الإرجاع يومان. يرى العملاء الاثنين — اجعلهما متطابقين.')
    // The preview starts in the app's language.
    expect(screen.getByTestId('preview-ar')).toHaveAttribute('dir', 'rtl')
    expect(screen.getByTestId('preview-ar')).toHaveTextContent('ابدأ الإرجاع')
    expect(screen.getByRole('button', { name: 'بوابة الإرجاع' })).toHaveAttribute('aria-current', 'page')
  })
})
