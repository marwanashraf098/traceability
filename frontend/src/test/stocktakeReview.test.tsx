import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import StockTakeReview from '../pages/StockTakeReview'

// ── Mock ─────────────────────────────────────────────────────────────────────
// The real api.ts error/type exports are kept from `actual` (via the ...actual spread) —
// same pattern adjust.test.tsx uses.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getStockTakeReconciliation: vi.fn(),
    getStockTakeSession:        vi.fn(),
    resolveStockTake:           vi.fn(),
    attestStockTakeComplete:    vi.fn(),
    finalizeStockTake:          vi.fn(),
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

  // str1 (2026-10-27): uncounted rows are read-only — no Found it / Mark lost / row checkboxes / bulk
  // action; attest-complete still works
  test('str1 — uncounted rows have no Found it / Mark lost; attest still works', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      makeReconciliation({ buckets: { ...makeReconciliation().buckets, on_shelf_uncounted: [freePieceRow()] } })
    )
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: false }))
    vi.mocked(api.attestStockTakeComplete).mockResolvedValue({ sessionId: SESSION_ID, completeCount: true })

    renderReview()

    expect(await screen.findByText('Widget · Variant A')).toBeInTheDocument()
    expect(screen.queryByText('Found it')).toBeNull()
    expect(screen.queryByText('Mark lost')).toBeNull()
    expect(screen.queryByText('Found it (selected)')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()

    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))
    await userEvent.click(await screen.findByText('Attest Complete'))
    await waitFor(() => expect(api.attestStockTakeComplete).toHaveBeenCalledWith(SESSION_ID))
    expect(api.resolveStockTake).not.toHaveBeenCalled()
  })

  // str2 (2026-10-27): a committed row links to its order and offers no write-off
  test('str2 — committed rows link to the order, no Mark lost', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      makeReconciliation({
        completeCount: true,
        buckets: { ...makeReconciliation().buckets, committed_to_orders: [committedPieceRow()] },
      })
    )
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))

    renderReview()

    expect(await screen.findByText('Gadget · Variant B')).toBeInTheDocument()
    expect(
      screen.getAllByText((_content, el) => el?.textContent === '#1001').length
    ).toBeGreaterThan(0)
    expect(screen.queryByText('Mark lost')).toBeNull()
    expect(screen.queryByText('Release from order')).toBeNull()
  })

  // str3: finalize shows a confirmation modal before calling POST /finalize, AND the
  // confirm button stays disabled until the "I understand..." checkbox is ticked — the
  // friction must gate, not decorate.
  test('str3 — finalize requires confirmation AND the understand-checkbox actually gates', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(makeReconciliation({ completeCount: true }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))
    vi.mocked(api.finalizeStockTake).mockResolvedValue({ sessionId: SESSION_ID, status: 'finalized', variantDeltas: [] })

    renderReview()

    const finalizeBtn = await screen.findByText('Finalize')
    await userEvent.click(finalizeBtn)

    expect(await screen.findByText('Finalize stock take?')).toBeInTheDocument()
    expect(api.finalizeStockTake).not.toHaveBeenCalled()

    const confirmBtn = screen.getByText('Finalize + Push to Shopify').closest('button')!
    expect(confirmBtn).toBeDisabled()

    // Clicking a disabled button must not fire the handler.
    await userEvent.click(confirmBtn)
    expect(api.finalizeStockTake).not.toHaveBeenCalled()

    const understandCheckbox = screen.getByText('I understand this permanently decrements Shopify inventory.')
    await userEvent.click(understandCheckbox)
    expect(confirmBtn).not.toBeDisabled()

    await userEvent.click(confirmBtn)
    await waitFor(() => expect(api.finalizeStockTake).toHaveBeenCalledWith(SESSION_ID))
  })

  // str5: re-opening the finalize modal resets the checkbox — a stale "understood" from a
  // prior open/cancel must never survive.
  test('str5 — re-opening the finalize modal resets the understand-checkbox', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(makeReconciliation({ completeCount: true }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSession({ completeCount: true }))

    renderReview()

    await userEvent.click(await screen.findByText('Finalize'))
    await userEvent.click(screen.getByText('I understand this permanently decrements Shopify inventory.'))
    const firstConfirmBtn = screen.getByText('Finalize + Push to Shopify').closest('button')!
    expect(firstConfirmBtn).not.toBeDisabled()

    await userEvent.click(screen.getByText('Cancel'))
    expect(screen.queryByText('Finalize stock take?')).not.toBeInTheDocument()

    await userEvent.click(screen.getByText('Finalize'))
    const secondConfirmBtn = screen.getByText('Finalize + Push to Shopify').closest('button')!
    expect(secondConfirmBtn).toBeDisabled()
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
