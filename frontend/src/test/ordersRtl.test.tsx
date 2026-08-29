import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import type { ReactElement } from 'react'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'
import { StationProvider } from '../components/StationProvider'
import type { OrderPage, OrderSummary, OrderSummaryCounts, DerivedOrderStatus, OrderDetail } from '../api'

// ── AR/RTL headless render check (verify bar item) ──────────────────────────────
// No shared test harness renders Arabic today — renderWithProviders (renderWithProviders.tsx)
// only registers the `en` bundle. Rather than touch that shared helper (used by every other
// test file), this file builds its own AR-capable i18n instance, scoped to Orders + the new
// two-facet columns + OrderDrawer — the surface this pass actually touched.

const arI18n = i18next.createInstance()
arI18n.use(initReactI18next).init({
  lng: 'ar',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } },
  interpolation: { escapeValue: false },
})

function renderRtl(ui: ReactElement) {
  document.documentElement.setAttribute('dir', 'rtl')
  document.documentElement.setAttribute('lang', 'ar')
  return render(ui, {
    wrapper: ({ children }) => (
      // Layout now reads useStation() (worker "Switch / Lock" control) — needs a
      // StationProvider ancestor, same as App.tsx's real nesting.
      <StationProvider>
        <MemoryRouter initialEntries={['/']}>
          <I18nextProvider i18n={arI18n}>{children}</I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    ),
  })
}

afterEach(() => {
  document.documentElement.removeAttribute('dir')
  document.documentElement.removeAttribute('lang')
})

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function makeDerivedStatus(overrides: Partial<DerivedOrderStatus> = {}): DerivedOrderStatus {
  return {
    primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
    conflictKey: null, notTraced: false, packedConfirmed: true,
    fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
    ...overrides,
  }
}

function makeOrderSummary(overrides: Partial<OrderSummary> = {}): OrderSummary {
  return {
    id: 'order-1', number: '#1001', customerName: 'منى سعيد', customerPhone: '010 2233 4455',
    status: 'with_courier', onHold: false, codAmount: 540, placedAt: '2026-07-20T09:12:00Z',
    trackingNumber: '889213', deliveryState: 'with_courier', exceptionReason: null,
    bostaLinkStatus: 'linked', failedDeliveryAttempts: 0, isDelayed: null, slaBreached: null,
    notTracedAt: null, isExchange: false, derivedStatus: makeDerivedStatus(),
    ...overrides,
  }
}

function makeOrderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#1001', customerName: 'منى سعيد', customerPhone: '010 2233 4455',
    address: null, paymentMethod: 'cod', codAmount: 540, status: 'with_courier', onHold: false,
    holdReason: null, placedAt: '2026-07-20T09:12:00Z', createdAt: '2026-07-20T09:12:00Z',
    items: [], shipments: [], bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false,
    derivedStatus: makeDerivedStatus(), shopifyOrderUrl: null,
    ...overrides,
  }
}

function makeOrderSummaryCounts(overrides: Partial<OrderSummaryCounts> = {}): OrderSummaryCounts {
  return { total: 3, processing: 1, withCourier: 1, delivered: 1, returned: 0, ...overrides }
}

test('Orders list renders Arabic labels and RTL dir without crashing', async () => {
  const appFetch = vi.fn((url: string) => {
    if (url.includes('/orders?')) return jsonOk({ items: [makeOrderSummary()], page: 0, size: 20, total: 1 } satisfies OrderPage)
    if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
    return jsonOk({})
  })
  stubFetchWithShellDefaults(appFetch)
  renderRtl(<Layout><Orders /></Layout>)

  expect(document.documentElement.getAttribute('dir')).toBe('rtl')
  await screen.findByText('#1001')
  // Tabs — Arabic labels from ar.json's orders.tabs.*
  expect(screen.getByRole('button', { name: /الكل/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /قيد الشحن/ })).toBeInTheDocument()
  // Fulfillment facet (status.fulfilled) and Delivery facet (status.in_transit) render
  // through the Arabic catalog, not English fallback text.
  expect(screen.getByText('تم التجهيز')).toBeInTheDocument()
  expect(screen.getByText('في الطريق')).toBeInTheDocument()
})

test('OrderDrawer opens with Arabic labels and does not crash in RTL', async () => {
  const appFetch = vi.fn((url: string) => {
    // /timeline first — it's a more specific path than the bare order-detail route below
    // and would otherwise match that branch too (both contain '/orders/order-1').
    if (url.includes('/orders/order-1/timeline')) return jsonOk([])
    if (url.includes('/orders/order-1')) return jsonOk(makeOrderDetail())
    if (url.includes('/orders?')) return jsonOk({ items: [makeOrderSummary()], page: 0, size: 20, total: 1 } satisfies OrderPage)
    if (url.includes('/orders/summary')) return jsonOk(makeOrderSummaryCounts())
    return jsonOk({})
  })
  stubFetchWithShellDefaults(appFetch)
  renderRtl(<Layout><Orders /></Layout>)

  await screen.findByText('#1001')
  const user = userEvent.setup()
  await user.click(screen.getByText('منى سعيد'))

  // Drawer header renders the Arabic "View in Shopify" chrome key set and current-state
  // section label — confirms the overlay mounts and translates cleanly under dir=rtl.
  const currentState = await screen.findByText('الحالة الحالية')
  expect(currentState).toBeInTheDocument()
  expect(within(document.body).getByText('سجل التتبع')).toBeInTheDocument()
})
