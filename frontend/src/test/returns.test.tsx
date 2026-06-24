import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import Returns from '../pages/Returns'

// ── Fixtures ──────────────────────────────────────────────────────────────────

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({ ok: true, status, json: async () => data })
}
function jsonErr(data: unknown, status = 422) {
  return Promise.resolve({ ok: false, status, json: async () => data })
}
function pdfOk() {
  return Promise.resolve({
    ok: true,
    status: 200,
    blob: async () => new Blob(['%PDF-stub'], { type: 'application/pdf' }),
    json: async () => ({}),
  })
}

function makeSession(overrides: Partial<{
  sessionId: string; waybillNumber: string; orderId: string; orderNumber: string
}> = {}) {
  return { sessionId: 'sess-1', waybillNumber: 'AWB-001', orderId: 'ord-1', orderNumber: '#101', ...overrides }
}

function makePiece(overrides: Partial<{
  id: string; barcode: string; status: string; processed: boolean
  product_title: string; variant_title: string
}> = {}) {
  return {
    id: 'piece-1', barcode: 'BAR-001', status: 'return_in_transit',
    product_title: 'Blue Widget', variant_title: 'M / Blue', processed: false,
    ...overrides,
  }
}

// ── Test setup ────────────────────────────────────────────────────────────────

let mockFetch: ReturnType<typeof vi.fn>

describe('Returns — session flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    vi.stubGlobal('URL', { createObjectURL: vi.fn().mockReturnValue('blob:fake') })
    vi.spyOn(window, 'open').mockImplementation(() => null)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  // rt1: session start success → order card + piece list rendered
  test('rt1 session start success shows order number and pieces', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [makePiece()] }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    const input = screen.getByPlaceholderText(/Scan or type waybill/i)
    await user.type(input, 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText('AWB-001'))
    expect(screen.getByText(/#101/)).toBeTruthy()
    expect(screen.getByTestId('pieces-list')).toBeTruthy()
    expect(screen.getByText('Blue Widget')).toBeTruthy()
  })

  // rt2: session start 404 → inline error, no crash
  test('rt2 session start 404 shows inline error', async () => {
    mockFetch.mockReturnValueOnce(jsonErr({ message: 'Waybill not found' }, 404))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    const input = screen.getByPlaceholderText(/Scan or type waybill/i)
    await user.type(input, 'BAD-AWB')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByTestId('session-error'))
    expect(screen.getByTestId('session-error').textContent).toMatch(/Waybill not found/i)
  })

  // rt3: session start 422 (invalid shipment state) → inline error
  test('rt3 session start 422 invalid state shows backend message', async () => {
    mockFetch.mockReturnValueOnce(jsonErr({ message: 'Shipment is with_courier — cannot open session' }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-BAD')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByTestId('session-error'))
    expect(screen.getByTestId('session-error').textContent).toMatch(/with_courier/i)
  })

  // rt4: piece list renders RIT + delivered; processed pieces visually distinct
  test('rt4 piece list shows return_in_transit and delivered pieces; processed has done marker', async () => {
    const ritPiece  = makePiece({ id: 'p1', status: 'return_in_transit', barcode: 'RIT-001' })
    const delPiece  = makePiece({ id: 'p2', status: 'delivered', barcode: 'DEL-001' })
    const donePiece = makePiece({ id: 'p3', barcode: 'DON-001', processed: true })
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [ritPiece, delPiece, donePiece] }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByTestId('pieces-list'))
    // All three barcodes visible
    expect(screen.getByText('RIT-001')).toBeTruthy()
    expect(screen.getByText('DEL-001')).toBeTruthy()
    expect(screen.getByText('DON-001')).toBeTruthy()
    // Processed piece shows Done marker
    expect(screen.getByText(/✓/)).toBeTruthy()
  })

  // rt5: un-scanned delivered piece NOT styled as an error/warning
  test('rt5 un-scanned delivered piece has no danger/warning styling and shows optional note', async () => {
    const delPiece = makePiece({ id: 'p1', status: 'delivered', barcode: 'DEL-001', processed: false })
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [delPiece] }))
    const user = userEvent.setup()
    const { container } = renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByTestId('pieces-list'))
    // optional note present
    expect(screen.getByText(/customer may have kept/i)).toBeTruthy()
    // piece card itself has no danger border
    const pieceCard = container.querySelector('[data-testid="pieces-list"] .card')
    expect(pieceCard?.className).not.toMatch(/border-danger/)
    expect(pieceCard?.className).not.toMatch(/border-warning/)
    // and no error-colored text on the piece row (the card body)
    expect(pieceCard?.querySelector('[class*="text-danger"]')).toBeNull()
  })

  // rt6: restock verdict → API called, piece shows done marker
  test('rt6 restock verdict marks piece done', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [makePiece()] }))
      .mockReturnValueOnce(jsonOk({}, 200))  // verdict response
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText(/Restock/i))
    await user.click(screen.getByText(/Restock/i))
    await waitFor(() => screen.getByText(/✓/))
    // verdict API was called
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining('/verdict'),
      expect.objectContaining({ method: 'POST' }),
    )
  })

  // rt7: damaged without reason → blocked (error shown, no API call beyond session+pieces)
  test('rt7 damaged without reason is blocked — shows required error', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [makePiece()] }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText(/Mark Damaged/i))
    await user.click(screen.getByText(/Mark Damaged/i))
    // damage reason input appears; submit without typing a reason
    await waitFor(() => screen.getByPlaceholderText(/Reason/i))
    await user.click(screen.getByText(/^Confirm$/))
    await waitFor(() => screen.getByTestId('damage-reason-error'))
    expect(screen.getByTestId('damage-reason-error').textContent).toMatch(/reason/i)
    // only the session open + pieces calls; no verdict call
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  // rt8: damaged with reason → success + reprint button offered
  test('rt8 damaged with reason succeeds and offers reprint', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [makePiece()] }))
      .mockReturnValueOnce(jsonOk({}, 200))  // verdict
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText(/Mark Damaged/i))
    await user.click(screen.getByText(/Mark Damaged/i))
    await waitFor(() => screen.getByPlaceholderText(/Reason/i))
    await user.type(screen.getByPlaceholderText(/Reason/i), 'dented corner')
    await user.click(screen.getByText(/^Confirm$/))
    // reprint button appears
    await waitFor(() => screen.getByTestId('reprint-piece-1'))
    expect(screen.getByTestId('reprint-piece-1').textContent).toMatch(/Print piece label/i)
  })

  // rt9: out-of-window 422 → message + switch-to-intake affordance
  test('rt9 out-of-window 422 shows nudge and switch-to-intake button', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [makePiece({ status: 'delivered' })] }))
      .mockReturnValueOnce(jsonErr({ message: 'beyond the customer return window' }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText(/Restock/i))
    await user.click(screen.getByText(/Restock/i))
    await waitFor(() => screen.getByTestId('out-of-window-nudge'))
    expect(screen.getByTestId('switch-to-intake')).toBeTruthy()
    expect(screen.getByTestId('switch-to-intake').textContent).toMatch(/waybill-less intake/i)
  })

  // rt10: finalize shows processed + RTO unresolved; delivered kept shown as non-alarming
  test('rt10 finalize shows processedCount and unresolvedRtoCount; delivered kept not alarming', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk(makeSession()))
      .mockReturnValueOnce(jsonOk({ pieces: [] }))
      .mockReturnValueOnce(jsonOk({ processedCount: 3, unresolvedRtoCount: 1, deliveredKeptCount: 4 }))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.type(screen.getByPlaceholderText(/Scan or type waybill/i), 'AWB-001')
    await user.keyboard('{Enter}')
    await waitFor(() => screen.getByText(/Finalize Session/i))
    await user.click(screen.getByText(/Finalize Session/i))
    await waitFor(() => screen.getByTestId('session-finalized'))
    // processed and RTO counts present
    expect(screen.getByTestId('session-finalized').textContent).toMatch(/3/)
    expect(screen.getByTestId('session-finalized').textContent).toMatch(/1/)
    // delivered kept count shown but in muted (non-danger) text — not an alarm
    const finalized = screen.getByTestId('session-finalized')
    expect(finalized.textContent).toMatch(/4/)
    // no danger text inside finalized for the delivered-kept row
    const dangerEl = finalized.querySelectorAll('[class*="text-danger"]')
    expect(dangerEl.length).toBe(0)
  })

  // rt11: dark tokens used; waybill input has input-scan class
  test('rt11 dark tokens — no bg-white or text-gray-*; input-scan on waybill field', async () => {
    const { container } = renderWithProviders(<Returns />)
    expect(container.querySelector('[class*="bg-white"]')).toBeNull()
    expect(container.querySelector('[class*="text-gray-"]')).toBeNull()
    const waybillInput = screen.getByPlaceholderText(/Scan or type waybill/i)
    expect(waybillInput.className).toContain('input-scan')
  })
})
