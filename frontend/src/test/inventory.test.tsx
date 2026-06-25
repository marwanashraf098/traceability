import { test, expect, describe, vi, beforeEach } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import Overview from '../pages/Overview'

// Mock api functions used by Overview.tsx.
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getInventorySummary:  vi.fn(),
    getOrderDailyCounts:  vi.fn().mockResolvedValue([]),
    getRoleFromToken:     vi.fn(() => 'owner' as const),
    // Silence the exceptions /api/v1/exceptions call (uses request() directly).
    // It has .catch(() => {}) in Overview so failing silently is fine.
    request: vi.fn().mockResolvedValue({ items: [] }),
  }
})

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeGroupA(overrides: Partial<Record<string, number>> = {}): api.InventoryStatusCount[] {
  const defaults: Record<string, number> = {
    available:                 10,
    reserved:                   3,
    packed:                     1,
    awaiting_pickup:            2,
    with_courier:               8,
    return_pending_inspection:  0,
  }
  return Object.entries({ ...defaults, ...overrides }).map(([status, count]) => ({
    status, count,
  }))
}

function makeGroupB(overrides: Partial<Record<string, number>> = {}): api.InventoryStatusCount[] {
  const defaults: Record<string, number> = { delivered: 42, damaged: 1, lost: 0 }
  return Object.entries({ ...defaults, ...overrides }).map(([status, count]) => ({
    status, count,
  }))
}

function fullSummary(): api.InventorySummary {
  return { groupA: makeGroupA(), groupB: makeGroupB() }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('Inventory summary on Overview', () => {
  beforeEach(() => vi.clearAllMocks())

  // 1. All 9 statuses render with their counts
  test('renders all group-A and group-B tiles with counts', async () => {
    vi.mocked(api.getInventorySummary).mockResolvedValue(fullSummary())

    renderWithProviders(<Overview />)

    // Group A — 6 statuses
    expect(await screen.findByTestId('tile-available')).toBeInTheDocument()
    expect(screen.getByTestId('tile-reserved')).toBeInTheDocument()
    expect(screen.getByTestId('tile-packed')).toBeInTheDocument()
    expect(screen.getByTestId('tile-awaiting_pickup')).toBeInTheDocument()
    expect(screen.getByTestId('tile-with_courier')).toBeInTheDocument()
    expect(screen.getByTestId('tile-return_pending_inspection')).toBeInTheDocument()

    // Group B — 3 statuses
    expect(screen.getByTestId('tile-delivered')).toBeInTheDocument()
    expect(screen.getByTestId('tile-damaged')).toBeInTheDocument()
    expect(screen.getByTestId('tile-lost')).toBeInTheDocument()

    // Counts appear in the DOM
    expect(screen.getByTestId('tile-available').textContent).toContain('10')
    expect(screen.getByTestId('tile-delivered').textContent).toContain('42')
  })

  // 2. Group B section carries the "Last 30 days" label
  test('group-B section shows the 30-day window label', async () => {
    vi.mocked(api.getInventorySummary).mockResolvedValue(fullSummary())

    renderWithProviders(<Overview />)

    await screen.findByTestId('tile-delivered')

    const groupB = screen.getByTestId('group-b')
    // The "Last 30 days" heading and the "30d" badge must both be present
    expect(screen.getByText('Last 30 days')).toBeInTheDocument()
    expect(screen.getByText('30d')).toBeInTheDocument()
    // All three group-B tiles must be inside the group-B container
    expect(groupB).toContainElement(screen.getByTestId('tile-delivered'))
    expect(groupB).toContainElement(screen.getByTestId('tile-damaged'))
    expect(groupB).toContainElement(screen.getByTestId('tile-lost'))
  })

  // 3. Zero count still renders the tile (not hidden or omitted)
  test('tile with count 0 still renders', async () => {
    vi.mocked(api.getInventorySummary).mockResolvedValue(
      fullSummary()  // return_pending_inspection=0, lost=0
    )

    renderWithProviders(<Overview />)

    await screen.findByTestId('tile-available')

    const rpi = screen.getByTestId('tile-return_pending_inspection')
    expect(rpi).toBeInTheDocument()
    expect(rpi.textContent).toContain('0')

    const lost = screen.getByTestId('tile-lost')
    expect(lost).toBeInTheDocument()
    expect(lost.textContent).toContain('0')
  })

  // 4. API error shows inline error, no crash
  test('api error shows inline error and does not crash', async () => {
    vi.mocked(api.getInventorySummary).mockRejectedValue(new Error('network failure'))

    renderWithProviders(<Overview />)

    const alert = await screen.findByRole('alert')
    expect(alert).toBeInTheDocument()

    // No inventory tiles on error
    expect(screen.queryByTestId('tile-available')).toBeNull()
    expect(screen.queryByTestId('tile-delivered')).toBeNull()
  })

  // 5. Each tile links to the drill-down with the correct href
  test('group-A tile links to /inventory?status=X and group-B includes within30d', async () => {
    vi.mocked(api.getInventorySummary).mockResolvedValue(fullSummary())

    renderWithProviders(<Overview />)

    await screen.findByTestId('tile-available')

    // Group A tile
    const availLink = screen.getByTestId('tile-available').closest('a')
    expect(availLink).toHaveAttribute('href', '/inventory?status=available')

    // Group B tile must include within30d=true
    const delivLink = screen.getByTestId('tile-delivered').closest('a')
    expect(delivLink).toHaveAttribute('href', '/inventory?status=delivered&within30d=true')
  })
})
