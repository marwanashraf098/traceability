import { afterEach, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { PageTour, TourHelpButton } from '../tours/TourCard'
import { forgetTours } from '../tours/tourState'
import { TOURS, type TourId } from '../tours/tours'
import { helpFor, HELP_REGISTRY } from '../help/helpRegistry'
import PickupSessions from '../pages/PickupSessions'
import Returns from '../pages/Returns'
import StockTake from '../pages/StockTake'
import StockTakeScan from '../pages/StockTakeScan'
import TransferScanOut from '../pages/TransferScanOut'
import TransferReconcile from '../pages/TransferReconcile'
import FulfillRoute from '../pages/fulfill/FulfillRoute'

/**
 * Onboarding O5 — first-time tours, the "?" and the Pick & Pack chooser.
 *   t1  the card: "Step x of N", Back / Next / Done, Skip on every step; Skip on step 1 AND Done on the last step write
 *       tourDone; "?" replays a finished tour; no input, never focused
 *   t2  auto-start matrix per tour: never-done action × tour done × role. Owners / managers read progress; workers
 *       (and station mode) read /me hasEverClosedPickup / hasEverScannedReturn and never ask progress; workers never
 *       see stock_take / transfers, not even "?"
 *   t2b phone: while the sheet is open the scroll container gets bottom padding = the sheet's height, restored after
 *   t3  focus safety on every scan screen (Pickups, Scan returns, Stock take, Transfer send-out, Transfer reconcile):
 *       the scan input keeps focus when the tour mounts, after Next, after Skip and after "?" (real userEvent)
 *   t4  station mode: tourDone goes out with the switched-in worker's token (the server keys it on the token's user)
 *   t5  the chooser: shown only when owner + not station + order_queue + not chosen + never packed; Use → PUT mode then
 *       PATCH pickPackChosen; Skip → PATCH only; managers / workers / station never; Recommended only with Bosta;
 *       "?" replays it (read-only unless owner)
 *   t6  helpRegistry; AR / RTL copy
 */

vi.mock('../pages/Fulfill', () => ({
  default: () => (
    <div data-testid="fulfill-page">
      <TourHelpButton tourId="pick_pack_mode" />
    </div>
  ),
}))
vi.mock('../pages/fulfill/WaybillPackPage', () => ({ default: () => <div data-testid="waybill-page" /> }))

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({ lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false } })
  return inst
}

function token(role: string, sub = 'u-' + role) {
  return 'h.' + btoa(JSON.stringify({ sub, role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=+$/, '') + '.s'
}

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status >= 200 && status < 300, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : k.toLowerCase() === 'content-length' ? '10' : null) },
  json: async () => structuredClone(data), text: async () => JSON.stringify(data),
})

type Call = { method: string; url: string; body?: Record<string, unknown>; auth?: string }
let calls: Call[]
let state: { tried: Record<string, boolean>; done: string[]; pickPackChosen: boolean; bosta: boolean; mode: string; role: string }

function progress() {
  return {
    steps: [], card: 'full', cardChosen: false, labelingPace: null, pickPackChosen: state.pickPackChosen, bostaConnected: state.bosta,
    tasks: ['receive', 'pack', 'pickup', 'returns', 'transfer', 'stock_take'].map(key => ({ key, tried: !!state.tried[key] })),
  }
}

const pickupSession = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-11-07', scheduledTimeSlot: null, scannedCount: 0,
  openedByName: 'Ahmed', createdAt: '2026-11-07T08:00:00Z', notes: null, closedByName: null, closedAt: null, scans: [] as unknown[] }
const ST_SESSION = { sessionId: 'st-1', status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: true, openedBy: 'u1',
  openedByName: 'Owner', openedAt: '2026-11-07T07:00:00Z', finalizedBy: null, finalizedByName: null, finalizedAt: null, note: 'Shelf A', shopifySync: null }
const line = { id: 'line-1', variant_id: 'v1', sku: 'TEE-M', variant_title: 'M', product_title: 'Tee', qty_out: 2, qty_returned_good: 0,
  qty_condemned: 0, qty_sold: 0, qty_lost: 0, phone_scans: 0 }
const transfer = (status: string) => ({ id: 'tr-1', transfer_type: 'showroom', transfer_mode: 'round_trip', status, note: null,
  expected_return_at: null, created_by: 'u1', created_at: '2026-11-07T07:00:00Z', closed_by: null, closed_at: null, sent_at: null,
  sent_by: null, cancelled_at: null, cancelled_by: null, reconcile_started_at: null, reconcile_started_by: null,
  destination_location_id: 'loc-2', destination_location_name: 'Zamalek Showroom', source_location_id: null, source_location_name: null,
  lines: [line], outstandingCount: 2, piecesEverCount: 2, phoneScanCount: 0 })

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body && typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  const auth = (opts.headers as Record<string, string> | undefined)?.Authorization
  calls.push({ method, url, body, auth })
  if (url.includes('/me/ui-state')) return json({ toursDone: [...state.done, body?.tourDone].filter(Boolean), pagesSeen: [] })
  if (url.endsWith('/fulfill/mode')) return json({ mode: state.mode })
  if (url.endsWith('/tenant/settings')) return json({})
  if (url.includes('/onboarding/prefs')) return json(progress())
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([pickupSession])
  if (url.endsWith('/pickup-sessions/pk-1')) return json(pickupSession)
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1')) return json({ id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-11-07T08:00:00Z',
    closed_by: null, closed_at: null, note: null, items: [], expectedPieces: [] })
  if (url.endsWith('/stock-takes/sessions/st-1')) return json(ST_SESSION)
  if (url.includes('/stock-takes/sessions')) return json([])
  if (url.endsWith('/transfers/tr-1')) return json(transfer(currentTransfer))
  return json({})
}
let currentTransfer = 'preparing'

function stub() {
  stubFetchWithShellDefaults(vi.fn(backend), {
    me: { name: 'M', role: state.role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true,
      uiState: { toursDone: state.done, pagesSeen: [] },
      hasEverClosedPickup: !!state.tried.pickup, hasEverScannedReturn: !!state.tried.returns },
    onboardingProgress: () => progress(),
  })
  // The shell answers /me and /onboarding/progress itself; count them too.
  const inner = globalThis.fetch as (u: string, o?: RequestInit) => unknown
  vi.stubGlobal('fetch', (url: string, opts: RequestInit = {}) => {
    if (typeof url === 'string' && (url.endsWith('/api/v1/me') || url.includes('/onboarding/progress') || url.includes('/me/ui-state'))) {
      calls.push({ method: (opts.method ?? 'GET').toUpperCase(), url, auth: (opts.headers as Record<string, string> | undefined)?.Authorization,
        body: typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined })
    }
    return inner(url, opts)
  })
}

function renderAt(ui: React.ReactElement, path = '/', pattern = '*', lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider><MemoryRouter initialEntries={[path]}><I18nextProvider i18n={i18nFor(lng)}><ToastProvider>
      <Routes><Route path={pattern} element={ui} /></Routes>
    </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
}

const tourDoneWrites = () => calls.filter(c => c.method === 'POST' && c.url.includes('/me/ui-state')).map(c => c.body?.tourDone)
const settle = () => act(async () => { for (let i = 0; i < 6; i++) await Promise.resolve() })

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', { writable: true,
    value: (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }) })
})

beforeEach(() => {
  calls = []
  currentTransfer = 'preparing'
  state = { tried: {}, done: [], pickPackChosen: false, bosta: false, mode: 'order_queue', role: 'owner' }
  forgetTours(); forgetOnboardingProgress(); resetCapabilitiesCache()
  localStorage.clear()
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken(token('owner'))
  stub()
})

afterEach(() => {
  cleanup(); clearAccessToken(); vi.unstubAllGlobals(); document.documentElement.dir = 'ltr'
})

function Harness({ id }: { id: TourId }) {
  return <><TourHelpButton tourId={id} /><PageTour tourId={id} /></>
}

describe('t1 the card', () => {
  test('steps, Back / Next / Done; Done on the last step writes tourDone; no input, never focused', async () => {
    const user = userEvent.setup()
    renderAt(<Harness id="pickups" />)
    const card = await screen.findByTestId('tour-pickups')
    expect(screen.getByTestId('tour-eyebrow')).toHaveTextContent('Pickups · Step 1 of 4')
    expect(card).toHaveTextContent(en.tours.pickups.s1.title)
    expect(within(card).queryByTestId('tour-back')).toBeNull()
    expect(card.querySelectorAll('input, textarea, select')).toHaveLength(0)
    expect(card.contains(document.activeElement)).toBe(false)
    for (let i = 2; i <= 4; i++) {
      await user.click(screen.getByTestId('tour-next'))
      expect(screen.getByTestId('tour-eyebrow')).toHaveTextContent(`Step ${i} of 4`)
      expect(screen.getByTestId('tour-skip')).toBeInTheDocument()
    }
    await user.click(screen.getByTestId('tour-back'))
    expect(screen.getByTestId('tour-eyebrow')).toHaveTextContent('Step 3 of 4')
    await user.click(screen.getByTestId('tour-next'))
    expect(screen.getByTestId('tour-next')).toHaveTextContent(en.tours.done)
    await user.click(screen.getByTestId('tour-next'))
    expect(screen.queryByTestId('tour-pickups')).toBeNull()
    await waitFor(() => expect(tourDoneWrites()).toEqual(['pickups']))
  })

  test('Skip on step 1 writes tourDone; "?" replays the finished tour', async () => {
    const user = userEvent.setup()
    renderAt(<Harness id="scan_returns" />)
    await screen.findByTestId('tour-scan_returns')
    await user.click(screen.getByTestId('tour-skip'))
    expect(screen.queryByTestId('tour-scan_returns')).toBeNull()
    await waitFor(() => expect(tourDoneWrites()).toEqual(['scan_returns']))
    await user.click(screen.getByTestId('tour-help'))
    expect(await screen.findByTestId('tour-scan_returns')).toHaveAttribute('data-step', '1')
  })

  test('every tour has 4 steps and its copy in EN and AR', () => {
    for (const id of Object.keys(TOURS) as TourId[]) {
      expect(TOURS[id].steps).toHaveLength(4)
      for (const s of TOURS[id].steps) {
        for (const L of [en, ar]) {
          const node = s.key.split('.').reduce((o: Record<string, unknown>, k) => o[k] as Record<string, unknown>, L as never) as Record<string, string>
          expect(node.title, `${s.key}`).toBeTruthy()
          expect(node.body).toBeTruthy()
          if (s.hasTip) expect(node.tip).toBeTruthy()
        }
      }
    }
  })
})

describe('t2 auto-start', () => {
  const TASK: Record<TourId, string> = { pickups: 'pickup', scan_returns: 'returns', stock_take: 'stock_take', transfers: 'transfer' }
  const cases: [TourId, string, boolean, boolean, boolean][] = []
  for (const id of Object.keys(TOURS) as TourId[]) {
    for (const role of ['owner', 'manager', 'worker']) {
      for (const tried of [false, true]) {
        for (const done of [false, true]) {
          const theirs = id === 'pickups' || id === 'scan_returns'
          cases.push([id, role, tried, done, (role !== 'worker' || theirs) && !tried && !done])
        }
      }
    }
  }
  test.each(cases)('%s · %s · tried=%s · tourDone=%s → starts=%s', async (id, role, tried, done, starts) => {
    state.role = role
    state.tried = { [TASK[id]]: tried }
    state.done = done ? [id] : []
    setAccessToken(token(role)); stub()
    renderAt(<Harness id={id} />)
    if (starts) {
      expect(await screen.findByTestId(`tour-${id}`)).toBeInTheDocument()
    } else {
      // A finished tour (or a worker) never needs progress: /me decides.
      if (role !== 'worker' && !done) await waitFor(() => expect(calls.some(c => c.url.includes('/onboarding/progress'))).toBe(true))
      else if (role !== 'worker' || id === 'pickups' || id === 'scan_returns') {
        await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/me'))).toBe(true))
      }
      await settle()
      expect(screen.queryByTestId(`tour-${id}`)).toBeNull()
    }
    // Workers: progress is owner / manager only — never asked; stock take and transfers aren't theirs at all.
    if (role === 'worker') {
      expect(calls.some(c => c.url.includes('/onboarding/progress'))).toBe(false)
      const theirs = id === 'pickups' || id === 'scan_returns'
      expect(!!screen.queryByTestId('tour-help')).toBe(theirs)
    }
  })

  test.each([
    ['pickups', 'pickup'], ['scan_returns', 'returns'],
  ] as const)("a worker's %s tour starts on a never-done store and not on a done one (from /me)", async (id, task) => {
    state.role = 'worker'; setAccessToken(token('worker')); stub()
    renderAt(<Harness id={id} />)
    expect(await screen.findByTestId(`tour-${id}`)).toBeInTheDocument()
    cleanup(); forgetTours(); calls = []
    state.tried = { [task]: true }; stub()
    renderAt(<Harness id={id} />)
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/me'))).toBe(true))
    await settle()
    expect(screen.queryByTestId(`tour-${id}`)).toBeNull()
    expect(calls.some(c => c.url.includes('/onboarding/progress'))).toBe(false)
  })

  test('station mode: an owner on the tablet uses /me too (progress is not asked)', async () => {
    localStorage.setItem('stationMode', 'true')
    renderAt(<Harness id="pickups" />)
    expect(await screen.findByTestId('tour-pickups')).toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/onboarding/progress'))).toBe(false)
    cleanup(); forgetTours(); calls = []
    state.tried = { pickup: true }; stub()
    renderAt(<Harness id="pickups" />)
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/me'))).toBe(true))
    await settle()
    expect(screen.queryByTestId('tour-pickups')).toBeNull()
  })

  test('a worker can take their own tours from "?"', async () => {
    const user = userEvent.setup()
    state.role = 'worker'; setAccessToken(token('worker')); stub()
    renderAt(<Harness id="pickups" />)
    await user.click(screen.getByTestId('tour-help'))
    expect(await screen.findByTestId('tour-pickups')).toBeInTheDocument()
  })
})

// ── t3 focus safety on the scan screens ──────────────────────────────────────

const SCREENS: { name: string; tour: TourId; open: () => Promise<HTMLInputElement> }[] = [
  { name: 'Pickups', tour: 'pickups', open: async () => {
    renderAt(<PickupSessions />)
    await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
    await userEvent.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
    return (await screen.findByPlaceholderText(en.pickups.scanPlaceholder)) as HTMLInputElement
  } },
  { name: 'Scan returns', tour: 'scan_returns', open: async () => {
    renderAt(<Returns />)
    await userEvent.click(await screen.findByTestId('open-session-button'))
    return (await screen.findByTestId('scan-input')) as HTMLInputElement
  } },
  { name: 'Stock take', tour: 'stock_take', open: async () => {
    renderAt(<StockTakeScan />, '/stock-take/st-1/scan', '/stock-take/:id/scan')
    await screen.findByTestId('stocktake-scan')
    return document.querySelector<HTMLInputElement>('input.input-scan')!
  } },
  { name: 'Transfer send-out', tour: 'transfers', open: async () => {
    renderAt(<TransferScanOut />, '/transfers/tr-1/scan-out', '/transfers/:id/scan-out')
    await screen.findByTestId('transfer-scan-out')
    return document.querySelector<HTMLInputElement>('input.input-scan')!
  } },
  { name: 'Transfer reconcile', tour: 'transfers', open: async () => {
    currentTransfer = 'reconciling'
    renderAt(<TransferReconcile />, '/transfers/tr-1/reconcile', '/transfers/:id/reconcile')
    await waitFor(() => expect(document.querySelector('input.input-scan')).toBeTruthy())
    return document.querySelector<HTMLInputElement>('input.input-scan')!
  } },
]

describe('t3 focus safety', () => {
  test.each(SCREENS.map(s => [s.name, s] as const))('%s: the scan input keeps focus (tour mounts, Next, Skip, "?")', async (_n, s) => {
    const user = userEvent.setup()
    const input = await s.open()
    const card = await screen.findByTestId(`tour-${s.tour}`)
    await waitFor(() => expect(input).toHaveFocus())
    expect(card.contains(document.activeElement)).toBe(false)

    await user.click(screen.getByTestId('tour-next'))
    expect(screen.getByTestId('tour-eyebrow')).toHaveTextContent('Step 2 of 4')
    expect(input).toHaveFocus()

    await user.click(screen.getByTestId('tour-skip'))
    expect(screen.queryByTestId(`tour-${s.tour}`)).toBeNull()
    expect(input).toHaveFocus()
    await waitFor(() => expect(tourDoneWrites()).toEqual([s.tour]))

    await user.click(screen.getByTestId('tour-help'))
    expect(await screen.findByTestId(`tour-${s.tour}`)).toBeInTheDocument()
    expect(input).toHaveFocus()
  })
})

describe('t4 station mode', () => {
  test("tourDone goes out with the switched-in worker's token", async () => {
    const user = userEvent.setup()
    localStorage.setItem('stationMode', 'true')
    const workerToken = token('worker', 'u-worker-7')
    state.role = 'worker'; setAccessToken(workerToken); stub()
    renderAt(<Harness id="scan_returns" />)
    await user.click(screen.getByTestId('tour-help'))
    await user.click(await screen.findByTestId('tour-skip'))
    await waitFor(() => expect(tourDoneWrites()).toEqual(['scan_returns']))
    const post = calls.find(c => c.method === 'POST' && c.url.includes('/me/ui-state'))!
    expect(post.auth).toBe(`Bearer ${workerToken}`)
  })

  test('workers never see the stock take or transfers tour (the list page has no "?" for them)', async () => {
    state.role = 'worker'; setAccessToken(token('worker')); stub()
    renderAt(<StockTake />)
    await settle(); await settle()
    expect(screen.queryByTestId('tour-help')).toBeNull()
    expect(screen.queryByTestId('tour-stock_take')).toBeNull()
  })
})

describe('t5 Pick & Pack chooser', () => {
  const putMode = () => calls.filter(c => c.method === 'PUT' && c.url.endsWith('/tenant/settings')).map(c => c.body)
  const prefs = () => calls.filter(c => c.method === 'PATCH' && c.url.includes('/onboarding/prefs')).map(c => c.body)

  test('shown when every condition holds; Use Waybill scan → PUT mode then PATCH pickPackChosen', async () => {
    const user = userEvent.setup()
    state.bosta = true
    renderAt(<FulfillRoute />)
    const chooser = await screen.findByTestId('pick-pack-chooser')
    expect(chooser).toHaveTextContent('Settings › Pick & Pack')
    expect(chooser).not.toHaveTextContent('Settings › Packing')
    expect(within(screen.getByTestId('pick-pack-mode-waybill_scan')).getByTestId('pick-pack-recommended')).toHaveTextContent('Recommended for Bosta')
    expect(screen.getByTestId('pick-pack-mode-waybill_scan')).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'Use Waybill scan' }))
    expect(await screen.findByTestId('waybill-page')).toBeInTheDocument()
    expect(putMode()).toEqual([{ pickPackMode: 'waybill_scan' }])
    expect(prefs()).toEqual([{ pickPackChosen: true }])
    const order = calls.filter(c => c.method !== 'GET').map(c => c.method)
    expect(order).toEqual(['PUT', 'PATCH'])
  })

  test('Use Order queue → PUT order_queue then PATCH; no Bosta → no Recommended, queue pre-selected', async () => {
    const user = userEvent.setup()
    renderAt(<FulfillRoute />)
    await screen.findByTestId('pick-pack-chooser')
    expect(screen.queryByTestId('pick-pack-recommended')).toBeNull()
    expect(screen.getByTestId('pick-pack-mode-order_queue')).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'Use Order queue' }))
    expect(await screen.findByTestId('fulfill-page')).toBeInTheDocument()
    expect(putMode()).toEqual([{ pickPackMode: 'order_queue' }])
    expect(prefs()).toEqual([{ pickPackChosen: true }])
  })

  test('Skip → PATCH only, the mode is never written', async () => {
    const user = userEvent.setup()
    renderAt(<FulfillRoute />)
    await screen.findByTestId('pick-pack-chooser')
    await user.click(screen.getByRole('button', { name: en.pickPackChooser.skip }))
    expect(await screen.findByTestId('fulfill-page')).toBeInTheDocument()
    expect(putMode()).toEqual([])
    expect(prefs()).toEqual([{ pickPackChosen: true }])
  })

  test.each([
    ['manager', () => { state.role = 'manager'; setAccessToken(token('manager')) }],
    ['worker', () => { state.role = 'worker'; setAccessToken(token('worker')) }],
    ['station mode', () => { localStorage.setItem('stationMode', 'true') }],
    ['already chosen', () => { state.pickPackChosen = true }],
    ['already packed', () => { state.tried = { pack: true } }],
    ['waybill mode', () => { state.mode = 'waybill_scan' }],
  ])('not shown: %s', async (_n, arrange) => {
    arrange(); stub()
    renderAt(<FulfillRoute />)
    expect(await screen.findByTestId(state.mode === 'waybill_scan' ? 'waybill-page' : 'fulfill-page')).toBeInTheDocument()
    expect(screen.queryByTestId('pick-pack-chooser')).toBeNull()
    expect(calls.filter(c => c.method !== 'GET')).toEqual([])
  })

  test('"?" replays it: the owner can switch; a manager reads it with the owner-only note', async () => {
    const user = userEvent.setup()
    state.pickPackChosen = true
    renderAt(<FulfillRoute />)
    await user.click(await screen.findByTestId('tour-help'))
    const replay = await screen.findByTestId('pick-pack-chooser')
    expect(replay).toHaveAttribute('data-replay', 'true')
    expect(screen.queryByRole('button', { name: en.pickPackChooser.skip })).toBeNull()
    await user.click(screen.getByRole('button', { name: en.pickPackChooser.close }))
    expect(await screen.findByTestId('fulfill-page')).toBeInTheDocument()
    expect(calls.filter(c => c.method !== 'GET')).toEqual([])
    cleanup(); forgetTours()

    state.role = 'manager'; setAccessToken(token('manager')); stub()
    renderAt(<FulfillRoute />)
    await user.click(await screen.findByTestId('tour-help'))
    await screen.findByTestId('pick-pack-chooser')
    expect(screen.getByTestId('pick-pack-owner-only')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Use / })).toBeNull()
  })
})

describe('t6 registry + AR', () => {
  test('helpRegistry maps each page to its tour and its Help article (O6)', () => {
    expect(helpFor('/stock-take/st-1/scan')).toEqual({ tourId: 'stock_take', articleId: 'stock-take' })
    expect(helpFor('/transfers/tr-1/reconcile')?.tourId).toBe('transfers')
    expect(helpFor('/orders')).toBeNull()
    expect(Object.values(HELP_REGISTRY).every(e => !!e.articleId)).toBe(true)
  })

  test('the card reads in Arabic, right to left', async () => {
    document.documentElement.dir = 'rtl'
    renderAt(<Harness id="transfers" />, '/', '*', 'ar')
    const card = await screen.findByTestId('tour-transfers')
    expect(card).toHaveTextContent(ar.tours.transfers.s1.title)
    expect(screen.getByTestId('tour-eyebrow')).toHaveTextContent('عمليات النقل · الخطوة 1 من 4')
    expect(screen.getByTestId('tour-skip')).toHaveTextContent(ar.tours.skip)
    expect(card.className).toContain('end-4')
  })
})

describe('t2b phone padding', () => {
  test('the scroll container gets the sheet height as bottom padding while open, and its own back after', async () => {
    const user = userEvent.setup()
    Object.defineProperty(window, 'matchMedia', { writable: true,
      value: (q: string) => ({ matches: q.includes('max-width: 640px'), media: q, addEventListener: () => {}, removeEventListener: () => {} }) })
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
      { height: 212, width: 390, top: 632, bottom: 844, left: 0, right: 390, x: 0, y: 632, toJSON: () => ({}) } as DOMRect)
    renderAt(<main data-testid="scroller" style={{ overflowY: 'auto', paddingBottom: '24px' }}><Harness id="pickups" /></main>)
    await screen.findByTestId('tour-pickups')
    expect(screen.getByTestId('scroller').style.paddingBottom).toBe('212px')
    await user.click(screen.getByTestId('tour-skip'))
    expect(screen.getByTestId('scroller').style.paddingBottom).toBe('24px')
    rect.mockRestore()
    Object.defineProperty(window, 'matchMedia', { writable: true,
      value: (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }) })
  })
})
