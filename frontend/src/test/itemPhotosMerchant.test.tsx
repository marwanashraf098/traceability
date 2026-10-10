import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ItemPhotos from '../pages/exchangesRefunds/ItemPhotos'
import SettingsPage from '../pages/settings/SettingsPage'
import type { PortalSettings, ReturnRequestItem } from '../api'
import { imgAllowed, imgSrcOf } from './cspContract'

// P3 — merchant side (design/Traced_portal_photos_dc.html a + c): the drawer's photo strip +
// lightbox (authed fetch → data: URL), removed states, and Settings → "Require photos".

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>
let settings: PortalSettings

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
    blob: async () => new Blob([String.fromCharCode(0xff, 0xd8)], { type: 'image/jpeg' }),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = typeof opts.body === 'string' ? JSON.parse(opts.body) : undefined
  calls.push({ method, url, body })
  if (url.includes('/photos/gone')) return json(null, 410)
  if (url.includes('/photos/')) return json(null, 200)
  if (url.includes('/tenant/portal-settings') && method === 'PUT') { settings = { ...settings, ...body }; return json(settings) }
  if (url.includes('/tenant/portal-settings')) return json(settings)
  if (url.includes('/variants?')) return json({ items: [], total: 0 })
  return json({})
}

beforeEach(() => {
  calls = []
  settings = {
    slug: 'thesnouts', enabled: true, autoApprove: false, returnWindowDays: 14, logoUrl: null, brandColor: null,
    policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
    font: 'cairo', storeName: 'The Snouts', logo: null, refundMethods: [], requirePhotos: false,
  }
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(() => { vi.unstubAllGlobals() })

function item(extra: Partial<ReturnRequestItem> = {}): ReturnRequestItem {
  return {
    id: 'item-1', pieceId: null, shortCode: null, variantId: 'v', productTitle: 'Flipped Pants', variantTitle: 'Black · XL',
    imageUrl: null, reasonCode: 'damaged', active: true, itemStatus: 'awaiting',
    photos: [{ id: 'ph-1', width: 1600, height: 1200 }, { id: 'ph-2', width: 1200, height: 1600 }, { id: 'ph-3', width: 1600, height: 900 }],
    photosRemoved: null, ...extra,
  } as ReturnRequestItem
}

describe('drawer photo strip + lightbox', () => {
  test('thumbnails come from the authed endpoint as data: images the app CSP allows', async () => {
    const sources = imgSrcOf('app.tracedtech.com')
    renderWithProviders(<ItemPhotos requestId="rr-1" item={item()} />)
    await waitFor(() => expect(screen.getAllByTestId('item-photo-thumb')).toHaveLength(3))
    for (const img of screen.getAllByTestId('item-photo-thumb')) {
      const src = img.getAttribute('src')!
      expect(src.startsWith('data:image/jpeg;base64,')).toBe(true)
      expect(imgAllowed('app.tracedtech.com', sources, src), `img-src ${sources.join(' ')}`).toBe(true)
    }
    expect(calls.filter(c => /\/return-requests\/rr-1\/photos\/ph-\d$/.test(c.url))).toHaveLength(3)
  })

  test('lightbox: opens on a thumbnail, next / previous wrap, arrow keys, Esc closes', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ItemPhotos requestId="rr-1" item={item()} />)
    await waitFor(() => expect(screen.getAllByTestId('item-photo-thumb')).toHaveLength(3))
    await user.click(screen.getByRole('button', { name: 'Photo 2 of 3, Flipped Pants' }))
    const box = screen.getByRole('dialog')
    expect(within(box).getByTestId('lightbox-position')).toHaveTextContent('Flipped Pants · 2 of 3')
    expect(within(box).getByRole('button', { name: 'Close' })).toHaveFocus()
    await user.click(within(box).getByRole('button', { name: 'Next photo' }))
    expect(within(box).getByTestId('lightbox-position')).toHaveTextContent('3 of 3')
    await user.click(within(box).getByRole('button', { name: 'Next photo' }))
    expect(within(box).getByTestId('lightbox-position')).toHaveTextContent('1 of 3')
    await user.keyboard('{ArrowLeft}')
    expect(within(box).getByTestId('lightbox-position')).toHaveTextContent('3 of 3')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  test('removed: retention date / privacy request; no photos at all → nothing', () => {
    const r1 = renderWithProviders(<ItemPhotos requestId="rr-1" item={item({ photos: [], photosRemoved: { at: '2026-11-07T10:00:00Z', reason: 'retention' } })} />)
    expect(screen.getByTestId('photos-removed')).toHaveTextContent('Photos removed on Nov 7, 2026 — 90 days after the request ended.')
    r1.unmount()
    const r2 = renderWithProviders(<ItemPhotos requestId="rr-1" item={item({ photos: [], photosRemoved: { at: '2026-11-07T10:00:00Z', reason: 'privacy' } })} />)
    expect(screen.getByTestId('photos-removed')).toHaveTextContent('Photos removed (privacy request).')
    r2.unmount()
    renderWithProviders(<ItemPhotos requestId="rr-1" item={item({ photos: [], photosRemoved: null })} />)
    expect(screen.queryByTestId('item-photos')).toBeNull()
    expect(screen.queryByTestId('photos-removed')).toBeNull()
  })
})

describe('Settings → Require photos', () => {
  test('switch saves requirePhotos; untouched → not sent', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Routes><Route path="/settings" element={<SettingsPage />} /></Routes>, { initialEntries: ['/settings?tab=portal'] })
    await screen.findByDisplayValue('thesnouts')
    const sw = screen.getByRole('switch', { name: 'Require photos' })
    expect(sw).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('Customers add 1–3 photos of each item they return. Helps you spot damage before the courier collects it.'))
      .toBeInTheDocument()
    await user.click(sw)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    expect(calls.filter(c => c.method === 'PUT').pop()!.body).toMatchObject({ requirePhotos: true })

    await user.type(screen.getByLabelText('Return policy'), 'x')
    await user.click(await screen.findByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(calls.filter(c => c.method === 'PUT')).toHaveLength(2))
    expect(calls.filter(c => c.method === 'PUT').pop()!.body).not.toHaveProperty('requirePhotos')
  })
})
