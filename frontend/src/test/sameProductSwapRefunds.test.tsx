import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import CustomerFeesSection from '../pages/settings/CustomerFeesSection'
import ReturnsPortalTab from '../pages/settings/ReturnsPortalTab'
import type { CustomerFeeSettings, PortalSettings } from '../api'

// V223 — "Same-product swaps can refund the customer" (Settings → Returns portal → Exchange pricing).
//   shown   — only while free size and colour swaps are off (it only matters then)
//   sent    — the PUT carries sameProductSwapRefunds only when it changed (left out = kept); EN + AR copy
// Harness as customerFees.test.tsx.
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

// Redesign Pass B — what the Returns portal tab also loads around the fee cards.
const PORTAL: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
  exchangesEnabled: true,   // exchange pricing shows only while exchanges are on
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
  if (url.endsWith('/tenant/portal-settings') && method === 'GET') return fakeResponse(PORTAL)
  if (url.includes('/variants?') && method === 'GET') return fakeResponse({ items: [], total: 0 })
  if (url.includes('/bosta/return-locations')) return fakeResponse([])
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

/** The Returns portal tab with the fee cards in it (redesign Pass B: one "Save changes" for the tab). */
async function renderTab() {
  renderWithProviders(<ReturnsPortalTab />)
  await screen.findByTestId('fee-rules-table')
}

describe('same-product swap refunds setting', () => {
  test('shown: hidden while free swaps are on, appears when they are switched off', async () => {
    const user = userEvent.setup()
    await renderTab()
    const box = screen.getByTestId('exchange-pricing')
    expect(within(box).queryByRole('switch', { name: 'Same-product swaps can refund the customer' })).toBeNull()
    await user.click(within(box).getByRole('switch', { name: 'Free size and colour swaps' }))
    const refunds = within(box).getByRole('switch', { name: 'Same-product swaps can refund the customer' })
    expect(refunds).not.toBeChecked()
  })

  test('sent: switching it on sends true; an unrelated change leaves it out', async () => {
    const user = userEvent.setup()
    settings.exchangePricing = { ...settings.exchangePricing, freeVariantSwaps: false, sameProductSwapRefunds: false }
    await renderTab()
    const box = screen.getByTestId('exchange-pricing')
    await user.click(within(box).getByRole('switch', { name: 'Same-product swaps can refund the customer' }))
    expect(writes()).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: false, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: false,
      sameProductSwapRefunds: true })
  })

  test('sent: another pricing change does not carry it', async () => {
    const user = userEvent.setup()
    settings.exchangePricing = { ...settings.exchangePricing, freeVariantSwaps: false, sameProductSwapRefunds: true }
    await renderTab()
    const box = screen.getByTestId('exchange-pricing')
    expect(within(box).getByRole('switch', { name: 'Same-product swaps can refund the customer' })).toBeChecked()
    await user.click(within(box).getByRole('radio', { name: /^Any product in stock/ }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toEqual({ anyProduct: true, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: false })
  })
})
