import { afterEach, describe, expect, test, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import SettingsPage from '../pages/settings/SettingsPage'

// Returns portal redesign Pass A — Settings' grouped vertical menu (SettingsNav) in place of the row of tabs.
//   groups    — Store / Operations / Returns / Account, items in the mockup's order, "Labels & printing"
//   deep link — every ?tab= key opens its page and marks its item aria-current="page" (portal included)
//   fallback  — a hidden or unknown ?tab= falls back to the first visible page (Business), exactly as before
//   click     — choosing an item writes ?tab=<key>
//   worker    — still redirected to /overview, no menu
// Each tab's body is stubbed: this file tests the menu, not the pages.

vi.mock('../pages/settings/BusinessTab', () => ({ default: () => <p data-testid="body">business</p> }))
vi.mock('../pages/settings/ConnectionsTab', () => ({ default: () => <p data-testid="body">connections</p> }))
vi.mock('../pages/settings/UsersTab', () => ({ default: () => <p data-testid="body">users</p> }))
vi.mock('../pages/settings/LocationsTab', () => ({ default: () => <p data-testid="body">locations</p> }))
vi.mock('../pages/settings/PlacesTab', () => ({ default: () => <p data-testid="body">places</p> }))
vi.mock('../pages/settings/PickPackTab', () => ({ default: () => <p data-testid="body">pickpack</p> }))
vi.mock('../pages/settings/LabelsTab', () => ({ default: () => <p data-testid="body">labels</p> }))
vi.mock('../pages/settings/CouriersTab', () => ({ default: () => <p data-testid="body">couriers</p> }))
vi.mock('../pages/settings/ReturnsPortalTab', () => ({ default: () => <p data-testid="body">portal</p> }))
vi.mock('../pages/settings/PrivacyTab', () => ({ default: () => <p data-testid="body">privacy</p> }))

function fakeJwt(role: 'owner' | 'manager' | 'worker') {
  return `h.${btoa(JSON.stringify({ role, exp: 9999999999 }))}.s`
}

function Where() {
  const l = useLocation()
  return <p data-testid="where">{l.pathname + l.search}</p>
}

function setup(role: 'owner' | 'manager' | 'worker', flexibleLabeling = false) {
  resetCapabilitiesCache()
  setAccessToken(fakeJwt(role))
  stubFetchWithShellDefaults(vi.fn(() => Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({}) })), { me: { flexibleLabeling } })
}

function open(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/settings" element={<><SettingsPage /><Where /></>} />
      <Route path="/overview" element={<p data-testid="overview">overview</p>} />
    </Routes>,
    { initialEntries: [path] })
}

const current = () => screen.getAllByRole('button').filter(b => b.getAttribute('aria-current') === 'page').map(b => b.textContent)

afterEach(() => { clearAccessToken(); resetCapabilitiesCache(); vi.unstubAllGlobals() })

describe('Settings menu', () => {
  test('groups and items in the mockup order (owner, flexible labeling on)', async () => {
    setup('owner', true)
    open('/settings')
    const nav = await screen.findByRole('navigation', { name: 'Settings sections' })
    await within(nav).findByRole('button', { name: 'Labels & printing' })
    const groups = within(nav).getAllByRole('group')
    expect(groups.map(g => [g.querySelector('p')!.textContent, within(g).getAllByRole('button').map(b => b.textContent)])).toEqual([
      ['Store', ['Business', 'Locations', 'Places']],
      ['Operations', ['Pick & Pack', 'Labels & printing', 'Couriers']],
      ['Returns', ['Returns portal']],
      ['Account', ['Users', 'Connections', 'Privacy']],
    ])
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
  })

  test.each([
    ['business', 'Business'], ['connections', 'Connections'], ['users', 'Users'], ['locations', 'Locations'],
    ['places', 'Places'], ['pickpack', 'Pick & Pack'], ['labels', 'Labels & printing'], ['couriers', 'Couriers'],
    ['portal', 'Returns portal'], ['privacy', 'Privacy'],
  ])('deep link ?tab=%s opens it and marks "%s" current', async (key, name) => {
    setup('owner', true)
    open(`/settings?tab=${key}`)
    await waitFor(() => expect(screen.getByTestId('body')).toHaveTextContent(key))
    expect(current()).toEqual([name])
  })

  test.each([
    ['manager', '?tab=privacy', false],     // owner-only page
    ['owner', '?tab=labels', false],        // labels hidden without flexible labeling
    ['owner', '?tab=nonsense', false],
    ['owner', '', false],
  ] as const)('%s %s → falls back to Business', async (role, query, flexible) => {
    setup(role, flexible)
    open(`/settings${query}`)
    await waitFor(() => expect(screen.getByTestId('body')).toHaveTextContent('business'))
    expect(current()).toEqual(['Business'])
    if (role === 'manager') expect(screen.queryByRole('button', { name: 'Privacy' })).toBeNull()
    if (query === '?tab=labels') expect(screen.queryByRole('button', { name: 'Labels & printing' })).toBeNull()
  })

  test('choosing an item writes ?tab= and moves aria-current', async () => {
    setup('manager')
    const user = userEvent.setup()
    open('/settings?tab=business')
    await user.click(await screen.findByRole('button', { name: 'Returns portal' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings?tab=portal')
    expect(screen.getByTestId('body')).toHaveTextContent('portal')
    expect(current()).toEqual(['Returns portal'])
  })

  test('a worker is still sent to Overview, with no menu', async () => {
    setup('worker')
    open('/settings?tab=portal')
    expect(await screen.findByTestId('overview')).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).toBeNull()
  })
})
