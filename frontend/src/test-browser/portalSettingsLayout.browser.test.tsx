import { afterEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { ToastProvider } from '../components/ui'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import SettingsPage from '../pages/settings/SettingsPage'

// Returns portal redesign Pass A — Settings (grouped menu) › Returns portal in a real browser with the app's CSS
// (Chromium + WebKit), EN/LTR and AR/RTL, desktop 1280 and phone 390, customer fees hidden and shown: nothing scrolls
// sideways, every button stays inside the page and none overlap; on a phone the menu sits above the page, on desktop
// beside it. Set VITE_PORTAL_SHOTS=<dir> to also save a full-height screenshot of each case.

const SHOTS = (import.meta.env.VITE_PORTAL_SHOTS as string | undefined) || null

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const SETTINGS = {
  slug: 'thesnouts', enabled: true, autoApprove: false, returnWindowDays: 90, logoUrl: null, brandColor: '#FF9D98',
  policyText: 'Return after 14 days', pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc-1',
  returnLocationName: 'مخزن النرجس', bostaConnected: true, exchangesEnabled: true, font: 'almarai', storeName: 'Snug Snout',
  refundMethods: ['bank_transfer', 'instapay', 'wallet'], requirePhotos: true, logo: null,
}
const rule = (kind: string) => ({ kind, enabled: true, mode: 'fixed', amount: 100, percent: null, minAmount: null,
  maxAmount: null, waiveMerchantFault: true })
const FEES = (visible: boolean) => ({
  visible, rules: { exchange: rule('exchange'), refund: { ...rule('refund'), mode: 'percent', amount: null, percent: 10, minAmount: 20, maxAmount: 300 } },
  merchantFaultReasons: ['damaged', 'not_as_pictured', 'wrong_item'],
  overrides: [{ id: 'o1', kind: 'refund', cityId: 'C1', cityName: 'Alexandria', cityNameAr: 'الإسكندرية', districtId: null,
    districtName: null, districtNameAr: null, mode: 'fixed', amount: 0, percent: null, minAmount: null, maxAmount: null }],
  exchangePricing: { anyProduct: true, sameOrLowerOnly: false, maxDifference: null, freeVariantSwaps: true },
})
const VARIANTS = {
  items: [
    { id: 'v1', productTitle: 'The Bag', variantTitle: 'One size / Pink & Red', sku: 'PINKBAG', nonReturnable: false },
    { id: 'v2', productTitle: 'The Bandanas', variantTitle: '2XL/3XL / Pink & White', sku: 'DBPINK-4', nonReturnable: true },
    { id: 'v3', productTitle: 'The Bucket Hat', variantTitle: 'XL/2XL/3XL / Checkered', sku: '1000-RED-2XL', nonReturnable: false },
  ],
  total: 3,
}

function backend(feesVisible: boolean) {
  stubFetchWithShellDefaults((url: string) => {
    if (url.includes('/customer-fees/areas')) return json([])
    if (url.includes('/customer-fees')) return json(FEES(feesVisible))
    if (url.includes('/other-couriers')) return json({ available: true, enabled: false, since: null })
    if (url.includes('/bosta/return-locations')) {
      return json([{ id: 'loc-1', name: 'مخزن النرجس', cityName: 'Cairo', isDefault: true },
        { id: 'loc-2', name: 'Store front', cityName: 'Cairo', isDefault: false }])
    }
    if (url.includes('/tenant/portal-settings')) return json(SETTINGS)
    if (url.includes('/variants?')) return json(VARIANTS)
    return json({})
  }, { me: { genericCourier: true } })
}

function overlappingButtons(root: HTMLElement): string[] {
  const buttons = [...root.querySelectorAll<HTMLElement>('button, a')].filter(b => b.offsetParent !== null)
  const out: string[] = []
  for (let i = 0; i < buttons.length; i++) {
    for (let j = i + 1; j < buttons.length; j++) {
      if (buttons[i].contains(buttons[j]) || buttons[j].contains(buttons[i])) continue
      const a = buttons[i].getBoundingClientRect(), b = buttons[j].getBoundingClientRect()
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) {
        out.push(`${buttons[i].textContent} × ${buttons[j].textContent}`)
      }
    }
  }
  return out
}

const SIZES: [string, number, number][] = [['desktop', 1280, 900], ['phone', 390, 844]]

describe('Settings › Returns portal layout', () => {
  afterEach(async () => {
    cleanup()
    vi.unstubAllGlobals()
    clearAccessToken()
    resetCapabilitiesCache()
    document.documentElement.dir = 'ltr'
    await page.viewport(1280, 800)
  })

  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      for (const feesVisible of [false, true]) {
        test(`${lng} ${size} ${w}px, fees ${feesVisible ? 'shown' : 'hidden'}`, async () => {
          await page.viewport(w, h)
          resetCapabilitiesCache()
          setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', exp: 9999999999 })) + '.s')
          backend(feesVisible)
          const i18n = i18next.createInstance()
          await i18n.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
            resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
          document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
          const { container } = render(
            <MemoryRouter initialEntries={['/settings?tab=portal']}>
              <I18nextProvider i18n={i18n}>
                <ToastProvider>
                  <main dir={lng === 'ar' ? 'rtl' : 'ltr'} lang={lng} className="bg-base min-h-screen p-6 max-[899px]:p-4">
                    <SettingsPage />
                  </main>
                </ToastProvider>
              </I18nextProvider>
            </MemoryRouter>,
          )
          await expect.poll(() => container.querySelector('[data-testid="variant-list"]')).toBeTruthy()
          await expect.poll(() => container.querySelector('#portal-return-location')).toBeTruthy()
          await expect.poll(() => container.querySelector('[data-testid="other-couriers-row"]')).toBeTruthy()
          if (feesVisible) await expect.poll(() => container.querySelector('[data-testid="fee-rules-table"]')).toBeTruthy()
          await new Promise(r => setTimeout(r, 300))     // fonts and the preview settle

          expect(document.documentElement.scrollWidth, 'page scrolls sideways').toBeLessThanOrEqual(window.innerWidth)
          for (const el of container.querySelectorAll<HTMLElement>('button, a, input, select, textarea')) {
            if (el.offsetParent === null || el.classList.contains('sr-only')) continue
            const r = el.getBoundingClientRect()
            expect(r.left, el.outerHTML.slice(0, 80)).toBeGreaterThanOrEqual(0)
            expect(r.right, el.outerHTML.slice(0, 80)).toBeLessThanOrEqual(w + 0.5)
          }
          expect(overlappingButtons(container)).toEqual([])

          const nav = container.querySelector<HTMLElement>('[data-testid="settings-nav"]')!.getBoundingClientRect()
          const tab = container.querySelector<HTMLElement>('[data-testid="returns-portal-settings"]')!.getBoundingClientRect()
          if (size === 'phone') expect(nav.bottom, 'menu above the page').toBeLessThanOrEqual(tab.top)
          else expect(lng === 'ar' ? nav.left >= tab.right - 1 : nav.right <= tab.left + 1, 'menu beside the page').toBe(true)

          if (SHOTS) {
            await page.viewport(w, Math.ceil(document.documentElement.scrollHeight))
            await new Promise(r => setTimeout(r, 200))
            await page.screenshot({ path: `${SHOTS}/${lng}-${size}-fees-${feesVisible ? 'on' : 'off'}.png` })
          }
        })
      }
    }
  }
})
