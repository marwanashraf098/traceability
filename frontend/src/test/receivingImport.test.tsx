import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'

/**
 * Receiving import (slice 2b) — the screen side:
 *   i1  an open print-and-stick session (no flag) offers "Import from Excel"; its panel offers "Download template"
 *   i2  upload → preview: matched rows, the "same as Shopify" warning, not-added rows with readable
 *       reasons, skipped count, the Shopify warning; removing a row changes the total; confirm sends
 *       only the kept rows and refreshes the session
 *   i3  a refused file shows the server's readable message, never a raw body
 *   i4  "Download template" fetches the .xlsx template
 */

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['x']),
  })
}

const SESSION = {
  id: 'sess-1', status: 'open', mode: 'print_stick', reference: 'REC-9', supplier_name: null,
  location_name: 'Main Warehouse', created_at: new Date().toISOString(), finalized_at: null,
  line_units: 0, piece_count: 0, lines: [],
}

const PREVIEW = {
  location: 'Shop location', units: 13, skipped: 15,
  matched: [
    { rowNumber: 9, variantId: 'v-ice', productTitle: 'The Complete Snowboard', variantTitle: 'Ice', sku: null,
      imageUrl: null, quantity: 3, onHandCurrent: 10, sameAsShopify: false, matchedBy: 'handle' },
    { rowNumber: 10, variantId: 'v-dawn', productTitle: 'The Complete Snowboard', variantTitle: 'Dawn', sku: null,
      imageUrl: null, quantity: 10, onHandCurrent: 10, sameAsShopify: true, matchedBy: 'handle' },
  ],
  unmatched: [
    { rowNumber: 14, handle: 'gift-card', title: 'Gift Card', options: '$10', sku: '', value: '4', reason: 'gift_card' },
    { rowNumber: 13, handle: 'the-complete-snowboard', title: 'The Complete Snowboard', options: 'Sunset', sku: '', value: '-2', reason: 'negative' },
  ],
}

let calls: { url: string; method: string; body: unknown }[]
let previewReply: () => ReturnType<typeof json>

function backend(url: string, opts: RequestInit = {}) {
  const method = opts.method ?? 'GET'
  calls.push({ url, method, body: typeof opts.body === 'string' ? JSON.parse(opts.body) : opts.body })
  if (url.includes('/locations')) return json([])
  if (url.includes('/catalog')) return json({ products: [], nextCursor: null })
  if (url.endsWith('/receiving/sessions')) return json([{ ...SESSION, line_units: 0 }])
  if (url.endsWith('/receiving/sessions/sess-1')) return json(SESSION)
  if (url.endsWith('/import/preview')) return previewReply()
  if (url.endsWith('/import/apply')) return json({ linesAdded: 1, linesUpdated: 0, units: 3 })
  if (url.includes('/import/template')) return json({})
  return json({})
}

beforeEach(() => {
  calls = []
  previewReply = () => json(PREVIEW)
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => vi.unstubAllGlobals())

async function openSession() {
  const user = userEvent.setup()
  renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
  await user.click(await screen.findByText('REC-9'))
  await screen.findByRole('button', { name: /import from excel/i })
  return user
}

async function uploadFile(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /import from excel/i }))
  const input = screen.getByTestId('import-file') as HTMLInputElement
  await user.upload(input, new File(['Handle,Title'], 'shop.csv', { type: 'text/csv' }))
}

describe('Receiving › import from file', () => {
  test('i1 — an open session offers import and the template (no flag needed)', async () => {
    const user = await openSession()
    await user.click(screen.getByRole('button', { name: /import from excel/i }))
    expect(screen.getByRole('button', { name: /download template/i })).toBeTruthy()
  })

  test('i2 — preview, remove a row, confirm sends only the kept rows', async () => {
    const user = await openSession()
    await uploadFile(user)
    const panel = await screen.findByTestId('import-panel')
    expect(panel.textContent).toContain("Don't also import this file into Shopify — Traced adds these quantities to Shopify when you finish receiving.")
    const preview = await screen.findByTestId('import-preview')
    expect(preview.textContent).toContain('2 products matched · 13 units to add')
    expect(preview.textContent).toContain('15 skipped')
    expect(within(screen.getByTestId('import-row-10')).getByTestId('same-as-shopify').textContent)
      .toBe('Same as Shopify stock — is this really what arrived?')
    expect(within(screen.getByTestId('import-row-9')).queryByTestId('same-as-shopify')).toBeNull()
    const notAdded = screen.getByTestId('import-unmatched')
    await user.click(within(notAdded).getByRole('button', { name: 'Not added (2)' }))   // collapsed by default
    expect(notAdded.textContent).toContain('Gift card')
    expect(notAdded.textContent).toContain('Negative quantity')

    await user.click(within(screen.getByTestId('import-row-10')).getByRole('button', { name: /remove this row/i }))
    expect(screen.getByTestId('import-preview').textContent).toContain('1 product matched · 3 units to add')
    await user.click(screen.getByRole('button', { name: 'Add 3 units to this session' }))

    await waitFor(() => expect(calls.some(c => c.url.endsWith('/import/apply'))).toBe(true))
    const apply = calls.find(c => c.url.endsWith('/import/apply'))!
    expect(apply.body).toEqual({ lines: [{ variantId: 'v-ice', quantity: 3 }] })
    await waitFor(() => expect(screen.queryByTestId('import-panel')).toBeNull())
    expect(calls.filter(c => c.url.endsWith('/receiving/sessions/sess-1')).length).toBeGreaterThan(1)
  })

  test('i3 — a refused file shows the readable message', async () => {
    previewReply = () => json({ code: 'IMPORT_SEVERAL_LOCATIONS',
      message_en: 'This file has several locations. Export one location only from Shopify.', message_ar: 'x' }, 400)
    const user = await openSession()
    await uploadFile(user)
    expect(await screen.findByText('This file has several locations. Export one location only from Shopify.')).toBeTruthy()
    expect(screen.queryByTestId('import-preview')).toBeNull()
  })

  test('i4 — Download template fetches the .xlsx template', async () => {
    const user = await openSession()
    await user.click(screen.getByRole('button', { name: /import from excel/i }))
    await user.click(screen.getByRole('button', { name: /download template/i }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/receiving/import/template?format=xlsx'))).toBe(true))
  })
})
