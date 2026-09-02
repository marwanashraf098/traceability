import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import TransferReconcile from '../pages/TransferReconcile'
import i18n from '../i18n'

// Reconcile focus fix — TransferReconcile.tsx puts useScanner's auto-focusing
// scan input on the same screen as the shortfall-classify Input fields,
// OUTSIDE any modal. useScanner's document-wide click-refocus listener
// (SAFETY-CRITICAL, not modified here) steals focus back to the scan bar on
// ANY click that bubbles to document — including a click into a shortfall
// field. The fix scopes a stopPropagation shield around the classify table
// (the same technique Modal already uses for its own content), so clicking
// inside it never reaches that listener.
//
// rf1 reproduces the REAL bug via user.click + user.type (not fireEvent.change,
// which bypasses the native mousedown/click sequence entirely and would never
// have caught this). rf2 is the positive control: scanning must still work
// with focus on the scan bar.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getTransfer: vi.fn(),
    scanBackTransferPiece: vi.fn(),
    classifyTransferShortfall: vi.fn(),
    closeTransferSession: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

const TRANSFER_ID = 'transfer-1'
const LINE_ID = 'line-1'
const VARIANT_ID = 'variant-1'

function reconcilingTransfer(): api.TransferDetail {
  return {
    id: TRANSFER_ID,
    transfer_type: 'repair',
    status: 'reconciling',
    note: null,
    expected_return_at: null,
    created_by: 'user-1',
    created_at: new Date().toISOString(),
    closed_by: null,
    closed_at: null,
    destination_location_id: 'dest-1',
    destination_location_name: 'Vendor A',
    lines: [{
      id: LINE_ID, variant_id: VARIANT_ID, sku: 'SKU1',
      variant_title: 'W-1', product_title: 'Widget',
      qty_out: 2, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0,
    }],
    outstandingCount: 2,
  }
}

function renderReconcile() {
  return renderWithProviders(
    <Routes>
      <Route path="/transfers/:id/reconcile" element={<TransferReconcile />} />
    </Routes>,
    { initialEntries: [`/transfers/${TRANSFER_ID}/reconcile`] },
  )
}

describe('Reconcile scan-focus fix', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.getTransfer).mockResolvedValue(reconcilingTransfer())
  })

  test('rf1 — click a shortfall Input then type: the value lands and stays (real click+type, not fireEvent.change)', async () => {
    const user = userEvent.setup()
    renderReconcile()

    const lostInput = await screen.findByTestId(`shortfall-lost-${LINE_ID}`)

    // Real interaction sequence: mousedown sets native focus on lostInput BEFORE
    // the click event fires and bubbles to document. If useScanner's refocus
    // listener isn't shielded here, it steals focus back to the scan bar the
    // instant this click bubbles, and the subsequent typed keys never reach
    // lostInput at all.
    await user.click(lostInput)
    await user.type(lostInput, '1')

    expect(lostInput).toHaveValue(1)
    expect(lostInput).toHaveFocus()
  })

  test('rf2 — positive control: with focus on the scan bar, a scan still lands and processes scan-back', async () => {
    const user = userEvent.setup()
    renderReconcile()

    vi.mocked(api.scanBackTransferPiece).mockResolvedValue({
      success: true, code: 'SCANNED', message_en: null, message_ar: null,
      pieceId: 'piece-1', barcode: 'PC-1', variantId: VARIANT_ID, outcome: 'returned_good',
    })

    const scanInput = await screen.findByPlaceholderText('Scan returned piece…')
    await user.type(scanInput, 'PC-1')
    await user.keyboard('{Enter}')

    await waitFor(() => expect(api.scanBackTransferPiece).toHaveBeenCalledWith(TRANSFER_ID, 'PC-1', 'good'))
  })

  // NOTE: renderWithProviders uses its own English-only i18next instance — a
  // pre-existing harness limitation (see returns.test.tsx's rt5) — so this only
  // exercises layout under dir="rtl", not actual Arabic strings; string-level AR
  // content is confirmed at the live-acceptance pass instead.
  test('rf3 RTL layout — reconcile screen (scan bar + classify table) renders without crash under dir=rtl', async () => {
    await i18n.changeLanguage('ar')
    renderReconcile()

    await screen.findByTestId('reconcile-outstanding')
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByTestId(`shortfall-lost-${LINE_ID}`)).toBeInTheDocument()
    await i18n.changeLanguage('en')
  })
})
