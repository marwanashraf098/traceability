import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import i18n from '../i18n'
import { renderWithProviders, waitFor } from './renderWithProviders'
import CustomerFeesSection from '../pages/settings/CustomerFeesSection'
import type { CustomerFeeSettings, FeeRule } from '../api'

// Prod report 2026-11-06 — Settings → Returns portal → Customer fees: choosing "Percentage" blanked the whole page.
//   modes    — fixed ⇄ percent both ways, on an empty rule and on a filled one (EN + AR): the fields swap, nothing
//              crashes, the other rule and the rest of the card stay
//   save     — percent with min / max, and without (null) — the exact PUT body; values sent as strings load too
//   areas    — an override in percent mode: the add form POSTs percent (no amount); a percent row is described
//   boundary — a render crash in the fee cards shows "Something went wrong — Reload" in place of the cards only;
//              the rest of the settings page stays (revert-checked: without the boundary the page is gone)

const roleMock = vi.hoisted(() => ({ role: 'owner' as 'owner' | 'manager' }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => roleMock.role) }
})

const FILLED = (kind: 'exchange' | 'refund'): FeeRule => ({
  kind, enabled: true, mode: 'fixed', amount: 100, percent: null, minAmount: null, maxAmount: null, waiveMerchantFault: true,
} as FeeRule)

const BASE: CustomerFeeSettings = {
  visible: true,
  rules: { exchange: null, refund: null },
  merchantFaultReasons: ['damaged'],
  overrides: [],
  exchangePricing: { anyProduct: true, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
}

let settings: CustomerFeeSettings
let calls: Array<{ method: string; url: string; body?: unknown }>
let consoleErrors: string[]

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
  if (url.includes('/customer-fees/areas?cityId=C1')) return fakeResponse([{ id: 'D1', name: 'Nasr City', nameAr: 'مدينة نصر' }])
  if (url.includes('/customer-fees/areas') && method === 'GET') return fakeResponse([{ id: 'C1', name: 'Cairo', nameAr: 'القاهرة' }])
  return fakeResponse(settings)
}

const writes = () => calls.filter(c => c.method !== 'GET')

beforeEach(async () => {
  roleMock.role = 'owner'
  settings = structuredClone(BASE)
  calls = []
  consoleErrors = []
  vi.stubGlobal('fetch', vi.fn(backend))
  vi.spyOn(console, 'error').mockImplementation((...a: unknown[]) => { consoleErrors.push(a.map(String).join(' ')) })
  await i18n.changeLanguage('en')
})

afterEach(async () => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  await i18n.changeLanguage('en')
})

function render() {
  return renderWithProviders(<I18nextProvider i18n={i18n}><CustomerFeesSection /></I18nextProvider>)
}

const mode = (m: 'fixed' | 'percent') => i18n.t(`settings.portal.fees.mode.${m}`)

for (const lang of ['en', 'ar'] as const) {
  describe(`modes (${lang})`, () => {
    beforeEach(async () => { await i18n.changeLanguage(lang) })

    for (const filled of [false, true]) {
      test(`${filled ? 'filled' : 'empty'} rule: fixed → percent → fixed`, async () => {
        if (filled) settings.rules = { exchange: FILLED('exchange'), refund: FILLED('refund') }
        const user = userEvent.setup()
        render()
        for (const kind of ['exchange', 'refund'] as const) {
          const rule = await screen.findByTestId(`fee-rule-${kind}`)
          await user.click(within(rule).getByRole('radio', { name: mode('percent') }))
          expect(within(rule).getByTestId(`fee-percent-${kind}`)).toHaveValue(null)
          expect(within(rule).queryByTestId(`fee-amount-${kind}`)).toBeNull()
          expect(rule.querySelector(`#fee-min-${kind}`)).not.toBeNull()
          expect(rule.querySelector(`#fee-max-${kind}`)).not.toBeNull()
          expect(rule).toHaveTextContent(i18n.t('settings.portal.fees.min'))
          expect(rule).toHaveTextContent(i18n.t('settings.portal.fees.waivePercentHelp'))

          await user.click(within(rule).getByRole('radio', { name: mode('fixed') }))
          expect(within(rule).getByTestId(`fee-amount-${kind}`)).toHaveValue(filled ? 100 : null)
          expect(within(rule).queryByTestId(`fee-percent-${kind}`)).toBeNull()
        }
        expect(screen.getByTestId('fee-area-overrides')).toBeInTheDocument()
        expect(screen.queryByTestId('customer-fees-crashed')).toBeNull()
        expect(consoleErrors).toEqual([])
      })
    }

    test('a stored percent rule opens in percent mode and switches to fixed', async () => {
      settings.rules = { exchange: null, refund: { ...FILLED('refund'), mode: 'percent', amount: null, percent: 10, minAmount: 20, maxAmount: 300 } }
      const user = userEvent.setup()
      render()
      const rule = await screen.findByTestId('fee-rule-refund')
      expect(within(rule).getByTestId('fee-percent-refund')).toHaveValue(10)
      await user.click(within(rule).getByRole('radio', { name: mode('fixed') }))
      expect(within(rule).getByTestId('fee-amount-refund')).toHaveValue(null)
      await user.click(within(rule).getByRole('radio', { name: mode('percent') }))
      expect(within(rule).getByTestId('fee-percent-refund')).toHaveValue(10)
      expect(consoleErrors).toEqual([])
    })
  })
}

describe('save', () => {
  test('percent with min and max', async () => {
    settings.rules = { exchange: null, refund: FILLED('refund') }
    const user = userEvent.setup()
    render()
    const rule = await screen.findByTestId('fee-rule-refund')
    await user.click(within(rule).getByRole('radio', { name: mode('percent') }))
    await user.type(within(rule).getByTestId('fee-percent-refund'), '12.5')
    await user.type(rule.querySelector('#fee-min-refund') as HTMLElement, '20')
    await user.type(rule.querySelector('#fee-max-refund') as HTMLElement, '300')
    await user.click(within(rule).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toMatchObject({ method: 'PUT', body: {
      enabled: true, mode: 'percent', waiveMerchantFault: true, amount: null, percent: 12.5, minAmount: 20, maxAmount: 300 } })
  })

  test('percent without min / max sends null for both', async () => {
    const user = userEvent.setup()
    render()
    const rule = await screen.findByTestId('fee-rule-exchange')
    await user.click(within(rule).getByRole('radio', { name: mode('percent') }))
    await user.type(within(rule).getByTestId('fee-percent-exchange'), '10')
    await user.click(within(rule).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({
      enabled: false, mode: 'percent', waiveMerchantFault: true, amount: null, percent: 10, minAmount: null, maxAmount: null })
  })

  test('amounts sent as strings load, and an unchanged rule is not dirty', async () => {
    settings.rules = { exchange: null,
      refund: { ...FILLED('refund'), mode: 'percent', amount: null, percent: '10.00', minAmount: '20', maxAmount: null } as unknown as FeeRule }
    render()
    const rule = await screen.findByTestId('fee-rule-refund')
    expect(within(rule).getByTestId('fee-percent-refund')).toHaveValue(10)
    expect(within(rule).getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(consoleErrors).toEqual([])
  })
})

for (const lang of ['en', 'ar'] as const) {
  describe(`areas (${lang})`, () => {
    beforeEach(async () => { await i18n.changeLanguage(lang) })

    test('add a percent override; a percent row is described', async () => {
      settings.overrides = [{ id: 'o1', kind: 'refund', cityId: 'C1', cityName: 'Cairo', cityNameAr: 'القاهرة', districtId: null,
        districtName: null, districtNameAr: null, mode: 'percent', amount: null, percent: 10, minAmount: 20, maxAmount: 300 }] as never
      const user = userEvent.setup()
      render()
      const row = await screen.findByTestId('fee-area-row')
      expect(row).toHaveTextContent(i18n.t('settings.portal.fees.percentLabel', { percent: 10 }))
      expect(row).toHaveTextContent(i18n.t('settings.portal.fees.minLabel', { amount: 20 }))

      await user.click(screen.getByRole('button', { name: i18n.t('settings.portal.fees.areas.add') }))
      const form = await screen.findByTestId('add-area-form')
      await waitFor(() => expect(within(form).getByTestId('area-city')).not.toBeDisabled())
      await user.selectOptions(within(form).getByTestId('area-city'), 'C1')
      await user.click(within(form).getByRole('radio', { name: mode('percent') }))
      expect(within(form).queryByTestId('area-amount')).toBeNull()
      await user.type(within(form).getByTestId('area-percent'), '15')
      await user.click(within(form).getByRole('radio', { name: mode('fixed') }))
      await user.click(within(form).getByRole('radio', { name: mode('percent') }))
      expect(within(form).getByTestId('area-percent')).toHaveValue(15)
      await user.click(within(form).getByRole('button', { name: i18n.t('settings.portal.fees.areas.save') }))
      await waitFor(() => expect(writes()).toHaveLength(1))
      expect(writes()[0]).toMatchObject({ method: 'POST', body: {
        kind: 'refund', cityId: 'C1', districtId: null, mode: 'percent', amount: null, percent: 15, minAmount: null, maxAmount: null } })
      expect(consoleErrors).toEqual([])
    })
  })
}

describe('boundary', () => {
  for (const lang of ['en', 'ar'] as const) {
    test(`a render crash replaces only the fee cards (${lang})`, async () => {
      await i18n.changeLanguage(lang)
      settings.overrides = {} as never            // not a list → the overrides table throws while rendering
      renderWithProviders(
        <I18nextProvider i18n={i18n}>
          <div>
            <p data-testid="rest-of-settings">Portal branding</p>
            <CustomerFeesSection />
          </div>
        </I18nextProvider>)
      const crashed = await screen.findByTestId('customer-fees-crashed')
      expect(crashed).toHaveTextContent(i18n.t('settings.portal.fees.crashed'))
      expect(within(crashed).getByRole('button', { name: i18n.t('settings.portal.fees.reload') })).toBeInTheDocument()
      expect(screen.getByTestId('rest-of-settings')).toBeInTheDocument()
      expect(screen.queryByTestId('customer-fees-section')).toBeNull()
      expect(consoleErrors.some(e => e.includes('Customer fees settings crashed'))).toBe(true)
    })
  }
})
