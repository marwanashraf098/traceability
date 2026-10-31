import { test, expect, describe, vi, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import { loadCapabilities, resetCapabilitiesCache } from '../capabilities'
import type { PlaceTree } from '../api'
import SettingsPage from '../pages/settings/SettingsPage'
import i18n from '../i18n'

/**
 * Places rollout — Settings › Places "Use places in this store".
 *   sw1  owner, switched off: only the switch and its line; switching on → PUT {enabled: true}, the setup shows
 *   sw2  manager, switched on: switching off asks first; "Keep places on" changes nothing, "Turn off places" → PUT
 *        {enabled: false} and only the switch is left
 *   sw3  Arabic, right-to-left: the switch, its line and the confirm in Arabic
 *   sw4  creating the first shelves forgets the cached /me, so the place features appear on the next screen
 * (A worker never sees Settings tabs — places.test.tsx pl2; the server answers 403 — PlacesRolloutTest.)
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
  })
}

const EMPTY: PlaceTree = { locationId: 'loc-main', locationName: 'Main warehouse', nextZoneCode: 'Z1', noPlaceYet: 0, nodes: [] }

let calls: { method: string; url: string; body?: unknown }[]
let meCalls: number
let enabled: boolean

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/places/settings') && method === 'PUT') {
    enabled = body.enabled
    return json({ enabled, setUp: false })
  }
  if (url.includes('/locations')) return json([{ id: 'loc-main', name: 'Main warehouse', is_fulfillment: true }])
  if (url.includes('/places/bulk/preview')) {
    return json({ locationId: 'loc-main', zoneCode: 'Z1', newZone: true, racks: body.racks, shelves: body.racks * body.shelvesPerRack,
      boxes: body.floorBoxes, total: body.racks * (1 + body.shelvesPerRack), firstRack: null, lastRack: null,
      firstShelf: 'Z1-R01-S1', lastShelf: 'Z1-R04-S4', firstBox: null, lastBox: null, conflicts: [] })
  }
  if (url.endsWith('/places/bulk') && method === 'POST') return json({ zoneId: 'z1', plan: {}, createdIds: ['n1'] }, 201)
  if (url.endsWith('/places/stickers')) return json({})
  if (url.includes('/places')) return json(EMPTY)
  return json({})
}

function setup(on: boolean, role: 'owner' | 'manager' = 'owner') {
  calls = []
  meCalls = 0
  enabled = on
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  vi.spyOn(window, 'open').mockImplementation(() => null)
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:x')
  stubFetchWithShellDefaults(vi.fn(backend),
    { me: { name: 'O', role, scanHelpers: false, demoMode: false, genericCourier: false, flexibleLabeling: false,
            places: on, placesDormant: on ? true : undefined } })
  const shell = globalThis.fetch
  vi.stubGlobal('fetch', (url: string, opts?: RequestInit) => {
    if (typeof url === 'string' && url.endsWith('/me')) meCalls++
    return shell(url, opts)
  })
}

afterEach(async () => {
  clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals(); vi.restoreAllMocks()
  await i18n.changeLanguage('en')
})

const puts = () => calls.filter(c => c.method === 'PUT' && c.url.endsWith('/places/settings'))

describe('Settings › Places — Use places in this store', () => {
  test('sw1 owner, off: only the switch; switching on shows the setup', async () => {
    setup(false, 'owner')
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const card = await screen.findByTestId('places-switch')
    expect(within(card).getByText('Use places in this store')).toBeTruthy()
    expect(within(card).getByText(
      'Organise your warehouse into zones, racks, shelves and boxes so pickers know where every piece is.')).toBeTruthy()
    const toggle = within(card).getByRole('switch', { name: 'Use places in this store' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(screen.queryByTestId('places-settings')).toBeNull()
    expect(calls.some(c => c.url.includes('/places'))).toBe(false)

    await userEvent.click(toggle)
    await waitFor(() => expect(puts().map(c => c.body)).toEqual([{ enabled: true }]))
    expect(await screen.findByTestId('places-empty')).toBeTruthy()
    expect(within(screen.getByTestId('places-switch')).getByRole('switch')).toHaveAttribute('aria-checked', 'true')
    expect(screen.queryByTestId('places-switch-confirm')).toBeNull()
  })

  test('sw2 manager, on: switching off asks first; keep changes nothing, turn off leaves only the switch', async () => {
    setup(true, 'manager')
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    await screen.findByTestId('places-empty')
    const toggle = within(screen.getByTestId('places-switch')).getByRole('switch')
    expect(toggle).toHaveAttribute('aria-checked', 'true')

    await user.click(toggle)
    const confirm = await screen.findByTestId('places-switch-confirm')
    expect(within(confirm).getByText(
      'Places will be hidden. Your zones, shelves, boxes and piece places are kept and come back if you turn it on again.')).toBeTruthy()
    await user.click(within(confirm).getByRole('button', { name: 'Keep places on' }))
    expect(screen.queryByTestId('places-switch-confirm')).toBeNull()
    expect(puts()).toHaveLength(0)
    expect(screen.getByTestId('places-empty')).toBeTruthy()

    await user.click(toggle)
    await user.click(within(await screen.findByTestId('places-switch-confirm')).getByRole('button', { name: 'Turn off places' }))
    await waitFor(() => expect(puts().map(c => c.body)).toEqual([{ enabled: false }]))
    await waitFor(() => expect(screen.queryByTestId('places-empty')).toBeNull())
    expect(screen.queryByTestId('places-switch-confirm')).toBeNull()
    expect(within(screen.getByTestId('places-switch')).getByRole('switch')).toHaveAttribute('aria-checked', 'false')
  })

  test('sw3 Arabic, right-to-left: the switch and the confirm', async () => {
    setup(true, 'owner')
    await i18n.changeLanguage('ar')
    document.documentElement.dir = 'rtl'
    renderWithProviders(<I18nextProvider i18n={i18n}><SettingsPage /></I18nextProvider>, { initialEntries: ['/settings?tab=places'] })
    const card = await screen.findByTestId('places-switch')
    expect(within(card).getByText('استخدام الأماكن في هذا المتجر')).toBeTruthy()
    expect(within(card).getByText('نظّم مخزنك في مناطق ووحدات رفوف ورفوف وصناديق ليعرف العاملون مكان كل قطعة.')).toBeTruthy()
    await userEvent.click(within(card).getByRole('switch'))
    const confirm = await screen.findByTestId('places-switch-confirm')
    expect(within(confirm).getByRole('button', { name: 'إيقاف الأماكن' })).toBeTruthy()
    expect(within(confirm).getByRole('button', { name: 'إبقاء الأماكن مفعّلة' })).toBeTruthy()
    document.documentElement.dir = 'ltr'
  })

  test('sw4 the first shelves forget the cached /me — the place features come with the next screen', async () => {
    setup(true, 'owner')
    const user = userEvent.setup()
    renderWithProviders(<SettingsPage />, { initialEntries: ['/settings?tab=places'] })
    const empty = await screen.findByTestId('places-empty')
    await loadCapabilities()
    const before = meCalls
    await user.click(within(empty).getByRole('button', { name: 'Create and print stickers' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/places/bulk') && c.method === 'POST')).toBe(true))
    await loadCapabilities()
    expect(meCalls).toBe(before + 1)
  })
})
