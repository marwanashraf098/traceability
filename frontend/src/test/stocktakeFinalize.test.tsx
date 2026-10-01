import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import StockTakeReview from '../pages/StockTakeReview'

// Stock-take finalize applies the count (2026-10-01): the modal shows the backend's finalize plan
// (the numbers finalize will act on), typed confirmation gates when the plan asks for it, a
// zero-scan session can't be finalized, and variance is positive when pieces are short.

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

const SESSION_ID = 'session-fin'

function renderReview() {
  return renderWithProviders(
    <Routes>
      <Route path="/stock-take/:id/review" element={<StockTakeReview />} />
    </Routes>,
    { initialEntries: [`/stock-take/${SESSION_ID}/review`] },
  )
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
    scans: 10, expectedFree: 13, scannedFree: 10, coveragePercent: 76.92,
    writeOffs: 3, damageCorrections: 1, founds: 0, foundIncrements: 0, driftSkipped: 0, alreadyWrittenOff: 0,
    shopifyDecrement: 2,
    byVariant: [
      { variantId: 'va', variantTitle: 'Blue / M', sku: 'A', available: 2, damaged: 0, onHold: 0,
        alreadyWrittenOff: 0, driftSkipped: 0, shopifyDecrement: 2 },
      { variantId: 'vb', variantTitle: 'Red / L', sku: 'B', available: 0, damaged: 1, onHold: 0,
        alreadyWrittenOff: 0, driftSkipped: 0, shopifyDecrement: 0 },
    ],
    requiresTypedConfirmation: false, minCoveragePercent: 80, maxWriteOffPercent: 10, blockedReason: null,
    ...overrides,
  }
}

function reconciliation(p: api.StockTakeFinalizePlan | undefined,
                        overrides: Partial<api.StockTakeReconciliation> = {}): api.StockTakeReconciliation {
  return {
    sessionId: SESSION_ID, status: 'open', completeCount: true, coveragePercent: 76.92,
    buckets: {
      on_shelf_counted: [], on_shelf_uncounted: [], committed_to_orders: [], with_courier_or_delivered: [],
      returns_bench: [], damaged: [], previously_written_off: [], unexpected_finds: [],
    },
    variantRollup: [
      { variantId: 'va', variantTitle: 'Blue / M', sku: 'A', totalKnown: 11, expectedOnShelf: 11, counted: 9,
        variance: 2, committed: 0, gone: 0, damagedCount: 0 },
    ],
    finalizePlan: p,
    ...overrides,
  }
}

async function openModal() {
  await userEvent.click(await screen.findByText('Finalize'))
  expect(await screen.findByText('Finalize stock take?')).toBeInTheDocument()
}

describe('Stock take finalize applies the count', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session())
    vi.mocked(api.finalizeStockTake).mockResolvedValue({ sessionId: SESSION_ID, status: 'finalized', variantDeltas: [] })
  })

  test('sf1 — the modal shows the plan: write-offs, Shopify decrement, per variant, damaged in Traced only', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(plan()))
    renderReview()
    await openModal()

    expect(screen.getByTestId('finalize-summary')).toHaveTextContent(
      '3 piece(s) not found on the shelf will be written off in Traced. Shopify stock at the Traced location goes down by 2.')
    const variants = screen.getByTestId('finalize-variants')
    expect(variants).toHaveTextContent('Blue / M')
    expect(variants).toHaveTextContent('−2 (Shopify −2)')
    expect(variants).toHaveTextContent('Red / L')
    expect(variants).toHaveTextContent('−1 (Shopify −0)')
    expect(screen.getByText(/1 damaged or on-hold piece\(s\) are written off in Traced only/)).toBeInTheDocument()
    expect(screen.getByText('1 piece(s) scanned as damaged will be marked damaged.')).toBeInTheDocument()
    expect(screen.queryByTestId('finalize-typed-count')).not.toBeInTheDocument()

    await userEvent.click(screen.getByText('I understand this permanently decrements Shopify inventory.'))
    await userEvent.click(screen.getByText('Finalize + Push to Shopify'))
    await waitFor(() => expect(api.finalizeStockTake).toHaveBeenCalledWith(SESSION_ID))
  })

  test('sf2 — typed confirmation: disabled until the exact write-off count is typed, then sent', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(plan({ requiresTypedConfirmation: true })))
    renderReview()
    await openModal()

    await userEvent.click(screen.getByText('I understand this permanently decrements Shopify inventory.'))
    const confirm = screen.getByText('Finalize + Push to Shopify').closest('button')!
    expect(confirm).toBeDisabled()

    const typed = screen.getByTestId('finalize-typed-count')
    await userEvent.type(typed, '2')
    expect(confirm).toBeDisabled()

    await userEvent.clear(typed)
    await userEvent.type(typed, '3')
    expect(confirm).not.toBeDisabled()
    await userEvent.click(confirm)
    await waitFor(() => expect(api.finalizeStockTake).toHaveBeenCalledWith(SESSION_ID, 3))
  })

  test('sf3 — zero scans: blocked, no finalize', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(
      reconciliation(plan({ scans: 0, writeOffs: 13, blockedReason: 'ZERO_SCANS' })))
    renderReview()
    await openModal()

    expect(screen.getByText(/Nothing was scanned in this count/)).toBeInTheDocument()
    const confirm = screen.getByText('Finalize + Push to Shopify').closest('button')!
    expect(confirm).toBeDisabled()
    await userEvent.click(confirm)
    expect(api.finalizeStockTake).not.toHaveBeenCalled()
  })

  test('sf4 — variance: positive = short, shown as a shortage', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(plan()))
    renderReview()

    const headline = await screen.findByTestId('variance-headline')
    expect(headline).toHaveTextContent('2')
    expect(headline.parentElement).toHaveClass('text-danger')
    const cell = screen.getAllByText('2').find(el => el.tagName === 'TD')!
    expect(cell).toHaveClass('text-danger')
  })

  test('sf5 — nothing_to_push sync status is shown as such, not as a push', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(undefined, { status: 'finalized' }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({
      status: 'finalized', finalizedBy: 'user-1', finalizedByName: 'Owner', finalizedAt: new Date().toISOString(),
      shopifySync: { status: 'nothing_to_push', deltas: [], pushedAt: null, error: null, referenceDocumentUri: '' },
    }))
    renderReview()

    expect(await screen.findByText('Nothing to send to Shopify — no available stock was written off')).toBeInTheDocument()
    expect(screen.getByText('Nothing to send')).toBeInTheDocument()
    expect(screen.queryByText('Pushed to Shopify')).not.toBeInTheDocument()
  })

  test('sf6 — close summary: written off counts every write-off in Traced; pushed to Shopify is separate', async () => {
    vi.mocked(api.getStockTakeReconciliation).mockResolvedValue(reconciliation(undefined, { status: 'finalized' }))
    vi.mocked(api.getStockTakeSession).mockResolvedValue(session({
      status: 'finalized', finalizedBy: 'user-1', finalizedByName: 'Owner', finalizedAt: new Date().toISOString(),
      writtenOff: 3, pushedToShopify: 2,
      shopifySync: { status: 'pushed', deltas: [{ variantId: 'va', variantTitle: 'Blue / M', sku: 'A', delta: -2 }],
        pushedAt: new Date().toISOString(), error: null, referenceDocumentUri: 'traced://stock-take/x' },
    }))
    renderReview()

    await screen.findByText('Count finalized')
    // Each summary stat is <p>value</p><p>label</p>.
    const stat = (label: string) => screen.getByText(label, { selector: 'p' }).previousElementSibling!.textContent
    expect(stat('written off')).toBe('3')
    expect(stat('pushed to Shopify')).toBe('2')
  })
})
