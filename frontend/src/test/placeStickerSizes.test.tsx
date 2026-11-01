import { test, expect, describe, vi, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import type { PlaceNode, PlaceStickerPlan, PlaceTree } from '../api'
import SettingsPage from '../pages/settings/SettingsPage'
import i18n from '../i18n'

/**
 * Place sticker sizes — the "Print stickers" dialog in Settings › Places.
 *   ps1  Print sticker on a shelf: the dialog offers the shelf's remembered / default size; Print posts it (+ lang)
 *   ps2  Print zone stickers: a row per kind (zones and racks too) with each kind's size; A6 offers one per page or
 *        4 per A4 sheet; a kind can be left out; one request carries every chosen kind
 *   ps3  a remembered choice from the plan is what the dialog shows
 *   ps4  Quick add "Create and print stickers" and "Print all stickers" open the dialog for those places
 *   ps5  Arabic: the dialog in Arabic, and the request asks for Arabic stickers
 *   ps6  Cancel prints nothing
 */

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role }))}.s`
}

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['%PDF'], { type: 'application/pdf' }),
  })
}

const node = (n: Partial<PlaceNode> & Pick<PlaceNode, 'id' | 'kind' | 'code' | 'label'>): PlaceNode => ({
  name: null, purpose: null, parentId: null, scanCode: 'Q000001', path: [], depth: 0, pieces: 0, totalPieces: 0,
  archived: false, createdAt: '2026-10-26T10:00:00Z', ...n,
})

const TREE: PlaceTree = {
  locationId: 'loc-main', locationName: 'Main warehouse', nextZoneCode: 'Z2', noPlaceYet: 0,
  nodes: [
    node({ id: 'z1', kind: 'zone', code: 'Z1', label: 'Z1' }),
    node({ id: 'r1', kind: 'rack', code: 'Z1-R01', label: 'R01', parentId: 'z1', path: ['Z1'], depth: 1 }),
    node({ id: 's1', kind: 'shelf', code: 'Z1-R01-S1', label: 'S1', parentId: 'r1', path: ['Z1', 'R01'], depth: 2 }),
    node({ id: 'b7', kind: 'box', code: 'BOX-07', label: 'BOX-07', parentId: 'z1', path: ['Z1'], depth: 1 }),
  ],
}

const DEFAULTS: PlaceStickerPlan['choices'] = {
  zone: { size: 'A4', a6Mode: null }, rack: { size: 'A6', a6Mode: 'single' },
  shelf: { size: 'SMALL', a6Mode: null }, box: { size: 'A6', a6Mode: 'single' },
}

let calls: { method: string; url: string; body?: unknown }[]
let choices: PlaceStickerPlan['choices']

function counts(ids: string[]): PlaceStickerPlan['counts'] {
  if (ids.includes('z1') || ids.includes('new-1')) return { zone: 1, rack: 1, shelf: 1, box: 1 }
  if (ids.includes('s1')) return { zone: 0, rack: 0, shelf: 1, box: 0 }
  return { zone: 0, rack: 0, shelf: 0, box: 1 }
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main warehouse', is_fulfillment: true }])
  if (url.endsWith('/places/stickers/plan')) return json({ counts: counts(body.placeIds), choices })
  if (url.endsWith('/places/stickers')) return json({})
  if (url.includes('/places/bulk/preview')) {
    return json({ locationId: 'loc-main', zoneCode: 'Z1', newZone: false, racks: 1, shelves: 4, boxes: 0, total: 5,
      firstRack: null, lastRack: null, firstShelf: 'Z1-R02-S1', lastShelf: 'Z1-R02-S4', firstBox: null, lastBox: null,
      conflicts: [] })
  }
  if (url.endsWith('/places/bulk') && method === 'POST') return json({ zoneId: 'z1', plan: {}, createdIds: ['new-1', 'new-2'] }, 201)
  if (/\/places\/s1$/.test(url)) {
    return json({ node: TREE.nodes[2], locationName: 'Main warehouse', contents: [], boxes: [], history: [] })
  }
  if (url.includes('/places')) return json(TREE)
  return json({})
}

function setup(remembered: Partial<PlaceStickerPlan['choices']> = {}) {
  calls = []
  choices = { ...DEFAULTS, ...remembered }
  resetCapabilitiesCache()
  setAccessToken(fakeJwt('owner'))
  vi.spyOn(window, 'open').mockImplementation(() => null)
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role: 'owner', scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: false, places: true } })
}

const printed = () => calls.filter(c => c.url.endsWith('/places/stickers'))
const plans = () => calls.filter(c => c.url.endsWith('/places/stickers/plan'))

/** The size buttons of a dialog row (SegmentedControl buttons have no pressed state, so the active one is styled). */
function activeLabels(row: HTMLElement): string[] {
  return within(row).getAllByRole('button').filter(b => b.className.includes('bg-surface')).map(b => b.textContent ?? '')
}

afterEach(async () => {
  clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks()
  await i18n.changeLanguage('en')
  document.documentElement.dir = 'ltr'
})

describe('Place sticker sizes — Print stickers dialog', () => {
  test('ps1 — Print sticker on a shelf: its size offered, Print posts it with the language', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByTestId('places-shelf-Z1-R01-S1'))
    const panel = await screen.findByTestId('places-selected')
    await user.click(within(panel).getByRole('button', { name: 'Print sticker' }))
    const dialog = await screen.findByTestId('places-print-dialog')
    expect(plans()[0].body).toEqual({ placeIds: ['s1'] })
    expect(printed()).toHaveLength(0)
    const row = await within(dialog).findByTestId('places-print-row-shelf')
    expect(within(dialog).queryByTestId('places-print-row-box')).toBeNull()
    expect(within(row).getByText('Shelves (1)')).toBeTruthy()
    expect(activeLabels(row)).toEqual(['50×25 mm'])
    expect(within(row).getByText('Label printer')).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await waitFor(() => expect(printed()[0]?.body).toEqual({
      placeIds: ['s1'], kinds: { shelf: { size: 'SMALL', a6Mode: null } }, lang: 'en' }))
    await waitFor(() => expect(screen.queryByTestId('places-print-dialog')).toBeNull())
  })

  test('ps2 — Print zone stickers: every kind with its size, A6 modes, a kind left out', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByRole('button', { name: 'Print zone stickers' }))
    const dialog = await screen.findByTestId('places-print-dialog')
    for (const kind of ['zone', 'rack', 'shelf', 'box']) expect(await within(dialog).findByTestId(`places-print-row-${kind}`)).toBeTruthy()
    expect(activeLabels(within(dialog).getByTestId('places-print-row-zone'))).toEqual(['A4'])
    expect(activeLabels(within(dialog).getByTestId('places-print-row-rack'))).toEqual(['A6', 'A6 labels'])
    expect(activeLabels(within(dialog).getByTestId('places-print-row-shelf'))).toEqual(['50×25 mm'])

    const box = within(dialog).getByTestId('places-print-row-box')
    await user.click(within(box).getByRole('button', { name: '4 per A4 sheet' }))
    expect(activeLabels(box)).toEqual(['A6', '4 per A4 sheet'])
    const shelf = within(dialog).getByTestId('places-print-row-shelf')
    await user.click(within(shelf).getByRole('button', { name: 'A6' }))
    expect(activeLabels(shelf)).toEqual(['A6', 'A6 labels'])
    expect(within(shelf).getByText('Paper printer')).toBeTruthy()
    await user.click(within(dialog).getByRole('checkbox', { name: 'Zones (1)' }))
    expect(within(within(dialog).getByTestId('places-print-row-zone')).queryByRole('button', { name: 'A4' })).toBeNull()

    await user.click(within(dialog).getByRole('button', { name: 'Print' }))
    await waitFor(() => expect(printed()[0]?.body).toEqual({
      placeIds: ['z1'],
      kinds: { rack: { size: 'A6', a6Mode: 'single' }, shelf: { size: 'A6', a6Mode: 'single' }, box: { size: 'A6', a6Mode: 'sheet' } },
      lang: 'en' }))
    expect(printed()).toHaveLength(1)
  })

  test('ps3 — the remembered choice is what the dialog shows', async () => {
    setup({ box: { size: 'A4', a6Mode: null } })
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByRole('button', { name: 'Print zone stickers' }))
    const dialog = await screen.findByTestId('places-print-dialog')
    expect(activeLabels(await within(dialog).findByTestId('places-print-row-box'))).toEqual(['A4'])
  })

  test('ps4 — Create and print / Print all stickers open the dialog for those places', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const qa = await screen.findByTestId('places-quick-add')
    await user.click(within(qa).getByRole('button', { name: 'Create and print stickers' }))
    await screen.findByTestId('places-print-dialog')
    await waitFor(() => expect(plans()[0]?.body).toEqual({ placeIds: ['new-1', 'new-2'] }))
    expect(printed()).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByTestId('places-print-dialog')).toBeNull())

    await user.click(screen.getByRole('button', { name: 'Print all stickers' }))
    await screen.findByTestId('places-print-dialog')
    await waitFor(() => expect(plans()[1]?.body).toEqual({ placeIds: ['z1'] }))
  })

  test('ps5 — Arabic: the dialog in Arabic, Arabic stickers asked for', async () => {
    setup()
    await i18n.changeLanguage('ar')
    const user = userEvent.setup()
    renderWithProviders(<I18nextProvider i18n={i18n}><SettingsPage /></I18nextProvider>,
      { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByRole('button', { name: 'طباعة ملصقات المنطقة' }))
    const dialog = await screen.findByTestId('places-print-dialog')
    expect(screen.getByRole('heading', { name: 'طباعة الملصقات' })).toBeTruthy()
    const box = await within(dialog).findByTestId('places-print-row-box')
    expect(within(box).getByText('الصناديق البيضاء (1)')).toBeTruthy()
    expect(within(box).getByRole('button', { name: 'ملصقات A6' })).toBeTruthy()
    expect(within(box).getByRole('button', { name: '4 في ورقة A4' })).toBeTruthy()
    expect(within(within(dialog).getByTestId('places-print-row-shelf')).getByRole('button', { name: '50×25 مم' })).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'طباعة' }))
    await waitFor(() => expect((printed()[0]?.body as { lang: string }).lang).toBe('ar'))
  })

  test('ps6 — Cancel prints nothing', async () => {
    setup()
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByRole('button', { name: 'Print zone stickers' }))
    const dialog = await screen.findByTestId('places-print-dialog')
    await within(dialog).findByTestId('places-print-row-zone')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByTestId('places-print-dialog')).toBeNull())
    expect(printed()).toHaveLength(0)
  })
})
