import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import BazarPhoneHome from '../pages/bazar/phone/BazarPhoneHome'
import BazarEndEvent from '../pages/bazar/phone/BazarEndEvent'
import BazarReportPage from '../pages/bazar/BazarReport'
import BazarEvents from '../pages/bazar/BazarEvents'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'

// Bazar POS B5 — End event (confirm; the days still to close), the leftovers screen (scan back with the SAME request
// id on a weak signal; good / damaged result; mark lost; Close the transfer only when sent = sold + back + damaged +
// lost) → the summary, the desktop report page and its CSV export, and an ended event opening its report.

vi.mock('../pages/scanpair/CameraReader', () => ({
  default: () => <span>camera</span>,
}))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TODAY = '2026-11-20'
const LIVE = { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 2, dayCount: 3, businessDay: TODAY,
               paymentMethods: ['cash', 'card', 'instapay'] }

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role, sub: `u-${role}` }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
    blob: async () => new Blob(['name,phone\n']),
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

const on = (method: string, frag: string, fn: (url: string, init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.includes(frag) ? fn(url, init) : undefined)

function Where() { return <div data-testid="where">{useLocation().pathname}</div> }

function renderAt(path: string, routes: React.ReactNode) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider><Routes>{routes}</Routes></ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

// ── fixtures ────────────────────────────────────────────────────────────────

const detail = (status: string) => ({
  id: EVENT, name: 'Zamalek Winter Bazar', starts_on: '2026-11-19', ends_on: '2026-11-21', status,
  location_id: 'l', location_name: 'Hall', transfer_id: 't-1', transfer_status: 'sent', created_at: TODAY,
  transfer_sent_at: TODAY, transfer_shopify_sync_mode: 'remove', payment_methods: ['cash'], seller_roles: ['owner'],
  discount_pct: null, round_to: null, started_at: TODAY, ended_at: null,
  counts: { sent: 5, at_bazar: 3, sold: 2, back: 0, lost: 0 },
  today: { business_day: TODAY, by_method: { cash: '0' }, total: '0', sales: 0 },
})

const piece = (id: string, title = 'Linen shirt', variant: string | null = 'White / M') =>
  ({ piece_id: id, piece_code: `P-${id}`, product_title: title, variant_title: variant, sku: null })

function leftovers(over: Record<string, unknown> = {}) {
  return {
    event_id: EVENT, event_status: 'ended', transfer_id: 't-1', transfer_status: 'reconciling',
    counts: { sent: 5, sold: 2, back: 0, damaged: 1, damaged_at_bazar: 1, lost: 0, outstanding: 2 },
    balanced: false, outstanding: [piece('a'), piece('b', 'Scarf', null)], damaged_to_bring_home: [piece('d', 'Knit top')],
    ...over,
  }
}

const report = {
  event: { id: EVENT, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: '2026-11-19', ends_on: '2026-11-21',
           started_at: TODAY, ended_at: TODAY, location_name: 'Hall' },
  revenue: '2400.00', money_in: '2400.00', pieces_sold: 4, pieces_sent: 9, sell_through: '0.4444', average_sale: '600.00',
  returns: 1, exchanges: 2,
  sales_by_day: [{ business_day: '2026-11-19', day_number: 1, sales: 4, revenue: '2100.00', money_in: '2100.00', status: 'closed_short' },
                 { business_day: TODAY, day_number: 2, sales: 3, revenue: '300.00', money_in: '300.00', status: 'open' }],
  busiest_hours: [{ hour: 13, sales: 3 }, { hour: 18, sales: 4 }],
  top_products: [{ product_title: 'Linen Shirt', sold: 3, sent: 6, sell_through: '0.5', revenue: '2100.00' }],
  top_sizes: [{ product_title: 'Linen Shirt', variant_title: 'L', sold: 2, sent: 2, sell_through: '1', revenue: '1600.00' }],
  payment_split: [{ method: 'cash', amount: '600.00', share: '0.25' }, { method: 'card', amount: '500.00', share: '0.2083' },
                  { method: 'instapay', amount: '1300.00', share: '0.5417' }],
  money_not_received: { cash_short: '50.00', cash_over: '0.00', card_short: '20.00', card_over: '0.00',
                        instapay_not_received: '0.00', total: '70.00', days_closed: 1, days_not_closed: 1 },
  per_seller: [{ seller_name: 'Omar', pieces: 3, total: '1900.00' }],
  customers_saved: 2,
  transfer: { sent: 9, sold: 4, back: 3, damaged: 1, damaged_at_bazar: 0, lost: 1, outstanding: 0, transfer_id: 't-1',
              status: 'closed', balanced: true },
}

function owner(...more: Handler[]): Handler[] {
  setAccessToken(fakeJwt('owner'))
  return [
    on('GET', `/bazar-events/${EVENT}/report`, () => json(200, report)),   // before the event-detail stubs (prefix)
    ...more,
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true, bazarLive: LIVE })),
    on('GET', `/bazar-events/${EVENT}/today`, () => json(200, { business_day: TODAY, total: '0', sale_count: 0, by_method: {}, per_seller: [] })),
  ]
}

const posts = (frag: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(`/bazar-events/${EVENT}/${frag}`))
const endRoutes = (
  <>
    <Route path="/bazar/:id/end" element={<BazarEndEvent />} />
    <Route path="/bazar/:id/close" element={<Where />} />
    <Route path="/bazar/:id/report" element={<Where />} />
  </>
)

async function scan(code: string) {
  await userEvent.type(await screen.findByTestId('bazar-code-input'), `${code}{Enter}`)
}

// ── Home ────────────────────────────────────────────────────────────────────

test('Phone home: End event opens the end screen', async () => {
  install(...owner())
  renderAt(`/bazar/${EVENT}/home`, <>
    <Route path="/bazar/:id/home" element={<BazarPhoneHome />} />
    <Route path="/bazar/:id/end" element={<Where />} />
  </>)
  await userEvent.click(await screen.findByTestId('home-end-event'))
  expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EVENT}/end`)
})

// ── End ─────────────────────────────────────────────────────────────────────

describe('End event', () => {
  test('days still open block it, named with a link to close them', async () => {
    install(...owner(
      on('GET', `/bazar-events/${EVENT}/leftovers`, () => json(200, leftovers({ event_status: 'live', transfer_status: 'sent' }))),
      on('POST', '/end', () => json(409, { code: 'BAZAR_DAYS_NOT_CLOSED', message_en: 'x', message_ar: 'x',
        details: { days: [{ business_day: TODAY, day_number: 2, status: 'changed_after_close' }] } })),
      on('GET', `/bazar-events/${EVENT}`, () => json(200, detail('live'))),
    ))
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    await userEvent.click(await screen.findByTestId('end-confirm'))
    const box = await screen.findByTestId('end-days-not-closed')
    expect(box).toHaveTextContent('Day 2')
    expect(box).toHaveTextContent('Changed after close — close again')
    await userEvent.click(within(box).getByTestId('end-close-days'))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EVENT}/close`)
  })

  test('confirming ends it and opens the leftovers', async () => {
    install(...owner(
      on('GET', `/bazar-events/${EVENT}/leftovers`, () => json(200, leftovers({ event_status: 'live', transfer_status: 'sent' }))),
      on('POST', '/end', () => json(200, leftovers())),
      on('GET', `/bazar-events/${EVENT}`, () => json(200, detail('live'))),
    ))
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    await userEvent.click(await screen.findByTestId('end-confirm'))
    expect(await screen.findByTestId('leftovers-panel')).toBeTruthy()
    expect(posts('end')).toHaveLength(1)
    expect(screen.getByTestId('leftover-equation').textContent).toBe('Sent 5 = 2 sold + 0 back + 1 damaged + 0 lost')
    expect(screen.getByTestId('leftover-still-out').textContent).toBe('2 pieces still out')
  })
})

// ── Leftovers ───────────────────────────────────────────────────────────────

describe('Leftovers', () => {
  const ended = () => [
    on('GET', `/bazar-events/${EVENT}/leftovers`, () => json(200, leftovers())),
    on('GET', `/bazar-events/${EVENT}`, () => json(200, detail('ended'))),
  ]

  test('scan back on a weak signal: retried with the SAME request id, "Back in stock" only once confirmed', async () => {
    let n = 0
    install(...owner(on('POST', '/scan-back', () => {
      n += 1
      if (n === 1) return Promise.reject(new TypeError('Failed to fetch'))
      return json(200, { result: 'back_good', piece: piece('a'), replayed: false,
        leftovers: leftovers({ counts: { sent: 5, sold: 2, back: 1, damaged: 1, damaged_at_bazar: 1, lost: 0, outstanding: 1 },
                               outstanding: [piece('b', 'Scarf', null)] }) })
    }), ...ended()))
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    await scan('P-a')
    expect(await screen.findByTestId('scan-back-back_good')).toHaveTextContent('Back in stock')
    const ids = posts('scan-back').map(c => (c.body as Record<string, string>).client_request_id)
    expect(ids).toHaveLength(2)
    expect(ids[0]).toBe(ids[1])
    expect((posts('scan-back')[0].body as Record<string, string>).code).toBe('P-a')
    expect(screen.getByTestId('count-back')).toHaveTextContent('1')
  })

  test('a damaged piece comes home flagged "set it aside"; a refusal shows its own copy', async () => {
    let n = 0
    install(...owner(on('POST', '/scan-back', () => {
      n += 1
      return n === 1
        ? json(200, { result: 'back_damaged', piece: piece('d', 'Knit top'), replayed: false,
                      leftovers: leftovers({ damaged_to_bring_home: [] }) })
        : json(409, { code: 'BAZAR_ALREADY_BACK', message_en: 'x', message_ar: 'x' })
    }), ...ended()))
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    await scan('P-d')
    expect(await screen.findByTestId('scan-back-back_damaged')).toHaveTextContent('Back home — damaged, set it aside')
    await scan('P-d')
    expect(await screen.findByTestId('cmd-error')).toHaveTextContent('Already back')
  })

  test('mark lost sends exactly the chosen pieces, after a confirm', async () => {
    install(...owner(on('POST', '/mark-lost', () => json(200, leftovers({
      counts: { sent: 5, sold: 2, back: 0, damaged: 1, damaged_at_bazar: 1, lost: 1, outstanding: 1 },
      outstanding: [piece('a')] }))), ...ended()))
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    await userEvent.click(await screen.findByTestId('out-b'))
    await userEvent.click(screen.getByTestId('mark-lost'))
    expect(screen.getByTestId('mark-lost-confirm')).toHaveTextContent('1 piece is marked lost')
    await userEvent.click(screen.getByTestId('mark-lost-yes'))
    await waitFor(() => expect(posts('mark-lost')).toHaveLength(1))
    expect(posts('mark-lost')[0].body).toEqual({ piece_ids: ['b'] })
    expect(await screen.findByTestId('count-lost')).toHaveTextContent('1')
  })

  test('Close the transfer is off until everything is accounted for, then shows the summary', async () => {
    let balanced = false
    install(...owner(
      on('POST', '/close-transfer', () => json(200, leftovers({ transfer_status: 'closed', balanced: true, outstanding: [],
        counts: { sent: 5, sold: 2, back: 2, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 0 } }))),
      on('GET', `/bazar-events/${EVENT}/leftovers`, () => json(200, balanced
        ? leftovers({ balanced: true, outstanding: [], counts: { sent: 5, sold: 2, back: 2, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 0 } })
        : leftovers())),
      on('GET', `/bazar-events/${EVENT}`, () => json(200, detail('ended'))),
    ))
    const first = renderAt(`/bazar/${EVENT}/end`, endRoutes)
    expect(((await screen.findByTestId('close-transfer')) as HTMLButtonElement).disabled).toBe(true)
    first.unmount()

    balanced = true
    renderAt(`/bazar/${EVENT}/end`, endRoutes)
    const btn = await screen.findByTestId('close-transfer') as HTMLButtonElement
    await waitFor(() => expect(btn.disabled).toBe(false))
    await userEvent.click(btn)
    const summary = await screen.findByTestId('end-summary')
    expect(posts('close-transfer')).toHaveLength(1)
    expect(within(summary).getByTestId('kpi-revenue')).toHaveTextContent('EGP 2,400')
    expect(within(summary).getByTestId('kpi-through')).toHaveTextContent('44%')
    await userEvent.click(screen.getByTestId('end-full-report'))
    expect(screen.getByTestId('where').textContent).toBe(`/bazar/${EVENT}/report`)
  })
})

// ── Report (desktop) ────────────────────────────────────────────────────────

describe('Report page', () => {
  test('shows the numbers and exports the customers CSV', async () => {
    install(...owner(on('GET', '/report/customers.csv', () => json(200, {}))))
    const created: string[] = []
    const orig = URL.createObjectURL
    URL.createObjectURL = vi.fn(() => { created.push('blob'); return 'blob:x' }) as typeof URL.createObjectURL
    URL.revokeObjectURL = vi.fn()
    try {
      renderAt(`/bazar/${EVENT}/report`, <Route path="/bazar/:id/report" element={<BazarReportPage />} />)
      expect(await screen.findByTestId('kpi-revenue')).toHaveTextContent('EGP 2,400')
      expect(screen.getByTestId('kpi-sold')).toHaveTextContent('4')
      expect(screen.getByTestId('kpi-avg')).toHaveTextContent('EGP 600')
      expect(screen.getByTestId('report-not-received')).toHaveTextContent('EGP 70')
      expect(screen.getByTestId('report-not-received')).toHaveTextContent('1 day isn\'t closed')
      expect(screen.getByTestId('report-top-sizes')).toHaveTextContent('Linen Shirt · L')
      expect(screen.getByTestId('report-transfer')).toHaveTextContent('Sent 9 = 4 sold + 3 back + 1 damaged + 1 lost')
      expect(screen.getByTestId('report-customers')).toHaveTextContent('2 customers saved')
      await userEvent.click(screen.getByRole('button', { name: /Export customers/ }))
      await waitFor(() => expect(calls.some(c => c.url.endsWith(`/bazar-events/${EVENT}/report/customers.csv`))).toBe(true))
      await waitFor(() => expect(created).toHaveLength(1))
    } finally {
      URL.createObjectURL = orig
    }
  })

  test('an ended event in the list opens its report', async () => {
    install(...owner(on('GET', '/bazar-events', () => json(200, [
      { ...detail('ended'), id: 'e-old', name: 'Autumn Fair' }, { ...detail('live'), id: 'e-live', name: 'Winter' }]))))
    renderAt('/bazar', <Route path="/bazar" element={<BazarEvents />} />)
    expect((await screen.findByText('Autumn Fair')).closest('a')?.getAttribute('href')).toBe('/bazar/e-old/report')
    expect(screen.getByText('Winter').closest('a')?.getAttribute('href')).toBe('/bazar/e-live')
  })
})
