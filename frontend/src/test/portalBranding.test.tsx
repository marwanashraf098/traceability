import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import SettingsPage from '../pages/settings/SettingsPage'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import type { PortalSettings } from '../api'
import type { PortalConfig } from '../portal/api'
import * as fonts from '../portal/fonts'

// Returns portal P1 — Settings → Branding (logo upload / remove, font) and the portal's font.
// design/Traced_portal_branding_dc.html.

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const LOGO_INFO = { contentType: 'image/png', sizeBytes: 4200, width: 600, height: 180, version: 'abc123abc123abc1' }

let settings: PortalSettings
let calls: Array<{ method: string; url: string; body?: unknown }>
let uploadReply: { status: number; body: unknown }

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
    blob: async () => new Blob(['x'], { type: 'image/png' }),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/tenant/portal-settings/logo') && method === 'DELETE') {
    settings = { ...settings, logo: null }
    return json(settings)
  }
  if (url.endsWith('/tenant/portal-settings/logo')) {
    return settings.logo ? json({}) : json(null, 404)
  }
  if (url.includes('/tenant/portal-settings') && method === 'PUT') {
    settings = { ...settings, ...body }
    return json(settings)
  }
  if (url.includes('/tenant/portal-settings')) return json(settings)
  if (url.includes('/variants?')) return json({ items: [], total: 0 })
  return json({})
}

/** XMLHttpRequest stand-in: the logo upload (progress + JSON reply). */
class FakeXhr {
  static sent: Array<{ method: string; url: string; file: File | null }> = []
  status = 0
  responseText = ''
  upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  withCredentials = false
  private method = ''
  private url = ''
  open(method: string, url: string) { this.method = method; this.url = url }
  setRequestHeader() {}
  abort() { this.onabort?.() }
  send(form: FormData) {
    const file = form.get('file') as File | null
    FakeXhr.sent.push({ method: this.method, url: this.url, file })
    setTimeout(() => {
      this.upload.onprogress?.({ lengthComputable: true, loaded: file?.size ?? 0, total: file?.size ?? 0 })
      this.status = uploadReply.status
      this.responseText = JSON.stringify(uploadReply.body)
      if (uploadReply.status === 200) settings = uploadReply.body as PortalSettings
      this.onload?.()
    }, 0)
  }
}

beforeEach(() => {
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30,
    logoUrl: null, brandColor: '#0F766E', policyText: null, pickupBooking: false, portalPickupBooking: false,
    returnLocationId: null, returnLocationName: null, font: 'cairo', storeName: 'Nour Studio', logo: null,
  }
  calls = []
  FakeXhr.sent = []
  uploadReply = { status: 200, body: { ...settings, logo: LOGO_INFO } }
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function loadedSettings() {
  const user = userEvent.setup({ applyAccept: false })
  renderWithProviders(
    <Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
    { initialEntries: ['/settings?tab=portal'] },
  )
  await screen.findByDisplayValue('nourstudio')
  return user
}

const png = (name = 'nour-logo.png', size = 1800) => new File([new Uint8Array(size)], name, { type: 'image/png' })

describe('Settings → Returns portal → Branding', () => {
  test('upload saves at once, shows the logo and the preview uses it; Remove falls back with a toast', async () => {
    const user = await loadedSettings()
    expect(screen.getByTestId('logo-dropzone')).toHaveTextContent('Drag your logo here, or choose a file')
    expect(screen.getByTestId('preview-en-wordmark')).toHaveTextContent('Nour Studio')

    await user.upload(screen.getByTestId('logo-file-input'), png())
    expect(await screen.findByTestId('logo-saved')).toHaveTextContent('nour-logo.png')
    expect(screen.getByTestId('logo-saved')).toHaveTextContent('PNG · 600 × 180')
    expect(screen.getByTestId('logo-saved-note')).toHaveTextContent('Logo saved — your returns page shows it now.')
    expect(FakeXhr.sent).toHaveLength(1)
    expect(FakeXhr.sent[0]).toMatchObject({ method: 'PUT', url: '/api/v1/tenant/portal-settings/logo' })
    expect(FakeXhr.sent[0].file?.name).toBe('nour-logo.png')
    // Saved without "Save changes" — which stays disabled (nothing else changed).
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    await waitFor(() => expect(screen.getByTestId('preview-en-logo')).toHaveAttribute('src', 'data:image/png;base64,eA=='))
    expect(screen.getByTestId('preview-ar-logo')).toHaveAttribute('src', 'data:image/png;base64,eA==')

    await user.click(screen.getByRole('button', { name: 'Remove' }))
    expect(await screen.findByText('Logo removed')).toBeInTheDocument()
    expect(calls.some(c => c.method === 'DELETE' && c.url.endsWith('/tenant/portal-settings/logo'))).toBe(true)
    expect(screen.getByTestId('logo-dropzone')).toBeInTheDocument()
    expect(screen.getByTestId('preview-en-wordmark')).toHaveTextContent('Nour Studio')
  })

  test('wrong type and too big are refused before any upload; a server error code is shown', async () => {
    const user = await loadedSettings()
    await user.upload(screen.getByTestId('logo-file-input'), new File(['GIF89a'], 'logo.gif', { type: 'image/gif' }))
    expect(screen.getByTestId('logo-error')).toHaveTextContent('“logo.gif” isn\'t a PNG, JPG or WebP image. Choose another file.')
    await user.upload(screen.getByTestId('logo-file-input'), png('banner.png', 3.4 * 1024 * 1024))
    expect(screen.getByTestId('logo-error')).toHaveTextContent('“banner.png” is 3.4 MB. Use a file up to 2 MB.')
    expect(FakeXhr.sent).toHaveLength(0)

    uploadReply = { status: 400, body: { field: 'logo', error: 'LOGO_PIXELS', message: 'x' } }
    await user.upload(screen.getByTestId('logo-file-input'), png('huge.png'))
    expect(await screen.findByTestId('logo-error'))
      .toHaveTextContent('This image is too large to process (over 40 megapixels). Use a smaller image.')
    expect(FakeXhr.sent).toHaveLength(1)
    expect(screen.queryByTestId('logo-saved')).not.toBeInTheDocument()
  })

  test('font: each option in its own font; choosing one updates the preview and Save sends it', async () => {
    const user = await loadedSettings()
    const picker = screen.getByTestId('font-picker')
    const options = within(picker).getAllByRole('radio')
    expect(options.map(o => o.getAttribute('aria-label')))
      .toEqual(['Cairo', 'Tajawal', 'IBM Plex Sans Arabic', 'Almarai', 'Readex Pro'])
    expect(within(picker).getByRole('radio', { name: 'Cairo' })).toBeChecked()
    const tajawalCard = within(picker).getByRole('radio', { name: 'Tajawal' }).closest('label')!
    expect(tajawalCard.style.fontFamily).toContain('Tajawal')
    expect(tajawalCard).toHaveTextContent('ابدأ الإرجاع · ابحث عن طلبي')
    // The Arabic sample carries its family inline — the app's [dir="rtl"] rule would force Cairo on it.
    expect(within(tajawalCard).getByText('ابدأ الإرجاع · ابحث عن طلبي').style.fontFamily).toContain('Tajawal')

    await user.click(within(picker).getByRole('radio', { name: 'Tajawal' }))
    expect(screen.getByTestId('portal-preview')).toHaveAttribute('data-font', 'tajawal')
    expect(screen.getByTestId('preview-ar').style.getPropertyValue('--pp-font')).toContain("'Tajawal'")
    // Tajawal has no 600: its semibold is 700, never synthesized.
    expect(screen.getByTestId('preview-ar').style.getPropertyValue('--pp-w600')).toBe('700')

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.endsWith('/tenant/portal-settings'))).toBe(true))
    const put = calls.filter(c => c.method === 'PUT' && c.url.endsWith('/tenant/portal-settings')).pop()!
    expect(put.body).toMatchObject({ slug: 'nourstudio', font: 'tajawal' })
    expect(await screen.findByText('Returns portal settings saved')).toBeInTheDocument()
  })
})

// ── The portal ───────────────────────────────────────────────────────────────

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 14,
  reasonCodes: ['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other'],
  logoUrl: null, brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false,
}

function renderPortal(config: PortalConfig, lang: PortalLang) {
  vi.stubGlobal('fetch', vi.fn((url: string) => (url.endsWith('/config') ? json(config) : json({}, 404))))
  return render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="nourstudio" /></I18nextProvider>)
}

describe('Portal — the store font', () => {
  test.each([['en', 'Start a return'], ['ar', 'ابدأ الإرجاع']] as const)(
    '%s: the chosen family on the root (both languages), only that family loaded; wordmark in it', async (lang, heading) => {
      const load = vi.spyOn(fonts, 'loadPortalFont')
      const { container } = renderPortal({ ...CONFIG, font: 'readex-pro' }, lang)
      await screen.findByRole('heading', { name: heading })
      const root = container.querySelector('.pp-root') as HTMLElement
      expect(root.style.getPropertyValue('--pp-font')).toContain("'Readex Pro Variable'")
      expect(root.style.getPropertyValue('--pp-w600')).toBe('600')
      expect(load.mock.calls.map(c => c[0])).toEqual(['readex-pro'])
      // No logo → the store-name wordmark, which inherits the root's font.
      const wordmark = container.querySelector('.pp-wordmark') as HTMLElement
      expect(wordmark).toHaveTextContent('Nour Studio')
      expect(wordmark.style.fontFamily).toBe('')
      load.mockRestore()
    })

  test('an uploaded logo renders from the app; Almarai maps 600 → 700; missing font → Cairo', async () => {
    const { container, unmount } = renderPortal(
      { ...CONFIG, font: 'almarai', logoUrl: '/api/v1/portal/nourstudio/logo?v=abc123abc123abc1' }, 'en')
    await screen.findByRole('heading', { name: 'Start a return' })
    expect(container.querySelector('img.pp-logo')).toHaveAttribute('src', '/api/v1/portal/nourstudio/logo?v=abc123abc123abc1')
    expect((container.querySelector('.pp-root') as HTMLElement).style.getPropertyValue('--pp-w600')).toBe('700')
    unmount()

    const older = renderPortal(CONFIG, 'ar')   // a backend without "font"
    await screen.findByRole('heading', { name: 'ابدأ الإرجاع' })
    expect((older.container.querySelector('.pp-root') as HTMLElement).style.getPropertyValue('--pp-font')).toContain("'Cairo Variable'")
  })
})
