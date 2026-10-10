import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import SettingsPage from '../pages/settings/SettingsPage'
import CustomerRefundChoice from '../pages/exchangesRefunds/CustomerRefundChoice'
import { RefundForm } from '../pages/exchangesRefunds/RequestLifecycle'
import type { PortalSettings, ReturnRequestDetail } from '../api'

// P2 — merchant side (design/Traced_portal_refund_method_dc.html a + c): Settings → Refund methods,
// the drawer's "Customer asked for" block, and the refund form prefilled with the customer's method.

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const IBAN = 'EG380019000500000000263180002'

let settings: PortalSettings
let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>
let refundDetails: { status: number; body: unknown }

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
  if (url.endsWith('/refund-details')) return json(refundDetails.body, refundDetails.status)
  if (url.includes('/tenant/portal-settings') && method === 'PUT') { settings = { ...settings, ...body }; return json(settings) }
  if (url.includes('/tenant/portal-settings')) return json(settings)
  if (url.includes('/variants?')) return json({ items: [], total: 0 })
  return json({})
}

beforeEach(() => {
  settings = {
    slug: 'thesnouts', enabled: true, autoApprove: false, returnWindowDays: 14, logoUrl: null, brandColor: null,
    policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
    font: 'cairo', storeName: 'The Snouts', logo: null, refundMethods: [],
  }
  calls = []
  refundDetails = { status: 200, body: { method: 'bank_transfer', holderName: 'Mona Adel', bankName: 'CIB', account: IBAN } }
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(() => { vi.unstubAllGlobals() })

function detail(extra: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-2HQ8XA', orderId: 'o-1', orderNumber: '#1052', customerName: 'Mona Adel',
    customerPhone: null, type: 'refund', status: 'refund_pending', email: null, note: null, createdAt: '2026-09-22T12:14:00Z',
    items: [], refunds: [], refundTotal: '0.00', currency: 'EGP',
    refundMethod: 'bank_transfer', refundHint: '••••0002', refundDetailsAvailable: true, refundDetailsPurgedAt: null,
    ...extra,
  } as ReturnRequestDetail
}

describe('Settings → Refund methods', () => {
  test('checkboxes; Save sends the ticked methods in order; unchanged → not sent', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
      { initialEntries: ['/settings?tab=portal'] })
    await screen.findByDisplayValue('thesnouts')
    const card = screen.getByTestId('refund-methods-settings')
    expect(card).toHaveTextContent("Customers asking for a refund choose one of these. Traced doesn't send money — you refund them yourself.")
    expect(within(card).getAllByRole('checkbox').map(c => (c as HTMLInputElement).checked)).toEqual([false, false, false, false])

    await user.click(within(card).getByRole('checkbox', { name: /^Cash/ }))
    await user.click(within(card).getByRole('checkbox', { name: /^InstaPay/ }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    expect(calls.filter(c => c.method === 'PUT').pop()!.body).toMatchObject({ refundMethods: ['instapay', 'cash'] })

    // Change something else only → refundMethods not in the body.
    await user.type(screen.getByLabelText('Return policy'), 'x')
    await user.click(await screen.findByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(calls.filter(c => c.method === 'PUT')).toHaveLength(2))
    expect(calls.filter(c => c.method === 'PUT').pop()!.body).not.toHaveProperty('refundMethods')
  })
})

describe('Request drawer — "Customer asked for"', () => {
  test('method + hint only until Show details; the details come from the endpoint; Hide forgets them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CustomerRefundChoice detail={detail()} />)
    expect(screen.getByTestId('refund-choice-summary')).toHaveTextContent('Bank transfer · ••••0002')
    expect(screen.queryByText(/EG38/)).toBeNull()
    expect(calls.some(c => c.url.endsWith('/refund-details'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Show details' }))
    const rows = await screen.findByTestId('refund-choice-details')
    expect(rows).toHaveTextContent('Account holderMona Adel')
    expect(rows).toHaveTextContent('EG38 0019 0005 0000 0000 2631 8000 2')
    expect(calls.filter(c => c.url.endsWith('/return-requests/rr-1/refund-details'))).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Hide details' }))
    expect(screen.queryByTestId('refund-choice-details')).toBeNull()
    expect(screen.queryByText(/EG38/)).toBeNull()
  })

  test('purged: removed note, no Show details; privacy request: no hint; cash: no button', () => {
    const { unmount } = renderWithProviders(<CustomerRefundChoice detail={detail({
      refundDetailsAvailable: false, refundDetailsPurgedAt: '2026-09-09T10:00:00Z' })} />)
    expect(screen.getByTestId('refund-choice-removed')).toHaveTextContent(/^Details removed on Sep 9, 2026 — 30 days after the request ended\.$/)
    expect(screen.queryByRole('button', { name: 'Show details' })).toBeNull()
    unmount()

    const r2 = renderWithProviders(<CustomerRefundChoice detail={detail({
      refundHint: null, refundDetailsAvailable: false, refundDetailsPurgedAt: '2026-09-09T10:00:00Z' })} />)
    expect(screen.getByTestId('refund-choice-removed')).toHaveTextContent('Details removed (privacy request).')
    expect(screen.getByTestId('refund-choice-summary')).toHaveTextContent(/^Bank transfer$/)
    r2.unmount()

    renderWithProviders(<CustomerRefundChoice detail={detail({ refundMethod: 'cash', refundHint: null, refundDetailsAvailable: false })} />)
    expect(screen.getByTestId('refund-choice-summary')).toHaveTextContent(/^Cash$/)
    expect(screen.queryByRole('button', { name: 'Show details' })).toBeNull()
  })

  test('no refund method → no block', () => {
    renderWithProviders(<CustomerRefundChoice detail={detail({ refundMethod: null, refundHint: null })} />)
    expect(screen.queryByTestId('customer-refund-choice')).toBeNull()
  })

  test('the refund form starts on the customer\'s method (changeable); the details never enter it', () => {
    renderWithProviders(<RefundForm detail={detail({ refundMethod: 'wallet', refundHint: '••••4521' })} suggestion={null}
      onSaved={async () => {}} />)
    expect(screen.getByRole('radio', { name: 'Wallet' })).toBeChecked()
    expect(screen.getByTestId('refund-method-prefilled'))
      .toHaveTextContent("Filled in from the customer's choice — change it if you paid another way.")
    expect(screen.getByLabelText(/Reference/)).toHaveValue('')
  })
})
