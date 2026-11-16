import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { render } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import type { ReactElement } from 'react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'
import type { ExchangeToMap } from '../api'
import { setAccessToken, clearAccessToken } from '../auth'
import i18n from '../i18n'
import ar from '../locales/ar.json'

// 2026-11-16 — Storm-shape exchanges (pre-connect parent #2739, Bosta type 30 2472106867 "#2739:#2739-R1") in Pick &
// Pack: the mapped replacement EXC-<AWB> is a normal queue row saying "Exchange for #2739"; an unmapped one is listed
// beside the queue under "Exchanges to map" with a "Needs mapping" badge (never as a pickable row), "Map items" opens
// the existing mapping screen, which hands back to Pick & Pack on the new order's pick screen. EN + AR.

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

/** A token whose payload says this role — getRoleFromToken reads only the claims. */
function tokenFor(role: 'owner' | 'manager' | 'worker'): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${b64({ alg: 'none' })}.${b64({ role, sub: 'u-1', exp: 4102444800 })}.sig`
}

const EXC_ROW = {
  id: 'exc-order-1', number: 'EXC-5028197718', customer_name: 'Mariam', status: 'new', payment_method: null,
  cod_amount: null, total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false,
  is_exchange: true, replacementFor: { orderId: 'parent-2922', number: '#2922' },
}
const PLAIN_ROW = {
  id: 'plain-1', number: '#3018', customer_name: 'Omar', status: 'new', payment_method: null, cod_amount: null,
  total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, is_exchange: false,
}
const TO_MAP: ExchangeToMap[] = [{
  exchangeId: 'ex-1', trackingNumber: '2472106867', createdAt: '2026-11-15T22:43:11Z',
  outboundDescription: 'Jeans 32', inboundDescription: 'Jeans 30',
  replacementFor: { orderId: 'parent-2739', number: '#2739' },
}]

let toMap: ExchangeToMap[]

beforeEach(async () => {
  await i18n.changeLanguage('en')
  toMap = []
  setAccessToken(tokenFor('owner'))
  stubFetchWithShellDefaults(vi.fn((url: string, opts?: RequestInit) => {
    const method = (opts?.method ?? 'GET').toUpperCase()
    if (url.endsWith('/fulfill/exchanges-to-map')) return json(toMap)
    if (url.endsWith('/fulfill/waiting-for-release')) return json([])
    if (url.endsWith('/fulfill/queue')) return json([EXC_ROW, PLAIN_ROW])
    if (url.endsWith('/fulfill/exc-order-1') && method === 'GET') return json({
      ...EXC_ROW, customer_phone: null, cancel_requested_at: null, shipment_id: 'sh-1', tracking_number: '5028197718',
      shipment_has_courier: true, awbPrinted: false,
      items: [{ id: 'item-1', variant_id: 'v-32', sku: 'JN-32', variant_title: '32', product_title: 'Jeans',
        quantity: 1, imageUrl: null, allocated: 0, allocatedPieces: [] }] })
    if (url.includes('/api/v1/catalog')) return json({ products: [{ id: 'p-j', title: 'Jeans', status: 'active', imageUrl: null,
      variants: [{ id: 'v-32', title: '32', sku: 'JN-32', price: 950 }, { id: 'v-30', title: '30', sku: 'JN-30', price: 950 }] }] })
    if (url.endsWith('/exchanges/ex-1/map') && method === 'POST') return json({ exchangeId: 'ex-1', orderId: 'exc-order-9', status: 'matched' })
    if (url.includes('/api/v1/exchanges?') && method === 'GET') return json([{ id: 'ex-1', tracking_number: '2472106867',
      outbound_description: 'Jeans 32', inbound_description: 'Jeans 30', inbound_description_ar: null,
      customer_name: null, customer_phone: null }])
    return json({})
  }), { passExchangesToMap: true })
})
afterEach(async () => {
  clearAccessToken()
  vi.unstubAllGlobals()
  await i18n.changeLanguage('en')
})

/** renderWithProviders is English-only; Arabic renders with the app's own i18n instance (as the other AR tests). */
function renderAr(ui: ReactElement) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={['/fulfill']}>
        <I18nextProvider i18n={i18n}>
          <ToastProvider>{ui}</ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

describe('mapped exchange — a normal queue row', () => {
  test('EN: "Exchange for #2922" + the Exchange badge on EXC-5028197718', async () => {
    renderWithProviders(<Fulfill />)
    const cell = await screen.findByText('EXC-5028197718')
    const row = cell.closest('[class*="card"], li, button, div[role="button"]') ?? cell.parentElement!.parentElement!
    expect(row).toHaveTextContent('Exchange for #2922')
    expect(row).toHaveTextContent('Exchange')
    // A plain order says nothing of the kind.
    expect(screen.getAllByTestId('shadow-exchange-for')).toHaveLength(1)
  })

  test('AR: the same row renders in Arabic', async () => {
    await i18n.changeLanguage('ar')
    renderAr(<Fulfill />)
    await screen.findByText('EXC-5028197718')
    const label = (ar as { fulfill: { shadow: { exchangeFor: string } } }).fulfill.shadow.exchangeFor.replace('{{number}}', '#2922')
    expect(screen.getByTestId('shadow-exchange-for')).toHaveTextContent(label)
  })
})

test('?pick=<order> (after mapping) opens its pick screen straight away, saying which order it replaces', async () => {
  renderWithProviders(<Fulfill openOrderId="exc-order-1" />, { initialEntries: ['/fulfill?pick=exc-order-1'] })
  expect(await screen.findAllByText('EXC-5028197718')).not.toHaveLength(0)
  expect(screen.getAllByTestId('shadow-exchange-for')[0]).toHaveTextContent('Exchange for #2922')
  expect(screen.queryByTestId('exchanges-to-map')).toBeNull()
})

describe('unmapped exchange — "Exchanges to map", beside the queue and not in it', () => {
  test('EN: Needs mapping badge, waybill, Exchange for #2739; Map items opens the mapping screen from Pick & Pack', async () => {
    toMap = TO_MAP
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/fulfill" element={<Fulfill />} />
        <Route path="/exchanges/:id" element={<LocationProbe />} />
      </Routes>,
      { initialEntries: ['/fulfill'] },
    )
    const section = await screen.findByTestId('exchanges-to-map')
    expect(section).toHaveTextContent('Exchanges to map (1)')
    const row = within(section).getByTestId('to-map-row-ex-1')
    expect(row).toHaveTextContent('Waybill 2472106867')
    expect(row).toHaveTextContent('Needs mapping')
    expect(row).toHaveTextContent('Exchange for #2739')
    // Not a queue row: the queue's own rows are unchanged, and nothing else shows this waybill.
    expect(screen.getAllByText(/2472106867/)).toHaveLength(1)
    await user.click(within(row).getByRole('button', { name: 'Map items' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/exchanges/ex-1?from=fulfill'))
  })

  test('AR: the section and the badge in Arabic', async () => {
    toMap = TO_MAP
    await i18n.changeLanguage('ar')
    renderAr(<Fulfill />)
    const section = await screen.findByTestId('exchanges-to-map')
    const t = (ar as { fulfill: { toMap: Record<string, string> } }).fulfill.toMap
    expect(section).toHaveTextContent(t.title)
    expect(section).toHaveTextContent(t.badge)
    expect(within(section).getByRole('button', { name: t.mapItems })).toBeInTheDocument()
  })

  test('a worker sees the row but no Map items (mapping is owner / manager only)', async () => {
    toMap = TO_MAP
    setAccessToken(tokenFor('worker'))
    renderWithProviders(<Fulfill />)
    const section = await screen.findByTestId('exchanges-to-map')
    expect(within(section).queryByRole('button', { name: 'Map items' })).toBeNull()
    expect(section).toHaveTextContent('A manager maps the items first')
  })

  test('hidden when there is nothing to map', async () => {
    renderWithProviders(<Fulfill />)
    await screen.findByText('EXC-5028197718')
    expect(screen.queryByTestId('exchanges-to-map')).toBeNull()
  })
})

describe('the mapping screen hands back to Pick & Pack', () => {
  test('from=fulfill: success offers Start picking → /fulfill?pick=<the new order>', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/exchanges/:id" element={<ExchangeMapping />} />
        <Route path="/fulfill" element={<LocationProbe />} />
      </Routes>,
      { initialEntries: ['/exchanges/ex-1?from=fulfill'] },
    )
    await screen.findByTestId('exchange-leg-outbound')
    for (const [leg, variant] of [['exchange-leg-outbound', 'v-32'], ['exchange-leg-inbound', 'v-30']] as const) {
      await user.click(within(screen.getByTestId(leg)).getByRole('button', { name: /Select variant|Change/i }))
      await screen.findByTestId('exchange-variant-picker')
      await user.click(screen.getByTestId('exchange-picker-product-p-j'))
      await screen.findByTestId('exchange-picker-variant-list')
      await user.click(screen.getByTestId(`exchange-picker-variant-${variant}`))
    }
    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))
    await screen.findByTestId('exchange-mapped-success')
    await user.click(screen.getByRole('button', { name: 'Start picking' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/fulfill?pick=exc-order-9'))
  })
})
