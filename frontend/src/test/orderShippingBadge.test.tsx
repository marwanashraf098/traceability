import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import * as api from '../api'
import { ShippingBadge } from '../components/ui'
import OrderDetail from '../pages/OrderDetail'
import Overview from '../pages/Overview'
import Layout from '../components/Layout'

// V139 — the order's shipping badge is server-derived (OrderShippingBadge); the UI only labels and
// tones it. Replaces the red "Shipment not created" badge driven by bosta_link_status = 'not_created'.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getOrder: vi.fn() }
})

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  return inst
}

function renderBadge(badge: api.OrderShippingBadge | null, lng: 'en' | 'ar') {
  return render(<I18nextProvider i18n={i18nFor(lng)}><ShippingBadge badge={badge} /></I18nextProvider>)
}

const CASES: { badge: api.OrderShippingBadge; en: string; ar: string; tone: string }[] = [
  { badge: { state: 'shipped_elsewhere', carrier: 'Wijha', days: null },
    en: 'Shipped with Wijha', ar: 'شُحن مع Wijha', tone: 'bg-muted' },
  { badge: { state: 'awaiting_booking', carrier: null, days: null },
    en: 'Awaiting Bosta booking', ar: 'في انتظار الحجز في بوسطة', tone: 'bg-muted' },
  { badge: { state: 'not_booked_overdue', carrier: null, days: 4 },
    en: 'Not booked in Bosta for 4 days', ar: 'لم يُحجز في بوسطة منذ 4 أيام', tone: 'bg-warning' },
  { badge: { state: 'bosta_tracking_not_linked', carrier: 'Bosta', days: null },
    en: 'Bosta tracking not linked', ar: 'رقم تتبع بوسطة غير مرتبط', tone: 'bg-critical' },
  { badge: { state: 'cancelled', carrier: null, days: null },
    en: 'Cancelled', ar: 'ملغى', tone: 'bg-muted' },
]

describe('ShippingBadge — labels in English and Arabic', () => {
  for (const c of CASES) {
    test(`sb ${c.badge.state} — EN`, () => {
      renderBadge(c.badge, 'en')
      const el = screen.getByTestId('shipping-badge')
      expect(el).toHaveTextContent(c.en)
      expect(el.className).toContain(c.tone)
    })
    test(`sb ${c.badge.state} — AR`, () => {
      renderBadge(c.badge, 'ar')
      expect(screen.getByTestId('shipping-badge')).toHaveTextContent(c.ar)
    })
  }

  test('sb shipped_elsewhere without a carrier name — generic label, EN + AR', () => {
    const b: api.OrderShippingBadge = { state: 'shipped_elsewhere', carrier: null, days: null }
    renderBadge(b, 'en')
    expect(screen.getByTestId('shipping-badge')).toHaveTextContent('Shipped with another carrier')
    document.body.innerHTML = ''
    renderBadge(b, 'ar')
    expect(screen.getByTestId('shipping-badge')).toHaveTextContent('شُحن مع شركة شحن أخرى')
  })

  test('sb linked and null render nothing (the delivery badge / shipment card says it)', () => {
    const { container } = renderBadge({ state: 'linked', carrier: 'Bosta', days: null }, 'en')
    expect(container).toBeEmptyDOMElement()
    const r2 = renderBadge(null, 'en')
    expect(r2.container).toBeEmptyDOMElement()
  })
})

// ── OrderDetail: the badge replaces the old not_created red badge ─────────────

function makeOrderDetail(overrides: Partial<api.OrderDetail> = {}): api.OrderDetail {
  return {
    id: 'order-sb', number: '#SB1', customerName: 'Sara', customerPhone: null, address: null,
    paymentMethod: 'cod', codAmount: null, status: 'new', onHold: false, holdReason: null,
    placedAt: new Date().toISOString(), createdAt: new Date().toISOString(),
    items: [], shipments: [], bostaLinkStatus: null, notTracedAt: null, isExchange: false,
    derivedStatus: {
      primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false,
    } as unknown as api.DerivedOrderStatus,
    shopifyOrderUrl: null, physicallyWithCourier: false, shippingBadge: null,
    ...overrides,
  }
}

function renderDetail() {
  return renderWithProviders(
    <Routes><Route path="/orders/:id" element={<OrderDetail />} /></Routes>,
    { initialEntries: ['/orders/order-sb'] },
  )
}

describe('OrderDetail — shipping badge', () => {
  test('od1 a legacy not_created flag no longer shows the red badge; the derived badge does', async () => {
    vi.mocked(api.getOrder).mockResolvedValue(makeOrderDetail({
      bostaLinkStatus: 'not_created',
      shippingBadge: { state: 'shipped_elsewhere', carrier: 'Wijha', days: null },
    }))
    renderDetail()
    expect(await screen.findByTestId('shipping-badge')).toHaveTextContent('Shipped with Wijha')
    expect(screen.queryByText('Shipment not created')).not.toBeInTheDocument()
  })

  test('od2 overdue — warning badge with the day count', async () => {
    vi.mocked(api.getOrder).mockResolvedValue(makeOrderDetail({
      shippingBadge: { state: 'not_booked_overdue', carrier: null, days: 5 },
    }))
    renderDetail()
    const el = await screen.findByTestId('shipping-badge')
    expect(el).toHaveTextContent('Not booked in Bosta for 5 days')
    expect(el.className).toContain('bg-warning')
  })
})

// ── Overview flow strip: "Shipped elsewhere" count ────────────────────────────

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

describe('Overview — shipped elsewhere count', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => { vi.unstubAllGlobals() })

  function stub(funnel: unknown) {
    stubFetchWithShellDefaults(vi.fn((url: string) => {
      if (url.includes('/orders/funnel')) return jsonOk(funnel)
      return Promise.resolve({ ok: false, status: 500, statusText: 'x', headers: { get: () => null }, json: async () => ({}) })
    }), {
      me: { name: 'M', email: 'm@test.com', role: 'owner' },
      exceptionsCount: { count: 0, critical: 0, warning: 0 },
      onboardingStatus: { steps: [], allDone: true, dismissed: true },
    })
  }

  test('ov-se1 a non-zero count is shown under the flow strip, not in New', async () => {
    stub({ newCount: 2, picking: 0, packed: 0, courier: 1, delivered: 0, shippedElsewhere: 7 })
    renderWithProviders(<Layout><Overview /></Layout>)
    const row = await screen.findByTestId('flow-shipped-elsewhere')
    expect(row).toHaveTextContent('Shipped elsewhere')
    expect(within(row).getByText('7')).toBeInTheDocument()
  })

  test('ov-se2 zero — nothing shown', async () => {
    stub({ newCount: 2, picking: 0, packed: 0, courier: 1, delivered: 0, shippedElsewhere: 0 })
    renderWithProviders(<Layout><Overview /></Layout>)
    await screen.findByTestId('flow-strip')
    expect(screen.queryByTestId('flow-shipped-elsewhere')).not.toBeInTheDocument()
  })
})
