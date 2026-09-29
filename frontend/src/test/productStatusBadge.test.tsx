import { test, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import type { ReactElement } from 'react'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { ProductStatusBadge } from '../components/ui'
import { renderWithProviders } from './renderWithProviders'
import ExchangeVariantPicker from '../pages/exchanges/ExchangeVariantPicker'

// One shared Shopify product-status badge: active → nothing, draft / archived → a translated
// label, anything else (e.g. unlisted) → the raw value, never a crash.

function renderIn(lang: 'en' | 'ar', ui: ReactElement) {
  const i18n = i18next.createInstance()
  i18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  return render(
    <I18nextProvider i18n={i18n}>
      <div dir={lang === 'ar' ? 'rtl' : 'ltr'}>{ui}</div>
    </I18nextProvider>,
  )
}

afterEach(() => { vi.unstubAllGlobals() })

test('psb1: active (any case), empty, null and undefined render nothing', () => {
  for (const status of ['active', 'ACTIVE', '', null, undefined]) {
    const { container, unmount } = renderIn('en', <ProductStatusBadge status={status} />)
    expect(container.querySelector('[data-testid="product-status-badge"]')).toBeNull()
    unmount()
  }
})

test('psb2: draft and archived are labelled in English', () => {
  renderIn('en', <><ProductStatusBadge status="draft" /><ProductStatusBadge status="ARCHIVED" /></>)
  const badges = screen.getAllByTestId('product-status-badge')
  expect(badges.map(b => b.textContent)).toEqual(['Draft', 'Archived'])
})

test('psb3: draft and archived are labelled in Arabic (RTL)', () => {
  renderIn('ar', <><ProductStatusBadge status="draft" /><ProductStatusBadge status="archived" /></>)
  const badges = screen.getAllByTestId('product-status-badge')
  expect(badges.map(b => b.textContent)).toEqual(['مسودة', 'مؤرشف'])
  expect(badges[0].closest('[dir]')?.getAttribute('dir')).toBe('rtl')
})

test('psb4: an unknown value (unlisted, dotted, odd) shows the raw status', () => {
  renderIn('en', <><ProductStatusBadge status="unlisted" /><ProductStatusBadge status="some.new_state" /></>)
  expect(screen.getAllByTestId('product-status-badge').map(b => b.textContent)).toEqual(['unlisted', 'some.new_state'])
})

test('psb5: the merchant exchange picker shows the badge on a draft product and still lets it be picked', async () => {
  const catalog = {
    products: [
      { id: 'p1', title: 'Linen Shirt', status: 'active', imageUrl: null,
        variants: [{ id: 'v1', title: 'M', sku: 'L-M', price: 450, pieceCounts: {}, committed: 0, available: 3 }] },
      { id: 'p2', title: 'Wool Coat', status: 'draft', imageUrl: null,
        variants: [{ id: 'v2', title: 'L', sku: 'W-L', price: 900, pieceCounts: {}, committed: 0, available: 2 }] },
    ],
  }
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(catalog), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })))
  renderWithProviders(<ExchangeVariantPicker onSelect={() => {}} onClose={() => {}} />)

  const coat = await screen.findByText('Wool Coat')
  const badges = screen.getAllByTestId('product-status-badge')
  expect(badges).toHaveLength(1)
  expect(badges[0].textContent).toBe('Draft')
  expect(coat.closest('button')).not.toBeNull()
  expect(coat.closest('button')?.hasAttribute('disabled')).toBe(false)
})
