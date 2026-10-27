import { test, expect, describe, vi, afterEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import * as api from '../api'
import Receiving from '../pages/Receiving'
import FactoryReceivingScan from '../pages/receiving/FactoryReceivingScan'
import LookupPage from '../pages/Lookup'

/**
 * Flexible labeling slice 6b — factory receiving.
 *   fb1  switch off: no chooser, so no "Factory-labeled" card (today's form)
 *   fb2  switch on: a THIRD card; it leads to the run picker (OPEN runs only: factory · reference · received / expected),
 *        the location locked to the main warehouse, no supplier field; creating sends mode factory + the run
 *   fb3  an open factory session: the run, per-product progress, Finish disabled with nothing scanned, "Scan pieces"
 *        opens the full-screen loop
 *   fb4  the scan loop: a staged scan shows "code → product / variant" and the progress; a refusal shows its message;
 *        Undo last scan; "Piece without a label" asks which product, prints 1 and opens it
 *   fb5  a finished factory session: "Print session labels (N)" is the logged reprint — no plain print
 *   fb6  Lookup: "Received from factory run «factory · reference»"
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['%PDF']),
  })
}

const RUNS: api.LabelRunSummary[] = [
  { id: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', status: 'open', createdAt: '2026-10-27T10:00:00Z',
    lines: 2, expected: 9, printed: 9, received: 2 },
  { id: 'run-2', factoryName: 'Old Mill', reference: null, status: 'closed', createdAt: '2026-10-20T10:00:00Z',
    lines: 1, expected: 3, printed: 3, received: 3 },
]

function progress(staged: number): api.FactoryProgress {
  return {
    runId: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', runStatus: 'open', staged,
    lines: [
      { lineId: 'l1', lineNo: 1, variantId: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'S', sku: 'SHIRT-S',
        imageUrl: null, expected: 6, received: 2, staged },
      { lineId: 'l2', lineNo: 2, variantId: 'v-mug', productTitle: 'Mug', variantTitle: 'Default Title', sku: 'MUG-1',
        imageUrl: null, expected: 3, received: 0, staged: 0 },
    ],
  }
}

let calls: { method: string; url: string; body?: unknown }[]
let staged: number
let finalized: boolean

function session() {
  return {
    id: 'rs-1', status: finalized ? 'finalized' : 'open', mode: 'factory', reference: 'PO-7', supplier_name: 'Nile Textiles',
    note: null, label_run_id: 'run-1', location_id: 'loc-main', location_name: 'Main', received_by: null,
    created_at: '2026-10-27T11:00:00Z', finalized_at: finalized ? '2026-10-27T12:00:00Z' : null, piece_count: finalized ? 3 : 0,
    lines: finalized ? [{ id: 'rl1', variant_id: 'v-shirt', variant_title: 'S', sku: 'SHIRT-S', product_title: 'Linen Shirt',
      quantity: 3, piece_count: 3 }] : [],
    factory: progress(finalized ? 0 : staged),
  }
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : opts.body
  calls.push({ method, url, body })
  if (url.endsWith('/factory/scan')) {
    if (body.code === 'P000101') {
      staged += 1
      return json({ outcome: 'STAGED', code: 'P000101', messageEn: null, messageAr: null, line: progress(staged).lines[0],
        overage: false, progress: progress(staged), viaPhone: false })
    }
    return json({ outcome: 'REJECTED:ALREADY_RECEIVED', code: body.code, messageEn: 'Already received — this piece is in stock',
      messageAr: 'x', line: null, overage: false, progress: progress(staged), viaPhone: false })
  }
  if (url.endsWith('/factory/undo')) { staged -= 1; return json({ code: 'P000101', progress: progress(staged) }) }
  if (url.endsWith('/factory/labels') && method === 'POST') {
    return json({ codeId: 'c9', code: 'P000150', overage: false, productTitle: 'Mug', variantTitle: 'Default Title' })
  }
  if (url.includes('/factory/labels/c9/pdf')) return json({})
  if (url.endsWith('/receiving/sessions/rs-1/reprint')) return json({})
  if (url.endsWith('/receiving/sessions') && method === 'POST') return json({ sessionId: 'rs-1' }, 201)
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/rs-1')) return json(session())
  if (url.endsWith('/label-runs')) return json(RUNS)
  if (url.includes('/locations')) return json([{ id: 'loc-other', name: 'Shop', is_fulfillment: false },
                                               { id: 'loc-main', name: 'Main', is_fulfillment: true }])
  if (url.includes('/label-settings')) return json({ receivingDefaultMode: 'print_stick' })
  return json({})
}

function setup(flexibleLabeling: boolean, opts: { finalized?: boolean } = {}) {
  calls = []
  staged = 0
  finalized = opts.finalized ?? false
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Mona', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:pdf')
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function routes() {
  return (
    <Routes>
      <Route path="/receiving" element={<Receiving />} />
      <Route path="/receiving/:id/scan" element={<FactoryReceivingScan />} />
    </Routes>
  )
}

describe('Slice 6b — factory receiving', () => {
  test('fb1 — switch off: no chooser, no factory card', async () => {
    setup(false)
    renderWithProviders(routes(), { initialEntries: ['/receiving'] })
    await userEvent.click(await screen.findByRole('button', { name: '+ New Session' }))
    expect(await screen.findByRole('heading', { name: 'New Receiving Session' })).toBeTruthy()
    expect(screen.queryByTestId('receiving-mode-factory')).toBeNull()
    expect(calls.some(c => c.url.includes('/label-runs'))).toBe(false)
  })

  test('fb2 — the factory card leads to the open-run picker; create sends the run', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(routes(), { initialEntries: ['/receiving'] })
    await user.click(await screen.findByRole('button', { name: '+ New Session' }))
    const card = await screen.findByTestId('receiving-mode-factory')
    expect(card.textContent).toContain('Pieces arrived with Traced labels from the factory. Scan each piece — only scanned pieces enter stock.')
    expect(within(card).queryByText('Your default')).toBeNull()
    await user.click(card)

    const picker = await screen.findByTestId('factory-run-picker')
    await within(picker).findByTestId('factory-run-option-run-1')
    expect(within(picker).getByText('Nile Textiles')).toBeTruthy()
    expect(within(picker).getByText('2 / 9')).toBeTruthy()
    expect(within(picker).queryByText('Old Mill')).toBeNull()                         // closed runs aren't offered
    expect(screen.getByTestId('count-only-location').textContent).toBe('Main')       // locked to the main warehouse
    expect(screen.getByText('Factory-labeled pieces go to the main warehouse.')).toBeTruthy()
    expect(screen.queryByPlaceholderText('Supplier name')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Create Session' }))
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/receiving/sessions'))?.body).toEqual({
      locationId: 'loc-main', reference: null, supplierName: null, note: null, mode: 'factory', labelRunId: 'run-1',
    }))
    expect(await screen.findByTestId('factory-session')).toBeTruthy()
  })

  test('fb3 — an open factory session shows the run and opens the scan loop', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(routes(), { initialEntries: ['/receiving?session=rs-1'] })
    const panel = await screen.findByTestId('factory-session')
    expect(within(panel).getByText('Nile Textiles · PO-7')).toBeTruthy()
    expect(within(panel).getByTestId('factory-line-v-shirt').textContent).toContain('2 / 6')
    expect(within(panel).getByRole('button', { name: 'Finish · add 0 to stock' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Import from Excel/ })).toBeNull()
    await user.click(within(panel).getByRole('button', { name: 'Scan pieces' }))
    expect(await screen.findByTestId('factory-scan')).toBeTruthy()
  })

  test('fb4 — scan, refusal, undo and a piece without a label', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(routes(), { initialEntries: ['/receiving/rs-1/scan'] })
    const input = await screen.findByPlaceholderText('Scan a factory label…')
    await user.type(input, 'P000101{Enter}')
    await waitFor(() => expect(screen.getByTestId('factory-staged').textContent).toBe('1'))
    expect(screen.getByTestId('factory-last-scan').textContent).toContain('P000101→Linen Shirt / S')
    expect(screen.getByTestId('factory-line-v-shirt').textContent).toContain('3 / 6')

    await user.type(input, 'P000077{Enter}')
    await waitFor(() => expect(screen.getByTestId('factory-last-scan').textContent).toContain('Already received — this piece is in stock'))

    await user.click(screen.getByRole('button', { name: 'Undo last scan' }))
    await waitFor(() => expect(screen.getByTestId('factory-staged').textContent).toBe('0'))
    expect(screen.getByText('Scan of P000101 undone')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Piece without a label' }))
    const picker = await screen.findByTestId('factory-extra-picker')
    await user.click(within(picker).getByText('Mug / Default Title'))
    await user.click(within(picker).getByRole('button', { name: 'Print 1 label' }))
    await waitFor(() => expect(calls.filter(c => c.url.includes('/factory/labels/c9/pdf'))).toHaveLength(1))
    expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/factory/labels'))?.body).toMatchObject({ variantId: 'v-mug' })
    expect(await screen.findByText('Label P000150 is ready — stick it on the piece, then scan it.')).toBeTruthy()
    expect(window.open).toHaveBeenCalledTimes(1)
  })

  test('fb5 — a finished factory session prints its labels only as a logged reprint', async () => {
    setup(true, { finalized: true })
    const user = userEvent.setup()
    renderWithProviders(routes(), { initialEntries: ['/receiving?session=rs-1'] })
    const print = await screen.findByRole('button', { name: 'Print session labels (3)' })
    expect(screen.queryByRole('button', { name: /^Print Labels/ })).toBeNull()
    await user.click(print)
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && c.url.endsWith('/receiving/sessions/rs-1/reprint'))).toHaveLength(1))
    expect(calls.some(c => c.url.endsWith('/receiving/sessions/rs-1/labels'))).toBe(false)
  })
})

test('fb6 — Lookup names the factory run a piece was received from', async () => {
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(() => Promise.reject(new Error('unexpected fetch'))),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
  vi.spyOn(api, 'lookup').mockResolvedValue({
    type: 'piece', id: 'piece-1', barcode: 'PC-1', status: 'available', receivedAt: new Date().toISOString(),
    variant: { id: 'v1', productTitle: 'Linen Shirt', title: 'S', sku: 'SHIRT-S' },
    currentLocation: { id: 'l', name: 'Main' }, currentOrder: null, currentShipment: null, receivingSession: null,
    timeline: [{
      id: 1, eventType: 'received', phraseKey: 'received_factory_run', actor: 'Mona', isSystem: false,
      fromStatus: null, toStatus: 'available', orderNumber: null, orderId: null, trackingNumber: null,
      locationName: 'Main', metadata: null, occurredAt: new Date().toISOString(), factoryRun: 'Nile Textiles · PO-7',
    }],
  } as api.PieceLookupResult)
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), 'P000101{Enter}')
  expect(await screen.findByText('Received from factory run «Nile Textiles · PO-7»')).toBeTruthy()
})
