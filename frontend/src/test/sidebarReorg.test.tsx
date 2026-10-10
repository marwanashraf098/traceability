import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within, act, cleanup } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import en from '../locales/en.json'
import Layout from '../components/Layout'
import { SIDEBAR_KEY } from '../components/Sidebar'
import App from '../App'
import { ANALYTICS_PAGES } from '../analytics/flag'
import { setAccessToken, clearAccessToken } from '../auth'

// Sidebar reorg: grouped nav, collapsible icon rail (owner/manager only), Analytics section with a
// rail flyout, "Exceptions" shown as "Alerts" with an /alerts alias. jsdom has no media queries:
// every rail class is min-[900px]: scoped, so these tests read the sidebar's own state
// (data-collapsed) and the elements it renders, and stub matchMedia where the default matters.

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
  return `h.${btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
}

function ok(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) }, json: async () => structuredClone(body) })
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

/** matchMedia stub for a given viewport width (min-width queries only). */
function viewport(width: number) {
  vi.stubGlobal('matchMedia', (q: string) => {
    const m = /min-width:\s*(\d+)px/.exec(q)
    return { matches: m ? width >= Number(m[1]) : false, media: q, addEventListener() {}, removeEventListener() {} }
  })
}

function renderShell(path = '/overview') {
  return renderWithProviders(<><Layout><p>page</p></Layout><LocationProbe /></>, { initialEntries: [path] })
}

const nav = () => screen.getByTestId('app-nav')
const hrefs = (el: HTMLElement) => within(el).getAllByRole('link').map(a => a.getAttribute('href'))

beforeEach(() => {
  localStorage.clear()
  setAccessToken(fakeJwt('owner'))
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
  stubFetchWithShellDefaults(() => ok({}))
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  clearAccessToken()
  localStorage.clear()
  document.documentElement.setAttribute('dir', 'ltr')
})

describe('grouped nav', () => {
  test('g1 — owner: Overview, Alerts, then Outbound / Stock / Returns / Analytics, Settings pinned at the bottom', () => {
    renderShell()
    const pages = ANALYTICS_PAGES.filter(p => p.ready).map(p => `/analytics/${p.id}`)
    expect(hrefs(nav())).toEqual([
      '/overview', '/exceptions',
      '/orders', '/fulfill', '/pickups',
      '/inventory', '/receiving', '/transfers', '/stock-take',
      '/exchanges', '/returns',
      ...pages,
      '/settings',
    ])
    for (const h of [en.nav.sections.outbound, en.nav.sections.stock, en.nav.sections.returns, en.nav.analytics, en.nav.beta]) {
      expect(within(nav()).getByText(h)).toBeInTheDocument()
    }
    expect(within(nav()).getByRole('link', { name: en.nav.exceptions })).toHaveAttribute('href', '/exceptions')
    expect(within(nav()).getByRole('link', { name: en.nav.exchangesRefunds })).toHaveAttribute('href', '/exchanges')
    expect(within(nav()).queryByText('Manager')).toBeNull()
    expect(within(nav()).queryByText('NEW')).toBeNull()
    // One number, one place: the Alerts item carries no badge.
    expect(within(nav()).getByRole('link', { name: en.nav.exceptions }).textContent).toBe(en.nav.exceptions)
  })

  test('g2 — manager: same groups, no Analytics section', () => {
    setAccessToken(fakeJwt('manager'))
    renderShell()
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
    expect(hrefs(nav())).toContain('/settings')
    expect(within(nav()).getByText(en.nav.sections.returns)).toBeInTheDocument()
  })

  test('g3 — the Analytics header folds and unfolds the section and remembers it', async () => {
    const user = userEvent.setup()
    renderShell()
    const section = screen.getByTestId('nav-analytics')
    const header = within(section).getByRole('button')
    expect(header).toHaveAttribute('aria-expanded', 'true')
    await user.click(header)
    expect(header).toHaveAttribute('aria-expanded', 'false')
    expect(within(section).queryAllByRole('link')).toHaveLength(0)
    expect(localStorage.getItem('traced-analytics-nav')).toBe('closed')
  })
})

describe('collapse', () => {
  test('c1 — the toggle collapses to the rail, the state persists across remounts, and expands again', async () => {
    const user = userEvent.setup()
    const { unmount } = renderShell()
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
    await user.click(screen.getByTestId('sidebar-toggle'))
    expect(nav()).toHaveAttribute('data-collapsed', 'true')
    expect(nav().className).toContain('min-[900px]:w-16')
    expect(localStorage.getItem(SIDEBAR_KEY)).toBe('collapsed')
    // Phone drawer is unaffected: every rail class is scoped to ≥ 900 px.
    expect(nav().className).not.toMatch(/(^|\s)(fixed|hidden|w-16)(\s|$)/)

    unmount()
    renderShell()
    expect(nav()).toHaveAttribute('data-collapsed', 'true')
    expect(screen.getByTestId('sidebar-toggle')).toHaveAccessibleName(en.nav.expand)
    await user.click(screen.getByTestId('sidebar-toggle'))
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
    expect(localStorage.getItem(SIDEBAR_KEY)).toBe('expanded')
  })

  test('c2 — no stored value: expanded at 1440 px, collapsed at 1024 px; a stored value wins', () => {
    viewport(1440)
    const a = renderShell()
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
    a.unmount()

    viewport(1024)
    const b = renderShell()
    expect(nav()).toHaveAttribute('data-collapsed', 'true')
    b.unmount()

    localStorage.setItem(SIDEBAR_KEY, 'expanded')
    renderShell()
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
  })

  test('c3 — Ctrl+B and ⌘+B toggle it for an owner', async () => {
    const user = userEvent.setup()
    renderShell()
    await user.keyboard('{Control>}b{/Control}')
    expect(nav()).toHaveAttribute('data-collapsed', 'true')
    await user.keyboard('{Meta>}b{/Meta}')
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
  })

  test('c4 — rail labels stay in the DOM for screen readers; a rail tooltip shows on focus and hides on blur', async () => {
    localStorage.setItem(SIDEBAR_KEY, 'collapsed')
    renderShell()
    const orders = within(nav()).getByRole('link', { name: en.nav.orders })
    act(() => orders.focus())
    expect(screen.getByRole('tooltip')).toHaveTextContent(en.nav.orders)
    act(() => orders.blur())
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  test('c5 — no tooltip while expanded', () => {
    renderShell()
    act(() => within(nav()).getByRole('link', { name: en.nav.orders }).focus())
    expect(screen.queryByRole('tooltip')).toBeNull()
  })
})

describe('analytics flyout (rail)', () => {
  test('f1 — opens with the pages in order, Escape closes it, and it closes on navigation', async () => {
    const user = userEvent.setup()
    localStorage.setItem(SIDEBAR_KEY, 'collapsed')
    renderShell('/analytics/summary?period=7d')
    const railBtn = screen.getByTestId('nav-analytics-rail')
    expect(railBtn.className).toContain('nav-item-active') // on an analytics page

    await user.click(railBtn)
    const flyout = screen.getByTestId('nav-analytics-flyout')
    expect(railBtn).toHaveAttribute('aria-expanded', 'true')
    expect(within(flyout).getAllByRole('menuitem').map(a => a.getAttribute('href'))).toEqual(ANALYTICS_PAGES.filter(p => p.ready).map(p => `/analytics/${p.id}?period=7d`))
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('nav-analytics-flyout')).toBeNull()
    expect(railBtn).toHaveAttribute('aria-expanded', 'false')

    await user.click(railBtn)
    await user.click(within(screen.getByTestId('nav-analytics-flyout')).getAllByRole('menuitem')[1])
    expect(screen.getByTestId('location').textContent).toBe('/analytics/revenue?period=7d')
    await waitFor(() => expect(screen.queryByTestId('nav-analytics-flyout')).toBeNull())
  })

  test('f2 — an outside click closes it', async () => {
    const user = userEvent.setup()
    localStorage.setItem(SIDEBAR_KEY, 'collapsed')
    renderShell()
    await user.click(screen.getByTestId('nav-analytics-rail'))
    expect(screen.getByTestId('nav-analytics-flyout')).toBeInTheDocument()
    await user.click(screen.getByText('page'))
    expect(screen.queryByTestId('nav-analytics-flyout')).toBeNull()
  })

  test('f3 — no rail Analytics button while expanded, or for a manager', () => {
    const a = renderShell()
    expect(screen.queryByTestId('nav-analytics-rail')).toBeNull()
    a.unmount()
    setAccessToken(fakeJwt('manager'))
    localStorage.setItem(SIDEBAR_KEY, 'collapsed')
    renderShell()
    expect(screen.queryByTestId('nav-analytics-rail')).toBeNull()
  })
})

describe('worker', () => {
  test('w1 — at 1024 px a worker keeps the full sidebar with exactly its 4 items, no toggle, no Ctrl+B', async () => {
    const user = userEvent.setup()
    setAccessToken(fakeJwt('worker'))
    viewport(1024)
    localStorage.setItem(SIDEBAR_KEY, 'collapsed') // even a stored rail state from an owner on this device
    renderShell('/worker-home')
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
    expect(hrefs(nav())).toEqual(['/worker-home', '/fulfill', '/returns', '/pickups'])
    for (const label of [en.nav.home, en.nav.fulfill, en.nav.returns, en.nav.pickups]) {
      expect(within(nav()).getByRole('link', { name: label })).toBeInTheDocument()
    }
    expect(screen.queryByTestId('sidebar-toggle')).toBeNull()
    expect(within(nav()).queryByText(en.nav.sections.outbound)).toBeNull()
    await user.keyboard('{Control>}b{/Control}')
    expect(nav()).toHaveAttribute('data-collapsed', 'false')
    expect(localStorage.getItem(SIDEBAR_KEY)).toBe('collapsed') // untouched
  })
})

describe('/alerts alias', () => {
  const EMPTY = { total: 0, page: 0, size: 50, items: [], counts: { critical: 0, warning: 0, info: 0 } }

  function renderAppAt(path: string) {
    window.history.pushState({}, '', path)
    return render(<App />)
  }

  afterEach(() => cleanup())

  test('a1 — /alerts renders the Alerts page inside the shell (same page as /exceptions)', async () => {
    stubFetchWithShellDefaults((url: string) => (url.includes('/exceptions?') ? ok(EMPTY) : ok({})))
    renderAppAt('/alerts')
    expect(await screen.findByRole('heading', { name: en.exceptions.title })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/alerts')
    expect(screen.getByTestId('app-nav')).toBeInTheDocument()
  })

  test('a2 — a worker on /alerts is sent to /worker-home (same guard as /exceptions)', async () => {
    setAccessToken(fakeJwt('worker'))
    renderAppAt('/alerts')
    await waitFor(() => expect(window.location.pathname).toBe('/worker-home'))
  })
})
