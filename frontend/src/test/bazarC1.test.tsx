import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { act, screen, render, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarSell from '../pages/bazar/phone/BazarSell'
import { CodeEntry } from '../pages/bazar/phone/ReturnParts'
import { R4CodeEntry } from '../pages/bazar/r4/parts'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'
import { cameraGateTiming } from '../bazar/cameraGate'

// Bazar C1 — one aim of the camera sells (adds, returns, scans back) at most one piece; the camera box is hidden on a
// computer, and the choice is remembered per device. The camera is a fake that feeds whole frames (every code it
// "sees", [] = an empty view) through CameraReader's onFrame — exactly what the real reader does with onFrame.

const cam = vi.hoisted(() => ({ frame: null as null | ((codes: string[]) => void) }))
vi.mock('../pages/scanpair/CameraReader', () => ({
  default: ({ onFrame }: { onFrame?: (codes: string[]) => void }) => {
    cam.frame = onFrame ?? null
    return <div data-testid="fake-camera" />
  },
}))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TODAY = '2026-11-20'
const SETTLE = 40
const QUIET = 80
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

function fakeJwt(role: string): string {
  return `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`
}
function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}
const LIVE = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
               paymentMethods: ['cash', 'card', 'instapay'] }

let n = 0
function sale(code: string) {
  n += 1
  return {
    id: `s-${n}`, sale_number: `B-${String(n).padStart(4, '0')}`, sold_at: new Date().toISOString(),
    business_day: TODAY, status: 'active', price: '960.00', price_source: 'bulk', method: 'cash',
    piece_id: `p-${n}`, piece_code: code, variant_id: 'v-1', product_title: 'Linen shirt',
    variant_title: 'White / M', seller_id: 'u-worker', seller_name: 'Omar', customer_name: null,
    customer_phone: null, undone_at: null, replayed: false,
  }
}
function previewOf(codes: string[]) {
  const lines = codes.map((code, index) => ({ index, code, piece_id: `pc-${code}`, piece_code: code,
    variant_id: `v-${code}`, product_title: 'Linen shirt', variant_title: 'M', price: '500.00', error: null }))
  return { lines, subtotal: (lines.length * 500).toFixed(2), ok: lines.length > 0 }
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
    for (const h of hs) { const r = h(url, init); if (r) return r }
    return json(404, {})
  }))
}
const on = (method: string, frag: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.includes(frag) ? fn(init) : undefined)
const sells = () => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/sell`))
const soldCodes = () => sells().map(c => (c.body as { code: string }).code)

function base(): Handler[] {
  setAccessToken(fakeJwt('worker'))
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'worker', bazar: true, bazarLive: LIVE })),
    on('GET', `/bazar-events/${EVENT}/sales`, () => json(200, { items: [], next_cursor: null })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, {
      business_day: TODAY, total: '0.00', sale_count: 0, by_method: { cash: '0.00' }, per_seller: [] })),
    on('POST', '/cart/preview', init => json(200, previewOf((JSON.parse(String(init?.body)) as { codes: string[] }).codes))),
  ]
}
const sellOk = on('POST', '/sell', init => json(200, sale((JSON.parse(String(init?.body)) as { code: string }).code)))

function wrap(ui: React.ReactNode) {
  return (
    <StationProvider>
      <MemoryRouter initialEntries={[`/bazar/${EVENT}/sell`]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes><Route path="/bazar/:id/sell" element={ui} /></Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>
  )
}
async function renderSell() {
  const r = render(wrap(<BazarSell />))
  await screen.findByTestId('fake-camera')
  return r
}

/** One camera frame; then let the settle window end. */
async function frame(codes: string[], settle = true) {
  act(() => { cam.frame?.(codes) })
  if (settle) await act(() => sleep(SETTLE + 20))
}
/** An empty view long enough to count (≥ 3 attempts over ≥ QUIET ms). */
async function clearView() {
  for (let i = 0; i < 5; i++) {
    act(() => { cam.frame?.([]) })
    await act(() => sleep(QUIET / 3 + 5))
  }
}

function stubComputer(computer: boolean) {
  vi.stubGlobal('matchMedia', (q: string) => ({
    matches: q === '(pointer: fine)' ? computer : q === '(any-pointer: coarse)' ? !computer : false,
    media: q, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
  }))
  Object.defineProperty(navigator, 'maxTouchPoints', { value: computer ? 0 : 5, configurable: true })
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  cam.frame = null
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  sellTiming.undoCardMs = 8_000
  cameraGateTiming.settleMs = SETTLE
  cameraGateTiming.quietMs = QUIET
  cameraGateTiming.quietFrames = 3
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'maxTouchPoints', { value: 0, configurable: true })
  cleanup()
  cameraGateTiming.settleMs = 250
  cameraGateTiming.quietMs = 600
})

describe('Quick sale — one aim, one sale', () => {
  test('two labels in one frame → nothing sold, "Two labels in view"', async () => {
    install(...base(), sellOk)
    await renderSell()
    await frame(['P0000055', 'P0000056'])
    expect(await screen.findByTestId('camera-two-labels')).toHaveTextContent('Two labels in view — scan one at a time')
    await sleep(50)
    expect(sells()).toHaveLength(0)
  })

  test('two labels in one burst (A then B inside the settle window) → nothing sold', async () => {
    install(...base(), sellOk)
    await renderSell()
    await frame(['P0000056'], false)
    await frame(['P0000055'])
    expect(await screen.findByTestId('camera-two-labels')).toBeInTheDocument()
    await sleep(50)
    expect(sells()).toHaveLength(0)
  })

  test('the prod case: A sold, B in view right after → B is NOT sold until the view is clear', async () => {
    install(...base(), sellOk)
    await renderSell()
    for (let i = 0; i < 6; i++) await frame(['P0000056'], false)
    await act(() => sleep(SETTLE + 20))
    await screen.findByTestId('sell-card-sold')
    expect(soldCodes()).toEqual(['P0000056'])
    for (let i = 0; i < 6; i++) await frame(['P0000055'])
    expect(screen.getByTestId('camera-paused')).toHaveTextContent('Move the camera off the label')
    expect(soldCodes()).toEqual(['P0000056'])
    await clearView()
    expect(screen.queryByTestId('camera-paused')).toBeNull()
    await frame(['P0000055'])
    await waitFor(() => expect(soldCodes()).toEqual(['P0000056', 'P0000055']))
  })

  test('an empty view while the sale is still sending does not count — only after the SOLD card', async () => {
    let release: () => void = () => {}
    install(...base(), on('POST', '/sell', () =>
      new Promise<Response>(res => { release = () => res(json(200, sale('P0000001')) as unknown as Response) })))
    await renderSell()
    await frame(['P0000001'])
    await screen.findByTestId('sell-card-sending')
    await clearView()                                  // before the card: ignored
    act(() => release())
    await screen.findByTestId('sell-card-sold')
    await frame(['P0000002'])
    expect(sells()).toHaveLength(1)
    expect(screen.getByTestId('camera-paused')).toBeInTheDocument()
  })

  test('"Scan next" ends the pause by hand', async () => {
    install(...base(), sellOk)
    const user = userEvent.setup()
    await renderSell()
    await frame(['P0000001'])
    await screen.findByTestId('sell-card-sold')
    await frame(['P0000002'])
    expect(sells()).toHaveLength(1)
    await user.click(screen.getByTestId('camera-scan-next'))
    await frame(['P0000002'])
    await waitFor(() => expect(soldCodes()).toEqual(['P0000001', 'P0000002']))
  })

  test('network failure: "Checking…" keeps the camera closed; once the server answers, a clear view un-pauses (no Scan next)', async () => {
    let attempts = 0
    install(...base(), on('POST', '/sell', init => {
      attempts += 1
      if (attempts === 1) return Promise.reject(new TypeError('Failed to fetch'))
      return json(200, sale((JSON.parse(String(init?.body)) as { code: string }).code))
    }))
    await renderSell()
    await frame(['P0000001'])
    await screen.findByTestId('sell-card-checking')
    await frame(['P0000002'])                       // while checking: ignored, never queued
    await screen.findByTestId('sell-card-sold')
    const ids = sells().map(c => (c.body as { client_request_id: string }).client_request_id)
    expect(ids).toHaveLength(2)
    expect(ids[0]).toBe(ids[1])                     // the same request, asked again — one sale
    await frame(['P0000002'])
    expect(sells()).toHaveLength(2)                 // paused after the card…
    await clearView()                               // …until the view is clear — no "Scan next" tap
    await frame(['P0000002'])
    await waitFor(() => expect(soldCodes()).toEqual(['P0000001', 'P0000001', 'P0000002']))
  })

  test('the error card pauses too; a clear view after it lets the next label sell', async () => {
    let first = true
    install(...base(), on('POST', '/sell', init => {
      if (first) {
        first = false
        return json(409, { code: 'BAZAR_PIECE_NOT_HERE', message_en: 'x', message_ar: 'x' })
      }
      return json(200, sale((JSON.parse(String(init?.body)) as { code: string }).code))
    }))
    await renderSell()
    await frame(['P0000009'])
    await screen.findByTestId('sell-card-error')
    await frame(['P0000002'])
    expect(sells()).toHaveLength(1)
    expect(screen.getByTestId('sell-card-error')).toBeInTheDocument()
    await clearView()
    await frame(['P0000002'])
    await waitFor(() => expect(soldCodes()).toEqual(['P0000009', 'P0000002']))
  })

  test('a camera read while a scanner sale is in flight is ignored (never queued)', async () => {
    let release: () => void = () => {}
    install(...base(), on('POST', '/sell', () =>
      new Promise<Response>(res => { release = () => res(json(200, sale('P0000001')) as unknown as Response) })))
    const user = userEvent.setup()
    await renderSell()
    await user.type(screen.getByTestId('bazar-scan-input'), 'P0000001{Enter}')
    await screen.findByTestId('sell-card-sending')
    await frame(['P0000777'])
    act(() => release())
    await screen.findByTestId('sell-card-sold')
    await sleep(SETTLE + 50)
    expect(soldCodes()).toEqual(['P0000001'])
  })
})

describe('Cart — several pieces, but one per aim', () => {
  async function cartLines() {
    return screen.queryAllByTestId('cart-line').length
  }
  test('the same code over many frames → one line; a second label needs a clear view', async () => {
    install(...base())
    const user = userEvent.setup()
    await renderSell()
    await user.click(await screen.findByRole('radio', { name: 'Cart' }))
    for (let i = 0; i < 6; i++) await frame(['P0000001'])
    await waitFor(async () => expect(await cartLines()).toBe(1))
    await frame(['P0000002'])
    await sleep(30)
    expect(await cartLines()).toBe(1)
    expect(screen.getByTestId('camera-paused')).toBeInTheDocument()
    await clearView()
    await frame(['P0000002'])
    await waitFor(async () => expect(await cartLines()).toBe(2))
  })

  test('two labels in one frame → nothing added', async () => {
    install(...base())
    const user = userEvent.setup()
    await renderSell()
    await user.click(await screen.findByRole('radio', { name: 'Cart' }))
    await frame(['P0000001', 'P0000002'])
    expect(await screen.findByTestId('camera-two-labels')).toBeInTheDocument()
    expect(await cartLines()).toBe(0)
  })
})

function renderEntry(ui: React.ReactNode) {
  return render(<I18nextProvider i18n={testI18n}>{ui}</I18nextProvider>)
}

describe('Return / Exchange (R4CodeEntry) — the same pause', () => {
  test('A → onCode once; B without a clear view → nothing; after a clear view → B', async () => {
    const onCode = vi.fn()
    renderEntry(<R4CodeEntry onCode={onCode} hint="Scan the piece" />)
    await screen.findByTestId('fake-camera')
    await frame(['P0000001'])
    await frame(['P0000001'])
    await frame(['P0000002'])
    expect(onCode.mock.calls).toEqual([['P0000001']])
    await clearView()
    await frame(['P0000002'])
    expect(onCode.mock.calls).toEqual([['P0000001'], ['P0000002']])
  })

  test('two labels in one frame → nothing', async () => {
    const onCode = vi.fn()
    renderEntry(<R4CodeEntry onCode={onCode} hint="Scan the piece" />)
    await screen.findByTestId('fake-camera')
    await frame(['P0000001', 'P0000002'])
    expect(onCode).not.toHaveBeenCalled()
    expect(screen.getByTestId('camera-two-labels')).toBeInTheDocument()
  })
})

describe('Leftovers scan-back (CodeEntry, pile) — label after label, no empty-view wait', () => {
  test('[A], [B], [C] in a row with no empty frames → three scan-backs; A again is ignored', async () => {
    const onCode = vi.fn()
    renderEntry(<CodeEntry onCode={onCode} hint="Scan what came back" gateMode="pile" />)
    await screen.findByTestId('fake-camera')
    await frame(['P0000001'])
    await frame(['P0000001'])
    await frame(['P0000002'])
    await frame(['P0000003'])
    await frame(['P0000003'])
    expect(onCode.mock.calls).toEqual([['P0000001'], ['P0000002'], ['P0000003']])
    expect(screen.queryByTestId('camera-paused')).toBeNull()
  })

  test('[A, B] in one frame → none, "Two labels in view"', async () => {
    const onCode = vi.fn()
    renderEntry(<CodeEntry onCode={onCode} hint="Scan what came back" gateMode="pile" />)
    await screen.findByTestId('fake-camera')
    await frame(['P0000001', 'P0000002'])
    expect(onCode).not.toHaveBeenCalled()
    expect(screen.getByTestId('camera-two-labels')).toBeInTheDocument()
  })

  test('the Leftovers panel uses the pile rule', () => {
    const src = readFileSync(resolve(__dirname, '..', 'pages/bazar/Leftovers.tsx'), 'utf8')
    expect(src).toMatch(/<CodeEntry [^>]*gateMode="pile"/)
  })

  test('noCamera (End event on a computer): no camera box and no "Use camera"', () => {
    renderEntry(<CodeEntry onCode={vi.fn()} hint="x" gateMode="pile" noCamera />)
    expect(screen.queryByTestId('bazar-camera-box')).toBeNull()
    expect(screen.queryByTestId('use-camera')).toBeNull()
  })
})

describe('Camera on a computer / a phone — remembered per device', () => {
  test('computer: no camera box ("Camera blocked…" never shown), a small "Use camera"; the choice sticks', async () => {
    stubComputer(true)
    install(...base(), sellOk)
    const user = userEvent.setup()
    const r = render(wrap(<BazarSell />))
    expect(await screen.findByTestId('use-camera')).toHaveTextContent('Use camera')
    expect(screen.queryByTestId('bazar-camera-box')).toBeNull()
    expect(screen.queryByTestId('fake-camera')).toBeNull()
    expect(screen.getByTestId('bazar-scan-input')).toBeInTheDocument()
    await user.click(screen.getByTestId('use-camera'))
    expect(screen.getByTestId('bazar-camera-box')).toBeInTheDocument()
    expect(localStorage.getItem('traced.bazar.camera')).toBe('shown')
    r.unmount()
    render(wrap(<BazarSell />))
    expect(await screen.findByTestId('bazar-camera-box')).toBeInTheDocument()
  })

  test('phone: camera shown; "Hide camera" hides it on every bazar screen of this device', async () => {
    stubComputer(false)
    install(...base(), sellOk)
    const user = userEvent.setup()
    const r = render(wrap(<BazarSell />))
    await screen.findByTestId('bazar-camera-box')
    await user.click(screen.getByRole('button', { name: 'Hide camera' }))
    expect(screen.queryByTestId('bazar-camera-box')).toBeNull()
    expect(screen.getByTestId('use-camera')).toBeInTheDocument()
    expect(localStorage.getItem('traced.bazar.camera')).toBe('hidden')
    r.unmount()
    renderEntry(<R4CodeEntry onCode={vi.fn()} hint="x" />)
    expect(screen.queryByTestId('bazar-camera-box')).toBeNull()
    expect(screen.getByTestId('use-camera')).toBeInTheDocument()
  })

  test('storage unavailable: the device default still applies, the toggle still works', async () => {
    stubComputer(true)
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    try {
      const user = userEvent.setup()
      renderEntry(<R4CodeEntry onCode={vi.fn()} hint="x" />)
      expect(screen.queryByTestId('bazar-camera-box')).toBeNull()
      await user.click(screen.getByTestId('use-camera'))
      expect(screen.getByTestId('bazar-camera-box')).toBeInTheDocument()
    } finally {
      get.mockRestore()
      set.mockRestore()
    }
  })
})
