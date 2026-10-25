import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { Routes, Route } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import * as api from '../api'
import Inventory from '../pages/Inventory'
import TagStockScan from '../pages/TagStockScan'
import TagStockSummary from '../pages/TagStockSummary'
import StockTakeScan from '../pages/StockTakeScan'
import LookupPage from '../pages/Lookup'
import { ToastProvider } from '../components/ui'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

/**
 * Flexible labeling slice 3 — Tag stock screens, and the "label not used yet" message elsewhere.
 *   tg1  Inventory → Untagged (UI pass 2026-10-25; was the Tag stock page): the untagged total, products most-untagged
 *        first; ticking one sets N (default = untagged, capped); Print starts/resumes the session, prints that many,
 *        opens the PDF, then Start scanning goes to the scan loop
 *   tg2  scan loop: a TAGGED scan bumps the counter, shows "code → product / variant" and the product's progress;
 *        a refusal shows its readable message
 *   tg3  replacement label (asks which product) prints 1; End session → summary with printed / tagged / unused
 *        and "Void unused labels"
 *   tg4  stock take: a refused scan (label not used yet) shows the server's message on the scan line
 *   tg5  Lookup: an unused label shows its product and "Label not used yet"
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    getTagStock: vi.fn(), startTagSession: vi.fn(), getTagSession: vi.fn(), printTagLabels: vi.fn(),
    printTagMany: vi.fn(), openTagGroupsPdf: vi.fn(),
    printTagReplacement: vi.fn(), scanTagLabel: vi.fn(), endTagSession: vi.fn(), voidUnusedTagLabels: vi.fn(),
    openLabelBatchPdf: vi.fn(), getStockTakeSession: vi.fn(), scanStockTakePiece: vi.fn(), lookup: vi.fn(),
    getRoleFromToken: vi.fn(() => 'owner' as const),
  }
})

const BLUE: api.TagVariantStock = { variantId: 'v-blue', productTitle: 'Tag Shirt', variantTitle: 'Blue', sku: 'TS-BLUE',
  imageUrl: null, untagged: 4, labeled: 1, unusedLabels: 0 }
const RED: api.TagVariantStock = { ...BLUE, variantId: 'v-red', variantTitle: 'Red', sku: 'TS-RED', untagged: 9, labeled: 0 }
const SESSION: api.TagSession = { id: 's1', startedAt: '2026-10-23T10:00:00Z', endedAt: null, tagged: 0, variants: [] }

function routes() {
  return (
    <ToastProvider>
      <Routes>
        <Route path="/inventory" element={<Inventory />} />
        <Route path="/tag-stock/:id/scan" element={<TagStockScan />} />
        <Route path="/tag-stock/:id/summary" element={<TagStockSummary />} />
      </Routes>
    </ToastProvider>
  )
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.getTagStock).mockResolvedValue({ labeled: 1, untagged: 13, variants: [RED, BLUE] })
  vi.mocked(api.startTagSession).mockResolvedValue(SESSION)
  vi.mocked(api.getTagSession).mockResolvedValue(SESSION)
  vi.mocked(api.printTagLabels).mockResolvedValue({ id: 'b1', kind: 'tag', count: 3, created: true, codes: [] })
  vi.mocked(api.openLabelBatchPdf).mockResolvedValue(undefined)
})

describe('Tag stock', () => {
  test('tg1 — list, choose a product, print N labels, go to the scan loop', async () => {
    vi.mocked(api.printTagMany).mockResolvedValue({ labels: 3, reprints: 0, issued: 3, groups: [
      { variantId: 'v-blue', productTitle: 'Tag Shirt', variantTitle: 'Blue', sku: 'TS-BLUE', codeIds: ['a', 'b', 'c'], reprints: 0 }] })
    vi.mocked(api.openTagGroupsPdf).mockResolvedValue(undefined)
    // The Untagged tab shows only for a switched-on tenant (/me) — the page's own calls are mocked above.
    resetCapabilitiesCache()
    setAccessToken('h.' + btoa(JSON.stringify({ role: 'owner' })) + '.s')
    stubFetchWithShellDefaults(vi.fn(() => Promise.resolve({ ok: true, status: 200, json: async () => ({}),
      headers: { get: () => 'application/json' } })), {
      me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: true } })
    renderWithProviders(routes(), { initialEntries: ['/inventory?tab=untagged'] })
    const list = await screen.findByTestId('untagged-variants')
    expect(await screen.findByRole('button', { name: 'Untagged (13)' })).toBeTruthy()
    const rows = within(list).getAllByRole('listitem')
    expect(rows[0].textContent).toContain('Red')
    await userEvent.click(within(screen.getByTestId('untagged-row-v-blue')).getByRole('checkbox'))
    const qty = screen.getByTestId('untagged-qty-v-blue') as HTMLInputElement
    expect(qty.value).toBe('4')
    await userEvent.clear(qty)
    await userEvent.type(qty, '7')
    expect(qty.value).toBe('4')                                  // capped at the untagged count
    await userEvent.clear(qty)
    await userEvent.type(qty, '3')
    await userEvent.click(within(screen.getByTestId('untagged-bar')).getByRole('button', { name: 'Print labels' }))
    await waitFor(() => expect(api.printTagMany).toHaveBeenCalledWith('s1', [{ variantId: 'v-blue', count: 3 }], expect.any(String)))
    expect(api.openTagGroupsPdf).toHaveBeenCalledWith('s1', expect.any(Array))
    await userEvent.click(await screen.findByRole('button', { name: 'Start scanning' }))
    await screen.findByTestId('tag-stock-scan')
  })

  test('tg2 — a tagged scan updates the counters; a refusal shows its message', async () => {
    vi.mocked(api.scanTagLabel)
      .mockResolvedValueOnce({ outcome: 'TAGGED', code: 'P000101', messageEn: null, messageAr: null,
        variant: { ...BLUE, untagged: 3, labeled: 2 }, taggedThisSession: 1, viaPhone: false })
      .mockResolvedValueOnce({ outcome: 'REJECTED:NO_UNTAGGED_STOCK', code: 'P000102',
        messageEn: 'No untagged piece of this product is left in the main warehouse — keep this label for the next one',
        messageAr: 'x', variant: { ...BLUE, untagged: 0, labeled: 5 }, taggedThisSession: 1, viaPhone: false })
    const blueProgress = (tagged: number) => ({ ...SESSION, tagged, variants: [{ variantId: 'v-blue', productTitle: 'Tag Shirt',
      variantTitle: 'Blue', sku: 'TS-BLUE', imageUrl: null, printed: 3, tagged, unused: 3 - tagged, untaggedLeft: 4 - tagged, labeled: 1 + tagged }] })
    vi.mocked(api.getTagSession).mockResolvedValueOnce(blueProgress(0)).mockResolvedValue(blueProgress(1))
    renderWithProviders(routes(), { initialEntries: ['/tag-stock/s1/scan'] })
    expect((await screen.findByTestId('tag-progress-v-blue')).textContent).toContain('0 / 3')
    const input = screen.getByPlaceholderText('Scan the label you just stuck on…')
    await userEvent.type(input, 'P000101{Enter}')
    await waitFor(() => expect(screen.getByTestId('tagged-count').textContent).toBe('1'))
    await waitFor(() => expect(screen.getByTestId('tag-progress-v-blue').textContent).toContain('1 / 3'))
    expect(screen.getByTestId('tag-last-scan').textContent).toContain('P000101→Tag Shirt / Blue')
    await userEvent.type(input, 'P000102{Enter}')
    await waitFor(() => expect(screen.getByTestId('tag-last-scan').textContent)
      .toContain('No untagged piece of this product is left in the main warehouse'))
  })

  test('tg3 — replacement label, end session, summary with void unused', async () => {
    vi.mocked(api.getTagSession).mockResolvedValue({ ...SESSION, variants: [{ variantId: 'v-blue', productTitle: 'Tag Shirt',
      variantTitle: 'Blue', sku: 'TS-BLUE', imageUrl: null, printed: 3, tagged: 0, unused: 3, untaggedLeft: 4, labeled: 1 }] })
    vi.mocked(api.printTagReplacement).mockResolvedValue({ id: 'b2', kind: 'replacement', count: 1, created: true, codes: [] })
    vi.mocked(api.endTagSession).mockResolvedValue({ ...SESSION, endedAt: '2026-10-23T11:00:00Z' })
    renderWithProviders(routes(), { initialEntries: ['/tag-stock/s1/scan'] })
    await userEvent.click(await screen.findByRole('button', { name: 'Print 1 replacement label' }))
    const picker = await screen.findByTestId('tag-replacement-picker')                 // asks which product
    await userEvent.click(within(picker).getByRole('button', { name: 'Print' }))
    await waitFor(() => expect(api.printTagReplacement).toHaveBeenCalledWith('s1', 'v-blue', expect.any(String)))
    expect(api.openLabelBatchPdf).toHaveBeenCalledWith('b2')

    vi.mocked(api.getTagSession).mockResolvedValue({ ...SESSION, endedAt: '2026-10-23T11:00:00Z', tagged: 2, variants: [
      { variantId: 'v-blue', productTitle: 'Tag Shirt', variantTitle: 'Blue', sku: 'TS-BLUE', imageUrl: null,
        printed: 3, tagged: 2, unused: 1, untaggedLeft: 2, labeled: 3 }] })
    vi.mocked(api.voidUnusedTagLabels).mockResolvedValue({ voided: 1 })
    await userEvent.click(screen.getByRole('button', { name: 'End session' }))
    await userEvent.click(within(await screen.findByRole('heading', { name: 'End this session?' }).then(h => h.closest('div')!.parentElement!))
      .getByRole('button', { name: 'End session' }))
    const summary = await screen.findByTestId('tag-stock-summary')
    expect(within(screen.getByTestId('summary-row-v-blue')).getAllByRole('cell').map(c => c.textContent).slice(1))
      .toEqual(['3', '2', '1'])
    expect(summary.textContent).toContain('1 label not used')
    await userEvent.click(screen.getByRole('button', { name: 'Void unused labels' }))
    await waitFor(() => expect(api.voidUnusedTagLabels).toHaveBeenCalledWith('s1'))
  })
})

describe('Label not used yet — elsewhere', () => {
  test('tg4 — stock take shows the server message for a refused scan', async () => {
    vi.mocked(api.getStockTakeSession).mockResolvedValue({ sessionId: 'st1', status: 'open', scopeType: 'all', locationId: 'l',
      completeCount: false, openedBy: 'u', openedByName: 'O', openedAt: new Date().toISOString(), finalizedBy: null,
      finalizedByName: null, finalizedAt: null, note: null, shopifySync: null } as api.StockTakeSessionDetail)
    vi.mocked(api.scanStockTakePiece).mockRejectedValue(
      new api.ServerMessageError(409, 'LABEL_NOT_USED_YET', 'Label not used yet — tag it in Tag stock first', 'ar'))
    renderWithProviders(<Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>,
      { initialEntries: ['/stock-take/st1/scan'] })
    await userEvent.type(await screen.findByPlaceholderText('Scan barcode…'), 'P000200{Enter}')
    await waitFor(() => expect(screen.getByTestId('last-scan-feedback').textContent)
      .toContain('Label not used yet — tag it in Tag stock first'))
  })

  test('tg5 — Lookup shows an unused label with its product', async () => {
    vi.mocked(api.lookup).mockResolvedValue({ type: 'label_code', code: 'P000300', status: 'issued',
      variant: { id: 'v-blue', title: 'Blue', sku: 'TS-BLUE', productTitle: 'Tag Shirt' },
      messageEn: 'Label not used yet — tag it in Tag stock first', messageAr: 'x' } as api.LabelCodeLookupResult)
    renderWithProviders(<LookupPage />)
    await userEvent.type(screen.getByRole('textbox'), 'P000300{Enter}')
    const view = await screen.findByTestId('label-code-view')
    expect(view.textContent).toContain('Tag Shirt')
    expect(view.textContent).toContain('Label not used yet — tag it in Tag stock first')
    expect(view.textContent).toContain('P000300')
  })
})
