import { test, expect, describe, vi, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import type { PlaceNode, PlaceTree } from '../api'
import SettingsPage from '../pages/settings/SettingsPage'
import i18n from '../i18n'

/**
 * Warehouse places S1 — Settings › Places.
 *   pl1  the tab is hidden without the capability (no /places call), shown after Locations with it
 *   pl2  a worker never sees Settings tabs (the page redirects)
 *   pl3  the zone is drawn as racks with stacked shelves, box chips, the floor's boxes, "No place yet"
 *   pl4  tapping a shelf opens it: contents, Print sticker (POST /places/stickers), + Box here
 *   pl5  Quick add: racks × shelves stepper → preview range, "Create and print stickers" posts the bulk to the zone
 *   pl6  no zone yet: the first-zone setup posts a new zone with racks, shelves and floor boxes
 *   pl7  "+ Zone Z2" creates the proposed zone; pl7b "+ Add rack" adds one rack (next free R)
 *   pl8  Arabic: RTL, Arabic copy, codes kept left-to-right
 *   pl9  renaming a rack says its shelves keep their names; renaming a shelf doesn't
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

const FULL: PlaceTree = {
  locationId: 'loc-main', locationName: 'Main warehouse', nextZoneCode: 'Z2', noPlaceYet: 45,
  nodes: [
    node({ id: 'z1', kind: 'zone', code: 'Z1', label: 'Z1', totalPieces: 72 }),
    node({ id: 'r1', kind: 'rack', code: 'Z1-R01', label: 'R01', parentId: 'z1', path: ['Z1'], depth: 1, totalPieces: 41 }),
    node({ id: 's1', kind: 'shelf', code: 'Z1-R01-S1', label: 'S1', parentId: 'r1', path: ['Z1', 'R01'], depth: 2, pieces: 24, totalPieces: 39 }),
    node({ id: 'b12', kind: 'box', code: 'BOX-12', label: 'BOX-12', parentId: 's1', path: ['Z1', 'R01', 'S1'], depth: 3, pieces: 15, totalPieces: 15 }),
    node({ id: 's2', kind: 'shelf', code: 'Z1-R01-S2', label: 'S2', parentId: 'r1', path: ['Z1', 'R01'], depth: 2, pieces: 2, totalPieces: 2 }),
    node({ id: 'r2', kind: 'rack', code: 'Z1-R02', label: 'R02', parentId: 'z1', path: ['Z1'], depth: 1 }),
    node({ id: 's3', kind: 'shelf', code: 'Z1-R02-S1', label: 'S1', parentId: 'r2', path: ['Z1', 'R02'], depth: 2 }),
    node({ id: 'b7', kind: 'box', code: 'BOX-07', label: 'BOX-07', parentId: 'z1', path: ['Z1'], depth: 1, pieces: 31, totalPieces: 31 }),
  ],
}

let calls: { method: string; url: string; body?: unknown }[]
let tree: PlaceTree

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main warehouse', is_fulfillment: true }])
  if (url.includes('/places/bulk/preview')) {
    const zone = body.zoneId ? 'Z1' : 'Z1'
    const first = body.zoneId ? 3 : 1
    const last = first + body.racks - 1
    return json({ locationId: 'loc-main', zoneCode: zone, newZone: !body.zoneId, racks: body.racks,
      shelves: body.racks * body.shelvesPerRack, boxes: body.floorBoxes, total: body.racks * (1 + body.shelvesPerRack),
      firstRack: null, lastRack: null, firstShelf: `${zone}-R${String(first).padStart(2, '0')}-S1`,
      lastShelf: `${zone}-R${String(last).padStart(2, '0')}-S${body.shelvesPerRack}`, firstBox: null, lastBox: null, conflicts: [] })
  }
  if (url.endsWith('/places/bulk') && method === 'POST') return json({ zoneId: 'z1', plan: {}, createdIds: ['new-1', 'new-2'] }, 201)
  if (url.endsWith('/places/stickers')) return json({})
  if (url.endsWith('/places') && method === 'POST') return json(node({ id: 'z2', kind: 'zone', code: 'Z2', label: 'Z2' }), 201)
  if (/\/places\/s1$/.test(url)) {
    return json({ node: FULL.nodes[2], locationName: 'Main warehouse',
      contents: [{ variantId: 'v1', productTitle: 'Black T-shirt', variantTitle: 'M', sku: 'TS-M', count: 24 }],
      boxes: [FULL.nodes[3]], history: [] })
  }
  if (/\/places\/r1$/.test(url)) {
    return json({ node: FULL.nodes[1], locationName: 'Main warehouse', contents: [], boxes: [], history: [] })
  }
  if (url.includes('/places')) return json(tree)
  if (url.includes('/tenant/settings')) return json({ pickPackMode: 'order_queue' })
  return json({})
}

function setup(places: boolean, role: 'owner' | 'manager' | 'worker' = 'owner', initial: PlaceTree = FULL) {
  calls = []
  tree = structuredClone(initial)
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  vi.spyOn(window, 'open').mockImplementation(() => null)
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: false, places } })
}

afterEach(async () => {
  clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks()
  await i18n.changeLanguage('en')
})

describe('Settings › Places', () => {
  test('pl1 — hidden without the capability, shown after Locations with it', async () => {
    setup(false)
    const { unmount } = renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await screen.findByRole('button', { name: 'Locations' })
    expect(screen.queryByRole('button', { name: 'Places' })).toBeNull()
    expect(screen.queryByTestId('places-settings')).toBeNull()
    expect(calls.some(c => c.url.includes('/places'))).toBe(false)
    unmount()

    setup(true)
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=locations'] })
    const placesTab = await screen.findByRole('button', { name: 'Places' })
    const labels = screen.getAllByRole('button').map(b => b.textContent)
    expect(labels.indexOf('Places')).toBe(labels.indexOf('Locations') + 1)
    await userEvent.setup().click(placesTab)
    expect(await screen.findByTestId('places-settings')).toBeTruthy()
  })

  test('pl2 — a worker gets no Settings tabs at all', async () => {
    setup(true, 'worker')
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await waitFor(() => expect(screen.queryByTestId('places-settings')).toBeNull())
    expect(screen.queryByRole('button', { name: 'Places' })).toBeNull()
  })

  test('pl3 — the zone is drawn as racks with stacked shelves, boxes and the floor', async () => {
    setup(true, 'manager')
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const layout = await screen.findByTestId('places-zone-layout')
    expect(screen.getByTestId('places-zone-Z1').getAttribute('aria-selected')).toBe('true')
    expect(within(screen.getByTestId('places-zone-Z1')).getByText('2 racks · 3 shelves · 72 pcs')).toBeTruthy()
    const r1 = within(layout).getByTestId('places-rack-Z1-R01')
    expect(within(r1).getByText('R01')).toBeTruthy()
    expect(within(r1).getByText('41 pcs')).toBeTruthy()
    const s1 = within(r1).getByTestId('places-shelf-Z1-R01-S1')
    expect(within(s1).getByText('39 pcs')).toBeTruthy()
    expect(within(s1).getByTestId('places-box-BOX-12')).toBeTruthy()
    expect(within(within(r1).getByTestId('places-shelf-Z1-R01-S2')).getByText('2 pcs')).toBeTruthy()
    expect(within(within(layout).getByTestId('places-rack-Z1-R02')).getByText('Empty')).toBeTruthy()
    expect(within(layout).getByTestId('places-floorbox-BOX-07')).toBeTruthy()
    expect(screen.getByTestId('places-next-zone').textContent).toBe('+ Zone Z2')
    expect(within(layout).getByTestId('places-add-rack').textContent).toBe('Add rackR03')
    expect(screen.getByTestId('places-no-place').textContent).toContain('45 pieces have no place yet')
    expect(within(screen.getByTestId('places-no-place')).getByRole('link')).toHaveAttribute('href', '/put-away')   // S2: links to Put away
  })

  test('pl4 — tap a shelf: its contents, Print sticker, + Box here', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByTestId('places-shelf-Z1-R01-S1'))
    const panel = await screen.findByTestId('places-selected')
    expect(within(panel).getByTestId('places-selected-code').textContent).toBe('Z1-R01-S1')
    expect(await within(panel).findByText('Black T-shirt · M')).toBeTruthy()
    await user.click(within(panel).getByRole('button', { name: 'Print sticker' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/places/stickers'))?.body).toEqual({ placeIds: ['s1'] }))
    await user.click(within(panel).getByRole('button', { name: '+ Box here' }))
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/places'))?.body)
      .toEqual({ kind: 'box', parentId: 's1' }))
  })

  test('pl5 — Quick add to the zone: preview range, then create and print', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const qa = await screen.findByTestId('places-quick-add')
    expect(within(qa).getByText('Quick add to Z1')).toBeTruthy()
    await user.click(within(qa).getByRole('button', { name: 'More: Racks' }))
    await user.click(within(qa).getByRole('button', { name: 'Fewer: Shelves in each rack' }))
    expect(within(qa).getByTestId('places-qa-total').textContent).toBe('Creates 9 shelves')
    await waitFor(() => expect(within(qa).getByTestId('places-qa-range').textContent).toBe('Z1-R03-S1 … Z1-R05-S3'))
    await user.click(within(qa).getByRole('button', { name: 'Create and print stickers' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/places/bulk'))?.body)
      .toEqual({ zoneId: 'z1', racks: 3, shelvesPerRack: 3, floorBoxes: 0 }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/places/stickers'))?.body).toEqual({ placeIds: ['new-1', 'new-2'] }))
  })

  test('pl6 — no zone yet: the first-zone setup', async () => {
    setup(true, 'owner', { locationId: 'loc-main', locationName: 'Main warehouse', nextZoneCode: 'Z1', noPlaceYet: 3, nodes: [] })
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const empty = await screen.findByTestId('places-empty')
    expect(within(empty).getByText('No places in Main warehouse yet')).toBeTruthy()
    await user.click(within(empty).getByRole('button', { name: 'More: White boxes on the floor' }))
    await user.click(within(empty).getByRole('button', { name: 'More: White boxes on the floor' }))
    expect(within(empty).getByTestId('places-setup-summary').textContent).toBe('Zone Z1 · 4 racks · 16 shelves · 2 boxes')
    await user.click(within(empty).getByRole('button', { name: 'Create and print stickers' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/places/bulk'))?.body)
      .toEqual({ locationId: 'loc-main', racks: 4, shelvesPerRack: 4, floorBoxes: 2 }))
  })

  test('pl7b — "+ Add rack" adds one rack with as many shelves as the last one', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByTestId('places-add-rack'))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/places/bulk'))?.body)
      .toEqual({ zoneId: 'z1', racks: 1, shelvesPerRack: 1, floorBoxes: 0 }))
  })

  test('pl7 — "+ Zone Z2" creates the proposed zone', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await user.click(await screen.findByTestId('places-next-zone'))
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/places'))?.body)
      .toEqual({ locationId: 'loc-main', kind: 'zone' }))
  })

  test('pl9 — renaming a rack says its shelves keep their names; a shelf has no such note', async () => {
    setup(true)
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const r1 = await screen.findByTestId('places-rack-Z1-R01')
    await user.click(within(r1).getByRole('button', { name: /R01/ }))
    const panel = await screen.findByTestId('places-selected')
    await waitFor(() => expect(within(panel).getByTestId('places-selected-code').textContent).toBe('Z1-R01'))
    await user.click(within(panel).getByRole('button', { name: 'Rename' }))
    expect((await screen.findByTestId('places-rename-rack-note')).textContent)
      .toBe('Shelf names on this rack stay the same. Rename or reprint them if you want.')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    await user.click(screen.getByTestId('places-shelf-Z1-R01-S1'))
    await waitFor(() => expect(within(screen.getByTestId('places-selected')).getByTestId('places-selected-code').textContent)
      .toBe('Z1-R01-S1'))
    await user.click(within(screen.getByTestId('places-selected')).getByRole('button', { name: 'Rename' }))
    await screen.findByTestId('places-rename-code')
    expect(screen.queryByTestId('places-rename-rack-note')).toBeNull()
  })

  test('pl8 — Arabic: right-to-left, Arabic copy, codes stay left-to-right', async () => {
    setup(true)
    await i18n.changeLanguage('ar')
    renderWithProviders(<I18nextProvider i18n={i18n}><SettingsPage /></I18nextProvider>,
      { initialEntries: ['/settings?tab=places'] })
    const qa = await screen.findByTestId('places-quick-add')
    expect(document.documentElement.dir).toBe('rtl')
    expect(within(qa).getByText('إضافة سريعة إلى Z1')).toBeTruthy()
    expect(within(qa).getByTestId('places-qa-range').getAttribute('dir')).toBe('ltr')
    expect(screen.getByTestId('places-no-place').textContent).toContain('45 قطعة ليس لها مكان بعد')
    expect(within(screen.getByTestId('places-rack-Z1-R01')).getByText('R01')).toBeTruthy()
    expect(screen.getByTestId('places-next-zone').textContent).toBe('+ منطقة Z2')
  })
})
