import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import App from '../App'
import Inventory from '../pages/Inventory'
import TagStockScan from '../pages/TagStockScan'
import Fulfill from '../pages/Fulfill'
import WaybillPackPage from '../pages/fulfill/WaybillPackPage'

/**
 * Flexible labeling UI pass (2026-10-25).
 * A — Tag stock in Inventory:
 *   ut1  switch off: no "Untagged" tab, no Tag stock request
 *   ut2  "Untagged (N)": select all ticks every row with its default qty; a row's qty is capped at its untagged count;
 *        the bar says "N labels for M products · includes N reprints"; Print sends every ticked product, opens ONE PDF,
 *        then "Start scanning" opens the scan loop with what was printed
 *   ut3  the old /tag-stock URL lands on the Untagged tab
 *   ut4  scan loop: no product picking — a scan shows "code → product / variant", per-product progress counts it,
 *        and "Print 1 replacement label" asks which product
 * B — one "Print" in Pick & Pack:
 *   pp1  switch off: "Print waybills" and its dialog, exactly as before (no "Print")
 *   pp2  switch on, queue: ONE "Print" (no "Print waybills" / "Print labels"); live counts per checkbox; a box with
 *        0 is disabled with its reason; both use the SAME scope + sort; "Ready" opens waybills and labels separately
 *   pp3  waybills only / labels only; nothing ticked → Print disabled
 *   pp4  a waybill failure doesn't block the labels; the error shows on the waybill row
 *   pp5  waybill scan mode: "Print" opens the same dialog and prints labels
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['%PDF']),
  })
}

const pdf = () => Promise.resolve({
  ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/pdf' : null) },
  json: async () => null, blob: async () => new Blob(['%PDF']),
})

let calls: { method: string; url: string; body?: unknown }[]
let routes: (url: string, method: string, body: unknown) => unknown

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  const r = routes(url, method, body)
  if (r === 'pdf') return pdf()
  if (r && typeof r === 'object' && 'status' in (r as object) && 'reply' in (r as object)) {
    const x = r as { status: number; reply: unknown }
    return json(x.reply, x.status)
  }
  return json(r ?? {})
}

function setup(flexibleLabeling: boolean, role: 'owner' | 'worker' = 'owner') {
  calls = []
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  vi.stubGlobal('open', vi.fn())
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

const posted = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.includes(suffix))

// ── A ─────────────────────────────────────────────────────────────────────────

const BLUE = { variantId: 'v-blue', productTitle: 'Hydrogen', variantTitle: 'Default', sku: 'HY-1', imageUrl: null,
  untagged: 4, labeled: 1, unusedLabels: 2, unusedThisSession: 0, reusableLabels: 2 }
const RED = { ...BLUE, variantId: 'v-red', productTitle: 'Helium', variantTitle: 'Red', sku: 'HE-R', untagged: 3,
  unusedLabels: 0, reusableLabels: 0 }
const SESSION = { id: 's1', startedAt: '2026-10-25T10:00:00Z', endedAt: null, tagged: 0, variants: [] as unknown[] }

function tagRoutes(url: string, method: string) {
  if (url.endsWith('/tag-stock') || url.includes('/tag-stock?')) {
    return { labeled: 1, untagged: 7, variants: [BLUE, RED], leftovers: [] }
  }
  if (url.endsWith('/tag-stock/sessions') && method === 'POST') return SESSION
  if (url.endsWith('/tag-stock/sessions/s1/print-many')) {
    return { labels: 7, reprints: 2, issued: 5, groups: [
      { variantId: 'v-blue', productTitle: 'Hydrogen', variantTitle: 'Default', sku: 'HY-1', codeIds: ['a', 'b', 'c', 'd'], reprints: 2 },
      { variantId: 'v-red', productTitle: 'Helium', variantTitle: 'Red', sku: 'HE-R', codeIds: ['e', 'f', 'g'], reprints: 0 }] }
  }
  if (url.endsWith('/tag-stock/sessions/s1/pdf')) return 'pdf'
  if (url.endsWith('/tag-stock/sessions/s1')) return SESSION
  if (url.includes('/inventory/stock')) return { items: [], nextCursor: null }
  if (url.includes('/inventory') || url.includes('/locations') || url.includes('/catalog')) return []
  return {}
}

describe('A — Tag stock in Inventory', () => {
  test('ut1 — switch off: no Untagged tab and no Tag stock request', async () => {
    setup(false)
    routes = tagRoutes
    renderWithProviders(<Inventory />)
    await screen.findByText('Stock by location')
    expect(screen.queryByText(/^Untagged/)).toBeNull()
    expect(calls.some(c => c.url.includes('/tag-stock'))).toBe(false)
  })

  test('ut2 — select all, qty cap, the bar, one print, then Start scanning', async () => {
    setup(true)
    routes = tagRoutes
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/inventory" element={<Inventory />} />
        <Route path="/tag-stock/:id/scan" element={<TagStockScan />} />
      </Routes>, { initialEntries: ['/inventory?tab=untagged'] })
    await screen.findByRole('button', { name: 'Untagged (7)' })
    const blue = await screen.findByTestId('untagged-row-v-blue')
    expect(blue.textContent).toContain('4 untagged · 1 labeled')
    expect(within(blue).getByTestId('unused-note').textContent).toContain('2 unused labels')
    expect(within(screen.getByTestId('untagged-row-v-red')).queryByTestId('unused-note')).toBeNull()

    await user.click(screen.getByText('Select all'))
    expect((screen.getByTestId('untagged-qty-v-blue') as HTMLInputElement).value).toBe('4')
    expect((screen.getByTestId('untagged-qty-v-red') as HTMLInputElement).value).toBe('3')
    const bar = screen.getByTestId('untagged-bar')
    expect(bar.textContent).toContain('7 labels for 2 products')
    expect(within(bar).getByTestId('untagged-reprints').textContent).toBe('includes 2 reprints')

    const red = screen.getByTestId('untagged-qty-v-red') as HTMLInputElement
    await user.clear(red)
    await user.type(red, '9')
    expect(red.value).toBe('3')                                              // capped at its untagged count
    await user.clear(red)
    await user.type(red, '1')
    expect(bar.textContent).toContain('5 labels for 2 products')

    await user.click(within(bar).getByRole('button', { name: 'Print labels' }))
    await waitFor(() => expect(posted('/print-many')).toHaveLength(1))
    expect(posted('/print-many')[0].body).toMatchObject({
      lines: [{ variantId: 'v-blue', count: 4 }, { variantId: 'v-red', count: 1 }] })
    await waitFor(() => expect(posted('/tag-stock/sessions/s1/pdf')).toHaveLength(1))       // ONE PDF
    expect(window.open).toHaveBeenCalledWith('blob:x', '_blank')

    await user.click(await screen.findByRole('button', { name: 'Start scanning' }))
    const progress = await screen.findByTestId('tag-progress')
    expect(progress.textContent).toContain('Hydrogen / Default')
    expect(progress.textContent).toContain('0 / 4')
    expect(progress.textContent).toContain('Helium / Red')
  })

  test('ut3 — the old /tag-stock URL lands on the Untagged tab', async () => {
    setup(true)
    routes = tagRoutes
    window.history.pushState({}, '', '/tag-stock')
    render(<App />)
    await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/inventory?tab=untagged'))
    expect(await screen.findByTestId('untagged-tab')).toBeTruthy()
    window.history.pushState({}, '', '/')
  })

  test('ut4 — scan loop: no product picking; "code → product"; progress; replacement asks which product', async () => {
    setup(true)
    let sessionNow = { ...SESSION, variants: [] as unknown[] }
    routes = (url, method) => {
      if (url.endsWith('/tag-stock/sessions/s1/scan')) {
        sessionNow = { ...SESSION, tagged: 1, variants: [{ variantId: 'v-blue', productTitle: 'Hydrogen', variantTitle: 'Default',
          sku: 'HY-1', imageUrl: null, printed: 4, tagged: 1, unused: 3, untaggedLeft: 3, labeled: 2 }] }
        return { outcome: 'TAGGED', code: 'P000123', messageEn: null, messageAr: null, variant: BLUE, taggedThisSession: 1, viaPhone: false }
      }
      if (url.endsWith('/tag-stock/sessions/s1/replacement')) return { id: 'b9', kind: 'replacement', count: 1, created: true, codes: [] }
      if (url.includes('/label-codes/batches/b9/pdf')) return 'pdf'
      if (url.endsWith('/tag-stock/sessions/s1') && method === 'GET') return sessionNow
      return tagRoutes(url, method)
    }
    const user = userEvent.setup()
    renderWithProviders(
      <Routes><Route path="/tag-stock/:id/scan" element={<TagStockScan />} /></Routes>,
      { initialEntries: [{ pathname: '/tag-stock/s1/scan', state: { printed: [
        { variantId: 'v-blue', productTitle: 'Hydrogen', variantTitle: 'Default', count: 4 },
        { variantId: 'v-red', productTitle: 'Helium', variantTitle: 'Red', count: 3 }] } }] })
    await screen.findByTestId('tag-stock-scan')
    expect(screen.queryByRole('button', { name: /change product/i })).toBeNull()
    expect(screen.queryByTestId('tag-current-variant')).toBeNull()

    const input = screen.getByPlaceholderText(/scan the label/i)
    await user.type(input, 'P000123{Enter}')
    const lastScan = await screen.findByTestId('tag-last-scan')
    await waitFor(() => expect(lastScan.textContent).toContain('P000123→Hydrogen / Default'))
    expect(screen.getByTestId('tagged-count').textContent).toBe('1')
    await waitFor(() => expect(screen.getByTestId('tag-progress-v-blue').textContent).toContain('1 / 4'))

    await user.click(screen.getByRole('button', { name: 'Print 1 replacement label' }))
    const picker = await screen.findByTestId('tag-replacement-picker')
    expect(within(picker).getByRole('button', { name: 'Print' })).toBeDisabled()
    await user.click(within(picker).getByText('Helium / Red'))
    await user.click(within(picker).getByRole('button', { name: 'Print' }))
    await waitFor(() => expect(posted('/replacement')).toHaveLength(1))
    expect(posted('/replacement')[0].body).toMatchObject({ variantId: 'v-red' })
  })
})

// ── B ─────────────────────────────────────────────────────────────────────────

const QUEUE = [
  { id: 'o1', number: '#101', customer_name: 'Mona', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 2, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, awb_printed: false },
  { id: 'o2', number: '#102', customer_name: 'Omar', status: 'new', payment_method: 'cod', cod_amount: '100',
    total_units: 1, scanned_units: 0, locked_by: null, locked_at: null, is_self_pickup: false, awb_printed: false },
]
const LABELS = { labels: 3, orders: 2, reused: 0, unitsWithoutLabel: 0, ordersConsidered: 2,
  parts: [{ part: 1, of: 1, labels: 3, orderIds: ['o1', 'o2'] }] }
const JOB = { jobId: 'job-1', kind: 'print', status: 'done', total: 2, done: 2, candidateCount: 2, remainingCount: 0,
  printedCount: 2, excluded: [], batchId: 'batch-1', batchNo: 1, reprintBatchId: null, reprintBatchNo: null,
  orderGuaranteed: true, pdfAvailable: true, pdfBytes: 10, message: null, createdAt: '2026-10-25T10:00:00Z',
  finishedAt: '2026-10-25T10:00:05Z', expiresAt: null }

let plan: { waybills: number; lastBatchId: string | null; labels: typeof LABELS }
let waybillStart: () => unknown

function packRoutes(url: string, method: string) {
  if (url.endsWith('/fulfill/queue')) return QUEUE
  if (url.endsWith('/pack-labels/plan')) return plan
  if (url.endsWith('/pack-labels/print')) return plan.labels
  if (url.endsWith('/pack-labels/pdf')) return 'pdf'
  if (url.endsWith('/fulfill/print-batches/options')) return { defaultPaper: 'A6' }
  if (url.endsWith('/fulfill/print-jobs') && method === 'POST') return waybillStart()
  if (url.endsWith('/fulfill/print-jobs/latest')) return null
  if (url.includes('/fulfill/print-jobs/job-1/pdf')) return 'pdf'
  if (url.endsWith('/fulfill/print-jobs/job-1')) return JOB
  if (url.includes('/pack-sessions/summary')) return { packedToday: 0, openSessionId: null }
  return {}
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Print' }))
  return await screen.findByTestId('print-for-packing')
}

describe('B — one Print in Pick & Pack', () => {
  beforeEach(() => {
    plan = { waybills: 2, lastBatchId: 'batch-0', labels: LABELS }
    waybillStart = () => ({ status: 202, reply: { jobId: 'job-1' } })
  })

  test('pp1 — switch off: "Print waybills" and its dialog, as before', async () => {
    setup(false)
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await user.click(await screen.findByRole('button', { name: 'Print waybills' }))
    expect(await screen.findByTestId('print-batch-dialog')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Print' })).toBeNull()
    expect(screen.queryByTestId('print-for-packing')).toBeNull()
    expect(calls.some(c => c.url.includes('/pack-labels'))).toBe(false)
  })

  test('pp2 — one Print; counts; both use the same scope + sort; Ready opens them separately', async () => {
    setup(true)
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await screen.findByText('#101')
    expect(screen.queryByRole('button', { name: 'Print waybills' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Print labels' })).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Labels' }).length).toBeGreaterThan(0)    // the per-row button stays

    const dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-what-waybills').textContent).toContain('Waybills — 2 (Bosta orders)'))
    expect(within(dialog).getByTestId('print-what-labels').textContent).toContain('Piece labels — 3 for 2 orders')
    expect(posted('/pack-labels/plan').at(-1)!.body).toEqual({ scope: 'new', sort: 'oldest' })

    await user.click(within(dialog).getByText('Everything ready to pack'))
    await user.click(within(dialog).getByRole('button', { name: 'Newest order first' }))
    await waitFor(() => expect(posted('/pack-labels/plan').at(-1)!.body).toEqual({ scope: 'all', sort: 'newest' }))
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))

    const ready = await screen.findByTestId('print-ready')
    const job = posted('/fulfill/print-jobs')
    expect(job).toHaveLength(1)
    expect(job[0].body).toEqual({ kind: 'print', scope: 'all', paper: 'A6', sort: 'newest' })
    expect(posted('/pack-labels/print')[0].body).toEqual({ scope: 'all', sort: 'newest' })
    expect(within(ready).getByTestId('print-ready-waybills').textContent).toContain('Waybills (A6)')
    await user.click(within(ready).getByRole('button', { name: 'Open labels (50×25) · 3' }))
    await waitFor(() => expect(posted('/pack-labels/pdf')[0].body).toEqual({ orderIds: ['o1', 'o2'] }))
    await waitFor(() => expect(within(screen.getByTestId('print-ready-waybills')).getByRole('button', { name: /open waybills/i })).toBeTruthy())
  })

  test('pp2b — "Same orders as the last print": a reprint of the last waybill print + labels for scope last', async () => {
    setup(true)
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    const dialog = await openDialog(user)
    await user.click(within(dialog).getByText('Same orders as the last print'))
    await waitFor(() => expect(posted('/pack-labels/plan').at(-1)!.body).toMatchObject({ scope: 'last' }))
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await screen.findByTestId('print-ready')
    expect(posted('/fulfill/print-jobs')[0].body).toEqual({ kind: 'reprint', batchId: 'batch-0' })
    expect(posted('/pack-labels/print')[0].body).toMatchObject({ scope: 'last' })
  })

  test('pp3 — a box with 0 is disabled with its reason; waybills only / labels only; nothing → Print disabled', async () => {
    setup(true)
    plan = { waybills: 0, lastBatchId: null, labels: LABELS }
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    let dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-reason-waybills').textContent).toBe('No Bosta waybills in these orders'))
    const waybillBox = within(within(dialog).getByTestId('print-what-waybills')).getByRole('checkbox')
    expect(waybillBox).toBeDisabled()
    // Labels only.
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await screen.findByTestId('print-ready')
    expect(posted('/fulfill/print-jobs')).toHaveLength(0)
    expect(posted('/pack-labels/print')).toHaveLength(1)
    expect(screen.queryByTestId('print-ready-waybills')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Done' }))

    // Waybills only: untick labels.
    plan = { waybills: 2, lastBatchId: null, labels: { ...LABELS, labels: 0, orders: 0, parts: [] } }
    dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-reason-labels').textContent).toBe('No untagged pieces in these orders'))
    const before = posted('/pack-labels/print').length
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await screen.findByTestId('print-ready')
    expect(posted('/fulfill/print-jobs')).toHaveLength(1)
    expect(posted('/pack-labels/print')).toHaveLength(before)
    await user.click(screen.getByRole('button', { name: 'Done' }))

    // No labels in this scope, but "Everything ready to pack" has some → the reason says so.
    plan = { waybills: 2, lastBatchId: null, labels: { ...LABELS, labels: 0, orders: 0, parts: [], queueNeedsLabels: true } as typeof LABELS }
    dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-reason-labels').textContent).toBe(
      "No untagged pieces in these orders — choose 'Everything ready to pack' to include all orders in the queue."))
    expect(within(within(dialog).getByTestId('print-what-labels')).getByRole('checkbox')).toBeDisabled()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    // Nothing ticked → Print disabled.
    plan = { waybills: 2, lastBatchId: null, labels: LABELS }
    dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-what-labels').textContent).toContain('Piece labels — 3'))
    await user.click(within(within(dialog).getByTestId('print-what-waybills')).getByRole('checkbox'))
    await user.click(within(within(dialog).getByTestId('print-what-labels')).getByRole('checkbox'))
    expect(within(dialog).getByRole('button', { name: 'Print' })).toBeDisabled()
  })

  test('pp4 — a waybill failure does not block the labels; the error is on the waybill row', async () => {
    setup(true)
    waybillStart = () => ({ status: 409, reply: { code: 'COURIER_NOT_CONNECTED', message_en: 'Connect Bosta first.', message_ar: 'x' } })
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    const dialog = await openDialog(user)
    await waitFor(() => expect(within(dialog).getByTestId('print-what-labels').textContent).toContain('Piece labels — 3'))
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    const ready = await screen.findByTestId('print-ready')
    expect(within(ready).getByTestId('print-ready-waybills').textContent).toMatch(/Connect Bosta first|went wrong|Couldn/i)
    expect(posted('/pack-labels/print')).toHaveLength(1)
    expect(within(ready).getByRole('button', { name: 'Open labels (50×25) · 3' })).toBeTruthy()
  })

  test('pp5 — waybill scan mode: "Print" opens the same dialog and prints labels', async () => {
    setup(true)
    routes = packRoutes
    const user = userEvent.setup()
    renderWithProviders(<WaybillPackPage />)
    await screen.findByTestId('waybill-pack-page')
    expect(screen.queryByRole('button', { name: 'Print waybills' })).toBeNull()
    const dialog = await openDialog(user)
    await user.click(within(within(dialog).getByTestId('print-what-waybills')).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await screen.findByTestId('print-ready')
    expect(posted('/pack-labels/print')).toHaveLength(1)
    expect(posted('/fulfill/print-jobs')).toHaveLength(0)
  })
})
