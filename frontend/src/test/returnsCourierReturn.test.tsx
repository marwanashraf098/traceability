import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { ToastProvider } from '../components/ui'
import { StationProvider } from '../components/StationProvider'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Returns from '../pages/Returns'
import { formatSessionStart } from '../pages/returns/sessionStart'
import * as api from '../api'
import i18n from '../i18n'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true, status,
    headers: { get: (k: string) => (k === 'content-length' ? '1' : 'application/json') },
    json: async () => structuredClone(data),
  })
}

function makeSessionDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: 'aaaaaaaa-0000-0000-0000-000000000009', status: 'open',
    opened_by: 'user-1', opened_at: new Date().toISOString(),
    closed_by: null, closed_at: null, note: null,
    items: [], expectedPieces: [
      { id: 'p1', barcode: 'PC-p1', status: 'delivered', variant_title: 'Grey L', product_title: 'Hoodie', sku: 'H-1' },
    ],
    ...overrides,
  }
}

let mockFetch: ReturnType<typeof vi.fn>

/** GET /returns/awaiting-scan answered by URL (empty default), never from the queue. */
function routeAwaitingScan(url: string, opts?: RequestInit) {
  return String(url).includes('/returns/awaiting-scan')
    ? jsonOk({ count: 0, items: [] })
    : mockFetch(url, opts)
}

/**
 * renderWithProviders() pins an EN-only i18n instance; the AR cases render against the app's
 * real i18n singleton (EN + AR resources) with the same provider nesting.
 */
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

/** Worker path: "Open return session" → POST /returns/sessions → GET the session detail. */
async function openWorkerSession(detail: ReturnType<typeof makeSessionDetail>, arabic = false) {
  const user = userEvent.setup()
  mockFetch
    .mockReturnValueOnce(jsonOk({ sessionId: detail.id }, 201))
    .mockReturnValueOnce(jsonOk(detail))
  if (arabic) renderWithAppI18n()
  else renderWithProviders(<Returns />)
  await user.click(await screen.findByTestId('open-session-button'))
  await waitFor(() => screen.getByTestId('open-session-screen'))
}

describe('Returns — courier return (CRP) AWB info line', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    stubFetchWithShellDefaults(routeAwaitingScan)
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(async () => {
    vi.unstubAllGlobals()
    await i18n.changeLanguage('en')
  })

  test('cr1 CRP AWB scanned → "Bosta says" line above the expected pieces (EN)', async () => {
    await openWorkerSession(makeSessionDetail({
      courierReturns: [{ awb: '7040001111', itemsCount: 2, description: 'Hoodie + scarf', descriptionAr: 'هودي وشال' }],
    }))
    const line = await screen.findByTestId('courier-return-info-7040001111')
    expect(line).toHaveTextContent('Bosta says: 2 items — Hoodie + scarf')
    // Rendered before (above) the expected-pieces list.
    const expected = screen.getByTestId('expected-p1')
    expect(line.compareDocumentPosition(expected) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('cr2 AR uses descriptionAr when present', async () => {
    await i18n.changeLanguage('ar')
    await openWorkerSession(makeSessionDetail({
      courierReturns: [{ awb: '7040001111', itemsCount: 2, description: 'Hoodie + scarf', descriptionAr: 'هودي وشال' }],
    }), true)
    expect(await screen.findByTestId('courier-return-info-7040001111'))
      .toHaveTextContent('بحسب بوستا: 2 قطع — هودي وشال')
  })

  test('cr3 AR falls back to the EN description when descriptionAr is missing', async () => {
    await i18n.changeLanguage('ar')
    await openWorkerSession(makeSessionDetail({
      courierReturns: [{ awb: '7040001111', itemsCount: 1, description: 'Hoodie', descriptionAr: null }],
    }), true)
    expect(await screen.findByTestId('courier-return-info-7040001111'))
      .toHaveTextContent('بحسب بوستا: 1 قطع — Hoodie')
  })

  test('cr4 missing itemsCount or description → no line', async () => {
    await openWorkerSession(makeSessionDetail({
      courierReturns: [
        { awb: '7040002222', itemsCount: null, description: 'Hoodie', descriptionAr: null },
        { awb: '7040003333', itemsCount: 1, description: null, descriptionAr: null },
      ],
    }))
    await screen.findByTestId('expected-p1')
    expect(screen.queryByTestId('courier-return-info-7040002222')).not.toBeInTheDocument()
    expect(screen.queryByTestId('courier-return-info-7040003333')).not.toBeInTheDocument()
  })

  test('cr5 forward-only session (no courierReturns field) renders no line', async () => {
    await openWorkerSession(makeSessionDetail())
    await screen.findByTestId('expected-p1')
    expect(screen.queryByText(/Bosta says/)).not.toBeInTheDocument()
  })
})

describe('Returns — open-session header timestamp', () => {
  const now = new Date(2026, 8, 23, 15, 0)

  test('hs1 opened today → time only', () => {
    const r = formatSessionStart(new Date(2026, 8, 23, 9, 5).toISOString(), 'en', now)
    expect(r.sameDay).toBe(true)
    expect(r.text).toBe(new Date(2026, 8, 23, 9, 5).toLocaleTimeString('en', { hour: 'numeric', minute: '2-digit' }))
    expect(r.text).not.toMatch(/Sep/)
  })

  test('hs2 opened on an earlier day → date + time (EN)', () => {
    const r = formatSessionStart(new Date(2026, 7, 28, 9, 5).toISOString(), 'en', now)
    expect(r.sameDay).toBe(false)
    expect(r.text).toMatch(/Aug/)
    expect(r.text).toMatch(/28/)
    expect(r.text).toMatch(/9:05/)
  })

  test('hs3 earlier day in AR → Arabic-locale date + time', () => {
    const iso = new Date(2026, 7, 28, 9, 5).toISOString()
    const r = formatSessionStart(iso, 'ar', now)
    expect(r.sameDay).toBe(false)
    expect(r.text).toBe(new Date(iso).toLocaleString('ar', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }))
  })

  test('hs4 header renders "started <date, time>" for a session opened on an earlier day', async () => {
    vi.clearAllMocks()
    mockFetch = vi.fn()
    stubFetchWithShellDefaults(routeAwaitingScan)
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
    await openWorkerSession(makeSessionDetail({ opened_at: '2026-08-28T09:05:00Z' }))
    const expectedText = formatSessionStart('2026-08-28T09:05:00Z', 'en').text
    expect(await screen.findByText(`started ${expectedText}`)).toBeInTheDocument()
    vi.unstubAllGlobals()
  })
})
