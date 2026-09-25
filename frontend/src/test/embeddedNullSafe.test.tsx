import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp from '../embedded/EmbeddedApp'
import { statusLabel } from '../embedded/statusLabels'

/**
 * Regression (2026-09-25, Shopify App Store review): /embedded/exceptions returned
 * type=null for every exception (EmbeddedController read the wrong map keys), and
 * fmtLabel(null) threw "Cannot read properties of null (reading 'replace')" inside
 * ExceptionsSection's .map — unmounting the whole embedded app to a white page.
 * The backend key fix is covered by EmbeddedExceptionsPayloadTest; this pins the
 * frontend half: a null label field renders "—", never throws.
 *
 * Same mock pattern as embeddedTabs.test.tsx — real RTL render, real response shapes.
 */

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    }),
  })
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

/** The exact shape the pre-fix endpoint served the reviewer: one LOW exception, type/subjectKey null. */
function mockFetchNullException() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/embedded/token-exchange'))        return jsonResponse(204, {})
    if (url.includes('/embedded/stores/status')) {
      return jsonResponse(200, [{ shop_domain: 'reviewer.myshopify.com', status: 'connected', import_status: 'completed', last_sync_at: null }])
    }
    if (url.includes('/embedded/inventory/summary'))     return jsonResponse(200, { groupA: [], groupB: [] })
    if (url.includes('/embedded/orders/daily-counts'))   return jsonResponse(200, [])
    if (url.includes('/embedded/exceptions')) {
      return jsonResponse(200, { count: 1, exceptions: [{ type: null, severity: 'LOW', subjectKey: null }] })
    }
    if (url.includes('/embedded/orders/funnel')) {
      return jsonResponse(200, { newCount: 0, picking: 0, packed: 0, courier: 0, delivered: 0 })
    }
    if (url.includes('/embedded/overview/late-to-pack')) return jsonResponse(200, { overdue: 0, over48: 0 })
    if (url.includes('/embedded/orders/list'))           return jsonResponse(200, [])
    return jsonResponse(404, {})
  }))
}

describe('EmbeddedApp — null label fields never crash the Overview', () => {
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

  test('an exception with type=null renders as "—" and the rest of the Overview stays up', async () => {
    mockFetchNullException()
    render(
      <PolarisProvider i18n={polarisEn}>
        <EmbeddedApp />
      </PolarisProvider>,
    )

    expect(await screen.findByText('Open Exceptions')).toBeInTheDocument()
    expect(await screen.findByText('LOW')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText('reviewer.myshopify.com')).toBeInTheDocument()
    expect(screen.getByText('Inventory')).toBeInTheDocument()
  })

  test('statusLabel(null/undefined) returns "—" instead of throwing', () => {
    expect(statusLabel(null)).toBe('—')
    expect(statusLabel(undefined)).toBe('—')
    expect(statusLabel('status.new')).toBe('New')
  })
})
