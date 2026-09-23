import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import Returns from '../pages/Returns'
import * as api from '../api'
import i18n from '../i18n'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true, status,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}

function jsonErr(data: unknown, status: number) {
  return Promise.resolve({
    ok: false, status,
    headers: { get: () => 'application/json' },
    json: async () => structuredClone(data),
  })
}

/** Exact shape of GET /api/v1/returns/awaiting-scan (ShipmentLinkService.awaitingScan()). */
function awaitingScan(count: number) {
  return {
    count,
    items: Array.from({ length: count }, (_, i) => ({
      shipmentId: `bbbbbbbb-0000-0000-0000-00000000000${i}`,
      trackingNumber: `70400000${i}${i}`,
      orderNumber: `#1${i}47`,
      returnedAt: '2026-09-22T09:00:00Z',
      itemsCount: 1,
      description: 'Hoodie',
      descriptionAr: null,
    })),
  }
}

const OPEN_ID = 'aaaaaaaa-0000-0000-0000-000000000001'

function sessionRow(status: 'open' | 'closed') {
  return {
    id: OPEN_ID, status, opened_by: 'user-1', opened_at: '2026-09-23T08:00:00Z',
    closed_at: status === 'closed' ? '2026-09-23T09:00:00Z' : null,
    piece_count: 0, restocked_count: 0, damaged_count: 0, mismatch_count: 0,
  }
}

function analytics(unassignedPendingCount = 0) {
  return {
    totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount, unassignedPending: [],
  }
}

function sessionDetail(id = OPEN_ID) {
  return {
    id, status: 'open', opened_by: 'user-1', opened_at: new Date().toISOString(),
    closed_by: null, closed_at: null, note: null, items: [], expectedPieces: [],
  }
}

// ── Harness ───────────────────────────────────────────────────────────────────

let mockFetch: ReturnType<typeof vi.fn>
let awaitingBody: ReturnType<typeof awaitingScan>

function renderWithAppI18n() {
  return render(
    <StationProvider>
      <MemoryRouter>
        <I18nextProvider i18n={i18n}>
          <ToastProvider><Returns /></ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

describe('Returns landing — courier returns awaiting scan callout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    awaitingBody = awaitingScan(0)
    stubFetchWithShellDefaults((url: string, opts?: RequestInit) =>
      String(url).includes('/returns/awaiting-scan') ? jsonOk(awaitingBody) : mockFetch(url, opts))
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(async () => {
    vi.unstubAllGlobals()
    await i18n.changeLanguage('en')
  })

  test('ac1 owner, count > 0 → callout directly below the unassigned callout (EN copy)', async () => {
    awaitingBody = awaitingScan(3)
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [sessionRow('closed')], total: 1 }))
      .mockReturnValueOnce(jsonOk(analytics(4)))
    renderWithProviders(<Returns />)
    const callout = await screen.findByTestId('awaiting-scan-callout')
    expect(callout).toHaveTextContent('3 courier returns waiting to be scanned')
    expect(callout).toHaveTextContent('Bosta brought these back to your warehouse. Scan the parcel label to start.')
    const unassigned = screen.getByTestId('unassigned-callout')
    expect(unassigned.nextElementSibling).toBe(callout)
    expect(unassigned).toHaveTextContent("These items are waiting for inspection but aren't in an open session.")
  })

  test('ac2 owner, count 0 → hidden', async () => {
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [sessionRow('closed')], total: 1 }))
      .mockReturnValueOnce(jsonOk(analytics(4)))
    renderWithProviders(<Returns />)
    await screen.findByTestId('unassigned-callout')
    expect(screen.queryByTestId('awaiting-scan-callout')).not.toBeInTheDocument()
  })

  test('ac3 worker reduced landing, count > 0 → callout shown', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    awaitingBody = awaitingScan(2)
    renderWithProviders(<Returns />)
    expect(await screen.findByTestId('awaiting-scan-callout'))
      .toHaveTextContent('2 courier returns waiting to be scanned')
    expect(screen.queryByTestId('analytics-band')).not.toBeInTheDocument()
  })

  test('ac4 worker, count 0 → hidden', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    renderWithProviders(<Returns />)
    await screen.findByTestId('open-session-button')
    await waitFor(() => expect(mockFetch).not.toHaveBeenCalled())
    expect(screen.queryByTestId('awaiting-scan-callout')).not.toBeInTheDocument()
  })

  test('ac5 AR copy', async () => {
    await i18n.changeLanguage('ar')
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    awaitingBody = awaitingScan(5)
    renderWithAppI18n()
    const callout = await screen.findByTestId('awaiting-scan-callout')
    expect(callout).toHaveTextContent('5 مرتجعات من بوستا بانتظار المسح')
    expect(callout).toHaveTextContent('أعادتها بوستا إلى مستودعك. امسح ملصق الشحنة للبدء.')
  })

  test('ac6 owner Review → resumes the open session (no POST)', async () => {
    const user = userEvent.setup()
    awaitingBody = awaitingScan(1)
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [sessionRow('open')], total: 1 }))
      .mockReturnValueOnce(jsonOk(analytics()))
      .mockReturnValueOnce(jsonOk(sessionDetail()))
    renderWithProviders(<Returns />)
    const callout = await screen.findByTestId('awaiting-scan-callout')
    await user.click(within(callout).getByRole('button', { name: 'Review →' }))
    await waitFor(() => screen.getByTestId('open-session-screen'))
    const posts = mockFetch.mock.calls.filter(([, o]) => (o as RequestInit)?.method === 'POST')
    expect(posts).toHaveLength(0)
    expect(String(mockFetch.mock.calls[2][0])).toMatch(`/returns/sessions/${OPEN_ID}`)
  })

  test('ac7 owner Review with no open session → opens one', async () => {
    const user = userEvent.setup()
    awaitingBody = awaitingScan(1)
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [sessionRow('closed')], total: 1 }))
      .mockReturnValueOnce(jsonOk(analytics()))
      .mockReturnValueOnce(jsonOk({ sessionId: 'new-session-id' }, 201))
      .mockReturnValueOnce(jsonOk(sessionDetail('new-session-id')))
    renderWithProviders(<Returns />)
    const callout = await screen.findByTestId('awaiting-scan-callout')
    await user.click(within(callout).getByRole('button', { name: 'Review →' }))
    await waitFor(() => screen.getByTestId('open-session-screen'))
    const posts = mockFetch.mock.calls.filter(([u, o]) =>
      String(u).endsWith('/returns/sessions') && (o as RequestInit)?.method === 'POST')
    expect(posts).toHaveLength(1)
  })

  test('ac8 worker Review → POST; already-open session is resumed via SESSION_ALREADY_OPEN', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    const user = userEvent.setup()
    awaitingBody = awaitingScan(1)
    mockFetch
      .mockReturnValueOnce(jsonErr({ code: 'SESSION_ALREADY_OPEN', message_en: 'Already open', details: { sessionId: OPEN_ID } }, 409))
      .mockReturnValueOnce(jsonOk(sessionDetail()))
    renderWithProviders(<Returns />)
    const callout = await screen.findByTestId('awaiting-scan-callout')
    await user.click(within(callout).getByRole('button', { name: 'Review →' }))
    await waitFor(() => screen.getByTestId('open-session-screen'))
    expect(String(mockFetch.mock.calls[1][0])).toMatch(`/returns/sessions/${OPEN_ID}`)
  })

  test('ac9 owner with no sessions yet still sees the callout above the empty state', async () => {
    awaitingBody = awaitingScan(2)
    mockFetch
      .mockReturnValueOnce(jsonOk({ items: [], total: 0 }))
      .mockReturnValueOnce(jsonOk(analytics()))
    renderWithProviders(<Returns />)
    expect(await screen.findByTestId('awaiting-scan-callout'))
      .toHaveTextContent('2 courier returns waiting to be scanned')
  })
})
