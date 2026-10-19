import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { Route, Routes, MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import SettingsPage from '../pages/settings/SettingsPage'
import CouriersTab from '../pages/settings/CouriersTab'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import type { PackSessionView } from '../api'

// Non-Bosta couriers G2 — Settings › Couriers ("I ship with other couriers", default carrier, catch-up note,
// Test a waybill) in EN and AR/RTL, and the courier-neutral pack-session texts.

let role: 'owner' | 'manager' | 'worker' = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data),
})

let settings: { enabled: boolean; defaultCarrier: string | null; suggestions: string[]; bostaConnected: boolean }
let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>

const OPENS = {
  raw: 'wj-12345', bostaKey: '12345', genericKey: 'WJ12345', format: 'unknown',
  fulfillments: [{ orderNumber: '#1234', carrier: 'Wijha', trackingNumber: 'WJ-12345', status: 'success' }],
  shipments: [{ orderNumber: '#1234', provider: 'generic', carrier: 'Wijha', trackingNumber: 'WJ-12345', state: 'created' }],
  verdict: 'OPENS_ORDER', verdictEn: 'This waybill will open order #1234', verdictAr: 'هذه البوليصة ستفتح الطلب #1234',
  waybillModeCode: 'OPEN', waybillModeEn: null, waybillModeAr: null, cameraHintEn: 'x', cameraHintAr: 'x',
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/settings/couriers') && method === 'PUT') {
    settings = { ...settings, enabled: body.enabled, defaultCarrier: body.defaultCarrier }
    return json(settings)
  }
  if (url.endsWith('/settings/couriers')) return json(settings)
  if (url.endsWith('/settings/couriers/catch-up/preview')) return json({ wouldCreate: 4, keyInUse: 0, byCarrier: { Wijha: 4 } })
  if (url.endsWith('/settings/couriers/test-waybill')) return json(OPENS)
  return json({})
}

beforeEach(() => {
  role = 'owner'
  settings = { enabled: false, defaultCarrier: null, suggestions: ['Wijha', 'Mylerz', 'QP Express'], bostaConnected: true }
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

function renderSettings() {
  return renderWithProviders(
    <Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
    { initialEntries: ['/settings?tab=couriers'] },
  )
}

describe('Settings › Couriers', () => {
  test('owner switches on with a default carrier: catch-up count shown, PUT sends both', async () => {
    const user = userEvent.setup()
    renderSettings()
    await screen.findByTestId('couriers-settings')
    expect(screen.getByText('Couriers')).toBeInTheDocument()
    const save = screen.getByTestId('couriers-save')
    expect(save).toBeDisabled()

    await user.click(screen.getByRole('switch', { name: 'I ship with other couriers' }))
    expect(screen.getByTestId('couriers-catchup')).toHaveTextContent('Traced adds 4 open orders')
    await user.type(screen.getByLabelText('Default courier name'), 'Wijha')
    await user.click(save)

    await waitFor(() => {
      const put = calls.find(c => c.method === 'PUT' && c.url.endsWith('/settings/couriers'))
      expect(put?.body).toEqual({ enabled: true, defaultCarrier: 'Wijha' })
    })
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument()
    expect(screen.getByText(/Bosta stays your main courier/)).toBeInTheDocument()
  })

  test('switching off explains that existing orders stay', async () => {
    settings.enabled = true
    const user = userEvent.setup()
    renderSettings()
    await user.click(await screen.findByRole('switch', { name: 'I ship with other couriers' }))
    expect(screen.getByTestId('couriers-off-note')).toHaveTextContent('Orders already added stay as they are')
  })

  test('manager: read-only switch and carrier, no Save — but can test a waybill', async () => {
    role = 'manager'
    const user = userEvent.setup()
    renderSettings()
    await screen.findByTestId('couriers-settings')
    expect(screen.getByText('View only — ask an owner to make changes here.')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'I ship with other couriers' })).toBeDisabled()
    expect(screen.getByLabelText('Default courier name')).toBeDisabled()
    expect(screen.queryByTestId('couriers-save')).toBeNull()

    await user.type(screen.getByTestId('test-waybill-input'), 'wj-12345{Enter}')
    const verdict = await screen.findByTestId('test-waybill-verdict')
    expect(verdict).toHaveTextContent('This waybill will open order #1234')
    expect(verdict).toHaveAttribute('data-verdict', 'OPENS_ORDER')
    expect(screen.getByText('WJ12345')).toBeInTheDocument()
    expect(screen.getByText('Unknown (scanners don\'t report it)')).toBeInTheDocument()
    expect(screen.getByText('Shipment: order #1234 · Wijha · created')).toBeInTheDocument()
    expect(calls.filter(c => c.url.endsWith('/test-waybill'))[0].body).toEqual({ code: 'wj-12345' })
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
    expect(screen.getByTestId('test-waybill-camera-hint')).toHaveTextContent('use a hardware scanner')
  })

  test('AR / RTL: Arabic labels and the Arabic verdict', async () => {
    const i18n = i18next.createInstance()
    await i18n.use(initReactI18next).init({ lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <I18nextProvider i18n={i18n}>
          <div dir="rtl"><CouriersTab isOwner /></div>
        </I18nextProvider>
      </MemoryRouter>,
    )
    expect(await screen.findByText('شركات شحن أخرى')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'أشحن مع شركات أخرى' })).toBeInTheDocument()
    await user.type(screen.getByTestId('test-waybill-input'), 'wj-12345{Enter}')
    expect(await screen.findByTestId('test-waybill-verdict')).toHaveTextContent('هذه البوليصة ستفتح الطلب #1234')
    expect(screen.getByTestId('test-waybill-input')).toHaveAttribute('dir', 'ltr')
  })
})

// ── courier-neutral pack-session texts ────────────────────────────────────────

const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-05T07:00:00Z', workerName: 'Ahmed',
  counters: { packed: 0, setAside: 0, rejected: 0, left: 3 }, recent: [], openOrder: null } as unknown as PackSessionView

function token() {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', tenant: 't1', role: 'owner' })}.sig`
}

describe('Pack session texts (G2)', () => {
  beforeEach(() => {
    setAccessToken(token())
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

  test('Bosta-only store: today\'s Bosta hint', async () => {
    stubFetchWithShellDefaults(vi.fn(backend), { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false } })
    renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={vi.fn()} />)
    expect(await screen.findByText('Scan the barcode on the Bosta waybill to open its order.')).toBeInTheDocument()
  })

  test('store that ships with other couriers: neutral hint; a generic order card names its courier', async () => {
    stubFetchWithShellDefaults(vi.fn(backend),
      { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: true } })
    const { unmount } = renderWithProviders(<PackSessionScreen initial={VIEW} onEnded={vi.fn()} />)
    expect(await screen.findByText('Scan the barcode on the waybill to open its order.')).toBeInTheDocument()
    unmount()

    const card = { id: 'o1', number: '#1234', customer_name: 'Alice', payment_method: null, cod_amount: null,
      tracking_number: 'WJ-12345', area: null, courierType: 'delivery', shipment_provider: 'generic',
      shipment_carrier: 'Wijha', batchNo: null, batchPrintedAt: null,
      items: [{ id: 'i1', variant_id: 'v1', sku: 'S', variant_title: 'M', product_title: 'Dress', quantity: 1,
        allocated: 0, allocatedPieces: [] }] }
    renderWithProviders(<PackSessionScreen initial={{ ...VIEW, openOrder: card } as unknown as PackSessionView}
                                           onEnded={vi.fn()} />)
    expect(await screen.findByText('Courier Wijha')).toBeInTheDocument()
    expect(screen.queryByText('Courier Bosta · Delivery')).toBeNull()
  })
})
