import { afterEach, describe, expect, test, vi } from 'vitest'
import { page, server, userEvent } from 'vitest/browser'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import '../index.css'
import ar from '../locales/ar.json'
import en from '../locales/en.json'
import { setAccessToken, clearAccessToken } from '../auth'
import { ToastProvider } from '../components/ui'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import { StationProvider } from '../components/StationProvider'
import StockTakeReview from '../pages/StockTakeReview'

// Stock-take review (2026-10-27) in a real browser with the app's CSS (Chromium + WebKit), EN/LTR and AR/RTL,
// desktop 1280×800 and phone 390×844: the open review, the "Found one? Scan it" overlay after scans, the finalize
// confirm (piece list, and grouped when > 10) and the finalized review — no sideways page scroll, every control
// inside the viewport, the overlay covering the whole viewport. VITE_ST_REVIEW_SHOT_DIR (optional) saves PNGs.

const SHOT_DIR = (import.meta.env.VITE_ST_REVIEW_SHOT_DIR as string | undefined) ?? ''

const json = (data: unknown, status = 200) => Promise.resolve({ ok: status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

const SESSION = { sessionId: 'st-1', status: 'open', scopeType: 'variant_subset', locationId: 'loc-1', completeCount: true,
  openedBy: 'u1', openedByName: 'Nour Hassan', openedAt: '2026-10-26T15:20:00Z', finalizedBy: null, finalizedByName: null,
  finalizedAt: null, note: 'Shelf A — linen', shopifySync: null }

type Row = Record<string, unknown>
const row = (pieceId: string, shortCode: string, over: Row = {}): Row => ({ pieceId, shortCode, variantId: 'v1',
  variantTitle: 'Sand / Large', sku: 'LIN-SND-L', productTitle: 'Linen overshirt with a long product name', liveStatus: 'available', ...over })
const buckets = (over: Record<string, Row[]> = {}) => ({ on_shelf_counted: [], on_shelf_uncounted: [], committed_to_orders: [],
  with_courier_or_delivered: [], returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [], ...over })
const wo = (i: number, over: Row = {}): Row => ({ pieceId: 'w' + i, shortCode: 'P000' + (140 + i), productTitle: 'Linen overshirt with a long product name',
  variantTitle: 'Sand / Large', variantId: 'v1', origin: 'available', shopifyEffect: -1, ...over })
// Built like the server's plan: byVariant from the same pieces, and the top-level shopifyDecrement = the sum of
// the variant lines (StockTakeReconciliationService.FinalizePlan.toResponse).
function plan(pieces: Row[]) {
  const byVariant = [...new Set(pieces.map(p => p.variantId as string))].map(variantId => {
    const of = pieces.filter(p => p.variantId === variantId)
    return { variantId, variantTitle: of[0].variantTitle, sku: 'SKU-' + variantId,
      available: of.filter(p => p.origin === 'available').length, damaged: of.filter(p => p.origin === 'damaged').length,
      onHold: of.filter(p => p.origin === 'on_hold').length, alreadyWrittenOff: 0, driftSkipped: 0,
      shopifyDecrement: of.filter(p => p.shopifyEffect === -1).length }
  })
  return { scans: 60, expectedFree: 65, scannedFree: 60, coveragePercent: 92.3, writeOffs: pieces.length,
    writeOffPieces: pieces, damageCorrections: 0, founds: 0, foundIncrements: 0, driftSkipped: 0, alreadyWrittenOff: 0,
    shopifyDecrement: byVariant.reduce((n, v) => n + v.shopifyDecrement, 0), byVariant,
    requiresTypedConfirmation: false, minCoveragePercent: 80, maxWriteOffPercent: 20, blockedReason: null }
}

function backend(kind: 'open' | 'finalized', pieces: Row[] = [wo(1), wo(2), wo(3, { origin: 'damaged', shopifyEffect: 0 })]) {
  const uncounted = [row('p1', 'P000147'), row('p2', 'P000151'), row('p3', 'P000300', { flag: 'condition_mismatch' })]
  vi.stubGlobal('fetch', (url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    if (url.includes('/station/pairings/current')) return json({ status: 'none' })
    if (url.endsWith('/stock-takes/sessions/st-1')) return json(kind === 'open' ? SESSION
      : { ...SESSION, status: 'finalized', finalizedBy: 'u1', finalizedByName: 'Nour Hassan', finalizedAt: '2026-10-27T08:49:00Z',
          shopifySync: { status: 'pushed', deltas: [{ variantId: 'v1', variantTitle: 'Sand / Large', sku: 'LIN-SND-L', delta: -5 }],
            referenceDocumentUri: 'traced://stock-take/st-1', pushedAt: '2026-10-27T08:49:03Z', error: null } })
    if (url.endsWith('/reconciliation')) return json(kind === 'open'
      ? { sessionId: 'st-1', status: 'open', completeCount: true, coveragePercent: 92.3, scanCount: 60, phoneScanCount: 12,
          variantRollup: [{ variantId: 'v1', variantTitle: 'Sand / Large', sku: 'LIN-SND-L', totalKnown: 65, expectedOnShelf: 65,
            counted: 60, variance: 5, committed: 3, gone: 0, damagedCount: 0 }],
          buckets: buckets({ on_shelf_counted: [row('c1', 'P000001', { scanDevice: 'phone' })], on_shelf_uncounted: uncounted }),
          finalizePlan: plan(pieces) }
      : { sessionId: 'st-1', status: 'finalized', completeCount: true, coveragePercent: 92.3, variantRollup: [],
          buckets: buckets({ on_shelf_counted: [row('c1', 'P000001'), row('c2', 'P000002')],
            previously_written_off: [row('l1', 'P000147', { liveStatus: 'lost', writtenOffHere: true })],
            unexpected_finds: [row('f1', 'P000050', { foundHere: true })] }) })
    if (url.endsWith('/scan') && method === 'POST') {
      const code = JSON.parse(String(opts.body)).barcode as string
      return json({ sessionId: 'st-1', barcode: code, pieceId: 'x', alreadyScanned: code.endsWith('again'),
        classification: code.endsWith('other') ? 'out_of_scope' : 'match', scanDevice: 'hardware' })
    }
    return json({})
  })
}

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  document.documentElement.dir = lng === 'ar' ? 'rtl' : 'ltr'
  return inst
}

function checkLayout(w: number) {
  expect(document.documentElement.scrollWidth, 'no sideways page scroll').toBeLessThanOrEqual(w)
  for (const el of Array.from(document.querySelectorAll('button, input, select, a'))) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    expect(r.left, `${el.outerHTML.slice(0, 60)} inside on the left`).toBeGreaterThanOrEqual(-0.5)
    expect(r.right, `${el.outerHTML.slice(0, 60)} inside on the right`).toBeLessThanOrEqual(w + 0.5)
  }
}

async function shot(name: string) {
  if (!SHOT_DIR) return
  await page.screenshot({ path: `${SHOT_DIR}/${server.browser}-${name}.png` })
}

function mount(lng: 'en' | 'ar') {
  setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner', tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s')
  render(
    <StationProvider><MemoryRouter initialEntries={['/stock-take/st-1/review']}>
      <I18nextProvider i18n={i18nFor(lng)}><ToastProvider><PhoneScanProvider>
        <div dir={lng === 'ar' ? 'rtl' : 'ltr'} className="bg-bg p-4 sm:p-6">
          <Routes><Route path="/stock-take/:id/review" element={<StockTakeReview />} /></Routes>
        </div>
      </PhoneScanProvider></ToastProvider></I18nextProvider>
    </MemoryRouter></StationProvider>)
}

const SIZES: [string, number, number][] = [['desktop', 1280, 800], ['phone', 390, 844]]
const T = (lng: 'en' | 'ar') => (lng === 'en' ? en : ar).stocktake.review

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

describe('stock-take review layout', () => {
  for (const lng of ['en', 'ar'] as const) {
    for (const [size, w, h] of SIZES) {
      test(`open review ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('open')
        mount(lng)
        await screen.findByRole('button', { name: T(lng).scan.openButton })
        await screen.findByTestId('uncounted-hint')
        checkLayout(w)
        await shot(`open-${lng}-${size}`)
      })

      test(`scan overlay ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('open')
        mount(lng)
        await userEvent.click(await screen.findByRole('button', { name: T(lng).scan.openButton }))
        const overlay = await screen.findByTestId('review-scan-overlay')
        const input = within(overlay).getByRole('textbox')
        await expect.poll(() => document.activeElement === input, { message: 'the overlay input has focus' }).toBe(true)
        for (const code of ['PC-p1', 'PC-again', 'PC-other']) {
          await userEvent.keyboard(code + '{Enter}')
          await expect.poll(() => within(overlay).queryAllByTestId('review-scan-feedback').length).toBeGreaterThan(0)
        }
        await expect.poll(() => within(overlay).queryAllByTestId('review-scan-feedback').length).toBe(3)
        const r = overlay.getBoundingClientRect()
        expect(r.left).toBeLessThanOrEqual(0.5); expect(r.top).toBeLessThanOrEqual(0.5)
        // The layout viewport (without the page's own scrollbar, if any).
        const vw = document.documentElement.clientWidth, vh = document.documentElement.clientHeight
        expect(r.width).toBeGreaterThanOrEqual(vw - 0.5); expect(r.height).toBeGreaterThanOrEqual(vh - 0.5)
        checkLayout(w)
        await shot(`overlay-${lng}-${size}`)
      })

      for (const [name, pieces] of [
        ['finalize-list', [wo(1), wo(2), wo(3, { origin: 'damaged', shopifyEffect: 0 })]],
        ['finalize-grouped', Array.from({ length: 14 }, (_, i) => wo(i + 1, i >= 11 ? { variantId: 'v2', variantTitle: 'Olive / Medium' } : {}))],
      ] as const) {
        test(`${name} ${lng} ${size}`, async () => {
          await page.viewport(w, h)
          backend('open', [...pieces])
          mount(lng)
          await userEvent.click(await screen.findByRole('button', { name: T(lng).finalizeButton }))
          await screen.findByTestId('finalize-pieces')
          // The "goes down by N" sentence and the per-variant "Shopify −N" lines agree.
          const expected = pieces.filter(p => p.shopifyEffect === -1).length
          const sentence = screen.getByTestId('finalize-summary').textContent ?? ''
          const lines = Array.from(screen.getByTestId('finalize-variants').querySelectorAll('.font-mono'))
            .map(el => Number(/Shopify\s*[−-](\d+)/.exec(el.textContent ?? '')?.[1] ?? NaN))
          expect(lines.every(Number.isFinite), 'every variant line names its Shopify change').toBe(true)
          expect(lines.reduce((a, b) => a + b, 0)).toBe(expected)
          const said = (lng === 'en' ? /goes down by (\d+)/ : /بمقدار (\d+)/).exec(sentence)?.[1]
          expect(Number(said), 'the sentence names the same total').toBe(expected)
          checkLayout(w)
          await shot(`${name}-${lng}-${size}`)
        })
      }

      test(`finalized review ${lng} ${size}`, async () => {
        await page.viewport(w, h)
        backend('finalized')
        mount(lng)
        await screen.findByTestId('finalized-lookup-hint')
        expect(screen.queryByRole('button', { name: T(lng).scan.openButton })).toBeNull()
        checkLayout(w)
        await shot(`finalized-${lng}-${size}`)
      })
    }
  }
})
