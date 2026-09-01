import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ExceptionsPage from '../pages/Exceptions'

// B2 — /exceptions?type=<code> (as linked from the A3 conflict chip) pre-filters the
// exception queue to the matching type on load.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, request: vi.fn() }
})

function renderExceptions(initialEntry: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/exceptions" element={<ExceptionsPage />} />
    </Routes>,
    { initialEntries: [initialEntry] },
  )
}

function exceptionItem(type: string) {
  return {
    type,
    severity: 'HIGH',
    subject_key: `${type}:order:o1`,
    subject_type: 'order',
    order_id: 'o1',
    order_number: '#1001',
    tracking_number: '9820011001',
    ageSeconds: 60,
    descriptionEn: `desc for ${type}`,
    descriptionAr: `وصف ${type}`,
    suggestedAction: 'do_something',
    actionUrl: '/orders/o1',
  }
}

describe('Exceptions page — B2 deep link from the conflict chip', () => {
  beforeEach(() => { vi.clearAllMocks() })

  test('?type=cancelled_live_shipment pre-fills the type filter on the request', async () => {
    vi.mocked(api.request).mockResolvedValue({
      total: 1, page: 0, size: 50, items: [exceptionItem('cancelled_live_shipment')],
    })

    renderExceptions('/exceptions?type=cancelled_live_shipment')

    await waitFor(() => expect(api.request).toHaveBeenCalled())
    const calledUrl = vi.mocked(api.request).mock.calls[0][0] as string
    expect(calledUrl).toContain('type=cancelled_live_shipment')

    await waitFor(() => expect(screen.getByText('desc for cancelled_live_shipment')).toBeInTheDocument())
  })

  test('no ?type= param — request has no type filter (existing behavior unchanged)', async () => {
    vi.mocked(api.request).mockResolvedValue({ total: 0, page: 0, size: 50, items: [] })

    renderExceptions('/exceptions')

    await waitFor(() => expect(api.request).toHaveBeenCalled())
    const calledUrl = vi.mocked(api.request).mock.calls[0][0] as string
    expect(calledUrl).not.toContain('type=')
  })
})
