import { describe, test, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppProvider as PolarisProvider } from '@shopify/polaris'
import polarisEn from '@shopify/polaris/locales/en.json'
import EmbeddedApp, { NotLinked } from '../embedded/EmbeddedApp'

/**
 * Option A (2026-09-04): a cold Shopify-side install must render EmbeddedApp's NotLinked
 * empty state, not redirect (window.top navigation) and not the four dashboard sections'
 * "Could not load..." error Banners. See ShopifyOAuthService.path2()/LinkOutcome.NOT_LINKED
 * and EmbeddedApp.tsx's linkStatus state.
 *
 * No react-i18next/i18next here (reverted 2026-09-04 — see notLinkedCopy.ts): the embedded
 * bundle carries its own local EN/AR copy now, not the shared locale tree. Production
 * EmbeddedApp always renders NotLinked's 'en' default (no locale signal exists to pick
 * 'ar' from) — the EN cases below go through the full cold-install flow via EmbeddedApp
 * itself; the AR case renders the exported NotLinked component directly with an explicit
 * lang prop, since nothing in production ever selects it automatically.
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

/** Real 401 NOT_PROVISIONED shape — matches ShopifySessionTokenFilter.rejectNotProvisioned(). */
function mockFetchColdInstall() {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/embedded/token-exchange')) {
      return jsonResponse(401, { error: 'NOT_PROVISIONED' })
    }
    // The four data endpoints hit the same auth filter — also 401, but with the
    // generic body shape (no "error" field), matching production for a non-token-exchange
    // call under the same filter.
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

describe('EmbeddedApp — cold install (NOT_PROVISIONED)', () => {
  const originalHref = window.location.href

  beforeEach(() => {
    // App Bridge CDN global — normally injected by embedded.html before React mounts.
    ;(globalThis as unknown as { shopify: { idToken(): Promise<string> } }).shopify = {
      idToken: async () => 'fake-session-token',
    }
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    delete (globalThis as unknown as { shopify?: unknown }).shopify
  })

  test('renders the NotLinked empty state, not the dashboard, on NOT_PROVISIONED (EN)', async () => {
    mockFetchColdInstall()
    renderEmbedded()

    expect(await screen.findByText("This store isn't connected to Traced")).toBeInTheDocument()

    // The four dashboard sections' error banners must never appear.
    expect(screen.queryByText('Could not load connection status')).not.toBeInTheDocument()
    expect(screen.queryByText('Could not load inventory summary')).not.toBeInTheDocument()
    expect(screen.queryByText('Could not load order activity')).not.toBeInTheDocument()
    expect(screen.queryByText('Could not load exceptions')).not.toBeInTheDocument()

    // No pricing/payment language anywhere in the not-linked copy.
    const body = document.body.textContent ?? ''
    expect(body).not.toMatch(/pay|price|pricing|\$|EGP|subscription/i)
  })

  test('is a render, not a redirect — window.location is never navigated', async () => {
    mockFetchColdInstall()
    renderEmbedded()
    await screen.findByText("This store isn't connected to Traced")

    expect(window.location.href).toBe(originalHref)
  })

  test('renders neutral copy that directs new users to tracedtech.com and existing users to open Traced', async () => {
    mockFetchColdInstall()
    const user = userEvent.setup()
    renderEmbedded()
    await screen.findByText("This store isn't connected to Traced")

    const newAccountLink = screen.getByRole('link', { name: 'tracedtech.com' })
    expect(newAccountLink).toHaveAttribute('href', 'https://tracedtech.com')

    const openTracedLink = screen.getByRole('link', { name: 'Open Traced →' })
    expect(openTracedLink).toHaveAttribute('href', 'https://app.tracedtech.com')

    // Real interaction path (not just a static assertion) — must not throw.
    await user.click(openTracedLink)
  })
})

describe('NotLinked — AR copy (explicit lang prop, never auto-selected in production)', () => {
  afterEach(() => {
    cleanup()
    document.documentElement.dir  = 'ltr'
    document.documentElement.lang = 'en'
  })

  test('renders the NotLinked empty state in Arabic with dir="rtl"', async () => {
    render(
      <PolarisProvider i18n={polarisEn}>
        <NotLinked lang="ar" />
      </PolarisProvider>,
    )

    expect(await screen.findByText('هذا المتجر غير مرتبط بحساب Traced')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'افتح Traced ←' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(document.documentElement.lang).toBe('ar')

    // No pricing/payment language in the Arabic copy either.
    const body = document.body.textContent ?? ''
    expect(body).not.toMatch(/\$|EGP/i)
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
    expect(screen.queryByText("This store isn't connected to Traced")).not.toBeInTheDocument()
  })
})
