import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp from '../embedded/EmbeddedApp'

/**
 * Linked-store control case: a healthy token exchange shows the dashboard, never onboarding.
 * The cold-install (NOT_PROVISIONED) screens moved to embeddedOnboarding.test.tsx (Build D —
 * onboarding replaced the old "This store isn't connected to Traced" empty state).
 */

// Polaris's AppProvider (Polaris is only used in the embedded bundle — no other test file
// needs this) renders a MediaQueryProvider that calls window.matchMedia, which jsdom does
// not implement. Local to this file, not the shared setup.ts, since no other test uses Polaris.
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

function renderEmbedded() {
  return render(
    <PolarisProvider i18n={polarisEn}>
      <EmbeddedApp />
    </PolarisProvider>,
  )
}

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

  test('a healthy token-exchange (204) never shows NotLinked', async () => {
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
    expect(screen.queryByText('Welcome to Traced')).not.toBeInTheDocument()
  })
})
