import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

/**
 * Flexible labeling slice 2 — the receiving mode chooser and count-only receiving.
 *   c1  flag off: no chooser; the create request is exactly today's (no `mode`)
 *   c2  flag on: both modes with their copy; count only locks the location to the main warehouse
 *       and sends mode count_only
 *   c3  open count-only session: the notice, "Finish · add N to stock", no print buttons
 *   c4  finished count-only session: N added, +N per variant, Shopify updated, labels none — no print
 *   c5  finished print-and-stick session still offers its label printing
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

const LOCATIONS = [
  { id: 'loc-show', name: 'Showroom', is_fulfillment: false },
  { id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true },
]

const LINES = [
  { id: 'l1', variant_id: 'v-s', variant_title: 'S', sku: 'TS-S', product_title: 'T-Shirt', quantity: 3, piece_count: 0 },
  { id: 'l2', variant_id: 'v-m', variant_title: 'M', sku: 'TS-M', product_title: 'T-Shirt', quantity: 2, piece_count: 0 },
]

let detail: Record<string, unknown>
let posts: { url: string; body: unknown }[]

function backend(url: string, opts: RequestInit = {}) {
  const method = opts.method ?? 'GET'
  if (url.includes('/locations')) return json(LOCATIONS)
  if (url.includes('/catalog')) return json({ products: [], nextCursor: null })
  if (url.endsWith('/receiving/sessions') && method === 'POST') {
    posts.push({ url, body: JSON.parse(String(opts.body)) })
    return json({ sessionId: 'sess-1' }, 201)
  }
  if (url.endsWith('/receiving/sessions')) return json([])
  if (url.endsWith('/receiving/sessions/sess-1')) return json(detail)
  return json({})
}

function setup(flexibleLabeling: boolean) {
  posts = []
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

function base(overrides: Record<string, unknown>) {
  return {
    id: 'sess-1', status: 'open', mode: 'count_only', reference: 'REC-77', supplier_name: null,
    location_name: 'Main Warehouse', created_at: new Date().toISOString(), finalized_at: null,
    line_units: 5, piece_count: 0, lines: LINES, ...overrides,
  }
}

async function startCreate() {
  const user = userEvent.setup()
  renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
  await user.click(await screen.findByRole('button', { name: /new/i }))
  return user
}

describe('Receiving › mode chooser', () => {
  test('c1 — flag off: no chooser, the create request carries no mode', async () => {
    setup(false)
    detail = base({ mode: 'print_stick' })
    const user = await startCreate()
    await screen.findByRole('button', { name: /create/i })
    expect(screen.queryByTestId('receiving-mode-chooser')).toBeNull()
    await user.click(screen.getByRole('button', { name: /create/i }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].body).not.toHaveProperty('mode')
  })

  test('c2 — flag on: both modes; count only locks the main warehouse and sends count_only', async () => {
    setup(true)
    detail = base({})
    const user = await startCreate()
    await screen.findByTestId('receiving-mode-chooser')
    expect(screen.getByText('Print & stick now')).toBeTruthy()
    expect(screen.getByText(/Exactly how receiving works today/)).toBeTruthy()
    expect(screen.getByText('Count only, label at pack')).toBeTruthy()
    expect(screen.getByText(/Pieces go in as untagged and get a label when they're packed/)).toBeTruthy()
    expect(screen.getByTestId('receiving-mode-print_stick').getAttribute('aria-pressed')).toBe('true')

    await user.click(screen.getByTestId('receiving-mode-count_only'))
    expect(screen.getByTestId('count-only-location').textContent).toBe('Main Warehouse')
    await user.click(screen.getByRole('button', { name: /create/i }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].body).toMatchObject({ mode: 'count_only', locationId: 'loc-main' })
  })
})

describe('Receiving › count only session', () => {
  beforeEach(() => setup(true))

  async function openSession() {
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    // The list is empty in this backend — open the session through the create flow.
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /new/i }))
    await user.click(await screen.findByRole('button', { name: /create/i }))
  }

  test('c3 — open: the notice and "Finish · add N to stock", no label printing', async () => {
    detail = base({})
    await openSession()
    const notice = await screen.findByTestId('count-only-notice')
    expect(notice.textContent).toContain('No labels will be printed — these pieces go in as untagged.')
    expect(notice.textContent).toContain('Shopify goes up by')
    expect(screen.getByRole('button', { name: /^Finish · add \d+ to stock$/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /print labels/i })).toBeNull()
  })

  test('c4 — finished: N added as untagged, +N per variant, Shopify updated, no labels', async () => {
    detail = base({ status: 'finalized', finalized_at: new Date().toISOString(), piece_count: 5,
      shopify: { applied: 5, pending: 0, failed: 0, skipped: 0 } })
    await openSession()
    const done = await screen.findByTestId('count-only-done')
    expect(done.textContent).toContain('5 pieces added to stock')
    expect(done.textContent).toContain('Added as untagged, labels go on at packing')
    expect(done.textContent).toContain('+3')
    expect(done.textContent).toContain('+2')
    expect(screen.getByTestId('count-only-shopify').textContent).toBe('Updated +5')
    expect(done.textContent).toContain('Labels printed')
    expect(done.textContent).toContain('None')
    expect(screen.queryByRole('button', { name: /print labels/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /reprint/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /print barcodes/i })).toBeNull()
  })

  test('c5 — a finished print-and-stick session still prints labels', async () => {
    detail = base({ mode: 'print_stick', status: 'finalized', finalized_at: new Date().toISOString(), piece_count: 5,
      lines: LINES.map(l => ({ ...l, piece_count: l.quantity })) })
    await openSession()
    expect(await screen.findByRole('button', { name: /print labels/i })).toBeTruthy()
    expect(screen.queryByTestId('count-only-done')).toBeNull()
  })
})
