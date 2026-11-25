import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { CloseDayForm, DayStatusBadge } from '../pages/bazar/CloseDayForm'
import { LeftoversPanel } from '../pages/bazar/Leftovers'
import BazarReportPage from '../pages/bazar/BazarReport'
import BazarCloseDay from '../pages/bazar/phone/BazarCloseDay'
import { resetCapabilitiesCache } from '../capabilities'
import type { BazarDay, BazarLeftovers, BazarReport } from '../bazar/api'
import { clearAccessToken, setAccessToken } from '../auth'

// Bazar R2 restyle — Close day (cash first + autofocused, the Drawer block below, "includes opening float", balanced /
// short / over), End event leftovers (partly / all back, per-row Mark lost → exactly one piece), the report (with and
// without lost / still-out pieces, the "62,780 EGP" format, a failed load).

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const DAY = '2026-11-20'

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: (url: string) => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn(url)
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: (url: string) => Promise<Response>): Handler => ({ method, path, fn })

function wrap(node: React.ReactNode, path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <I18nextProvider i18n={testI18n}><Routes><Route path="*" element={node} /></Routes></I18nextProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  clearAccessToken()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner', sub: 'u-owner' }))}.s`)
  testI18n.changeLanguage('en')
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

// ── Close day fixtures ───────────────────────────────────────────────────────

function day(over: Partial<BazarDay> = {}): BazarDay {
  return {
    business_day: DAY, day_number: 1, is_today: true, status: 'open', close: null, close_count: 0,
    money: { by_method: { cash: '9840', card: '3480', instapay: '5600' }, total: '18920', sales: 26, returns: 1 },
    opening_float_default: { amount: null, source: 'none' }, ...over,
  } as BazarDay
}

/** The server's preview, from the query: expected cash = float + 9,840; card 3,480; InstaPay 5,600. */
function preview(url: string, carried = 0) {
  const q = new URL(url, 'http://x').searchParams
  const flt = q.get('float') != null ? Number(q.get('float')) : carried
  const cash = q.get('cash') == null ? null : Number(q.get('cash'))
  const card = q.get('card') == null ? null : Number(q.get('card'))
  const insta = q.get('instapay') !== 'false'
  const expCash = flt + 9840
  const cashDiff = (cash ?? 0) - expCash
  const cardDiff = (card ?? 0) - 3480
  const instaDiff = insta ? 0 : -5600
  const st = (d: number, ip = false) => (d === 0 ? 'even' : d > 0 ? 'over' : ip ? 'not_received' : 'short')
  const short = (d: number) => (d < 0 ? -d : 0)
  const over = Math.max(cashDiff, 0) + Math.max(cardDiff, 0)
  const notRec = short(cashDiff) + short(cardDiff) + short(instaDiff)
  return {
    business_day: DAY, status: 'open', money: {}, opening_float_source: q.get('float') ? 'entered' : 'none',
    needs_opening_float: true, problems: [],
    result: {
      opening_float: flt, cash_kept: flt, take_home: (cash ?? 0) - flt, not_received_total: notRec,
      not_received: { cash: short(cashDiff), card: short(cardDiff), instapay: short(instaDiff) }, over_total: over,
      methods: [
        { method: 'cash', expected: expCash, entered: cash ?? 0, difference: cashDiff, state: st(cashDiff) },
        { method: 'card', expected: 3480, entered: card ?? 0, difference: cardDiff, state: st(cardDiff) },
        { method: 'instapay', expected: 5600, entered: insta ? 5600 : 0, difference: instaDiff, state: st(instaDiff, true), received: insta },
      ],
    },
  }
}

const toNum = (v: unknown) => (v == null ? 0 : Number(v))

function renderClose(over: Partial<BazarDay> = {}, compact = true) {
  const carried = toNum(over.opening_float_default?.amount)
  install(on('GET', `/bazar-events/${EVENT}/days/${DAY}/close-preview`, url => json(200, preview(url, carried))))
  return wrap(<CloseDayForm eventId={EVENT} day={day(over)} methods={['cash', 'instapay', 'card']} onClosed={() => {}} compact={compact} />)
}

const typeIn = async (id: string, v: string) => {
  const el = screen.getByTestId(id)
  await userEvent.clear(el)
  await userEvent.type(el, v)
}

describe('Close day', () => {
  test('desktop (compact): the cursor starts in Cash; order is Cash → Card → InstaPay → difference → Drawer', async () => {
    renderClose()
    expect(document.activeElement).toBe(screen.getByTestId('close-cash'))
    const order = ['close-section-cash', 'close-section-card', 'close-section-instapay', 'close-drawer']
      .map(id => screen.getByTestId(id))
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    // The opening float and kept-for-tomorrow live in the Drawer block, not above Cash.
    const drawer = screen.getByTestId('close-drawer')
    expect(within(drawer).getByTestId('close-float')).toBeTruthy()
    expect(within(drawer).getByTestId('close-kept')).toBeTruthy()
  })

  test('phone (not compact): the cursor starts in Cash too', () => {
    renderClose({}, false)
    expect(document.activeElement).toBe(screen.getByTestId('close-cash'))
  })

  test('"Should be" for cash says it includes the opening float; balanced day → Matches everywhere, all received', async () => {
    renderClose()
    await typeIn('close-float', '1000')
    await typeIn('close-cash', '10840')
    await typeIn('close-card', '3480')
    const cash = screen.getByTestId('close-section-cash')
    await waitFor(() => expect(cash.textContent).toContain('includes opening float 1,000'))
    expect(cash.textContent).toContain('10,840')
    await waitFor(() => expect(screen.getByTestId('close-chip-cash').textContent).toBe('Matches'))
    expect(screen.getByTestId('close-chip-card').textContent).toBe('Matches')
    expect(screen.getByTestId('close-all-received')).toBeTruthy()
    expect(screen.getByTestId('close-difference').textContent).toContain('0')
    // The day's counts from the days list.
    expect(screen.getByTestId('close-summary').textContent).toContain('26 sales')
    expect(screen.getByTestId('close-summary').textContent).toContain('1 refund')
    // Take-home sits in the Drawer block.
    expect(within(screen.getByTestId('close-drawer')).getByTestId('close-take-home').textContent)
      .toContain('Take home 9,840 EGP in cash')
  })

  test('short day: chip and the dark difference card show it, the split keeps label → value', async () => {
    renderClose({ opening_float_default: { amount: '1000.00', source: 'previous_close' } })
    expect(screen.getByTestId('close-float-line').textContent).toContain('Drawer started with 1,000 EGP')
    expect(screen.queryByTestId('close-float')).toBeNull()
    await typeIn('close-cash', '10800')
    await typeIn('close-card', '3480')
    await waitFor(() => expect(screen.getByTestId('close-chip-cash').textContent).toBe('Short 40'))
    expect(screen.getByTestId('close-difference').textContent).toContain('− 40')
    const box = screen.getByTestId('close-not-received')
    expect(box.textContent).toContain('40 EGP not received')
    expect(within(box).getByText('Cash short').nextSibling?.textContent).toBe('40')
  })

  test('InstaPay not received and cash over are both shown', async () => {
    renderClose()
    await typeIn('close-float', '0')
    await typeIn('close-cash', '9940')
    await typeIn('close-card', '3480')
    await userEvent.click(screen.getByTestId('close-instapay'))
    await waitFor(() => expect(screen.getByTestId('close-chip-instapay').textContent).toBe('Not received 5,600'))
    expect(screen.getByTestId('close-over').textContent).toContain('100 EGP over')
    expect(within(screen.getByTestId('close-not-received')).getByText('InstaPay not received').nextSibling?.textContent).toBe('5,600')
  })

  test('phone close screen: the header shows today from the days list already loaded', async () => {
    resetCapabilitiesCache()
    install(
      on('GET', '/me', () => json(200, { name: 'O', email: 'o@x', role: 'owner', bazar: true,
        bazarLive: { eventId: EVENT, name: 'Zamalek Winter Bazar', day: 1, dayCount: 3, businessDay: DAY, paymentMethods: ['cash', 'instapay', 'card'] } })),
      on('GET', `/bazar-events/${EVENT}/days`, () => json(200, { today: DAY, payment_methods: ['cash', 'instapay', 'card'], days: [day()] })),
      on('GET', `/bazar-events/${EVENT}/days/${DAY}/close-preview`, url => json(200, preview(url))),
    )
    wrap(<Routes><Route path="/bazar/:id/close" element={<BazarCloseDay />} /></Routes>, `/bazar/${EVENT}/close`)
    const head = await screen.findByTestId('close-header-today')
    expect(head.textContent).toBe('Today 18,920 · 26 sales')
    // Only the days list (and the preview) — no extra read for the header.
    await screen.findByTestId('close-day-form')
    expect(calls.map(c => c.url.replace(/^.*\/api\/v1/, '').split('?')[0]).filter(u => u.startsWith('/bazar-events')).sort())
      .toEqual(expect.arrayContaining([`/bazar-events/${EVENT}/days`]))
    expect(calls.some(c => /\/bazar-events\/e-1$/.test(c.url))).toBe(false)
  })

  test('day status tags are square (6px), never pills', () => {
    wrap(<>
      <DayStatusBadge day={day({ status: 'closed_balanced' })} />
    </>)
    const tag = screen.getByTestId(`day-status-${DAY}`)
    expect(tag.textContent).toBe('Closed · balanced')
    expect(tag.className).toMatch(/\brounded\b/)
    expect(tag.className).not.toMatch(/rounded-full/)
  })
})

// ── End event ───────────────────────────────────────────────────────────────

const piece = (id: string, code: string, title: string, variant: string | null = null) =>
  ({ piece_id: id, piece_code: code, product_title: title, variant_title: variant, sku: null })

function leftovers(over: Partial<BazarLeftovers> = {}): BazarLeftovers {
  return {
    event_id: EVENT, event_status: 'ended', transfer_id: 't-12345678', transfer_status: 'sent',
    counts: { sent: 24, sold: 0, back: 20, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 3 },
    balanced: false,
    outstanding: [piece('p-a', 'P000229', 'Wool cardigan', 'Beige / S'), piece('p-b', 'P000250', 'Knit scarf'),
                  piece('p-c', 'P000236', 'Linen shirt', 'White / L')],
    damaged_to_bring_home: [], ...over,
  }
}

describe('End event — leftovers', () => {
  test('partly back: "21 of 24 back", the counts, Close the transfer disabled with the reason', () => {
    install()
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera />)
    expect(screen.getByTestId('leftover-back-of').textContent).toBe('21of 24 back')
    expect(screen.getByTestId('count-back').textContent).toContain('20')
    expect(screen.getByTestId('count-damaged').textContent).toContain('1')
    expect(screen.getByTestId('leftover-still-out').textContent).toBe('3 pieces still out')
    expect(screen.getByTestId('leftovers-out').textContent).toContain('Not back yet · 3')
    expect((screen.getByTestId('close-transfer') as HTMLButtonElement).disabled).toBe(true)
    // No "still looking" row in the box — the transfer can't close while anything is out.
    expect(screen.getByTestId('leftovers-close-box').textContent).not.toMatch(/still looking/i)
  })

  test('all back: Close the transfer is enabled and calls close-transfer', async () => {
    const all = leftovers({ balanced: true, outstanding: [], counts: { sent: 24, sold: 0, back: 23, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 0 } })
    install(on('POST', `/bazar-events/${EVENT}/close-transfer`, () => json(200, { ...all, transfer_status: 'closed' })))
    const onClosed = vi.fn()
    wrap(<LeftoversPanel eventId={EVENT} initial={all} onClosed={onClosed} noCamera />)
    const btn = screen.getByTestId('close-transfer') as HTMLButtonElement
    expect(btn.disabled).toBe(false)
    await userEvent.click(btn)
    await screen.findByTestId('transfer-closed')
    expect(onClosed).toHaveBeenCalledTimes(1)
  })

  test('per-row Mark lost asks first, then sends exactly that one piece', async () => {
    const after = leftovers({ outstanding: leftovers().outstanding.filter(p => p.piece_id !== 'p-a'),
      counts: { sent: 24, sold: 0, back: 20, damaged: 1, damaged_at_bazar: 0, lost: 1, outstanding: 2 } })
    install(on('POST', `/bazar-events/${EVENT}/mark-lost`, () => json(200, after)))
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera />)
    await userEvent.click(screen.getByTestId('mark-lost-row-p-a'))
    const confirm = screen.getByTestId('mark-lost-row-confirm')
    expect(confirm.textContent).toContain('Mark P000229 as lost? It will be removed from stock.')
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(0)            // nothing sent before the confirm
    await userEvent.click(within(confirm).getByTestId('mark-lost-row-yes'))
    await waitFor(() => expect(screen.getByTestId('count-lost').textContent).toContain('1'))
    const posts = calls.filter(c => c.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toEqual({ piece_ids: ['p-a'] })
    expect(screen.queryByTestId('mark-lost-row-p-a')).toBeNull()
  })

  test('the per-row confirm can be cancelled; the tick-many path still works', async () => {
    install(on('POST', `/bazar-events/${EVENT}/mark-lost`, () => json(200, leftovers())))
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera />)
    await userEvent.click(screen.getByTestId('mark-lost-row-p-b'))
    await userEvent.click(within(screen.getByTestId('mark-lost-row-confirm')).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByTestId('mark-lost-row-confirm')).toBeNull()
    await userEvent.click(screen.getByTestId('out-p-b'))
    await userEvent.click(screen.getByTestId('out-p-c'))
    await userEvent.click(screen.getByTestId('mark-lost'))
    await userEvent.click(screen.getByTestId('mark-lost-yes'))
    await waitFor(() => expect(calls.filter(c => c.method === 'POST')).toHaveLength(1))
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({ piece_ids: ['p-b', 'p-c'] })
  })

  test('damaged pieces to bring home are listed', () => {
    install()
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers({ damaged_to_bring_home: [piece('p-d', 'P000014', 'Wool cardigan', 'Beige / M')] })}
      onClosed={() => {}} noCamera />)
    expect(screen.getByTestId('leftovers-damaged').textContent).toContain('Wool cardigan · Beige / M')
  })
})

// ── Report ──────────────────────────────────────────────────────────────────

function report(over: Partial<BazarReport> = {}): BazarReport {
  return {
    event: { id: EVENT, name: 'Zamalek Winter Bazar', status: 'ended', starts_on: '2026-11-20', ends_on: '2026-11-22',
             started_at: null, ended_at: null, location_name: 'Zamalek Bazar' },
    revenue: '62780', money_in: '61940', pieces_sold: 40, pieces_sent: 64, sell_through: '0.62', average_sale: '1336',
    sales: 47, discounts: '0', returns: 3, exchanges: 2,
    sales_by_day: [
      { business_day: '2026-11-20', day_number: 1, sales: 15, revenue: '18080', money_in: '18080', status: 'closed_short' },
      { business_day: '2026-11-21', day_number: 2, sales: 17, revenue: '24100', money_in: '24100', status: 'closed_balanced' },
      { business_day: '2026-11-22', day_number: 3, sales: 15, revenue: '20600', money_in: '19760', status: 'closed_balanced' },
    ],
    busiest_hours: [{ hour: 17, sales: 6 }, { hour: 18, sales: 7 }, { hour: 19, sales: 4 }],
    top_products: [{ product_title: 'Wool cardigan', sold: 8, sent: 10, sell_through: '0.8', revenue: '16000' }],
    top_sizes: [{ product_title: 'Linen shirt', variant_title: 'L', sold: 5, sent: 6, sell_through: '0.83', revenue: '4800' }],
    payment_split: [{ method: 'cash', amount: '31640', share: '0.51' }, { method: 'instapay', amount: '17620', share: '0.28' },
                    { method: 'card', amount: '12680', share: '0.2' }],
    money_not_received: { cash_short: '40', cash_over: '0', card_short: '0', card_over: '0', instapay_not_received: '800',
                          total: '840', days_closed: 3, days_not_closed: 0 },
    per_seller: [{ seller_name: 'Omar', pieces: 19, total: '24300' }],
    customers_saved: 18,
    transfer: { sent: 64, sold: 40, back: 20, damaged: 1, damaged_at_bazar: 0, lost: 2, outstanding: 1,
                transfer_id: 't-1', status: 'closed', balanced: false },
    ...over,
  } as BazarReport
}

describe('Report', () => {
  test('hero in "62,780 EGP" format; lost and still-out pieces show in "where the pieces ended up"', async () => {
    install(on('GET', `/bazar-events/${EVENT}/report`, () => json(200, report())))
    wrap(<Routes><Route path="/bazar/:id/report" element={<BazarReportPage />} /></Routes>, `/bazar/${EVENT}/report`)
    const hero = await screen.findByTestId('kpi-revenue')
    expect(hero.textContent).toContain('62,780 EGP')
    expect(hero.textContent).not.toContain('EGP 62,780')
    expect(hero.textContent).toContain('not received (whole event) 840 EGP')
    expect(screen.getByTestId('kpi-through').textContent).toContain('62%')
    expect(screen.getByTestId('kpi-day').textContent).toContain('20,927')   // 62,780 ÷ 3 days
    const tr = screen.getByTestId('report-transfer')
    expect(tr.textContent).toContain('Where the 64 pieces ended up')
    expect(tr.textContent).toContain('Sent 64 = 40 sold + 20 back + 1 damaged + 2 lost')
    expect(tr.textContent).toContain('1 piece still out')
    expect(screen.getByTestId('report-hours').textContent).toContain('Busiest: 18:00–19:00 · 7 sales')
    expect(screen.getByTestId('report-per-seller').textContent).toContain('19 pieces')
    expect(screen.getByTestId('report-per-seller').textContent).toContain('24,300 EGP')
    expect(screen.getByTestId('report-status').textContent).toBe('Ended · transfer closed')
    expect(screen.getByTestId('report-status').className).not.toMatch(/rounded-full/)
    // Nothing the report doesn't return.
    expect(document.body.textContent).not.toMatch(/Plan next bazar|vs Shopify/)   // U1b added Download PDF + Export orders
  })

  test('no lost / still-out pieces: the transfer card shows no "still out" line', async () => {
    install(on('GET', `/bazar-events/${EVENT}/report`, () => json(200, report({
      money_not_received: { cash_short: '0', cash_over: '0', card_short: '0', card_over: '0', instapay_not_received: '0',
                            total: '0', days_closed: 3, days_not_closed: 0 },
      transfer: { sent: 64, sold: 40, back: 23, damaged: 1, damaged_at_bazar: 0, lost: 0, outstanding: 0,
                  transfer_id: 't-1', status: 'closed', balanced: true } }))))
    wrap(<Routes><Route path="/bazar/:id/report" element={<BazarReportPage />} /></Routes>, `/bazar/${EVENT}/report`)
    const tr = await screen.findByTestId('report-transfer')
    expect(tr.textContent).not.toMatch(/still out/)
    expect(screen.getByTestId('kpi-revenue').textContent).not.toMatch(/not received/)
  })

  test('a failed report load shows the error, nothing else', async () => {
    install(on('GET', `/bazar-events/${EVENT}/report`, () => json(500, { code: 'BOOM' })))
    wrap(<Routes><Route path="/bazar/:id/report" element={<BazarReportPage />} /></Routes>, `/bazar/${EVENT}/report`)
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByTestId('report-kpis')).toBeNull()
  })

  test('Arabic: money stays LTR with Latin digits', async () => {
    await testI18n.changeLanguage('ar')
    install(on('GET', `/bazar-events/${EVENT}/report`, () => json(200, report())))
    wrap(<Routes><Route path="/bazar/:id/report" element={<BazarReportPage />} /></Routes>, `/bazar/${EVENT}/report`)
    const hero = await screen.findByTestId('kpi-revenue')
    const money = hero.querySelector('[dir="ltr"]')!
    expect(money.textContent).toBe('62,780 EGP')
  })
})

