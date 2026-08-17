import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import StockTake from '../pages/StockTake'

// StockTake.tsx (landing) is not itself Layout-wrapped — App.tsx wraps the ROUTE in
// <Layout>, the page component doesn't render it — so this follows the same api-mock
// pattern as stocktakeScan.test.tsx/stocktakeReview.test.tsx, no stubFetchWithShellDefaults
// needed (no real fetch/Layout background calls in play here).

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listStockTakeSessions: vi.fn(),
    getStockTakeSummary:   vi.fn(),
  }
})

function renderLanding() {
  return renderWithProviders(
    <Routes>
      <Route path="/stock-take" element={<StockTake />} />
    </Routes>,
    { initialEntries: ['/stock-take'] },
  )
}

function makeSession(overrides: Partial<api.StockTakeSessionSummary> = {}): api.StockTakeSessionSummary {
  return {
    sessionId: '11111111-2222-3333-4444-555555555555',
    status: 'finalized',
    scopeType: 'all',
    openedBy: 'user-1',
    openedByName: 'Aya Farouk',
    openedAt: new Date().toISOString(),
    coveragePercent: 100,
    counted: 8,
    expected: 10,
    ...overrides,
  }
}

function makeSummary(overrides: Partial<api.StockTakeSummaryCounts> = {}): api.StockTakeSummaryCounts {
  return {
    countsThisMonth: 3,
    piecesWrittenOff: 5,
    avgVariancePercent: 12.5,
    openSessions: 0,
    ...overrides,
  }
}

describe('FR-21 Stock-take landing (Returns-pattern restyle)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getStockTakeSummary).mockResolvedValue(makeSummary())
  })

  // st1: populated table shows a mono Session column derived from sessionId
  test('st1 — session column renders a mono short id derived from sessionId', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([makeSession()])
    renderLanding()

    expect(await screen.findByText('ST-1111')).toBeInTheDocument()
  })

  // st2: empty state
  test('st2 — empty tenant shows the empty state', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([])
    renderLanding()

    expect(await screen.findByText('No stock takes yet')).toBeInTheDocument()
  })

  // st3: error state
  test('st3 — load failure shows an error alert', async () => {
    vi.mocked(api.listStockTakeSessions).mockRejectedValue(new Error('network down'))
    renderLanding()

    expect(await screen.findByText('network down')).toBeInTheDocument()
  })

  // st4: an open session swaps the primary CTA to Resume and shows the already-open note,
  // instead of the ordinary "+ New Count" button.
  test('st4 — open session shows Resume CTA + already-open note, not + New Count', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([
      makeSession({ sessionId: '99999999-aaaa-bbbb-cccc-dddddddddddd', status: 'open', counted: 42 }),
    ])
    renderLanding()

    expect(await screen.findByText('Resume count ST-9999')).toBeInTheDocument()
    expect(screen.getByText(/A count is already open \(42 pieces scanned so far\)/)).toBeInTheDocument()
    expect(screen.queryByText('+ New Count')).not.toBeInTheDocument()
  })

  // st5: multiple open sessions (the latent no-unique-index gap) — resumes the MOST
  // RECENT one rather than crashing or picking arbitrarily.
  test('st5 — defensive against more than one open session: resumes the most recent', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([
      makeSession({ sessionId: '11111111-0000-0000-0000-000000000000', status: 'open', openedAt: '2026-08-01T00:00:00Z' }),
      makeSession({ sessionId: '22222222-0000-0000-0000-000000000000', status: 'open', openedAt: '2026-08-15T00:00:00Z' }),
    ])
    renderLanding()

    expect(await screen.findByText('Resume count ST-2222')).toBeInTheDocument()
  })

  // st6: analytics band renders all four tiles from the summary endpoint
  test('st6 — analytics band renders all four tiles', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([makeSession()])
    renderLanding()

    const band = await screen.findByTestId('stocktake-summary')
    expect(band).toHaveTextContent('Counts this month')
    expect(band).toHaveTextContent('3')
    expect(band).toHaveTextContent('Total pieces written off')
    expect(band).toHaveTextContent('5')
    expect(band).toHaveTextContent('Average variance')
    expect(band).toHaveTextContent('12.5%')
    expect(band).toHaveTextContent('Open sessions')
  })

  // st7: avgVariancePercent === null renders a calm "no data" label, never a literal 0.0%
  test('st7 — null avgVariancePercent renders "No counts yet", not 0%', async () => {
    vi.mocked(api.getStockTakeSummary).mockResolvedValue(makeSummary({ avgVariancePercent: null }))
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([makeSession()])
    renderLanding()

    const band = await screen.findByTestId('stocktake-summary')
    expect(band).toHaveTextContent('No counts yet')
    expect(band).not.toHaveTextContent('0%')
  })

  // st8: finalized row shows a variance figure (counted - expected), derived from the
  // existing per-row payload — no new fetch.
  test('st8 — finalized row shows variance; cancelled row shows a dash', async () => {
    vi.mocked(api.listStockTakeSessions).mockResolvedValue([
      makeSession({ sessionId: '33333333-0000-0000-0000-000000000000', status: 'finalized', counted: 7, expected: 10 }),
      makeSession({ sessionId: '44444444-0000-0000-0000-000000000000', status: 'cancelled' }),
    ])
    renderLanding()

    await screen.findByText('ST-3333')
    expect(screen.getByText('-3')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  // st9: pagination — client-side slicing of the already-fetched full array
  test('st9 — pagination slices the fetched list, Next/Prev navigate pages', async () => {
    const many = Array.from({ length: 12 }, (_, i) => makeSession({
      sessionId: `${i}0000000-0000-0000-0000-000000000000`,
      openedAt: new Date(Date.now() - i * 60000).toISOString(),
    }))
    vi.mocked(api.listStockTakeSessions).mockResolvedValue(many)
    renderLanding()

    await screen.findByText('Showing 1–10 of 12')
    expect(screen.getAllByText(/^ST-/).length).toBe(10)

    await userEvent.click(screen.getByText('Next'))
    await waitFor(() => expect(screen.getByText('Showing 11–12 of 12')).toBeInTheDocument())
    expect(screen.getAllByText(/^ST-/).length).toBe(2)
  })
})
