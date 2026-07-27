import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import Fulfill from '../pages/Fulfill'

// ── Fixtures ──────────────────────────────────────────────────────────────────

function jsonOk(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: async () => data })
}

function jsonErr(data: unknown, status = 500) {
  return Promise.resolve({ ok: false, status, json: async () => data })
}

function makeQueueOrder(overrides: Partial<{
  id: string; number: string; status: string; customer_name: string; is_self_pickup: boolean
}> = {}) {
  return {
    id: 'order-1',
    number: '#101',
    customer_name: 'Alice',
    status: 'new',
    payment_method: null,
    cod_amount: null,
    total_units: 1,
    scanned_units: 0,
    locked_by: null,
    locked_at: null,
    is_self_pickup: false,
    ...overrides,
  }
}

function makeOrderDetail(overrides: Partial<{
  shipment_id: string | null
  tracking_number: string | null
  allocated: number
  is_self_pickup: boolean
}> = {}) {
  const { allocated = 0, ...rest } = overrides
  return {
    id: 'order-1',
    number: '#101',
    customer_name: 'Alice',
    customer_phone: null,
    status: 'new',
    payment_method: null,
    cod_amount: null,
    locked_by: null,
    is_self_pickup: false,
    cancel_requested_at: null,
    shipment_id: null,
    tracking_number: null,
    items: [{
      id: 'item-1',
      variant_id: 'v1',
      sku: 'SKU-1',
      variant_title: 'Default Title',
      product_title: 'Test Product',
      quantity: 1,
      allocated,
      allocatedPieces: [],
    }],
    ...rest,
  }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

// Fulfill.tsx uses its own local fetch wrapper — stub is set up per test inside jsdom.
let mockFetch: ReturnType<typeof vi.fn>

describe('Fulfill — dark theme + AWB print', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ft1: queue view uses dark design tokens — no raw bg-white or text-gray-* classes
  test('ft1 queue view has no light-mode token classes', async () => {
    mockFetch.mockReturnValueOnce(jsonOk([makeQueueOrder()]))
    const { container } = renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByTestId('fulfill-queue'))
    expect(container.querySelector('[class*="bg-white"]')).toBeNull()
    expect(container.querySelector('[class*="text-gray-"]')).toBeNull()
    expect(container.querySelector('[class*="bg-indigo-"]')).toBeNull()
  })

  // ft2: scan input uses input-scan class
  test('ft2 pack screen uses input-scan class on the scan input', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail()))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('fulfill-pick'))
    await waitFor(() => screen.getByPlaceholderText(/Scan or type barcode/i))
    const input = screen.getByPlaceholderText(/Scan or type barcode/i)
    expect(input.className).toContain('input-scan')
  })

  // ft3: scan flash uses bg-success/20 or bg-danger/20 tokens, not raw green/red
  test('ft3 pack screen has no raw bg-green or bg-red flash classes', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail()))
    const user = userEvent.setup()
    const { container } = renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('fulfill-pick'))
    // The flash overlay uses bg-success/20 / bg-danger/20 — neither bg-green-* nor bg-red-*
    expect(container.querySelector('[class*="bg-green-"]')).toBeNull()
    expect(container.querySelector('[class*="bg-red-"]')).toBeNull()
  })

  // ft4: Print Waybill — order with tracking_number → button enabled
  test('ft4 Print Waybill button is enabled when order has tracking_number', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({
        shipment_id: 'ship-1',
        tracking_number: 'TRK-123',
      })))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-print-awb'))
    const btn = screen.getByTestId('btn-print-awb')
    expect(btn).not.toBeDisabled()
    expect(screen.queryByTestId('awb-not-linked-note')).toBeNull()
  })

  // ft5: Print Waybill — order without tracking_number → disabled + note shown
  test('ft5 Print Waybill button is disabled with note when no tracking_number', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({ shipment_id: null, tracking_number: null })))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-print-awb'))
    const btn = screen.getByTestId('btn-print-awb')
    expect(btn).toBeDisabled()
    expect(screen.getByTestId('awb-not-linked-note')).toBeTruthy()
    expect(screen.getByTestId('awb-not-linked-note').textContent).toMatch(/not linked/i)
  })

  // ft6: Print Waybill fetch error → inline error shown, scan input still present
  test('ft6 AWB print error shows inline message without crashing pack flow', async () => {
    vi.spyOn(window, 'open').mockImplementation(() => null)
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({
        shipment_id: 'ship-1',
        tracking_number: 'TRK-123',
      })))
      // AWB print call fails
      .mockReturnValueOnce(jsonErr({ message: 'Bosta unavailable' }, 503))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-print-awb'))
    await user.click(screen.getByTestId('btn-print-awb'))
    await waitFor(() => screen.getByTestId('awb-msg'))
    expect(screen.getByTestId('awb-msg').textContent).toMatch(/Bosta unavailable/i)
    // Scan input still renders — pack flow unaffected
    expect(screen.getByPlaceholderText(/Scan or type barcode/i)).toBeTruthy()
    vi.restoreAllMocks()
  })

  // ft7: linked order — Complete stays hidden until Print Waybill has been pressed
  test('ft7 Complete is hidden until Print Waybill is pressed (linked path)', async () => {
    vi.spyOn(window, 'open').mockImplementation(() => null)
    vi.stubGlobal('URL', { createObjectURL: vi.fn().mockReturnValue('blob:fake') })
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({
        shipment_id: 'ship-1', tracking_number: 'TRK-123', allocated: 1,
      })))
      .mockReturnValueOnce(jsonOk({ pdfBase64List: [btoa('%PDF-1.4 fake')], emailMessage: null, exceptions: [] }))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-print-awb'))

    // All pieces already allocated (allComplete=true) but not yet printed — Complete absent.
    expect(screen.queryByText('Complete & Pack Order')).toBeNull()

    await user.click(screen.getByTestId('btn-print-awb'))
    await waitFor(() => screen.getByText('Complete & Pack Order'))
    vi.restoreAllMocks()
  })

  // ft8: unlinked order — must scan-to-link before Print Waybill/Complete can appear
  test('ft8 unlinked order cannot Complete without scanning to link first', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({ tracking_number: null, allocated: 1 })))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-scan-to-link'))

    expect(screen.queryByTestId('btn-print-awb')).toBeNull()
    expect(screen.queryByText('Complete & Pack Order')).toBeNull()

    await user.click(screen.getByTestId('btn-scan-to-link'))
    await waitFor(() => screen.getByPlaceholderText(/Scan or type tracking number/i))

    mockFetch
      .mockReturnValueOnce(jsonOk({ shipmentId: 'ship-2', trackingNumber: '2944282510', linkedPieces: 1, orderStatus: 'awaiting_pickup' }))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({ tracking_number: '2944282510', shipment_id: 'ship-2', allocated: 1 })))

    // PickScreen's global click-refocus effect (SAFETY-CRITICAL, keeps the top piece-scan
    // input focused on any click) would steal focus back if we clicked into this input first
    // (as user.type() does) — a real HID scanner never clicks, it just emits keystrokes into
    // whatever is already focused, so focus() + keyboard() is the faithful simulation here.
    const trackingInput = screen.getByPlaceholderText(/Scan or type tracking number/i)
    trackingInput.focus()
    await user.keyboard('2944282510')
    await user.keyboard('{Enter}')

    // Linked, but not yet printed — Print Waybill appears, Complete still absent.
    await waitFor(() => screen.getByTestId('btn-print-awb'))
    expect(screen.queryByTestId('btn-scan-to-link')).toBeNull()
    expect(screen.queryByText('Complete & Pack Order')).toBeNull()
  })

  // ft9: no skip button in the pre-Complete inline link step
  test('ft9 no skip button in the pre-Complete scan-to-link step', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({ tracking_number: null, allocated: 1 })))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-scan-to-link'))
    await user.click(screen.getByTestId('btn-scan-to-link'))
    await waitFor(() => screen.getByPlaceholderText(/Scan or type tracking number/i))
    expect(screen.queryByText(/skip/i)).toBeNull()
  })

  // ft10: no skip button in the post-Complete mandatory verify-scan dialog
  test('ft10 no skip button in the post-Complete verify-scan dialog', async () => {
    vi.spyOn(window, 'open').mockImplementation(() => null)
    vi.stubGlobal('URL', { createObjectURL: vi.fn().mockReturnValue('blob:fake') })
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder()]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({
        shipment_id: 'ship-1', tracking_number: 'TRK-123', allocated: 1,
      })))
      .mockReturnValueOnce(jsonOk({ pdfBase64List: [btoa('x')], emailMessage: null, exceptions: [] }))
      .mockReturnValueOnce(jsonOk({ packedPieces: 1 }))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByTestId('btn-print-awb'))
    await user.click(screen.getByTestId('btn-print-awb'))
    await waitFor(() => screen.getByText('Complete & Pack Order'))
    await user.click(screen.getByText('Complete & Pack Order'))
    await waitFor(() => screen.getByText(/Scan the Bosta waybill/i))
    expect(screen.queryByText(/skip/i)).toBeNull()
    vi.restoreAllMocks()
  })

  // ft11: self-pickup path is untouched — Complete appears immediately, no AWB gating
  test('ft11 self-pickup order — Complete appears immediately, no link/print gate', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk([makeQueueOrder({ is_self_pickup: true })]))
      .mockReturnValueOnce(jsonOk(makeOrderDetail({
        tracking_number: null, allocated: 1, is_self_pickup: true,
      })))
    const user = userEvent.setup()
    renderWithProviders(<Fulfill />)
    await waitFor(() => screen.getByText('#101'))
    await user.click(screen.getByText('#101'))
    await waitFor(() => screen.getByText('Complete & Pack Order'))
    expect(screen.queryByTestId('btn-scan-to-link')).toBeNull()
  })
})
