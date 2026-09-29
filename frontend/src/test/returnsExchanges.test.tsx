import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { ReturnCase, ReturnRequestDetail } from '../api'

// Returns & exchanges Step 2 — the new page on GET /returns-exchanges + /counts (mockups E1/E2/E3):
// tiles (hidden at 0, filter + chip), tabs + counts, type filter, debounced search, All grouping,
// Show more (cursor), tone colours, overdue red age, redacted "—", "Not found", every drawer target,
// the alerts' deep link, refresh after a drawer action, empty / error states, Arabic RTL.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

function kase(id: string, over: Partial<ReturnCase> = {}): ReturnCase {
  return {
    caseType: 'A', id, kind: 'refund', reference: `RR-${id.toUpperCase()}`, source: 'returns_page',
    customerName: 'Mariam Saleh', orderNumber: '#1047', stage: 'to_do', nextStep: 'approve', tone: 'action',
    overdueDays: null, status: 'requested',
    reason: {
      bookingStatus: null, bookingError: null, itemsCount: 1, arrivedCount: 0, awaitingCount: 1, candidateCount: 0,
      candidateReferences: null, trackingNumber: null, refundTotal: '0.00', currency: 'EGP', closeReason: null,
      inspectionState: null,
    },
    alerts: [], itemsSummary: 'Linen Shirt White / M', itemsSummaryAr: 'Linen Shirt White / M', notScanned: false,
    updatedAt: new Date().toISOString(), legStatus: null,
    target: { requestId: id, exchangeId: null, shipmentId: null },
    ...over,
  }
}

const CASES: ReturnCase[] = [
  kase('a1', { kind: 'exchange', itemsSummary: 'Linen Shirt White / M → White / L', itemsSummaryAr: 'قميص M ← L' }),
  kase('a2', { nextStep: 'booking_problem', tone: 'problem', status: 'approved',
    reason: { ...kase('x').reason, bookingStatus: 'failed_ambiguous' } }),
  kase('a3', { nextStep: 'record_refund', tone: 'problem', status: 'refund_pending', overdueDays: 6 }),
  kase('b1', { caseType: 'B', kind: 'exchange', reference: '877468285', source: 'bosta', orderNumber: null,
    customerName: 'Yara Farouk', nextStep: 'link_order', status: 'unmatched',
    target: { requestId: null, exchangeId: 'exc-b1', shipmentId: null } }),
  kase('c1', { caseType: 'C', reference: '9371680874', source: 'bosta', customerName: null, orderNumber: '#1012',
    stage: 'in_progress', nextStep: 'on_the_way', tone: 'moving', status: 'with_courier',
    legStatus: { primaryKey: 'status.in_transit', tone: 'INFO' },
    reason: { ...kase('x').reason, inspectionState: 'in_transit' },
    target: { requestId: null, exchangeId: null, shipmentId: 'ship-c1' } }),
  kase('a4', { stage: 'done', nextStep: 'refunded', tone: 'done_good', status: 'refunded',
    reason: { ...kase('x').reason, refundTotal: '850.00' } }),
  kase('a5', { stage: 'done', nextStep: 'closed', tone: 'done_closed', status: 'closed',
    reason: { ...kase('x').reason, closeReason: 'no_refund' } }),
]

const TILE_STEPS: Record<string, string[]> = {
  toApprove: ['approve'], replacementToChoose: ['choose_replacement'], toLinkOrder: ['link_order', 'link_request'],
  refundToRecord: ['record_refund'], bookingProblem: ['booking_problem'],
}

let cases: ReturnCase[]
let pageSize: number | null
let failList: boolean
let calls: Array<{ method: string; url: string }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function filtered(p: URLSearchParams, withStage: boolean) {
  const q = (p.get('q') ?? '').toLowerCase()
  return cases.filter(c =>
    (!withStage || !p.get('stage') || p.get('stage') === 'all' || c.stage === p.get('stage'))
    && (!p.get('type') || c.kind === p.get('type'))
    && (!withStage || !p.get('tile') || TILE_STEPS[p.get('tile')!].includes(c.nextStep))
    && (!q || c.reference.toLowerCase() === q || (c.customerName ?? '').toLowerCase().includes(q)
        || (c.orderNumber ?? '').toLowerCase().includes(q)))
}

const DETAIL: ReturnRequestDetail = {
  id: 'a1', reference: 'RR-A1', orderId: 'o1', orderNumber: '#1047', customerName: 'Mariam Saleh', customerPhone: null,
  type: 'refund', status: 'requested', email: null, note: 'It runs small.', createdAt: new Date().toISOString(),
  deliveredAt: null, pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
  pickupDistrictName: null, pickupDistrictNameAr: null, decidedAt: null, decidedBy: null, decidedByName: null,
  rejectionReason: null, returnShipmentId: null,
  items: [{ id: 'i1', pieceId: 'p1', shortCode: 'P000245', variantId: 'v1', productTitle: 'Linen Shirt',
    variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }],
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url })
  const u = new URL(url, 'http://x')
  if (u.pathname.endsWith('/returns-exchanges/counts')) {
    const all = filtered(u.searchParams, false)
    const tiles = Object.fromEntries(Object.entries(TILE_STEPS).map(([k, steps]) =>
      [k, all.filter(c => steps.includes(c.nextStep)).length]))
    return fakeResponse({
      stages: { all: all.length, to_do: all.filter(c => c.stage === 'to_do').length,
        in_progress: all.filter(c => c.stage === 'in_progress').length, done: all.filter(c => c.stage === 'done').length },
      tiles,
    })
  }
  if (u.pathname.endsWith('/returns-exchanges')) {
    if (failList) return fakeResponse({ message: 'boom' }, 500)
    const rows = filtered(u.searchParams, true)
    const size = pageSize ?? rows.length
    const start = u.searchParams.get('cursor') ? Number(u.searchParams.get('cursor')) : 0
    const items = rows.slice(start, start + size)
    return fakeResponse({ items, nextCursor: start + size < rows.length ? String(start + size) : null })
  }
  if (method === 'POST' && url.endsWith('/approve')) return fakeResponse(null, 204)
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(DETAIL)
  if (url.includes('/exchanges/') && method === 'GET') {
    return fakeResponse({ id: 'exc-b1', tracking_number: '877468285', status: 'unmatched', matched_order_id: null,
      match_method: null, matched_at: null, outbound_description: 'Hat L', inbound_description: 'Hat M',
      inbound_description_ar: null, cod: 0, goods_value: 0, outbound_items_count: 1, inbound_items_count: 1,
      customer_name: 'Yara Farouk', customer_phone: null })
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse({ pickupBooking: false })
  return fakeResponse({})
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  cases = CASES
  pageSize = null
  failList = false
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => {
  await i18n.changeLanguage('en')
  document.documentElement.dir = 'ltr'
})

function render(initialEntries?: string[]) {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>, { initialEntries })
  return user
}

const listCalls = () => calls.filter(c => c.url.includes('/returns-exchanges?'))
const lastList = () => new URL(listCalls()[listCalls().length - 1].url, 'http://x').searchParams
const countCalls = () => calls.filter(c => c.url.includes('/returns-exchanges/counts'))
const rows = () => screen.getAllByTestId('case-row')
const rowOf = (ref: string) => screen.getByText(ref).closest('tr') as HTMLElement

describe('Returns & exchanges page', () => {
  test('title, subtitle, tabs in order with counts; To do badge amber', async () => {
    render()
    expect(await screen.findByRole('heading', { name: 'Returns & exchanges' })).toBeInTheDocument()
    expect(screen.getByText('Everything customers are sending back, from your returns page and from Bosta.')).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab').map(t => t.textContent)
    await waitFor(() => expect(screen.getByTestId('tab-count-all')).toHaveTextContent('7'))
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['All7', 'To do4', 'In progress1', 'Done2'])
    expect(tabs[0]).toMatch(/^All/)
    expect(screen.getByTestId('tab-all')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('tab-count-to_do').className).toContain('bg-[#FDEBD8]')
    expect(screen.getByTestId('tab-count-done').className).toContain('bg-[#EEF0F3]')
  })

  test('tiles hidden at 0; a tile filters the To do tab with a removable chip', async () => {
    const user = render()
    await screen.findByTestId('tiles')
    expect(screen.getByTestId('tile-toApprove')).toHaveTextContent('1To approveCustomer requests')
    expect(screen.getByTestId('tile-bookingProblem').className).toContain('bg-[#FDF3F2]')
    expect(screen.queryByTestId('tile-replacementToChoose')).toBeNull()
    await user.click(screen.getByTestId('tile-refundToRecord'))
    await waitFor(() => expect(lastList().get('tile')).toBe('refundToRecord'))
    expect(lastList().get('stage')).toBe('to_do')
    expect(screen.getByTestId('tab-to_do')).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(screen.getByTestId('tile-chip')).toHaveTextContent('Refund to record')
    await user.click(screen.getByRole('button', { name: 'Remove filter' }))
    await waitFor(() => expect(lastList().get('tile')).toBeNull())
    expect(screen.queryByTestId('tile-chip')).toBeNull()
  })

  test('All groups rows under To do / In progress / Done with counts; other tabs have no headers', async () => {
    const user = render()
    await screen.findByTestId('group-to_do')
    expect(screen.getByTestId('group-to_do')).toHaveTextContent('To do · 4')
    expect(screen.getByTestId('group-in_progress')).toHaveTextContent('In progress · 1')
    expect(screen.getByTestId('group-done')).toHaveTextContent('Done · 2')
    expect(screen.getByTestId('group-to_do').querySelector('td')!.className).toContain('bg-[#FBF7F2]')
    const order = Array.from(document.querySelectorAll('tbody tr')).map(tr =>
      tr.getAttribute('data-testid') === 'case-row' ? tr.getAttribute('data-case-id') : tr.getAttribute('data-testid'))
    expect(order).toEqual(['group-to_do', 'a1', 'a2', 'a3', 'b1', 'group-in_progress', 'c1', 'group-done', 'a4', 'a5'])
    await user.click(screen.getByTestId('tab-done'))
    await waitFor(() => expect(lastList().get('stage')).toBe('done'))
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(screen.queryByTestId('group-done')).toBeNull()
  })

  test('type filter and debounced search apply to the list and the counts', async () => {
    const user = render()
    await screen.findAllByTestId('case-row')
    await user.selectOptions(screen.getByLabelText('Type'), 'exchange')
    await waitFor(() => expect(lastList().get('type')).toBe('exchange'))
    expect(new URL(countCalls()[countCalls().length - 1].url, 'http://x').searchParams.get('type')).toBe('exchange')
    await waitFor(() => expect(rows()).toHaveLength(2))
    await user.selectOptions(screen.getByLabelText('Type'), '')
    await user.type(screen.getByLabelText('Search'), 'yara')
    await waitFor(() => expect(lastList().get('q')).toBe('yara'))
    expect(listCalls().some(c => new URL(c.url, 'http://x').searchParams.get('q') === 'y')).toBe(false)
    expect(new URL(countCalls()[countCalls().length - 1].url, 'http://x').searchParams.get('q')).toBe('yara')
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toHaveTextContent('877468285')
  })

  test('Show more follows the cursor and appends', async () => {
    pageSize = 3
    const user = render()
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(screen.getByTestId('group-to_do')).toHaveTextContent('To do · 4 — showing 3')
    await user.click(screen.getByRole('button', { name: 'Show more' }))
    await waitFor(() => expect(rows()).toHaveLength(6))
    expect(lastList().get('cursor')).toBe('3')
    await user.click(screen.getByRole('button', { name: 'Show more' }))
    await waitFor(() => expect(rows()).toHaveLength(7))
    expect(screen.queryByRole('button', { name: 'Show more' })).toBeNull()
  })

  test('row content: type tag + source, redacted "—", "Not found", tone colours, reasons, overdue red age', async () => {
    render()
    await screen.findAllByTestId('case-row')
    const a1 = rowOf('RR-A1')
    expect(within(a1).getByTestId('type-tag')).toHaveTextContent('Exchange')
    expect(within(a1).getByTestId('type-tag').className).toContain('border-[#D5DAE1]')
    expect(a1).toHaveTextContent('Returns page')
    expect(within(a1).getByTestId('case-customer')).toHaveTextContent('Mariam S.')
    expect(within(a1).getByTestId('case-items')).toHaveTextContent('Linen Shirt White / M → White / L')
    expect(within(a1).getByTestId('case-pill')).toHaveTextContent('Approve or reject')
    expect(within(a1).getByTestId('case-reason')).toHaveTextContent('Replacement in stock')

    const b1 = rowOf('877468285')
    expect(b1).toHaveTextContent('Booked in Bosta')
    expect(within(b1).getByTestId('case-order')).toHaveTextContent('Not found')
    expect(within(b1).getByTestId('case-reason')).toHaveTextContent('No order matched this customer')
    expect(within(rowOf('9371680874')).getByTestId('case-customer')).toHaveTextContent('—')

    const tones: Record<string, string> = {
      'RR-A1': 'bg-[#FDEBD8]', 'RR-A2': 'bg-[#FBE7E5]', '9371680874': 'bg-[#E7EEFF]',
      'RR-A4': 'bg-[#E6F4EC]', 'RR-A5': 'bg-[#EEF0F3]',
    }
    Object.entries(tones).forEach(([ref, cls]) => expect(within(rowOf(ref)).getByTestId('case-pill').className).toContain(cls))
    expect(within(rowOf('RR-A2')).getByTestId('case-pill')).toHaveTextContent('Booking problem')
    expect(within(rowOf('RR-A2')).getByTestId('case-reason')).toHaveTextContent("Bosta didn't confirm — check and retry")
    expect(within(rowOf('RR-A4')).getByTestId('case-pill')).toHaveTextContent('Refunded · EGP 850')
    expect(within(rowOf('RR-A5')).getByTestId('case-pill')).toHaveTextContent('Closed · no refund')

    const overdue = within(rowOf('RR-A3')).getByTestId('case-updated')
    expect(overdue).toHaveTextContent('6 days ago')
    expect(overdue.className).toContain('text-[#B12D25]')
    expect(overdue.className).toContain('font-bold')
    expect(within(rowOf('RR-A3')).getByTestId('case-reason')).toHaveTextContent('Overdue')
    expect(within(a1).getByTestId('case-updated').className).not.toContain('text-[#B12D25]')
  })

  test('an untracked parcel marked received: to do, "Add it in Receiving"', async () => {
    cases = [kase('c2', { caseType: 'C', reference: '6136538746', source: 'bosta', nextStep: 'add_in_receiving',
      status: 'returned', reason: { ...kase('x').reason, inspectionState: 'received_untracked' },
      target: { requestId: null, exchangeId: null, shipmentId: 'ship-c2' } })]
    render()
    const row = (await screen.findAllByTestId('case-row'))[0]
    expect(within(row).getByTestId('case-pill')).toHaveTextContent('Add it in Receiving')
    expect(within(row).getByTestId('case-pill').className).toContain('bg-[#FDEBD8]')
    expect(within(row).getByTestId('case-reason')).toHaveTextContent('Came back without a Traced piece — add it to stock')
    expect(screen.getByTestId('group-to_do')).toHaveTextContent('To do · 1')
  })

  test('every drawer target opens: request, dashboard exchange, courier return', async () => {
    const user = render()
    await screen.findAllByTestId('case-row')
    await user.click(rowOf('RR-A1'))
    const req = await screen.findByTestId('return-request-drawer')
    await within(req).findByText('It runs small.')
    expect(calls.some(c => c.url.endsWith('/return-requests/a1'))).toBe(true)
    await user.click(within(req).getAllByRole('button', { name: 'Close' })[0])

    await user.click(rowOf('877468285'))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/exchanges/exc-b1'))).toBe(true))
    const ex = screen.getByTestId('exchange-refund-drawer')
    expect(ex).toHaveAttribute('aria-hidden', 'false')
    await user.click(within(ex).getByRole('button', { name: 'Close' }))

    await user.click(rowOf('9371680874'))
    const body = await screen.findByTestId('refund-drawer-body')
    expect(within(body).getByText('#1012')).toBeInTheDocument()
    expect(within(body).getByRole('link', { name: 'Open in Scan returns' })).toHaveAttribute('href', '/returns')
  })

  test('deep link ?tab=requests&request=…&parcel=… opens that request drawer', async () => {
    render(['/exchanges?tab=requests&request=a1&parcel=ship-9'])
    const drawer = await screen.findByTestId('return-request-drawer')
    await within(drawer).findByText('It runs small.')
    expect(calls.some(c => c.url.endsWith('/return-requests/a1'))).toBe(true)
  })

  test('a drawer action refreshes the list and the counts', async () => {
    const user = render()
    await screen.findAllByTestId('case-row')
    await user.click(rowOf('RR-A1'))
    const drawer = await screen.findByTestId('return-request-drawer')
    await within(drawer).findByText('It runs small.')
    const lists = listCalls().length, cnts = countCalls().length
    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/a1/approve'))).toBe(true))
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(lists))
    expect(countCalls().length).toBeGreaterThan(cnts)
  })

  test('empty states per tab, filtered empty, and the error state with retry', async () => {
    cases = []
    const user = render()
    expect(await screen.findByTestId('cases-empty')).toHaveTextContent('No returns or exchanges yet')
    expect(screen.queryByTestId('tiles')).toBeNull()
    for (const [tab, text] of [['to_do', 'Nothing to do'], ['in_progress', 'Nothing on the way'], ['done', 'Nothing finished yet']]) {
      await user.click(screen.getByTestId(`tab-${tab}`))
      await waitFor(() => expect(screen.getByTestId('cases-empty')).toHaveTextContent(text))
    }
    await user.selectOptions(screen.getByLabelText('Type'), 'refund')
    await waitFor(() => expect(screen.getByTestId('cases-empty')).toHaveTextContent('Nothing matches these filters'))

    failList = true
    await user.selectOptions(screen.getByLabelText('Type'), '')
    expect(await screen.findByTestId('load-error')).toHaveTextContent("Couldn't load returns and exchanges")
    failList = false
    cases = CASES
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(rows().length).toBeGreaterThan(0))
  })

  test('Arabic: the exchange drawer opens in RTL with Arabic labels', async () => {
    await i18n.changeLanguage('ar')
    const user = render()
    await screen.findAllByTestId('case-row')
    await user.click(rowOf('877468285'))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/exchanges/exc-b1'))).toBe(true))
    const drawer = screen.getByTestId('exchange-refund-drawer')
    await within(drawer).findByTestId('unmatched-actions')
    expect(document.documentElement.dir).toBe('rtl')
    expect(within(drawer).getByRole('button', { name: 'إغلاق' })).toBeInTheDocument()
  })

  test('Arabic: title, tabs, tiles, tags, Arabic items text, "غير معروف"', async () => {
    await i18n.changeLanguage('ar')
    document.documentElement.dir = 'rtl'
    render()
    expect(await screen.findByRole('heading', { name: 'المرتجعات والاستبدال' })).toBeInTheDocument()
    await screen.findAllByTestId('case-row')
    expect(screen.getAllByRole('tab').map(t => t.textContent?.replace(/\d+/g, ''))).toEqual(['الكل', 'مطلوب منك', 'جارية', 'مكتملة'])
    expect(screen.getByTestId('tile-toApprove')).toHaveTextContent('بانتظار موافقتك')
    const a1 = rowOf('RR-A1')
    expect(within(a1).getByTestId('type-tag')).toHaveTextContent('استبدال')
    expect(within(a1).getByTestId('case-items')).toHaveTextContent('قميص M ← L')
    expect(within(a1).getByTestId('case-pill')).toHaveTextContent('وافق أو ارفض')
    expect(within(rowOf('877468285')).getByTestId('case-order')).toHaveTextContent('غير معروف')
    expect(within(rowOf('RR-A3')).getByTestId('case-updated')).toHaveTextContent('منذ 6 أيام')
    expect(a1).toHaveTextContent('صفحة المرتجعات')
  })
})
