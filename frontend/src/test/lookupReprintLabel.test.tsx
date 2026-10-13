import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import * as api from '../api'
import LookupPage from '../pages/Lookup'

// Lookup "Reprint label": owner / manager only, enabled from the server's canReprint, a confirm
// step before printing, the real reprintPieceLabel() → POST → blob → window.open, then a refresh.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    lookup:           vi.fn(),
    getRoleFromToken: vi.fn(() => 'owner' as const),
  }
})

const ULID = '01JC8ZQ4M7T2V9X3K5N6P8R0SW'

function piece(overrides: Partial<api.PieceLookupResult> = {}): api.PieceLookupResult {
  return {
    type: 'piece',
    id: ULID,
    barcode: 'PC-' + ULID,
    shortCode: 'P000123',
    status: 'available',
    canReprint: true,
    condition: 'good',
    receivedAt: '2026-10-01T09:00:00Z',
    variant: { id: 'v1', productTitle: 'بلوزة قطنية', title: 'أحمر / M', sku: 'BLZ-RED-M' },
    currentLocation: { id: 'loc1', name: 'Main Warehouse' },
    currentOrder: null,
    currentShipment: null,
    receivingSession: null,
    timeline: [],
    ...overrides,
  }
}

const appFetch = vi.fn()
const openSpy = vi.fn()

async function search(code = 'P000123') {
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), code)
  await userEvent.keyboard('{Enter}')
}

describe('Lookup — Reprint label', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.lookup).mockResolvedValue(piece())
    vi.stubGlobal('open', openSpy)
    URL.createObjectURL = vi.fn(() => 'blob:label')
    stubFetchWithShellDefaults(appFetch)
  })

  afterEach(() => vi.unstubAllGlobals())

  test.each(['owner', 'manager'] as const)('rl1 — %s sees the button', async (role) => {
    vi.mocked(api.getRoleFromToken).mockReturnValue(role)
    await search()
    expect(await screen.findByTestId('reprint-label-btn')).toBeEnabled()
  })

  test('rl2 — worker does not see the button', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    await search()
    await screen.findByTestId('piece-short-code')
    expect(screen.queryByTestId('reprint-label-btn')).toBeNull()
  })

  test('rl3 — canReprint=false → disabled with the warehouse tooltip', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ status: 'delivered', canReprint: false }))
    await search()
    const btn = await screen.findByTestId('reprint-label-btn')
    expect(btn).toBeDisabled()
    expect(btn.parentElement).toHaveAttribute('title', 'Only pieces in the warehouse can be relabelled')
  })

  test('rl4 — click shows the confirm; Cancel sends nothing', async () => {
    await search()
    await userEvent.click(await screen.findByTestId('reprint-label-btn'))
    expect(screen.getByText('Remove the old label from this piece before sticking the new one.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByText('Remove the old label from this piece before sticking the new one.')).toBeNull()
    expect(appFetch).not.toHaveBeenCalled()
    expect(openSpy).not.toHaveBeenCalled()
  })

  test('rl5 — Print → POST, the PDF opens, the piece refreshes', async () => {
    appFetch.mockResolvedValue({ ok: true, status: 200, blob: async () => new Blob(['%PDF'], { type: 'application/pdf' }) })
    await search()
    await userEvent.click(await screen.findByTestId('reprint-label-btn'))
    await userEvent.click(screen.getByRole('button', { name: 'Print' }))

    await waitFor(() => expect(openSpy).toHaveBeenCalledWith('blob:label', '_blank'))
    expect(appFetch).toHaveBeenCalledTimes(1)
    const [url, opts] = appFetch.mock.calls[0]
    expect(url).toBe(`/api/v1/pieces/${ULID}/label/reprint`)
    expect(opts.method).toBe('POST')
    await waitFor(() => expect(api.lookup).toHaveBeenCalledTimes(2))
    expect(screen.queryByText('Remove the old label from this piece before sticking the new one.')).toBeNull()
  })

  test('rl6 — 422 REPRINT_NOT_ALLOWED → friendly inline error, no PDF', async () => {
    appFetch.mockResolvedValue({
      ok: false, status: 422,
      json: async () => ({ code: 'REPRINT_NOT_ALLOWED', message_en: 'x', message_ar: 'y' }),
    })
    await search()
    await userEvent.click(await screen.findByTestId('reprint-label-btn'))
    await userEvent.click(screen.getByRole('button', { name: 'Print' }))

    expect(await screen.findByTestId('reprint-label-error'))
      .toHaveTextContent('Only pieces in the warehouse can be relabelled.')
    expect(screen.queryByText(/REPRINT_NOT_ALLOWED|message_en/)).toBeNull()
    expect(openSpy).not.toHaveBeenCalled()
  })

  test('rl7 — 403 → friendly inline error', async () => {
    appFetch.mockResolvedValue({
      ok: false, status: 403,
      json: async () => ({ error: 'ACCESS_DENIED', path: '/x', authorities: 'ROLE_WORKER' }),
    })
    await search()
    await userEvent.click(await screen.findByTestId('reprint-label-btn'))
    await userEvent.click(screen.getByRole('button', { name: 'Print' }))

    expect(await screen.findByTestId('reprint-label-error'))
      .toHaveTextContent('Only owners and managers can reprint labels.')
    expect(screen.queryByText(/ACCESS_DENIED/)).toBeNull()
  })

  test('rl8 — header shows only the short code; the PC- barcode is the "Internal code" meta field', async () => {
    await search()
    const header = await screen.findByTestId('piece-short-code')
    expect(header).toHaveTextContent('P000123')
    expect(header.parentElement).not.toHaveTextContent('PC-' + ULID)
    const internal = screen.getByTestId('piece-internal-code')
    expect(internal).toHaveTextContent('PC-' + ULID)
    expect(internal.closest('div')).toHaveTextContent('Internal code')
  })
})
