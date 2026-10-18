import { test, expect, describe } from 'vitest'
import { render, screen } from '@testing-library/react'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { LegStatusBadge, ShippingBadge } from '../components/ui'

// V169 (non-Bosta couriers, G1): a generic forward leg's badge names its carrier — never "Bosta",
// never "Awaiting Bosta booking" — and its with_courier status reads "With {carrier}", never "In transit".

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  return inst
}

describe('generic carrier badge', () => {
  test.each([
    ['en', 'Mylerz', 'Mylerz'],
    ['ar', 'Mylerz', 'Mylerz'],
    ['en', null, 'Another carrier'],
    ['ar', null, 'شركة شحن أخرى'],
  ] as const)('%s, carrier %s → %s', (lng, carrier, label) => {
    render(<I18nextProvider i18n={i18nFor(lng)}>
      <ShippingBadge badge={{ state: 'generic_carrier', carrier, days: null }} />
    </I18nextProvider>)
    const el = screen.getByTestId('shipping-badge')
    expect(el.textContent).toBe(label)
    expect(el.textContent).not.toMatch(/Bosta|بوسطة/)
  })

  test.each([
    ['en', 'Mylerz', 'With Mylerz'],
    ['ar', 'Mylerz', 'مع Mylerz'],
    ['en', null, 'With another carrier'],
  ] as const)('status.with_carrier %s, %s → %s', (lng, carrier, label) => {
    render(<I18nextProvider i18n={i18nFor(lng)}>
      <LegStatusBadge legStatus={{ primaryKey: 'status.with_carrier', tone: 'INFO' }} carrier={carrier} />
    </I18nextProvider>)
    expect(screen.getByText(label)).toBeTruthy()
    expect(screen.queryByText(/In transit/)).toBeNull()
  })
})
