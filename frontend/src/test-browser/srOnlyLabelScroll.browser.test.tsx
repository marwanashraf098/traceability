import { afterEach, describe, expect, test, vi } from 'vitest'
import { page, userEvent } from 'vitest/browser'
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import { useState } from 'react'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { Checkbox, Radio, ToastProvider } from '../components/ui'
import CustomerFeesSection from '../pages/settings/CustomerFeesSection'

// Prod 2026-11-06 (The Snouts) — Settings › Returns portal › Customer fees: clicking "Percentage" jumped the page
// ~3,500 px down to an empty area ("blank page"). The segmented control's radio is sr-only (position:absolute) inside
// a label that wasn't positioned, so it was laid out against a far ancestor and the browser scrolled to it on focus.
// In a real browser (Chromium + WebKit, EN/LTR + AR/RTL), inside the app shell's frame (a scrolled <main>): a real
// click on every sr-only-input label — fee mode (exchange, refund), the area-override mode picker, the shared Checkbox
// and Radio — leaves window.scrollY where it was, and the hidden input sits inside its label.

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const rule = (kind: string) => ({ kind, enabled: true, mode: 'fixed', amount: 100, percent: null, minAmount: null,
  maxAmount: null, waiveMerchantFault: true })
const SETTINGS = { visible: true, rules: { exchange: rule('exchange'), refund: rule('refund') }, merchantFaultReasons: ['damaged'],
  overrides: [], exchangePricing: { anyProduct: true, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true } }

function i18n(lang: 'en' | 'ar') {
  const i = i18next.createInstance()
  i.use(initReactI18next).init({ lng: lang, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return i
}

function SharedControls() {
  const [checked, setChecked] = useState(false)
  const [radio, setRadio] = useState('a')
  return (
    <div className="p-6 space-y-3" data-testid="shared-controls">
      <Checkbox checked={checked} onChange={setChecked} label="Shared checkbox" />
      <div className="flex gap-4">
        <Radio value="a" checked={radio === 'a'} onChange={setRadio} label="Option A" />
        <Radio value="b" checked={radio === 'b'} onChange={setRadio} label="Option B" />
      </div>
    </div>
  )
}

/**
 * The app shell's shape (components/Layout.tsx): an h-screen overflow-hidden frame whose <main> is the scroller, with
 * no positioned ancestor in between — so an unanchored absolute input is laid out against the page, at the label's
 * UNSCROLLED offset inside <main>, far below the viewport once <main> is scrolled.
 */
function mount(lang: 'en' | 'ar') {
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
  return render(
    <I18nextProvider i18n={i18n(lang)}>
      <MemoryRouter>
        <ToastProvider>
          <div className="flex h-screen overflow-hidden" dir={lang === 'ar' ? 'rtl' : 'ltr'}>
            <main className="flex-1 overflow-y-auto p-6" data-testid="scroller">
              <div style={{ height: 2500 }} />
              <CustomerFeesSection />
              <SharedControls />
              <div style={{ height: 2500 }} />
            </main>
          </div>
        </ToastProvider>
      </MemoryRouter>
    </I18nextProvider>)
}

/**
 * Scrolls <main> so the label is in the middle of the viewport, clicks it for real, and returns how far anything
 * moved — the window (the prod symptom: window.scrollY ~3,519) or <main>.
 */
async function clickAndMeasure(label: HTMLElement): Promise<number> {
  label.scrollIntoView({ block: 'center' })
  await new Promise(r => requestAnimationFrame(() => r(null)))
  const scroller = document.querySelector<HTMLElement>('[data-testid=scroller]')!
  const before = [window.scrollY, scroller.scrollTop]
  await userEvent.click(label)
  await new Promise(r => setTimeout(r, 50))
  return Math.max(Math.abs(window.scrollY - before[0]), Math.abs(scroller.scrollTop - before[1]))
}

function inputInsideLabel(label: HTMLElement) {
  const input = label.querySelector('input')!
  const l = label.getBoundingClientRect(), i = input.getBoundingClientRect()
  return i.top >= l.top - 2 && i.bottom <= l.bottom + 2 && i.left >= l.left - 2 && i.right <= l.right + 2
}

describe('sr-only inputs inside labels never scroll the page', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearAccessToken()
    document.documentElement.dir = 'ltr'
    window.scrollTo(0, 0)
    document.documentElement.scrollTop = 0
  })

  for (const lang of ['en', 'ar'] as const) {
    test(`fee mode, area-override mode, shared Checkbox / Radio (${lang})`, async () => {
      await page.viewport(1280, 800)
      setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', exp: 9999999999 })) + '.s')
      vi.stubGlobal('fetch', vi.fn((url: string) => json(url.includes('/customer-fees/areas') ? [{ id: 'C1', name: 'Cairo', nameAr: 'القاهرة' }] : SETTINGS)))
      const { findByTestId, getByTestId } = mount(lang)
      const t = i18n(lang).t

      const labels: Array<[string, HTMLElement]> = []
      for (const kind of ['exchange', 'refund']) {
        const card = await findByTestId(`fee-rule-${kind}`)
        for (const m of ['percent', 'fixed', 'percent']) {
          const label = [...card.querySelectorAll<HTMLElement>('[role=radiogroup] label')]
            .find(l => l.textContent === t(`settings.portal.fees.mode.${m}`))!
          labels.push([`${kind} → ${m}`, label])
        }
      }
      for (const [name, label] of labels) {
        expect(await clickAndMeasure(label), `${name}: page scrolled`).toBeLessThan(2)
        expect(inputInsideLabel(label), `${name}: radio outside its label`).toBe(true)
      }

      const add = [...getByTestId('fee-area-overrides').querySelectorAll('button')]
        .find(b => b.textContent === t('settings.portal.fees.areas.add'))!
      await userEvent.click(add)
      const form = await findByTestId('add-area-form')
      for (const m of ['percent', 'fixed']) {
        const label = [...form.querySelectorAll<HTMLElement>('[role=radiogroup] label')]
          .find(l => l.textContent === t(`settings.portal.fees.mode.${m}`))!
        expect(await clickAndMeasure(label), `area → ${m}: page scrolled`).toBeLessThan(2)
        expect(inputInsideLabel(label), `area → ${m}: radio outside its label`).toBe(true)
      }

      const shared = getByTestId('shared-controls')
      for (const label of shared.querySelectorAll<HTMLElement>('label')) {
        expect(await clickAndMeasure(label), `${label.textContent}: page scrolled`).toBeLessThan(2)
        expect(inputInsideLabel(label), `${label.textContent}: input outside its label`).toBe(true)
      }
      expect(shared.querySelector<HTMLInputElement>('input[type=checkbox]')!.checked).toBe(true)
    })
  }
})
