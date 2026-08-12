import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, fireEvent, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'

// ── Fake backend: a small mutable in-memory model mirroring the real
//    ReceivingService semantics (POST always creates a new line, PUT updates
//    one line's quantity by id, DELETE removes one line by id) — this lets
//    the tests exercise the grid's create/update/delete reconciliation logic
//    against realistic state transitions, not hand-picked mock values. This
//    reconciliation logic is UNCHANGED from the prior inline-expand build —
//    only the entry UI (modal instead of inline expand) changed. ───────────

interface FakeVariant { id: string; title: string; sku: string | null; productId: string; productTitle: string }
interface FakeLine {
  id: string; variant_id: string; variant_title: string; sku: string | null
  product_title: string; quantity: number; piece_count: number
}

const VARIANTS: FakeVariant[] = [
  { id: 'v-tshirt-s', title: 'S', sku: 'TSHIRT-S', productId: 'p-tshirt', productTitle: 'T-Shirt' },
  { id: 'v-tshirt-m', title: 'M', sku: 'TSHIRT-M', productId: 'p-tshirt', productTitle: 'T-Shirt' },
  { id: 'v-cap-navy', title: 'Navy', sku: 'CAP-NVY', productId: 'p-cap', productTitle: 'Cap' },
]

const CATALOG = {
  products: [
    {
      id: 'p-tshirt', title: 'T-Shirt', status: 'active',
      imageUrl: 'https://cdn.shopify.com/s/files/1/tshirt.jpg',
      variants: VARIANTS.filter(v => v.productId === 'p-tshirt')
        .map(v => ({ id: v.id, title: v.title, sku: v.sku, price: 350 })),
    },
    {
      id: 'p-cap', title: 'Cap', status: 'active',
      imageUrl: null,
      variants: VARIANTS.filter(v => v.productId === 'p-cap')
        .map(v => ({ id: v.id, title: v.title, sku: v.sku, price: 220 })),
    },
  ],
}

let session: {
  id: string; status: string; reference: string | null; supplier_name: string | null
  location_name: string | null; created_at: string; finalized_at: string | null
  line_units: number; piece_count: number; lines: FakeLine[]
}
let lineSeq = 0

function resetState() {
  lineSeq = 0
  session = {
    id: 'sess-1', status: 'open', reference: 'REC-0092', supplier_name: 'Cairo Cotton Co.',
    location_name: 'Main Warehouse', created_at: new Date().toISOString(), finalized_at: null,
    line_units: 0, piece_count: 0, lines: [],
  }
}

function sessionSummary() {
  return {
    id: session.id, status: session.status, reference: session.reference,
    supplier_name: session.supplier_name, location_name: session.location_name,
    created_at: session.created_at, finalized_at: session.finalized_at,
    line_units: session.lines.reduce((s, l) => s + l.quantity, 0),
    piece_count: session.piece_count,
  }
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    // Deep-clone on every read — a real fetch() Response always deserializes a fresh
    // object from the wire; returning the live `session`/`session.lines` reference
    // here instead would let React's useMemo([lines]) see the SAME array identity
    // across two separate responses (since our fake backend mutates in place),
    // masking real prop changes. structuredClone matches real HTTP semantics.
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

// Real ReceivingController.updateLine()/deleteLine() are @ResponseStatus(NO_CONTENT)
// void methods — Spring sends a genuine 204 with NO body. A real fetch(...).json() on
// an empty body throws SyntaxError("Unexpected end of JSON input") — this fixture must
// reproduce that throw, not resolve to null, or it silently masks the exact production
// bug (api()'s old unconditional res.json() call) this suite is meant to catch.
function emptyResponse(status = 204) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k === 'content-length' ? '0' : null) },
    json: async () => { throw new SyntaxError('Unexpected end of JSON input') },
    text: async () => '',
  })
}

let mockFetch: ReturnType<typeof vi.fn>

function backendFetch(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()

  if (url.includes('/api/v1/catalog')) return fakeResponse(CATALOG)
  if (url.endsWith('/receiving/sessions') && method === 'GET') return fakeResponse([sessionSummary()])
  if (url.endsWith(`/receiving/sessions/${session.id}`) && method === 'GET') {
    return fakeResponse({ ...sessionSummary(), lines: session.lines })
  }
  if (url.endsWith(`/receiving/sessions/${session.id}/lines`) && method === 'POST') {
    const body = JSON.parse(opts.body as string) as { variantId: string; quantity: number }
    const v = VARIANTS.find(x => x.id === body.variantId)!
    const line: FakeLine = {
      id: `line-${++lineSeq}`, variant_id: v.id, variant_title: v.title, sku: v.sku,
      product_title: v.productTitle, quantity: body.quantity, piece_count: 0,
    }
    session.lines.push(line)
    return fakeResponse({ lineId: line.id }, 201)
  }
  const putMatch = url.match(/\/receiving\/sessions\/[^/]+\/lines\/([^/]+)$/)
  if (putMatch && method === 'PUT') {
    const body = JSON.parse(opts.body as string) as { quantity: number }
    const line = session.lines.find(l => l.id === putMatch[1])
    if (line) line.quantity = body.quantity
    return emptyResponse()
  }
  if (putMatch && method === 'DELETE') {
    session.lines = session.lines.filter(l => l.id !== putMatch[1])
    return emptyResponse()
  }
  if (url.endsWith(`/receiving/sessions/${session.id}/finalize`) && method === 'POST') {
    const total = session.lines.reduce((s, l) => s + l.quantity, 0)
    session.status = 'finalized'
    session.finalized_at = new Date().toISOString()
    session.piece_count = total
    session.lines = session.lines.map(l => ({ ...l, piece_count: l.quantity }))
    return fakeResponse({ piecesCreated: total })
  }
  if (url.includes('/locations')) return fakeResponse([])

  return fakeResponse({})
}

function renderReceiving() {
  return renderWithProviders(
    <ToastProvider><Receiving /></ToastProvider>,
  )
}

async function openSession(user: ReturnType<typeof userEvent.setup>) {
  renderReceiving()
  const row = await screen.findByText('REC-0092')
  await user.click(row)
  await screen.findByTestId('receiving-grid')
}

async function openProductModal(user: ReturnType<typeof userEvent.setup>, productId: string) {
  await user.click(screen.getByTestId(`product-card-${productId}`))
  await screen.findByTestId('variant-modal-body')
}

async function checkVariant(user: ReturnType<typeof userEvent.setup>, variantId: string) {
  const row = screen.getByTestId(`variant-row-${variantId}`)
  await user.click(within(row).getByRole('checkbox'))
}

describe('Receiving — browse grid + variant modal + selected summary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetState()
    mockFetch = vi.fn(backendFetch)
    stubFetchWithShellDefaults(mockFetch)
  })

  test('clicking a card opens the variant modal over the grid; Done closes it back to the grid (no expand/reflow)', async () => {
    const user = userEvent.setup()
    await openSession(user)

    expect(screen.queryByTestId('variant-modal-body')).not.toBeInTheDocument()
    await openProductModal(user, 'p-tshirt')
    expect(screen.getByText('TSHIRT-S')).toBeInTheDocument()
    expect(screen.getByText('TSHIRT-M')).toBeInTheDocument()
    // The grid card is still present underneath, unchanged (no expand-in-place).
    expect(screen.getByTestId('product-card-p-tshirt')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByTestId('variant-modal-body')).not.toBeInTheDocument())
  })

  test('quantity field is disabled until its checkbox is checked, then enables and focuses', async () => {
    const user = userEvent.setup()
    await openSession(user)
    await openProductModal(user, 'p-cap')

    const input = screen.getByTestId('qty-input-v-cap-navy')
    expect(input).toBeDisabled()

    await checkVariant(user, 'v-cap-navy')
    await waitFor(() => expect(input).toBeEnabled())
    expect(input).toHaveFocus()
  })

  test('checking alone commits nothing — only entering a qty POSTs a new line', async () => {
    const user = userEvent.setup()
    await openSession(user)
    await openProductModal(user, 'p-cap')

    await checkVariant(user, 'v-cap-navy')
    await new Promise(r => setTimeout(r, 50))
    expect(session.lines).toHaveLength(0)

    const input = screen.getByTestId('qty-input-v-cap-navy')
    await user.type(input, '40')
    fireEvent.blur(input)

    await waitFor(() => expect(session.lines).toHaveLength(1))
    expect(session.lines[0]).toMatchObject({ variant_id: 'v-cap-navy', quantity: 40 })
    const postCalls = mockFetch.mock.calls.filter(([u, o]) =>
      String(u).endsWith('/lines') && (o as RequestInit)?.method === 'POST')
    expect(postCalls).toHaveLength(1)
  })

  test('editing an existing single-line variant PUTs the same line (not a second POST)', async () => {
    const user = userEvent.setup()
    session.lines.push({
      id: 'line-existing', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY',
      product_title: 'Cap', quantity: 20, piece_count: 0,
    })
    await openSession(user)
    await openProductModal(user, 'p-cap')

    const input = screen.getByTestId('qty-input-v-cap-navy')
    expect(input).toBeEnabled()
    expect(input).toHaveValue(20)
    await user.clear(input)
    await user.type(input, '35')
    fireEvent.blur(input)

    await waitFor(() => expect(session.lines).toEqual([
      expect.objectContaining({ id: 'line-existing', variant_id: 'v-cap-navy', quantity: 35 }),
    ]))
    const postCalls = mockFetch.mock.calls.filter(([u, o]) =>
      String(u).endsWith('/lines') && (o as RequestInit)?.method === 'POST')
    expect(postCalls).toHaveLength(0)
    const putCalls = mockFetch.mock.calls.filter(([, o]) => (o as RequestInit)?.method === 'PUT')
    expect(putCalls).toHaveLength(1)
  })

  test('unchecking an already-quantified variant clears it (DELETEs the line, disables the field)', async () => {
    const user = userEvent.setup()
    session.lines.push({
      id: 'line-existing', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY',
      product_title: 'Cap', quantity: 20, piece_count: 0,
    })
    await openSession(user)
    await openProductModal(user, 'p-cap')

    const row = screen.getByTestId('variant-row-v-cap-navy')
    expect(within(row).getByRole('checkbox')).toBeChecked()

    await user.click(within(row).getByRole('checkbox'))

    await waitFor(() => expect(session.lines).toHaveLength(0))
    const deleteCalls = mockFetch.mock.calls.filter(([, o]) => (o as RequestInit)?.method === 'DELETE')
    expect(deleteCalls).toHaveLength(1)
    expect(screen.getByTestId('qty-input-v-cap-navy')).toBeDisabled()
  })

  test('legacy-duplicate consolidation: two pre-existing lines for one variant display as their sum, and editing consolidates to one line preserving the net quantity', async () => {
    const user = userEvent.setup()
    session.lines.push(
      { id: 'line-a', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY', product_title: 'Cap', quantity: 12, piece_count: 0 },
      { id: 'line-b', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY', product_title: 'Cap', quantity: 8, piece_count: 0 },
    )
    await openSession(user)
    await openProductModal(user, 'p-cap')

    // Displays the SUM (20), pre-checked, not either individual line's quantity.
    const input = screen.getByTestId('qty-input-v-cap-navy')
    expect(input).toHaveValue(20)

    // Edit to 25 — must consolidate to exactly one line, net quantity 25 (not 12+25 or lost).
    await user.clear(input)
    await user.type(input, '25')
    fireEvent.blur(input)

    await waitFor(() => {
      expect(session.lines).toHaveLength(1)
      expect(session.lines[0].quantity).toBe(25)
    })
    expect(['line-a', 'line-b']).toContain(session.lines[0].id)
  })

  test('compact card shows the selected-badge (variants · units) only once qty > 0, and stays quiet when empty', async () => {
    const user = userEvent.setup()
    await openSession(user)

    const capCardBefore = screen.getByTestId('product-card-p-cap')
    expect(within(capCardBefore).queryByText(/1 · 20u/)).not.toBeInTheDocument()

    await openProductModal(user, 'p-tshirt')
    const sInput = screen.getByTestId('qty-input-v-tshirt-s')
    await checkVariant(user, 'v-tshirt-s')
    await user.type(sInput, '10')
    fireEvent.blur(sInput)
    await waitFor(() => expect(session.lines.some(l => l.variant_id === 'v-tshirt-s')).toBe(true))

    const mInput = screen.getByTestId('qty-input-v-tshirt-m')
    await checkVariant(user, 'v-tshirt-m')
    await user.type(mInput, '15')
    fireEvent.blur(mInput)
    await waitFor(() => expect(session.lines.some(l => l.variant_id === 'v-tshirt-m')).toBe(true))

    await user.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByTestId('variant-modal-body')).not.toBeInTheDocument())

    const tshirtCard = screen.getByTestId('product-card-p-tshirt')
    expect(within(tshirtCard).getByText('2 · 25u')).toBeInTheDocument()
  })

  test('selected-products summary lists a row per product with its variants/qty and a unit subtotal, empty state when nothing selected', async () => {
    const user = userEvent.setup()
    await openSession(user)

    expect(screen.getByText('Nothing selected yet — tap a product to add quantities.')).toBeInTheDocument()

    await openProductModal(user, 'p-cap')
    const capInput = screen.getByTestId('qty-input-v-cap-navy')
    await checkVariant(user, 'v-cap-navy')
    await user.type(capInput, '20')
    fireEvent.blur(capInput)
    await waitFor(() => expect(session.lines).toHaveLength(1))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByTestId('variant-modal-body')).not.toBeInTheDocument())

    const row = await screen.findByTestId('summary-row-p-cap')
    expect(within(row).getByText('Cap')).toBeInTheDocument()
    expect(within(row).getByText('Navy: 20')).toBeInTheDocument()
    expect(within(row).getByText('20')).toBeInTheDocument()
  })

  test('summary row Edit reopens that product\'s modal; Remove clears all its lines', async () => {
    const user = userEvent.setup()
    session.lines.push({
      id: 'line-existing', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY',
      product_title: 'Cap', quantity: 20, piece_count: 0,
    })
    await openSession(user)

    const row = await screen.findByTestId('summary-row-p-cap')
    await user.click(within(row).getByRole('button', { name: 'Edit' }))
    await screen.findByTestId('variant-modal-body')
    expect(screen.getByTestId('qty-input-v-cap-navy')).toHaveValue(20)
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByTestId('variant-modal-body')).not.toBeInTheDocument())

    await user.click(within(screen.getByTestId('summary-row-p-cap')).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(session.lines).toHaveLength(0))
    await waitFor(() => expect(screen.queryByTestId('summary-row-p-cap')).not.toBeInTheDocument())
  })

  test('"show selected only" filters the grid to cards with a qty, then back', async () => {
    const user = userEvent.setup()
    session.lines.push({
      id: 'line-existing', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY',
      product_title: 'Cap', quantity: 5, piece_count: 0,
    })
    await openSession(user)

    expect(screen.getByTestId('product-card-p-tshirt')).toBeInTheDocument()
    expect(screen.getByTestId('product-card-p-cap')).toBeInTheDocument()

    await user.click(screen.getByRole('switch'))
    await waitFor(() => expect(screen.queryByTestId('product-card-p-tshirt')).not.toBeInTheDocument())
    expect(screen.getByTestId('product-card-p-cap')).toBeInTheDocument()

    await user.click(screen.getByRole('switch'))
    await waitFor(() => expect(screen.getByTestId('product-card-p-tshirt')).toBeInTheDocument())
  })

  test('finalize is disabled until any variant qty > 0, and enables once one is set', async () => {
    const user = userEvent.setup()
    await openSession(user)

    const finalizeBtn = screen.getByRole('button', { name: /finalize & generate pieces/i })
    expect(finalizeBtn).toBeDisabled()

    await openProductModal(user, 'p-cap')
    const input = screen.getByTestId('qty-input-v-cap-navy')
    await checkVariant(user, 'v-cap-navy')
    await user.type(input, '3')
    fireEvent.blur(input)

    await waitFor(() => expect(finalizeBtn).not.toBeDisabled())
  })

  test('finalize opens the shared Modal (not native confirm), and success shows a toast (not native alert)', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    const alertSpy   = vi.spyOn(window, 'alert')
    const user = userEvent.setup()
    session.lines.push({
      id: 'line-existing', variant_id: 'v-cap-navy', variant_title: 'Navy', sku: 'CAP-NVY',
      product_title: 'Cap', quantity: 5, piece_count: 0,
    })
    await openSession(user)

    const finalizeBtn = screen.getByRole('button', { name: /finalize & generate pieces/i })
    await user.click(finalizeBtn)

    // Modal, not confirm()
    expect(await screen.findByText('Finalize session REC-0092?')).toBeInTheDocument()
    expect(screen.getByText('This creates 5 pieces and locks the session for editing.')).toBeInTheDocument()
    expect(confirmSpy).not.toHaveBeenCalled()

    const confirmBtn = screen.getByRole('button', { name: 'Finalize' })
    await user.click(confirmBtn)

    // Toast, not alert()
    expect(await screen.findByText('5 pieces created')).toBeInTheDocument()
    expect(alertSpy).not.toHaveBeenCalled()
    await waitFor(() => expect(session.status).toBe('finalized'))
  })
})
