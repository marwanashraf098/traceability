import { afterEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import { MemoryRouter } from 'react-router-dom'
import '../index.css'
import '../portal/portal.css'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { ToastProvider } from '../components/ui'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'

// Defect replacement (V246) in a real browser (Chromium + WebKit), phone 390, EN/LTR and AR/RTL: the portal's
// "Replace with the same item" (in stock and sold out), the review step and the sent screen, and the merchant's
// "Replacement (same item)" type tag — nothing scrolls sideways and the same-item card stays inside the page.
// Set VITE_PORTAL_SHOTS=<dir> to also save a screenshot of each state.

const SHOTS = (import.meta.env.VITE_PORTAL_SHOTS as string | undefined) || null

const json = (d: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(d), { status, headers: { 'content-type': 'application/json' } }))

const CONFIG = {
  storeName: 'Nour Studio', returnWindowDays: 30,
  reasonCodes: ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: false,
  exchangesEnabled: true, replacementReasons: ['damaged', 'wrong_item'],
}

const line = (inStock: boolean) => ({
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }], currentOptions: ['White', 'M'],
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true }],
  sameItem: { inStock },
})

function portalBackend(inStock: boolean) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return json(CONFIG)
    if (url.endsWith('/lookup')) return json({ token: 't', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z',
      pickup: null, lines: [line(inStock)] })
    if (url.endsWith('/requests')) return json({ reference: 'RR-7K3F9M', status: 'requested' }, 201)
    return json({}, 404)
  }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function settle() {
  await new Promise(r => setTimeout(r, 250))
}

function fitsSideways(el: HTMLElement | null) {
  expect(document.documentElement.scrollWidth, 'page scrolls sideways').toBeLessThanOrEqual(window.innerWidth)
  if (!el) return
  const r = el.getBoundingClientRect()
  expect(r.left).toBeGreaterThanOrEqual(0)
  expect(r.right).toBeLessThanOrEqual(window.innerWidth + 0.5)
}

async function shot(name: string) {
  if (!SHOTS) return
  await page.viewport(390, Math.max(844, Math.ceil(document.documentElement.scrollHeight)))
  await settle()
  await page.screenshot({ path: `${SHOTS}/${name}.png` })
  await page.viewport(390, 844)
}

describe('defect replacement layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    test(`portal ${lng}: defect reason → same item, sold out, review, sent`, async () => {
      await page.viewport(390, 844)
      const i18n = createPortalI18n(lng)
      const t = i18n.getFixedT(lng)
      for (const inStock of [false, true]) {
        portalBackend(inStock)
        const user = userEvent.setup()
        render(<I18nextProvider i18n={i18n}><PortalApp slug="nourstudio" /></I18nextProvider>)
        await screen.findByRole('heading', { name: t('p1.title') })
        await user.type(screen.getByLabelText(t('p1.orderLabel')), '#1047')
        await user.type(screen.getByLabelText(t('p1.phoneLabel')), '010 1234 5678')
        await user.click(screen.getByRole('button', { name: t('p1.find') }))
        await user.click(await screen.findByRole('radio', { name: new RegExp(t('x1.exchange')) }))
        await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
        await user.selectOptions(screen.getByLabelText(t('x1.reasonLabel')), 'damaged')
        await shot(`portal-${lng}-1-reason`)
        await user.click(screen.getByRole('button', { name: t('p2.continue') }))
        const same = await screen.findByTestId('same-item')
        await settle()
        fitsSideways(same)
        if (!inStock) {
          expect(within(same).getByRole('radio')).toBeDisabled()
          await shot(`portal-${lng}-2-out-of-stock`)
          cleanup()
          continue
        }
        await shot(`portal-${lng}-2-replace-option`)
        await user.click(within(same).getByRole('radio'))
        await shot(`portal-${lng}-3-replace-chosen`)
        await user.click(screen.getByRole('button', { name: t('p2.continue') }))
        await screen.findByTestId('summary-line')
        await settle()
        fitsSideways(screen.getByTestId('summary-line'))
        await shot(`portal-${lng}-4-confirm`)
        await user.click(screen.getByRole('button', { name: t('p3.send') }))
        await screen.findByRole('heading', { name: t('x3.replacementTitle') })
        await settle()
        fitsSideways(screen.getByTestId('sent-lead'))
        await shot(`portal-${lng}-5-sent`)
        cleanup()
      }
    })

    test(`merchant ${lng}: the Replacement (same item) type tag`, async () => {
      await page.viewport(1280, 800)
      const kase = (id: string, exchangeKind?: 'replacement') => ({
        caseType: 'A', id, kind: 'exchange', reference: `RR-${id.toUpperCase()}`, source: 'returns_page', exchangeKind,
        customerName: 'Mariam Saleh', orderNumber: '#1047', stage: 'to_do', nextStep: 'approve', tone: 'action',
        overdueDays: null, status: 'requested',
        reason: { bookingStatus: null, bookingError: null, itemsCount: 1, arrivedCount: 0, awaitingCount: 1, candidateCount: 0,
          candidateReferences: null, trackingNumber: null, refundTotal: '0.00', currency: 'EGP', closeReason: null, inspectionState: null },
        alerts: [], itemsSummary: id === 'r1' ? 'Linen Shirt White / M → White / M' : 'Linen Shirt White / M → White / L',
        itemsSummaryAr: id === 'r1' ? 'Linen Shirt White / M → White / M' : 'Linen Shirt White / M → White / L', notScanned: false,
        updatedAt: new Date().toISOString(), legStatus: null, target: { requestId: id, exchangeId: null, shipmentId: null },
      })
      stubFetchWithShellDefaults(vi.fn((url: string) => {
        const u = new URL(url, 'http://x')
        if (u.pathname.endsWith('/returns-exchanges/counts')) return json({ stages: { all: 2, to_do: 2, in_progress: 0, done: 0 }, tiles: {} })
        if (u.pathname.endsWith('/returns-exchanges')) return json({ items: [kase('r1', 'replacement'), kase('s1')], nextCursor: null })
        return json({})
      }))
      const i18n = i18next.createInstance()
      await i18n.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
        resources: { ar: { translation: ar }, en: { translation: en } }, interpolation: { escapeValue: false } })
      document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
      render(
        <MemoryRouter initialEntries={['/exchanges']}>
          <I18nextProvider i18n={i18n}>
            <ToastProvider>
              <main dir={lng === 'ar' ? 'rtl' : 'ltr'} lang={lng} className="bg-base min-h-screen p-6">
                <ExchangesRefunds />
              </main>
            </ToastProvider>
          </I18nextProvider>
        </MemoryRouter>,
      )
      const rows = await screen.findAllByTestId('case-row')
      const tag = within(rows.find(r => r.textContent?.includes('RR-R1'))!).getByTestId('type-tag')
      expect(tag).toHaveTextContent(lng === 'ar' ? 'استبدال بنفس المنتج' : 'Replacement (same item)')
      await settle()
      expect(document.documentElement.scrollWidth, 'page scrolls sideways').toBeLessThanOrEqual(window.innerWidth)
      if (SHOTS) {
        await settle()
        await page.screenshot({ path: `${SHOTS}/merchant-${lng}-list-pill.png` })
      }
    })
  }
})
