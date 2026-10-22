import { afterEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup, render, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import { ToastProvider } from '../components/ui'

// Non-Bosta couriers G4b — in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, at desktop
// 1280×800, tablet 768×1024 and phone 390×844:
//   the request drawer's "Book the exchange with <carrier>" panel inside the exchange progress (customer block,
//   courier + outbound + return waybill inputs, save, a refusal message) — every element inside the viewport and no buttons overlapping at every size; no sideways
//   page scroll from tablet up (at phone width the Returns & exchanges page itself is wider — pre-existing);
//   the customer portal's exchange pickup step for an order shipped with another courier — same checks.

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const DETAIL = {
  id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#6001', customerName: 'Mona Abdelrahman El-Sayed',
  customerPhone: '01012345678', type: 'exchange', status: 'approved', email: null, note: null,
  createdAt: '2026-10-20T10:00:00Z', deliveredAt: '2026-10-18T10:00:00Z',
  pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
  pickupDistrictName: null, pickupDistrictNameAr: null,
  bookingStatus: 'manual', bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
  decidedAt: '2026-10-20T10:00:00Z', decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
  items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
    variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting',
    replacementVariantId: 'v-2', replacementVariantTitle: 'White / L' }],
  exchange: { trackingNumber: null, exchangeStatus: null, orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M', orderStatus: 'new',
    shipmentState: null, deliveredAt: null, withCourierAt: null },
  otherCourier: { forwardCarrierName: 'SMSA Express', carrierName: 'SMSA Express',
    pickup: { name: 'Mona Abdelrahman El-Sayed', phone: '01012345678',
      addressLines: ['Building 14, Abbas El-Akkad Street, off Makram Ebeid', 'Nasr City', 'Cairo'] },
    replacement: { orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M', outboundTrackingNumber: null, outboundState: null } },
}

function backend() {
  stubFetchWithShellDefaults((url: string) => {
    if (url.endsWith('/manual-courier')) return json({ code: 'SAME_AS_OUTBOUND',
      message_en: "The return waybill must be different from the replacement's outbound waybill. Enter the return's own number, or leave it empty — the old item can still be scanned in by its piece.",
      message_ar: 'يجب أن تختلف بوليصة الإرجاع عن بوليصة إرسال البديل. أدخل رقم الإرجاع الخاص، أو اتركه فارغًا — يمكن استلام المنتج القديم بمسح قطعته.' }, 409)
    if (url.includes('/generic-shipments/carriers')) return json([{ carrier: 'SMSA Express', withCourier: 3 }])
    if (url.includes('/return-requests/rr-1')) return json(DETAIL)
    if (url.includes('/tenant/portal-settings')) return json({ pickupBooking: true })
    if (url.includes('/returns-exchanges')) return json({ items: [], nextCursor: null, stages: {}, tiles: {} })
    return json({})
  }, { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: true } })
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

function insideViewport(root: ParentNode, w: number, selector: string) {
  for (const el of root.querySelectorAll<HTMLElement>(selector)) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    expect(r.left, el.outerHTML.slice(0, 80)).toBeGreaterThanOrEqual(-0.5)
    expect(r.right, el.outerHTML.slice(0, 80)).toBeLessThanOrEqual(w + 0.5)
  }
}

async function appI18n(lng: 'en' | 'ar') {
  const i18n = i18next.createInstance()
  await i18n.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return i18n
}

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['tablet', 768, 1024], ['phone', 390, 844]]

describe('G4b layouts', () => {
  afterEach(async () => {
    cleanup()
    vi.unstubAllGlobals()
    clearAccessToken()
    resetCapabilitiesCache()
    localStorage.clear()
    document.documentElement.dir = 'ltr'
    await page.viewport(1280, 800)
  })

  for (const lng of ['en', 'ar'] as const) {
    for (const [name, w, h] of SIZES) {
      test(`drawer — Book the exchange with <carrier> — ${lng} ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        resetCapabilitiesCache()
        setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
        backend()
        const i18n = await appI18n(lng)
        render(
          <MemoryRouter initialEntries={['/exchanges?tab=requests&request=rr-1']}><I18nextProvider i18n={i18n}>
            <ToastProvider><div dir={lng === 'ar' ? 'rtl' : 'ltr'}><ExchangesRefunds /></div></ToastProvider>
          </I18nextProvider></MemoryRouter>)
        await expect.poll(() => document.querySelector('[data-testid="manual-courier-form"]')).toBeTruthy()
        const panel = document.querySelector<HTMLElement>('[data-testid="manual-courier"]')!
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
        for (const id of ['#manual-outbound', '#manual-tracking']) {
          const input = panel.querySelector<HTMLInputElement>(id)!
          input.focus()
          setter.call(input, 'SMSA-ABC-123')
          input.dispatchEvent(new Event('input', { bubbles: true }))
        }
        const save = [...panel.querySelectorAll<HTMLButtonElement>('button')].pop()!
        await expect.poll(() => save.textContent).toMatch(lng === 'ar' ? /التجهيز والتغليف/ : /Pick & Pack/)
        save.click()
        await expect.poll(() => panel.querySelector('[data-testid="manual-courier-error"]')).toBeTruthy()
        insideViewport(panel, w, '[data-testid="manual-courier"], [data-testid="manual-courier-form"], input, button, p')
        expect(overlappingButtons(panel)).toEqual([])
        // Whole-page sideways scroll only from tablet up: at 390 the Returns & exchanges page itself (its 230px
        // search box and the cases table) is wider than the screen — measured with the panel inside, unchanged by G4a.
        if (w >= 768) expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
        expect(within(panel).queryByText(/Bosta|بوسطة/)).toBeNull()
      })

      test(`portal — exchange step for another courier — ${lng} ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        vi.stubGlobal('fetch', (url: string) => {
          if (url.endsWith('/config')) return json({ storeName: 'Nour Studio', returnWindowDays: 14, reasonCodes: ['wrong_size'],
            logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: true,
            exchangesEnabled: true, otherCouriers: true })
          if (url.endsWith('/lookup')) return json({ token: 'tok', orderNumber: '#6001', deliveredAt: '2026-10-18T10:00:00Z',
            lines: [{ variantId: 'v1', productTitle: 'Linen Shirt Oversized Relaxed Fit', variantTitle: 'Sand / M', imageUrl: null,
              deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
              optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }], currentOptions: ['Sand', 'M'],
              exchangeOptions: [{ variantId: 'v2', title: 'Sand / L', options: ['Sand', 'L'], inStock: true }] }],
            pickup: null, otherCourier: { carrierName: 'SMSA Express' } })
          return json({}, 404)
        })
        render(<I18nextProvider i18n={createPortalI18n(lng)}><PortalApp slug="nourstudio" /></I18nextProvider>)
        await expect.poll(() => document.getElementById('pp-order')).toBeTruthy()
        const type = (id: string, v: string) => {
          const el = document.getElementById(id) as HTMLInputElement
          Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(el, v)
          el.dispatchEvent(new Event('input', { bubbles: true }))
        }
        type('pp-order', '#6001')
        type('pp-phone', '01012345678')
        ;(document.querySelector('form button[type=submit]') as HTMLElement).click()
        await expect.poll(() => document.querySelector('[data-testid="portal-line"]')).toBeTruthy()
        const modes = document.querySelectorAll<HTMLInputElement>('input[name="pp-mode"]')
        expect(modes.length).toBe(2)
        modes[1].click()
        await expect.poll(() => document.querySelector('[data-testid="exchange-line"] input')).toBeTruthy()
        document.querySelector<HTMLInputElement>('[data-testid="exchange-line"] input')!.click()
        const select = document.querySelector<HTMLSelectElement>('select')!
        select.value = 'wrong_size'
        select.dispatchEvent(new Event('change', { bubbles: true }))
        await expect.poll(() => (document.querySelector('.pp-bar button') as HTMLButtonElement | null)?.disabled).toBe(false)
        ;(document.querySelector('.pp-bar button') as HTMLElement).click()
        await expect.poll(() => [...document.querySelectorAll<HTMLInputElement>('.pp-chips input')]
          .find(i => !i.disabled && !i.checked)).toBeTruthy()
        ;[...document.querySelectorAll<HTMLInputElement>('.pp-chips input')].find(i => !i.disabled && !i.checked)!.click()
        await expect.poll(() => (document.querySelector('.pp-bar button') as HTMLButtonElement | null)?.disabled).toBe(false)
        ;(document.querySelector('.pp-bar button') as HTMLElement).click()
        await expect.poll(() => document.body.textContent).toMatch(lng === 'ar' ? /يرسل Nour Studio مقاسك الجديد/ : /sends your new size/)
        expect(document.querySelector('[data-testid="address-choice"]')).toBeNull()
        expect(document.body.textContent).not.toMatch(/Bosta|بوسطة/)
        insideViewport(document, w, '.pp-card, .pp-bar, .pp-bar button')
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
      })
    }
  }
})
