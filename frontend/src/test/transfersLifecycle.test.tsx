import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within, fireEvent } from './renderWithProviders'
import * as api from '../api'
import { ToastProvider } from '../components/ui'
import Transfers from '../pages/Transfers'
import TransferScanOut from '../pages/TransferScanOut'
import TransferDetail from '../pages/TransferDetail'
import TransferReconcile from '../pages/TransferReconcile'

// FR-22.9 — one end-to-end test through the FULL transfer lifecycle, driven
// through real navigation (react-router, not four isolated renders): create →
// scan-out 3 pieces of one variant → begin-reconcile → scan-back 1 good + 1
// condemned → classify the remaining 1 as lost → close, asserting the transfer
// only becomes closeable once every line balances (outstandingCount reaches 0)
// and that the resulting screen shows the closed state.
//
// The backend itself is not under test here (TransferServiceTest /
// TransferReconcileTest already cover the real piece-state transitions) — this
// test fakes api.ts's transfer functions with a small mutable in-memory model
// that mirrors the server's own bookkeeping (qty_out / qty_returned_good /
// qty_condemned / qty_sold / qty_lost / outstandingCount), so the UI's own
// balance-gating logic (Close disabled until outstandingCount === 0) is
// exercised against realistic state transitions, not hand-picked mock values.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listOpenTransfers: vi.fn(),
    listTransferDestinations: vi.fn(),
    createTransfer: vi.fn(),
    getTransfer: vi.fn(),
    scanOutTransferPiece: vi.fn(),
    beginReconcileTransfer: vi.fn(),
    scanBackTransferPiece: vi.fn(),
    classifyTransferShortfall: vi.fn(),
    closeTransferSession: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

const TRANSFER_ID = 'transfer-1'
const LINE_ID = 'line-1'
const VARIANT_ID = 'variant-1'
const DEST_ID = 'dest-1'

let state: api.TransferDetail
let pieceCounter = 0

function resetState() {
  pieceCounter = 0
  state = {
    id: TRANSFER_ID,
    transfer_type: 'repair',
    status: 'open',
    note: null,
    expected_return_at: null,
    created_by: 'user-1',
    created_at: new Date().toISOString(),
    closed_by: null,
    closed_at: null,
    destination_location_id: DEST_ID,
    destination_location_name: 'Vendor A',
    lines: [],
    outstandingCount: 0,
  }
}

function renderApp(initialEntries: string[]) {
  return renderWithProviders(
    <ToastProvider>
      <Routes>
        <Route path="/transfers" element={<Transfers />} />
        <Route path="/transfers/:id/scan-out" element={<TransferScanOut />} />
        <Route path="/transfers/:id/reconcile" element={<TransferReconcile />} />
        <Route path="/transfers/:id" element={<TransferDetail />} />
      </Routes>
    </ToastProvider>,
    { initialEntries },
  )
}

describe('FR-22.9 — Transfers full lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetState()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.listOpenTransfers).mockResolvedValue([])
    vi.mocked(api.listTransferDestinations).mockResolvedValue([
      { id: DEST_ID, name: 'Vendor A', is_fulfillment: false },
    ])
    vi.mocked(api.getTransfer).mockImplementation(() => Promise.resolve(structuredClone(state)))

    vi.mocked(api.createTransfer).mockImplementation(async () => {
      state.status = 'open'
      return { id: TRANSFER_ID }
    })

    vi.mocked(api.scanOutTransferPiece).mockImplementation(async (_id, barcode) => {
      let line = state.lines.find(l => l.id === LINE_ID)
      if (!line) {
        line = {
          id: LINE_ID, variant_id: VARIANT_ID, sku: 'SKU1',
          variant_title: 'W-1', product_title: 'Widget',
          qty_out: 0, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0,
        }
        state.lines.push(line)
      }
      line.qty_out += 1
      state.outstandingCount += 1
      pieceCounter += 1
      return {
        success: true, code: 'SCANNED', message_en: null, message_ar: null,
        pieceId: `piece-${pieceCounter}`, barcode, variantId: VARIANT_ID,
        lineId: LINE_ID, qtyOut: line.qty_out,
      }
    })

    vi.mocked(api.beginReconcileTransfer).mockImplementation(async () => {
      state.status = 'reconciling'
    })

    vi.mocked(api.scanBackTransferPiece).mockImplementation(async (_id, barcode, condition) => {
      const line = state.lines.find(l => l.id === LINE_ID)!
      if (condition === 'good') line.qty_returned_good += 1
      else line.qty_condemned += 1
      state.outstandingCount -= 1
      return {
        success: true, code: 'SCANNED', message_en: null, message_ar: null,
        pieceId: `piece-${++pieceCounter}`, barcode, variantId: VARIANT_ID,
        outcome: condition === 'good' ? 'returned_good' : 'condemned',
      }
    })

    vi.mocked(api.classifyTransferShortfall).mockImplementation(async (_id, lineId, counts) => {
      const line = state.lines.find(l => l.id === lineId)!
      line.qty_sold += counts.sold
      line.qty_lost += counts.lost
      line.qty_condemned += counts.condemnedNotReturned
      state.outstandingCount -= (counts.sold + counts.lost + counts.condemnedNotReturned)
    })

    vi.mocked(api.closeTransferSession).mockImplementation(async () => {
      state.status = 'closed'
      state.closed_by = 'user-1'
      state.closed_at = new Date().toISOString()
    })
  })

  test('tl1 — create, scan-out 3, begin-reconcile, scan-back good+condemned, classify lost, close only when balanced', async () => {
    const user = userEvent.setup()
    renderApp(['/transfers'])

    // ── 1. Create ──────────────────────────────────────────────────────────
    const newBtn = await screen.findByText('+ New Transfer')
    await user.click(newBtn)

    const destTrigger = await screen.findByText('Select destination location…')
    await user.click(destTrigger)
    const destOption = await screen.findByText('Vendor A')
    await user.click(destOption)

    await user.click(screen.getByTestId('type-repair-btn'))

    const submitBtn = screen.getByText('Create Transfer')
    await user.click(submitBtn)

    await waitFor(() => expect(api.createTransfer).toHaveBeenCalledWith(
      expect.objectContaining({ transferType: 'repair', destinationLocationId: DEST_ID }),
    ))

    // ── 2. Scan-out 3 pieces of the same variant ────────────────────────────
    await screen.findByTestId('transfer-scan-out')
    const scanInput = screen.getByPlaceholderText('Scan piece barcode…')
    for (const barcode of ['PC-1', 'PC-2', 'PC-3']) {
      await user.type(scanInput, barcode)
      await user.keyboard('{Enter}')
      await waitFor(() => expect(api.scanOutTransferPiece).toHaveBeenLastCalledWith(TRANSFER_ID, barcode))
    }
    expect(api.scanOutTransferPiece).toHaveBeenCalledTimes(3)
    expect(state.lines[0].qty_out).toBe(3)

    const doneBtn = screen.getByText('Done — View Transfer')
    await user.click(doneBtn)

    // ── 3. Detail: begin reconcile ───────────────────────────────────────────
    await waitFor(() => expect(screen.getByTestId('outstanding-headline')).toHaveTextContent('3'))
    const beginBtn = screen.getByText('Begin Reconcile')
    expect(beginBtn.closest('button')).not.toBeDisabled()
    await user.click(beginBtn)

    await waitFor(() => expect(api.beginReconcileTransfer).toHaveBeenCalledWith(TRANSFER_ID))
    expect(state.status).toBe('reconciling')

    // ── 4. Reconcile: scan back 1 good + 1 condemned ─────────────────────────
    await screen.findByTestId('reconcile-outstanding')
    const reconcileScanInput = screen.getByPlaceholderText('Scan returned piece…')

    await user.type(reconcileScanInput, 'PC-1')
    await user.keyboard('{Enter}')
    await waitFor(() => expect(api.scanBackTransferPiece).toHaveBeenLastCalledWith(TRANSFER_ID, 'PC-1', 'good'))

    await user.click(screen.getByTestId('condition-condemned-btn'))
    await user.type(reconcileScanInput, 'PC-2')
    await user.keyboard('{Enter}')
    await waitFor(() => expect(api.scanBackTransferPiece).toHaveBeenLastCalledWith(TRANSFER_ID, 'PC-2', 'condemned'))

    await waitFor(() => expect(screen.getByTestId('reconcile-outstanding')).toHaveTextContent('1'))

    // Close must still be disabled — one piece unaccounted for.
    expect(screen.getByText('Close Transfer').closest('button')).toBeDisabled()

    // ── 5. Classify the remaining 1 as lost ──────────────────────────────────
    const lostInput = screen.getByTestId(`shortfall-lost-${LINE_ID}`)
    fireEvent.change(lostInput, { target: { value: '1' } })
    const lineRow = screen.getByText('Widget · W-1').closest('tr')!
    await user.click(within(lineRow).getByText('Apply'))

    await waitFor(() => expect(api.classifyTransferShortfall).toHaveBeenCalledWith(
      TRANSFER_ID, LINE_ID, { sold: 0, lost: 1, condemnedNotReturned: 0 },
    ))
    await waitFor(() => expect(screen.getByTestId('reconcile-outstanding')).toHaveTextContent('0'))

    // ── 6. Close — only enabled now that every line balances ────────────────
    const closeBtn = screen.getByText('Close Transfer').closest('button')!
    expect(closeBtn).not.toBeDisabled()
    await user.click(closeBtn)

    const confirmBtn = await screen.findByText('Confirm Close')
    await user.click(confirmBtn)

    await waitFor(() => expect(api.closeTransferSession).toHaveBeenCalledWith(TRANSFER_ID))
    expect(state.status).toBe('closed')

    // ── 7. Final state: navigated back to detail, shows closed ──────────────
    await screen.findByText('This transfer is closed')
  })
})
