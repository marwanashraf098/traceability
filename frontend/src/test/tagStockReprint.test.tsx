import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import UntaggedTab from '../pages/inventory/UntaggedTab'
import { ToastProvider } from '../components/ui'

/**
 * Tag stock — unused labels from ended sessions (live-test fix, 2026-10-25; now in Inventory → Untagged, UI pass
 * the same day — the per-product "Reprint the N unused labels" panel it also tested is gone: a print reuses
 * unused labels automatically, see flexibleUiPass ut2 and TagStockTest m1).
 *   tr3  "Unused labels" from ended sessions: Reprint opens their PDF; Void asks first, then voids exactly those codes
 *   tr4  no leftovers → no "Unused labels" section
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getTagStock: vi.fn(), startTagSession: vi.fn(), getTagSession: vi.fn(), printTagLabels: vi.fn(),
    openLabelBatchPdf: vi.fn(), openTagReprintPdf: vi.fn(), openTagLeftoverPdf: vi.fn(), voidLabelCodes: vi.fn(),
    getRoleFromToken: vi.fn(() => 'owner' as const),
  }
})

const BLUE: api.TagVariantStock = { variantId: 'v-blue', productTitle: 'Tag Shirt', variantTitle: 'Blue', sku: 'TS-BLUE',
  imageUrl: null, untagged: 10, labeled: 0, unusedLabels: 10, unusedThisSession: 10 }
const RED: api.TagVariantStock = { ...BLUE, variantId: 'v-red', variantTitle: 'Red', sku: 'TS-RED', untagged: 4,
  unusedLabels: 0, unusedThisSession: 0 }
const SESSION: api.TagSession = { id: 's1', startedAt: '2026-10-25T10:00:00Z', endedAt: null, tagged: 0, variants: [] }
const LEFTOVER: api.TagLeftover = { variantId: 'v-green', productTitle: 'Tag Shirt', variantTitle: 'Green', sku: 'TS-GRN',
  imageUrl: null, count: 2, codeIds: ['c1', 'c2'] }

function routes() {
  return (
    <ToastProvider>
      <Routes>
        <Route path="/inventory" element={<UntaggedTab />} />
      </Routes>
    </ToastProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.getTagStock).mockResolvedValue({ labeled: 0, untagged: 14, variants: [BLUE, RED], leftovers: [LEFTOVER] })
  vi.mocked(api.startTagSession).mockResolvedValue(SESSION)
  vi.mocked(api.getTagSession).mockResolvedValue(SESSION)
  vi.mocked(api.printTagLabels).mockResolvedValue({ id: 'b2', kind: 'tag', count: 2, created: true, codes: [] })
  vi.mocked(api.openLabelBatchPdf).mockResolvedValue(undefined)
  vi.mocked(api.openTagReprintPdf).mockResolvedValue(undefined)
  vi.mocked(api.openTagLeftoverPdf).mockResolvedValue(undefined)
  vi.mocked(api.voidLabelCodes).mockResolvedValue({ voided: 2 })
})

describe('Tag stock — reprint, not reissue', () => {
  test('tr3 — unused labels from ended sessions: reprint; void asks first and voids exactly those codes', async () => {
    renderWithProviders(routes(), { initialEntries: ['/inventory'] })
    const row = await screen.findByTestId('tag-leftover-v-green')
    expect(row.textContent).toContain('Green')
    expect(row.textContent).toContain('2 unused')
    await userEvent.click(within(row).getByRole('button', { name: 'Reprint' }))
    await waitFor(() => expect(api.openTagLeftoverPdf).toHaveBeenCalledWith('v-green'))

    await userEvent.click(within(row).getByRole('button', { name: 'Void' }))
    expect(api.voidLabelCodes).not.toHaveBeenCalled()
    const title = await screen.findByText('Void 2 unused labels?')
    expect(title).toBeTruthy()
    vi.mocked(api.getTagStock).mockResolvedValue({ labeled: 0, untagged: 14, variants: [BLUE, RED], leftovers: [] })
    const voidButtons = screen.getAllByRole('button', { name: 'Void' })
    expect(voidButtons).toHaveLength(2)                                    // the row's and the dialog's
    await userEvent.click(voidButtons[voidButtons.length - 1])
    await waitFor(() => expect(api.voidLabelCodes).toHaveBeenCalledWith(['c1', 'c2'], 'tag_session_leftover'))
    await waitFor(() => expect(screen.queryByTestId('tag-leftovers')).toBeNull())
  })

  test('tr4 — no leftovers, no section', async () => {
    vi.mocked(api.getTagStock).mockResolvedValue({ labeled: 0, untagged: 14, variants: [BLUE, RED] })
    renderWithProviders(routes(), { initialEntries: ['/inventory'] })
    await screen.findByTestId('untagged-row-v-blue')
    expect(screen.queryByTestId('tag-leftovers')).toBeNull()
  })
})
