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
  id: string; number: string; status: string; customer_name: string
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
})
