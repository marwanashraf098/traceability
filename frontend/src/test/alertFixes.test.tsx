import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import userEvent from '@testing-library/user-event'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { setAccessToken, clearAccessToken } from '../auth'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExceptionsPage, { ALERT_LABELS, ALERT_TYPES } from '../pages/Exceptions'
import FulfillRoute from '../pages/fulfill/FulfillRoute'
import { alertDestination, goTargetFor } from '../alertTargets'

/**
 * O6-fixes — the four Alerts-page problems found while writing the Help center.
 *   f1  every alert type the backend raises (read from ExceptionService.java) has an EN and an AR label
 *   f2  every "clears when" code the backend sends (CONDITION_ONLY) has its EN and AR text; an unknown code shows a
 *       plain fallback, never the raw key
 *   f3  every "Go →" target is a real route: each actionUrl the backend can build (derived from ExceptionService.java,
 *       per alert type) goes through goTargetFor and lands on a route in App.tsx (or no button); /orders/<id> → the
 *       order drawer, /shipments/... → the order's drawer or none, Unpack Required → Pick & Pack's unpack screen
 *   f3b the Overview alerts widget uses the SAME mapping: every destination is the order drawer or a real route — no
 *       dead link anywhere an alert is shown
 *   f4  the Orders "Not traced" hint says what the tag means (no piece was scanned out)
 */

const ROOT = resolve(__dirname, '../../..')
const SERVICE = readFileSync(resolve(ROOT, 'src/main/java/com/traceability/inventory/ExceptionService.java'), 'utf8')
const APP = readFileSync(resolve(ROOT, 'frontend/src/App.tsx'), 'utf8')

const RAISED = [...new Set([
  ...[...SERVICE.matchAll(/'([a-z_]+)' AS type/g)].map(m => m[1]),
  ...[...SERVICE.matchAll(/put\("type",\s*"([a-z_]+)"\)/g)].map(m => m[1]),
])].sort()

const ID = '7f3c9a10-1b2c-4d5e-8f90-123456789abc'

/** Each case block of the enrich switch → its actionUrl expressions, as sample URLs (identifiers → an id, both
 *  branches of a ternary, ordersUrl(item) → with and without an order). */
function sampleTargets(): { type: string; url: string }[] {
  const out: { type: string; url: string }[] = []
  const parts = SERVICE.split(/\n\s+case ("[a-z_]+"(?:,\s*"[a-z_]+")*) ->/)
  for (let i = 1; i < parts.length; i += 2) {
    const types = [...parts[i].matchAll(/"([a-z_]+)"/g)].map(m => m[1])
    const body = parts[i + 1].split(/\n\s+case "/)[0]
    for (const m of body.matchAll(/"actionUrl",\s*([^;]+);/g)) {
      const expr = m[1].replace(/\)\s*$/, '').trim()
      for (const url of evaluate(expr)) for (const type of types) out.push({ type, url })
    }
  }
  return out
}
function evaluate(expr: string): string[] {
  const e = expr.replace(/\s+/g, ' ').trim()
  if (e === 'ordersUrl(item)') return [`/orders/${ID}`, '/orders']
  const tern = /^(.*?)\s\?\s(.*?)\s:\s(.*)$/.exec(e)
  if (tern) return [...evaluate(tern[2]), ...evaluate(tern[3])]
  return [e.split('+').map(p => p.trim()).map(p => (/^"/.test(p) ? p.replace(/^"|"$/g, '') : ID)).join('')]
}

const ROUTES = [...APP.matchAll(/path="([^"]+)"/g)].map(m => m[1]).filter(p => p !== '*')
function isRoute(target: string): boolean {
  const path = target.split('?')[0]
  return ROUTES.some(r => new RegExp('^' + r.replace(/:[^/]+/g, '[^/]+') + '$').test(path))
}

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return inst
}
function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=+$/, '') + '.s'
}
const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data) })

function Where() { const l = useLocation(); return <div data-testid="where">{l.pathname + l.search}</div> }

let calls: string[]
beforeEach(() => { calls = []; setAccessToken(token('owner')) })
afterEach(() => { cleanup(); clearAccessToken(); vi.unstubAllGlobals() })

describe('f1 labels', () => {
  test('the backend raises a known set of types and every one has an EN and AR label', () => {
    expect(RAISED.length).toBeGreaterThan(40)
    for (const type of RAISED) {
      const l = ALERT_LABELS[type]
      expect(l, `${type} has no label on the Alerts page`).toBeTruthy()
      expect(l.en.trim().length, type).toBeGreaterThan(2)
      expect(l.ar.trim().length, type).toBeGreaterThan(2)
      expect(l.en, type).not.toMatch(/_/)
    }
    expect(ALERT_TYPES).toEqual(expect.arrayContaining(RAISED))
  })

  test.each([
    ['high_attempts', 'Many Delivery Attempts'], ['void_hold_sync_failed', 'Shopify Stock Update Failed'],
    ['shopify_cancel_vs_inflight', 'Cancelled In Shopify · In Transit'], ['generic_bosta_conflict', 'Bosta Waybill On Another Courier’s Order'],
    ['shopify_edit_conflict', 'Edited In Shopify After Packing'], ['return_mismatch', 'Return Mismatch'],
  ])('%s shows "%s", not its raw type', async (type, label) => {
    stubFetchWithShellDefaults((url: string) => json(url.includes('/exceptions') ? { total: 1, page: 0, size: 50,
      items: [{ type, severity: 'HIGH', subject_key: `${type}:x`, subject_type: 'order', ageSeconds: 60,
        descriptionEn: 'd', descriptionAr: 'و', suggestedAction: 'x', actionUrl: '' }] } : {}))
    render(<StationProvider><MemoryRouter initialEntries={['/exceptions']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
      <Routes><Route path="/exceptions" element={<ExceptionsPage />} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    expect(await screen.findAllByText(label)).not.toHaveLength(0)
    expect(screen.queryByText(type)).toBeNull()
  })
})

describe('f2 clears when', () => {
  const CODES = [...SERVICE.slice(SERVICE.indexOf('CONDITION_ONLY = Map.of('), SERVICE.indexOf(');', SERVICE.indexOf('CONDITION_ONLY = Map.of(')))
    .matchAll(/"[a-z_]+",\s*"([A-Z_]+)"/g)].map(m => m[1])

  test('every code the backend sends has its EN and AR text', () => {
    expect(CODES).toEqual(expect.arrayContaining(['STOCK_COVERS_EXCHANGES', 'EXCHANGE_EXPLAINED_OR_RELEASED', 'SHOPIFY_RETURN_PROCESSED']))
    for (const c of CODES) {
      expect((en.exc.clearsWhen as Record<string, string>)[c], `en ${c}`).toBeTruthy()
      expect((ar.exc.clearsWhen as Record<string, string>)[c], `ar ${c}`).toBeTruthy()
    }
  })

  test.each([
    ['STOCK_COVERS_EXCHANGES', en.exc.clearsWhen.STOCK_COVERS_EXCHANGES],
    ['A_CODE_NOBODY_WROTE', en.exc.clearsWhen.fallback],
  ])('%s → its text, never the raw key', async (code, text) => {
    stubFetchWithShellDefaults((url: string) => json(url.includes('/exceptions') ? { total: 1, page: 0, size: 50,
      items: [{ type: 'shopify_exchange_short', severity: 'MEDIUM', subject_key: 's:x', subject_type: 'variant', ageSeconds: 60,
        descriptionEn: 'd', descriptionAr: 'و', suggestedAction: 'x', actionUrl: '/receiving', clearsWhen: code }] } : {}))
    render(<StationProvider><MemoryRouter initialEntries={['/exceptions']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
      <Routes><Route path="/exceptions" element={<ExceptionsPage />} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('exc.clearsWhen')
  })
})

describe('f3 Go targets', () => {
  test('every actionUrl the backend can build lands on a real route (or shows no button)', () => {
    const samples = sampleTargets()
    expect(samples.length).toBeGreaterThan(40)
    expect(samples.some(s => s.url === '/shipments/unlinked')).toBe(true)
    expect(samples.some(s => s.type === 'guided_unpack')).toBe(true)
    const dead: string[] = []
    for (const s of samples) {
      for (const order of [ID, undefined]) {
        const target = goTargetFor({ type: s.type, actionUrl: s.url, order_id: order })
        if (target !== null && !isRoute(target)) dead.push(`${s.type}: ${s.url} → ${target}`)
      }
    }
    expect(dead).toEqual([])
  })

  test('the named fixes', () => {
    for (const type of ['unmatched_delivery', 'bosta_discovery_failed', 'generic_bosta_conflict']) {
      expect(goTargetFor({ type, actionUrl: '/shipments/unlinked', order_id: 'o1' })).toBe('/orders?order=o1')
      expect(goTargetFor({ type, actionUrl: '/shipments/unlinked' })).toBeNull()
    }
    expect(goTargetFor({ type: 'guided_unpack', actionUrl: '/fulfill/o1', order_id: 'o1' })).toBe('/fulfill?guidedUnpack=o1')
    expect(goTargetFor({ type: 'exchange_unmapped_state', actionUrl: '/fulfill/o2' })).toBe('/orders?order=o2')
    expect(goTargetFor({ type: 'lost', actionUrl: '/orders/o3' })).toBe('/orders?order=o3')
    expect(goTargetFor({ type: 'missing_awb', actionUrl: '/shipments/s1', order_id: 'o4' })).toBe('/orders?order=o4')
    expect(goTargetFor({ type: 'warehouse_address_push_failed', actionUrl: '/onboarding' })).toBe('/settings?tab=locations')
    expect(goTargetFor({ type: 'return_to_receive', actionUrl: '/receiving' })).toBe('/receiving')
    expect(goTargetFor({ type: 'x', actionUrl: '' })).toBeNull()
  })

  test('the Alerts page: Go → opens the mapped page; no target, no button', async () => {
    const user = userEvent.setup()
    stubFetchWithShellDefaults((url: string) => json(url.includes('/exceptions') ? { total: 2, page: 0, size: 50, items: [
      { type: 'lost', severity: 'HIGH', subject_key: 'lost:x', subject_type: 'piece', ageSeconds: 60, order_id: 'o9',
        descriptionEn: 'lost one', descriptionAr: 'و', suggestedAction: 'x', actionUrl: '/orders/o9' },
      { type: 'bosta_discovery_failed', severity: 'HIGH', subject_key: 'b:x', subject_type: 'shipment', ageSeconds: 60,
        descriptionEn: 'fetch failed', descriptionAr: 'و', suggestedAction: 'x', actionUrl: '/shipments/unlinked' },
    ] } : {}))
    render(<StationProvider><MemoryRouter initialEntries={['/exceptions']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
      <Routes><Route path="/exceptions" element={<ExceptionsPage />} /><Route path="*" element={<Where />} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    await screen.findByText('fetch failed')
    expect(screen.getAllByRole('button', { name: 'Go →' })).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Go →' }))
    expect(await screen.findByTestId('where')).toHaveTextContent('/orders?order=o9')
  })

  test('/fulfill?guidedUnpack=<id> opens that order on Pick & Pack, in waybill mode too', async () => {
    stubFetchWithShellDefaults((url: string) => {
      calls.push(url)
      if (url.endsWith('/fulfill/mode')) return json({ mode: 'waybill_scan' })
      if (url.includes('/fulfill/queue')) return json([])
      return json({})
    })
    render(<StationProvider><MemoryRouter initialEntries={['/fulfill?guidedUnpack=o7']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
      <Routes><Route path="/fulfill" element={<FulfillRoute />} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    // The order's own screen loads it (the waybill page would have asked for its batches instead).
    await waitFor(() => expect(calls.some(u => u.endsWith('/api/v1/fulfill/o7'))).toBe(true))
  })
})

describe('f3b the Overview widget', () => {
  test('every alert destination is the order drawer or a real route', () => {
    const dead: string[] = []
    for (const s of sampleTargets()) {
      for (const order of [ID, undefined]) {
        const d = alertDestination({ type: s.type, actionUrl: s.url, order_id: order })
        if (d.kind === 'order') { if (!d.orderId) dead.push(`${s.type}: empty order`) }
        else if (!isRoute(d.url)) dead.push(`${s.type}: ${s.url} → ${d.url}`)
      }
    }
    expect(dead).toEqual([])
  })

  test('its three alert types: order → drawer; nothing to open → the Alerts page filtered to the type', () => {
    expect(alertDestination({ type: 'ndr_failed', actionUrl: `/orders/${ID}` })).toEqual({ kind: 'order', orderId: ID })
    expect(alertDestination({ type: 'stuck_shipment', actionUrl: '/orders' })).toEqual({ kind: 'url', url: '/orders' })
    expect(alertDestination({ type: 'unmatched_delivery', actionUrl: '/shipments/unlinked', order_id: 'o5' }))
      .toEqual({ kind: 'order', orderId: 'o5' })
    expect(alertDestination({ type: 'unmatched_delivery', actionUrl: '/shipments/unlinked' }))
      .toEqual({ kind: 'url', url: '/exceptions?type=unmatched_delivery' })
    expect(alertDestination({ type: 'guided_unpack', actionUrl: '/fulfill/o6', order_id: 'o6' }))
      .toEqual({ kind: 'url', url: '/fulfill?guidedUnpack=o6' })
  })
})

describe('f4 the Not traced hint', () => {
  test('says no piece was scanned out (EN + AR)', () => {
    const hint = en.orders.filters.hints.traced
    expect(hint).toMatch(/^Not traced = /)
    expect(hint).toContain('without any of its pieces being scanned')
    expect(hint).not.toMatch(/every piece/i)
    expect(ar.orders.filters.hints.traced).toMatch(/^غير متتبَّع = /)
    expect(ar.orders.filters.hints.traced).toContain('دون مسح أي من قطعه')
  })
})
