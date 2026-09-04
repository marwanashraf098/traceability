import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within } from '@testing-library/react'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp from '../embedded/EmbeddedApp'

/**
 * Build-spec (embedded Overview + Orders tabs, 2026-09-04): Polaris Tabs wrapping the
 * linked-state dashboard. Same mock pattern as embeddedNotLinked.test.tsx — real RTL
 * render, real response shapes, no shallow rendering.
 *
 * Polaris's <Tabs> always mounts a hidden width-probe (TabMeasurer) alongside the real
 * tab list — its buttons carry the SAME accessible name/role="tab" but an id suffixed
 * "Measurer" (see node_modules/@shopify/polaris .../Tabs/components/TabMeasurer/
 * TabMeasurer.js). The real, interactive tablist only mounts after an internal
 * requestAnimationFrame measurement pass, and only it carries role="tablist". Every
 * tab lookup below goes through the tablist, not a bare getByRole('tab', ...), to avoid
 * clicking the inert measurer button.
 */

async function realTablist() {
  return await screen.findByRole('tablist')
}

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })

  // jsdom has no real layout engine — offsetWidth/getBoundingClientRect always report 0.
  // Polaris's <Tabs> measures both to decide how many tabs fit inline before collapsing
  // the rest behind a "More views" disclosure popover (see TabMeasurer.js's
  // handleMeasurement); with everything reporting 0 width it collapses every non-selected
  // tab into that popover. A wide-enough fixed width here keeps both tabs inline, matching
  // real-browser behavior for a two-tab bar.
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, value: 1000 })
  Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ width: 200, height: 40, top: 0, left: 0, right: 200, bottom: 40, x: 0, y: 0, toJSON() {} }),
  })
})

function jsonResponse(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as Response)
}

const ORDER_ROW = {
  id: 'order-1',
  number: '#EMB-1001',
  isExchange: false,
  notTraced: false,
  customerName: 'Nour Traced',
  customerPhone: '01099998888',
  codAmount: 450,
  placedAt: '2026-09-03T10:00:00Z',
  primaryKey: 'status.in_transit',
  tone: 'INFO',
  fulfillmentKey: 'status.fulfilled',
  fulfillmentTone: 'SUCCESS',
}

/** Full happy-path mock — every embedded endpoint answers with a real response shape. */
function mockFetchLinked() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/embedded/token-exchange'))        return jsonResponse(204, {})
    if (url.includes('/embedded/stores/status')) {
      return jsonResponse(200, [{ shop_domain: 'linked.myshopify.com', status: 'connected', import_status: 'idle', last_sync_at: null }])
    }
    if (url.includes('/embedded/inventory/summary'))       return jsonResponse(200, { groupA: [], groupB: [] })
    if (url.includes('/embedded/orders/daily-counts'))     return jsonResponse(200, [])
    if (url.includes('/embedded/exceptions'))              return jsonResponse(200, { count: 0, exceptions: [] })
    if (url.includes('/embedded/orders/funnel')) {
      return jsonResponse(200, { newCount: 1, picking: 0, packed: 2, courier: 3, delivered: 4 })
    }
    if (url.includes('/embedded/overview/late-to-pack'))   return jsonResponse(200, { overdue: 0, over48: 0 })
    if (url.includes('/embedded/orders/list'))             return jsonResponse(200, [ORDER_ROW])
    return jsonResponse(404, {})
  }))
}

function mockFetchColdInstall() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/embedded/token-exchange')) return jsonResponse(401, { error: 'NOT_PROVISIONED' })
    return jsonResponse(401, {})
  }))
}

function renderEmbedded() {
  return render(
    <PolarisProvider i18n={polarisEn}>
      <EmbeddedApp />
    </PolarisProvider>,
  )
}

describe('EmbeddedApp — Overview/Orders tabs (linked)', () => {
  beforeEach(() => {
    ;(globalThis as unknown as { shopify: { idToken(): Promise<string> } }).shopify = {
      idToken: async () => 'fake-session-token',
    }
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    delete (globalThis as unknown as { shopify?: unknown }).shopify
  })

  test('both tabs render when linkStatus is linked', async () => {
    mockFetchLinked()
    renderEmbedded()

    const tablist = await realTablist()
    expect(within(tablist).getByRole('tab', { name: 'Overview' })).toBeInTheDocument()
    expect(within(tablist).getByRole('tab', { name: 'Orders' })).toBeInTheDocument()
  })

  test('Overview tab shows the four existing sections plus the two new tiles', async () => {
    mockFetchLinked()
    renderEmbedded()

    await screen.findByText('linked.myshopify.com')
    expect(screen.getByText('Inventory')).toBeInTheDocument()
    expect(screen.getByText('Order Activity — last 14 days')).toBeInTheDocument()
    expect(screen.getByText('Open Exceptions')).toBeInTheDocument()
    expect(await screen.findByText("Today's order flow")).toBeInTheDocument()
    expect(screen.getByText('Late to pack')).toBeInTheDocument()
  })

  test('Orders tab renders derived status badges from a real /orders/list response', async () => {
    mockFetchLinked()
    const { container } = renderEmbedded()

    const tablist = await realTablist()
    within(tablist).getByRole('tab', { name: 'Orders' }).click()

    expect(await screen.findByText('#EMB-1001')).toBeInTheDocument()
    expect(screen.getByText('Nour Traced')).toBeInTheDocument()
    expect(screen.getByText('01099998888')).toBeInTheDocument()
    // fulfillmentKey 'status.fulfilled' -> 'Fulfilled', primaryKey 'status.in_transit' -> 'In transit'
    // — both from the local statusLabels map, proving the server-derived keys render
    // through it rather than showing raw dotted keys.
    expect(screen.getByText('Fulfilled')).toBeInTheDocument()
    expect(screen.getByText('In transit')).toBeInTheDocument()
    expect(screen.getByText('450 EGP')).toBeInTheDocument()
    // No raw status.* key leaked to the DOM unresolved.
    expect(container.textContent).not.toMatch(/status\.\w/)
  })

  test('Orders tab has no actionable controls — no buttons, no write-capable links', async () => {
    mockFetchLinked()
    renderEmbedded()

    const tablist = await realTablist()
    within(tablist).getByRole('tab', { name: 'Orders' }).click()
    await screen.findByText('#EMB-1001')

    const panel = screen.getByRole('tabpanel')

    // Polaris's own <DataTable> unconditionally renders "Scroll table left/right"
    // buttons as a horizontal-overflow affordance (no prop disables them) — they only
    // scroll the viewport, they don't act on data, navigate, filter, or paginate, so
    // they're excluded here as known DataTable chrome, not a violation of "no actionable
    // controls." Every OTHER button, and every link/textbox, must be absent: no row
    // click, no drawer trigger, no search/filter controls, no pagination.
    const buttons = within(panel).queryAllByRole('button')
      .filter(b => !/scroll table/i.test(b.getAttribute('aria-label') ?? ''))
    expect(buttons).toHaveLength(0)
    expect(within(panel).queryAllByRole('link')).toHaveLength(0)
    expect(within(panel).queryAllByRole('textbox')).toHaveLength(0)
  })
})

describe('EmbeddedApp — cold install still shows NotLinked, no tabs', () => {
  beforeEach(() => {
    ;(globalThis as unknown as { shopify: { idToken(): Promise<string> } }).shopify = {
      idToken: async () => 'fake-session-token',
    }
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    delete (globalThis as unknown as { shopify?: unknown }).shopify
  })

  test('NOT_PROVISIONED renders NotLinked, never the tabbed dashboard', async () => {
    mockFetchColdInstall()
    renderEmbedded()

    expect(await screen.findByText("This store isn't connected to Traced")).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Overview' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Orders' })).not.toBeInTheDocument()
  })
})
