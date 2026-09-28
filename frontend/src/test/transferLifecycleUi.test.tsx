import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import Transfers from '../pages/Transfers'
import TransferDetail from '../pages/TransferDetail'
import TransferScanOut from '../pages/TransferScanOut'
import TransferReconcile from '../pages/TransferReconcile'

// V118 transfer lifecycle on screen: preparing → sent → reconciling → closed, cancelled.
// Which transfers each tab holds is decided server-side (listOpen); these tests check the
// view the page asks for, the badges and tiles it draws, and which actions each status offers.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listOpenTransfers: vi.fn(),
    listTransferDestinations: vi.fn(),
    listLocations: vi.fn(),
    listReturnablePieces: vi.fn(),
    getTransfer: vi.fn(),
    markTransferSent: vi.fn(),
    cancelTransfer: vi.fn(),
    beginReconcileTransfer: vi.fn(),
    getRoleFromToken: vi.fn(),
  }
})

const TID = 'transfer-1'

function summary(o: Partial<api.TransferSummary>): api.TransferSummary {
  return {
    id: 't', transfer_type: 'showroom', transfer_mode: 'round_trip', status: 'preparing',
    note: null, expected_return_at: null, created_by: 'u1', created_at: new Date().toISOString(),
    destination_location_id: 'd1', destination_location_name: 'Vendor A', outstanding_count: 0,
    ...o,
  }
}

function detail(o: Partial<api.TransferDetail>): api.TransferDetail {
  return {
    id: TID, transfer_type: 'showroom', transfer_mode: 'round_trip', status: 'preparing',
    note: null, expected_return_at: null, created_by: 'u1', created_at: new Date().toISOString(),
    closed_by: null, closed_at: null, sent_at: null, sent_by: null, cancelled_at: null,
    cancelled_by: null, reconcile_started_at: null, reconcile_started_by: null,
    destination_location_id: 'd1', destination_location_name: 'Vendor A',
    source_location_id: null, source_location_name: null,
    lines: [], outstandingCount: 0, piecesEverCount: 0,
    ...o,
  }
}

function renderAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/transfers" element={<Transfers />} />
      <Route path="/transfers/:id" element={<TransferDetail />} />
      <Route path="/transfers/:id/scan-out" element={<TransferScanOut />} />
      <Route path="/transfers/:id/reconcile" element={<TransferReconcile />} />
    </Routes>,
    { initialEntries: [path] },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
  vi.mocked(api.listTransferDestinations).mockResolvedValue([])
  vi.mocked(api.listLocations).mockResolvedValue([])
})

describe('Transfers list — tabs, badges, tiles', () => {
  test('lc1 — every status gets its own label; Open and Closed tabs ask for their groups', async () => {
    vi.mocked(api.listOpenTransfers).mockImplementation(async (view) => view === 'closed'
      ? [summary({ id: 'c1', status: 'closed', destination_location_name: 'Loc Closed' }),
         summary({ id: 'c2', status: 'cancelled', destination_location_name: 'Loc Cancelled' })]
      : [summary({ id: 'o1', status: 'preparing', destination_location_name: 'Loc Preparing' }),
         summary({ id: 'o2', status: 'sent', destination_location_name: 'Loc Sent' }),
         summary({ id: 'o3', status: 'reconciling', destination_location_name: 'Loc Reconciling' })])
    const user = userEvent.setup()
    renderAt('/transfers')

    const row = async (dest: string) => (await screen.findByText(dest)).closest('tr')!
    expect(within(await row('Loc Preparing')).getByText('Preparing')).toBeInTheDocument()
    expect(within(await row('Loc Sent')).getByText('Sent')).toBeInTheDocument()
    expect(within(await row('Loc Reconciling')).getByText('Reconciling')).toBeInTheDocument()
    expect(api.listOpenTransfers).toHaveBeenCalledWith('open')

    await user.click(screen.getByRole('button', { name: 'Closed' }))
    await waitFor(() => expect(api.listOpenTransfers).toHaveBeenCalledWith('closed'))
    expect(within(await row('Loc Closed')).getByText('Closed')).toBeInTheDocument()
    expect(within(await row('Loc Cancelled')).getByText('Cancelled')).toBeInTheDocument()
    expect(screen.queryByText('Loc Preparing')).not.toBeInTheDocument()
  })

  test('lc2 — tiles are Sent / Reconciling / Pieces outstanding; preparing is not counted as sent', async () => {
    vi.mocked(api.listOpenTransfers).mockResolvedValue([
      summary({ id: 'a', status: 'preparing', outstanding_count: 1 }),
      summary({ id: 'b', status: 'sent', outstanding_count: 3 }),
      summary({ id: 'c', status: 'reconciling', outstanding_count: 2 }),
      summary({ id: 'd', status: 'reconciling', outstanding_count: 0 }),
    ])
    renderAt('/transfers')

    const tiles = await screen.findByTestId('transfers-summary')
    await waitFor(() => expect(within(tiles).getByText('6')).toBeInTheDocument())
    const tile = (label: string) => within(tiles).getByText(label).parentElement!
    expect(tile('Sent')).toHaveTextContent('1')
    expect(tile('Reconciling')).toHaveTextContent('2')
    expect(tile('Pieces Outstanding')).toHaveTextContent('6')
    expect(within(tiles).queryByText('Open')).not.toBeInTheDocument()
  })
})

describe('Transfer detail — actions per status and mode', () => {
  const actions = () => screen.queryByTestId('transfer-actions')
  const btn = (name: string) => screen.queryByRole('button', { name })

  test('lc3 — preparing, nothing scanned: scan, mark-sent disabled, cancel; nothing else', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'preparing', piecesEverCount: 0 }))
    renderAt(`/transfers/${TID}`)

    await screen.findByTestId('transfer-actions')
    expect(btn('Scan Out More')).toBeInTheDocument()
    expect(btn('Mark as sent')).toBeDisabled()
    expect(btn('Cancel transfer')).toBeInTheDocument()
    expect(btn('Begin Reconcile')).not.toBeInTheDocument()
    expect(btn('Reprint Outstanding Labels')).not.toBeInTheDocument()
    expect(btn('Close')).not.toBeInTheDocument()
  })

  test('lc4 — preparing with pieces: mark-sent enabled, no cancel', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'preparing', piecesEverCount: 2, outstandingCount: 2 }))
    renderAt(`/transfers/${TID}`)

    await screen.findByTestId('transfer-actions')
    expect(btn('Mark as sent')).not.toBeDisabled()
    expect(btn('Cancel transfer')).not.toBeInTheDocument()
  })

  test('lc5 — preparing move (relocate_out): close, never mark-sent', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({
      transfer_mode: 'relocate_out', status: 'preparing', piecesEverCount: 1, outstandingCount: 1,
    }))
    renderAt(`/transfers/${TID}`)

    await screen.findByTestId('transfer-actions')
    expect(btn('Close')).toBeInTheDocument()
    expect(btn('Mark as sent')).not.toBeInTheDocument()
    expect(btn('Begin Reconcile')).not.toBeInTheDocument()
  })

  test('lc6 — sent: begin reconcile + reprint only; scanning is locked', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'sent', piecesEverCount: 2, outstandingCount: 2 }))
    renderAt(`/transfers/${TID}`)

    await screen.findByTestId('transfer-actions')
    expect(btn('Begin Reconcile')).toBeInTheDocument()
    expect(btn('Reprint Outstanding Labels')).toBeInTheDocument()
    expect(btn('Scan Out More')).not.toBeInTheDocument()
    expect(btn('Mark as sent')).not.toBeInTheDocument()
    expect(btn('Cancel transfer')).not.toBeInTheDocument()
  })

  test('lc7 — reconciling: continue + reprint', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'reconciling', piecesEverCount: 1, outstandingCount: 1 }))
    renderAt(`/transfers/${TID}`)

    await screen.findByTestId('transfer-actions')
    expect(btn('Continue Reconcile')).toBeInTheDocument()
    expect(btn('Reprint Outstanding Labels')).toBeInTheDocument()
    expect(btn('Begin Reconcile')).not.toBeInTheDocument()
  })

  test('lc8 — cancelled: read-only banner, no actions', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'cancelled', cancelled_at: new Date().toISOString() }))
    renderAt(`/transfers/${TID}`)

    expect(await screen.findByText('This transfer was cancelled.')).toBeInTheDocument()
    expect(actions()).not.toBeInTheDocument()
  })

  test('lc16 — cancelled: no empty Lines table under the banner', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'cancelled', cancelled_at: new Date().toISOString() }))
    renderAt(`/transfers/${TID}`)

    await screen.findByText('This transfer was cancelled.')
    expect(screen.queryByText('Lines')).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  test('lc17 — subtitle names the mode for a move / bring back, the category for a round trip', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ transfer_mode: 'relocate_out', transfer_type: 'other' }))
    const first = renderAt(`/transfers/${TID}`)
    expect(await screen.findByText('Move to another location')).toBeInTheDocument()
    expect(screen.queryByText('Other')).not.toBeInTheDocument()
    first.unmount()

    vi.mocked(api.getTransfer).mockResolvedValue(detail({
      transfer_mode: 'relocate_return', transfer_type: 'other',
      source_location_id: 'w2', source_location_name: 'Warehouse 2', destination_location_name: 'Main Warehouse',
    }))
    const second = renderAt(`/transfers/${TID}`)
    expect(await screen.findByText('Bring back')).toBeInTheDocument()
    second.unmount()

    vi.mocked(api.getTransfer).mockResolvedValue(detail({ transfer_mode: 'round_trip', transfer_type: 'repair' }))
    renderAt(`/transfers/${TID}`)
    expect(await screen.findByText('Repair')).toBeInTheDocument()
  })
})

describe('Transfer detail — mark as sent and cancel', () => {
  test('lc9 — mark as sent calls the API and reloads into the sent actions', async () => {
    vi.mocked(api.getTransfer)
      .mockResolvedValueOnce(detail({ status: 'preparing', piecesEverCount: 1, outstandingCount: 1 }))
      .mockResolvedValue(detail({ status: 'sent', piecesEverCount: 1, outstandingCount: 1 }))
    vi.mocked(api.markTransferSent).mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderAt(`/transfers/${TID}`)

    await user.click(await screen.findByRole('button', { name: 'Mark as sent' }))

    expect(api.markTransferSent).toHaveBeenCalledWith(TID)
    expect(await screen.findByRole('button', { name: 'Begin Reconcile' })).toBeInTheDocument()
    expect(screen.getByText('Marked as sent')).toBeInTheDocument()
  })

  test('lc10 — a refused mark-sent shows the plain server message, not the code', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'preparing', piecesEverCount: 1, outstandingCount: 1 }))
    vi.mocked(api.markTransferSent).mockRejectedValue(new api.TransferCommandError({
      code: 'TRANSFER_NOT_PREPARING',
      message_en: "This action isn't available for this transfer right now. Refresh the page.",
      message_ar: 'هذا الإجراء غير متاح لعملية النقل هذه حاليًا. حدّث الصفحة.',
    }))
    const user = userEvent.setup()
    renderAt(`/transfers/${TID}`)

    await user.click(await screen.findByRole('button', { name: 'Mark as sent' }))

    expect(await screen.findByText("This action isn't available for this transfer right now. Refresh the page.")).toBeInTheDocument()
    expect(screen.queryByText('TRANSFER_NOT_PREPARING')).not.toBeInTheDocument()
  })

  test('lc11 — cancel asks first, "Keep it" does nothing, confirming cancels and shows the banner', async () => {
    vi.mocked(api.getTransfer)
      .mockResolvedValueOnce(detail({ status: 'preparing', piecesEverCount: 0 }))
      .mockResolvedValue(detail({ status: 'cancelled', cancelled_at: new Date().toISOString() }))
    vi.mocked(api.cancelTransfer).mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderAt(`/transfers/${TID}`)

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }))
    expect(screen.getByText('Cancel this transfer? Nothing was scanned on it.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Keep it' }))
    expect(api.cancelTransfer).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Cancel transfer' }))
    const dialogButtons = screen.getAllByRole('button', { name: 'Cancel transfer' })
    await user.click(dialogButtons[dialogButtons.length - 1])

    expect(api.cancelTransfer).toHaveBeenCalledWith(TID)
    expect(await screen.findByText('This transfer was cancelled.')).toBeInTheDocument()
  })

  test('lc12 — a refused cancel shows the plain server message inside the dialog', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'preparing', piecesEverCount: 0 }))
    vi.mocked(api.cancelTransfer).mockRejectedValue(new api.TransferCommandError({
      code: 'TRANSFER_HAS_PIECES',
      message_en: "Pieces were scanned on this transfer, so it can't be cancelled.",
      message_ar: 'تم مسح قطع على عملية النقل هذه، لذلك لا يمكن إلغاؤها.',
    }))
    const user = userEvent.setup()
    renderAt(`/transfers/${TID}`)

    await user.click(await screen.findByRole('button', { name: 'Cancel transfer' }))
    const dialogButtons = screen.getAllByRole('button', { name: 'Cancel transfer' })
    await user.click(dialogButtons[dialogButtons.length - 1])

    expect(await screen.findByText("Pieces were scanned on this transfer, so it can't be cancelled.")).toBeInTheDocument()
  })
})

describe('Scan-out and reconcile screens follow the new statuses', () => {
  test('lc13 — scan-out refuses anything but preparing', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'sent', piecesEverCount: 1, outstandingCount: 1 }))
    renderAt(`/transfers/${TID}/scan-out`)

    expect(await screen.findByText("This transfer isn't accepting scans anymore.")).toBeInTheDocument()
    expect(screen.queryByTestId('transfer-scan-out')).not.toBeInTheDocument()
  })

  test('lc14 — reconcile treats sent as not started and cancelled like closed', async () => {
    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'sent', piecesEverCount: 1, outstandingCount: 1 }))
    const { unmount } = renderAt(`/transfers/${TID}/reconcile`)
    expect(await screen.findByText('This transfer is not in reconciliation')).toBeInTheDocument()
    unmount()

    vi.mocked(api.getTransfer).mockResolvedValue(detail({ status: 'cancelled', cancelled_at: new Date().toISOString() }))
    renderAt(`/transfers/${TID}/reconcile`)
    expect(await screen.findByText('This transfer was cancelled.')).toBeInTheDocument()
  })
})

describe('Bring back form', () => {
  test('lc15 — pieces at the source are a read-only list, no checkboxes', async () => {
    vi.mocked(api.listTransferDestinations).mockResolvedValue([{ id: 'w2', name: 'Warehouse 2', is_fulfillment: false }])
    vi.mocked(api.listLocations).mockResolvedValue([
      { id: 'main', name: 'Main Warehouse', is_fulfillment: true } as api.LocationRow,
    ])
    vi.mocked(api.listOpenTransfers).mockImplementation(async (view) =>
      view === 'all' ? [summary({ transfer_mode: 'relocate_out', status: 'closed' })] : [])
    vi.mocked(api.listReturnablePieces).mockResolvedValue([
      { id: 'p1', barcode: 'PC-1', short_code: 'P000001', variant_id: 'v1', sku: 'SKU1', variant_title: 'Red', product_title: 'Scarf' },
      { id: 'p2', barcode: 'PC-2', short_code: 'P000002', variant_id: 'v2', sku: null, variant_title: 'Blue', product_title: 'Scarf' },
    ])
    const user = userEvent.setup()
    renderAt('/transfers')

    const newBtn = (await screen.findAllByRole('button', { name: '+ New transfer' }))[0]
    await waitFor(() => expect(newBtn).not.toBeDisabled())
    await user.click(newBtn)
    await user.click(await screen.findByTestId('chooser-option-return'))
    await user.click(await screen.findByText('Select source location…'))
    await user.click(await screen.findByText('Warehouse 2'))

    const list = await screen.findByTestId('returnable-pieces')
    expect(within(list).getByText(/Red/)).toBeInTheDocument()
    expect(within(list).getByText(/Blue/)).toBeInTheDocument()
    expect(screen.getByText("Pieces at this location — scan the ones you're bringing back next.")).toBeInTheDocument()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.queryByText('Select all')).not.toBeInTheDocument()
  })
})
