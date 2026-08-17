import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import StockTakeScan from '../pages/StockTakeScan'

// ── Mock ─────────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getStockTakeSession: vi.fn(),
    scanStockTakePiece:  vi.fn(),
    unscanStockTakePiece: vi.fn(),
    cancelStockTake: vi.fn(),
  }
})

const SESSION_ID = 'session-001'

function renderScan() {
  return renderWithProviders(
    <Routes>
      <Route path="/stock-take/:id/scan" element={<StockTakeScan />} />
    </Routes>,
    { initialEntries: [`/stock-take/${SESSION_ID}/scan`] },
  )
}

function makeSessionDetail(): api.StockTakeSessionDetail {
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
  }
}

function makeScanResult(overrides: Partial<api.StockTakeScanResult> = {}): api.StockTakeScanResult {
  return {
    sessionId: SESSION_ID,
    barcode: 'PC-ABC123',
    pieceId: 'piece-1',
    classification: 'match',
    alreadyScanned: false,
    ...overrides,
  }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('FR-21 Step 6.3 — Stock Take Scan screen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getStockTakeSession).mockResolvedValue(makeSessionDetail())
  })

  // sts1: default condition is GOOD; scanning sends condition='good'
  test('sts1 — default condition GOOD is sent on scan', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(makeScanResult())
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-ABC123')
    await userEvent.keyboard('{Enter}')

    await waitFor(() =>
      expect(api.scanStockTakePiece).toHaveBeenCalledWith(SESSION_ID, 'PC-ABC123', 'good')
    )
  })

  // sts2: mode toggle flips condition — subsequent scans send 'damaged'
  test('sts2 — condition mode toggle sets condition on scan', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(makeScanResult({ classification: 'condition_mismatch' }))
    renderScan()

    const damagedBtn = await screen.findByTestId('condition-damaged-btn')
    await userEvent.click(damagedBtn)

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-XYZ999')
    await userEvent.keyboard('{Enter}')

    await waitFor(() =>
      expect(api.scanStockTakePiece).toHaveBeenCalledWith(SESSION_ID, 'PC-XYZ999', 'damaged')
    )
  })

  // sts3: match classification -> counted increments, success feedback shown
  test('sts3 — match classification counts and shows success feedback', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(makeScanResult({ classification: 'match' }))
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-ABC123')
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByTestId('counted-display')).toHaveTextContent('1'))
    const feedback = await screen.findByTestId('last-scan-feedback')
    expect(feedback).toHaveTextContent('Match')
    await waitFor(() => expect(screen.getByTestId('tally-matched')).toHaveTextContent('1'))
  })

  // sts4: unknown classification -> NOT counted, distinct "unknown barcode" feedback shown
  test('sts4 — unknown classification does not count, shows unknown-barcode feedback', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(
      makeScanResult({ classification: 'unknown', pieceId: null })
    )
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'GARBAGE')
    await userEvent.keyboard('{Enter}')

    const feedback = await screen.findByTestId('last-scan-feedback')
    expect(feedback).toHaveTextContent('Unknown barcode')
    expect(screen.getByTestId('counted-display')).toHaveTextContent('0')
    await waitFor(() => expect(screen.getByTestId('tally-unknown')).toHaveTextContent('1'))
  })

  // sts7: unexpected_resurfaced and out_of_scope land in SEPARATE tally buckets — the
  // 4->5 split approved in this restyle. Neither one is the blind-count leak (both stay
  // outcomes-only, no denominator).
  test('sts7 — resurfaced and out-of-scope tally into distinct buckets, not merged', async () => {
    vi.mocked(api.scanStockTakePiece)
      .mockResolvedValueOnce(makeScanResult({ classification: 'unexpected_resurfaced', pieceId: 'piece-r' }))
      .mockResolvedValueOnce(makeScanResult({ classification: 'out_of_scope', pieceId: 'piece-o', barcode: 'PC-OOS999' }))
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-ABC123')
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.getByTestId('tally-resurfaced')).toHaveTextContent('1'))
    expect(screen.getByTestId('tally-outOfScope')).toHaveTextContent('0')

    await userEvent.clear(input)
    await userEvent.type(input, 'PC-OOS999')
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.getByTestId('tally-outOfScope')).toHaveTextContent('1'))
    expect(screen.getByTestId('tally-resurfaced')).toHaveTextContent('1')
  })

  // sts8: Abandon count opens a confirm modal and, on confirm, calls cancelStockTake ONLY —
  // never scanStockTakePiece or any resolve/finalize endpoint. This is the UI's first entry
  // point to cancel(); it must never bleed into the write-off path.
  test('sts8 — abandon count confirms and calls cancelStockTake only', async () => {
    vi.mocked(api.cancelStockTake).mockResolvedValue(undefined)
    renderScan()

    const abandonLink = await screen.findByTestId('abandon-link')
    await userEvent.click(abandonLink)

    expect(await screen.findByText('Abandon this count?')).toBeInTheDocument()

    const confirmBtn = screen.getByTestId('confirm-abandon')
    await userEvent.click(confirmBtn)

    await waitFor(() => expect(api.cancelStockTake).toHaveBeenCalledWith(SESSION_ID))
    expect(api.scanStockTakePiece).not.toHaveBeenCalled()
  })

  // sts5: unscan calls the API and decrements the tally/counted
  test('sts5 — unscan removes the entry and decrements counted', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(makeScanResult({ classification: 'match', pieceId: 'piece-9' }))
    vi.mocked(api.unscanStockTakePiece).mockResolvedValue(undefined)
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-ABC123')
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByTestId('counted-display')).toHaveTextContent('1'))

    const unscanBtns = await screen.findAllByTitle('Unscan')
    await userEvent.click(unscanBtns[0])

    await waitFor(() =>
      expect(api.unscanStockTakePiece).toHaveBeenCalledWith(SESSION_ID, 'piece-9')
    )
    await waitFor(() => expect(screen.getByTestId('counted-display')).toHaveTextContent('0'))
  })

  // sts6: idempotent re-scan (alreadyScanned=true) does not inflate the count
  test('sts6 — alreadyScanned result does not increment counted again', async () => {
    vi.mocked(api.scanStockTakePiece).mockResolvedValue(
      makeScanResult({ classification: 'match', alreadyScanned: true })
    )
    renderScan()

    const input = await screen.findByPlaceholderText('Scan barcode…')
    await userEvent.type(input, 'PC-ABC123')
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(api.scanStockTakePiece).toHaveBeenCalled())
    expect(screen.getByTestId('counted-display')).toHaveTextContent('0')
  })
})
