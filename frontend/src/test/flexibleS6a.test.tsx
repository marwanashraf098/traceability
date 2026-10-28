import { test, expect, describe, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'

/**
 * Flexible labeling slice 6a — Receiving › Factory runs.
 *   fr1  switch off: no tabs, no /label-runs call
 *   fr2  switch on: the "Factory runs" tab lists runs (factory, reference, received / expected)
 *   fr3  create with the product picker: the run's lines are the picked quantities; a factory name is required
 *   fr4  create from Excel: "On hand (new)" fills the expected quantities (the runs preview endpoint, no apply call)
 *   fr5  a run: Print labels issues + opens the PDF; afterwards Reprint opens the same part, never prints again
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

const CATALOG = {
  products: [
    { id: 'p-shirt', title: 'Linen Shirt', status: 'active', imageUrl: null,
      variants: [{ id: 'v-shirt-s', title: 'S', sku: 'SHIRT-S', price: 300 }, { id: 'v-shirt-m', title: 'M', sku: 'SHIRT-M', price: 300 }] },
    { id: 'p-mug', title: 'Mug', status: 'active', imageUrl: null,
      variants: [{ id: 'v-mug', title: 'Default Title', sku: 'MUG-1', price: 90 }] },
  ],
  nextCursor: null,
}

const SUMMARY = { id: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', status: 'open', createdAt: '2026-10-27T10:00:00Z',
  lines: 2, expected: 9, printed: 0, received: 0 }

function detail(printed: boolean) {
  return {
    id: 'run-1', factoryName: 'Nile Textiles', reference: 'PO-7', status: 'open', createdAt: '2026-10-27T10:00:00Z',
    createdBy: 'Mona', expected: 9, printed: printed ? 9 : 0, received: 0, toPrint: printed ? 0 : 9,
    lines: [
      { id: 'l1', lineNo: 1, variantId: 'v-shirt-s', productTitle: 'Linen Shirt', variantTitle: 'S', sku: 'SHIRT-S', imageUrl: null,
        expected: 6, printed: printed ? 6 : 0, received: 0, unused: printed ? 6 : 0, voided: 0, overage: 0 },
      { id: 'l2', lineNo: 2, variantId: 'v-mug', productTitle: 'Mug', variantTitle: 'Default Title', sku: 'MUG-1', imageUrl: null,
        expected: 3, printed: printed ? 3 : 0, received: 0, unused: printed ? 3 : 0, voided: 0, overage: 0 },
    ],
    parts: printed ? [{ part: 1, labels: 9, groups: [
      { productTitle: 'Linen Shirt', variantTitle: 'S', sku: 'SHIRT-S', labels: 6 },
      { productTitle: 'Mug', variantTitle: 'Default Title', sku: 'MUG-1', labels: 3 }] }] : [],
  }
}

let calls: { method: string; url: string; body?: unknown }[]
let printed: boolean

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : opts.body
  calls.push({ method, url, body })
  if (url.includes('/label-runs/import/preview')) {
    return json({ location: 'Main', units: 12, skipped: 0, quantityColumn: 'new', newColumnEmpty: false,
      matched: [{ rowNumber: 2, variantId: 'v-shirt-s', productTitle: 'Linen Shirt', variantTitle: 'S', sku: 'SHIRT-S',
        imageUrl: null, quantity: 12, onHandCurrent: 40, sameAsShopify: false, matchedBy: 'sku' }],
      unmatched: [{ rowNumber: 3, handle: null, title: 'Mug', options: 'Default Title', sku: 'MUG-1', value: '20001', reason: 'too_large' }] })
  }
  if (url.endsWith('/label-runs') && method === 'POST') return json({ ...detail(false), id: 'run-1' }, 201)
  if (url.endsWith('/label-runs')) return json([SUMMARY])
  if (url.endsWith('/label-runs/run-1/print')) { printed = true; return json({ issued: 9, run: detail(true) }) }
  if (url.includes('/label-runs/run-1/pdf')) return json({})
  if (url.endsWith('/label-runs/run-1')) return json(detail(printed))
  if (url.includes('/catalog')) return json(CATALOG)
  if (url.includes('/receiving/sessions')) return json([])
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main', is_fulfillment: true }])
  if (url.includes('/label-settings')) return json({ receivingDefaultMode: 'print_stick' })
  return json({})
}

function setup(flexibleLabeling: boolean) {
  calls = []
  printed = false
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'owner' }))}.s`)
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'Mona', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
  vi.stubGlobal('open', vi.fn())
  URL.createObjectURL = vi.fn(() => 'blob:pdf')
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function render() {
  renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
}

async function openRunsTab(user: ReturnType<typeof userEvent.setup>) {
  render()
  await user.click(await screen.findByRole('button', { name: 'Factory runs' }))
  return screen.findByTestId('factory-runs')
}

describe('Slice 6a — Factory runs tab', () => {
  test('fr1 — switch off: no tabs and no runs call', async () => {
    setup(false)
    render()
    await screen.findByRole('heading', { name: 'Receiving' })
    await new Promise(r => setTimeout(r, 30))
    expect(screen.queryByRole('button', { name: 'Factory runs' })).toBeNull()
    expect(calls.some(c => c.url.includes('/label-runs'))).toBe(false)
  })

  test('fr2 — switch on: the tab lists runs', async () => {
    setup(true)
    const user = userEvent.setup()
    const list = await openRunsTab(user)
    const row = await within(list).findByTestId('run-row-run-1')
    expect(within(row).getByText('Nile Textiles')).toBeTruthy()
    expect(within(row).getByText('PO-7')).toBeTruthy()
    expect(within(row).getByText('0 / 9')).toBeTruthy()
    expect(within(row).getByText('Open')).toBeTruthy()
  })

  test('fr3 — create with the product picker; the factory name is required', async () => {
    setup(true)
    const user = userEvent.setup()
    await openRunsTab(user)
    await user.click(screen.getByRole('button', { name: '+ New factory run' }))
    await screen.findByTestId('factory-run-create')

    await user.click(await screen.findByTestId('product-card-p-mug'))
    await screen.findByTestId('variant-modal-body')
    await user.click(within(screen.getByTestId('variant-row-v-mug')).getByRole('checkbox'))
    const input = screen.getByTestId('qty-input-v-mug')
    await waitFor(() => expect(input).toBeEnabled())
    await user.type(input, '40')
    fireEvent.blur(input)
    await user.click(screen.getByRole('button', { name: 'Done' }))

    const submit = await screen.findByRole('button', { name: 'Create run · 40 pieces' })
    await user.click(submit)
    expect(await screen.findByText('Enter the factory name')).toBeTruthy()
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/label-runs'))).toBe(false)

    await user.type(screen.getByTestId('run-factory'), 'Nile Textiles')
    await user.type(screen.getByTestId('run-reference'), 'PO-7')
    await user.click(submit)
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/label-runs'))?.body).toEqual({
      factoryName: 'Nile Textiles', reference: 'PO-7', lines: [{ variantId: 'v-mug', expectedQty: 40 }],
    }))
    expect(await screen.findByTestId('factory-run')).toBeTruthy()
  })

  test('fr4 — create from Excel: On hand (new) becomes the expected quantity', async () => {
    setup(true)
    const user = userEvent.setup()
    await openRunsTab(user)
    await user.click(screen.getByRole('button', { name: '+ New factory run' }))
    await user.click(await screen.findByRole('button', { name: /Import from Excel/ }))
    expect(screen.getByText("Type how many pieces you expect from the factory in the 'On hand (new)' column.")).toBeTruthy()
    await user.upload(screen.getByTestId('import-file'), new File(['x'], 'factory.xlsx'))
    expect(await screen.findByTestId('import-row-2')).toBeTruthy()
    expect(screen.queryByTestId('same-as-shopify')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Add 12 pieces to the run' }))
    await waitFor(() => expect(screen.queryByTestId('import-panel')).toBeNull())

    await user.type(screen.getByTestId('run-factory'), 'Delta')
    await user.click(await screen.findByRole('button', { name: 'Create run · 12 pieces' }))
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/label-runs'))?.body).toEqual({
      factoryName: 'Delta', lines: [{ variantId: 'v-shirt-s', expectedQty: 12 }],
    }))
    expect(calls.some(c => c.url.includes('/label-runs/import/preview'))).toBe(true)
    expect(calls.some(c => c.url.includes('/import/apply'))).toBe(false)
  })

  test('fr5 — print issues and opens the labels; afterwards only Reprint, the same part', async () => {
    setup(true)
    const user = userEvent.setup()
    const list = await openRunsTab(user)
    await user.click(await within(list).findByTestId('run-row-run-1'))
    await screen.findByTestId('factory-run')
    expect(screen.getByTestId('run-expected').textContent).toBe('9')

    await user.click(screen.getByRole('button', { name: 'Print labels · 9' }))
    await waitFor(() => expect(calls.filter(c => c.url.includes('/label-runs/run-1/pdf?part=1'))).toHaveLength(1))
    expect(window.open).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: /Print labels/ })).toBeNull()
    expect(screen.getByTestId('run-reprint-note')).toBeTruthy()

    await user.click(within(screen.getByTestId('run-part-1')).getByRole('button', { name: 'Reprint' }))
    await waitFor(() => expect(calls.filter(c => c.url.includes('/label-runs/run-1/pdf?part=1'))).toHaveLength(2))
    expect(calls.filter(c => c.url.endsWith('/label-runs/run-1/print'))).toHaveLength(1)
  })
})
