import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { fireEvent } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { ToastProvider } from '../components/ui'
import Receiving from '../pages/Receiving'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'

/**
 * Receiving redesign from the live test (2026-10-25).
 *   rs1  switch on, step 1: only the two cards (Default badge, bodies, footers); a click picks the mode and goes on
 *   rs2  step 2: "<mode> · Change", Back to step 1; count only locks the main warehouse with its hint and sends it
 *   rs3  session screen: the mode badge next to the status (switch on only)
 *   ri1  ONE header button "Import from Excel"; the panel's three steps; the template format choice; the Shopify
 *        warning once, above the upload
 *   ri2  an empty "On hand (new)" column: the message, then "Use On hand (current) instead" re-previews the same
 *        file with column=current and the panel says "Using On hand (current)"
 *   ri3  drag and drop a file; the chosen file with × clears it; "Add 0 units" disabled with the reason
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['x']),
  })
}

const LOCATIONS = [
  { id: 'loc-show', name: 'Showroom', is_fulfillment: false },
  { id: 'loc-main', name: 'Main Warehouse', is_fulfillment: true },
]

const SESSION = {
  id: 'sess-1', status: 'open', mode: 'count_only', reference: 'REC-9', supplier_name: null,
  location_name: 'Main Warehouse', created_at: new Date().toISOString(), finalized_at: null,
  line_units: 0, piece_count: 0, lines: [],
}

const MATCHED = [{ rowNumber: 3, variantId: 'v-ice', productTitle: 'Snowboard', variantTitle: 'Ice', sku: null,
  imageUrl: null, quantity: 10, onHandCurrent: 10, sameAsShopify: false, matchedBy: 'handle' }]
const EMPTY_NEW = { location: 'Shop', units: 0, matched: [], unmatched: [], skipped: 25, quantityColumn: 'new', newColumnEmpty: true }
const FROM_CURRENT = { location: 'Shop', units: 10, matched: MATCHED, unmatched: [], skipped: 24, quantityColumn: 'current', newColumnEmpty: true }

let calls: { url: string; method: string; body: unknown }[]
let session: Record<string, unknown>

function backend(url: string, opts: RequestInit = {}) {
  const method = opts.method ?? 'GET'
  calls.push({ url, method, body: opts.body })
  if (url.includes('/locations')) return json(LOCATIONS)
  if (url.includes('/catalog')) return json({ products: [], nextCursor: null })
  if (url.endsWith('/receiving/sessions') && method === 'POST') return json({ sessionId: 'sess-1' }, 201)
  if (url.endsWith('/receiving/sessions')) return json([session])
  if (url.endsWith('/receiving/sessions/sess-1')) return json(session)
  if (url.endsWith('/import/preview')) {
    const fd = opts.body as FormData
    return json(fd.get('column') === 'current' ? FROM_CURRENT : EMPTY_NEW)
  }
  if (url.includes('/import/template')) return json({})
  return json({})
}

function setup(flexibleLabeling: boolean) {
  calls = []
  session = { ...SESSION }
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling } })
}

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

const posted = (suffix: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(suffix))

describe('Receiving — two-step start (switch on)', () => {
  beforeEach(() => setup(true))

  test('rs1 — step 1 shows only the two cards; a click picks the mode and moves on', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByRole('button', { name: /new/i }))
    const chooser = await screen.findByTestId('receiving-mode-chooser')
    expect(within(chooser).getByRole('heading').textContent).toBe('How do you want to receive this delivery?')
    const stick = screen.getByTestId('receiving-mode-print_stick')
    expect(stick.textContent).toContain('Default')
    expect(stick.textContent).toContain('Every piece has a barcode from day one.')
    expect(screen.getByTestId('receiving-mode-count_only').textContent).toContain('Best for big deliveries and fast movers.')
    expect(screen.queryByText('Location')).toBeNull()                         // nothing else on the screen
    expect(screen.queryByRole('button', { name: /create/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /continue/i })).toBeNull()

    await user.click(screen.getByTestId('receiving-mode-count_only'))
    expect(screen.getByTestId('receiving-mode-chosen').textContent).toBe('Count only, label at pack · Change')
    expect(screen.getByTestId('count-only-location').textContent).toBe('Main Warehouse')
    expect(screen.getByText('Count-only stock goes to the main warehouse.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: /create/i }))
    await waitFor(() => expect(posted('/receiving/sessions')).toHaveLength(1))
    expect(JSON.parse(String(posted('/receiving/sessions')[0].body))).toMatchObject({ mode: 'count_only', locationId: 'loc-main' })
  })

  test('rs2 — Back / Change return to step 1; print & stick keeps the location choice', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByRole('button', { name: /new/i }))
    await user.click(await screen.findByTestId('receiving-mode-count_only'))
    await user.click(screen.getByTestId('receiving-mode-back'))
    expect(screen.getByTestId('receiving-mode-count_only').getAttribute('aria-pressed')).toBe('true')
    await user.click(screen.getByTestId('receiving-mode-print_stick'))
    expect(screen.getByTestId('receiving-mode-chosen').textContent).toBe('Print & stick now · Change')
    expect(screen.queryByTestId('count-only-location')).toBeNull()
    expect(screen.getByRole('combobox')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByTestId('receiving-mode-chooser')).toBeTruthy()
  })

  test('rs3 — the session shows its mode next to the status', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByText('REC-9'))
    expect((await screen.findByTestId('receiving-session-mode')).textContent).toBe('Count only')
  })
})

describe('Receiving — switch off', () => {
  test('rs4 — no chooser and no mode badge', async () => {
    setup(false)
    session = { ...SESSION, mode: 'print_stick' }
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByText('REC-9'))
    await screen.findByRole('button', { name: /import from excel/i })
    expect(screen.queryByTestId('receiving-session-mode')).toBeNull()
  })
})

describe('Import from Excel', () => {
  beforeEach(() => setup(false))

  async function openPanel() {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByText('REC-9'))
    await user.click(await screen.findByRole('button', { name: /import from excel/i }))
    return { user, panel: await screen.findByTestId('import-panel') }
  }

  test('ri1 — one header button; three steps; the format choice; the warning once, above the upload', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ToastProvider><Receiving /></ToastProvider>)
    await user.click(await screen.findByText('REC-9'))
    await screen.findByRole('button', { name: /import from excel/i })
    expect(screen.queryByRole('button', { name: /download template/i })).toBeNull()   // inside the panel only
    expect(screen.queryByRole('button', { name: /^csv$/i })).toBeNull()
    await user.click(screen.getByRole('button', { name: /import from excel/i }))
    const panel = await screen.findByTestId('import-panel')
    expect(panel.textContent).toContain('Download the template')
    expect(panel.textContent).toContain("Type what arrived in the 'On hand (new)' column.")
    expect(panel.textContent).toContain('Upload the file')
    expect(panel.textContent).toContain('Or use a Shopify inventory export for one location.')
    expect(panel.textContent).toContain('.csv or .xlsx, up to 5 MB')
    expect(within(panel).getAllByTestId('import-shopify-warning')).toHaveLength(1)

    await user.click(within(panel).getByRole('button', { name: 'CSV' }))
    await user.click(within(panel).getByRole('button', { name: /download template/i }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/receiving/import/template?format=csv'))).toBe(true))
  })

  test('ri2 — an empty "On hand (new)" column: the message, then "Use On hand (current) instead"', async () => {
    const { user, panel } = await openPanel()
    await user.upload(within(panel).getByTestId('import-file') as HTMLInputElement,
      new File(['Handle,Title'], 'raw.csv', { type: 'text/csv' }))
    const empty = await screen.findByTestId('import-new-empty')
    expect(empty.textContent).toContain("The 'On hand (new)' column is empty. Type the quantities that arrived in that column " +
      'and upload again — or use the stock numbers already in the file.')
    expect(screen.queryByTestId('import-preview')).toBeNull()               // never "0 rows · 0 units"
    expect((posted('/import/preview')[0].body as FormData).get('column')).toBeNull()

    await user.click(within(empty).getByRole('button', { name: 'Use On hand (current) instead' }))
    await screen.findByTestId('import-preview')
    expect((posted('/import/preview')[1].body as FormData).get('column')).toBe('current')
    expect((posted('/import/preview')[1].body as FormData).get('file')).toBeTruthy()
    expect(screen.getByTestId('import-using-current').textContent).toBe('Using On hand (current)')
    expect(screen.getByTestId('import-summary').textContent).toContain('1 product matched · 10 units to add')
    expect(posted('/import/apply')).toHaveLength(0)                          // nothing applied without the click
  })

  test('ri3 — drop a file; × removes it; "Add 0 units" is disabled with the reason', async () => {
    const { user, panel } = await openPanel()
    fireEvent.drop(within(panel).getByTestId('import-dropzone'), {
      dataTransfer: { files: [new File(['x'], 'drop.csv', { type: 'text/csv' })] } })
    expect((await screen.findByTestId('import-chosen')).textContent).toContain('drop.csv')
    await user.click(await screen.findByRole('button', { name: 'Use On hand (current) instead' }))
    await screen.findByTestId('import-preview')
    await user.click(within(screen.getByTestId('import-row-3')).getByRole('button', { name: /remove this row/i }))
    const apply = screen.getByRole('button', { name: /add 0 units/i })
    expect(apply).toBeDisabled()
    expect(screen.getByTestId('import-apply-reason').textContent).toBe('Every row was removed — nothing to add.')

    await user.click(screen.getByTestId('import-remove-file'))
    expect(screen.queryByTestId('import-chosen')).toBeNull()
    expect(screen.queryByTestId('import-preview')).toBeNull()
    expect(screen.getByTestId('import-dropzone')).toBeTruthy()
  })
})
