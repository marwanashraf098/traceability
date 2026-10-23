import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import StockTakeScan from '../pages/StockTakeScan'
import StockTakeReview from '../pages/StockTakeReview'

// Flexible labeling slice 7 — untagged pieces are counted per product by a typed number.
//   su1  counting screen: "Untagged: expected X · counted [__]" per product, a plain number input
//   su2  the count saves on leaving the field (typed number; blank → cleared), never auto-focused, and
//        clicking into it keeps the cursor there (the scan bar's click-refocus doesn't take it back)
//   su3  Enter hands focus back to the scan bar (and saves once)
//   su4  switch off (no untagged key): no panel, the counting screen exactly as before
//   su5  review: per-product outcome — written off as lost / extra found (receive with count-only) / missing
//   su6  finalize modal: blank count blocks with the product count; write-offs and extras listed per product,
//        the summary count includes untagged write-offs

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getStockTakeSession:         vi.fn(),
    scanStockTakePiece:          vi.fn(),
    unscanStockTakePiece:        vi.fn(),
    cancelStockTake:             vi.fn(),
    getStockTakeUntaggedCounts:  vi.fn(),
    setStockTakeUntaggedCount:   vi.fn(),
    getStockTakeReconciliation:  vi.fn(),
    finalizeStockTake:           vi.fn(),
  }
})

const SESSION_ID = 'session-unt'

function row(overrides: Partial<api.StockTakeUntaggedRow> = {}): api.StockTakeUntaggedRow {
  return {
    variantId: 'va', variantTitle: 'Blue / M', sku: 'BLU-M', productTitle: 'Hoodie',
    expected: 3, counted: null, writeOff: 0, extra: 0, ...overrides,
  }
}

function session(overrides: Partial<api.StockTakeSessionDetail> = {}): api.StockTakeSessionDetail {
  return {
    sessionId: SESSION_ID, status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: true,
    openedBy: 'user-1', openedByName: 'Owner', openedAt: new Date().toISOString(),
    finalizedBy: null, finalizedByName: null, finalizedAt: null, note: null, shopifySync: null,
    ...overrides,
  }
}

function plan(overrides: Partial<api.StockTakeFinalizePlan> = {}): api.StockTakeFinalizePlan {
  return {
    scans: 5, expectedFree: 5, scannedFree: 4, coveragePercent: 80,
    writeOffs: 1, damageCorrections: 0, founds: 0, foundIncrements: 0, driftSkipped: 0, alreadyWrittenOff: 0,
    shopifyDecrement: 3,
    byVariant: [{ variantId: 'va', variantTitle: 'Blue / M', sku: 'BLU-M', available: 1, damaged: 0, onHold: 0,
      alreadyWrittenOff: 0, driftSkipped: 0, shopifyDecrement: 1 }],
    requiresTypedConfirmation: false, minCoveragePercent: 80, maxWriteOffPercent: 10, blockedReason: null,
    untagged: [row({ counted: 1, writeOff: 2 }), row({ variantId: 'vb', variantTitle: 'Red / L', expected: 1, counted: 4, extra: 3 })],
    untaggedWriteOffs: 2, untaggedExtra: 3, untaggedMissingCounts: 0,
    ...overrides,
  }
}

function reconciliation(p: api.StockTakeFinalizePlan, untagged: api.StockTakeUntaggedRow[]): api.StockTakeReconciliation {
  return {
    sessionId: SESSION_ID, status: 'open', completeCount: true, coveragePercent: 80,
    buckets: {
      on_shelf_counted: [], on_shelf_uncounted: [], committed_to_orders: [], with_courier_or_delivered: [],
      returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [],
    },
    variantRollup: [], finalizePlan: p, untagged,
  }
}

function renderScan() {
  return renderWithProviders(
    <Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>,
    { initialEntries: [`/stock-take/${SESSION_ID}/scan`] },
  )
}

function renderReview() {
  return renderWithProviders(
    <Routes><Route path="/stock-take/:id/review" element={<StockTakeReview />} /></Routes>,
    { initialEntries: [`/stock-take/${SESSION_ID}/review`] },
  )
}

describe('Stock take — untagged counts (flexible labeling slice 7)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.setStockTakeUntaggedCount).mockResolvedValue({ sessionId: SESSION_ID, variantId: 'va', counted: 2 })
    vi.mocked(api.getStockTakeUntaggedCounts).mockResolvedValue([row({ counted: 2, writeOff: 1 })])
  })

  test('su1 — the counting screen shows each product with untagged pieces: expected and a number field', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({ untagged: [row()] }))
    renderScan()
    const panel = await screen.findByTestId('untagged-counts')
    expect(panel).toHaveTextContent('Untagged pieces')
    expect(panel).toHaveTextContent('Hoodie · Blue / M')
    expect(panel).toHaveTextContent('Untagged: expected 3')
    const input = within(panel).getByTestId('untagged-input-va')
    expect(input).toHaveAttribute('type', 'number')
    expect(input).toHaveValue(null)
    expect(panel).toHaveTextContent('Enter the count — type 0 if there are none.')
  })

  test('su2 — saves on leaving the field; never auto-focused; a click keeps the cursor in the field; blank clears', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({ untagged: [row({ counted: 1 })] }))
    renderScan()
    const scanInput = await screen.findByPlaceholderText('Scan barcode…')
    const input = await screen.findByTestId('untagged-input-va')
    expect(scanInput).toHaveFocus()                                   // the scan bar keeps the focus on open

    await userEvent.click(input)
    expect(input).toHaveFocus()                                       // not pulled back to the scan bar
    await userEvent.clear(input)
    await userEvent.type(input, '2')
    expect(input).toHaveFocus()
    await userEvent.click(screen.getByTestId('counted-display'))      // leave the field
    await waitFor(() => expect(api.setStockTakeUntaggedCount).toHaveBeenCalledWith(SESSION_ID, 'va', 2))
    expect(api.setStockTakeUntaggedCount).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.getByTestId('untagged-writeoff')).toHaveTextContent('−1 untagged will be written off as lost'))

    await userEvent.click(input)
    await userEvent.clear(input)
    await userEvent.click(screen.getByTestId('counted-display'))
    await waitFor(() => expect(api.setStockTakeUntaggedCount).toHaveBeenLastCalledWith(SESSION_ID, 'va', null))
  })

  test('su3 — Enter returns focus to the scan bar and saves once', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({ untagged: [row()] }))
    renderScan()
    const scanInput = await screen.findByPlaceholderText('Scan barcode…')
    const input = await screen.findByTestId('untagged-input-va')
    await userEvent.click(input)
    await userEvent.type(input, '0{Enter}')
    expect(scanInput).toHaveFocus()
    await waitFor(() => expect(api.setStockTakeUntaggedCount).toHaveBeenCalledWith(SESSION_ID, 'va', 0))
    expect(api.setStockTakeUntaggedCount).toHaveBeenCalledTimes(1)
    expect(api.scanStockTakePiece).not.toHaveBeenCalled()
  })

  test('su4 — switch off: no untagged rows on the wire → no panel', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session())
    renderScan()
    expect(await screen.findByTestId('counted-display')).toBeInTheDocument()
    expect(screen.queryByTestId('untagged-counts')).not.toBeInTheDocument()
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(api.getStockTakeUntaggedCounts).not.toHaveBeenCalled()
  })

  test('su5 — review lists each product: written off as lost, extra found (receive with count-only), missing', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({ status: 'finalized', completeCount: true }))
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue({
      ...reconciliation(plan(), [
        row({ counted: 1, writeOff: 2 }),
        row({ variantId: 'vb', variantTitle: 'Red / L', expected: 1, counted: 4, extra: 3 }),
      ]),
      status: 'finalized', finalizePlan: undefined,
    })
    renderReview()
    const panel = await screen.findByTestId('untagged-counts')
    expect(within(panel).getByTestId('untagged-row-va')).toHaveTextContent('−2 untagged written off as lost')
    expect(within(panel).getByTestId('untagged-row-vb'))
      .toHaveTextContent('3 extra Hoodie · Red / L found — receive them with count-only receiving.')
    expect(within(panel).queryByRole('spinbutton')).not.toBeInTheDocument()   // finalized: read-only
  })

  test('su6 — finalize modal: a blank count blocks; write-offs and extras per product; summary counts both kinds', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session())
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValueOnce(reconciliation(
      plan({ blockedReason: 'UNTAGGED_COUNT_MISSING', untaggedMissingCounts: 2 }), [row(), row({ variantId: 'vb' })]))
    const first = renderReview()
    await userEvent.click(await screen.findByText('Finalize'))
    expect(await screen.findByText('Enter the untagged count for 2 product(s) before finalizing.')).toBeInTheDocument()
    expect(screen.getByText('Finalize + Push to Shopify').closest('button')).toBeDisabled()
    first.unmount()

    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(plan(), plan().untagged!))
    renderReview()
    await userEvent.click(await screen.findByText('Finalize'))
    expect(screen.getByTestId('finalize-summary')).toHaveTextContent(
      '3 piece(s) not found on the shelf will be written off in Traced. Shopify stock at the Traced location goes down by 3.')
    const untagged = screen.getByTestId('finalize-untagged')
    expect(untagged).toHaveTextContent('Blue / M')
    expect(untagged).toHaveTextContent('−2 untagged written off as lost')
    expect(untagged).toHaveTextContent('Red / L')
    expect(untagged).toHaveTextContent('+3 extra found (not added)')
  })
})
