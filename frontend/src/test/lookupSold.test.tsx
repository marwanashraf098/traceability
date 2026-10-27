import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import * as api from '../api'
import LookupPage from '../pages/Lookup'

// C1 — Lookup "Sold" (a sale outside Shopify) and "Not sold": owner / manager only; Sold opens a confirm
// panel (optional note + the Shopify line) and POSTs /sold; Not sold shows only on a Lookup sale
// (canUndoSold from the server) and, after a short confirm with no note, POSTs /not-sold.

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
    variant: { id: 'v1', productTitle: 'Linen Shirt', title: 'White / M', sku: 'LIN-WHT-M' },
    currentLocation: { id: 'loc1', name: 'Main Warehouse' },
    currentOrder: null,
    currentShipment: null,
    receivingSession: null,
    timeline: [],
    ...overrides,
  }
}

const appFetch = vi.fn()

async function search(code = 'P000123') {
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), code)
  await userEvent.keyboard('{Enter}')
}

describe('Lookup — Sold / Not sold', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.lookup).mockResolvedValue(piece())
    stubFetchWithShellDefaults(appFetch)
  })

  afterEach(() => vi.unstubAllGlobals())

  test('sd1 — worker sees no Sold button', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    await search()
    await screen.findByTestId('piece-short-code')
    expect(screen.queryByTestId('sold-open-btn')).toBeNull()
  })

  test('sd2 — Sold opens the confirm panel; Cancel sends nothing', async () => {
    await search()
    await userEvent.click(await screen.findByTestId('sold-open-btn'))
    expect(screen.getByRole('form', { name: 'Mark as sold' })).toBeInTheDocument()
    expect(screen.getByText('Shopify stock at Traced Main Warehouse goes down by 1.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('e.g. Sold at the showroom')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByTestId('sold-panel')).toBeNull()
    expect(appFetch).not.toHaveBeenCalled()
  })

  test('sd3 — Mark sold POSTs /sold with the note, then the piece refreshes', async () => {
    appFetch.mockResolvedValue({ ok: true, status: 204, json: async () => ({}), text: async () => '' })
    await search()
    await userEvent.click(await screen.findByTestId('sold-open-btn'))
    await userEvent.type(screen.getByTestId('sold-note'), 'Sold at the showroom')
    await userEvent.click(screen.getByTestId('sold-submit-btn'))

    await waitFor(() => expect(appFetch).toHaveBeenCalledTimes(1))
    const [url, opts] = appFetch.mock.calls[0]
    expect(url).toBe(`/api/v1/pieces/${ULID}/sold`)
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ note: 'Sold at the showroom' })
    await waitFor(() => expect(api.lookup).toHaveBeenCalledTimes(2))
  })

  test('sd4 — a Lookup-sold piece shows Not sold; a short confirm (no note), then POSTs /not-sold', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ status: 'sold', canUndoSold: true }))
    appFetch.mockResolvedValue({ ok: true, status: 204, json: async () => ({}), text: async () => '' })
    await search()
    expect(screen.queryByTestId('sold-open-btn')).toBeNull()
    await userEvent.click(await screen.findByTestId('not-sold-btn'))

    const confirm = await screen.findByTestId('not-sold-confirm')
    expect(appFetch).not.toHaveBeenCalled()
    expect(confirm.querySelector('input, textarea')).toBeNull()
    await userEvent.click(screen.getByTestId('not-sold-confirm-btn'))

    await waitFor(() => expect(appFetch).toHaveBeenCalledTimes(1))
    const [url, opts] = appFetch.mock.calls[0]
    expect(url).toBe(`/api/v1/pieces/${ULID}/not-sold`)
    expect(opts.method).toBe('POST')
  })

  test('sd6 — Cancel on the Not sold confirm sends nothing and shows Not sold again', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ status: 'sold', canUndoSold: true }))
    await search()
    await userEvent.click(await screen.findByTestId('not-sold-btn'))
    await screen.findByTestId('not-sold-confirm')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByTestId('not-sold-confirm')).toBeNull()
    expect(screen.getByTestId('not-sold-btn')).toBeTruthy()
    expect(appFetch).not.toHaveBeenCalled()
  })

  test('sd5 — a transfer off-book sale (canUndoSold false) has no Not sold', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ status: 'sold', canUndoSold: false }))
    await search()
    await screen.findByTestId('piece-short-code')
    expect(screen.queryByTestId('not-sold-btn')).toBeNull()
  })
})
