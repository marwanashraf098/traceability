import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'
import * as api from '../api'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn() }
})

// A receiving session reversed by the one-off admin reversal (2026-11-05): status 'reversed'.
// The list and the detail show a translated "Reversed" badge (never the raw value, never blank),
// the detail says its pieces were voided, and no label / put-away action is offered.

const SESSION = {
  id: 'sess-rev', status: 'reversed', mode: 'print_stick', reference: 'PO-01', supplier_name: null,
  location_name: 'Main Warehouse', created_at: '2026-11-04T10:00:00Z', finalized_at: '2026-11-04T21:01:04Z',
  line_units: 1113, piece_count: 1113,
}

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function backendFetch(url: string) {
  if (url.endsWith('/receiving/sessions')) return json([SESSION])
  if (url.endsWith(`/receiving/sessions/${SESSION.id}`)) return json({ ...SESSION, lines: [] })
  if (url.includes('/locations')) return json([])
  return json({})
}

describe('Receiving — a reversed session', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    stubFetchWithShellDefaults(vi.fn(backendFetch))
  })

  test('list and detail show "Reversed" (not the raw status); the detail explains it and offers no label actions', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)

    const ref = await screen.findByText('PO-01')
    expect(screen.getByText('Reversed')).toBeInTheDocument()
    expect(screen.queryByText('reversed')).not.toBeInTheDocument()

    await user.click(ref)
    await waitFor(() =>
      expect(screen.getByText('This session was reversed: its 1113 pieces were voided, as if they never arrived.'))
        .toBeInTheDocument())
    expect(screen.getByText('Reversed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /print labels/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reprint/i })).not.toBeInTheDocument()
  })
})
