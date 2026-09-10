import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp, { Disconnected } from '../embedded/EmbeddedApp'

/**
 * Closes the disconnect-then-reconnect bug's visible half: a merchant disconnects a
 * store from Traced settings, then opens/refreshes the embedded app in Shopify admin.
 * Before this fix, the embedded token-exchange call (POST /api/v1/embedded/token-exchange)
 * silently re-linked the store — see ShopifyOAuthService.acquireOrRefreshViaSessionToken()'s
 * disconnected guard (409 SHOPIFY_STORE_DISCONNECTED). This file proves the frontend half:
 * on that 409, the embedded surface must show a Disconnected empty state — never the
 * dashboard, never a "Connected" badge — mirroring embeddedNotLinked.test.tsx's pattern
 * for the sibling NOT_PROVISIONED case.
 */

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

/** Real 409 shape — matches ApiExceptionHandler.handleShopifyDisconnected()'s ReauthErrorBody. */
function mockFetchDisconnected() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/embedded/token-exchange')) {
      return jsonResponse(409, {
        error: 'SHOPIFY_STORE_DISCONNECTED',
        message: 'Store is disconnected — reconnect via Traced settings to resume sync',
        shop: 'test10-zrnkwiwc.myshopify.com',
      })
    }
    // The four data endpoints still resolve normally underneath — the fix must hide them
    // regardless of what they return, not because they also fail.
    if (url.includes('/embedded/stores/status')) {
      return jsonResponse(200, [{ shop_domain: 'test10-zrnkwiwc.myshopify.com', status: 'disconnected', import_status: 'completed', last_sync_at: '2026-09-09T10:00:00Z' }])
    }
    return jsonResponse(200, {})
  }))
}

function renderEmbedded() {
  return render(
    <PolarisProvider i18n={polarisEn}>
      <EmbeddedApp />
    </PolarisProvider>,
  )
}

describe('EmbeddedApp — disconnected (SHOPIFY_STORE_DISCONNECTED)', () => {
  const originalHref = window.location.href

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

  test('renders the Disconnected empty state, not the dashboard, on 409 SHOPIFY_STORE_DISCONNECTED', async () => {
    mockFetchDisconnected()
    renderEmbedded()

    expect(await screen.findByText('This store is disconnected from Traced')).toBeInTheDocument()

    // The exact reported symptom: no "Connected" badge, no live-looking dashboard —
    // even though the underlying store-status data (still 'disconnected', but present)
    // resolves successfully underneath.
    expect(screen.queryByText('Connected')).not.toBeInTheDocument()
    expect(screen.queryByText('test10-zrnkwiwc.myshopify.com')).not.toBeInTheDocument()
  })

  test('is a render, not a redirect — window.location is never navigated', async () => {
    mockFetchDisconnected()
    renderEmbedded()
    await screen.findByText('This store is disconnected from Traced')

    expect(window.location.href).toBe(originalHref)
  })

  test('points the merchant to Traced settings — no reconnect action inside the embedded surface', async () => {
    mockFetchDisconnected()
    const user = userEvent.setup()
    renderEmbedded()
    await screen.findByText('This store is disconnected from Traced')

    const reconnectLink = screen.getByRole('link', { name: 'Reconnect in Traced →' })
    expect(reconnectLink).toHaveAttribute('href', 'https://app.tracedtech.com/settings')

    // There is exactly one link on this screen (the Traced deep link) — no button or
    // control that attempts to reconnect from inside the embedded surface itself.
    expect(screen.getAllByRole('link')).toHaveLength(1)

    // Real interaction path — must not throw.
    await user.click(reconnectLink)
  })

  test('an unrecognized 409 body falls through to linked, not a crash', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/embedded/token-exchange')) return jsonResponse(409, { error: 'SOMETHING_ELSE' })
      if (url.includes('/stores/status')) {
        return jsonResponse(200, [{ shop_domain: 'linked.myshopify.com', status: 'connected', import_status: 'idle', last_sync_at: null }])
      }
      if (url.includes('/inventory/summary')) return jsonResponse(200, { groupA: [], groupB: [] })
      if (url.includes('/orders/daily-counts')) return jsonResponse(200, [])
      if (url.includes('/exceptions')) return jsonResponse(200, { count: 0, exceptions: [] })
      return jsonResponse(404, {})
    }))
    renderEmbedded()

    expect(await screen.findByText('linked.myshopify.com')).toBeInTheDocument()
    expect(screen.queryByText('This store is disconnected from Traced')).not.toBeInTheDocument()
  })
})

describe('Disconnected — AR copy (explicit lang prop, never auto-selected in production)', () => {
  afterEach(() => {
    cleanup()
    document.documentElement.dir  = 'ltr'
    document.documentElement.lang = 'en'
  })

  test('renders the Disconnected empty state in Arabic with dir="rtl"', async () => {
    render(
      <PolarisProvider i18n={polarisEn}>
        <Disconnected lang="ar" />
      </PolarisProvider>,
    )

    expect(await screen.findByText('هذا المتجر غير متصل بـ Traced')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'إعادة الاتصال في Traced ←' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(document.documentElement.lang).toBe('ar')
  })
})

describe('EmbeddedApp — linked (control case, unchanged happy path)', () => {
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

  test('a healthy token-exchange (204) never shows Disconnected', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/embedded/token-exchange')) return jsonResponse(204, {})
      if (url.includes('/stores/status')) {
        return jsonResponse(200, [{ shop_domain: 'linked.myshopify.com', status: 'connected', import_status: 'idle', last_sync_at: null }])
      }
      if (url.includes('/inventory/summary')) return jsonResponse(200, { groupA: [], groupB: [] })
      if (url.includes('/orders/daily-counts')) return jsonResponse(200, [])
      if (url.includes('/exceptions')) return jsonResponse(200, { count: 0, exceptions: [] })
      return jsonResponse(404, {})
    }))

    renderEmbedded()

    expect(await screen.findByText('linked.myshopify.com')).toBeInTheDocument()
    expect(screen.queryByText('This store is disconnected from Traced')).not.toBeInTheDocument()
  })
})
