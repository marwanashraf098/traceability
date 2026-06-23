import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import LookupPage from '../pages/Lookup'

// ── Mock ─────────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    lookup:                vi.fn(),
    adjustPiece:           vi.fn(),
    releasePieceForAdjust: vi.fn(),
    getRoleFromToken:      vi.fn(() => 'owner' as const),
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeAvailablePiece(): api.PieceLookupResult {
  return {
    type: 'piece',
    id: 'piece-001',
    barcode: 'PC-AVAIL',
    status: 'available',
    receivedAt: new Date().toISOString(),
    variant: { id: 'v1', productTitle: 'Widget', title: 'Default', sku: 'SKU-1' },
    currentLocation: { id: 'loc1', name: 'Shelf A' },
    currentOrder: null,
    currentShipment: null,
    receivingSession: null,
    timeline: [],
  }
}

function makeLostPiece(): api.PieceLookupResult {
  return { ...makeAvailablePiece(), id: 'piece-002', barcode: 'PC-LOST', status: 'lost' }
}

function makeDamagedPiece(): api.PieceLookupResult {
  return { ...makeAvailablePiece(), id: 'piece-003', barcode: 'PC-DMG', status: 'damaged' }
}

function makeReservedPiece(): api.PieceLookupResult {
  return { ...makeAvailablePiece(), id: 'piece-004', barcode: 'PC-RES', status: 'reserved' }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('FR-13 Adjust Panel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.adjustPiece).mockResolvedValue(undefined)
    vi.mocked(api.releasePieceForAdjust).mockResolvedValue(undefined)
  })

  // fa1: available piece shows Adjust button; submit calls adjustPiece with chosen status+reason
  test('fa1 — available piece: adjust dialog submits correctly', async () => {
    vi.mocked(api.lookup).mockResolvedValue(makeAvailablePiece())

    renderWithProviders(<LookupPage />)
    const input = screen.getByRole('textbox')
    await userEvent.type(input, 'PC-AVAIL')
    await userEvent.keyboard('{Enter}')

    const openBtn = await screen.findByTestId('adjust-open-btn')
    await userEvent.click(openBtn)

    // Select "damaged" status
    const damagedBtn = await screen.findByText('Damaged')
    await userEvent.click(damagedBtn)

    const submitBtn = screen.getByTestId('adjust-submit-btn')
    await userEvent.click(submitBtn)

    await waitFor(() =>
      expect(api.adjustPiece).toHaveBeenCalledWith(
        'piece-001',
        'damaged',
        'cycle_count_missing',
        undefined,
      )
    )
  })

  // fa2: reason=other requires note; submit disabled until note filled
  test('fa2 — reason=other: submit blocked without note', async () => {
    vi.mocked(api.lookup).mockResolvedValue(makeAvailablePiece())

    renderWithProviders(<LookupPage />)
    const input = screen.getByRole('textbox')
    await userEvent.type(input, 'PC-AVAIL')
    await userEvent.keyboard('{Enter}')

    const openBtn = await screen.findByTestId('adjust-open-btn')
    await userEvent.click(openBtn)

    // Select 'Other' reason
    const reasonSelect = await screen.findByRole('combobox')
    await userEvent.selectOptions(reasonSelect, 'other')

    const submitBtn = screen.getByTestId('adjust-submit-btn')
    expect(submitBtn).toBeDisabled()

    // Fill in note → button becomes enabled
    const noteField = screen.getByTestId('adjust-note')
    await userEvent.type(noteField, 'Custom note')
    expect(submitBtn).not.toBeDisabled()
  })

  // fa3: lost piece shows both "Found It" and "Adjust" buttons
  test('fa3 — lost piece: shows found-it button and adjust button', async () => {
    vi.mocked(api.lookup).mockResolvedValue(makeLostPiece())

    renderWithProviders(<LookupPage />)
    const input = screen.getByRole('textbox')
    await userEvent.type(input, 'PC-LOST')
    await userEvent.keyboard('{Enter}')

    expect(await screen.findByTestId('found-it-btn')).toBeInTheDocument()
    expect(screen.getByTestId('adjust-open-btn')).toBeInTheDocument()
  })

  // fa4: Found It button calls adjustPiece with toStatus=available
  test('fa4 — found it: calls adjustPiece(available)', async () => {
    // After found-it, re-lookup returns available piece
    vi.mocked(api.lookup)
      .mockResolvedValueOnce(makeLostPiece())
      .mockResolvedValueOnce(makeAvailablePiece())

    renderWithProviders(<LookupPage />)
    const input = screen.getByRole('textbox')
    await userEvent.type(input, 'PC-LOST')
    await userEvent.keyboard('{Enter}')

    const foundItBtn = await screen.findByTestId('found-it-btn')
    await userEvent.click(foundItBtn)

    await waitFor(() =>
      expect(api.adjustPiece).toHaveBeenCalledWith(
        'piece-002', 'available', expect.any(String), undefined,
      )
    )
  })

  // fa5: damaged piece: adjust panel hidden (terminal — no adjust button shown)
  test('fa5 — damaged piece: adjust button not rendered', async () => {
    vi.mocked(api.lookup).mockResolvedValue(makeDamagedPiece())

    renderWithProviders(<LookupPage />)
    const input = screen.getByRole('textbox')
    await userEvent.type(input, 'PC-DMG')
    await userEvent.keyboard('{Enter}')

    // Wait for piece view to render
    await screen.findByText('Widget')

    expect(screen.queryByTestId('adjust-open-btn')).not.toBeInTheDocument()
    expect(screen.queryByTestId('found-it-btn')).not.toBeInTheDocument()
  })
})
