import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import SettingsPage from '../pages/settings/SettingsPage'
import type { PortalSettings } from '../api'

// P1 fix — Settings → Branding shows the uploaded logo under the REAL app CSP (a blob: image was
// refused on app.tracedtech.com), and a logo that fails to load falls back instead of rendering
// nothing (preview → store-name wordmark; thumbnail → neutral placeholder).

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** img-src of the app.tracedtech.com HTTPS server block in deploy/nginx.conf — the CSP the settings page runs under. */
function appImgSrc(): string[] {
  const conf = readFileSync(resolve(__dirname, '../../../deploy/nginx.conf'), 'utf8')
  const blocks = conf.split(/\n\s*server\s*\{/)
  const app = blocks.find(b => /server_name\s+app\.tracedtech\.com;/.test(b) && /listen\s+443/.test(b))
  expect(app, 'app.tracedtech.com 443 server block').toBeDefined()
  const csp = app!.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"/)
  expect(csp, 'CSP header in the app server block').not.toBeNull()
  const img = csp![1].split(';').map(d => d.trim()).find(d => d.startsWith('img-src'))
  expect(img, 'img-src directive').toBeDefined()
  return img!.split(/\s+/).slice(1)
}

/** Would this CSP source list allow an <img> with this src on https://app.tracedtech.com? */
function allowedBy(sources: string[], src: string): boolean {
  const url = new URL(src, 'https://app.tracedtech.com/settings')
  if (url.protocol === 'data:' || url.protocol === 'blob:') return sources.includes(url.protocol)
  if (url.origin === 'https://app.tracedtech.com') return sources.includes("'self'")
  return sources.some(s => s.startsWith('https://') && url.href.startsWith(s))
}

let settings: PortalSettings

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function backend(url: string) {
  if (url.endsWith('/tenant/portal-settings/logo')) {
    return Promise.resolve({
      ok: true, status: 200, statusText: '',
      headers: { get: (k: string) => (k === 'content-type' ? 'image/png' : null) },
      blob: async () => new Blob([PNG_BYTES], { type: 'image/png' }),
    })
  }
  if (url.includes('/tenant/portal-settings')) return json(settings)
  if (url.includes('/variants?')) return json({ items: [], total: 0 })
  return json({})
}

beforeEach(() => {
  settings = {
    slug: 'thesnouts', enabled: false, autoApprove: false, returnWindowDays: 14,
    logoUrl: null, brandColor: '#0F766E', policyText: null, pickupBooking: false, portalPickupBooking: false,
    returnLocationId: null, returnLocationName: null, font: 'cairo', storeName: 'The Snouts',
    logo: { contentType: 'image/png', sizeBytes: 7305, width: 600, height: 600, version: '0da8a09b8207',
      url: '/api/v1/portal/thesnouts/logo?v=0da8a09b8207' } as PortalSettings['logo'],
  }
  // What a real browser hands back for URL.createObjectURL (jsdom has none).
  globalThis.URL.createObjectURL = vi.fn(() => 'blob:https://app.tracedtech.com/6f1c2a7e-0000-4000-8000-000000000000')
  globalThis.URL.revokeObjectURL = vi.fn()
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(() => { vi.unstubAllGlobals() })

async function renderBranding() {
  renderWithProviders(
    <Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
    { initialEntries: ['/settings?tab=portal'] },
  )
  await screen.findByDisplayValue('thesnouts')
}

describe('Settings → Branding: the uploaded logo under the real CSP', () => {
  test('the logo the thumbnail and both previews use is allowed by app.tracedtech.com img-src', async () => {
    const sources = appImgSrc()
    await renderBranding()
    const en = await screen.findByTestId('preview-en-logo')
    const ar = screen.getByTestId('preview-ar-logo')
    const thumb = screen.getByTestId('uploaded-logo-preview')
    for (const img of [en, ar, thumb]) {
      const src = img.getAttribute('src')!
      expect(allowedBy(sources, src), `img-src ${sources.join(' ')} must allow ${src.slice(0, 40)}…`).toBe(true)
    }
    // The bytes the authenticated endpoint returned, not a URL the CSP or auth could block.
    expect(en.getAttribute('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
  })

  test('a preview logo that fails to load falls back to the store-name wordmark', async () => {
    await renderBranding()
    fireEvent.error(await screen.findByTestId('preview-en-logo'))
    fireEvent.error(screen.getByTestId('preview-ar-logo'))
    expect(screen.getByTestId('preview-en-wordmark')).toHaveTextContent('The Snouts')
    expect(screen.getByTestId('preview-ar-wordmark')).toHaveTextContent('The Snouts')
    expect(screen.queryByTestId('preview-en-logo')).not.toBeInTheDocument()
  })

  test('a thumbnail that fails to load shows a neutral placeholder, not alt text', async () => {
    await renderBranding()
    fireEvent.error(await screen.findByTestId('uploaded-logo-preview'))
    await waitFor(() => expect(screen.queryByTestId('uploaded-logo-preview')).not.toBeInTheDocument())
    expect(screen.getByTestId('logo-placeholder')).toBeInTheDocument()
    // Replace / Remove still there — the merchant can fix it.
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument()
  })
})
