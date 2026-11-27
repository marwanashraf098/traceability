import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarAddPieces from '../pages/bazar/BazarAddPieces'
import BazarScanOut from '../pages/bazar/BazarScanOut'
import TransferScanOut from '../pages/TransferScanOut'
import { LiveView } from '../pages/bazar/r3/LiveView'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'
import type { BazarEventDetail } from '../bazar/api'
import { LIBRARY } from '../help/content'
import { SCREEN_ARTICLES } from '../help/helpRegistry'

// Help center step (c) — the "Read more" links added after top-up merged: the bazar scan-out → send by count, a top-up
// batch → bazar-top-up, a plain transfer's scan-out → nothing; the Live page → selling from the computer (live only),
// never for a worker; and the bazar-top-up article itself. (Fixtures copied from bazarTopup.test.tsx — not edited.)

vi.mock('../pages/scanpair/CameraReader', () => ({ default: () => null }))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TID = 't-1'
const BATCH = 'b-1'

function fakeJwt(role: 'owner' | 'manager' | 'worker', sub = `u-${role}`): string {
  return `h.${btoa(JSON.stringify({ role, sub }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = (url: string, init: RequestInit | undefined) => Promise<Response> | undefined
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    for (const h of hs) {
      const r = h(url, init)
      if (r) return r
    }
    return json(404, {})
  }))
}

const on = (method: string, end: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.split('?')[0].endsWith(end) ? fn(init) : undefined)

const posts = (end: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(end))

function batch(lines: { variant_id: string; count: number; codes?: string[] }[] = []) {
  return {
    id: BATCH, batch_no: 2, status: 'open',
    lines: lines.map(l => ({ variant_id: l.variant_id, sku: 'SH-' + l.variant_id, variant_title: 'White / L',
      product_title: 'Linen shirt', count: l.count, codes: l.codes ?? [] })),
    total: lines.reduce((n, l) => n + l.count, 0),
  }
}

function transfer() {
  return {
    id: TID, transfer_type: 'bazar', transfer_mode: 'round_trip', status: 'sent', note: null,
    expected_return_at: null, created_by: 'u-owner', created_at: new Date().toISOString(), closed_by: null,
    closed_at: null, sent_at: new Date().toISOString(), sent_by: 'u-owner', cancelled_at: null, cancelled_by: null,
    reconcile_started_at: null, reconcile_started_by: null, destination_location_id: 'd-1',
    destination_location_name: 'Zamalek Hall', source_location_id: null, source_location_name: null,
    lines: [{ id: 'l-0', variant_id: 'v-first', sku: 'X', variant_title: 'Black / S', product_title: 'First send',
      qty_out: 7, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0 }],
  }
}

const STOCK = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', categories: [],
  products: [{ number: null, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: null,
    left: 5, price_min: '960.00', price_max: '960.00', sizes: [
      { variant_id: 'v-l', title: 'White / L', size: 'L', price: '960.00', left: 5, left_unlabelled: 2, committed: 0 },
    ] }],
}

/** The add-pieces screen's reads; {@code current} is what GET …/batches/current answers (changes as the list does). */
function screenBase(state: { current: ReturnType<typeof batch> | null }, onlineStock: 'remove' | 'leave' = 'remove'): Handler[] {
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })),
    on('GET', `/bazar-events/${EVENT}/send-stock`, () => json(200, STOCK)),
    on('GET', `/bazar-events/${EVENT}/batches/current`, () => json(200, { batch: state.current })),
    on('POST', `/bazar-events/${EVENT}/batches`, () => json(200, state.current ?? batch())),
    on('GET', `/bazar-events/${EVENT}`, () => json(200, { id: EVENT, transfer_id: TID, status: 'live', online_stock: onlineStock })),
    on('GET', `/transfers/${TID}`, () => json(200, transfer())),
  ]
}

function renderAt(path: string, routes: React.ReactNode) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes>{routes}</Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

const routes = <>
  <Route path="/bazar/:id/add-pieces" element={<BazarAddPieces />} />
  <Route path="/bazar/:id" element={<p data-testid="event-page">event page</p>} />
</>

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  testI18n.changeLanguage('en')
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  setAccessToken(fakeJwt('owner'))
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

function liveEvent(over: Partial<BazarEventDetail> = {}): BazarEventDetail {
  return {
    id: EVENT, name: 'Zamalek Winter Bazar', starts_on: '2026-11-26', ends_on: '2026-11-28', status: 'live',
    location_id: 'd-1', location_name: 'Zamalek Hall', transfer_id: TID, transfer_status: 'sent',
    transfer_sent_at: '2026-11-26T08:00:00Z', transfer_shopify_sync_mode: 'remove', online_stock: 'remove',
    payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null,
    started_at: '2026-11-26T09:00:00Z', ended_at: null,
    counts: { sent: 12, at_bazar: 10, sold: 2, back: 0, lost: 0 },
    today: { business_day: '2026-11-27', sales: 0, revenue: '0', by_method: {} },
    batches: [{ batch_no: 1, sent_at: '2026-11-26T08:00:00Z', pieces: 9 },
              { batch_no: 2, sent_at: '2026-11-27T12:30:00Z', pieces: 3 }],
    open_batch: null, no_price_count: 0, ...over,
  } as unknown as BazarEventDetail
}

function renderLive(ev: BazarEventDetail, path = `/bazar/${EVENT}`) {
  install(on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })))
  return renderAt(path, <>
    <Route path="/bazar/:id" element={<LiveView ev={ev} reload={async () => {}} />} />
    <Route path="/bazar/:id/add-pieces" element={<p data-testid="add-pieces-page">add</p>} />
  </>)
}


const allRoutes = <>
  <Route path="/bazar/:id/add-pieces" element={<BazarAddPieces />} />
  <Route path="/bazar/:id/scan-out" element={<BazarScanOut />} />
  <Route path="/transfers/:id/scan-out" element={<TransferScanOut />} />
  <Route path="/bazar/:id" element={<p data-testid="event-page">event page</p>} />
</>

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  testI18n.changeLanguage('en')
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  setAccessToken(fakeJwt('owner'))
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('c1 scan-out screens', () => {
  test('a top-up batch links to "Add pieces to a live bazar"', async () => {
    install(...screenBase({ current: batch() }))
    renderAt(`/bazar/${EVENT}/add-pieces`, allRoutes)
    await screen.findByTestId('batch-title')
    const link = screen.getByTestId('help-link-bazarTopUp')
    expect(link).toHaveAttribute('href', '/help/bazar-top-up')
    expect(link).toHaveAttribute('target', '_blank')
    expect(screen.queryByTestId('help-link-bazarScanOut')).toBeNull()
  })

  test('the bazar\'s first send links to "Send pieces by count"', async () => {
    install(on('GET', `/transfers/${TID}`, () => json(200, { ...transfer(), status: 'preparing', sent_at: null })),
      ...screenBase({ current: null }))
    renderAt(`/bazar/${EVENT}/scan-out`, allRoutes)
    const link = await screen.findByTestId('help-link-bazarScanOut')
    expect(link).toHaveAttribute('href', '/help/bazar-send-by-count')
    expect(screen.queryByTestId('help-link-bazarTopUp')).toBeNull()
  })

  test('a plain transfer\'s scan-out has no bazar link', async () => {
    install(on('GET', `/transfers/${TID}`, () => json(200, { ...transfer(), transfer_type: 'stock', status: 'preparing', sent_at: null })),
      ...screenBase({ current: null }))
    renderAt(`/transfers/${TID}/scan-out`, allRoutes)
    await screen.findByPlaceholderText('Scan piece barcode…')
    expect(screen.queryByTestId('help-link-bazarScanOut')).toBeNull()
    expect(screen.queryByTestId('help-link-bazarTopUp')).toBeNull()
  })

  test('a worker sees no link on the batch screen', async () => {
    setAccessToken(fakeJwt('worker'))
    install(...screenBase({ current: batch() }))
    renderAt(`/bazar/${EVENT}/add-pieces`, allRoutes)
    await screen.findByTestId('batch-title')
    expect(screen.queryByTestId('help-link-bazarTopUp')).toBeNull()
  })
})

describe('c2 Live page', () => {
  function renderLive(ev: BazarEventDetail) {
    install(on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })))
    return renderAt(`/bazar/${EVENT}`, <Route path="/bazar/:id" element={<LiveView ev={ev} reload={async () => {}} />} />)
  }

  test('live → "Read more" on selling from the computer', async () => {
    renderLive(liveEvent())
    const link = await screen.findByTestId('help-link-bazarLive')
    expect(link).toHaveAttribute('href', '/help/bazar-sell-laptop')
  })

  test('ended → no link', async () => {
    renderLive(liveEvent({ status: 'ended', ended_at: '2026-11-28T18:00:00Z' } as Partial<BazarEventDetail>))
    await screen.findByTestId('bazar-live')
    expect(screen.queryByTestId('help-link-bazarLive')).toBeNull()
  })
})

describe('c3 the new links and the bazar-top-up article', () => {
  test('every step (c) screen points at its article', () => {
    expect(SCREEN_ARTICLES).toMatchObject({
      bazarGetReady: 'bazar-get-ready', bazarLive: 'bazar-sell-laptop', bazarPrices: 'bazar-prices',
      bazarScanOut: 'bazar-send-by-count', bazarTopUp: 'bazar-top-up', bazarEndEvent: 'bazar-end-event',
      bazarReport: 'bazar-report',
    })
  })

  test.each(['en', 'ar'] as const)('bazar-top-up (%s): owners and managers, in the Bazar topic, linked from get ready', lng => {
    const a = LIBRARY[lng]['bazar-top-up']
    expect(a.topic).toBe('bazar')
    expect(a.roles).toEqual(['owner', 'manager'])
    expect(a.hidden).toBe(false)
    expect(LIBRARY[lng]['bazar-get-ready'].rest).toContain('](/help/bazar-top-up)')
  })

  test('the Keep line is on the online stock article (EN and AR)', () => {
    expect(LIBRARY.en['bazar-online-stock'].rest)
      .toContain('If Keep is greyed out for your store, pieces are removed from online stock instead.')
    expect(LIBRARY.ar['bazar-online-stock'].rest).toContain('تُزال القطع من المخزون الأونلاين بدلًا من ذلك')
  })
})
