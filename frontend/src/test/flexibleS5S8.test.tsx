import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { ToastProvider } from '../components/ui'
import * as api from '../api'
import LookupPage from '../pages/Lookup'
import SettingsPage from '../pages/settings/SettingsPage'
import Receiving from '../pages/Receiving'

/**
 * Flexible labeling slices 5 + 8 — the screen side.
 *   lk1  Lookup history: a label bound at packing reads "Label P000033 attached at packing · order #1002"; one bound
 *        in Tag stock reads "Label … attached in Tag stock" (the row shows the person already)
 *   sl1  Settings: "Labels" only with the switch on; the default receiving mode is saved (owner) …
 *   sl2  … and by a manager too
 *   rc1  Receiving step 1 pre-selects the saved default ("Your default"); the other card still works
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

let calls: { method: string; url: string; body?: unknown }[]
let labelDefault: 'print_stick' | 'count_only'

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/label-settings') && method === 'PUT') {
    labelDefault = body.receivingDefaultMode
    return json(body)
  }
  if (url.endsWith('/label-settings')) return json({ receivingDefaultMode: labelDefault })
  if (url.endsWith('/receiving/sessions') && method === 'POST') return json({ sessionId: 'sess-1' }, 201)
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/sess-1')) {
    return json({ id: 'sess-1', status: 'open', mode: 'print_stick', reference: null, supplier_name: null,
      location_name: 'Main', created_at: new Date().toISOString(), finalized_at: null, line_units: 0, piece_count: 0, lines: [] })
  }
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main', is_fulfillment: true }])
  if (url.includes('/catalog')) return json({ products: [], nextCursor: null })
  if (url.includes('/tenant/settings')) return json({ pickPackMode: 'order_queue' })
  return json({})
}

function setup(flexibleLabeling: boolean, role: 'owner' | 'manager' = 'owner') {
  calls = []
  labelDefault = 'print_stick'
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

const event = (overrides: Partial<api.TimelineEvent>): api.TimelineEvent => ({
  id: 1, eventType: 'label_bound', phraseKey: 'label_bound_packing', actor: 'Mona', isSystem: false,
  fromStatus: 'available', toStatus: 'available', orderNumber: '#1002', orderId: 'o1', trackingNumber: null,
  locationName: 'Main', metadata: null, occurredAt: new Date().toISOString(), labelCode: 'P000033', ...overrides,
})

describe('Slice 5 — Lookup history', () => {
  beforeEach(() => setup(true))

  test('lk1 — label bound at packing and in Tag stock read as one line each', async () => {
    vi.spyOn(api, 'lookup').mockResolvedValue({
      type: 'piece', id: 'piece-1', barcode: 'PC-1', status: 'available', receivedAt: new Date().toISOString(),
      variant: { id: 'v1', productTitle: 'Hydrogen', title: 'Default', sku: 'H-1' },
      currentLocation: { id: 'l', name: 'Main' }, currentOrder: null, currentShipment: null, receivingSession: null,
      timeline: [
        event({}),
        event({ id: 2, phraseKey: 'label_bound_tag', orderNumber: null, orderId: null, labelCode: 'P000044', actor: 'Omar' }),
      ],
    } as api.PieceLookupResult)
    renderWithProviders(<LookupPage />)
    await userEvent.type(screen.getByRole('textbox'), 'P000033{Enter}')
    expect(await screen.findByText('Label P000033 attached at packing · order #1002')).toBeTruthy()
    expect(screen.getByText('Label P000044 attached in Tag stock')).toBeTruthy()
    expect(screen.getAllByText('Mona').length).toBeGreaterThan(0)                // the person, on the row itself
  })
})

describe('Slice 8 — Settings › Labels', () => {
  test('sl1 — hidden with the switch off', async () => {
    setup(false)
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=labels'] })
    await screen.findByRole('button', { name: 'Pick & Pack' })
    expect(screen.queryByRole('button', { name: 'Labels' })).toBeNull()
    expect(calls.some(c => c.url.endsWith('/label-settings'))).toBe(false)
  })

  test('sl1b — switch on: the owner saves the default receiving mode', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=labels'] })
    const tab = await screen.findByTestId('labels-settings')
    expect(within(tab).getByTestId('labels-mode-print_stick').querySelector('input')!.checked).toBe(true)
    expect(within(tab).queryByText(/turn flexible labeling|switch it off/i)).toBeNull()        // no self-serve switch
    await user.click(within(tab).getByTestId('labels-mode-count_only'))
    await user.click(within(tab).getByTestId('labels-save'))
    await waitFor(() => expect(calls.find(c => c.method === 'PUT' && c.url.endsWith('/label-settings'))?.body)
      .toEqual({ receivingDefaultMode: 'count_only' }))
    expect(await within(tab).findByRole('status')).toBeTruthy()
  })

  test('sl2 — a manager can change it too', async () => {
    setup(true, 'manager')
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=labels'] })
    const tab = await screen.findByTestId('labels-settings')
    await user.click(within(tab).getByTestId('labels-mode-count_only'))
    expect(within(tab).getByTestId('labels-save')).not.toBeDisabled()
  })
})

describe('Slice 8 — receiving step 1', () => {
  test('rc1 — the saved default is pre-selected with "Your default"; the other card still works', async () => {
    setup(true)
    labelDefault = 'count_only'
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByRole('button', { name: /new/i }))
    const countOnly = await screen.findByTestId('receiving-mode-count_only')
    await waitFor(() => expect(countOnly.getAttribute('aria-pressed')).toBe('true'))
    expect(within(countOnly).getByTestId('receiving-mode-default-count_only').textContent).toBe('Your default')
    expect(screen.getByTestId('receiving-mode-print_stick').getAttribute('aria-pressed')).toBe('false')
    expect(screen.queryByTestId('receiving-mode-default-print_stick')).toBeNull()

    await user.click(screen.getByTestId('receiving-mode-print_stick'))                    // not the default — still works
    expect(screen.getByTestId('receiving-mode-chosen').textContent).toBe('Print & stick now · Change')
    await user.click(screen.getByRole('button', { name: /create/i }))
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/receiving/sessions'))?.body)
      .toMatchObject({ mode: 'print_stick' }))
  })
})
