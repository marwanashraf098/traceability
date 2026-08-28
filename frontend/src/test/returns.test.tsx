import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Returns from '../pages/Returns'
import * as api from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}

function jsonErr(data: unknown, status = 500) {
  return Promise.resolve({
    ok: false, status,
    headers: { get: () => 'application/json' },
    json: async () => structuredClone(data),
  })
}

function noContent() {
  return Promise.resolve({
    ok: true, status: 204,
    headers: { get: (k: string) => (k === 'content-length' ? '0' : null) },
    json: async () => null,
  })
}

function makeSessionRow(overrides: Partial<{
  id: string; status: 'open' | 'closed' | 'abandoned'; piece_count: number
  restocked_count: number; damaged_count: number; mismatch_count: number
}> = {}) {
  return {
    id: 'aaaaaaaa-0000-0000-0000-000000000001',
    status: 'closed', opened_by: 'user-1', opened_at: '2026-08-10T10:00:00Z',
    closed_at: '2026-08-10T10:30:00Z',
    piece_count: 2, restocked_count: 2, damaged_count: 0, mismatch_count: 0,
    ...overrides,
  }
}

function makeAnalytics(overrides: Partial<{
  totalReturns: number; restockedCount: number; damagedCount: number; mismatchCount: number
  expectedNotScannedCount: number; unassignedPendingCount: number
  unassignedPending: unknown[]
}> = {}) {
  return {
    totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [],
    ...overrides,
  }
}

function makeItem(overrides: Partial<{
  id: string; piece_id: string; barcode: string; status: string
  disposition: 'pending' | 'restocked' | 'damaged' | 'mismatch'
  unexpected: boolean; damage_reason: string | null
}> = {}) {
  return {
    id: 'item-1', piece_id: 'piece-1', barcode: 'PC-piece-1',
    status: 'return_pending_inspection', variant_title: 'Black · M', product_title: 'T-Shirt',
    sku: 'TS-BLK-M', disposition: 'pending', unexpected: false, damage_reason: null,
    ...overrides,
  }
}

function makeSessionDetail(overrides: Partial<{
  id: string; status: 'open' | 'closed' | 'abandoned'; opened_at: string
  items: unknown[]; expectedPieces: unknown[]
}> = {}) {
  return {
    id: 'aaaaaaaa-0000-0000-0000-000000000001', status: 'open',
    opened_by: 'user-1', opened_at: '2026-08-14T08:14:00Z',
    items: [], expectedPieces: [],
    ...overrides,
  }
}

// ── Harness ───────────────────────────────────────────────────────────────────

let mockFetch: ReturnType<typeof vi.fn>

describe('Returns — session-based rebuild', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    // Landing renders inside the shared Layout shell — see mockShellFetch.ts.
    stubFetchWithShellDefaults(mockFetch)
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ── Landing ───────────────────────────────────────────────────────────────

  test('rl1 landing loading skeleton then populated — analytics band + sessions table', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow()], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics({ totalReturns: 47, restockedCount: 38, damagedCount: 7, mismatchCount: 2, expectedNotScannedCount: 5 })))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('returns-landing'))
    await waitFor(() => screen.getByTestId('analytics-band'))
    expect(screen.getByTestId('sessions-table')).toBeInTheDocument()
    expect(screen.getByText('47')).toBeInTheDocument()
  })

  test('rl2 landing empty tenant — zero sessions', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [], total: 0 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByText(/No return sessions/i))
    expect(screen.queryByTestId('sessions-table')).not.toBeInTheDocument()
  })

  test('rl3 landing error state shows retry', async () => {
    mockFetch
      .mockReturnValueOnce(jsonErr({}, 500))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('landing-error'))
    expect(screen.getByText("Couldn't load sessions")).toBeInTheDocument()
  })

  test('rl4 zero-analytics renders all-zero stat tiles, no unassigned callout', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow()], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('analytics-band'))
    expect(screen.queryByTestId('unassigned-callout')).not.toBeInTheDocument()
  })

  test('rl5 unassigned pending callout shown when count > 0', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow()], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics({ unassignedPendingCount: 5 })))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('unassigned-callout'))
    expect(screen.getByText('5 unassigned pending returns')).toBeInTheDocument()
  })

  test('rl6 already-open session shows Resume button and note, not Open', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow({ status: 'open', piece_count: 3 })], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('already-open-note'))
    expect(screen.getByTestId('open-session-button')).toHaveTextContent(/Resume session/)
    expect(screen.getByText(/3 pieces scanned/)).toBeInTheDocument()
  })

  // ── Entering an open session ──────────────────────────────────────────────

  test('rs1 opening a new session enters the full-screen immersive scan loop', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [], total: 0 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
      .mockReturnValueOnce(jsonOk({ sessionId: 'new-session-id' }))
      .mockReturnValueOnce(jsonOk(makeSessionDetail({ id: 'new-session-id' })))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('open-session-button'))
    await user.click(screen.getByTestId('open-session-button'))
    await waitFor(() => screen.getByTestId('open-session-screen'))
    expect(screen.getByTestId('scan-input')).toBeInTheDocument()
    // Full-screen — no sidebar nav item rendered alongside it.
    expect(screen.queryByText('Overview')).not.toBeInTheDocument()
  })

  test('rs2 empty just-opened session shows "ready to scan"', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [], total: 0 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
      .mockReturnValueOnce(jsonOk({ sessionId: 'new-session-id' }))
      .mockReturnValueOnce(jsonOk(makeSessionDetail({ id: 'new-session-id' })))
    const user = userEvent.setup()
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await waitFor(() => screen.getByText('Ready to scan'))
  })

  // ── Scan outcomes ─────────────────────────────────────────────────────────

  async function enterSession(user: ReturnType<typeof userEvent.setup>, detail: ReturnType<typeof makeSessionDetail>) {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow({ status: 'open', piece_count: detail.items.length })], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
      .mockReturnValueOnce(jsonOk(detail))
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await waitFor(() => screen.getByTestId('open-session-screen'))
  }

  test('rs3 needs-decision legal item shows Restock/Damage/mismatch actions', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem()] }))
    const card = screen.getByTestId('item-piece-1')
    expect(card).toHaveTextContent('NEEDS DECISION')
    expect(card).toHaveTextContent('Restock')
    expect(card).toHaveTextContent('Damage')
  })

  test('rs4 unexpected legal item shows UNEXPECTED pill plus all three actions', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem({ unexpected: true })] }))
    const card = screen.getByTestId('item-piece-1')
    expect(card).toHaveTextContent('UNEXPECTED')
    expect(card).toHaveTextContent('Restock')
    expect(card).toHaveTextContent('Damage')
  })

  test('rs5 illegal-state item (unexpected + status not return_pending_inspection) offers only mismatch', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({
      items: [makeItem({ unexpected: true, status: 'available' })],
    }))
    const card = screen.getByTestId('item-piece-1')
    expect(card).toHaveTextContent('UNEXPECTED')
    expect(card).not.toHaveTextContent('Restock')
    expect(card).not.toHaveTextContent('Damage')
    expect(card).toHaveTextContent('Not the real piece')
  })

  test('rs6 dispositioned restocked item is a single collapsed view — no action buttons', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({
      items: [makeItem({ disposition: 'restocked' })],
    }))
    const card = screen.getByTestId('item-piece-1')
    expect(card).toHaveTextContent('Restocked')
    expect(card).not.toHaveTextContent('NEEDS DECISION')
    expect(card.querySelector('button')).toBeNull()
  })

  test('rs7 dispositioned damaged item shows the reason', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({
      items: [makeItem({ disposition: 'damaged', damage_reason: 'Stained' })],
    }))
    const card = screen.getByTestId('item-piece-1')
    expect(card).toHaveTextContent('Damaged')
    expect(card).toHaveTextContent('reason: Stained')
  })

  test('rs8 expected-awaiting-scan AWB row renders with reprint, no disposition actions', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({
      expectedPieces: [{ id: 'piece-2', barcode: 'PC-piece-2', status: 'return_in_transit', variant_title: 'M', product_title: 'Cap', sku: null }],
    }))
    const row = screen.getByTestId('expected-piece-2')
    expect(row).toHaveTextContent('awaiting scan')
    expect(row).not.toHaveTextContent('Restock')
  })

  test('rs9 rejected (foreign) scan shows a toast and does not block close', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [] }))
    mockFetch.mockReturnValueOnce(jsonErr({ message_en: 'no match' }, 422))
    await user.type(screen.getByTestId('scan-input'), 'GARBAGE{Enter}')
    await waitFor(() => screen.getByTestId('rejected-scan-toast'))
    expect(screen.getByTestId('close-session-button')).not.toBeDisabled()
  })

  test('rs10 legal scan success clears input and refreshes the item list', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [] }))
    mockFetch
      .mockReturnValueOnce(noContent())
      .mockReturnValueOnce(jsonOk(makeSessionDetail({ items: [makeItem()] })))
    await user.type(screen.getByTestId('scan-input'), 'PC-piece-1{Enter}')
    await waitFor(() => screen.getByTestId('item-piece-1'))
    expect((screen.getByTestId('scan-input') as HTMLInputElement).value).toBe('')
  })

  // ── Disposition ───────────────────────────────────────────────────────────

  test('rs11 damage without reason is blocked — shows required error', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem()] }))
    const card = screen.getByTestId('item-piece-1')
    await user.click(within(card).getByText('Damage'))
    await user.click(within(card).getByText('Confirm'))
    expect(screen.getByTestId('damage-reason-error')).toBeInTheDocument()
  })

  test('rs12 restock disposition marks the item resolved', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem()] }))
    mockFetch
      .mockReturnValueOnce(jsonOk(makeItem({ disposition: 'restocked' })))
      .mockReturnValueOnce(jsonOk(makeSessionDetail({ items: [makeItem({ disposition: 'restocked' })] })))
    await user.click(within(screen.getByTestId('item-piece-1')).getByText('Restock'))
    await waitFor(() => expect(screen.getByTestId('item-piece-1')).toHaveTextContent('Restocked'))
  })

  // ── Close ─────────────────────────────────────────────────────────────────

  test('rs13 close blocked while a pending item exists — names it in the callout', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem({ barcode: 'PC-piece-1', product_title: 'T-Shirt' })] }))
    expect(screen.getByTestId('close-blocked-callout')).toHaveTextContent('PC-piece-1')
    expect(screen.getByTestId('close-session-button')).toBeDisabled()
  })

  test('rs14 close succeeds once all items are dispositioned — shows the summary receipt', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem({ disposition: 'restocked' })] }))
    mockFetch.mockReturnValueOnce(jsonOk({
      sessionId: 'aaaaaaaa-0000-0000-0000-000000000001', pieceCount: 1,
      restockedCount: 1, damagedCount: 0, mismatchCount: 0, shipmentCount: 1,
      closedAt: '2026-08-14T08:36:00Z',
    }))
    await user.click(screen.getByTestId('close-session-button'))
    await waitFor(() => screen.getByTestId('close-summary'))
    expect(screen.getByText('Session closed')).toBeInTheDocument()
    // Single post-action view — the open-session scan loop is gone.
    expect(screen.queryByTestId('scan-input')).not.toBeInTheDocument()
  })

  test('rs15 close disabled for a worker even with nothing pending', async () => {
    // A worker's landing never fetches the sessions list/analytics (FIX 6a —
    // owner-only endpoints), so its "Open return session" button always POSTs
    // directly; resuming an already-open session goes through the
    // SESSION_ALREADY_OPEN 409 → details.sessionId path, not enterSession()'s
    // owner-path list-then-resume sequence.
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    const user = userEvent.setup()
    const detail = makeSessionDetail({ items: [makeItem({ disposition: 'restocked' })] })
    mockFetch
      .mockReturnValueOnce(jsonErr({ code: 'SESSION_ALREADY_OPEN', message_en: 'Already open', details: { sessionId: detail.id } }, 409))
      .mockReturnValueOnce(jsonOk(detail))
    renderWithProviders(<Returns />)
    await user.click(await screen.findByTestId('open-session-button'))
    await waitFor(() => screen.getByTestId('open-session-screen'))
    expect(screen.getByTestId('close-session-button')).toBeDisabled()
    expect(screen.queryByTestId('abandon-link')).not.toBeInTheDocument()
  })

  // ── Abandon (change B: soft-delete, no revert) ────────────────────────────

  test('rs16 abandon confirmation modal, not native confirm — then returns to landing', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [makeItem()] }))
    await user.click(screen.getByTestId('abandon-link'))
    expect(await screen.findByText('Abandon this session?')).toBeInTheDocument()
    expect(confirmSpy).not.toHaveBeenCalled()

    mockFetch
      .mockReturnValueOnce(noContent())
      .mockReturnValueOnce(jsonOk({ items: [], total: 0 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    await user.click(screen.getByTestId('confirm-abandon'))
    await waitFor(() => screen.getByTestId('returns-landing'))
    const deleteCalls = mockFetch.mock.calls.filter(([u, o]) =>
      String(u).endsWith('/returns/sessions/aaaaaaaa-0000-0000-0000-000000000001') && (o as RequestInit)?.method === 'DELETE')
    expect(deleteCalls).toHaveLength(1)
  })

  // ── Dark tokens ───────────────────────────────────────────────────────────

  test('rl7 no light-mode token classes on the landing', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [makeSessionRow()], total: 1 }))
      .mockReturnValueOnce(jsonOk(makeAnalytics()))
    const { container } = renderWithProviders(<Returns />)
    await waitFor(() => screen.getByTestId('sessions-table'))
    expect(container.querySelector('[class*="bg-white"]')).toBeNull()
    expect(container.querySelector('[class*="text-gray-"]')).toBeNull()
  })

  test('rs17 scan input uses input-scan class', async () => {
    const user = userEvent.setup()
    await enterSession(user, makeSessionDetail({ items: [] }))
    expect(screen.getByTestId('scan-input').className).toContain('input-scan')
  })
})
