import { test, expect, describe, vi, beforeEach } from 'vitest'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import Overview from '../pages/Overview'
import Login from '../pages/Login'

// ── API mock ──────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getInventorySummary:  vi.fn().mockResolvedValue({ groupA: [], groupB: [] }),
    getOrderDailyCounts:  vi.fn(),
    getRoleFromToken:     vi.fn(() => 'owner' as const),
    request:              vi.fn().mockResolvedValue({ items: [] }),
  }
})

const mockGetInventory = vi.mocked(api.getInventorySummary)
const mockGetChart     = vi.mocked(api.getOrderDailyCounts)

function makeChartData(counts: number[]): api.DayCount[] {
  return counts.map((count, i) => ({
    date: `2026-05-${String(i + 1).padStart(2, '0')}`,
    count,
  }))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('Overview — logo + chart', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetInventory.mockResolvedValue({ groupA: [], groupB: [] })
  })

  // ov1: Login renders SVG logo — not the "T" placeholder text
  test('ov1 Login shows SVG logo, not "T" text placeholder', () => {
    const { container } = renderWithProviders(<Login />)
    // SVG element must be present (the Logo component)
    expect(container.querySelector('[data-testid="logo-svg"]')).toBeTruthy()
    // The old "T" span must be gone
    const tSpan = Array.from(container.querySelectorAll('span')).find(s => s.textContent === 'T')
    expect(tSpan).toBeFalsy()
  })

  // ov2: Overview chart shows spinner while loading
  test('ov2 overview chart shows loading spinner while fetching data', async () => {
    // Never resolves — chart stays loading
    mockGetChart.mockReturnValue(new Promise(() => {}))
    renderWithProviders(<Overview />)
    await waitFor(() => screen.getByTestId('orders-chart-loading'))
    expect(screen.getByTestId('orders-chart-loading')).toBeTruthy()
  })

  // ov3: chart shows empty state when all counts are zero
  test('ov3 overview chart shows empty state when no orders', async () => {
    mockGetChart.mockResolvedValue(makeChartData([0, 0, 0, 0, 0]))
    renderWithProviders(<Overview />)
    await waitFor(() => screen.getByTestId('orders-chart-empty'))
    expect(screen.getByTestId('orders-chart-empty').textContent).toMatch(/No orders/i)
  })

  // ov4: chart renders SVG with data when counts > 0
  test('ov4 overview chart renders SVG line chart when data present', async () => {
    mockGetChart.mockResolvedValue(makeChartData([2, 5, 3, 8, 4]))
    renderWithProviders(<Overview />)
    await waitFor(() => screen.getByTestId('orders-chart'))
    const chart = screen.getByTestId('orders-chart')
    expect(chart.tagName).toBe('svg')
    // Line path present (brand color)
    expect(chart.querySelector('path[stroke="#6366FF"]')).toBeTruthy()
    // Data dots present
    expect(chart.querySelectorAll('circle[fill="#6366FF"]').length).toBeGreaterThan(0)
  })

  // ov5: chart handles API error gracefully — shows empty state, page doesn't crash
  test('ov5 overview chart API error shows empty state without crash', async () => {
    mockGetChart.mockRejectedValue(new Error('network error'))
    renderWithProviders(<Overview />)
    await waitFor(() => screen.getByTestId('orders-chart-empty'))
    // Rest of the page still renders
    expect(screen.getByText(/Overview/i)).toBeTruthy()
  })
})
