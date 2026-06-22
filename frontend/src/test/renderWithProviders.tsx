import { render, RenderOptions } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import { ReactElement } from 'react'

// Fresh i18next instance for tests — does NOT share state with the singleton in
// src/i18n.ts, avoids the localStorage.getItem('lang') call at import time, and
// keeps tests independent of each other.
const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  initImmediate: false,
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
})

interface Options extends Omit<RenderOptions, 'wrapper'> {
  initialEntries?: string[]
}

export function renderWithProviders(
  ui: ReactElement,
  { initialEntries = ['/'], ...renderOptions }: Options = {},
) {
  function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <MemoryRouter initialEntries={initialEntries}>
        <I18nextProvider i18n={testI18n}>
          {children}
        </I18nextProvider>
      </MemoryRouter>
    )
  }
  return render(ui, { wrapper: Wrapper, ...renderOptions })
}

// Re-export everything from @testing-library/react so callers only need one import.
export * from '@testing-library/react'
