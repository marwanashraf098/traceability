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
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import Orders from '../pages/Orders'
import PickupSessions from '../pages/PickupSessions'

// Non-Bosta couriers G3 — in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL, at desktop
// 1280×800, station tablet 768×1024 and phone 390×844:
//   Orders  — the "With <carrier>" filter, the selection bar and the Mark delivered confirm dialog;
//   Pickups — an open session naming its courier, with the "This parcel goes with …" refusal showing.
// Every G3 element (filter, selection bar, confirm dialog, the session's courier badge, the refusal banner) stays
// inside the page and none of their buttons overlap. Whole-page sideways scroll is NOT asserted at phone width:
// two pre-existing overflows predate G3 and are unchanged by it — the Orders tab strip once its counts load
// (485px at 390) and the pickup scan table's ✕ column (424px at 390), both measured on the page with G3 off.

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data) })

const status = { primaryKey: 'status.with_carrier', tone: 'INFO', healthChips: [], historicalNote: null, conflictKey: null,
  notTraced: false, packedConfirmed: true, fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS' }
const row = (id: string, number: string) => ({
  id, number, customerName: 'Mona Abdelrahman El-Sayed', customerPhone: '01012345678', status: 'packed', onHold: false,
  codAmount: 1250, placedAt: '2026-10-18T10:00:00Z', trackingNumber: '34127840151', deliveryState: 'with_courier',
  exceptionReason: null, bostaLinkStatus: null, failedDeliveryAttempts: 0, isDelayed: null, slaBreached: null,
  notTracedAt: null, isExchange: false, derivedStatus: status,
  shippingBadge: { state: 'generic_carrier', carrier: 'SMSA Express', days: null },
})

const SESSION = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-10-20', scheduledTimeSlot: '10:00 to 13:00',
  scannedCount: 1, openedByName: 'Fatma', createdAt: '2026-10-20T08:00:00Z', notes: null, closedByName: null,
  closedAt: null, carrier: 'SMSA Express',
  scans: [{ shipmentId: 'sh-1', trackingNumber: '290123456789012', orderNumber: '#5001', codAmount: 1250,
            scannedAt: '2026-10-20T08:01:00Z', scannedByName: 'Fatma', carrierName: 'SMSA Express' }] }

function backend() {
  stubFetchWithShellDefaults((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    if (url.includes('/generic-shipments/carriers')) return json([{ carrier: 'SMSA Express', withCourier: 12 }, { carrier: 'Aramex', withCourier: 3 }])
    if (url.includes('/orders/summary')) return json({ total: 15, withCourier: 15, delivered: 0, returned: 0 })
    if (url.includes('/orders?')) return json({ items: [row('o1', '#5001'), row('o2', '#5002')], page: 0, size: 20, total: 2 })
    if (url.endsWith('/pickup-sessions') && method === 'GET') return json([SESSION])
    if (url.endsWith('/pickup-sessions/pk-1')) return json(SESSION)
    if (url.endsWith('/pickup-sessions/pk-1/scans')) return json({ outcome: 'WRONG_CARRIER', entry: {}, carrier: 'SMSA Express' })
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

function insidePage(root: HTMLElement, w: number, selector: string) {
  for (const el of root.querySelectorAll<HTMLElement>(selector)) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    expect(r.left, el.outerHTML.slice(0, 80)).toBeGreaterThanOrEqual(0)
    expect(r.right, el.outerHTML.slice(0, 80)).toBeLessThanOrEqual(w + 0.5)
  }
}

async function i18nFor(lng: 'en' | 'ar') {
  const i18n = i18next.createInstance()
  await i18n.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return i18n
}

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['tablet', 768, 1024], ['phone', 390, 844]]

describe('G3 layouts', () => {
  afterEach(async () => {
    cleanup()
    vi.unstubAllGlobals()
    clearAccessToken()
    resetCapabilitiesCache()
    document.documentElement.dir = 'ltr'
    await page.viewport(1280, 800)
  })

  for (const lng of ['en', 'ar'] as const) {
    for (const [name, w, h] of SIZES) {
      test(`Orders carrier filter + Mark delivered — ${lng} ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        resetCapabilitiesCache()
        setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
        backend()
        const i18n = await i18nFor(lng)
        const { container } = render(
          <MemoryRouter><I18nextProvider i18n={i18n}>
            <div dir={lng === 'ar' ? 'rtl' : 'ltr'} className="px-4"><Orders /></div>
          </I18nextProvider></MemoryRouter>)
        await expect.poll(() => container.querySelector('[data-testid="orders-carrier-filter"]')).toBeTruthy()
        const select = container.querySelector<HTMLSelectElement>('[data-testid="orders-carrier-filter"]')!
        select.value = 'SMSA Express'
        select.dispatchEvent(new Event('change', { bubbles: true }))
        await expect.poll(() => container.querySelector('[data-testid="orders-mark-bar"]')).toBeTruthy()
        await expect.poll(() => container.querySelector('[data-testid="orders-select-#5001"] input')).toBeTruthy()
        container.querySelector<HTMLInputElement>('[data-testid="orders-select-#5001"] input')!.click()
        insidePage(container, w, '[data-testid="orders-mark-bar"], [data-testid="orders-mark-bar"] button, [data-testid="orders-mark-bar"] input, [data-testid="orders-carrier-filter"]')
        if (w >= 768) expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
        expect(overlappingButtons(container.querySelector('[data-testid="orders-mark-bar"]')!)).toEqual([])

        const markBtn = [...container.querySelectorAll<HTMLButtonElement>('[data-testid="orders-mark-bar"] button')].pop()!
        markBtn.click()
        await expect.poll(() => document.querySelector('[data-testid="orders-mark-confirm"]')).toBeTruthy()
        const dialog = document.querySelector<HTMLElement>('[data-testid="orders-mark-confirm"]')!
        insidePage(document.body, w, '[data-testid="orders-mark-confirm"], [data-testid="orders-mark-confirm"] button')
        expect(overlappingButtons(dialog)).toEqual([])
      })

      test(`Pickups session courier + wrong-carrier refusal — ${lng} ${name} ${w}×${h}`, async () => {
        await page.viewport(w, h)
        resetCapabilitiesCache()
        setAccessToken(`h.${btoa(JSON.stringify({ role: 'worker' }))}.s`)
        backend()
        const i18n = await i18nFor(lng)
        const { container } = render(
          <MemoryRouter><I18nextProvider i18n={i18n}>
            <div dir={lng === 'ar' ? 'rtl' : 'ltr'} className="px-4"><PickupSessions /></div>
          </I18nextProvider></MemoryRouter>)
        await expect.poll(() => container.querySelector('tr.tbl-row')).toBeTruthy()
        container.querySelector<HTMLElement>('tr.tbl-row')!.click()
        await expect.poll(() => container.querySelector('[data-testid="pickup-carrier"]')).toBeTruthy()
        const input = container.querySelector<HTMLInputElement>('input.input')!
        input.value = '8123456789'
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
        await expect.poll(() => container.textContent?.includes(lng === 'ar' ? 'هذا الطرد يذهب مع' : 'This parcel goes with')).toBe(true)
        insidePage(container, w, '[data-testid="pickup-carrier"], input.input, [data-testid="pickup-scan-feedback"]')
        if (w >= 768) {
          // tablet / desktop (the station screen): the whole page fits, no buttons overlap
          expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
          insidePage(container, w, 'button')
          expect(overlappingButtons(container)).toEqual([])
        }
      })
    }
  }
})
