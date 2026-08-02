import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import StockTakeReview from '../pages/StockTakeReview'

// ── Mock ─────────────────────────────────────────────────────────────────────
// StockTakeCommittedError is kept from `actual` (via the ...actual spread) so
// `e instanceof StockTakeCommittedError` inside the component still works —
// same pattern adjust.test.tsx uses for the real api.ts error/type exports.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getStockTakeReconciliation: vi.fn(),
    getStockTakeSession:        vi.fn(),
    resolveStockTake:           vi.fn(),
    attestStockTakeComplete:    vi.fn(),
    finalizeStockTake:          vi.fn(),
    releasePieceForAdjust:      vi.fn(),
    markStockTakeSyncResolved:  vi.fn(),
    repushStockTakeSync:        vi.fn(),
  }
})

const SESSION_ID = 'session-001'

function renderReview() {
  return renderWithProviders(
    <Routes>
      <Route path="/stock-take/:id/review" element={<StockTakeReview />} />
    </Routes>,
    { initialEntries: [`/stock-take/${SESSION_ID}/review`] },
  )
}

function makeSession(overrides: Partial<api.StockTakeSessionDetail> = {}): api.StockTakeSessionDetail {
  return {
    sessionId: SESSION_ID,
    status: 'open',
    scopeType: 'all',
    locationId: 'loc-1',
    completeCount: false,
    openedBy: 'user-1',
    openedByName: 'Owner',
    openedAt: new Date().toISOString(),
    finalizedBy: null,
    finalizedByName: null,
    finalizedAt: null,
    note: null,
    shopifySync: null,
    ...overrides,
  }
}

function makeReconciliation(overrides: Partial<api.StockTakeReconciliation> = {}): api.StockTakeReconciliation {
  return {
    sessionId: SESSION_ID,
    status: 'open',
    completeCount: false,
    coveragePercent: 50,
    buckets: {
      on_shelf_counted: [],
      on_shelf_uncounted: [],
      committed_to_orders: [],
      with_courier_or_delivered: [],
      returns_bench: [],
      damaged: [],
      previously_written_off: [],
      unexpected_finds: [],
    },
    variantRollup: [],
    ...overrides,
  }
}

function freePieceRow(): api.StockTakePieceRow {
  return {
    pieceId: 'piece-free-1',
    variantId: 'v1',
    variantTitle: 'Variant A',
    sku: 'SKU-A',
    productTitle: 'Widget',
    liveStatus: 'available',
  }
}

function committedPieceRow(): api.StockTakePieceRow {
  return {
    pieceId: 'piece-committed-1',
    variantId: 'v2',
    variantTitle: 'Variant B',
    sku: 'SKU-B',
    productTitle: 'Gadget',
    liveStatus: 'reserved',
    orderId: 'order-1',
    orderNumber: '1001',
  }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('FR-21 Step 6.3 — Stock Take Review screen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // str1: Mark lost is disabled pre-attest, enabled after attest-complete
  test('str1 — Mark lost disabled pre-attest, enabled post-attest', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      makeReconciliation({ buckets: { ...makeReconciliation().buckets, on_shelf_uncounted: [freePieceRow()] } })
    )
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: false }))
    vi.mocked(api.attestStockTakeComplete).mockResolvedValue({ sessionId: SESSION_ID, completeCount: true })

    renderReview()

    const markLostBtns = await screen.findAllByText('Mark lost')
    expect(markLostBtns[0]).toBeDisabled()

    // After attest-complete, re-fetch returns completeCount=true.
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      makeReconciliation({
        completeCount: true,
        buckets: { ...makeReconciliation().buckets, on_shelf_uncounted: [freePieceRow()] },
      })
    )

    const attestBtn = await screen.findByText('Attest Complete')
    await userEvent.click(attestBtn)

    await waitFor(() => expect(api.attestStockTakeComplete).toHaveBeenCalledWith(SESSION_ID))
    await waitFor(async () => {
      const btns = await screen.findAllByText('Mark lost')
      expect(btns[0]).not.toBeDisabled()
    })
  })

  // str2: committed Mark-lost renders the AdjustPanel-style release card on 409
  test('str2 — committed Mark-lost renders the inline release card', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      makeReconciliation({
        completeCount: true,
        buckets: { ...makeReconciliation().buckets, committed_to_orders: [committedPieceRow()] },
      })
    )
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))
    vi.mocked(api.resolveStockTake).mockRejectedValue(
      new api.StockTakeCommittedError({ error: 'PIECE_COMMITTED', orderId: 'order-1', orderNumber: '1001' })
    )

    renderReview()

    const markLostBtn = await screen.findByText('Mark lost')
    await userEvent.click(markLostBtn)

    expect(await screen.findByText('Piece is committed to an order')).toBeInTheDocument()
    expect(screen.getByText('Release from order')).toBeInTheDocument()
    expect(
      screen.getAllByText((_content, el) => el?.textContent === '#1001').length
    ).toBeGreaterThan(0)
  })

  // str3: finalize shows a confirmation modal before calling POST /finalize
  test('str3 — finalize requires confirmation', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(makeReconciliation({ completeCount: true }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))
    vi.mocked(api.finalizeStockTake).mockResolvedValue({ sessionId: SESSION_ID, status: 'finalized', variantDeltas: [] })

    renderReview()

    const finalizeBtn = await screen.findByText('Finalize')
    await userEvent.click(finalizeBtn)

    expect(await screen.findByText('Finalize stock take?')).toBeInTheDocument()
    expect(api.finalizeStockTake).not.toHaveBeenCalled()

    const confirmBtn = screen.getByText('Finalize + Push to Shopify')
    await userEvent.click(confirmBtn)

    await waitFor(() => expect(api.finalizeStockTake).toHaveBeenCalledWith(SESSION_ID))
  })

  // str4: failed_ambiguous sync panel buttons call the right endpoints
  test('str4 — failed_ambiguous buttons call mark-resolved and repush', async () => {
    const finalizedSession = makeSession({
      status: 'finalized',
      completeCount: true,
      shopifySync: {
        status: 'failed_ambiguous',
        deltas: [{ variantId: 'v1', variantTitle: 'Variant A', sku: 'SKU-A', delta: -2 }],
        referenceDocumentUri: `traced://stock-take/${SESSION_ID}`,
        pushedAt: null,
        error: 'timeout',
      },
    })
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(makeReconciliation({ status: 'finalized', completeCount: true }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(finalizedSession)
    vi.mocked(api.markStockTakeSyncResolved).mockResolvedValue({ sessionId: SESSION_ID, status: 'pushed' })
    vi.mocked(api.repushStockTakeSync).mockResolvedValue({ sessionId: SESSION_ID, status: 'pending' })

    renderReview()

    expect(await screen.findByText('Shopify sync needs verification')).toBeInTheDocument()

    const markResolvedBtn = screen.getByText('Mark resolved')
    await userEvent.click(markResolvedBtn)
    await waitFor(() => expect(api.markStockTakeSyncResolved).toHaveBeenCalledWith(SESSION_ID))

    const repushBtn = screen.getByText('Re-push')
    await userEvent.click(repushBtn)
    await waitFor(() => expect(api.repushStockTakeSync).toHaveBeenCalledWith(SESSION_ID))
  })
})
