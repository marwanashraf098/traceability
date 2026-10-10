import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within, act, cleanup } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import en from '../locales/en.json'
import Layout from '../components/Layout'
import { SIDEBAR_KEY, LAST_PAGE_KEY } from '../components/Sidebar'
import App from '../App'
import { setAccessToken, clearAccessToken } from '../auth'

// Sidebar: grouped nav, collapsible icon rail (owner/manager only), Operations | Analytics mode
// switch (owner, analytics on; mode derived from the route), "Exceptions" shown as "Alerts" with an
// /alerts alias. jsdom has no media queries:
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
  test('g1 — owner, Operations mode: Overview, Alerts, Outbound / Stock / Returns, Settings pinned; no Analytics links', () => {
    renderShell()
    expect(hrefs(nav())).toEqual([
      '/overview', '/exceptions',
      '/orders', '/fulfill', '/pickups',
      '/inventory', '/receiving', '/transfers', '/stock-take',
      '/exchanges', '/returns',
      '/settings',
    ])
    for (const h of [en.nav.sections.outbound, en.nav.sections.stock, en.nav.sections.returns]) {
      expect(within(nav()).getByText(h)).toBeInTheDocument()
    }
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
    // The switch: Operations | Analytics + BETA, Operations selected.
    const sw = screen.getByTestId('mode-switch')
    expect(within(sw).getAllByRole('tab').map(t => t.textContent)).toEqual([en.nav.modes.operations, en.nav.analytics + en.nav.beta])
    expect(screen.getByTestId('mode-tab-operations')).toHaveAttribute('aria-selected', 'true')
    expect(within(nav()).getByRole('link', { name: en.nav.exceptions })).toHaveAttribute('href', '/exceptions')
    expect(within(nav()).getByRole('link', { name: en.nav.exchangesRefunds })).toHaveAttribute('href', '/exchanges')
    expect(within(nav()).queryByText('Manager')).toBeNull()
    expect(within(nav()).queryByText('NEW')).toBeNull()
    // One number, one place: the Alerts item carries no badge.
    expect(within(nav()).getByRole('link', { name: en.nav.exceptions }).textContent).toBe(en.nav.exceptions)
  })

  test('g2 — manager: same groups, no Analytics section, no mode switch', () => {
    setAccessToken(fakeJwt('manager'))
    renderShell()
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
    expect(screen.queryByTestId('mode-switch')).toBeNull()
    expect(screen.queryByRole('tabpanel')).toBeNull()
    expect(hrefs(nav())).toContain('/settings')
    expect(within(nav()).getByText(en.nav.sections.returns)).toBeInTheDocument()
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

describe('mode switch (owner, analytics on)', () => {
  const loc = () => screen.getByTestId('location').textContent
  const tab = (m: 'operations' | 'analytics') => screen.getByTestId(`mode-tab-${m}`)

  test('m1 — the mode follows the route: an analytics page shows the Analytics list in nav order, carrying the period', () => {
    renderShell('/analytics/revenue?period=7d&x=1')
    expect(tab('analytics')).toHaveAttribute('aria-selected', 'true')
    expect(tab('operations')).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'mode-tab-analytics')
    const order = ['summary', 'revenue', 'orders', 'money', 'products', 'stock', 'delivery', 'customers']
    expect(hrefs(screen.getByTestId('nav-analytics'))).toEqual(order.map(id => `/analytics/${id}?period=7d`))
    expect(hrefs(nav())).toEqual([...order.map(id => `/analytics/${id}?period=7d`), '/settings'])
    for (const h of [en.nav.analyticsSections.money, en.nav.analyticsSections.products, en.nav.analyticsSections.customers]) {
      expect(within(nav()).getByText(h)).toBeInTheDocument()
    }
  })

  test('m2 — each side goes to that mode\'s last-visited page (Operations: the nav item; Analytics: page + period)', async () => {
    const user = userEvent.setup()
    localStorage.setItem(LAST_PAGE_KEY.analytics, '/analytics/stock?period=30d&junk=1')
    renderShell('/transfers/abc')
    await user.click(tab('analytics'))
    expect(loc()).toBe('/analytics/stock?period=30d')
    expect(tab('analytics')).toHaveAttribute('aria-selected', 'true')
    await user.click(tab('operations'))
    expect(loc()).toBe('/transfers') // the nav item the visited route belongs to, never its id
    expect(localStorage.getItem(LAST_PAGE_KEY.analytics)).toBe('/analytics/stock?period=30d')
  })

  test('m3 — no or invalid stored pages fall back to Overview / Summary', async () => {
    const user = userEvent.setup()
    renderShell('/orders')
    localStorage.setItem(LAST_PAGE_KEY.analytics, '/analytics/not-a-page?period=7d')
    await user.click(tab('analytics'))
    expect(loc()).toBe('/analytics/summary')
    localStorage.setItem(LAST_PAGE_KEY.operations, 'https://evil.example/')
    await user.click(tab('operations'))
    expect(loc()).toBe('/overview')
  })

  test('m4 — clicking the active mode does nothing', async () => {
    const user = userEvent.setup()
    renderShell('/orders?order=1')
    await user.click(tab('operations'))
    expect(loc()).toBe('/orders?order=1')
  })

  test('m5 — keyboard: roving tabindex, arrows / Home / End move focus only, Enter activates; arrows mirror in RTL', async () => {
    const user = userEvent.setup()
    renderShell('/overview')
    expect(tab('operations')).toHaveAttribute('tabindex', '0')
    expect(tab('analytics')).toHaveAttribute('tabindex', '-1')
    act(() => tab('operations').focus())
    await user.keyboard('{ArrowRight}')
    expect(tab('analytics')).toHaveFocus()
    expect(loc()).toBe('/overview') // manual activation
    await user.keyboard('{Home}')
    expect(tab('operations')).toHaveFocus()
    await user.keyboard('{End}')
    expect(tab('analytics')).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(loc()).toBe('/analytics/summary')

    await user.keyboard('{ArrowRight}') // no wrap at the end
    expect(tab('analytics')).toHaveFocus()

    // RTL: Operations is on the right, so ArrowLeft moves toward Analytics.
    document.documentElement.setAttribute('dir', 'rtl')
    act(() => tab('operations').focus())
    await user.keyboard('{ArrowRight}')
    expect(tab('operations')).toHaveFocus()
    await user.keyboard('{ArrowLeft}')
    expect(tab('analytics')).toHaveFocus()
  })

  test('m6 — rail: two stacked icons, vertical arrows, a tooltip on focus', async () => {
    const user = userEvent.setup()
    localStorage.setItem(SIDEBAR_KEY, 'collapsed')
    renderShell('/overview')
    expect(screen.getByRole('tablist')).toHaveAttribute('aria-orientation', 'vertical')
    act(() => tab('operations').focus())
    expect(screen.getByRole('tooltip')).toHaveTextContent(en.nav.modes.operations)
    await user.keyboard('{ArrowDown}')
    expect(tab('analytics')).toHaveFocus()
    expect(screen.getByRole('tooltip')).toHaveTextContent(en.nav.analytics)
    await user.keyboard('{ArrowUp}')
    expect(tab('operations')).toHaveFocus()
  })

  test('m7 — the old Analytics fold state is removed on mount', () => {
    localStorage.setItem('traced-analytics-nav', 'closed')
    renderShell()
    expect(localStorage.getItem('traced-analytics-nav')).toBeNull()
  })

  test('m8 — flag off: no switch, Operations even on an /analytics path', () => {
    vi.stubEnv('VITE_ANALYTICS_ENABLED', 'false')
    renderShell('/analytics/summary')
    expect(screen.queryByTestId('mode-switch')).toBeNull()
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
    expect(hrefs(nav())).toContain('/orders')
  })

  test('m9 — the switch sits at the top of the sidebar (and so of the phone drawer), before the nav list', () => {
    renderShell('/analytics/summary')
    const sw = within(nav()).getByTestId('mode-switch')
    expect(sw.compareDocumentPosition(screen.getByRole('tabpanel')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(sw.className).not.toMatch(/(^|\s)min-\[900px\]:flex-col(\s|$)/) // a full-width row while expanded
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
