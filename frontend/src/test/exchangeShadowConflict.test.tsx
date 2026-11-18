import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangeMapping from '../pages/exchanges/ExchangeMapping'
import i18n from '../i18n'

/**
 * B0 (2026-11-18) — mapping an exchange whose Shopify exchange already reserves a different item: the server answers
 * 409 EXCHANGE_SHADOW_CONFLICT and the screen shows ITS words (EN / AR), never a bare "409".
 */

const EN = "This exchange was made in Shopify, and Shopify's exchange already reserves a different item for it. "
  + 'Release the Shopify exchange reservation (Pick & Pack → Waiting for release) or fix the exchange in Shopify first, then map it again.'
const AR = 'تم إنشاء هذا الاستبدال في Shopify، واستبدال Shopify يحجز له منتجًا مختلفًا بالفعل. '
  + 'ألغِ حجز استبدال Shopify (التجهيز والتغليف ← بانتظار الإتاحة) أو صحّح الاستبدال في Shopify أولًا، ثم اربطه مرة أخرى.'

const CATALOG = { products: [{ id: 'p-top', title: 'ROUND LONG SLEEVE COTTON TOP', status: 'active', imageUrl: null, variants: [
  { id: 'v-xl', title: 'Beige / XL', sku: 'RT-B-XL', price: 650 }, { id: 'v-l', title: 'Beige / L', sku: 'RT-B-L', price: 650 }] }] }

const EXCHANGE = {
  id: 'exc-1', tracking_number: '638012455', status: 'needs_mapping', matched_order_id: null, match_method: null,
  matched_at: null, outbound_description: 'ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / XL',
  inbound_description: 'ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / L', inbound_description_ar: null,
  cod: 0, goods_value: 650, auto_matched: false, outbound_items_count: 1, inbound_items_count: 1,
  customer_name: 'Sara Ali', customer_phone: '+201012345678',
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/api/v1/catalog')) return fakeResponse(CATALOG)
  if (url.endsWith('/outbound-candidates')) return fakeResponse({ classification: 'NONE', committedVariantId: null, candidates: [] })
  if (url.endsWith('/exchanges/exc-1/map') && method === 'POST') {
    return fakeResponse({ code: 'EXCHANGE_SHADOW_CONFLICT', message_en: EN, message_ar: AR }, 409)
  }
  if (url.endsWith('/exchanges/exc-1')) return fakeResponse(EXCHANGE)
  return fakeResponse({})
}

async function mapBoth(user: ReturnType<typeof userEvent.setup>) {
  for (const [leg, variant] of [['outbound', 'v-l'], ['inbound', 'v-xl']] as const) {
    const row = await screen.findByTestId(`exchange-row-${leg}-0`)
    await user.click(within(row).getAllByRole('button').find(b => /Select variant|اختيار المتغير/.test(b.textContent ?? ''))!)
    await screen.findByTestId('exchange-variant-picker')
    await user.click(screen.getByTestId('exchange-picker-product-p-top'))
    await screen.findByTestId('exchange-picker-variant-list')
    await user.click(screen.getByTestId(`exchange-picker-variant-${variant}`))
  }
}

function renderScreen() {
  return renderWithProviders(
    <I18nextProvider i18n={i18n}><Routes><Route path="/exchanges/:id" element={<ExchangeMapping />} /></Routes></I18nextProvider>,
    { initialEntries: ['/exchanges/exc-1'] })
}

beforeEach(() => { stubFetchWithShellDefaults(vi.fn(backendFetch)) })
afterEach(async () => { await i18n.changeLanguage('en') })

describe('Exchange mapping — Shopify exchange conflict (B0)', () => {
  test('x1 409 EXCHANGE_SHADOW_CONFLICT → the server\'s English words', async () => {
    await i18n.changeLanguage('en')
    const user = userEvent.setup()
    renderScreen()
    await mapBoth(user)
    await user.click(screen.getByRole('button', { name: /Confirm mapping/i }))
    expect(await screen.findByText(EN)).toBeInTheDocument()
    expect(screen.queryByText(/^409/)).toBeNull()
    expect(screen.queryByTestId('exchange-mapped-success')).toBeNull()
  })

  test('x2 Arabic → the server\'s Arabic words', async () => {
    await i18n.changeLanguage('ar')
    const user = userEvent.setup()
    renderScreen()
    await mapBoth(user)
    await user.click(screen.getByRole('button', { name: 'تأكيد الربط' }))
    expect(await screen.findByText(AR)).toBeInTheDocument()
  })
})
