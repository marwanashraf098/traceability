import { afterEach, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import i18next from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { forgetOnboardingProgress } from '../onboardingProgress'
import { forgetPagesSeen } from '../pageSeen'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ReceivingRoute } from '../pages/receiving/GuidedReceiving'
import ReceivingModeCards, { MODE_ORDER } from '../pages/receiving/ReceivingModeCards'
import Receiving from '../pages/Receiving'
import { readGuidedSession } from '../pages/receiving/guidedState'

/**
 * Onboarding O4 — the guided first receiving (Flow-5 / 6 / 7) and the receiving mode rename.
 *   gr1  entry: owner / manager with first_receiving not done → guided; worker, station mode, first_receiving done or
 *        no ?guided → the normal page (no progress request without ?guided); Skip → Overview, nothing written
 *   gr2  the suggestion: product + "most in Shopify", quantity 10, Traced Main before → after; Start = the SAME calls
 *        as normal receiving (POST session {main, mode} → POST line); empty and failure → the picker; GETs only
 *   gr3  modes: Medium pre-selected; High disabled + "Needs a label run · Set up" without an open run, usable with
 *        one (sends the run); Low sends count_only
 *   gr4  the guide per mode: Medium 5 steps, Low 3 (no print / stick / check), High mirrors the factory flow; the panel
 *        has no input
 *   gr5  the label check: READ ONLY (GETs only), counts only THIS session's pieces, normalises the code, repeat =
 *        already checked, unknown = not from this session
 *   gr6  focus: the check input is the only input and keeps focus after clicks on the guide (real userEvent)
 *   gr7  Flow-7: Medium after "I'm done", Low right after finish (no Lookup line); pace → PATCH labelingPace →
 *        Overview; shown once (the guided marker is cleared, the next visit is the normal page)
 *   gr8  mode cards: name, description and meter (1 / 2 / 3) in EN and AR, low → high, Recommended on High; the
 *        normal chooser still sends the internal values; old names gone from both locale files
 *   gr9  AR / RTL Flow-5
 *   gr10 a label PDF that opens in the guided session (the guide's Print or the session's own print buttons) →
 *        PATCH testLabelPrinted true, once; a failed print → no write
 */

function i18nFor(lng: 'en' | 'ar') {
  const inst = i18next.createInstance()
  inst.use(initReactI18next).init({
    lng, fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
  })
  return inst
}

function token(role: string) {
  return 'h.' + btoa(JSON.stringify({ role, tenant: 't', exp: Math.floor(Date.now() / 1000) + 3600 })) + '.s'
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data), blob: async () => new Blob(['pdf']),
  })
}

const LOCATIONS = [{ id: 'loc-show', name: 'Showroom', is_fulfillment: false }, { id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true }]
const PRODUCT = { id: 'p-1', title: 'Linen Shirt', status: 'active', imageUrl: null,
  variants: [{ id: 'v-1', title: 'Black / M', sku: 'LS-BM', price: 500, pieceCounts: {}, committed: 0, available: 0 }] }

function progress(firstDone: boolean) {
  return {
    steps: [
      { key: 'shopify', done: true, skippable: false, skipped: false },
      { key: 'address', done: true, skippable: false, skipped: false },
      { key: 'first_receiving', done: firstDone, skippable: false, skipped: false },
      { key: 'courier', done: false, skippable: true, skipped: false },
      { key: 'test_label', done: false, skippable: true, skipped: false },
    ],
    tasks: [], card: 'full', cardChosen: false, pickPackChosen: false, labelingPace: null,
  }
}

type Call = { url: string; method: string; body: unknown }
let calls: Call[]
let opts: {
  firstDone: boolean
  suggestion: unknown | 'fail'
  runs: { id: string; status: string; factoryName: string; reference: string | null; received: number; expected: number }[]
  session: Record<string, unknown>
  pieces: { id: string; barcode: string; short_code: string | null }[]
}

function session(mode: string, status: 'open' | 'finalized', lines = 1) {
  return {
    id: 'sess-1', status, mode, reference: null, supplier_name: null, location_name: 'Main Warehouse',
    created_at: new Date().toISOString(), finalized_at: status === 'finalized' ? new Date().toISOString() : null,
    line_units: 3 * lines, piece_count: status === 'finalized' ? 3 : 0,
    lines: Array.from({ length: lines }, (_, i) => ({ id: `l-${i}`, variant_id: 'v-1', variant_title: 'Black / M', sku: 'LS-BM',
      product_title: 'Linen Shirt', quantity: 3, piece_count: status === 'finalized' ? 3 : 0 })),
    ...(mode === 'factory' ? { factory: { staged: 0, factoryName: 'Nile', reference: 'R1', lines: [] } } : {}),
  }
}

function backend(url: string, init: RequestInit = {}) {
  const method = init.method ?? 'GET'
  calls.push({ url, method, body: init.body ? JSON.parse(String(init.body)) : undefined })
  if (url.includes('/onboarding/receiving-suggestion')) {
    return opts.suggestion === 'fail' ? json({ message_en: 'x' }, 500) : json({ suggestion: opts.suggestion })
  }
  if (url.includes('/onboarding/prefs')) return json(progress(true))
  if (url.includes('/locations')) return json(LOCATIONS)
  if (url.includes('/label-runs')) return json(opts.runs)
  if (url.includes('/catalog')) return json({ products: url.includes('variantIds') ? [PRODUCT] : [], nextCursor: null })
  if (url.endsWith('/receiving/sessions') && method === 'POST') return json({ sessionId: 'sess-1' }, 201)
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/sess-1/lines') && method === 'POST') return json({ lineId: 'l-0' }, 201)
  if (url.endsWith('/receiving/sessions/sess-1/pieces')) return json(opts.pieces)
  if (url.endsWith('/receiving/sessions/sess-1/labels')) return json({})
  if (url.endsWith('/receiving/sessions/sess-1')) return json(opts.session)
  return json({})
}

function stub(role = 'owner') {
  stubFetchWithShellDefaults(backend as never, {
    me: { name: 'Mona', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true },
    onboardingProgress: () => progress(opts.firstDone),
  })
}

function renderAt(path: string, lng: 'en' | 'ar' = 'en') {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={i18nFor(lng)}>
          <ToastProvider>
            <Routes>
              <Route path="/receiving" element={<ReceivingRoute />} />
              <Route path="/overview" element={<div data-testid="overview-page" />} />
              <Route path="/lookup" element={<div data-testid="lookup-page" />} />
            </Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

const writes = () => calls.filter(c => c.method !== 'GET')
const progressAsked = () => calls.some(c => c.url.includes('/onboarding/progress'))

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (q: string) => ({ matches: false, media: q, addEventListener: () => {}, removeEventListener: () => {} }),
  })
})

beforeEach(() => {
  calls = []
  opts = { firstDone: false, suggestion: { variantId: 'v-1', shopifyQuantity: 42, mainOnHand: 5 }, runs: [],
    session: session('print_stick', 'open'), pieces: [] }
  forgetOnboardingProgress(); forgetPagesSeen(); resetCapabilitiesCache()
  sessionStorage.clear(); localStorage.clear()
  setAccessToken(token('owner'))
  window.open = vi.fn() as never
  URL.createObjectURL = vi.fn(() => 'blob:x')
  stub()
})

afterEach(() => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
})

// The shell's GET /onboarding/progress goes through stubFetchWithShellDefaults (not `backend`); record it too.
function trackProgress() {
  const inner = globalThis.fetch
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (typeof url === 'string' && url.includes('/onboarding/progress')) calls.push({ url, method: 'GET', body: undefined })
    return (inner as (u: string, i?: RequestInit) => unknown)(url, init)
  })
}

describe('gr1 entry', () => {
  test('owner and manager with first_receiving not done get the guided flow', async () => {
    renderAt('/receiving?guided=1')
    expect(await screen.findByTestId('guided-setup')).toBeInTheDocument()
    cleanup(); forgetOnboardingProgress(); resetCapabilitiesCache()
    setAccessToken(token('manager')); stub('manager')
    renderAt('/receiving?guided=1')
    expect(await screen.findByTestId('guided-setup')).toBeInTheDocument()
  })

  test('first_receiving done → the normal page', async () => {
    opts.firstDone = true
    renderAt('/receiving?guided=1')
    expect(await screen.findByText(en.receiving.title, { selector: 'h1' })).toBeInTheDocument()
    expect(screen.queryByTestId('guided-setup')).toBeNull()
  })

  test('worker and station mode → the normal page, progress never asked', async () => {
    setAccessToken(token('worker')); stub('worker'); trackProgress()
    renderAt('/receiving?guided=1')
    expect(await screen.findByText(en.receiving.title, { selector: 'h1' })).toBeInTheDocument()
    expect(screen.queryByTestId('guided-setup')).toBeNull()
    expect(progressAsked()).toBe(false)
    cleanup(); calls = []
    setAccessToken(token('owner')); stub(); trackProgress()
    localStorage.setItem('stationMode', 'true')
    renderAt('/receiving?guided=1')
    expect(await screen.findByText(en.receiving.title, { selector: 'h1' })).toBeInTheDocument()
    expect(screen.queryByTestId('guided-setup')).toBeNull()
    expect(progressAsked()).toBe(false)
  })

  test('no ?guided → the normal page without asking progress', async () => {
    trackProgress()
    renderAt('/receiving')
    expect(await screen.findByText(en.receiving.title, { selector: 'h1' })).toBeInTheDocument()
    // Layout's own chip asks progress; the route itself must not decide on it — the guided screen never shows.
    expect(screen.queryByTestId('guided-setup')).toBeNull()
  })

  test("Skip, I'll do it later → Overview, nothing written", async () => {
    const user = userEvent.setup()
    renderAt('/receiving?guided=1')
    await screen.findByTestId('guided-setup')
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.skip }))
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(writes()).toEqual([])
    expect(readGuidedSession()).toBeNull()
  })
})

describe('gr2 the suggestion', () => {
  test('product, "most in Shopify", quantity 10, Traced Main 5 → 15; Start = the normal receiving calls', async () => {
    const user = userEvent.setup()
    renderAt('/receiving?guided=1')
    const card = await screen.findByTestId('guided-suggestion')
    expect(card).toHaveTextContent('Linen Shirt · Black / M')
    expect(screen.getByTestId('guided-suggestion-why')).toHaveTextContent('You have the most of this one in Shopify · 42 in Shopify')
    expect(screen.getByTestId('guided-qty')).toHaveValue(10)
    expect(screen.getByTestId('guided-shopify-change')).toHaveTextContent('Traced Main5 → 15')
    expect(screen.getByTestId('guided-warning')).toHaveTextContent(en.guidedReceiving.warning)
    expect(writes()).toEqual([])

    await user.click(screen.getByRole('button', { name: en.guidedReceiving.start }))
    await screen.findByTestId('guided-session')
    expect(writes().map(c => [c.method, c.url.replace(/^.*\/api\/v1/, ''), c.body])).toEqual([
      ['POST', '/receiving/sessions', { locationId: 'loc-main', reference: null, supplierName: null, note: null, mode: 'print_stick' }],
      ['POST', '/receiving/sessions/sess-1/lines', { variantId: 'v-1', quantity: 10 }],
    ])
    expect(readGuidedSession()).toEqual({ id: 'sess-1', mode: 'print_stick', mainBefore: 5 })
  })

  test.each([['empty', null], ['failure', 'fail']])('%s → the picker, nothing written', async (_n, s) => {
    opts.suggestion = s
    renderAt('/receiving?guided=1')
    expect(await screen.findByTestId('guided-picker')).toBeInTheDocument()
    expect(screen.queryByTestId('guided-suggestion')).toBeNull()
    expect(screen.getByTestId('guided-shopify-change')).toHaveTextContent('Traced Main+0')
    expect(writes()).toEqual([])
  })

  test('Choose another opens the picker; Use the suggestion goes back', async () => {
    const user = userEvent.setup()
    renderAt('/receiving?guided=1')
    await user.click(await screen.findByTestId('guided-choose-another'))
    expect(screen.getByTestId('guided-picker')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.useSuggestion }))
    expect(screen.getByTestId('guided-suggestion')).toBeInTheDocument()
  })
})

describe('gr3 modes', () => {
  test('Medium pre-selected; High disabled with Set up without an open run', async () => {
    renderAt('/receiving?guided=1')
    await screen.findByTestId('guided-setup')
    await waitFor(() => expect(screen.getByTestId('receiving-mode-factory-needs-run')).toBeInTheDocument())
    expect(screen.getByTestId('receiving-mode-print_stick')).toHaveAttribute('aria-pressed', 'true')
    const high = screen.getByTestId('receiving-mode-factory')
    expect(high).toHaveAttribute('aria-disabled', 'true')
    expect(high.tagName).not.toBe('BUTTON')
    expect(within(high).getByRole('link', { name: en.receiving.mode.setUp })).toHaveAttribute('href', '/receiving?tab=runs')
  })

  test('High usable with an open run; it sends the run, no line', async () => {
    const user = userEvent.setup()
    opts.runs = [{ id: 'run-1', status: 'open', factoryName: 'Nile', reference: 'R1', received: 0, expected: 20 }]
    opts.session = session('factory', 'open', 0)
    renderAt('/receiving?guided=1')
    const high = await screen.findByRole('button', { name: /High control · Label & verify/ })
    await user.click(high)
    expect(screen.getByTestId('guided-run')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.start }))
    await screen.findByTestId('guided-session')
    expect(writes().map(c => [c.url.replace(/^.*\/api\/v1/, ''), c.body])).toEqual([
      ['/receiving/sessions', { locationId: 'loc-main', reference: null, supplierName: null, note: null, mode: 'factory', labelRunId: 'run-1' }],
    ])
  })

  test('Low sends count_only', async () => {
    const user = userEvent.setup()
    opts.session = session('count_only', 'open')
    renderAt('/receiving?guided=1')
    await user.click(await screen.findByRole('button', { name: /Low control · Quick count/ }))
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.start }))
    await screen.findByTestId('guided-session')
    expect(writes()[0].body).toMatchObject({ mode: 'count_only', locationId: 'loc-main' })
  })
})

async function openSession(mode: string, status: 'open' | 'finalized') {
  opts.session = session(mode, status)
  sessionStorage.setItem('traced.guidedReceiving', JSON.stringify({ id: 'sess-1', mode, mainBefore: 0 }))
  renderAt('/receiving?guided=1')
  return screen.findByTestId('guide-panel')
}

describe('gr4 the guide per mode', () => {
  const keys = (panel: HTMLElement) => Array.from(panel.querySelectorAll('[data-testid^="guide-step-"]'))
    .map(e => e.getAttribute('data-testid')!.replace('guide-step-', ''))

  test('Medium: add product → close → print → stick → check', async () => {
    const panel = await openSession('print_stick', 'open')
    expect(keys(panel)).toEqual(['addProduct', 'close', 'print', 'stick', 'check'])
    expect(screen.getByTestId('guide-step-addProduct')).toHaveAttribute('data-done', 'true')
    expect(screen.getByTestId('guide-step-close')).toHaveAttribute('data-current', 'true')
    expect(panel.querySelectorAll('input, textarea, select')).toHaveLength(0)
  })

  test('Low: add product → enter the count → close, nothing to print or check', async () => {
    const panel = await openSession('count_only', 'open')
    expect(keys(panel)).toEqual(['addProduct', 'enterCount', 'close'])
    expect(panel).not.toHaveTextContent(en.guidedReceiving.step.print)
    expect(panel).not.toHaveTextContent(en.guidedReceiving.step.check)
    expect(panel.querySelectorAll('input, textarea, select')).toHaveLength(0)
  })

  test('High: scan each piece → finish (the factory flow)', async () => {
    opts.session = session('factory', 'open', 0)
    const panel = await openSession('factory', 'open')
    expect(keys(panel)).toEqual(['scanFactory', 'finishFactory'])
  })

  test('Hide guide collapses it for this session', async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'open')
    await user.click(screen.getByTestId('guided-hide-guide'))
    expect(screen.queryByTestId('guide-panel')).toBeNull()
    expect(screen.getByTestId('guided-show-guide')).toBeInTheDocument()
  })
})

describe('gr5 + gr6 the label check', () => {
  beforeEach(() => {
    opts.pieces = [
      { id: 'pc-1', barcode: 'BC-1', short_code: 'P000101' },
      { id: 'pc-2', barcode: 'BC-2', short_code: 'P000102' },
      { id: 'pc-3', barcode: 'BC-3', short_code: 'P000103' },
    ]
  })

  test('read only; counts only this session; normalised; repeat and unknown', async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    const input = await screen.findByPlaceholderText(en.guidedReceiving.check.placeholder)
    await waitFor(() => expect(screen.getByTestId('label-check-counter')).toHaveTextContent('0 / 3 checked'))
    const before = writes().length

    await user.type(input, ' p000101 {Enter}')
    await waitFor(() => expect(screen.getByTestId('label-check-counter')).toHaveTextContent('1 / 3 checked'))
    expect(screen.getByTestId('label-check-last')).toHaveTextContent('P000101')

    await user.type(input, 'P000101{Enter}')
    await waitFor(() => expect(screen.getByTestId('label-check-last')).toHaveTextContent(en.guidedReceiving.check.again))
    expect(screen.getByTestId('label-check-counter')).toHaveTextContent('1 / 3 checked')

    await user.type(input, 'P999999{Enter}')
    await waitFor(() => expect(screen.getByText(en.guidedReceiving.check.notHere, { exact: false })).toBeInTheDocument())
    expect(screen.getByTestId('label-check-counter')).toHaveTextContent('1 / 3 checked')

    await user.type(input, 'P000102{Enter}')
    await user.type(input, 'BC-3{Enter}')
    await waitFor(() => expect(screen.getByTestId('label-check-counter')).toHaveTextContent('3 / 3 checked'))
    expect(screen.getByTestId('label-check-all')).toBeInTheDocument()
    expect(screen.getByTestId('guide-step-check')).toHaveAttribute('data-done', 'true')
    expect(writes().length).toBe(before)
    expect(calls.filter(c => c.url.includes('/pieces')).every(c => c.method === 'GET')).toBe(true)
  })

  test('the check input is the only input and keeps focus after clicks on the guide', async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    const input = await screen.findByPlaceholderText(en.guidedReceiving.check.placeholder)
    expect(document.querySelectorAll('input, textarea, select')).toHaveLength(1)
    await waitFor(() => expect(input).toHaveFocus())
    await user.click(screen.getByText(en.guidedReceiving.guideTitle))
    expect(input).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Print 3 labels' }))
    expect(input).toHaveFocus()
    await waitFor(() => expect(screen.getByTestId('guide-step-print')).toHaveAttribute('data-done', 'true'))
  })
})

describe('gr7 Flow-7', () => {
  test("Medium: I'm done → Flow-7 with Lookup; pace → PATCH → Overview; shown once", async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    await user.click(await screen.findByRole('button', { name: en.guidedReceiving.done }))
    const done = await screen.findByTestId('guided-done')
    expect(done).toHaveTextContent('Your first 3 pieces are in Traced')
    expect(screen.getByTestId('guided-done-shopify')).toHaveTextContent('Traced Main0 → 3')
    expect(screen.getByTestId('guided-done-lookup')).toHaveTextContent(en.guidedReceiving.finished.lookupBody)
    expect(within(screen.getByTestId('guided-done-lookup')).getByRole('link')).toHaveAttribute('href', '/lookup')

    await user.click(screen.getByTestId('guided-pace-steady'))
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.finished.setGoal }))
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(writes().filter(c => c.url.includes('/onboarding/prefs'))).toEqual([
      expect.objectContaining({ method: 'PATCH', body: { labelingPace: 150 } }),
    ])
    expect(readGuidedSession()).toBeNull()

    // Shown once: the next visit (first_receiving now done) is the normal page.
    cleanup(); forgetOnboardingProgress(); opts.firstDone = true
    renderAt('/receiving?guided=1')
    expect(await screen.findByText(en.receiving.title, { selector: 'h1' })).toBeInTheDocument()
    expect(screen.queryByTestId('guided-done')).toBeNull()
  })

  test('Low: Flow-7 right after finishing, no Lookup line; Not now → Overview without a write', async () => {
    const user = userEvent.setup()
    opts.session = session('count_only', 'finalized')
    sessionStorage.setItem('traced.guidedReceiving', JSON.stringify({ id: 'sess-1', mode: 'count_only', mainBefore: null }))
    renderAt('/receiving?guided=1')
    const done = await screen.findByTestId('guided-done')
    expect(done).toHaveTextContent(en.guidedReceiving.finished.subLow)
    expect(screen.queryByTestId('guided-done-lookup')).toBeNull()
    expect(screen.getByTestId('guided-done-shopify')).toHaveTextContent('Traced Main+3')
    await user.click(screen.getByRole('button', { name: en.guidedReceiving.finished.notNow }))
    expect(await screen.findByTestId('overview-page')).toBeInTheDocument()
    expect(writes()).toEqual([])
  })
})

describe('gr8 mode cards (rename)', () => {
  const NAMES = {
    en: { count_only: 'Low control · Quick count', print_stick: 'Medium control · Label now', factory: 'High control · Label & verify' },
    ar: { count_only: 'تحكم منخفض · عدّ سريع', print_stick: 'تحكم متوسط · ملصقات الآن', factory: 'تحكم عالٍ · ملصقات وتأكيد' },
  } as const

  test.each(['en', 'ar'] as const)('%s: name, description, meter, low → high, no Recommended badge', lng => {
    const L = lng === 'en' ? en : ar
    render(
      <MemoryRouter><I18nextProvider i18n={i18nFor(lng)}>
        <ReceivingModeCards selected="print_stick" onSelect={() => {}} factoryAvailable />
      </I18nextProvider></MemoryRouter>)
    const cards = MODE_ORDER.map(m => screen.getByTestId(`receiving-mode-${m}`))
    expect(MODE_ORDER).toEqual(['count_only', 'print_stick', 'factory'])
    MODE_ORDER.forEach((m, i) => {
      expect(cards[i]).toHaveTextContent(NAMES[lng][m])
      expect(cards[i]).toHaveTextContent(L.receiving.mode[m].body)
      expect(within(cards[i]).getByTestId('mode-meter')).toHaveAttribute('data-level', String(i + 1))
      expect(within(cards[i]).getByTestId('mode-meter').querySelectorAll('[data-filled="true"]')).toHaveLength(i + 1)
    })
    expect(cards.some(c => /Recommended|موصى به/.test(c.textContent ?? ''))).toBe(false)
    // The descriptions say what the code does: main warehouse, stock on finish / only scanned pieces.
    expect(cards[0]).toHaveTextContent(lng === 'en' ? 'goes into your main warehouse and shows in Inventory as soon as you finish the session' : 'بمجرد إنهاء الجلسة')
    expect(cards[2]).toHaveTextContent(lng === 'en' ? 'Only scanned pieces enter your stock' : 'لا تدخل المخزون إلا القطع الممسوحة')
  })

  test('the normal chooser still sends the internal value; High disabled without a run, enabled with one', async () => {
    const user = userEvent.setup()
    opts.runs = []
    render(
      <StationProvider><MemoryRouter initialEntries={['/receiving']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
        <Routes><Route path="/receiving" element={<Receiving />} /></Routes>
      </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    await user.click(await screen.findByRole('button', { name: '+ ' + en.receiving.new }))
    await waitFor(() => expect(screen.getByTestId('receiving-mode-factory-needs-run')).toBeInTheDocument())
    expect(screen.getByTestId('receiving-mode-factory')).toHaveAttribute('aria-disabled', 'true')
    await user.click(screen.getByRole('button', { name: /Low control · Quick count/ }))
    await user.click(screen.getByRole('button', { name: en.receiving.create }))
    await waitFor(() => expect(writes().some(c => c.url.endsWith('/receiving/sessions'))).toBe(true))
    expect(writes().find(c => c.url.endsWith('/receiving/sessions'))!.body).toMatchObject({ mode: 'count_only' })

    cleanup(); resetCapabilitiesCache()
    opts.runs = [{ id: 'run-1', status: 'open', factoryName: 'Nile', reference: null, received: 0, expected: 5 }]
    render(
      <StationProvider><MemoryRouter initialEntries={['/receiving']}><I18nextProvider i18n={i18nFor('en')}><ToastProvider>
        <Routes><Route path="/receiving" element={<Receiving />} /></Routes>
      </ToastProvider></I18nextProvider></MemoryRouter></StationProvider>)
    await user.click(await screen.findByRole('button', { name: '+ ' + en.receiving.new }))
    const high = await screen.findByRole('button', { name: /High control · Label & verify/ })
    expect(high).not.toHaveAttribute('aria-disabled')
    expect(screen.queryByTestId('receiving-mode-factory-needs-run')).toBeNull()
  })

  test('the old names are gone from every merchant-facing string (EN + AR locale files)', () => {
    const OLD = [/print\s*(&|and)\s*stick/i, /count[- ]only/i, /factory[- ]label(l)?ed/i,
      /اطبع والصق/, /طباعة ولصق/, /عدّ فقط/, /بالعدد فقط/, /بملصقات المصنع/]
    for (const [name, file] of [['en', en], ['ar', ar]] as const) {
      const text = JSON.stringify(file)
      for (const re of OLD) expect(re.test(text), `${name}: ${re}`).toBe(false)
    }
  })
})

describe('gr9 AR / RTL', () => {
  test('Flow-5 in Arabic, right to left', async () => {
    document.documentElement.dir = 'rtl'
    renderAt('/receiving?guided=1', 'ar')
    await screen.findByTestId('guided-setup')
    expect(screen.getByText(ar.guidedReceiving.setupTitle)).toBeInTheDocument()
    expect(screen.getByTestId('guided-suggestion-why')).toHaveTextContent('لديك من هذا المنتج أكثر من غيره في شوبيفاي · 42 في شوبيفاي')
    expect(screen.getByRole('button', { name: ar.guidedReceiving.skip })).toBeInTheDocument()
    expect(screen.getByTestId('guided-warning')).toHaveTextContent(ar.guidedReceiving.warning)
  })
})

describe('gr10 the test label', () => {
  const prefWrites = () => writes().filter(c => c.url.includes('/onboarding/prefs'))

  test("a print that opens → testLabelPrinted, once (guide's Print and the session's own buttons)", async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    expect(prefWrites()).toEqual([])
    await user.click(await screen.findByRole('button', { name: 'Print 3 labels' }))
    await waitFor(() => expect(prefWrites()).toEqual([
      expect.objectContaining({ method: 'PATCH', body: { testLabelPrinted: true } }),
    ]))
    expect(window.open).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: en.receiving.reprint }))
    await waitFor(() => expect(window.open).toHaveBeenCalledTimes(2))
    expect(prefWrites()).toHaveLength(1)
  })

  test("the session's own Print Labels counts too", async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    await user.click(await screen.findByRole('button', { name: `${en.receiving.printLabels} (3)` }))
    await waitFor(() => expect(prefWrites()).toEqual([
      expect.objectContaining({ method: 'PATCH', body: { testLabelPrinted: true } }),
    ]))
  })

  test('a failed print writes nothing', async () => {
    const user = userEvent.setup()
    await openSession('print_stick', 'finalized')
    const inner = globalThis.fetch
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      if (typeof url === 'string' && url.endsWith('/labels')) {
        calls.push({ url, method: 'GET', body: undefined })
        return json({ message_en: 'x' }, 500)
      }
      return (inner as (u: string, i?: RequestInit) => unknown)(url, init)
    })
    await user.click(await screen.findByRole('button', { name: 'Print 3 labels' }))
    expect(await screen.findByText(en.guidedReceiving.printFailed)).toBeInTheDocument()
    expect(prefWrites()).toEqual([])
    expect(window.open).not.toHaveBeenCalled()
  })
})
