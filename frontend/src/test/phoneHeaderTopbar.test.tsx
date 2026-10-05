import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { PackSessionView, ScanPairingStatus } from '../api'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import Layout from '../components/Layout'
import Overview from '../pages/Overview'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import Fulfill from '../pages/Fulfill'

// Phone as scanner — no floating control: the "Use phone" / connected button lives in the scan
// screens' own headers (pack session, PickScreen) and opens the QR modal; Layout's top bar shows a
// phone icon only while a phone is paired (status + Unpair); a non-scanning page (Overview) shows
// nothing phone-related while unpaired.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})
vi.mock('../phone/relayStream', () => ({ openRelayStream: (_d: string, _h: RelayHandlers) => () => {} }))

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

const NONE: ScanPairingStatus = { status: 'none', pairingId: null, deviceLabel: null, pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }
const CONNECTED: ScanPairingStatus = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari',
  pairCodeExpiresAt: null, claimedAt: null, expiresAt: null, reason: null }

const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-05T07:00:00Z', workerName: 'Ahmed',
  counters: { packed: 0, setAside: 0, rejected: 0, left: 3 }, recent: [], openOrder: null } as unknown as PackSessionView
const PICK = { id: 'order-1', number: '#1047', customer_name: 'Alice', customer_phone: null, status: 'new', payment_method: null,
  cod_amount: null, locked_by: null, is_self_pickup: false, cancel_requested_at: null, shipment_id: null, tracking_number: null,
  shipment_has_courier: true, awbPrinted: false, items: [{ id: 'i1', variant_id: 'v1', sku: 'S', variant_title: 'M',
    product_title: 'Shirt', quantity: 2, allocated: 0, allocatedPieces: [] }] }


// Overview's own endpoints (a cut of overview.test.tsx's fixtures).
const day = (i: number) => new Date(Date.UTC(2026, 9, 5 - (13 - i))).toISOString().slice(0, 10)
const series = () => Array.from({ length: 14 }, (_, i) => ({ date: day(i), count: i }))
const OVERVIEW: Array<[string, unknown]> = [
  ['/inventory/status-totals', { statusCounts: { available: 100, reserved: 20, packed: 5, awaiting_pickup: 2,
    with_courier: 3, delivered: 50, return_in_transit: 0, return_pending_inspection: 4, damaged: 6, lost: 2,
    destroyed: 0, out_on_transfer: 0, sold: 1 } }],
  ['/inventory/valuation', { lowStockCount: 3, inventoryValue: 15000, variantsCosted: 8, variantsTotal: 10 }],
  ['/orders/funnel', { newCount: 5, picking: 2, packed: 1, courier: 3, delivered: 1 }],
  ['/overview/trends', ['orders', 'delivered', 'returns', 'exchanges', 'exceptions'].map(metric => ({ metric, total: 3, series: series() }))],
  ['/overview/late-to-pack', { overdue: 0, over48: 0 }],
  ['/overview/top-skus', []],
  ['/orders/summary', { total: 10, processing: 5, withCourier: 3, delivered: 2, returned: 0 }],
  ['/exceptions?', []],
  ['/orders?', { items: [], page: 0, size: 20, total: 0 }],
  ['/connections', { shopify: { connected: false, storeId: null, shopDomain: null, connectionType: null,
    status: 'disconnected', importStatus: null, lastSyncAt: null },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: false,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '2026-04', scopes: [] } }],
  ['/locations', []],
]

let pairing: ScanPairingStatus
let calls: Array<{ method: string; url: string }>

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
  pairing = NONE
  calls = []
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    calls.push({ method, url })
    if (url.includes('/station/pairings/current') && method === 'GET') return json(pairing)
    if (url.includes('/station/pairings/current') && method === 'DELETE') return json({})
    if (url.endsWith('/station/pairings') && method === 'POST') {
      return json({ pairingId: 'p1', pairUrl: 'https://app.tracedtech.com/scan/AbC_123-xyz',
        pairCodeExpiresAt: new Date(Date.now() + 90_000).toISOString(), expiresAt: new Date(Date.now() + 3600_000).toISOString() })
    }
    if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
    if (url.endsWith('/fulfill/queue')) return json([{ id: 'order-1', number: '#1047', customer_name: 'Alice', status: 'new',
      payment_method: null, cod_amount: null, total_units: 2, scanned_units: 0, locked_by: null, locked_at: null,
      is_self_pickup: false, is_exchange: false }])
    if (url.endsWith('/fulfill/order-1')) return json(PICK)
    const overview = OVERVIEW.find(([path]) => url.includes(path))
    if (overview) return json(overview[1])
    return json({})
  }))
})
afterEach(() => { clearAccessToken(); vi.unstubAllGlobals() })

function withPhone(ui: React.ReactNode) {
  return renderWithProviders(<PhoneScanProvider>{ui}<PhoneControl /></PhoneScanProvider>)
}

describe('the header phone button', () => {
  test('pack session: "Use phone" sits in the session header and opens the QR modal', async () => {
    const user = userEvent.setup()
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    const header = (await screen.findByText('Pack session')).closest('div.bg-panel') as HTMLElement
    const button = await within(header).findByRole('button', { name: 'Use phone' })
    expect(within(header).getByRole('button', { name: 'End session' })).toBeInTheDocument()
    await user.click(button)
    expect(await screen.findByTestId('pair-qr')).toHaveAttribute('data-value', 'https://app.tracedtech.com/scan/AbC_123-xyz')
  })

  test('PickScreen: "Use phone" sits in the order header next to Cancel Order and opens the QR modal', async () => {
    const user = userEvent.setup()
    withPhone(<Fulfill />)
    await user.click(await screen.findByText('#1047'))
    const pick = await screen.findByTestId('fulfill-pick')
    const header = within(pick).getByText('Cancel Order').parentElement as HTMLElement
    await user.click(await within(header).findByRole('button', { name: 'Use phone' }))
    expect(await screen.findByTestId('pair-qr')).toBeInTheDocument()
  })

  test('paired: the header shows "Phone connected · <device>"; its menu unpairs', async () => {
    pairing = CONNECTED
    const user = userEvent.setup()
    withPhone(<PackSessionScreen initial={VIEW} onEnded={() => {}} />)
    const chip = await screen.findByTestId('phone-chip')
    expect(chip).toHaveTextContent('Phone connected · iPhone · Safari')
    await user.click(chip)
    await user.click(within(screen.getByTestId('phone-button-menu')).getByRole('button', { name: 'Unpair' }))
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url.includes('/station/pairings/current'))).toBe(true))
    expect(await screen.findByRole('button', { name: 'Use phone' })).toBeInTheDocument()
  })
})

describe('the top bar phone icon', () => {
  test('hidden while no phone is paired; shown while paired, with status and Unpair; gone after Unpair', async () => {
    pairing = CONNECTED
    const user = userEvent.setup()
    withPhone(<Layout><p>page</p></Layout>)
    const icon = await screen.findByRole('button', { name: 'Phone connected · iPhone · Safari' })
    await user.click(icon)
    const menu = screen.getByTestId('phone-topbar-menu')
    expect(menu).toHaveTextContent('Phone connected · iPhone · Safari')
    await user.click(within(menu).getByRole('button', { name: 'Unpair' }))
    await waitFor(() => expect(screen.queryByTestId('phone-topbar')).toBeNull())
    expect(calls.some(c => c.method === 'DELETE' && c.url.includes('/station/pairings/current'))).toBe(true)
  })

  test('a waiting (unclaimed) pairing shows no top bar icon', async () => {
    pairing = { ...CONNECTED, status: 'waiting', deviceLabel: null }
    withPhone(<Layout><p>page</p></Layout>)
    await waitFor(() => expect(calls.some(c => c.url.includes('/station/pairings/current'))).toBe(true))
    await new Promise(r => setTimeout(r, 50))
    expect(screen.queryByTestId('phone-topbar')).toBeNull()
  })
})

test('Overview while unpaired: nothing phone-related renders', async () => {
  withPhone(<Layout><Overview /></Layout>)
  await waitFor(() => expect(calls.some(c => c.url.includes('/station/pairings/current'))).toBe(true))
  await new Promise(r => setTimeout(r, 50))
  expect(screen.queryByTestId('phone-topbar')).toBeNull()
  expect(screen.queryByTestId('phone-button')).toBeNull()
  expect(screen.queryByTestId('phone-chip')).toBeNull()
  expect(screen.queryByRole('button', { name: /phone/i })).toBeNull()
  expect(screen.queryByText(/Use phone|Phone connected|Waiting for the phone/)).toBeNull()
})
