import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { BROEK, analyticsFetch } from './analyticsFixtures'
import Layout from '../components/Layout'
import Orders from '../pages/Orders'
import OrdersPage from '../pages/analytics/OrdersPage'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'

// The phone build: below 900 px the shell's sidebar is a slide-in menu (jsdom has no media
// queries, so these tests drive the menu's own state and classes), the Orders page opens an order
// from ?order=<id>, and Analytics links order numbers to it.

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}
const loc = () => screen.getByTestId('location').textContent ?? ''

function ok(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) }, json: async () => structuredClone(body) })
}

let calls: string[] = []
beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(TOKEN)
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
  calls = []
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  clearAccessToken()
})

describe('shell menu', () => {
  beforeEach(() => {
    stubFetchWithShellDefaults(((url: string) => { calls.push(url); return ok({}) }) as (url: string) => unknown)
  })

  test('m1 — the top-bar button opens the menu; the scrim, Escape and a navigation close it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<><Layout><p>page</p></Layout><LocationProbe /></>, { initialEntries: ['/overview'] })
    const button = screen.getByTestId('nav-menu-button')
    const nav = screen.getByTestId('app-nav')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAttribute('aria-controls', 'app-nav')
    expect(nav).toHaveAttribute('data-open', 'false')
    expect(nav.className).toContain('max-[899px]:ltr:-translate-x-full') // hidden off the start edge on phones
    expect(nav.className).toContain('max-[899px]:rtl:translate-x-full')  // the right edge in Arabic

    await user.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(nav).toHaveAttribute('data-open', 'true')
    expect(nav.className).toContain('max-[899px]:translate-x-0')
    await user.click(screen.getByTestId('nav-scrim'))
    expect(nav).toHaveAttribute('data-open', 'false')

    await user.click(button)
    await user.keyboard('{Escape}')
    expect(nav).toHaveAttribute('data-open', 'false')

    await user.click(button)
    await user.click(within(nav).getAllByRole('link').find(a => a.getAttribute('href') === '/orders')!)
    expect(loc()).toBe('/orders')
    await waitFor(() => expect(nav).toHaveAttribute('data-open', 'false'))
    expect(screen.queryByTestId('nav-scrim')).toBeNull()
  })

  test('m2 — on wide screens nothing changes: the menu button and close button hide at 900 px and up', () => {
    renderWithProviders(<Layout><p>page</p></Layout>, { initialEntries: ['/overview'] })
    expect(screen.getByTestId('nav-menu-button').className).toContain('min-[900px]:hidden')
    expect(screen.getByTestId('app-nav').className).not.toMatch(/(^|\s)(fixed|hidden)(\s|$)/) // only phone-scoped classes
  })
})

describe('order links', () => {
  test('o1 — /orders?order=<id> opens that order; closing removes it from the URL', async () => {
    stubFetchWithShellDefaults(((url: string) => { calls.push(url); return ok(url.includes('/orders/o-77') ? null : { content: [], totalElements: 0, totalPages: 0, number: 0 }) }) as (url: string) => unknown)
    const user = userEvent.setup()
    renderWithProviders(<><Orders /><LocationProbe /></>, { initialEntries: ['/orders?order=o-77'] })
    await waitFor(() => expect(calls.some(u => /\/orders\/o-77(\?|$)/.test(u))).toBe(true))
    await user.keyboard('{Escape}')
    await waitFor(() => expect(loc()).toBe('/orders'))
  })

  test('o2 — Order finances links each order number to the Orders page with ?order=', async () => {
    stubFetchWithShellDefaults(analyticsFetch(BROEK) as (url: string) => unknown)
    renderWithProviders(<Layout><OrdersPage /></Layout>, { initialEntries: ['/analytics/orders'] })
    const links = await screen.findAllByTestId('order-link')
    expect(links[0]).toHaveAttribute('href', '/orders?order=o-0')
    expect(links[0]).toHaveTextContent('#4871')
  })
})
