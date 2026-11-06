import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import CustomerFeesSection from '../pages/settings/CustomerFeesSection'
import type { CustomerFeeSettings } from '../api'

// Returns portal fees F1 — Settings → Returns portal → Exchange pricing + Customer fees.
//   hidden   — visible:false (until fees are charged) or a failed load → nothing renders
//   rules    — the owner saves a fee rule (the exact PUT body); a refusal shows the backend's message
//   reasons  — merchant-fault reasons saved in the portal's order
//   pricing  — "same price or lower" hides the max-difference field and sends null for it;
//              "free size and colour swaps" is sent as stored unless changed, false when switched off
//   areas    — add a district fee (city → districts → POST), remove a row (DELETE)
//   manager  — read-only: no save buttons, owner-only note

const roleMock = vi.hoisted(() => ({ role: 'owner' as 'owner' | 'manager' }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => roleMock.role) }
})

const BASE: CustomerFeeSettings = {
  visible: true,
  rules: { exchange: null, refund: null },
  merchantFaultReasons: ['damaged', 'not_as_pictured', 'wrong_item'],
  overrides: [],
  exchangePricing: { anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
}

let settings: CustomerFeeSettings
let calls: Array<{ method: string; url: string; body?: unknown }>
let reply: ((method: string, url: string, body: unknown) => { status: number; data: unknown }) | null

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
  if (reply && method !== 'GET') {
    const r = reply(method, url, body)
    return fakeResponse(r.data, r.status)
  }
  if (url.includes('/customer-fees/areas?cityId=C1')) return fakeResponse([{ id: 'D1', name: 'Nasr City', nameAr: 'مدينة نصر' }])
  if (url.includes('/customer-fees/areas')) return fakeResponse([{ id: 'C1', name: 'Cairo', nameAr: 'القاهرة' }])
  if (url.endsWith('/customer-fees') && method === 'GET') return fakeResponse(settings)
  return fakeResponse(settings)
}

beforeEach(() => {
  roleMock.role = 'owner'
  settings = structuredClone(BASE)
  calls = []
  reply = null
  vi.stubGlobal('fetch', vi.fn(backend))
})

afterEach(() => { vi.unstubAllGlobals() })

const writes = () => calls.filter(c => c.method !== 'GET')

describe('customer fees settings', () => {
  test('hidden: visible:false renders nothing', async () => {
    settings.visible = false
    renderWithProviders(<CustomerFeesSection />)
    await waitFor(() => expect(calls.length).toBeGreaterThan(0))
    expect(screen.queryByTestId('customer-fees-section')).toBeNull()
  })

  test('hidden: a failed load renders nothing', async () => {
    vi.stubGlobal('fetch', vi.fn(() => fakeResponse({ code: 'X' }, 500)))
    renderWithProviders(<CustomerFeesSection />)
    await new Promise(r => setTimeout(r, 20))
    expect(screen.queryByTestId('customer-fees-section')).toBeNull()
  })

  test('rules: the owner saves a fixed return fee', async () => {
    const user = userEvent.setup()
    reply = () => {
      settings.rules.refund = { kind: 'refund', enabled: true, mode: 'fixed', amount: 50, percent: null, minAmount: null,
        maxAmount: null, waiveMerchantFault: true, updatedAt: '2026-11-05T10:00:00Z' }
      return { status: 200, data: settings }
    }
    renderWithProviders(<CustomerFeesSection />)
    const card = await screen.findByTestId('fee-rule-refund')
    await user.click(within(card).getByRole('switch'))
    await user.type(within(card).getByTestId('fee-amount-refund'), '50')
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toEqual({
      method: 'PUT', url: expect.stringContaining('/tenant/portal-settings/customer-fees/rules/refund'),
      body: { enabled: true, mode: 'fixed', waiveMerchantFault: true, amount: 50, percent: null, minAmount: null, maxAmount: null },
    })
    await waitFor(() => expect(within(card).getByRole('button', { name: 'Save' })).toBeDisabled())
  })

  test('rules: a refusal shows the backend message', async () => {
    const user = userEvent.setup()
    reply = () => ({ status: 400, data: { code: 'FEE_PERCENT_INVALID', message_en: 'Enter a percentage above 0.', message_ar: 'أدخل نسبة.' } })
    renderWithProviders(<CustomerFeesSection />)
    const card = await screen.findByTestId('fee-rule-exchange')
    await user.click(within(card).getByRole('radio', { name: 'Percentage' }))
    await user.type(within(card).getByTestId('fee-percent-exchange'), '0')
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByTestId('error-fee-exchange')).toHaveTextContent('Enter a percentage above 0.')
  })

  test('reasons: saved in the portal order', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CustomerFeesSection />)
    const box = await screen.findByTestId('merchant-fault-reasons')
    await user.click(within(box).getByTestId('reason-wrong_size'))
    await user.click(within(box).getByTestId('reason-damaged'))
    await user.click(within(box).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ reasons: ['wrong_size', 'not_as_pictured', 'wrong_item'] })
  })

  test('pricing: same price or lower hides the max difference and sends null', async () => {
    const user = userEvent.setup()
    settings.exchangePricing.maxDifference = 300
    renderWithProviders(<CustomerFeesSection />)
    const box = await screen.findByTestId('exchange-pricing')
    expect(within(box).getByTestId('max-difference')).toHaveValue(300)
    // "What they can swap to" is a pair of cards on the same anyProduct boolean (redesign Pass A).
    const any = within(box).getByRole('radio', { name: /^Any product in stock/ })
    expect(any).toHaveAttribute('aria-checked', 'false')
    await user.click(any)
    expect(any).toHaveAttribute('aria-checked', 'true')
    await user.click(within(box).getByRole('switch', { name: 'Same price or lower only' }))
    expect(within(box).queryByTestId('max-difference')).toBeNull()
    await user.click(within(box).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: true, sameOrLowerOnly: true, maxDifference: null, freeVariantSwaps: true })
  })

  test('pricing: switching free swaps off sends false', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CustomerFeesSection />)
    const box = await screen.findByTestId('exchange-pricing')
    const swaps = within(box).getByRole('switch', { name: 'Free size and colour swaps' })
    expect(swaps).toBeChecked()
    await user.click(swaps)
    await user.click(within(box).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: false })
  })

  test('areas: add a district fee, remove a row', async () => {
    const user = userEvent.setup()
    settings.overrides = [{ id: 'o1', kind: 'refund', cityId: 'C1', cityName: 'Cairo', cityNameAr: 'القاهرة', districtId: null,
      districtName: null, districtNameAr: null, mode: 'fixed', amount: 0, percent: null, minAmount: null, maxAmount: null }]
    reply = () => ({ status: 200, data: settings })
    renderWithProviders(<CustomerFeesSection />)
    const box = await screen.findByTestId('fee-area-overrides')
    expect(within(box).getByTestId('fee-area-row')).toHaveTextContent('Cairo (all districts)')
    expect(within(box).getByTestId('fee-area-row')).toHaveTextContent('EGP 0')

    await user.click(within(box).getByRole('button', { name: 'Add an area fee' }))
    const form = within(box).getByTestId('add-area-form')
    await waitFor(() => expect(within(form).getByRole('option', { name: 'Cairo' })).toBeInTheDocument())
    await user.selectOptions(within(form).getByTestId('area-kind'), 'exchange')
    await user.selectOptions(within(form).getByTestId('area-city'), 'C1')
    await waitFor(() => expect(within(form).getByRole('option', { name: 'Nasr City' })).toBeInTheDocument())
    await user.selectOptions(within(form).getByTestId('area-district'), 'D1')
    await user.type(within(form).getByTestId('area-amount'), '25')
    await user.click(within(form).getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toEqual({
      method: 'POST', url: expect.stringContaining('/customer-fees/area-overrides'),
      body: { kind: 'exchange', cityId: 'C1', districtId: 'D1', mode: 'fixed', amount: 25, percent: null, minAmount: null, maxAmount: null },
    })

    await user.click(within(box).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(writes()).toHaveLength(2))
    expect(writes()[1]).toMatchObject({ method: 'DELETE', url: expect.stringContaining('/customer-fees/area-overrides/o1') })
  })

  test('manager: read-only', async () => {
    roleMock.role = 'manager'
    renderWithProviders(<CustomerFeesSection />)
    const section = await screen.findByTestId('customer-fees-section')
    expect(within(section).queryByRole('button', { name: 'Save' })).toBeNull()
    expect(within(section).queryByRole('button', { name: 'Add an area fee' })).toBeNull()
    expect(within(section).getAllByText('Only the owner can change these.').length).toBeGreaterThan(0)
    for (const sw of within(section).getAllByRole('switch')) expect(sw).toBeDisabled()
  })
})
