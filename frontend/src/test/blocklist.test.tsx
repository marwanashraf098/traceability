import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import Blocklist from '../pages/Blocklist'
import Exceptions from '../pages/Exceptions'

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    listBlocklist:      vi.fn(),
    addToBlocklist:     vi.fn(),
    removeFromBlocklist: vi.fn(),
    releaseOrderHold:   vi.fn(),
    cancelOrder:        vi.fn(),
    getRoleFromToken:   vi.fn(() => 'owner' as const),
    request:            vi.fn(),
  }
})

const mockList   = vi.mocked(api.listBlocklist)
const mockAdd    = vi.mocked(api.addToBlocklist)
const mockRemove = vi.mocked(api.removeFromBlocklist)
const mockRelease = vi.mocked(api.releaseOrderHold)
const mockCancel  = vi.mocked(api.cancelOrder)
const mockRequest = vi.mocked(api.request)

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeEntry(overrides?: Partial<api.BlocklistEntry>): api.BlocklistEntry {
  return {
    id: 'bl-001',
    phoneCanonical: '01001234567',
    reason: 'fraud',
    source: 'manual',
    createdBy: 'Alice',
    createdAt: new Date().toISOString(),
    ...overrides,
  }
}

function makeExceptionPage(extraItem?: object) {
  return {
    total: 1,
    page: 0,
    size: 20,
    items: [
      {
        type: 'blocked_customer',
        severity: 'LOW',
        subjectKey: 'blocked:order-1',
        subject_type: 'order',
        order_id: 'order-1',
        order_number: '#1001',
        ageSeconds: 300,
        descriptionEn: 'Order #1001 is on hold: blocked_customer: fraud',
        descriptionAr: 'الطلب #1001 معلَّق',
        suggestedAction: 'review_and_release',
        actionUrl: '/orders/order-1',
        releaseUrl: '/api/v1/orders/order-1/release-hold',
        cancelUrl: '/api/v1/orders/order-1/cancel',
        ...extraItem,
      },
    ],
  }
}

// ── Blocklist page tests ──────────────────────────────────────────────────────

describe('Blocklist page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockList.mockResolvedValue([])
    mockAdd.mockResolvedValue(makeEntry())
    mockRemove.mockResolvedValue(undefined as unknown as void)
  })

  // fb1: page renders empty state
  test('fb1 renders empty state when no entries', async () => {
    renderWithProviders(<Blocklist />)
    await waitFor(() => expect(screen.getByText(/No blocked phones/i)).toBeTruthy())
  })

  // fb2: add phone → addToBlocklist called, list refreshed
  test('fb2 add button opens modal and submits', async () => {
    mockList.mockResolvedValueOnce([]).mockResolvedValueOnce([makeEntry()])
    const user = userEvent.setup()

    renderWithProviders(<Blocklist />)
    await waitFor(() => screen.getByTestId('bl-add-btn'))

    await user.click(screen.getByTestId('bl-add-btn'))
    await waitFor(() => screen.getByTestId('bl-phone'))

    await user.type(screen.getByTestId('bl-phone'),  '01001234567')
    await user.type(screen.getByTestId('bl-reason'), 'fraud')
    await user.click(screen.getByRole('button', { name: /Block$/i }))

    await waitFor(() =>
      expect(mockAdd).toHaveBeenCalledWith('01001234567', 'fraud'),
    )
    // List reloaded: entry visible
    await waitFor(() => screen.getByText('01001234567'))
  })

  // fb3: remove button calls removeFromBlocklist
  test('fb3 remove button triggers unblock', async () => {
    mockList.mockResolvedValue([makeEntry()])
    const user = userEvent.setup()

    renderWithProviders(<Blocklist />)
    await waitFor(() => screen.getByTestId('bl-remove-bl-001'))

    // First click → confirm dialog
    await user.click(screen.getByTestId('bl-remove-bl-001'))
    // Confirm
    await user.click(screen.getByTestId('bl-confirm-remove-bl-001'))

    await waitFor(() =>
      expect(mockRemove).toHaveBeenCalledWith('bl-001'),
    )
  })
})

// ── Exceptions page: release/cancel actions ───────────────────────────────────

describe('Exceptions blocked_customer actions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRequest.mockResolvedValue(makeExceptionPage())
    mockRelease.mockResolvedValue(undefined as unknown as void)
    mockCancel.mockResolvedValue({ status: 'cancelled', message: 'ok' })
  })

  // fb4: release button calls releaseOrderHold
  test('fb4 release hold button calls releaseOrderHold', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Exceptions />)
    await waitFor(() => screen.getByTestId('exc-release-order-1'))

    await user.click(screen.getByTestId('exc-release-order-1'))
    await waitFor(() => expect(mockRelease).toHaveBeenCalledWith('order-1'))
  })

  // fb5: cancel button calls cancelOrder
  test('fb5 cancel button calls cancelOrder', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Exceptions />)
    await waitFor(() => screen.getByTestId('exc-cancel-order-1'))

    await user.click(screen.getByTestId('exc-cancel-order-1'))
    await waitFor(() => expect(mockCancel).toHaveBeenCalledWith('order-1'))
  })
})
