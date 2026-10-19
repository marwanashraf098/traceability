import { afterEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import CouriersTab from '../pages/settings/CouriersTab'

// Non-Bosta couriers G2 — Settings › Couriers in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and
// AR/RTL, at desktop 1280×800, station tablet 768×1024 and phone 390×844: with the switch on (catch-up note) and a
// Test-a-waybill result open, nothing scrolls sideways, every control stays inside the page and no buttons overlap.

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data) })

const RESULT = {
  raw: 'https://www.jtexpress.eg/trajectoryQuery?billcode=JT0001234567890EG', bostaKey: null, genericKey: 'JT0001234567890EG',
  format: 'unknown',
  fulfillments: [{ orderNumber: '#10234', carrier: 'J&T Express', trackingNumber: 'JT0001234567890EG', status: 'success' }],
  shipments: [{ orderNumber: '#10234', provider: 'generic', carrier: 'J&T Express', trackingNumber: 'JT0001234567890EG', state: 'created' }],
  verdict: 'OPENS_ORDER', verdictEn: 'This waybill will open order #10234', verdictAr: 'هذه البوليصة ستفتح الطلب #10234',
  waybillModeCode: 'OPEN', waybillModeEn: null, waybillModeAr: null, cameraHintEn: '', cameraHintAr: '',
}

function backend() {
  stubFetchWithShellDefaults((url: string) => {
    if (url.endsWith('/settings/couriers')) {
      return json({ enabled: false, defaultCarrier: 'J&T Express', suggestions: ['Wijha', 'Mylerz', 'J&T Express'], bostaConnected: true })
    }
    if (url.includes('/catch-up/preview')) return json({ days: 3, wouldCreate: 12, keyInUse: 0, byCarrier: {} })
    if (url.endsWith('/test-waybill')) return json(RESULT)
    return json({})
  })
}

function overlappingButtons(root: HTMLElement): string[] {
  const buttons = [...root.querySelectorAll<HTMLElement>('button')].filter(b => b.offsetParent !== null)
  const out: string[] = []
  for (let i = 0; i < buttons.length; i++) {
    for (let j = i + 1; j < buttons.length; j++) {
      const a = buttons[i].getBoundingClientRect(), b = buttons[j].getBoundingClientRect()
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) {
        out.push(`${buttons[i].textContent} × ${buttons[j].textContent}`)
      }
    }
  }
  return out
}

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['tablet', 768, 1024], ['phone', 390, 844]]

describe('Settings › Couriers layout', () => {
  afterEach(async () => {
    cleanup()
    vi.unstubAllGlobals()
    document.documentElement.dir = 'ltr'
    await page.viewport(1280, 800)
  })

  for (const lng of ['en', 'ar'] as const) {
    for (const [name, w, h] of SIZES) {
      test(`${lng} fits at ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        backend()
        const i18n = i18next.createInstance()
        await i18n.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
          resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
        document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
        const { container } = render(
          <MemoryRouter>
            <I18nextProvider i18n={i18n}>
              <div dir={lng === 'ar' ? 'rtl' : 'ltr'} className="px-4"><CouriersTab isOwner /></div>
            </I18nextProvider>
          </MemoryRouter>,
        )
        await expect.poll(() => container.querySelector('[role="switch"]')).toBeTruthy()
        container.querySelector<HTMLElement>('[role="switch"]')!.click()
        await expect.poll(() => container.querySelector('[data-testid="couriers-catchup"]')).toBeTruthy()
        container.querySelector<HTMLElement>('[data-testid="couriers-include-recent"]')!.click()
        await expect.poll(() => container.querySelector('[data-testid="couriers-catchup-count"]')).toBeTruthy()

        const input = container.querySelector<HTMLInputElement>('[data-testid="test-waybill-input"]')!
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
        setter.call(input, RESULT.raw)
        input.dispatchEvent(new Event('input', { bubbles: true }))
        input.form!.requestSubmit()
        await expect.poll(() => container.querySelector('[data-testid="test-waybill-result"]')).toBeTruthy()
        expect(container.querySelector('[data-testid="test-waybill-verdict"]')!.textContent)
          .toBe(lng === 'ar' ? RESULT.verdictAr : RESULT.verdictEn)

        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
        for (const el of container.querySelectorAll<HTMLElement>('button, input, select, [data-testid="test-waybill-result"], [data-testid="couriers-catchup"]')) {
          const r = el.getBoundingClientRect()
          if (r.width === 0) continue
          expect(r.left, el.outerHTML.slice(0, 60)).toBeGreaterThanOrEqual(0)
          expect(r.right, el.outerHTML.slice(0, 60)).toBeLessThanOrEqual(w + 0.5)
        }
        expect(overlappingButtons(container)).toEqual([])
      })
    }
  }
})
