import { test, expect, describe, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'
import type { LabelRunDetail, LabelRunSummary } from '../api'

/**
 * Flexible labeling slice 6c — closing a factory run, and its shortfall report.
 *   fc1  the runs list shows Open / Still coming / Closed and received / expected
 *   fc2  Close run: each line's received of expected, missing and extra; a reason is required for every line with
 *        missing pieces (Close disabled until then); a complete line needs none; the reasons are sent; Still coming
 *        keeps the run open ("Save — keep the run open")
 *   fc3  the server's refusal (live scans in a receiving session) shows in the dialog
 *   fc4  the shortfall report: PDF opens (lang sent), CSV downloads
 *   fc5  a closed run: no Close run / Print; each line's reason; "Closed by …"
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['x']),
  })
}

const SUMMARIES: LabelRunSummary[] = [
  { id: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', status: 'open', createdAt: '2026-10-27T10:00:00Z',
    lines: 3, expected: 9, printed: 9, received: 5, stillComing: false },
  { id: 'run-2', factoryName: 'Delta Knits', reference: null, status: 'open', createdAt: '2026-10-26T10:00:00Z',
    lines: 1, expected: 4, printed: 4, received: 2, stillComing: true },
  { id: 'run-3', factoryName: 'Old Mill', reference: null, status: 'closed', createdAt: '2026-10-20T10:00:00Z',
    lines: 1, expected: 3, printed: 3, received: 3, stillComing: false },
]

function line(id: string, variantId: string, product: string, expected: number, received: number,
              closeReason: LabelRunDetail['lines'][number]['closeReason'] = null) {
  return { id, lineNo: 1, variantId, productTitle: product, variantTitle: 'S', sku: null, imageUrl: null, expected,
    printed: expected, received, unused: Math.max(0, expected - received), voided: 0, overage: 0,
    gap: Math.max(0, expected - received), closeReason }
}

function detail(closed: boolean): LabelRunDetail {
  return {
    id: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', status: closed ? 'closed' : 'open',
    createdAt: '2026-10-27T10:00:00Z', createdBy: 'Mona', expected: 9, printed: 9, received: 5, toPrint: 0,
    lines: [
      line('l1', 'v-a', 'Linen Shirt', 3, 3),
      line('l2', 'v-b', 'Mug', 4, 1, closed ? 'short_shipped' : null),
      line('l3', 'v-c', 'Scarf', 2, 1, closed ? 'lost' : null),
    ],
    parts: [], stillComing: false, closedAt: closed ? '2026-10-28T09:00:00Z' : null, closedBy: closed ? 'Mona' : null,
  }
}

let calls: { method: string; url: string; body?: unknown }[]
let closeAnswer: 'ok' | 'stillOpen' | 'liveScans'
let runClosed: boolean

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : opts.body
  calls.push({ method, url, body })
  if (url.endsWith('/label-runs/run-1/close')) {
    if (closeAnswer === 'liveScans') {
      return json({ code: 'RUN_HAS_LIVE_SCANS', message_en: 'Finalize or undo receiving session «DOCK-4» first',
        message_ar: 'x' }, 409)
    }
    const d = detail(closeAnswer === 'ok')
    if (closeAnswer === 'stillOpen') { d.stillComing = true; d.lines[2].closeReason = 'still_coming'; d.lines[1].closeReason = 'short_shipped' }
    return json({ voided: 3, closed: closeAnswer === 'ok', run: d })
  }
  if (url.includes('/label-runs/run-1/report/')) return json({})
  if (url.endsWith('/label-runs/run-1')) return json(detail(runClosed))
  if (url.endsWith('/label-runs')) return json(SUMMARIES)
  if (url.includes('/receiving/sessions')) return json([])
  if (url.includes('/locations')) return json([])
  return json({})
}

function setup(opts: { closeAnswer?: typeof closeAnswer; runClosed?: boolean } = {}) {
  calls = []
  closeAnswer = opts.closeAnswer ?? 'ok'
  runClosed = opts.runClosed ?? false
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Mona', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:x')
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

async function openRun(user: ReturnType<typeof userEvent.setup>) {
  renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
  await user.click(await screen.findByRole('button', { name: 'Factory runs' }))
  await user.click(await screen.findByTestId('run-row-run-1'))
  return screen.findByTestId('factory-run')
}

describe('Slice 6c — close run + shortfall report', () => {
  test('fc1 — the list shows Open, Still coming and Closed', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByRole('button', { name: 'Factory runs' }))
    expect((await screen.findByTestId('run-status-run-1')).textContent).toBe('Open')
    expect(screen.getByTestId('run-status-run-2').textContent).toBe('Still coming')
    expect(screen.getByTestId('run-status-run-3').textContent).toBe('Closed')
    expect(within(screen.getByTestId('run-row-run-2')).getByText('2 / 4')).toBeTruthy()
  })

  test('fc2 — every line with missing pieces needs a reason; the reasons are sent', async () => {
    setup()
    const user = userEvent.setup()
    await openRun(user)
    await user.click(screen.getByRole('button', { name: 'Close run' }))
    const dialog = await screen.findByTestId('close-run-dialog')
    expect(within(dialog).getByTestId('close-line-v-a').textContent).toContain('All pieces received')
    expect(within(dialog).getByTestId('close-line-v-b').textContent).toContain('1 of 4 received')
    expect(within(dialog).getByTestId('close-line-v-b').textContent).toContain('3 missing')
    const submit = within(dialog).getByRole('button', { name: 'Close run' })
    expect(submit).toBeDisabled()

    await user.click(within(within(dialog).getByTestId('close-line-v-b')).getByLabelText('Short-shipped'))
    expect(submit).toBeDisabled()
    await user.click(within(within(dialog).getByTestId('close-line-v-c')).getByLabelText('Lost'))
    expect(submit).toBeEnabled()
    await user.click(submit)
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/close'))?.body).toEqual({
      lines: [{ lineId: 'l2', reason: 'short_shipped' }, { lineId: 'l3', reason: 'lost' }],
    }))
    expect(await screen.findByText('Factory run closed')).toBeTruthy()
    await waitFor(() => expect(screen.queryByTestId('close-run-dialog')).toBeNull())
    expect(screen.queryByRole('button', { name: 'Close run' })).toBeNull()
  })

  test('fc2b — Still coming keeps the run open', async () => {
    setup({ closeAnswer: 'stillOpen' })
    const user = userEvent.setup()
    await openRun(user)
    await user.click(screen.getByRole('button', { name: 'Close run' }))
    const dialog = await screen.findByTestId('close-run-dialog')
    await user.click(within(within(dialog).getByTestId('close-line-v-b')).getByLabelText('Short-shipped'))
    await user.click(within(within(dialog).getByTestId('close-line-v-c')).getByLabelText('Still coming'))
    await user.click(within(dialog).getByRole('button', { name: 'Save — keep the run open' }))
    expect(await screen.findByText('Saved — the run stays open while pieces are still coming')).toBeTruthy()
    expect(within(screen.getByTestId('factory-run')).getAllByText('Still coming').length).toBeGreaterThan(0)
  })

  test('fc3 — a refusal from the server shows in the dialog', async () => {
    setup({ closeAnswer: 'liveScans' })
    const user = userEvent.setup()
    await openRun(user)
    await user.click(screen.getByRole('button', { name: 'Close run' }))
    const dialog = await screen.findByTestId('close-run-dialog')
    await user.click(within(within(dialog).getByTestId('close-line-v-b')).getByLabelText('Lost'))
    await user.click(within(within(dialog).getByTestId('close-line-v-c')).getByLabelText('Lost'))
    await user.click(within(dialog).getByRole('button', { name: 'Close run' }))
    expect(await within(dialog).findByText('Finalize or undo receiving session «DOCK-4» first')).toBeTruthy()
  })

  test('fc4 — the shortfall report: PDF opens, CSV downloads', async () => {
    setup()
    const user = userEvent.setup()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await openRun(user)
    await user.click(screen.getByRole('button', { name: 'Report (PDF)' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/label-runs/run-1/report/pdf?lang=en'))).toBe(true))
    expect(window.open).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: 'Download CSV' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/label-runs/run-1/report/csv?lang=en'))).toBe(true))
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  })

  test('fc5 — a closed run: no Close run, each reason, closed by', async () => {
    setup({ runClosed: true })
    const user = userEvent.setup()
    const run = await openRun(user)
    expect(within(run).queryByRole('button', { name: 'Close run' })).toBeNull()
    expect(within(run).getByTestId('run-closed-by').textContent).toContain('Closed by Mona')
    expect(within(within(run).getByTestId('run-line-v-b')).getByText('Short-shipped')).toBeTruthy()
    expect(within(within(run).getByTestId('run-line-v-c')).getByText('Lost')).toBeTruthy()
  })
})
