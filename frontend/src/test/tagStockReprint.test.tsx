import { test, expect, describe, vi, beforeEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import TagStock from '../pages/TagStock'
import TagStockScan from '../pages/TagStockScan'
import { ToastProvider } from '../components/ui'

/**
 * Tag stock — reprint instead of new codes (live-test fix, 2026-10-25).
 *   tr1  a product whose labels from this session aren't used yet: "Reprint the N unused labels" is the default
 *        (same codes, nothing issued), "Print new labels" the secondary choice that prints new ones
 *   tr2  no unused labels this session: the count field and "Print N labels", as before
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
        <Route path="/tag-stock" element={<TagStock />} />
        <Route path="/tag-stock/:id/scan" element={<TagStockScan />} />
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
  test('tr1 — unused labels from this session: reprint them by default; "Print new labels" prints new ones', async () => {
    renderWithProviders(routes(), { initialEntries: ['/tag-stock'] })
    await userEvent.click(await screen.findByTestId('tag-variant-v-blue'))
    const panel = screen.getByTestId('tag-reprint-panel')
    expect(screen.queryByTestId('tag-print-panel')).toBeNull()
    await userEvent.click(within(panel).getByRole('button', { name: 'Reprint the 10 unused labels' }))
    await waitFor(() => expect(api.openTagReprintPdf).toHaveBeenCalledWith('s1', 'v-blue'))
    expect(api.printTagLabels).not.toHaveBeenCalled()
    await screen.findByTestId('tag-stock-scan')
  })

  test('tr1b — "Print new labels" is the secondary choice and issues new ones', async () => {
    renderWithProviders(routes(), { initialEntries: ['/tag-stock'] })
    await userEvent.click(await screen.findByTestId('tag-variant-v-blue'))
    await userEvent.click(within(screen.getByTestId('tag-reprint-panel')).getByRole('button', { name: 'Print new labels' }))
    const qty = within(screen.getByTestId('tag-print-panel')).getByRole('spinbutton') as HTMLInputElement
    await userEvent.clear(qty)
    await userEvent.type(qty, '2')
    await userEvent.click(screen.getByRole('button', { name: 'Print 2 labels' }))
    await waitFor(() => expect(api.printTagLabels).toHaveBeenCalledWith('s1', 'v-blue', 2, expect.any(String)))
    expect(api.openTagReprintPdf).not.toHaveBeenCalled()
  })

  test('tr2 — nothing unused this session: the count field, as before', async () => {
    renderWithProviders(routes(), { initialEntries: ['/tag-stock'] })
    await userEvent.click(await screen.findByTestId('tag-variant-v-red'))
    expect(screen.queryByTestId('tag-reprint-panel')).toBeNull()
    expect(within(screen.getByTestId('tag-print-panel')).getByRole('spinbutton')).toBeTruthy()
  })

  test('tr3 — unused labels from ended sessions: reprint; void asks first and voids exactly those codes', async () => {
    renderWithProviders(routes(), { initialEntries: ['/tag-stock'] })
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
    renderWithProviders(routes(), { initialEntries: ['/tag-stock'] })
    await screen.findByTestId('tag-variant-v-blue')
    expect(screen.queryByTestId('tag-leftovers')).toBeNull()
  })
})
