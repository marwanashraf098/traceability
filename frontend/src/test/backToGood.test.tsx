import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import LookupPage from '../pages/Lookup'
import i18n from '../i18n'

// D11 (2026-10-10) — "Back to good" on a damaged piece in Lookup: manager+ only, reason + note
// (note required for Other), calls POST /pieces/{id}/restore.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    lookup:           vi.fn(),
    restorePiece:     vi.fn(),
    adjustPiece:      vi.fn(),
    getRoleFromToken: vi.fn(() => 'manager' as const),
  }
})

function piece(status: string): api.PieceLookupResult {
  return {
    type: 'piece',
    id: 'piece-dmg',
    barcode: 'PC-DMG',
    status,
    receivedAt: new Date().toISOString(),
    variant: { id: 'v1', productTitle: 'Widget', title: 'Default', sku: 'SKU-1' },
    currentLocation: { id: 'loc1', name: 'Shelf A' },
    currentOrder: null,
    currentShipment: null,
    receivingSession: null,
    timeline: [],
  }
}

async function open(barcode = 'PC-DMG') {
  await userEvent.type(screen.getByRole('textbox'), barcode)
  await userEvent.keyboard('{Enter}')
}

describe('Back to good', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('manager')
    vi.mocked(api.restorePiece).mockResolvedValue(undefined)
  })

  afterEach(async () => { await i18n.changeLanguage('en') })

  test('bg1 — damaged piece: Back to good submits the chosen reason and note', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece('damaged'))
    renderWithProviders(<LookupPage />)
    await open()

    await userEvent.click(await screen.findByTestId('back-to-good-btn'))
    await userEvent.selectOptions(screen.getByTestId('restore-reason'), 'mis_graded')
    await userEvent.type(screen.getByTestId('restore-note'), 'scratch was on the box only')
    await userEvent.click(screen.getByTestId('restore-submit-btn'))

    await waitFor(() =>
      expect(api.restorePiece).toHaveBeenCalledWith('piece-dmg', 'mis_graded', 'scratch was on the box only'))
    expect(api.adjustPiece).not.toHaveBeenCalled()
  })

  test('bg2 — reason Other: submit blocked until a note is written', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece('damaged'))
    renderWithProviders(<LookupPage />)
    await open()

    await userEvent.click(await screen.findByTestId('back-to-good-btn'))
    await userEvent.selectOptions(screen.getByTestId('restore-reason'), 'other')
    expect(screen.getByTestId('restore-submit-btn')).toBeDisabled()
    await userEvent.type(screen.getByTestId('restore-note'), 'repaired by the supplier')
    expect(screen.getByTestId('restore-submit-btn')).toBeEnabled()
    await userEvent.click(screen.getByTestId('restore-submit-btn'))

    await waitFor(() =>
      expect(api.restorePiece).toHaveBeenCalledWith('piece-dmg', 'other', 'repaired by the supplier'))
  })

  test('bg3 — workers never see it; non-damaged pieces never show it', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.mocked(api.lookup).mockResolvedValue(piece('damaged'))
    const { unmount } = renderWithProviders(<LookupPage />)
    await open()
    await screen.findByText('Widget')
    expect(screen.queryByTestId('back-to-good-btn')).not.toBeInTheDocument()
    unmount()

    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.lookup).mockResolvedValue(piece('destroyed'))
    renderWithProviders(<LookupPage />)
    await open()
    await screen.findByText('Widget')
    expect(screen.queryByTestId('back-to-good-btn')).not.toBeInTheDocument()
  })

  test('bg4 — renders in English and Arabic', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece('damaged'))
    const { unmount } = renderWithProviders(<I18nextProvider i18n={i18n}><LookupPage /></I18nextProvider>)
    await open()
    expect(await screen.findByText('Back to good')).toBeInTheDocument()
    unmount()

    await i18n.changeLanguage('ar')
    renderWithProviders(<I18nextProvider i18n={i18n}><LookupPage /></I18nextProvider>)
    await open()
    expect(await screen.findByText('إعادتها سليمة')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('back-to-good-btn'))
    expect(screen.getByRole('option', { name: 'صُنّفت تالفة بالخطأ' })).toBeInTheDocument()
  })
})
