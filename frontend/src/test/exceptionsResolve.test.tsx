import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import Exceptions from '../pages/Exceptions'

// Regression test for the resolve-flow bug fixed alongside this file:
// ExceptionItem.subjectKey didn't match the API's snake_case subject_key field, so
// item.subjectKey was always undefined, JSON.stringify dropped it from the resolve
// POST body, and the backend's NOT NULL constraint on exception_resolutions.subject_key
// rejected the insert (CONSTRAINT_VIOLATION). This test asserts the outgoing request
// body carries a defined, non-null subjectKey sourced from item.subject_key.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, request: vi.fn() }
})

const mockRequest = vi.mocked(api.request)

function makeExceptionPage() {
  return {
    total: 1,
    page: 0,
    size: 50,
    items: [
      {
        type: 'lost',
        severity: 'CRITICAL',
        subject_key: 'lost:piece:11111111-1111-1111-1111-111111111111',
        subject_type: 'piece',
        piece_id: '11111111-1111-1111-1111-111111111111',
        barcode: 'BC-001',
        ageSeconds: 120,
        descriptionEn: 'Piece BC-001 is marked as lost by the courier',
        descriptionAr: 'القطعة BC-001 مُسجَّلة كمفقودة لدى شركة الشحن',
        suggestedAction: 'confirm_write_off',
        actionUrl: '/orders',
      },
    ],
  }
}

describe('Exceptions page — resolve submit body regression', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // GET /exceptions (mount + any post-resolve reload) vs POST /exceptions/resolve
    mockRequest.mockImplementation(async (path: unknown) => {
      if (typeof path === 'string' && path.startsWith('/exceptions/resolve')) return undefined
      return makeExceptionPage()
    })
  })

  test('Mark-resolved sends a defined, non-null subjectKey sourced from item.subject_key', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Exceptions />)

    await waitFor(() => screen.getByText('Piece BC-001 is marked as lost by the courier'))

    // Open the resolve dialog from the row's "Resolve" button.
    await user.click(screen.getByRole('button', { name: 'Resolve' }))

    // Scope into the modal so we don't collide with the row's own "Resolve" button,
    // which stays mounted underneath the modal.
    const modalTitle = await screen.findByText('Resolve exception')
    const modal = modalTitle.closest('.bg-surface') as HTMLElement
    await user.click(within(modal).getByRole('button', { name: 'Resolve' }))

    await waitFor(() => expect(mockRequest).toHaveBeenCalledWith(
      '/exceptions/resolve',
      expect.objectContaining({ method: 'POST' }),
    ))

    const resolveCall = mockRequest.mock.calls.find(([path]) => path === '/exceptions/resolve')
    expect(resolveCall).toBeTruthy()
    const opts = resolveCall![1] as { body: string }
    const body = JSON.parse(opts.body)

    expect(body.subjectKey).toBeDefined()
    expect(body.subjectKey).not.toBeNull()
    expect(body.subjectKey).toBe('lost:piece:11111111-1111-1111-1111-111111111111')
  })
})
