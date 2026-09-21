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
import { ToastProvider } from '../components/ui'
import Layout from '../components/Layout'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import { StationProvider } from '../components/StationProvider'
import type { ExchangeSummary, RefundLeg } from '../api'

// ── AR/RTL headless render check — same scoped-instance pattern as ordersRtl.test.tsx ──

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
      <StationProvider>
        <MemoryRouter initialEntries={['/']}>
          <I18nextProvider i18n={arI18n}>
            <ToastProvider>{children}</ToastProvider>
          </I18nextProvider>
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

const EXCHANGE: ExchangeSummary = {
  id: 'exc-1', tracking_number: '910000001', status: 'needs_confirmation',
  matched_order_id: null, match_method: null, matched_at: null,
  outbound_description: 'قبعة صفراء', inbound_description: 'قبعة حمراء', inbound_description_ar: 'قبعة حمراء',
  cod: 0, goods_value: 600, outbound_items_count: 1, inbound_items_count: 1,
  customer_name: 'مايا مصطفى', customer_phone: '01001234567',
}

// internal_state='returned' — the real CRP-arrival value (Step 4-close diagnosis: a
// return leg never reaches 'delivered', that's a forward-leg-only concept).
const REFUND: RefundLeg = {
  id: 'ship-1', tracking_number: 'RFD-001', internal_state: 'returned',
  order_id: 'order-1', order_number: '#1001',
  customer_name: 'نور عادل', customer_phone: '01098765432',
  leg_status: { primaryKey: 'status.returned', tone: 'WARN' },
  inspection_state: 'needs_inspection',
}

function appFetch(url: string) {
  if (url.includes('/api/v1/exchanges/') && url.includes('/candidates')) return jsonOk([])
  if (url.includes('/api/v1/exchanges/') && !url.includes('?')) return jsonOk(EXCHANGE)
  if (url.includes('/api/v1/exchanges')) return jsonOk([EXCHANGE])
  if (url.includes('/api/v1/refunds')) return jsonOk([REFUND])
  return jsonOk({})
}

test('Exchanges & Refunds list renders Arabic labels and RTL dir without crashing', async () => {
  stubFetchWithShellDefaults(vi.fn(appFetch))
  renderRtl(<Layout><ExchangesRefunds /></Layout>)

  expect(document.documentElement.getAttribute('dir')).toBe('rtl')
  await screen.findByText('مايا مصطفى')
  expect(screen.getByText('نور عادل')).toBeInTheDocument()
  // Tabs — Arabic labels from ar.json's exchangesRefunds.tabs.*
  expect(screen.getByRole('button', { name: /الكل/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /^استرجاع/ })).toBeInTheDocument()
  // Step 4-close Part 2: the STATUS cell renders the Arabic inspectionState vocabulary
  // (exchangesRefunds.inspectionState.*), not the raw courier leg_status.
  const refundRow = screen.getByText('نور عادل').closest('tr')!
  expect(within(refundRow).getByText(ar.exchangesRefunds.inspectionState.needs_inspection)).toBeInTheDocument()
  // Exchange status pill renders the Arabic exchangesRefunds.status.* vocabulary.
  const exchangeRow = screen.getByText('مايا مصطفى').closest('tr')!
  expect(within(exchangeRow).getByText(ar.exchangesRefunds.status.needs_confirmation)).toBeInTheDocument()
})

test('Exchange drawer opens with Arabic labels and does not crash in RTL', async () => {
  stubFetchWithShellDefaults(vi.fn(appFetch))
  renderRtl(<Layout><ExchangesRefunds /></Layout>)

  await screen.findByText('مايا مصطفى')
  const user = userEvent.setup()
  await user.click(screen.getByText('مايا مصطفى'))

  await screen.findByTestId('candidate-picker')
  expect(screen.getByText(ar.exchangesRefunds.drawer.exchange.candidatesTitle)).toBeInTheDocument()
})
