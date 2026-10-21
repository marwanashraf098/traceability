import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import * as api from '../api'
import LookupPage from '../pages/Lookup'

// Flexible labeling slice 2 — Lookup and untagged pieces:
//   u1  an untagged piece: "Untagged" badge, the "no barcode yet" note, no reprint and no adjust actions
//   u2  the variant's Available / Labeled / Untagged split + the untagged note (flag tenants only)
//   u3  a labeled piece with no split (flag off) renders exactly as before

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, lookup: vi.fn(), getRoleFromToken: vi.fn(() => 'owner' as const) }
})

const ULID = '01JC8ZQ4M7T2V9X3K5N6P8R0SW'

function piece(overrides: Partial<api.PieceLookupResult> = {}): api.PieceLookupResult {
  return {
    type: 'piece', id: ULID, barcode: 'PC-' + ULID, shortCode: 'P000123', status: 'available',
    canReprint: true, condition: 'good', receivedAt: '2026-10-01T09:00:00Z',
    variant: { id: 'v1', productTitle: 'Linen Shirt', title: 'M', sku: 'LS-M' },
    currentLocation: { id: 'loc1', name: 'Main Warehouse' },
    currentOrder: null, currentShipment: null, receivingSession: null, timeline: [],
    ...overrides,
  }
}

async function search(code: string) {
  renderWithProviders(<LookupPage />)
  await userEvent.type(screen.getByRole('textbox'), code)
  await userEvent.keyboard('{Enter}')
}

describe('Lookup — untagged pieces', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    stubFetchWithShellDefaults(vi.fn())
  })
  afterEach(() => vi.unstubAllGlobals())

  test('u1 — untagged: badge + note, no reprint, no adjust actions', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ shortCode: null, canReprint: false,
      variantStock: { available: 5, labeled: 3, untagged: 2 } }))
    await search('PC-' + ULID)
    expect(await screen.findByTestId('piece-untagged-badge')).toBeTruthy()
    expect(screen.queryByTestId('piece-short-code')).toBeNull()
    const note = screen.getByTestId('piece-untagged-note')
    expect(note.textContent).toContain('No barcode yet')
    expect(note.textContent).toContain("can't be reprinted, transferred, held or marked damaged")
    expect(screen.queryByTestId('reprint-label-btn')).toBeNull()
    expect(screen.queryByRole('button', { name: /hold|damage|void|lost|write off/i })).toBeNull()
  })

  test('u2 — the variant split and the untagged note', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ variantStock: { available: 5, labeled: 3, untagged: 2 } }))
    await search('P000123')
    const split = await screen.findByTestId('untagged-split')
    expect(split.textContent).toContain('Available')
    expect(split.textContent).toContain('Labeled')
    expect(split.textContent).toContain('Untagged')
    expect(split.textContent).toMatch(/5.*3.*2/s)
    expect(screen.getByTestId('untagged-note').textContent).toBe(
      "2 untagged pieces have no barcode yet. They get one when packed or in a Tag stock session. " +
      "They can't be reprinted, transferred, held or marked damaged until then.")
    expect(await screen.findByTestId('reprint-label-btn')).toBeEnabled()
  })

  test('u3 — flag off (no split): a labeled piece renders as before', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece())
    await search('P000123')
    expect((await screen.findByTestId('piece-short-code')).textContent).toBe('P000123')
    expect(screen.queryByTestId('untagged-split')).toBeNull()
    expect(screen.queryByTestId('piece-untagged-note')).toBeNull()
    expect(screen.getByTestId('reprint-label-btn')).toBeEnabled()
  })
})
